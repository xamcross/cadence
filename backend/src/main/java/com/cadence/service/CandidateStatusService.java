package com.cadence.service;

import com.cadence.api.CandidateStatusDtos.CandidateStatusView;
import com.cadence.api.CandidateStatusDtos.DisplayState;
import com.cadence.api.CandidateStatusDtos.PublishStatusRequest;
import com.cadence.api.CandidateStatusDtos.RecruiterStatusResponse;
import com.cadence.api.CandidateStatusExceptions;
import com.cadence.api.RbacExceptions;
import com.cadence.api.SchedulingExceptions;
import com.cadence.config.AuthProperties;
import com.cadence.config.StatusPageProperties;
import com.cadence.domain.Candidate;
import com.cadence.domain.CandidateAuditOutcome;
import com.cadence.domain.CandidateEventType;
import com.cadence.domain.CandidateStatusOutcome;
import com.cadence.domain.ErasureReasonCode;
import com.cadence.domain.ErasureState;
import com.cadence.repository.CandidateRepository;
import com.cadence.security.SecureTokens;
import com.cadence.security.TokenHasher;
import com.cadence.repository.WorkspaceConfigRepository;
import com.mongodb.client.result.UpdateResult;
import net.logstash.logback.argument.StructuredArguments;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * F30 Candidate Status Page service — the single home for view, publish, rotate, recruiter-read, the
 * link-derivation SPI, and the candidate-initiated erasure intake (research D1–D9). Pure orchestration of
 * existing seams: {@code SecureTokens}/{@code TokenHasher} (mint/hash), the {@code PiiStringConverter} (the
 * status free-text + reversible token encrypt at rest, via {@code MongoTemplate.$set}), {@code CandidateRateLimiter}
 * (per-IP 429), {@code CandidateAuditService}, {@code ErasureRequestService} (intake), and the workspace zone.
 *
 * <p><b>Clock</b> (research D5): injected {@link Clock} (the F01 {@code MutableClock}/{@code AuthTestConfig}
 * pattern). {@code today = LocalDate.ofInstant(Instant.now(clock), workspaceZone)} — NEVER {@code LocalDate.now()}
 * — so the PAST_DATE boundary (SC-013) and the precedence matrix (SC-016) are deterministic under a controlled clock.
 *
 * <p><b>PII discipline (D15)</b>: logs carry ids + {@code .name()} only; the status free text and the raw token
 * never reach a logger / audit / dead-letter. The decrypted status link materializes only in
 * {@link #statusLinkFor} and is handed straight to the merge context / recruiter response — never logged.
 */
@Service
public class CandidateStatusService {

    private static final Logger log = LoggerFactory.getLogger(CandidateStatusService.class);

    private final CandidateRepository candidates;
    private final MongoTemplate mongo;
    private final WorkspaceConfigRepository configs;
    private final TokenHasher hasher;
    private final CandidateAuditService audit;
    private final CandidateRateLimiter rateLimiter;
    private final ErasureRequestService erasureRequests;
    private final AuthProperties authProps;
    private final StatusPageProperties statusProps;
    private final Clock clock;

    public CandidateStatusService(CandidateRepository candidates, MongoTemplate mongo,
                                  WorkspaceConfigRepository configs, TokenHasher hasher,
                                  CandidateAuditService audit, CandidateRateLimiter rateLimiter,
                                  ErasureRequestService erasureRequests, AuthProperties authProps,
                                  StatusPageProperties statusProps, Clock clock) {
        this.candidates = candidates;
        this.mongo = mongo;
        this.configs = configs;
        this.hasher = hasher;
        this.audit = audit;
        this.rateLimiter = rateLimiter;
        this.erasureRequests = erasureRequests;
        this.authProps = authProps;
        this.statusProps = statusProps;
        this.clock = clock;
    }

    // ===================================== Candidate: view (contract A) ==================================

    /**
     * Candidate view (D6): rate-limit (429) → resolve solely by the token hash (no IDOR) → compute the
     * server-side {@code displayState} against the workspace zone → minimal escaped view, no per-view audit
     * (FR-034). Unknown/malformed/erased all throw the SAME {@code StatusNotFoundException} → indistinguishable 404.
     */
    public CandidateStatusView view(String rawToken, String ip) {
        rateLimit(ip);
        Candidate c = resolveActiveByToken(rawToken);
        ZoneId zone = workspaceZone(c.getWorkspaceId());
        DisplayState state = resolveDisplayState(c, zone);
        return toView(c, state, zone);
    }

    private CandidateStatusView toView(Candidate c, DisplayState state, ZoneId zone) {
        String zoneId = zone.getId();
        return switch (state) {
            case TERMINAL -> new CandidateStatusView(state, c.getStatusStage(), c.getStatusNextStep(),
                null, c.getStatusOutcome(), zoneId);
            case PAST_DATE -> new CandidateStatusView(state, c.getStatusStage(), c.getStatusNextStep(),
                c.getStatusExpectedDate(), c.getStatusOutcome(), zoneId);
            case PUBLISHED -> new CandidateStatusView(state, c.getStatusStage(), c.getStatusNextStep(),
                c.getStatusExpectedDate(), c.getStatusOutcome(), zoneId);
            case UNDER_REVIEW -> new CandidateStatusView(state, null, null, null, null, zoneId);
        };
    }

    /**
     * The single precedence resolver (D5, SC-016): TERMINAL &gt; PAST_DATE &gt; PUBLISHED &gt; UNDER_REVIEW.
     * Package-visible + static so {@code DisplayStateResolverTest} can drive it with a controlled "today".
     */
    static DisplayState resolveDisplayState(Candidate c, LocalDate today) {
        CandidateStatusOutcome outcome = c.getStatusOutcome();
        if (c.getStatusPublishedAt() == null) {
            return DisplayState.UNDER_REVIEW;
        }
        if (outcome == CandidateStatusOutcome.COMPLETE_OFFER || outcome == CandidateStatusOutcome.COMPLETE_REJECTED) {
            return DisplayState.TERMINAL;
        }
        // IN_PROGRESS from here.
        LocalDate expected = c.getStatusExpectedDate();
        if (expected != null && expected.isBefore(today)) {
            return DisplayState.PAST_DATE;
        }
        return DisplayState.PUBLISHED;
    }

    private DisplayState resolveDisplayState(Candidate c, ZoneId zone) {
        LocalDate today = LocalDate.ofInstant(Instant.now(clock), zone);
        return resolveDisplayState(c, today);
    }

    // ===================================== Recruiter: publish (contract C) ===============================

    /**
     * Publish (D3/D4): validate the shape (value-free 400), then one atomic {@code updateFirst} of all status
     * fields guarded on {@code erasureState:ACTIVE} (last-valid-write-wins, no partial state, FR-016). The
     * converter encrypts the free-text {@code $set} values (F03 precedent — do NOT pre-encrypt). Then
     * ensure-provision the token (so {@code statusLinkFor} can re-derive the link) and audit {@code STATUS_PUBLISHED}.
     * {@code matchedCount==0} ⇒ missing or erased ⇒ scoped 404 (never publishes onto an erased subject).
     */
    public RecruiterStatusResponse publish(String workspaceId, String candidateId, String actorMemberId,
                                           PublishStatusRequest req) {
        validatePublish(req);
        Instant now = Instant.now(clock);
        Update u = new Update()
            .set("statusOutcome", req.outcome())
            .set("statusStage", trimToNull(req.stage()))
            .set("statusNextStep", trimToNull(req.nextStep()))
            .set("statusExpectedDate", req.outcome() == CandidateStatusOutcome.IN_PROGRESS ? req.expectedDate() : null)
            .set("statusPublishedAt", now)
            .set("statusPublishedByMemberId", actorMemberId);
        UpdateResult r = mongo.updateFirst(
            Query.query(Criteria.where("_id").is(candidateId).and("workspaceId").is(workspaceId)
                .and("erasureState").is(ErasureState.ACTIVE)),
            u, Candidate.class);
        if (r.getMatchedCount() == 0) {
            throw new RbacExceptions.ScopedNotFoundException();
        }
        audit.append(workspaceId, candidateId, CandidateEventType.STATUS_PUBLISHED,
            CandidateAuditOutcome.RECORDED, actorMemberId);
        ensureProvisioned(workspaceId, candidateId, actorMemberId);
        log.info("status published {} {}", StructuredArguments.kv("workspaceId", workspaceId),
            StructuredArguments.kv("candidateId", candidateId));
        return readForRecruiter(workspaceId, candidateId);
    }

    private void validatePublish(PublishStatusRequest req) {
        if (req == null || req.outcome() == null) {
            throw new CandidateStatusExceptions.InvalidStatusPublishException("An outcome is required.");
        }
        boolean blankNext = req.nextStep() == null || req.nextStep().isBlank();
        if (blankNext) {
            throw new CandidateStatusExceptions.InvalidStatusPublishException("A next-step message is required.");
        }
        if (req.outcome() == CandidateStatusOutcome.IN_PROGRESS) {
            if (req.stage() == null || req.stage().isBlank()) {
                throw new CandidateStatusExceptions.InvalidStatusPublishException("A stage is required.");
            }
            if (req.expectedDate() == null) {
                throw new CandidateStatusExceptions.InvalidStatusPublishException("An expected date is required.");
            }
        }
    }

    // ===================================== Recruiter: read + rotate (contracts D/E) ======================

    /** Recruiter read (D/F): decrypted status + the resolved displayState + the current status link. */
    public RecruiterStatusResponse readForRecruiter(String workspaceId, String candidateId) {
        Candidate c = candidates.findByWorkspaceIdAndId(workspaceId, candidateId)
            .filter(x -> x.getErasureState() == ErasureState.ACTIVE)
            .orElseThrow(RbacExceptions.ScopedNotFoundException::new);
        String link = statusLinkFor(workspaceId, candidateId); // lazy-provision (audited) if absent
        ZoneId zone = workspaceZone(workspaceId);
        DisplayState state = resolveDisplayState(c, zone);
        return new RecruiterStatusResponse(state, c.getStatusOutcome(), c.getStatusStage(), c.getStatusNextStep(),
            c.getStatusExpectedDate(), link);
    }

    /** Rotate (D8): mint a fresh token, atomic {@code $set} of both representations, audit, return the new link. */
    public String rotateLink(String workspaceId, String candidateId, String actorMemberId) {
        candidates.findByWorkspaceIdAndId(workspaceId, candidateId)
            .filter(x -> x.getErasureState() == ErasureState.ACTIVE)
            .orElseThrow(RbacExceptions.ScopedNotFoundException::new);
        String raw = SecureTokens.newToken();
        UpdateResult r = setToken(workspaceId, candidateId, raw);
        if (r.getMatchedCount() == 0) {
            throw new RbacExceptions.ScopedNotFoundException();
        }
        audit.append(workspaceId, candidateId, CandidateEventType.STATUS_LINK_ROTATED,
            CandidateAuditOutcome.RECORDED, actorMemberId);
        return linkFromRaw(raw);
    }

    // ===================================== Candidate: erasure submit (contract B) ========================

    /**
     * Candidate erasure intake (D7): rate-limit → resolve the active candidate by token → record an
     * (idempotent) PENDING erasure request reusing the existing {@code CANDIDATE_REQUEST} reason. ALWAYS
     * silent on the outcome to the caller — the controller returns the same 202 ack across {valid, unknown,
     * malformed, erased}, so this is not an existence oracle (FR-023/SC-010). A request is recorded ONLY when
     * the token resolves to an active candidate.
     */
    public void requestErasureByToken(String rawToken, String ip) {
        rateLimit(ip);
        Candidate c;
        try {
            c = resolveActiveByToken(rawToken);
        } catch (CandidateStatusExceptions.StatusNotFoundException e) {
            return; // unknown/malformed/erased — no record, but the caller still returns the constant ack
        }
        erasureRequests.requestErasure(c.getWorkspaceId(), c.getId(), ErasureReasonCode.CANDIDATE_REQUEST);
    }

    // ===================================== SPI: link derivation (contract F) =============================

    /**
     * {@code statusLinkFor} (D9): lazily ensure a token is provisioned (mint + atomic {@code $set} if absent,
     * audited {@code STATUS_LINK_ISSUED} — never a silent credential mint), decrypt {@code statusToken}, and
     * return {@code {spaBaseUrl}{spaStatusBasePath}?token={raw}}. Supplies {@code MergeToken.STATUS_LINK}.
     * The decrypted link is returned to the (authorized) caller only — NEVER logged.
     */
    public String statusLinkFor(String workspaceId, String candidateId) {
        Candidate c = candidates.findByWorkspaceIdAndId(workspaceId, candidateId)
            .filter(x -> x.getErasureState() == ErasureState.ACTIVE)
            .orElseThrow(RbacExceptions.ScopedNotFoundException::new);
        String raw = ensureProvisioned(workspaceId, candidateId, null, c);
        return linkFromRaw(raw);
    }

    private void ensureProvisioned(String workspaceId, String candidateId, String actorMemberId) {
        Candidate c = candidates.findByWorkspaceIdAndId(workspaceId, candidateId).orElse(null);
        if (c != null) {
            ensureProvisioned(workspaceId, candidateId, actorMemberId, c);
        }
    }

    /**
     * Mint + persist a token only if the candidate has none; returns the raw token either way. {@code c} is a
     * repository-loaded entity, so the converter has ALREADY decrypted {@code statusToken} to plaintext — do
     * NOT {@code crypto.decrypt} it again (double-decrypt → AEADBadTagException).
     */
    private String ensureProvisioned(String workspaceId, String candidateId, String actorMemberId, Candidate c) {
        if (c.getStatusToken() != null) {
            return c.getStatusToken();
        }
        String raw = SecureTokens.newToken();
        // Atomic CAS: mint ONLY if the candidate still has no token (and is ACTIVE). A concurrent
        // first-publish / first-link race -> exactly ONE writer matches the {statusTokenHash:null} guard and
        // mints; losers fall through, re-read, and return the winner's persisted token. This prevents the
        // double-mint that would invalidate an already-delivered link (data-model section 5).
        UpdateResult r = mongo.updateFirst(
            Query.query(Criteria.where("_id").is(candidateId).and("workspaceId").is(workspaceId)
                .and("erasureState").is(ErasureState.ACTIVE).and("statusTokenHash").is(null)),
            new Update().set("statusToken", raw).set("statusTokenHash", hasher.hashToken(raw)),
            Candidate.class);
        if (r.getMatchedCount() == 1) {
            audit.append(workspaceId, candidateId, CandidateEventType.STATUS_LINK_ISSUED,
                CandidateAuditOutcome.RECORDED, actorMemberId);
            return raw;
        }
        // Lost the CAS (another writer minted) OR the candidate is now missing/erased: re-read and return the
        // persisted token; a still-tokenless active row should be impossible after the CAS attempt.
        Candidate fresh = candidates.findByWorkspaceIdAndId(workspaceId, candidateId)
            .filter(x -> x.getErasureState() == ErasureState.ACTIVE)
            .orElseThrow(RbacExceptions.ScopedNotFoundException::new);
        if (fresh.getStatusToken() != null) {
            return fresh.getStatusToken();
        }
        throw new RbacExceptions.ScopedNotFoundException();
    }

    /**
     * Atomic {@code $set} of both token representations, guarded on {@code erasureState:ACTIVE}. The converter
     * encrypts {@code statusToken} at rest (we pass plaintext — do NOT pre-encrypt); {@code statusTokenHash}
     * is the HMAC. A concurrent publish/rotate is last-write-wins on this single document.
     */
    private UpdateResult setToken(String workspaceId, String candidateId, String raw) {
        return mongo.updateFirst(
            Query.query(Criteria.where("_id").is(candidateId).and("workspaceId").is(workspaceId)
                .and("erasureState").is(ErasureState.ACTIVE)),
            new Update().set("statusToken", raw).set("statusTokenHash", hasher.hashToken(raw)),
            Candidate.class);
    }

    // ===================================== helpers =======================================================

    /** Resolve solely by the token hash; empty OR not-ACTIVE ⇒ the single indistinguishable not-found. */
    private Candidate resolveActiveByToken(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new CandidateStatusExceptions.StatusNotFoundException();
        }
        return candidates.findByStatusTokenHash(hasher.hashToken(rawToken))
            .filter(c -> c.getErasureState() == ErasureState.ACTIVE)
            .orElseThrow(CandidateStatusExceptions.StatusNotFoundException::new);
    }

    private ZoneId workspaceZone(String workspaceId) {
        return configs.findByWorkspaceId(workspaceId)
            .map(cfg -> cfg.getTimeZone())
            .filter(tz -> tz != null && !tz.isBlank())
            .map(ZoneId::of)
            .orElse(ZoneId.of("UTC"));
    }

    private String linkFromRaw(String raw) {
        return authProps.getSpaBaseUrl() + statusProps.getSpaStatusBasePath() + "?token=" + raw;
    }

    private void rateLimit(String ip) {
        if (!rateLimiter.tryAcquire(ip)) {
            throw new SchedulingExceptions.RateLimitedException();
        }
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
