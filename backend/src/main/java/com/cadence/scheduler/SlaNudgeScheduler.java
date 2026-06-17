package com.cadence.scheduler;

import com.cadence.domain.WorkspaceConfig;
import com.cadence.repository.WorkspaceConfigRepository;
import com.cadence.service.SlaNudgeService;
import jakarta.annotation.PostConstruct;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

/**
 * F31 SLA breach scan (the F23 {@link NoShowDefenseScheduler} shape). A fixed-delay sweep that, per configured
 * workspace, drafts a holding message for each breaching, non-suppressed, non-terminal candidate. Wrapped in
 * {@link SchedulerCheckpointService#start}/{@code complete} + a {@code @PostConstruct} replay registration (the
 * F00.2 contract) so a missed firing window replays once on {@code ApplicationReadyEvent}.
 *
 * <p><b>No-auto-send (FR-010/SC-008):</b> this scheduler holds NO reference to {@code EmailDispatchService} and
 * never sends — it only calls {@link SlaNudgeService#scanWorkspace} (which creates drafts). Correctness rests on
 * the per-row unique-partial-index de-dup, NOT single-threading (the {@code EmailDispatchScheduler} precedent).
 * {@code now} is the injected {@link Clock} (never wall-clock — the test-clock rule).
 */
@Component
public class SlaNudgeScheduler {

    public static final String TASK_NAME = "sla-nudge-scan";

    private final SchedulerCheckpointService checkpoints;
    private final WorkspaceConfigRepository workspaceConfigs;
    private final SlaNudgeService sla;
    private final Clock clock;

    public SlaNudgeScheduler(SchedulerCheckpointService checkpoints, WorkspaceConfigRepository workspaceConfigs,
                             SlaNudgeService sla, Clock clock) {
        this.checkpoints = checkpoints;
        this.workspaceConfigs = workspaceConfigs;
        this.sla = sla;
        this.clock = clock;
    }

    @PostConstruct
    void registerReplay() {
        checkpoints.registerReplayAction(TASK_NAME, this::sweep);
    }

    @Scheduled(fixedDelayString = "${cadence.sla.scan-interval-ms:300000}")
    public void scheduled() {
        sweep();
    }

    /** One scan pass (also the registered missed-fire replay action). */
    public void sweep() {
        checkpoints.start(TASK_NAME);
        try {
            Instant now = Instant.now(clock);
            for (WorkspaceConfig cfg : workspaceConfigs.findAll()) {
                sla.scanWorkspace(cfg, now); // skips unconfigured workspaces internally
            }
        } finally {
            checkpoints.complete(TASK_NAME);
        }
    }
}
