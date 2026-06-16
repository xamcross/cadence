package com.cadence.service;

import com.cadence.config.EmailDeliveryProperties;
import com.cadence.config.MailConfig.MailSenderSelector;
import com.cadence.domain.AuthEventType;
import com.cadence.domain.Candidate;
import com.cadence.domain.DispatchOutcomeReason;
import com.cadence.domain.DispatchStatus;
import com.cadence.domain.EmailDispatch;
import com.cadence.domain.EmailMessageType;
import com.cadence.domain.RecruiterNotificationType;
import com.cadence.domain.RenderedMessage;
import com.cadence.integration.EmailSender;
import com.cadence.integration.OutboundEmail;
import com.cadence.integration.SendOutcome;
import com.cadence.repository.CandidateRepository;
import com.cadence.repository.EmailDispatchRepository;
import com.cadence.scheduler.DeadLetterService;
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
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/**
 * The single candidate-email dispatch entry point (F22, contract C). {@link #enqueue} inserts a
 * {@code PENDING} outbox row (idempotent on the unique {workspaceId,idempotencyKey} index — a duplicate
 * enqueue returns the existing row, never a second send, T033) and, if the row is due now, drives it
 * through the dispatch core: claim CAS -> gate at claim time -> render (F21) -> transport -> record.
 *
 * <p><b>All status transitions are raw {@code findAndModify} CAS — no {@code @Version} on the row</b>
 * (research D5). The CAS claim {@code {_id,status:PENDING,nextAttemptAt<=now} -> SENDING} makes only one
 * worker transmit; concurrent claimers get {@code matchedCount==0} and no-op. The consent gate is
 * evaluated AFTER winning the claim and on EVERY dispatch (never cached — for scheduled fires too).
 *
 * <p><b>PII discipline (D10)</b>: the row carries ids/instants/enums only; the recipient address lives
 * ONLY in the transient {@link OutboundEmail} (resolved here from the candidate, never persisted). Logs
 * carry ids + {@code .name()} Strings only — never an enum to {@code kv}, never recipient/name/subject/body.
 * Audit payloads are value-free.
 */
@Service
public class EmailDispatchService {

    private static final Logger log = LoggerFactory.getLogger(EmailDispatchService.class);

    private static final String COL_STATUS = "status";
    private static final String COL_NEXT = "nextAttemptAt";
    private static final String COL_UPDATED = "updatedAt";

    /** A renderContextRef is shape-guarded to an ObjectId-hex or a bounded opaque token (DeadLetter precedent). */
    private static final Pattern SAFE_REF = Pattern.compile("^[A-Za-z0-9._\\-]{1,128}$");

    private final EmailDispatchRepository repo;
    private final MongoTemplate mongo;
    private final ContactPermissionGate gate;
    private final EmailTemplateService templates;
    private final CandidateRepository candidates;
    private final MailSenderSelector senders;
    private final EmailSender emailSender;
    private final AuthAuditService audit;
    private final DeadLetterService deadLetters;
    private final RecruiterNotificationService notifications;
    private final EmailDispatchMetrics metrics;
    private final EmailDeliveryProperties props;
    private final Clock clock;

    public EmailDispatchService(EmailDispatchRepository repo, MongoTemplate mongo, ContactPermissionGate gate,
                                EmailTemplateService templates, CandidateRepository candidates,
                                MailSenderSelector senders, EmailSender emailSender, AuthAuditService audit,
                                DeadLetterService deadLetters, RecruiterNotificationService notifications,
                                EmailDispatchMetrics metrics, EmailDeliveryProperties props, Clock clock) {
        this.repo = repo;
        this.mongo = mongo;
        this.gate = gate;
        this.templates = templates;
        this.candidates = candidates;
        this.senders = senders;
        this.emailSender = emailSender;
        this.audit = audit;
        this.deadLetters = deadLetters;
        this.notifications = notifications;
        this.metrics = metrics;
        this.props = props;
        this.clock = clock;
    }

    /** The outcome of an enqueue/dispatch (contract C). All fields are value-free ids/enums. */
    public record DispatchResult(String dispatchId, DispatchStatus status, DispatchOutcomeReason reason,
                                 boolean idempotentDuplicate) {}

