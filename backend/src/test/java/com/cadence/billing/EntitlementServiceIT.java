package com.cadence.billing;

import com.cadence.api.BillingExceptions;
import com.cadence.domain.BillingPlan;
import com.cadence.domain.GatedFeature;
import com.cadence.service.EntitlementService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 032 Task 3 -- plan resolution and feature gating (FR-001/FR-003), clock-driven expiry. */
class EntitlementServiceIT extends BillingItBase {

    @Autowired
    EntitlementService entitlements;

    @Test
    void noRow_meansFree_andEveryGateRefuses() {
        assertThat(entitlements.planOf(WS)).isEqualTo(BillingPlan.FREE);
        for (GatedFeature f : GatedFeature.values()) {
            assertThat(entitlements.hasFeature(WS, f)).isFalse();
            assertThatThrownBy(() -> entitlements.requireFeature(WS, f))
                .isInstanceOf(BillingExceptions.UpgradeRequiredException.class);
        }
    }

    @Test
    void boundRow_confersTeam_andEveryGatePasses() {
        seedTeam(WS, "L1", Instant.now(clock).plus(Duration.ofDays(30)));
        assertThat(entitlements.planOf(WS)).isEqualTo(BillingPlan.TEAM);
        for (GatedFeature f : GatedFeature.values()) {
            assertThatCode(() -> entitlements.requireFeature(WS, f)).doesNotThrowAnyException();
        }
    }

    @Test
    void expiryIsClockDriven_teamDropsToFree_whenClockPassesExpiresAt() {
        seedTeam(WS, "L1", Instant.now(clock).plus(Duration.ofDays(30)));
        assertThat(entitlements.planOf(WS)).isEqualTo(BillingPlan.TEAM);
        clock.advance(Duration.ofDays(31));
        assertThat(entitlements.planOf(WS)).isEqualTo(BillingPlan.FREE);
    }

    @Test
    void lifetimeLicense_neverExpires() {
        seedTeam(WS, "L1", null);
        clock.advance(Duration.ofDays(3650));
        assertThat(entitlements.planOf(WS)).isEqualTo(BillingPlan.TEAM);
    }
}
