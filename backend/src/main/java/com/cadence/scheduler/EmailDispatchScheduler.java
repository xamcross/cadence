package com.cadence.scheduler;

import com.cadence.config.EmailDeliveryProperties;
import com.cadence.domain.DispatchStatus;
import com.cadence.domain.EmailDispatch;
import com.cadence.repository.EmailDispatchRepository;
import com.cadence.service.EmailDispatchService;
import com.cadence.service.EmailDispatchService.DispatchResult;
import jakarta.annotation.PostConstruct;
import net.logstash.logback.argument.StructuredArguments;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * The scheduled due-row worker (F22, US4, research D6, T048). A fixed-delay {@link #sweep()} selects due
 * {@code PENDING} rows (schedule + backoff both elapsed) and runs each through the SAME gated, idempotent
 * {@link EmailDispatchService#dispatch} path an immediate send uses — so a future-dated row fires once, with
 * the consent gate <b>re-evaluated at fire time</b> (never cached). Consuming features (F23/F31/F32) enqueue a
 * future {@code scheduledFor} and inherit idempotency + missed-fire recovery for free.
 *
 * <p>The sweep is wrapped in {@link SchedulerCheckpointService#start}/{@link SchedulerCheckpointService#complete}
 * (the F00.2 / {@code RetentionScanTask} pattern) and registers a {@code @PostConstruct} replay action, so a
 * missed firing window (downtime spanning the {@code scheduledFor}) replays the same sweep once on
 * {@code ApplicationReadyEvent}.
 *
 * <p><b>Correctness rests on the per-row CAS claim, NOT on single-threading.</b> {@code @Scheduled(fixedDelay)}
 * is single-threaded per task (default pool 1) so a slow sweep cannot overlap itself, but even a double-pick
 * (overlap, a rolling-deploy two-instance window) is a no-op because {@link EmailDispatchService#dispatch}'s
 * {@code PENDING->SENDING} CAS lets exactly one worker transmit. Do NOT remove the CAS as an "optimization."
 *
 * <p>PII discipline (D10): the per-tick log carries counts + {@code .name()} Strings only — never a recipient/
 * subject/body, never an enum to {@code kv}.
 */
@Component
public class EmailDispatchScheduler {

    public static final String TASK_NAME = "email-dispatch-sweep";

    private static final Logger log = LoggerFactory.getLogger(EmailDispatchScheduler.class);

    private final SchedulerCheckpointService checkpoints;
    private final EmailDispatchRepository repo;
    private final EmailDispatchService dispatch;
    private final EmailDeliveryProperties props;
    private final Clock clock;

    public EmailDispatchScheduler(SchedulerCheckpointService checkpoints, EmailDispatchRepository repo,
                                  EmailDispatchService dispatch, EmailDeliveryProperties props, Clock clock) {
        this.checkpoints = checkpoints;
        this.repo = repo;
        this.dispatch = dispatch;
        this.props = props;
        this.clock = clock;
    }

    @PostConstruct
    void registerReplay() {
        // Registered before ApplicationReadyEvent, or a real missed fire is silently swallowed (the F00.2 lesson).
        checkpoints.registerReplayAction(TASK_NAME, this::sweep);
    }

    @Scheduled(fixedDelayString = "${cadence.email.sweep-fixed-delay:PT30S}")
    public void scheduled() {
        sweep();
    }

    /**
     * Drive every due row through the dispatch path. The checkpoint makes it missed-fire-safe; the per-row CAS
     * makes a double-pick a no-op. The batch is capped ({@code sweepBatchLimit}) so a backlog cannot load an
     * unbounded result set into one tick (the F12 explicit-{@code @Query} lesson).
     */
    public void sweep() {
        checkpoints.start(TASK_NAME);
        Instant now = Instant.now(clock);
        List<EmailDispatch> due = repo.findDue(
            DispatchStatus.PENDING, now, PageRequest.of(0, props.getSweepBatchLimit()));

        int sent = 0;
        int refused = 0;
        int failed = 0;
        for (EmailDispatch row : due) {
            // nonPiiContext is null for a scheduled fire — values are re-derived from the candidate (+ the
            // shape-guarded renderContextRef) at render time, so a retry renders deterministically (no PII persisted).
            DispatchResult r = dispatch.dispatch(row.getId(), null);
            switch (r.status()) {
                case SENT -> sent++;
                case REFUSED -> refused++;
                case FAILED -> failed++;
                default -> { /* PENDING (re-queued transient / lost claim) — counted as neither terminal outcome */ }
            }
        }
        checkpoints.complete(TASK_NAME);

        if (!due.isEmpty()) {
            log.info("email dispatch sweep {} {} {} {}",
                StructuredArguments.kv("due", due.size()),
                StructuredArguments.kv("sent", sent),
                StructuredArguments.kv("refused", refused),
                StructuredArguments.kv("failed", failed));
        }
    }
}