    /**
     * Enqueue (and, if due now, run) a candidate email. Idempotent on (workspace,candidate,type,scheduledFor).
     * A future {@code scheduledFor} sits {@code PENDING} until the scheduled worker picks it up (US4).
     */
    public DispatchResult enqueue(String workspaceId, String candidateId, EmailMessageType type,
                                  String stageKey, Instant scheduledFor,
                                  Map<String, String> nonPiiContext, String renderContextRef) {
        Instant now = Instant.now(clock);
        // Workspace-scoped candidate existence (empty -> ScopedNotFoundException -> 404, oracle-free).
        // A present-but-non-contactable candidate is NOT a 404 — it is refused by the gate at dispatch (409).
        candidates.findByWorkspaceIdAndId(workspaceId, candidateId)
            .orElseThrow(com.cadence.api.RbacExceptions.ScopedNotFoundException::new);
        String sk = (stageKey == null || stageKey.isBlank()) ? "BASE" : stageKey;
        String ref = shapeGuard(renderContextRef);
        String key = IdempotencyKeys.dispatchKey(workspaceId, candidateId, type, scheduledFor.toEpochMilli());

        Inserted inserted = insertOrFindExisting(workspaceId, candidateId, type, sk, scheduledFor, ref, key, now);
        EmailDispatch row = inserted.row();
        boolean duplicate = inserted.duplicate();

        // If the row is already terminal (a prior send/refusal), report it idempotently — no second send.
        if (row.getStatus() != DispatchStatus.PENDING) {
            return new DispatchResult(row.getId(), row.getStatus(), row.getLastOutcomeReason(), true);
        }
        // Future-dated rows wait for the scheduler (US4). Immediate rows run inline now.
        if (row.getScheduledFor() != null && row.getScheduledFor().isAfter(now)) {
            return new DispatchResult(row.getId(), DispatchStatus.PENDING, DispatchOutcomeReason.NONE, duplicate);
        }
        DispatchResult ran = dispatch(row.getId(), nonPiiContext);
        return new DispatchResult(ran.dispatchId(), ran.status(), ran.reason(), duplicate || ran.idempotentDuplicate());
    }

    private record Inserted(EmailDispatch row, boolean duplicate) {}

    private Inserted insertOrFindExisting(String workspaceId, String candidateId, EmailMessageType type,
                                          String stageKey, Instant scheduledFor, String ref, String key,
                                          Instant now) {
        EmailDispatch d = new EmailDispatch();
        d.setWorkspaceId(workspaceId);
        d.setCandidateId(candidateId);
        d.setMessageType(type);
        d.setStageKey(stageKey);
        d.setIdempotencyKey(key);
        d.setStatus(DispatchStatus.PENDING);
        d.setScheduledFor(scheduledFor);
        d.setNextAttemptAt(scheduledFor); // initially <= scheduledFor
        d.setLastOutcomeReason(DispatchOutcomeReason.NONE);
        d.setRenderContextRef(ref);
        d.setCreatedAt(now);
        d.setUpdatedAt(now);
        try {
            return new Inserted(repo.insert(d), false); // insert (not save) so a dup hits the unique index
        } catch (DuplicateKeyException e) {
            // The same logical message already exists — the idempotent success it is (T033).
            EmailDispatch existing = repo.findByWorkspaceIdAndIdempotencyKey(workspaceId, key)
                .orElseThrow(() -> e); // extremely unlikely; re-surface if the row vanished mid-race
            return new Inserted(existing, true);
        }
    }

