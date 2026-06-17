package com.cadence.scheduler;

import com.cadence.config.NoShowProperties;
import com.cadence.domain.SchedulingRequest;
import com.cadence.domain.WorkspaceConfig;
import com.cadence.repository.SchedulingRequestRepository;
import com.cadence.repository.WorkspaceConfigRepository;
import com.cadence.service.NoShowCascadeService;
import jakarta.annotation.PostConstruct;
import net.logstash.logback.argument.StructuredArguments;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * F23 No-Show Defense cascade (research D1/D8). A fixed-delay sweep that advances three per-booking stages —
 * confirmation request at the lead-time boundary, unconfirmed escalation at the deadline, and a no-show stamp
 * at start — each driven through a per-row CAS in {@link NoShowCascadeService} (correctness rests on the CAS,
 * NOT single-threading — the {@code EmailDispatchScheduler} precedent).
 *
 * <p>Wrapped in {@link SchedulerCheckpointService#start}/{@code complete} + a {@code @PostConstruct} replay
 * registration (the F00.2 contract) so a missed firing window replays once on {@code ApplicationReadyEvent}.
 *
 * <p>The per-workspace lead/deadline offsets cannot be a single Mongo arithmetic, so each stage scans an
 * indexed range bounded by the GLOBAL {@code cascadeQueryBound} and Java-filters the capped batch against the
 * row's workspace setting (D2/D7). {@code now} is the injected {@link Clock} (never wall-clock — the test-clock
 * rule). Logs counts + ids only.
 */
@Component
public class NoShowDefenseScheduler {

    public static final String TASK_NAME = "no-show-cascade";

    private static final Logger log = LoggerFactory.getLogger(NoShowDefenseScheduler.class);

    private final SchedulerCheckpointService checkpoints;
    private final SchedulingRequestRepository requests;
    private final WorkspaceConfigRepository workspaceConfigs;
    private final NoShowCascadeService cascade;
    private final NoShowProperties props;
    private final Clock clock;

    public NoShowDefenseScheduler(SchedulerCheckpointService checkpoints, SchedulingRequestRepository requests,
                                  WorkspaceConfigRepository workspaceConfigs, NoShowCascadeService cascade,
                                  NoShowProperties props, Clock clock) {
        this.checkpoints = checkpoints;
        this.requests = requests;
        this.workspaceConfigs = workspaceConfigs;
        this.cascade = cascade;
        this.props = props;
        this.clock = clock;
    }

    @PostConstruct
    void registerReplay() {
        checkpoints.registerReplayAction(TASK_NAME, this::sweep);
    }

    @Scheduled(fixedDelayString = "${cadence.noshow.cascade-interval-ms:60000}")
    public void scheduled() {
        sweep();
    }

    /** One cascade pass (also the registered missed-fire replay action). */
    public void sweep() {
        checkpoints.start(TASK_NAME);
        try {
            Instant now = Instant.now(clock);
            Instant bound = now.plus(props.getCascadeQueryBound());
            PageRequest page = PageRequest.of(0, props.getCascadeSweepBatchLimit());
            Map<String, WorkspaceConfig> wsCache = new HashMap<>();

            int requested = 0;
            int escalated = 0;
            int noShow = 0;

            // Stage 1: confirmation request (per-workspace lead time, Java-filtered; future starts only).
            for (SchedulingRequest req : requests.findConfirmationRequestDue(now, bound, page)) {
                Duration lead = leadTime(req.getWorkspaceId(), wsCache);
                if (req.getBookedStartAt() != null && !req.getBookedStartAt().minus(lead).isAfter(now)) {
                    cascade.requestConfirmation(req, now);
                    requested++;
                }
            }

            // Stage 2: unconfirmed escalation (per-workspace deadline, Java-filtered).
            for (SchedulingRequest req : requests.findEscalationDue(now, bound, page)) {
                Duration deadline = escalationDeadline(req.getWorkspaceId(), wsCache);
                if (req.getBookedStartAt() != null && !req.getBookedStartAt().minus(deadline).isAfter(now)) {
                    cascade.escalateUnconfirmed(req, now);
                    escalated++;
                }
            }

            // Stage 3: no-show stamp (start already reached — no per-workspace offset).
            for (SchedulingRequest req : requests.findNoShowDue(now, page)) {
                cascade.stampNoShow(req, now);
                noShow++;
            }

            if (requested + escalated + noShow > 0) {
                log.info("no-show cascade sweep {} {} {}",
                    StructuredArguments.kv("requested", requested),
                    StructuredArguments.kv("escalated", escalated),
                    StructuredArguments.kv("noShow", noShow));
            }
        } finally {
            checkpoints.complete(TASK_NAME);
        }
    }

    private Duration leadTime(String workspaceId, Map<String, WorkspaceConfig> cache) {
        WorkspaceConfig c = config(workspaceId, cache);
        return c != null && c.getConfirmationLeadTime() != null
            ? c.getConfirmationLeadTime() : props.getConfirmationLeadTime();
    }

    private Duration escalationDeadline(String workspaceId, Map<String, WorkspaceConfig> cache) {
        WorkspaceConfig c = config(workspaceId, cache);
        return c != null && c.getUnconfirmedEscalationDeadline() != null
            ? c.getUnconfirmedEscalationDeadline() : props.getEscalationDeadline();
    }

    private WorkspaceConfig config(String workspaceId, Map<String, WorkspaceConfig> cache) {
        return cache.computeIfAbsent(workspaceId,
            ws -> workspaceConfigs.findByWorkspaceId(ws).orElse(null));
    }
}
