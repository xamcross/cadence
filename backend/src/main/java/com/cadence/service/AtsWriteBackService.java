package com.cadence.service;

import com.cadence.config.AtsProperties;
import com.cadence.domain.AtsConnection;
import com.cadence.domain.AtsConnectionStatus;
import com.cadence.domain.AtsWriteBack;
import com.cadence.domain.AtsWriteBackStatus;
import com.cadence.domain.AtsWriteBackType;
import com.cadence.domain.Candidate;
import com.cadence.domain.ErasureState;
import com.cadence.domain.RecruiterNotificationType;
import com.cadence.integration.AtsActivity;
import com.cadence.integration.AtsApiException;
import com.cadence.integration.AtsConnector;
import com.cadence.integration.AtsProvider;
import com.cadence.repository.AtsConnectionRepository;
import com.cadence.repository.AtsWriteBackRepository;
import com.cadence.repository.CandidateRepository;
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
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The ATS write-back outbox (F40, US3/US4) — the {@code EmailDispatch} precedent: NO {@code @Version}; the
 * unique {@code {workspaceId,idempotencyKey}} index is the exactly-once guarantee; the {@code findAndModify}
 * PENDING->SENDING claim is the single-winner concurrency guarantee; insert-then-catch-DuplicateKeyException is
 * the idempotent enqueue. The note carries only a non-PII scheduling fact (D5/FR-029). At-most-once is anchored
 * in the local claim-before-send transition; Greenhouse has no client dedup key, so the reaper reconciles a
 * crashed SENDING row (no blind re-send — the SC-003 honest bound).
 */
@Service
public class AtsWriteBackService implements AtsWriteBackInvalidator {