    /**
     * Run one dispatch attempt on a PENDING/due row: claim CAS -> gate -> render -> send -> record.
     * Used inline for immediate sends and by the scheduler for due rows.
     */
    public DispatchResult dispatch(String dispatchId, Map<String, String> nonPiiContext) {
        Instant now = Instant.now(clock);

        // 1) Claim CAS: PENDING + due -> SENDING (only the winner transmits).
        EmailDispatch claimed = mongo.findAndModify(
            Query.query(Criteria.where("_id").is(dispatchId)
                .and(COL_STATUS).is(DispatchStatus.PENDING)
                .and(COL_NEXT).lte(now)),
            new Update().set(COL_STATUS, DispatchStatus.SENDING).set(COL_UPDATED, now).inc("attemptCount", 1),
            FindAndModifyOptions.options().returnNew(true),
            EmailDispatch.class);
        if (claimed == null) {
            // Lost the claim (concurrent winner) or not due — report the current row state idempotently.
            EmailDispatch cur = repo.findById(dispatchId).orElse(null);
            DispatchStatus st = cur == null ? DispatchStatus.PENDING : cur.getStatus();
            DispatchOutcomeReason rs = cur == null ? DispatchOutcomeReason.NONE : cur.getLastOutcomeReason();
            return new DispatchResult(dispatchId, st, rs, true);
        }

        // 2) Consent gate at claim time (re-evaluated every dispatch, never cached).
        ContactPermissionGate.Decision decision = gate.evaluate(claimed.getWorkspaceId(), claimed.getCandidateId());
        if (!decision.permit()) {
            DispatchOutcomeReason reason = mapGateReason(decision.reason());
            terminal(dispatchId, DispatchStatus.REFUSED, reason, now);
            metrics.incRefused();
            audit.record(AuthEventType.EMAIL_DISPATCH_REFUSED, claimed.getWorkspaceId(), claimed.getCandidateId(),
                reason.name(), null);
            notifications.notify(claimed.getWorkspaceId(), claimed.getCandidateId(),
                RecruiterNotificationType.DISPATCH_REFUSED); // FR-008
            log.info("email dispatch refused {} {} {}",
                StructuredArguments.kv("dispatchId", dispatchId),
                StructuredArguments.kv("messageType", claimed.getMessageType().name()),
                StructuredArguments.kv("reason", reason.name()));
            return new DispatchResult(dispatchId, DispatchStatus.REFUSED, reason, false);
        }

        // 3) Resolve the sender (per-workspace F03 credential, else app default, else NO_PROVIDER_CONFIG).
        if (!senders.forWorkspace(claimed.getWorkspaceId()).present()) {
            terminal(dispatchId, DispatchStatus.FAILED, DispatchOutcomeReason.NO_PROVIDER_CONFIG, now);
            metrics.incFailed();
            audit.record(AuthEventType.EMAIL_DISPATCH_FAILED, claimed.getWorkspaceId(), claimed.getCandidateId(),
                DispatchOutcomeReason.NO_PROVIDER_CONFIG.name(), null);
            deadLetters.recordFailure("email-dispatch", new IllegalStateException("no provider config"),
                claimed.getCandidateId());
            notifications.notify(claimed.getWorkspaceId(), claimed.getCandidateId(),
                RecruiterNotificationType.DISPATCH_FAILED); // FR-012
            log.warn("email dispatch failed — no provider config {} {}",
                StructuredArguments.kv("dispatchId", dispatchId),
                StructuredArguments.kv("messageType", claimed.getMessageType().name()));
            return new DispatchResult(dispatchId, DispatchStatus.FAILED, DispatchOutcomeReason.NO_PROVIDER_CONFIG, false);
        }

        // 4) Render (F21) — a render failure is terminal (no broken message ever transmitted).
        RenderedMessage rendered;
        try {
            rendered = templates.renderForSend(claimed.getWorkspaceId(), claimed.getMessageType(),
                claimed.getStageKey(), claimed.getCandidateId(), nonPiiContext);
        } catch (RuntimeException e) {
            terminal(dispatchId, DispatchStatus.FAILED, DispatchOutcomeReason.RENDER_FAILED, now);
            metrics.incFailed();
            audit.record(AuthEventType.EMAIL_DISPATCH_FAILED, claimed.getWorkspaceId(), claimed.getCandidateId(),
                DispatchOutcomeReason.RENDER_FAILED.name(), null);
            // A render exception's MESSAGE may carry template/PII content — the dead-letter only sanitises
            // emails, so never hand it the raw exception. Pass a PII-free summary (the cause class only).
            deadLetters.recordFailure("email-dispatch",
                new IllegalStateException("render_failed: " + e.getClass().getSimpleName()),
                claimed.getCandidateId());
            notifications.notify(claimed.getWorkspaceId(), claimed.getCandidateId(),
                RecruiterNotificationType.DISPATCH_FAILED); // FR-012
            log.warn("email dispatch failed — render error {} {} {}",
                StructuredArguments.kv("dispatchId", dispatchId),
                StructuredArguments.kv("messageType", claimed.getMessageType().name()),
                StructuredArguments.kv("errorType", e.getClass().getSimpleName())); // never the message (PII)
            return new DispatchResult(dispatchId, DispatchStatus.FAILED, DispatchOutcomeReason.RENDER_FAILED, false);
        }

        // 5) Resolve the recipient (decrypted) — lives ONLY here, never persisted, never logged.
        Candidate candidate = candidates.findByWorkspaceIdAndId(claimed.getWorkspaceId(), claimed.getCandidateId())
            .orElse(null);
        if (candidate == null || candidate.getEmail() == null || candidate.getEmail().isBlank()) {
            // The gate already passed, so this is a read race / corrupt row — fail closed (no transmit).
            terminal(dispatchId, DispatchStatus.FAILED, DispatchOutcomeReason.UNAVAILABLE, now);
            metrics.incFailed();
            audit.record(AuthEventType.EMAIL_DISPATCH_FAILED, claimed.getWorkspaceId(), claimed.getCandidateId(),
                DispatchOutcomeReason.UNAVAILABLE.name(), null);
            deadLetters.recordFailure("email-dispatch",
                new IllegalStateException("recipient unresolvable"), claimed.getCandidateId());
            notifications.notify(claimed.getWorkspaceId(), claimed.getCandidateId(),
                RecruiterNotificationType.DISPATCH_FAILED); // FR-012
            return new DispatchResult(dispatchId, DispatchStatus.FAILED, DispatchOutcomeReason.UNAVAILABLE, false);
        }

        // 6) Transmit. Message-ID = the idempotency key (best-effort provider-side dedup hint, D5/T032).
        OutboundEmail message = new OutboundEmail(claimed.getWorkspaceId(), candidate.getEmail(),
            rendered.subject(), rendered.bodyHtml(), claimed.getIdempotencyKey());
        SendOutcome outcome;
        try {
            outcome = emailSender.send(message);
        } catch (RuntimeException e) {
            // An unexpected transport exception is treated as transient (re-queue or cap -> FAILED).
            return afterTransientOrPermanent(claimed, SendOutcome.transientFailure("transport_exception"), e);
        }

        if (outcome.accepted()) {
            Instant sentAt = Instant.now(clock);
            EmailDispatch sent = mongo.findAndModify(
                Query.query(Criteria.where("_id").is(dispatchId).and(COL_STATUS).is(DispatchStatus.SENDING)),
                new Update().set(COL_STATUS, DispatchStatus.SENT)
                    .set("sentAt", sentAt).set(COL_UPDATED, sentAt)
                    .set("providerMessageRef", outcome.providerMessageRef())
                    .set("lastOutcomeReason", DispatchOutcomeReason.NONE),
                FindAndModifyOptions.options().returnNew(true),
                EmailDispatch.class);
            if (sent == null) {
                // The row was no longer SENDING when the accept landed (e.g. a misconfigured reaper marked it
                // SENT_UNCONFIRMED mid-flight). Never report a clean SENT — surface the row's actual status and
                // flag the conflict for operator triage (no resend, the provider already accepted).
                metrics.incReconciliationConflict();
                EmailDispatch cur = repo.findById(dispatchId).orElse(null);
                DispatchStatus st = cur == null ? DispatchStatus.SENT_UNCONFIRMED : cur.getStatus();
                DispatchOutcomeReason rs = cur == null ? DispatchOutcomeReason.NONE : cur.getLastOutcomeReason();
                log.warn("email dispatch reconciliation conflict — accept landed but row no longer SENDING; "
                        + "not reporting clean SENT (no resend) {} {} {}",
                    StructuredArguments.kv("dispatchId", dispatchId),
                    StructuredArguments.kv("messageType", claimed.getMessageType().name()),
                    StructuredArguments.kv("currentStatus", st.name()));
                return new DispatchResult(dispatchId, st, rs, false);
            }
            metrics.incSent();
            audit.record(AuthEventType.EMAIL_DISPATCH_SENT, claimed.getWorkspaceId(), claimed.getCandidateId(),
                claimed.getMessageType().name(), null);
            log.info("email dispatch sent {} {}",
                StructuredArguments.kv("dispatchId", dispatchId),
                StructuredArguments.kv("messageType", claimed.getMessageType().name()));
            return new DispatchResult(dispatchId, DispatchStatus.SENT, DispatchOutcomeReason.NONE, false);
        }
        return afterTransientOrPermanent(claimed, outcome, null);
    }

