package com.cadence.service;

import com.cadence.domain.AuthEventType;
import com.cadence.domain.Candidate;
import com.cadence.domain.DispatchOutcomeReason;
import com.cadence.domain.DispatchStatus;
import com.cadence.domain.EmailDispatch;
import com.cadence.domain.ProcessedWebhookEvent;
import com.cadence.domain.RecruiterNotificationType;
import com.cadence.repository.CandidateRepository;
import com.cadence.repository.EmailDispatchRepository;
import com.cadence.repository.ProcessedWebhookEventRepository;
import net.logstash.logback.argument.StructuredArguments;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;

/**
 * Applies an inbound provider event (delivered / bounce / complaint) to the dispatch outbox + candidate
 * (F22, T040, contract B). The controller verifies the signature BEFORE calling this; this service applies
 * the <b>ordered, non-transactional, idempotent</b> flips.
 *
 * <p><b>Idempotent by {@code eventId}</b> (SC-009): the first thing each event does is claim its {@code eventId}
 * by inserting a {@link ProcessedWebhookEvent}; a duplicate/out-of-order replay hits the unique index
 * (ChangeUnit011) -> {@code DuplicateKeyException} -> no-op (no second flag/notify). A row is claimed only
 * once the event correlates to a real, same-workspace dispatch — so an unknown/cross-workspace event neither
 * claims an id nor changes state (no existence oracle).
 *
 * <p><b>Ordered flips for a hard bounce / complaint</b> (data-model §3, contract B): (i) CAS the dispatch row
 * {@code -> BOUNCED}, then (ii) set candidate {@code undeliverable=true} + a value-free reason + instant, then
 * (iii) {@code EMAIL_DISPATCH_BOUNCED} audit + recruiter notification. A crash mid-sequence is safe: the gate
 * fail-closes on {@code undeliverable}, and the {@code eventId} idempotency makes a replay a no-op. A soft
 * bounce sets only the row's {@code lastOutcomeReason=SOFT_BOUNCE} — NO candidate flag (FR-018). A delivered
 * event sets the row's reason to {@code DELIVERED} (informational).
 *
 * <p>PII discipline (D10): the parser (controller) never binds the provider's free-text reason; this service
 * logs ids + {@code .name()} Strings only, never the recipient/subject/body.
 */
@Service
public class EmailBounceService {

    private static final Logger log = LoggerFactory.getLogger(EmailBounceService.class);

    private static final String COL_STATUS = "status";

    private final MongoTemplate mongo;
    private final EmailDispatchRepository dispatches;
    private final ProcessedWebhookEventRepository processedEvents;
    private final CandidateRepository candidates;
    private final AuthAuditService audit;
    private final RecruiterNotificationService notifications;
    private final EmailDispatchMetrics metrics;
    private final Clock clock;

    public EmailBounceService(MongoTemplate mongo, EmailDispatchRepository dispatches,
                              ProcessedWebhookEventRepository processedEvents, CandidateRepository candidates,
                              AuthAuditService audit, RecruiterNotificationService notifications,
                              EmailDispatchMetrics metrics, Clock clock) {
        this.mongo = mongo;
        this.dispatches = dispatches;
        this.processedEvents = processedEvents;
        this.candidates = candidates;
        this.audit = audit;
        this.notifications = notifications;
        this.metrics = metrics;
        this.clock = clock;
    }

    /** The provider event kind, mapped from the parsed (never free-text) {@code type} field. */
    public enum EventType { DELIVERED, HARD_BOUNCE, SOFT_BOUNCE, COMPLAINT, UNKNOWN }

