package com.cadence.scheduler;

import com.cadence.config.SchedulingProperties;
import com.cadence.domain.AuthEventType;
import com.cadence.domain.ClaimStatus;
import com.cadence.domain.InterviewSlotClaim;
import com.cadence.domain.SchedulingOutcomeReason;
import com.cadence.domain.SchedulingRequest;
import com.cadence.domain.SchedulingStatus;
import com.cadence.repository.SchedulingRequestRepository;
import com.cadence.service.AuthAuditService;
import jakarta.annotation.PostConstruct;
import net.logstash.logback.argument.StructuredArguments;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * F13 recovery sweep (research D6, FR-017): expire PENDING_SELECTION links past their TTL, and release
 * requests stuck in BOOKING (a crash mid-confirm) back to PENDING_SELECTION (claims released) so a slot is
 * never permanently held. Wrapped in the F00.2 {@link SchedulerCheckpointService} (idempotent +
 * missed-fire replay). Correctness rests on the per-row CAS, NOT single-threading — a double-pick is a no-op.
 * The {@code reaperThreshold} invariant (SchedulingProperties) keeps it from racing a live confirm.
 */
@Component
public class SchedulingReaper {

    private static final Logger log = LoggerFactory.getLogger(SchedulingReaper.class);
    static final String TASK = "scheduling-reaper";

    private final SchedulingRequestRepository requests;
    private final MongoTemplate mongo;
    private final SchedulerCheckpointService checkpoints;
    private final AuthAuditService audit;
    private final com.cadence.service.CalendarEventService calendar;
    private final SchedulingProperties props;
    private final Clock clock;

    public SchedulingReaper(SchedulingRequestRepository requests, MongoTemplate mongo,
                            SchedulerCheckpointService checkpoints, AuthAuditService audit,
                            com.cadence.service.CalendarEventService calendar,
                            SchedulingProperties props, Clock clock) {
        this.requests = requests;
        this.mongo = mongo;
        this.checkpoints = checkpoints;
        this.audit = audit;
        this.calendar = calendar;
        this.props = props;
        this.clock = clock;
    }

    @PostConstruct
    void registerReplay() {
        checkpoints.registerReplayAction(TASK, this::sweep);
    }

    @Scheduled(fixedDelayString = "${cadence.scheduling.reaper-interval-ms:60000}")
    public void scheduled() {
        sweep();
    }