    /**
     * Classify a failed transport outcome (T034): a transient failure re-queues SENDING->PENDING with
     * backoff until {@code retryMaxAttempts}; a permanent failure (or the cap) goes SENDING->FAILED +
     * dead-letter. {@code cause} is the optional original exception for the dead-letter record.
     */
    private DispatchResult afterTransientOrPermanent(EmailDispatch claimed, SendOutcome outcome, RuntimeException cause) {
        Instant now = Instant.now(clock);
        String dispatchId = claimed.getId();
        boolean canRetry = outcome.transientError() && claimed.getAttemptCount() < props.getRetryMaxAttempts();

        if (canRetry) {
            Instant nextAttempt = now.plus(backoff(claimed.getAttemptCount()));
            mongo.findAndModify(
                Query.query(Criteria.where("_id").is(dispatchId).and(COL_STATUS).is(DispatchStatus.SENDING)),
                new Update().set(COL_STATUS, DispatchStatus.PENDING)
                    .set(COL_NEXT, nextAttempt).set(COL_UPDATED, now)
                    .set("lastOutcomeReason", DispatchOutcomeReason.TRANSPORT_REJECTED),
                EmailDispatch.class);
            log.info("email dispatch transient — requeued {} {} {}",
                StructuredArguments.kv("dispatchId", dispatchId),
                StructuredArguments.kv("messageType", claimed.getMessageType().name()),
                StructuredArguments.kv("attemptCount", claimed.getAttemptCount()));
            return new DispatchResult(dispatchId, DispatchStatus.PENDING, DispatchOutcomeReason.TRANSPORT_REJECTED, false);
        }

        DispatchOutcomeReason reason = outcome.transientError()
            ? DispatchOutcomeReason.RETRY_EXHAUSTED : DispatchOutcomeReason.TRANSPORT_REJECTED;
        terminal(dispatchId, DispatchStatus.FAILED, reason, now);
        metrics.incFailed();
        audit.record(AuthEventType.EMAIL_DISPATCH_FAILED, claimed.getWorkspaceId(), claimed.getCandidateId(),
            reason.name(), null);
        Throwable ex = cause != null ? cause
            : new IllegalStateException("transport rejected: " + outcome.reasonCode());
        deadLetters.recordFailure("email-dispatch", ex, claimed.getCandidateId());
        notifications.notify(claimed.getWorkspaceId(), claimed.getCandidateId(),
            RecruiterNotificationType.DISPATCH_FAILED); // FR-012
        log.warn("email dispatch failed {} {} {}",
            StructuredArguments.kv("dispatchId", dispatchId),
            StructuredArguments.kv("messageType", claimed.getMessageType().name()),
            StructuredArguments.kv("reason", reason.name()));
        return new DispatchResult(dispatchId, DispatchStatus.FAILED, reason, false);
    }