    /**
     * Apply one provider event. {@code eventId}/{@code providerMessageRef} are opaque tokens. Returns silently
     * for an unknown/cross-workspace ref or a duplicate event (the controller always acks 200 — no oracle).
     */
    public void process(String eventId, String providerMessageRef, EventType type) {
        if (providerMessageRef == null || providerMessageRef.isBlank() || type == EventType.UNKNOWN) {
            return; // ack-and-ignore; nothing to correlate (no state change, no claimed eventId)
        }
        EmailDispatch row = dispatches.findByProviderMessageRef(providerMessageRef).orElse(null);
        if (row == null) {
            // Unknown ref -> ack-and-ignore (no state change, no existence leak). Do NOT claim the eventId.
            metrics.incWebhookUnmatched();
            return;
        }

        // Idempotency claim AFTER correlation: a duplicate/out-of-order replay is a no-op (SC-009).
        if (eventId != null && !eventId.isBlank() && !claimEvent(eventId)) {
            return; // already processed
        }

        Instant now = Instant.now(clock);
        switch (type) {
            case DELIVERED -> recordRowReason(row.getId(), DispatchOutcomeReason.DELIVERED, now);
            case SOFT_BOUNCE -> recordRowReason(row.getId(), DispatchOutcomeReason.SOFT_BOUNCE, now); // no flag (FR-018)
            case HARD_BOUNCE -> applyUndeliverable(row, DispatchOutcomeReason.HARD_BOUNCE, now);
            case COMPLAINT -> applyUndeliverable(row, DispatchOutcomeReason.COMPLAINT, now);
            default -> { /* unreachable — UNKNOWN handled above */ }
        }
    }

    /** Insert the eventId; false if it was already processed (the unique-index DuplicateKeyException). */
    private boolean claimEvent(String eventId) {
        try {
            processedEvents.insert(new ProcessedWebhookEvent(eventId, Instant.now(clock)));
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    /** Informational row-reason set (DELIVERED / SOFT_BOUNCE) — no status change, no candidate flag. */
    private void recordRowReason(String dispatchId, DispatchOutcomeReason reason, Instant now) {
        mongo.findAndModify(
            Query.query(Criteria.where("_id").is(dispatchId)),
            new Update().set("lastOutcomeReason", reason).set("updatedAt", now),
            EmailDispatch.class);
    }

    /**
     * Ordered hard-bounce / complaint flips: row -> BOUNCED (CAS), candidate undeliverable=true, audit + notify.
     * Each step is independently idempotent; the eventId claim makes the whole sequence replay-safe.
     */
    private void applyUndeliverable(EmailDispatch row, DispatchOutcomeReason reason, Instant now) {
        String dispatchId = row.getId();
        String workspaceId = row.getWorkspaceId();
        String candidateId = row.getCandidateId();

        // (i) CAS the dispatch row -> BOUNCED. Idempotent (a re-BOUNCE is a no-op via the not-already-BOUNCED guard).
        mongo.findAndModify(
            Query.query(Criteria.where("_id").is(dispatchId).and(COL_STATUS).ne(DispatchStatus.BOUNCED)),
            new Update().set(COL_STATUS, DispatchStatus.BOUNCED).set("lastOutcomeReason", reason).set("updatedAt", now),
            EmailDispatch.class);

        // (ii) Flag the candidate undeliverable (value-free reason + instant). Workspace-scoped.
        Candidate candidate = candidates.findByWorkspaceIdAndId(workspaceId, candidateId).orElse(null);
        if (candidate != null) {
            mongo.updateFirst(
                Query.query(Criteria.where("_id").is(candidateId).and("workspaceId").is(workspaceId)),
                new Update().set("undeliverable", true).set("undeliverableReason", reason)
                    .set("undeliverableAt", now),
                Candidate.class);
        }

        // (iii) Audit + recruiter notification (value-free — reason literal only).
        audit.record(AuthEventType.EMAIL_DISPATCH_BOUNCED, workspaceId, candidateId, reason.name(), null);
        notifications.notify(workspaceId, candidateId, RecruiterNotificationType.DISPATCH_BOUNCED);
        metrics.incBounced();

        log.warn("email dispatch bounced {} {}",
            StructuredArguments.kv("dispatchId", dispatchId),
            StructuredArguments.kv("reason", reason.name())); // .name() only — never the enum
    }
}
