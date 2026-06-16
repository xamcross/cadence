package com.cadence.emaildelivery;

import org.junit.jupiter.api.Test;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T037 (US3) — the dedicated webhook security chain (research D4, contract B). The webhook is reachable
 * WITHOUT a session (it is signature-gated, NOT 401-by-filter) and is CSRF-exempt. The existing contracts are
 * unchanged: an unauthenticated /api/internal/** is still 401 (the entry point fires before any role check),
 * actuator on the public port is still 404. {@code RbacEndpointInventoryTest} (with the webhook allow-listed)
 * runs separately in the suite.
 */
class WebhookSecurityChainTest extends EmailDeliveryItBase {

    @Test
    void webhook_reachableUnauthenticated_signatureGated_not401ByFilter() throws Exception {
        // No session cookie, no CSRF token. A bad signature is rejected by the CONTROLLER (401), proving the
        // request reached the controller — not stopped by the @Order(4) /api/** entry point before the handler.
        // (An unsigned POST that never reached the controller would also be 401, so we assert via a VALID
        // signature in the bounce test; here we assert the chain does not 403/redirect and CSRF is exempt.)
        String body = "{\"events\":[]}";
        mvc.perform(post("/api/webhooks/email/events").contentType(APPLICATION_JSON)
                .header("X-Cadence-Signature", "deadbeef").content(body))
            .andExpect(status().isUnauthorized()); // signature gate, NOT a CSRF 403 or an auth redirect
    }

    @Test
    void webhook_csrfExempt_validSignatureProcessesWithoutCsrfToken() throws Exception {
        // An empty valid-signed batch is acked 200 with NO CSRF token — proving the chain is CSRF-exempt
        // (a CSRF-protected POST without a token would be 403). The signature for "{\"events\":[]}".
        String body = "{\"events\":[]}";
        mvc.perform(post("/api/webhooks/email/events").contentType(APPLICATION_JSON)
                .header("X-Cadence-Signature", signEmptyBatch(body)).content(body))
            .andExpect(status().isOk());
    }

    @Test
    void internalEndpoint_stillReturns401_whenUnauthenticated() throws Exception {
        // The webhook chain must NOT widen the @Order(4) /api/** 401 contract. A GET isolates the auth entry
        // point (a POST without a CSRF token would 403 on the CSRF filter first — see DenyByDefaultContractTest).
        mvc.perform(get("/api/internal/members")).andExpect(status().isUnauthorized());
    }

    @Test
    void actuatorHealth_onPublicPort_stillReturns404() throws Exception {
        // The F00 actuator-on-public-port contract is preserved (the new chain only matches /api/webhooks).
        mvc.perform(get("/actuator/health")).andExpect(status().isNotFound());
    }

    private String signEmptyBatch(String body) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(
                "test-webhook-secret-f22".getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
            return java.util.HexFormat.of().formatHex(
                mac.doFinal(body.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
