package com.cadence.billing;

import com.cadence.domain.Member;
import com.cadence.domain.Role;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 032 Task 4 -- checkout URL, claim validation, race semantics, role split (US1). */
class BillingClaimIT extends BillingItBase {

    private static final String ACTIVE_TEAM_LICENSE =
        "{\"id\":\"L1\",\"plan_id\":\"2002\",\"user_id\":\"55\",\"expiration\":\"2027-01-15 10:30:00\",\"is_cancelled\":false}";

    private String claimBody(String licenseId) {
        return "{\"licenseId\":\"" + licenseId + "\"}";
    }

    @Test
    void entitlement_isReadableByEveryRole_butDefaultsFree() throws Exception {
        for (Role role : Role.values()) {
            Cookie c = cookie(member(role.name().toLowerCase() + "@x.com", role));
            mvc.perform(get("/api/internal/billing/entitlement").cookie(c))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plan", is("FREE")));
        }
    }

    @Test
    void checkoutSession_adminOnly_returnsHostedUrl() throws Exception {
        mvc.perform(post("/api/internal/billing/checkout-session").cookie(adminCookie()).with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.checkoutUrl", startsWith("https://checkout.freemius.com/product/1001/plan/2002/")));
        Cookie recruiter = cookie(member("rec@x.com", Role.RECRUITER));
        mvc.perform(post("/api/internal/billing/checkout-session").cookie(recruiter).with(csrf()))
            .andExpect(status().isForbidden());
    }

    @Test
    void claim_validLicense_bindsAndReturnsTeam() throws Exception {
        stub.programLicense("L1", ACTIVE_TEAM_LICENSE);
        mvc.perform(post("/api/internal/billing/claim").cookie(adminCookie()).with(csrf())
                .contentType("application/json").content(claimBody("L1")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.plan", is("TEAM")))
            .andExpect(jsonPath("$.status", is("ACTIVE")));
    }

    @Test
    void claim_isIdempotent_forTheSameWorkspaceAndLicense() throws Exception {
        stub.programLicense("L1", ACTIVE_TEAM_LICENSE);
        Cookie admin = adminCookie();
        mvc.perform(post("/api/internal/billing/claim").cookie(admin).with(csrf())
                .contentType("application/json").content(claimBody("L1")))
            .andExpect(status().isOk());
        mvc.perform(post("/api/internal/billing/claim").cookie(admin).with(csrf())
                .contentType("application/json").content(claimBody("L1")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.plan", is("TEAM")));
    }

    @Test
    void claim_typedRefusals() throws Exception {
        Cookie admin = adminCookie();
        // wrong plan
        stub.programLicense("L2", "{\"id\":\"L2\",\"plan_id\":\"9999\",\"user_id\":\"55\",\"expiration\":null,\"is_cancelled\":false}");
        mvc.perform(post("/api/internal/billing/claim").cookie(admin).with(csrf())
                .contentType("application/json").content(claimBody("L2")))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error", is("wrong_plan")));
        // cancelled + past end = inactive
        stub.programLicense("L3", "{\"id\":\"L3\",\"plan_id\":\"2002\",\"user_id\":\"55\",\"expiration\":\"2020-01-01 00:00:00\",\"is_cancelled\":true}");
        mvc.perform(post("/api/internal/billing/claim").cookie(admin).with(csrf())
                .contentType("application/json").content(claimBody("L3")))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error", is("license_inactive")));
        // unknown license id -> provider 404
        mvc.perform(post("/api/internal/billing/claim").cookie(admin).with(csrf())
                .contentType("application/json").content(claimBody("NOPE")))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error", is("invalid_license")));
    }

    @Test
    void claim_licenseBoundElsewhere_andWorkspaceAlreadyUpgraded_areDistinct() throws Exception {
        stub.programLicense("L1", ACTIVE_TEAM_LICENSE);
        seedTeam("other-ws", "L1", Instant.now(clock).plus(Duration.ofDays(30)));
        Cookie admin = adminCookie();
        mvc.perform(post("/api/internal/billing/claim").cookie(admin).with(csrf())
                .contentType("application/json").content(claimBody("L1")))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error", is("license_already_bound")));

        seedTeam(WS, "L9", Instant.now(clock).plus(Duration.ofDays(30)));
        stub.programLicense("L4", "{\"id\":\"L4\",\"plan_id\":\"2002\",\"user_id\":\"55\",\"expiration\":null,\"is_cancelled\":false}");
        mvc.perform(post("/api/internal/billing/claim").cookie(admin).with(csrf())
                .contentType("application/json").content(claimBody("L4")))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error", is("already_upgraded")));
    }

    @Test
    void claim_providerDown_is503_withoutBinding() throws Exception {
        stub.programStatus(500);
        mvc.perform(post("/api/internal/billing/claim").cookie(adminCookie()).with(csrf())
                .contentType("application/json").content(claimBody("L1")))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.error", is("billing_unavailable")));
        mvc.perform(get("/api/internal/billing/entitlement").cookie(adminCookie()))
            .andExpect(jsonPath("$.plan", is("FREE")));
    }
}
