package com.cadence.service;

import com.cadence.api.AuthExceptions;
import com.cadence.api.InterestExceptions;
import com.cadence.api.RbacExceptions;
import com.cadence.domain.Invitation;
import com.cadence.domain.InterestRequest;
import com.cadence.domain.InterestRequestStatus;
import com.cadence.domain.RecruiterNotificationType;
import com.cadence.domain.Role;
import com.cadence.repository.InterestRequestRepository;
import com.cadence.security.PiiCrypto;
import net.logstash.logback.argument.StructuredArguments;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * F70 Join / Express-Interest service — captures a public no-login submission (encrypted, deduped, no-oracle),
 * serves the Admin review queue, and converts a request to an invitation via the existing {@link InvitationService}.
 *
 * <p><b>No-oracle submit (FR-005/R8, the load-bearing control):</b> {@link #submit} performs NO member- or
 * invitation-existence lookup — those cases are indistinguishable BY CONSTRUCTION (there is no email-keyed
 * invitation finder and none is needed). The only branch is the dedup: {@code insert} catch
 * {@link DuplicateKeyException} -> re-resolve the open row + coalesce-update; both branches return the identical
 * 202 response. The notify side effect is deferred OFF the response path (new-insert only). The no-oracle
 * guarantee is STRUCTURAL (a single code path with no existence branch), not a wall-clock assertion.
 *
 * <p><b>PII discipline:</b> logs carry ids + {@code .name()} Strings only — never name/email/organization/message,
 * never the enum to {@code kv} (the F01.1 logstash crash). Exception messages are reduced to PII-free cause-class
 * strings at the boundary (the F22 lesson).
 */
@Service
public class InterestRequestService {

    private static final Logger log = LoggerFactory.getLogger(InterestRequestService.class);

    /** Open (re-actionable) statuses — carry openEmailHash, counted as "active". */
    private static final List<InterestRequestStatus> OPEN =
        List.of(InterestRequestStatus.NEW, InterestRequestStatus.REVIEWED);

    private final InterestRequestRepository repo;
    private final MongoTemplate mongo;
    private final InvitationService invitations;
    private final InterestRateLimiter rateLimiter;
    private final RecruiterNotificationService notifications;
    private final PiiCrypto crypto;
    private final InterestProperties props;
    private final Clock clock;
    private final CsvInjectionEscaper escaper;

    public InterestRequestService(InterestRequestRepository repo, MongoTemplate mongo,
                                  InvitationService invitations, InterestRateLimiter rateLimiter,
                                  RecruiterNotificationService notifications, PiiCrypto crypto,
                                  InterestProperties props, Clock clock, CsvInjectionEscaper escaper) {
        this.repo = repo;
        this.mongo = mongo;
        this.invitations = invitations;
        this.rateLimiter = rateLimiter;
        this.notifications = notifications;
        this.crypto = crypto;
        this.props = props;
        this.clock = clock;
        this.escaper = escaper;
    }

    /** A validated submit command (the controller has already bean-validated lengths/format). */
    public record SubmitCommand(String name, String email, String organization, String message,
                                String honeypot, Long formRenderedAtMillis) {}

    // ===================================== submit (US1) =================================================

    /**
     * Capture an interest submission. Resolves the workspace from config (never from input), applies the
     * bot-heuristic + the layered rate limit, then runs the single dedup code path. Returns silently (neutral
     * accept) on a tripped honeypot / sub-min-fill — no row, no oracle.
     */
    public void submit(SubmitCommand cmd, String clientIp) {
        // 1) Bot heuristic — neutral accept with NO row (no oracle). Honeypot non-empty OR a sub-minFill render.
        if (isBot(cmd)) {
            return;
        }
        // 2) Layered rate limit: best-effort per-source (layer 1) + the durable per-workspace ceiling (layer 2).
        if (!rateLimiter.tryAcquire(clientIp)) {
            throw new InterestExceptions.RateLimitedException();
        }
        String ws = props.getDefaultWorkspaceId(); // FR-019 — server-resolved, NEVER from submitter input
        Instant windowStart = Instant.now(clock).minus(props.getWorkspaceWindow());
        if (repo.countByWorkspaceIdAndSubmittedAtAfter(ws, windowStart) >= props.getMaxPerWorkspacePerWindow()) {
            throw new InterestExceptions.RateLimitedException();
        }

        Instant now = Instant.now(clock);
        String emailHash = crypto.emailHash(cmd.email());

        // 3) The ONLY branch: dedup insert vs coalesce. NO member/invitation existence check (FR-005/R8) — the
        //    member/pending-invite/unknown cases are indistinguishable by construction; both branches return
        //    identically and the notify side effect is deferred off the response path (new-insert only).
        boolean newInsert;
        InterestRequest row = new InterestRequest();
        row.setWorkspaceId(ws);
        row.setName(cmd.name());
        row.setEmail(cmd.email());
        row.setEmailHash(emailHash);
        row.setOpenEmailHash(emailHash);
        row.setOrganization(blankToNull(cmd.organization()));
        row.setMessage(blankToNull(cmd.message()));
        row.setStatus(InterestRequestStatus.NEW);
        row.setSubmittedAt(now);
        row.setUpdatedAt(now);
        try {
            repo.insert(row);
            newInsert = true;
        } catch (DuplicateKeyException e) {
            // An open request for this email already exists (the unique partial {workspaceId,openEmailHash}).
            // Coalesce: refresh the latest claimed details + updatedAt; do NOT create a second row, do NOT
            // re-notify. A null re-resolve (e.g. it just terminated in a race) is a benign no-op (no oracle).
            coalesce(ws, emailHash, cmd, now);
            newInsert = false;
        }

        // 4) Deferred side-effect seam — AFTER the response decision, new-insert only (US3 plugs in notify).
        if (newInsert) {
            onNewRequest(ws);
        }
    }

    private void coalesce(String ws, String emailHash, SubmitCommand cmd, Instant now) {
        InterestRequest open = repo.findByWorkspaceIdAndOpenEmailHash(ws, emailHash).orElse(null);
        if (open == null) {
            return; // raced to terminal between the insert failure and this read — no-op, no oracle
        }
        mongo.updateFirst(
            Query.query(Criteria.where("_id").is(open.getId()).and("workspaceId").is(ws)),
            new Update()
                .set("name", cmd.name())
                .set("organization", blankToNull(cmd.organization()))
                .set("message", blankToNull(cmd.message()))
                .set("updatedAt", now),
            InterestRequest.class);
    }

    /**
     * Deferred side effect for a genuinely new open request (US3): a best-effort, value-free in-app notification
     * to the workspace admins. 3-arg notify with a null candidateId (the {@code ATS_SYNC_FAILED} precedent). Never
     * emails the submitter (structural anti-amplification). Best-effort — a failure never affects the response.
     */
    private void onNewRequest(String workspaceId) {
        try {
            notifications.notify(workspaceId, null, RecruiterNotificationType.INTEREST_REQUEST);
        } catch (RuntimeException e) {
            // Best-effort: the response is already decided. Reduce the cause to a PII-free cause-class string at
            // the boundary (the F22 lesson) — never the raw exception message, which could carry PII.
            log.warn("interest notification failed {} {}",
                StructuredArguments.kv("workspaceId", workspaceId),
                StructuredArguments.kv("errorType", e.getClass().getSimpleName()));
        }
    }

    private boolean isBot(SubmitCommand cmd) {
        if (cmd.honeypot() != null && !cmd.honeypot().isBlank()) {
            return true; // honeypot field filled — a bot
        }
        Long renderedAt = cmd.formRenderedAtMillis();
        if (renderedAt != null && props.getMinFillMillis() > 0) {
            long elapsed = Instant.now(clock).toEpochMilli() - renderedAt;
            // Negative (clock skew / future timestamp) or impossibly fast fill -> treat as a bot.
            return elapsed >= 0 && elapsed < props.getMinFillMillis();
        }
        return false;
    }

    // ===================================== admin queue (US2) ===========================================

    /**
     * List the workspace's requests for a status filter. {@code open} (default triage) EXCLUDES REVIEWED;
     * {@code reviewed} returns only REVIEWED; {@code all} returns everything; otherwise an exact status
     * (invited/dismissed). Recent-first.
     */
    public List<InterestRequest> list(String workspaceId, String statusFilter) {
        List<InterestRequestStatus> statuses = resolveFilter(statusFilter);
        return repo.findByWorkspaceIdAndStatusInOrderBySubmittedAtDesc(workspaceId, statuses);
    }

    /** The result of a CSV export — the rendered injection-safe CSV String + the row count for the audit. */
    public record ExportResult(String csv, int rowCount) {}

    /**
     * Render the workspace's interest-request queue to injection-safe CSV (closes the deferred SC-012/FR-010 export
     * half). Workspace-scoped, same status-filter semantics as {@link #list} (default {@code open} EXCLUDES
     * REVIEWED). Every free-text cell (name, email, organization, message) passes through {@link CsvInjectionEscaper}
     * at the export boundary (the {@code DashboardService.renderCsv} precedent) so a {@code =cmd|...}/{@code +SUM(1)}/
     * {@code @foo}/leading-{@code -} payload cannot execute in a spreadsheet; status/submittedAt are safe enums/instants
     * but are still escaper-quoted for RFC-4180 consistency. The caller (controller) streams the result and audits
     * the egress; this method never persists or logs any cell value (PII — the F70 discipline).
     */
    public ExportResult exportCsv(String workspaceId, String statusFilter, String actorMemberId) {
        List<InterestRequestStatus> statuses = resolveFilter(statusFilter);
        List<InterestRequest> rows = repo.findByWorkspaceIdAndStatusInOrderBySubmittedAtDesc(workspaceId, statuses);
        StringBuilder sb = new StringBuilder();
        sb.append("name,email,organization,message,status,submittedAt\n");
        for (InterestRequest r : rows) {
            csvRow(sb,
                r.getName(),
                r.getEmail(),
                r.getOrganization(),
                r.getMessage(),
                r.getStatus() == null ? "" : r.getStatus().name(),
                r.getSubmittedAt() == null ? "" : r.getSubmittedAt().toString());
        }
        log.info("interest requests exported {} {}",
            StructuredArguments.kv("workspaceId", workspaceId),
            StructuredArguments.kv("rows", rows.size()));
        return new ExportResult(sb.toString(), rows.size());
    }

    /** Append one CSV record — every free-text cell neutralized at the export boundary (SC-012/FR-010). */
    private void csvRow(StringBuilder sb, String name, String email, String organization, String message,
                        String status, String submittedAt) {
        sb.append(escaper.escapeForSpreadsheet(name == null ? "" : name)).append(',')
            .append(escaper.escapeForSpreadsheet(email == null ? "" : email)).append(',')
            .append(escaper.escapeForSpreadsheet(organization == null ? "" : organization)).append(',')
            .append(escaper.escapeForSpreadsheet(message == null ? "" : message)).append(',')
            .append(escaper.escapeForSpreadsheet(status)).append(',')
            .append(escaper.escapeForSpreadsheet(submittedAt)).append('\n');
    }

    private List<InterestRequestStatus> resolveFilter(String filter) {
        String f = filter == null ? "open" : filter.trim().toLowerCase();
        return switch (f) {
            case "open" -> List.of(InterestRequestStatus.NEW); // default triage EXCLUDES REVIEWED (FR-013/US2 Sc.2)
            case "reviewed" -> List.of(InterestRequestStatus.REVIEWED);
            case "invited" -> List.of(InterestRequestStatus.INVITED);
            case "dismissed" -> List.of(InterestRequestStatus.DISMISSED);
            case "all" -> List.of(InterestRequestStatus.values());
            default -> List.of(InterestRequestStatus.NEW);
        };
    }

    /** Guarded CAS NEW -> REVIEWED. 404 if absent/other-workspace; 409 if not in NEW. */
    public void review(String workspaceId, String id, String actorMemberId) {
        requireExists(workspaceId, id);
        InterestRequest won = mongo.findAndModify(
            Query.query(Criteria.where("_id").is(id).and("workspaceId").is(workspaceId)
                .and("status").is(InterestRequestStatus.NEW)),
            transition(InterestRequestStatus.REVIEWED, actorMemberId),
            FindAndModifyOptions.options().returnNew(true), InterestRequest.class);
        if (won == null) {
            throw new InterestExceptions.ConflictException();
        }
    }

    /** Guarded CAS {NEW,REVIEWED} -> DISMISSED (+ $unset openEmailHash). 404/409. */
    public void dismiss(String workspaceId, String id, String actorMemberId) {
        requireExists(workspaceId, id);
        InterestRequest won = mongo.findAndModify(
            Query.query(Criteria.where("_id").is(id).and("workspaceId").is(workspaceId)
                .and("status").in(OPEN)),
            transition(InterestRequestStatus.DISMISSED, actorMemberId).unset("openEmailHash"),
            FindAndModifyOptions.options().returnNew(true), InterestRequest.class);
        if (won == null) {
            throw new InterestExceptions.ConflictException();
        }
    }

    /** The result of an invite attempt — the new invitation id (or null if the email was already a member). */
    public record InviteResult(String invitationId, boolean alreadyMember) {}

    /**
     * Convert a NEW/REVIEWED request to an invitation (FR-014/FR-015/FR-016). Single-winner claim CAS
     * {NEW,REVIEWED} -> INVITED, then {@link InvitationService#create}; on success set {@code invitationId} +
     * $unset openEmailHash; on {@link AuthExceptions.AlreadyMemberException} the request STAYS terminal (resolved)
     * and returns {@code alreadyMember} — no second invitation, no 500, no public leak. Role + workspace + actor
     * are from the session (never submitter input).
     */
    public InviteResult invite(String workspaceId, String id, Role role, String actorMemberId, String ip) {
        InterestRequest existing = requireExists(workspaceId, id);
        // Claim the open state atomically (single-winner across two concurrent admins -> exactly one create).
        InterestRequest claimed = mongo.findAndModify(
            Query.query(Criteria.where("_id").is(id).and("workspaceId").is(workspaceId)
                .and("status").in(OPEN)),
            transition(InterestRequestStatus.INVITED, actorMemberId),
            FindAndModifyOptions.options().returnNew(true), InterestRequest.class);
        if (claimed == null) {
            throw new InterestExceptions.ConflictException(); // already terminal / lost the race (FR-016)
        }
        // The claimed row's email is converter-decrypted on read; use it for the invitation address.
        String email = claimed.getEmail() != null ? claimed.getEmail() : existing.getEmail();
        InterestRequestStatus priorStatus = existing.getStatus(); // the open state to restore on a transient failure
        String emailHash = claimed.getEmailHash() != null ? claimed.getEmailHash() : existing.getEmailHash();
        Invitation inv;
        try {
            inv = invitations.create(workspaceId, actorMemberId, email, role, ip);
        } catch (AuthExceptions.AlreadyMemberException e) {
            // The email is already an active member — no second access path. The claim already consumed the open
            // state, so the request stays terminal (resolved). Drop the open hash so it falls out of the dedup set.
            mongo.updateFirst(
                Query.query(Criteria.where("_id").is(id).and("workspaceId").is(workspaceId)),
                new Update().unset("openEmailHash").set("updatedAt", Instant.now(clock)),
                InterestRequest.class);
            log.info("interest request invite resolved as already-member {} {}",
                StructuredArguments.kv("interestRequestId", id),
                StructuredArguments.kv("workspaceId", workspaceId));
            return new InviteResult(null, true);
        } catch (RuntimeException e) {
            // Any OTHER failure (transient Mongo / email transport) leaves the claimed row terminal INVITED with
            // no invitationId — stranded and non-re-actionable. Revert the claim atomically (guarded on the
            // still-uninvited INVITED state so a concurrent winner that DID create is never clobbered), restoring
            // the prior open status + openEmailHash, then re-throw so the admin sees a non-200 and can retry.
            mongo.findAndModify(
                Query.query(Criteria.where("_id").is(id).and("workspaceId").is(workspaceId)
                    .and("status").is(InterestRequestStatus.INVITED).and("invitationId").is(null)),
                new Update().set("status", priorStatus).set("openEmailHash", emailHash)
                    .set("updatedAt", Instant.now(clock)),
                FindAndModifyOptions.options().returnNew(true), InterestRequest.class);
            log.warn("interest request invite failed, claim reverted {} {} {}",
                StructuredArguments.kv("interestRequestId", id),
                StructuredArguments.kv("workspaceId", workspaceId),
                StructuredArguments.kv("errorType", e.getClass().getSimpleName()));
            throw e;
        }
        mongo.updateFirst(
            Query.query(Criteria.where("_id").is(id).and("workspaceId").is(workspaceId)),
            new Update().set("invitationId", inv.getId()).unset("openEmailHash")
                .set("updatedAt", Instant.now(clock)),
            InterestRequest.class);
        log.info("interest request invited {} {}",
            StructuredArguments.kv("interestRequestId", id),
            StructuredArguments.kv("workspaceId", workspaceId));
        return new InviteResult(inv.getId(), false);
    }

    /**
     * Admin erasure (FR-022): {@code $set "[ERASED]"} on the encrypted PII fields (the converter encrypts the
     * non-null marker — NEVER {@code $unset} an encrypted field, the F03 ClassCastException trap) + {@code $unset}
     * the plain hashes (no longer discoverable by email). Idempotent — a re-run / unknown id returns the same
     * shape (no oracle). The {@code CandidateErasureService.wipe} precedent.
     */
    public void erase(String workspaceId, String id) {
        requireExists(workspaceId, id);
        mongo.updateFirst(
            Query.query(Criteria.where("_id").is(id).and("workspaceId").is(workspaceId)),
            new Update()
                .set("name", CandidateErasureService.ERASED_MARKER)
                .set("email", CandidateErasureService.ERASED_MARKER)
                .set("organization", CandidateErasureService.ERASED_MARKER)
                .set("message", CandidateErasureService.ERASED_MARKER)
                .unset("emailHash")
                .unset("openEmailHash")
                .set("updatedAt", Instant.now(clock)),
            InterestRequest.class);
    }

    // ===================================== retention (Polish) ==========================================

    /**
     * Hard-delete rows older than the workspace's retention period (FR-021). {@code retentionPeriodDays <= 0}
     * means "unset" -> the configured fallback (NOT immediate delete). Returns the deleted count.
     */
    public long purgeAged(String workspaceId, int retentionPeriodDays) {
        int days = retentionPeriodDays <= 0 ? props.getRetentionFallbackDays() : retentionPeriodDays;
        Instant cutoff = Instant.now(clock).minus(Duration.ofDays(days));
        List<InterestRequest> aged = repo.findByWorkspaceIdAndSubmittedAtBefore(workspaceId, cutoff);
        if (aged.isEmpty()) {
            return 0L;
        }
        List<String> ids = aged.stream().map(InterestRequest::getId).toList();
        long deleted = mongo.remove(Query.query(Criteria.where("_id").in(ids).and("workspaceId").is(workspaceId)),
            InterestRequest.class).getDeletedCount();
        if (deleted > 0) {
            log.info("interest requests purged {} {}",
                StructuredArguments.kv("workspaceId", workspaceId),
                StructuredArguments.kv("deleted", deleted));
        }
        return deleted;
    }

    // ===================================== helpers =====================================================

    /** Scoped existence: a cross-workspace / absent id -> ScopedNotFoundException -> indistinguishable 404. */
    private InterestRequest requireExists(String workspaceId, String id) {
        return repo.findByWorkspaceIdAndId(workspaceId, id)
            .orElseThrow(RbacExceptions.ScopedNotFoundException::new);
    }

    private Update transition(InterestRequestStatus to, String actorMemberId) {
        Instant now = Instant.now(clock);
        return new Update().set("status", to).set("lastActorMemberId", actorMemberId)
            .set("actionedAt", now).set("updatedAt", now);
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
