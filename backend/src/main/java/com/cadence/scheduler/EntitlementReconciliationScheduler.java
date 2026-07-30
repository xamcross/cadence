package com.cadence.scheduler;

import com.cadence.domain.WorkspaceEntitlement;
import com.cadence.repository.WorkspaceEntitlementRepository;
import com.cadence.service.BillingService;
import jakarta.annotation.PostConstruct;
import net.logstash.logback.argument.StructuredArguments;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 032 -- nightly entitlement re-verify against Freemius truth (FR-011/SC-002): missed webhooks
 * self-heal within 24h. 04:00 UTC (03:00 retention-scan and 03:30 interest-retention are taken).
 * Wrapped in the shared SchedulerCheckpointService (RUNNING -> COMPLETED + missed-fire replay);
 * the replay action is registered in @PostConstruct (before ApplicationReadyEvent -- the F00.2
 * lesson). Per-row isolation: a provider failure on one row never changes that row's state
 * (FR-011) and never starves the rest. Row count is bounded by paying workspaces, so findAll()
 * matches the SlaNudgeScheduler precedent.
 */
@Component
public class EntitlementReconciliationScheduler {

    public static final String TASK_NAME = "billing-entitlement-reconcile";

    private static final Logger log = LoggerFactory.getLogger(EntitlementReconciliationScheduler.class);

    private final SchedulerCheckpointService checkpoints;
    private final WorkspaceEntitlementRepository entitlements;
    private final BillingService billing;

    public EntitlementReconciliationScheduler(SchedulerCheckpointService checkpoints,
                                              WorkspaceEntitlementRepository entitlements,
                                              BillingService billing) {
        this.checkpoints = checkpoints;
        this.entitlements = entitlements;
        this.billing = billing;
    }

    @PostConstruct
    void registerReplay() {
        checkpoints.registerReplayAction(TASK_NAME, this::sweep);
    }

    /** 04:00 UTC nightly. The checkpoint makes it idempotent + replay-safe. */
    @Scheduled(cron = "0 0 4 * * *", zone = "UTC")
    public void scheduled() {
        sweep();
    }

    /** One re-verify pass (also the registered missed-fire replay action). */
    public void sweep() {
        checkpoints.start(TASK_NAME);
        try {
            int verified = 0;
            for (WorkspaceEntitlement e : entitlements.findAll()) {
                try {
                    billing.refresh(e);
                    verified++;
                } catch (RuntimeException ex) {
                    // Transient provider failure or one bad row: state untouched (FR-011), sweep continues.
                    log.warn("billing reconcile iteration failed (isolated) {}",
                        StructuredArguments.kv("workspaceId", e.getWorkspaceId()));
                }
            }
            if (verified > 0) {
                log.info("billing reconcile sweep {}", StructuredArguments.kv("verified", verified));
            }
        } finally {
            checkpoints.complete(TASK_NAME);
        }
    }
}
