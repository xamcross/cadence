package com.cadence.scheduler;

import com.cadence.domain.WorkspaceConfig;
import com.cadence.repository.WorkspaceConfigRepository;
import com.cadence.service.InterestRequestService;
import jakarta.annotation.PostConstruct;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * F70 daily interest-request retention purge (FR-021, the F04 {@link RetentionScanTask} pattern). Wraps the work
 * in the shared {@link SchedulerCheckpointService} (RUNNING -> COMPLETED + missed-fire replay) so a mid-scan
 * restart neither double-runs harmfully nor skips a window; the replay action is registered in
 * {@code @PostConstruct} (before {@code ApplicationReadyEvent}).
 *
 * <p>Only {@code isConfigured()} workspaces are scanned (mirror {@code RetentionScanTask.runScan}). The cutoff is
 * {@code now - (retentionPeriodDays <= 0 ? fallback : retentionPeriodDays)} (the {@code 0}/unset case is the
 * fallback, NOT immediate delete) — computed inside {@link InterestRequestService#purgeAged}. Hard-delete.
 */
@Component
public class InterestRetentionScheduler {

    public static final String TASK_NAME = "interest-retention-scan";

    private final SchedulerCheckpointService checkpoints;
    private final InterestRequestService interest;
    private final WorkspaceConfigRepository configs;

    public InterestRetentionScheduler(SchedulerCheckpointService checkpoints,
                                      InterestRequestService interest,
                                      WorkspaceConfigRepository configs) {
        this.checkpoints = checkpoints;
        this.interest = interest;
        this.configs = configs;
    }

    @PostConstruct
    void registerReplay() {
        checkpoints.registerReplayAction(TASK_NAME, this::runScan);
    }

    /** 03:30 UTC daily (offset from the F04 retention scan). The checkpoint makes it idempotent + replay-safe. */
    @Scheduled(cron = "0 30 3 * * *", zone = "UTC")
    public void scheduled() {
        runScan();
    }

    void runScan() {
        checkpoints.start(TASK_NAME);
        for (WorkspaceConfig cfg : configs.findAll()) {
            if (cfg.isConfigured()) {
                interest.purgeAged(cfg.getWorkspaceId(), cfg.getRetentionPeriodDays());
            }
        }
        checkpoints.complete(TASK_NAME);
    }
}
