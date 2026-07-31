package com.cadence.billing;

import com.cadence.domain.EntitlementStatus;
import com.cadence.domain.WorkspaceEntitlement;
import com.cadence.scheduler.EntitlementReconciliationScheduler;
import com.cadence.service.BillingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** 032 Task 6 -- nightly re-verify: self-heal, error isolation, never-downgrade-on-error (US3). */
class EntitlementReconcileIT extends BillingItBase {

    @Autowired
    EntitlementReconciliationScheduler scheduler;

    @Autowired
    BillingService billing;

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

    /**
     * 032 round 2: {@code refresh} must CAS on the license it actually verified. {@code fsLicenseId} is mutable
     * (the lapsed-then-repurchase guarded replace), so a stale snapshot -- the nightly {@code findAll}, or a
     * trailing webhook for the OLD license -- must never stamp the old license's EXPIRED state onto a row that
     * has since been rebound to a NEW, active license.
     */
    @Test
    void refresh_staleSnapshot_doesNotStampOldLicenseStateOntoAReboundRow() {
        seedTeam(WS, "L-old", Instant.now(clock).plus(Duration.ofDays(30)));
        WorkspaceEntitlement stale = mongoTemplate.findAll(WorkspaceEntitlement.class).get(0); // the stale snapshot

        // A concurrent repurchase rebinds the SAME row to a new, active license.
        mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is(stale.getId())),
            new Update().set("fsLicenseId", "L-new").set("status", EntitlementStatus.ACTIVE)
                .set("expiresAt", Instant.now(clock).plus(Duration.ofDays(365))),
            WorkspaceEntitlement.class);

        // Provider truth for the OLD license says it is over.
        stub.programLicense("L-old", "{\"id\":\"L-old\",\"plan_id\":\"2002\",\"user_id\":\"55\","
            + "\"expiration\":\"2026-07-01 00:00:00\",\"is_cancelled\":true}");

        billing.refresh(stale); // the stale write must not land

        WorkspaceEntitlement after = mongoTemplate.findById(stale.getId(), WorkspaceEntitlement.class);
        assertThat(after.getFsLicenseId()).isEqualTo("L-new");
        assertThat(after.getStatus()).isEqualTo(EntitlementStatus.ACTIVE);
        assertThat(after.getExpiresAt()).isAfter(Instant.now(clock));
        assertThat(after.getLastVerifiedAt()).isNull(); // never touched by the old license's verification
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