    private static final Logger log = LoggerFactory.getLogger(AtsWriteBackService.class);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);

    private final AtsWriteBackRepository repo;
    private final AtsConnectionRepository connections;
    private final CandidateRepository candidates;
    private final MongoTemplate mongo;
    private final DeadLetterService deadLetters;
    private final RecruiterNotificationService notifications;
    private final Map<AtsProvider, AtsConnector> connectors = new EnumMap<>(AtsProvider.class);
    private final AtsProperties props;
    private final Clock clock;

    public AtsWriteBackService(AtsWriteBackRepository repo, AtsConnectionRepository connections,
                               CandidateRepository candidates, MongoTemplate mongo, DeadLetterService deadLetters,
                               RecruiterNotificationService notifications, List<AtsConnector> connectorList,
                               AtsProperties props, Clock clock) {
        this.repo = repo;
        this.connections = connections;
        this.candidates = candidates;
        this.mongo = mongo;
        this.deadLetters = deadLetters;
        this.notifications = notifications;
        for (AtsConnector c : connectorList) {
            connectors.put(c.provider(), c);
        }
        this.props = props;
        this.clock = clock;
    }

    /**
     * Enqueue a write-back for a scheduling event (best-effort — never throws to the caller). No-op when the
     * candidate is not ATS-linked or is erased (FR-013/FR-015). Idempotent on the deterministic event instant.
     */
    public void enqueue(String workspaceId, String candidateId, AtsWriteBackType type, Instant eventAt) {
        try {
            Candidate c = candidates.findByWorkspaceIdAndId(workspaceId, candidateId).orElse(null);
            if (c == null || c.getAtsExternalRef() == null || c.getErasureState() != ErasureState.ACTIVE) {
                return; // not linked / erased -> nothing to write back
            }
            Instant now = Instant.now(clock);
            String key = IdempotencyKeys.atsWriteBackKey(workspaceId, candidateId, type, eventAt.toEpochMilli());
            AtsWriteBack w = new AtsWriteBack();
            w.setWorkspaceId(workspaceId);
            w.setCandidateId(candidateId);
            w.setProvider(c.getAtsProvider()); // F41 routing key — the candidate's provider of record
            w.setAtsExternalRef(c.getAtsExternalRef());
            w.setType(type);
            w.setIdempotencyKey(key);
            w.setStatus(AtsWriteBackStatus.PENDING);
            w.setEventAt(eventAt);
            w.setNextAttemptAt(now);
            w.setCreatedAt(now);
            w.setUpdatedAt(now);
            repo.insert(w);
        } catch (DuplicateKeyException dup) {
            // The same logical event is already queued/delivered — idempotent no-op.
        } catch (RuntimeException e) {
            log.warn("ATS write-back enqueue failed (non-fatal) {} {}",
                StructuredArguments.kv("candidateId", candidateId),
                StructuredArguments.kv("errorType", e.getClass().getSimpleName()));
        }
    }

    /** Claim one PENDING-due row and deliver it (the scheduler drains; no-op if the claim is lost). */
    public void claimAndDeliver(String writeBackId) {
        Instant now = Instant.now(clock);
        AtsWriteBack claimed = mongo.findAndModify(
            Query.query(Criteria.where("_id").is(writeBackId)
                .and("status").is(AtsWriteBackStatus.PENDING).and("nextAttemptAt").lte(now)),
            new Update().set("status", AtsWriteBackStatus.SENDING).set("updatedAt", now).inc("attemptCount", 1),
            FindAndModifyOptions.options().returnNew(true),
            AtsWriteBack.class);
        if (claimed == null) {
            return; // lost the claim / not due
        }
        // Route by the row's provider (F41) — load THIS provider's connection, never the wrong one.
        AtsConnection conn = connections.findByWorkspaceIdAndProvider(
            claimed.getWorkspaceId(), claimed.getProvider()).orElse(null);
        AtsConnector connector = conn == null ? null : connectors.get(conn.getProvider());
        if (conn == null || conn.getStatus() != AtsConnectionStatus.CONNECTED
            || conn.getApiKey() == null || connector == null) {
            // The connection is not deliverable right now (NEEDS_REAUTH / ERROR / missing). This is NOT a
            // delivery failure — HOLD the row (the review B1 fix): a transiently-degraded connection must not
            // permanently dead-letter pending write-backs (SC-004: deliver within 15 min of recovery). A truly
            // disconnected workspace has its pending write-backs CANCELLED by disconnect(), so they never reach here.
            holdForConnectionRecovery(claimed, "connection_not_ready");
            return;
        }
        AtsActivity activity = new AtsActivity(claimed.getType(), claimed.getEventAt(), noteFor(claimed));
        try {
            String ref = connector.pushActivity(conn.getWorkspaceId(), conn.getApiKey(),
                claimed.getAtsExternalRef(), activity);
            mongo.findAndModify(
                Query.query(Criteria.where("_id").is(writeBackId).and("status").is(AtsWriteBackStatus.SENDING)),
                new Update().set("status", AtsWriteBackStatus.DELIVERED).set("providerActivityRef", ref)
                    .set("lastOutcomeCategory", "delivered").set("updatedAt", Instant.now(clock)),
                AtsWriteBack.class);
            log.info("ATS write-back delivered {} {}",
                StructuredArguments.kv("writeBackId", writeBackId),
                StructuredArguments.kv("type", claimed.getType().name()));
        } catch (AtsApiException e) {
            if (e.isNeedsReauth()) {
                // Flip the connection and HOLD (do not dead-letter / do not consume the retry budget on a fixable
                // creds issue — review B1): the write-back delivers once the Admin re-authorizes (D6).
                // Provider-scoped flip (F41 confused-deputy fix): flip ONLY this provider's connection, never the
                // coexisting provider's — a Lever auth failure must not flip the Greenhouse connection (SC-014).
                mongo.updateFirst(Query.query(Criteria.where("workspaceId").is(conn.getWorkspaceId())
                        .and("provider").is(conn.getProvider())),
                    new Update().set("status", AtsConnectionStatus.NEEDS_REAUTH)
                        .set("lastErrorCategory", "auth").set("updatedAt", Instant.now(clock)),
                    AtsConnection.class);
                holdForConnectionRecovery(claimed, "auth");
            } else {
                requeueOrDeadLetter(claimed, e.getCategory(), e.isTransient());
            }
        }
    }

    /**
     * Hold a write-back whose connection is not deliverable (NEEDS_REAUTH / degraded) WITHOUT consuming the retry
     * budget (the claim already inc'd attemptCount; undo it). Re-queues PENDING so a later drain re-attempts once
     * the connection recovers — never a permanent dead-letter for a recoverable connection state (review B1).
     */
    private void holdForConnectionRecovery(AtsWriteBack claimed, String category) {
        Instant now = Instant.now(clock);
        mongo.updateFirst(
            Query.query(Criteria.where("_id").is(claimed.getId()).and("status").is(AtsWriteBackStatus.SENDING)),
            new Update().set("status", AtsWriteBackStatus.PENDING)
                .set("nextAttemptAt", now.plus(backoff(0)))
                .inc("attemptCount", -1)          // a "connection not ready" hold is not a failed delivery attempt
                .set("lastOutcomeCategory", category).set("updatedAt", now),
            AtsWriteBack.class);
    }

    /** Transient + under cap -> requeue with backoff; otherwise dead-letter + operator notify (FR-018). */
    private void requeueOrDeadLetter(AtsWriteBack claimed, String category, boolean retryable) {
        Instant now = Instant.now(clock);
        boolean canRetry = retryable && claimed.getAttemptCount() < props.getRetryMaxAttempts();
        if (canRetry) {
            Instant next = now.plus(backoff(claimed.getAttemptCount()));
            mongo.updateFirst(
                Query.query(Criteria.where("_id").is(claimed.getId()).and("status").is(AtsWriteBackStatus.SENDING)),
                new Update().set("status", AtsWriteBackStatus.PENDING).set("nextAttemptAt", next)
                    .set("lastOutcomeCategory", category).set("updatedAt", now),
                AtsWriteBack.class);
            return;
        }
        mongo.updateFirst(
            Query.query(Criteria.where("_id").is(claimed.getId()).and("status").is(AtsWriteBackStatus.SENDING)),
            new Update().set("status", AtsWriteBackStatus.DEAD_LETTER).set("lastOutcomeCategory", category)
                .set("updatedAt", now),
            AtsWriteBack.class);
        deadLetters.recordFailure("ats-writeback", new IllegalStateException("ats_writeback_failed: " + category),
            claimed.getCandidateId());
        notifications.notify(claimed.getWorkspaceId(), claimed.getCandidateId(),
            RecruiterNotificationType.ATS_WRITEBACK_FAILED);
        log.warn("ATS write-back dead-lettered {} {} {}",
            StructuredArguments.kv("writeBackId", claimed.getId()),
            StructuredArguments.kv("type", claimed.getType().name()),
            StructuredArguments.kv("category", category));
    }

    /**
     * Reaper (the F22 SENT_UNCONFIRMED honest-bound analogue): a SENDING row older than the reaper threshold
     * crashed mid-send. Greenhouse has no client dedup key, so we do NOT blindly re-send (would risk a duplicate
     * note); we reconcile it to DEAD_LETTER for operator review (visible, no duplicate — the SC-003 honest bound).
     */
    public int reapStuck() {
        Instant now = Instant.now(clock);
        Instant threshold = now.minus(props.getReaperThreshold());
        List<AtsWriteBack> stuck = repo.findStuck(AtsWriteBackStatus.SENDING, threshold,
            org.springframework.data.domain.PageRequest.of(0, props.getWritebackBatchLimit()));
        int reaped = 0;
        for (AtsWriteBack w : stuck) {
            long matched = mongo.updateFirst(
                Query.query(Criteria.where("_id").is(w.getId()).and("status").is(AtsWriteBackStatus.SENDING)),
                new Update().set("status", AtsWriteBackStatus.DEAD_LETTER)
                    .set("lastOutcomeCategory", "reaped_in_flight").set("updatedAt", now),
                AtsWriteBack.class).getMatchedCount();
            if (matched == 1) {
                reaped++;
                notifications.notify(w.getWorkspaceId(), w.getCandidateId(),
                    RecruiterNotificationType.ATS_WRITEBACK_FAILED);
            }
        }
        return reaped;
    }

    @Override
    public void cancelPendingForCandidate(String workspaceId, String candidateId) {
        mongo.updateMulti(
            Query.query(Criteria.where("workspaceId").is(workspaceId).and("candidateId").is(candidateId)
                .and("status").is(AtsWriteBackStatus.PENDING)),
            new Update().set("status", AtsWriteBackStatus.CANCELLED).set("updatedAt", Instant.now(clock)),
            AtsWriteBack.class);
    }

    @Override
    public void cancelPendingForWorkspace(String workspaceId) {
        mongo.updateMulti(
            Query.query(Criteria.where("workspaceId").is(workspaceId).and("status").is(AtsWriteBackStatus.PENDING)),
            new Update().set("status", AtsWriteBackStatus.CANCELLED).set("updatedAt", Instant.now(clock)),
            AtsWriteBack.class);
    }

    @Override
    public void cancelPendingForWorkspaceAndProvider(String workspaceId, AtsProvider provider) {
        mongo.updateMulti(
            Query.query(Criteria.where("workspaceId").is(workspaceId).and("provider").is(provider)
                .and("status").is(AtsWriteBackStatus.PENDING)),
            new Update().set("status", AtsWriteBackStatus.CANCELLED).set("updatedAt", Instant.now(clock)),
            AtsWriteBack.class);
    }

    /** Non-PII scheduling-fact note (D5) — type phrase + the event date (UTC). Never candidate PII. */
    private static String noteFor(AtsWriteBack w) {
        String date = w.getEventAt() == null ? "" : (" (" + DATE.format(w.getEventAt()) + ")");
        String phrase = switch (w.getType()) {
            case LINK_SENT -> "Scheduling link sent via Cadence";
            case CONFIRMED -> "Interview scheduled via Cadence";
            case RESCHEDULED -> "Interview rescheduled via Cadence";
            case CANCELLED -> "Interview cancelled via Cadence";
            case NO_SHOW -> "Candidate marked no-show in Cadence";
            case FEEDBACK_SUBMITTED -> "Interviewer feedback submitted in Cadence";
        };
        return phrase + date;
    }

    private Duration backoff(int attempt) {
        long base = props.getRetryBaseBackoff().toMillis();
        if (base <= 0) {
            return Duration.ZERO;
        }
        long capped = Math.min(attempt, 16);
        long millis = base << capped;
        long jitter = ThreadLocalRandom.current().nextLong(base);
        return Duration.ofMillis(millis + jitter);
    }
}
