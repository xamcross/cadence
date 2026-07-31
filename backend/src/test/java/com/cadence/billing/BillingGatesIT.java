package com.cadence.billing;

import com.cadence.domain.GatedFeature;
import com.cadence.service.EntitlementService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 032 Task 7 -- the four initiation gates (US2). The ATS connect endpoint is asserted end-to-end
 * (402 envelope, SC-003); the three sweep gates are asserted at the service seam here, with the
 * full sweep behavior covered by each feature suite staying green (no-show/SLA/ATS ITs).
 */
class BillingGatesIT extends BillingItBase {

    @Autowired
    EntitlementService entitlements;

    @Test
    void atsConnect_onFreeWorkspace_is402UpgradeRequired() throws Exception {
        mvc.perform(post("/api/internal/ats/greenhouse/connection").cookie(adminCookie()).with(csrf())
                .contentType("application/json").content("{\"apiKey\":\"gh-key\"}"))
            .andExpect(status().isPaymentRequired())
            .andExpect(jsonPath("$.error", is("upgrade_required")));
    }

    @Test
    void atsConnect_onTeamWorkspace_passesTheGate() throws Exception {
        seedTeam(WS, "L1", Instant.now(clock).plus(Duration.ofDays(30)));
        // The gate must not refuse; downstream credential verification proceeds as before
        // (its own outcome depends on the ATS stubs, so assert only "not 402" here).
        int status = mvc.perform(post("/api/internal/ats/greenhouse/connection").cookie(adminCookie()).with(csrf())
                .contentType("application/json").content("{\"apiKey\":\"gh-key\"}"))
            .andReturn().getResponse().getStatus();
        assertThat(status).isNotEqualTo(402);
    }

    @Test
    void gateChecks_flipWithEntitlement() {
        assertThat(entitlements.hasFeature(WS, GatedFeature.ATS_INTEGRATIONS)).isFalse();
        assertThat(entitlements.hasFeature(WS, GatedFeature.NO_SHOW_DEFENSE)).isFalse();
        assertThat(entitlements.hasFeature(WS, GatedFeature.SLA_NUDGES)).isFalse();
        seedTeam(WS, "L1", Instant.now(clock).plus(Duration.ofDays(30)));
        assertThat(entitlements.hasFeature(WS, GatedFeature.ATS_INTEGRATIONS)).isTrue();
        assertThat(entitlements.hasFeature(WS, GatedFeature.NO_SHOW_DEFENSE)).isTrue();
        assertThat(entitlements.hasFeature(WS, GatedFeature.SLA_NUDGES)).isTrue();
    }
}
