package com.cadence.billing;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 032 live-promotion pre-flight -- blank Freemius ids (missing FREEMIUS_PRODUCT_ID /
 * FREEMIUS_TEAM_PLAN_ID secrets) must fail closed as a clean 503 billing_unavailable, never a
 * redirect to a broken checkout URL. Own Spring context (distinct property set).
 */
class BillingCheckoutMisconfigIT extends BillingItBase {

    @DynamicPropertySource
    static void blankBillingIds(DynamicPropertyRegistry r) {
        r.add("cadence.billing.product-id", () -> "");
        r.add("cadence.billing.team-plan-id", () -> "");
    }

    @Test
    void checkoutSession_withBlankIds_failsClosed503() throws Exception {
        mvc.perform(post("/api/internal/billing/checkout-session").cookie(adminCookie()).with(csrf()))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.error", is("billing_unavailable")));
    }
}
