package com.cadence.scheduler;

import com.cadence.domain.AtsConnection;
import com.cadence.domain.AtsConnectionStatus;
import com.cadence.domain.GatedFeature;
import com.cadence.repository.AtsConnectionRepository;
import com.cadence.service.AtsSyncService;
import com.cadence.service.EntitlementService;
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
    private final EntitlementService entitlements;

    public AtsSyncScheduler(SchedulerCheckpointService checkpoints, AtsConnectionRepository connections,
                            AtsSyncService syncService, EntitlementService entitlements) {
        this.checkpoints = checkpoints;
        this.connections = connections;
        this.syncService = syncService;
        this.entitlements = entitlements;
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
                // 032 T7 placement 3: a Team->Free downgrade leaves the connection CONNECTED (F41 coexistence —
                // disconnect stays a separate, ungated, user action) but the sweep must not initiate a NEW sync
                // for it. Inside the loop only — never around checkpoints.start/complete (those bracket the whole
                // pass regardless of any single workspace's entitlement).
                if (!entitlements.hasFeature(conn.getWorkspaceId(), GatedFeature.ATS_INTEGRATIONS)) {
                    continue;
                }
                // Per-connection isolation (F41 SC-014/FR-022): syncWorkspace already swallows AtsApiException, but
                // an UNEXPECTED RuntimeException (e.g. a Mongo DataAccessException) must NOT abort the sweep and
                // starve the other provider's / other workspaces' connections. Log and continue.
                try {
                    syncService.syncWorkspace(conn);
                } catch (RuntimeException e) {
                    log.warn("ATS sync iteration failed (isolated) {} {}",
                        StructuredArguments.kv("workspaceId", conn.getWorkspaceId()),
                        StructuredArguments.kv("provider", conn.getProvider() == null ? null : conn.getProvider().name()));
                }
            }
            if (!connected.isEmpty()) {
                log.info("ATS sync sweep {}", StructuredArguments.kv("workspaces", connected.size()));
            }
        } finally {
            checkpoints.complete(TASK_NAME);
        }
    }
}
