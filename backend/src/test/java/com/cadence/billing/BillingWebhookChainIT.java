package com.cadence.billing;

import org.junit.jupiter.api.Test;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 032 -- the WebhookSecurityChainTest analogue: CSRF-exempt webhook, nothing else widened. */
class BillingWebhookChainIT extends BillingItBase {

    @Test
    void webhook_isCsrfExempt_badSignatureStillRejected401() throws Exception {
        mvc.perform(post("/api/webhooks/billing/freemius").contentType(APPLICATION_JSON)
                .header("X-Signature", "00").content("{}"))
            .andExpect(status().isUnauthorized()); // reached the controller without a CSRF token
    }

    @Test
    void internalEndpoints_stillRequireAuth() throws Exception {
        mvc.perform(get("/api/internal/billing/entitlement"))
            .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/internal/members"))
            .andExpect(status().isUnauthorized());
    }
}