    /** A terminal CAS off SENDING (REFUSED/FAILED) — sets status + reason + updatedAt. */
    private void terminal(String dispatchId, DispatchStatus status, DispatchOutcomeReason reason, Instant now) {
        mongo.findAndModify(
            Query.query(Criteria.where("_id").is(dispatchId).and(COL_STATUS).is(DispatchStatus.SENDING)),
            new Update().set(COL_STATUS, status).set("lastOutcomeReason", reason).set(COL_UPDATED, now),
            EmailDispatch.class);
    }

    /** Exponential backoff with jitter: base * 2^attempt, +/- up to base/2 of jitter. */
    private Duration backoff(int attempt) {
        Duration base = props.getRetryBaseBackoff();
        long capped = Math.min(attempt, 16); // guard against overflow on a runaway attemptCount
        long millis = base.toMillis() * (1L << capped);
        long jitter = base.toMillis() == 0 ? 0 : ThreadLocalRandom.current().nextLong(base.toMillis() / 2 + 1);
        return Duration.ofMillis(millis + jitter);
    }

    private static DispatchOutcomeReason mapGateReason(ContactPermissionGate.Reason r) {
        return switch (r) {
            case NO_BASIS -> DispatchOutcomeReason.NO_BASIS;
            case WITHDRAWN -> DispatchOutcomeReason.WITHDRAWN;
            case ERASED -> DispatchOutcomeReason.ERASED;
            case OVER_RETENTION -> DispatchOutcomeReason.OVER_RETENTION;
            case UNDELIVERABLE -> DispatchOutcomeReason.UNDELIVERABLE;
            case UNAVAILABLE, NONE -> DispatchOutcomeReason.UNAVAILABLE;
        };
    }

    /** A renderContextRef must be an ObjectId-hex or a bounded opaque token, else it is dropped (PII guard). */
    private static String shapeGuard(String ref) {
        if (ref == null || ref.isBlank()) return null;
        return SAFE_REF.matcher(ref).matches() ? ref : null;
    }
}