    /** One reaper pass (also the registered missed-fire replay action). */
    public void sweep() {
        checkpoints.start(TASK);
        try {
            Instant now = Instant.now(clock);
            PageRequest page = PageRequest.of(0, props.getReaperSweepBatchLimit());

            // 1) Expire links past TTL.
            for (SchedulingRequest req : requests.findExpired(SchedulingStatus.PENDING_SELECTION, now, page)) {
                SchedulingRequest expired = mongo.findAndModify(
                    Query.query(Criteria.where("_id").is(req.getId())
                        .and("status").is(SchedulingStatus.PENDING_SELECTION)
                        .and("expiresAt").lt(now)),
                    new Update().set("status", SchedulingStatus.EXPIRED)
                        .set("lastOutcomeReason", SchedulingOutcomeReason.EXPIRED).set("updatedAt", now),
                    org.springframework.data.mongodb.core.FindAndModifyOptions.options().returnNew(true),
                    SchedulingRequest.class);
                if (expired != null) {
                    // A normal PENDING link has no claims (claims are only created during confirm); this also
                    // sweeps up the rare orphaned-claim-on-PENDING window (a BOOKED status-write that threw
                    // after the provider events were created, leaving the row reverted to PENDING with claims).
                    releaseClaims(req.getWorkspaceId(), req.getId());
                    audit.record(AuthEventType.SCHEDULING_LINK_EXPIRED, req.getWorkspaceId(), "SYSTEM",
                        SchedulingOutcomeReason.EXPIRED.name(), null);
                }
            }

            // 2) Release requests stuck in BOOKING older than the threshold (a crash mid-confirm).
            Instant stuckBefore = now.minus(props.getReaperThreshold());
            for (SchedulingRequest req : requests.findStuck(SchedulingStatus.BOOKING, stuckBefore, page)) {
                releaseClaims(req.getWorkspaceId(), req.getId());
                mongo.findAndModify(
                    Query.query(Criteria.where("_id").is(req.getId()).and("status").is(SchedulingStatus.BOOKING)),
                    new Update().set("status", SchedulingStatus.PENDING_SELECTION)
                        .set("chosenSlotId", null).set("updatedAt", now),
                    SchedulingRequest.class);
                log.info("scheduling reaper released stuck booking {}",
                    StructuredArguments.kv("schedulingRequestId", req.getId()));
            }

            // 3) F20 forward-commit recovery (D3): a RESCHEDULE round reached BOOKED but the parent cancel may
            // not have finished (crash window). Deterministic: child BOOKED + parent still BOOKED -> roll
            // FORWARD (cancel the parent). The parent-status check is a per-row CAS (cross-document). A round
            // still in BOOKING is handled by pass (2) above (roll back, parent stands) — no change needed.
            for (SchedulingRequest child : requests.findRescheduleAwaitingForwardCommit(
                    com.cadence.domain.SchedulingMode.RESCHEDULE, SchedulingStatus.BOOKED, stuckBefore, page)) {
                if (child.getParentRequestId() == null) {
                    continue;
                }
                SchedulingRequest parent = mongo.findAndModify(
                    Query.query(Criteria.where("_id").is(child.getParentRequestId())
                        .and("status").is(SchedulingStatus.BOOKED)),
                    new Update().set("status", SchedulingStatus.RESCHEDULED).set("updatedAt", now)
                        .unset("manageTokenHash"),
                    org.springframework.data.mongodb.core.FindAndModifyOptions.options().returnNew(true),
                    SchedulingRequest.class);
                if (parent != null) {
                    calendar.cancelBooking(parent.getWorkspaceId(), parent.getId());
                    releaseClaims(parent.getWorkspaceId(), parent.getId());
                    log.info("scheduling reaper forward-committed reschedule {}",
                        StructuredArguments.kv("schedulingRequestId", child.getId()));
                } else {
                    // The parent is no longer BOOKED (concurrently cancelled/superseded) — the orphaned new
                    // round must not stand. Roll it back: remove its events, release its claims, mark SUPERSEDED.
                    calendar.cancelBooking(child.getWorkspaceId(), child.getId());
                    releaseClaims(child.getWorkspaceId(), child.getId());
                    mongo.updateFirst(
                        Query.query(Criteria.where("_id").is(child.getId()).and("status").is(SchedulingStatus.BOOKED)),
                        new Update().set("status", SchedulingStatus.SUPERSEDED).set("updatedAt", now),
                        SchedulingRequest.class);
                    log.info("scheduling reaper rolled back orphaned reschedule (parent gone) {}",
                        StructuredArguments.kv("schedulingRequestId", child.getId()));
                }
            }

            // 4) F20 erasure async calendar teardown (D9): erasure flips a BOOKED booking to CANCELLED
            // synchronously (O(1)) and defers the provider event removal here. Idempotent (already-DELETED
            // events are no-ops); inherits the cleanup-incomplete honest bound.
            for (SchedulingRequest req : requests.findAwaitingCalendarTeardown(page)) {
                boolean clean = calendar.cancelBooking(req.getWorkspaceId(), req.getId());
                mongo.updateFirst(Query.query(Criteria.where("_id").is(req.getId())),
                    new Update().set("calendarTeardownPending", false)
                        .set("status", clean ? SchedulingStatus.CANCELLED : SchedulingStatus.CLEANUP_INCOMPLETE)
                        .set("updatedAt", now),
                    SchedulingRequest.class);
                log.info("scheduling reaper erasure teardown {} {}",
                    StructuredArguments.kv("schedulingRequestId", req.getId()),
                    StructuredArguments.kv("clean", Boolean.toString(clean)));
            }
        } finally {
            checkpoints.complete(TASK);
        }
    }

    private void releaseClaims(String workspaceId, String requestId) {
        mongo.updateMulti(
            Query.query(Criteria.where("workspaceId").is(workspaceId)
                .and("schedulingRequestId").is(requestId).and("status").is(ClaimStatus.ACTIVE)),
            new Update().set("status", ClaimStatus.RELEASED), InterviewSlotClaim.class);
    }
}
