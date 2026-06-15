package com.cadence.scheduler;

import com.cadence.domain.WorkspaceConfig;
import com.cadence.repository.WorkspaceConfigRepository;
import com.cadence.service.RetentionService;
import jakarta.annotation.PostConstruct;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Daily retention scan (F04, FR-018, F00.2 pattern). Wraps the work in the shared
 * {@link SchedulerCheckpointService} (RUNNING -> COMPLETED, missed-fire replay) so a mid-scan restart
 * neither double-flags nor skips a window. The replay action is registered in {@code @PostConstruct}
 * (before {@code ApplicationReadyEvent}), or a real missed fire would be silently swallowed.
 */
@Component
public class RetentionScanTask {

    public static final String TASK_NAME = "retention-scan";

    private final SchedulerCheckpointService checkpoints;
    private final RetentionService retention;
    private final WorkspaceConfigRepository configs;

    public RetentionScanTask(SchedulerCheckpointService checkpoints, RetentionService retention,
                             WorkspaceConfigRepository configs) {
        this.checkpoints = checkpoints;
        this.retention = retention;
        this.configs = configs;
    }

    @PostConstruct
    void registerReplay() {
        checkpoints.registerReplayAction(TASK_NAME, this::runScan);
    }

    /** 03:00 UTC daily. The checkpoint makes it idempotent + missed-fire-safe. */
    @Scheduled(cron = "0 0 3 * * *", zone = "UTC")
    public void scheduled() {
        runScan();
    }

    void runScan() {
        checkpoints.start(TASK_NAME);
        for (WorkspaceConfig cfg : configs.findAll()) {
            if (cfg.isConfigured()) {
                retention.scan(cfg.getWorkspaceId());
            }
        }
        checkpoints.complete(TASK_NAME);
    }
}
