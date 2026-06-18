package com.cadence.scheduler;

import com.cadence.domain.AtsConnection;
import com.cadence.domain.AtsConnectionStatus;
import com.cadence.repository.AtsConnectionRepository;
import com.cadence.service.AtsSyncService;
import jakarta.annotation.PostConstruct;
import net.logstash.logback.argument.StructuredArguments;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * F40 inbound poll (US2). A fixed-delay sweep (default 5 min, FR-009) that iterates CONNECTED workspaces and
 * runs one {@link AtsSyncService#syncWorkspace} each. Wrapped in {@link SchedulerCheckpointService} +
 * {@code @PostConstruct} replay (the F00.2 contract) so a missed window replays once on startup. Correctness
 * rests on the per-record reconcile (idempotent), NOT single-threading.
 */
@Component
public class AtsSyncScheduler {

    public static final String TASK_NAME = "ats-sync-scan";

    private static final Logger log = LoggerFactory.getLogger(AtsSyncScheduler.class);

    private final SchedulerCheckpointService checkpoints;
    private final AtsConnectionRepository connections;
    private final AtsSyncService syncService;

    public AtsSyncScheduler(SchedulerCheckpointService checkpoints, AtsConnectionRepository connections,
                            AtsSyncService syncService) {
        this.checkpoints = checkpoints;
        this.connections = connections;
        this.syncService = syncService;
    }

    @PostConstruct
    void registerReplay() {
        checkpoints.registerReplayAction(TASK_NAME, this::sweep);
    }

    @Scheduled(fixedDelayString = "${cadence.ats.poll-interval-ms:300000}")
    public void scheduled() {
        sweep();
    }

    /** One poll pass (also the registered missed-fire replay action). */
    public void sweep() {
        checkpoints.start(TASK_NAME);
        try {
            List<AtsConnection> connected = connections.findByStatus(AtsConnectionStatus.CONNECTED);
            for (AtsConnection conn : connected) {
                syncService.syncWorkspace(conn);
            }
            if (!connected.isEmpty()) {
                log.info("ATS sync sweep {}", StructuredArguments.kv("workspaces", connected.size()));
            }
        } finally {
            checkpoints.complete(TASK_NAME);
        }
    }
}
