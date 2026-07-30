package com.cadence.billing;

import com.cadence.domain.EntitlementStatus;
import com.cadence.domain.WorkspaceEntitlement;
import com.cadence.scheduler.EntitlementReconciliationScheduler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** 032 Task 6 -- nightly re-verify: self-heal, error isolation, never-downgrade-on-error (US3). */
class EntitlementReconcileIT extends BillingItBase {

    @Autowired
    EntitlementReconciliationScheduler scheduler;

    @Test
    void sweep_selfHeals_missedCancellation() {
        seedTeam(WS, "L1", Instant.now(clock).plus(Duration.ofDays(30)));
        stub.programLicense("L1", "{\"id\":\"L1\",\"plan_id\":\"2002\",\"user_id\":\"55\","
            + "\"expiration\":\"2026-08-30 00:00:00\",\"is_cancelled\":true}");
        scheduler.sweep();
        assertThat(mongoTemplate.findAll(WorkspaceEntitlement.class).get(0).getStatus())
            .isEqualTo(EntitlementStatus.CANCELLED);
    }

    @Test
    void sweep_providerDown_leavesStateUntouched_andCompletes() {
        seedTeam(WS, "L1", Instant.now(clock).plus(Duration.ofDays(30)));
        stub.programStatus(500);
        scheduler.sweep(); // must not throw -- per-row isolation
        WorkspaceEntitlement e = mongoTemplate.findAll(WorkspaceEntitlement.class).get(0);
        assertThat(e.getStatus()).isEqualTo(EntitlementStatus.ACTIVE);
        assertThat(e.getLastVerifiedAt()).isNull(); // untouched, not stamped
    }

    @Test
    void sweep_oneBadRow_doesNotStarveOthers() {
        seedTeam("ws-a", "LA", Instant.now(clock).plus(Duration.ofDays(30)));
        seedTeam("ws-b", "LB", Instant.now(clock).plus(Duration.ofDays(30)));
        // LA missing from the stub -> 404 (fatal) on that row; LB programmed and cancelled.
        stub.programLicense("LB", "{\"id\":\"LB\",\"plan_id\":\"2002\",\"user_id\":\"55\","
            + "\"expiration\":null,\"is_cancelled\":true}");
        scheduler.sweep();
        WorkspaceEntitlement b = mongoTemplate.findOne(
            org.springframework.data.mongodb.core.query.Query.query(
                org.springframework.data.mongodb.core.query.Criteria.where("workspaceId").is("ws-b")),
            WorkspaceEntitlement.class);
        assertThat(b.getStatus()).isEqualTo(EntitlementStatus.CANCELLED);
    }

    @Test
    void sweep_isIdempotent_onDoubleRun() {
        seedTeam(WS, "L1", Instant.now(clock).plus(Duration.ofDays(30)));
        stub.programLicense("L1", "{\"id\":\"L1\",\"plan_id\":\"2002\",\"user_id\":\"55\","
            + "\"expiration\":null,\"is_cancelled\":false}");
        scheduler.sweep();
        scheduler.sweep(); // replay proxy -- no further effect
        assertThat(mongoTemplate.findAll(WorkspaceEntitlement.class)).hasSize(1);
    }
}
