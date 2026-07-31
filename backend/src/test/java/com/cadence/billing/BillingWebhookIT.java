package com.cadence.billing;

import com.cadence.domain.BillingWebhookEvent;
import com.cadence.domain.EntitlementStatus;
import com.cadence.domain.WorkspaceEntitlement;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 032 Task 5 -- HMAC verify, replay suppression, poke->refresh, unbound ack (US3). */
class BillingWebhookIT extends BillingItBase {

    private static final String PATH = "/api/webhooks/billing/freemius";

    private String sign(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec("test-billing-webhook-secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String event(String eventId, String type, String licenseId) {
        return "{\"id\":\"" + eventId + "\",\"type\":\"" + type + "\","
            + "\"objects\":{\"license\":{\"id\":\"" + licenseId + "\"}},"
            + "\"user_email\":\"SENTINEL@pii.test\"}";
    }

    @Test
    void invalidSignature_is401_andNothingProcessed() throws Exception {
        String body = event("E1", "license.cancelled", "L1");
        mvc.perform(post(PATH).contentType(APPLICATION_JSON)
                .header("X-Signature", "deadbeef").content(body))
            .andExpect(status().isUnauthorized());
        mvc.perform(post(PATH).contentType(APPLICATION_JSON).content(body))
            .andExpect(status().isUnauthorized());
        assertThat(mongoTemplate.findAll(BillingWebhookEvent.class)).isEmpty();
    }

    @Test
    void rejectedWebhook_logsPiiFreeWarn() throws Exception {
        // A misconfigured FREEMIUS_WEBHOOK_SECRET must not fail 100% silently (live-promotion
        // pre-flight): one fixed warn line, never the secret/signature/body.
        ch.qos.logback.classic.Logger controllerLogger = (ch.qos.logback.classic.Logger)
            org.slf4j.LoggerFactory.getLogger(com.cadence.api.FreemiusWebhookController.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
            new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        controllerLogger.addAppender(appender);
        try {
            String body = event("E-log", "license.cancelled", "L1");
            mvc.perform(post(PATH).contentType(APPLICATION_JSON)
                    .header("X-Signature", "deadbeef").content(body))
                .andExpect(status().isUnauthorized());
            assertThat(appender.list)
                .anySatisfy(e -> {
                    assertThat(e.getLevel()).isEqualTo(ch.qos.logback.classic.Level.WARN);
                    assertThat(e.getFormattedMessage()).contains("rejected");
                    assertThat(e.getFormattedMessage()).doesNotContain("deadbeef");
                    assertThat(e.getFormattedMessage()).doesNotContain("SENTINEL");
                });
        } finally {
            controllerLogger.detachAppender(appender);
        }
    }

    @Test
    void boundLicenseEvent_refetchesTruth_andUpdatesEntitlement() throws Exception {
        seedTeam(WS, "L1", Instant.now(clock).plus(Duration.ofDays(30)));
        stub.programLicense("L1", "{\"id\":\"L1\",\"plan_id\":\"2002\",\"user_id\":\"55\","
            + "\"expiration\":\"2026-08-30 00:00:00\",\"is_cancelled\":true}");
        String body = event("E2", "license.cancelled", "L1");
        mvc.perform(post(PATH).contentType(APPLICATION_JSON)
                .header("X-Signature", sign(body)).content(body))
            .andExpect(status().isOk());
        WorkspaceEntitlement e = mongoTemplate.findAll(WorkspaceEntitlement.class).get(0);
        assertThat(e.getStatus()).isEqualTo(EntitlementStatus.CANCELLED);   // truth from API, not payload
        assertThat(mongoTemplate.findAll(BillingWebhookEvent.class)).hasSize(1);
    }

    @Test
    void replayedEventId_isIdempotent_noSecondProviderCall() throws Exception {
        seedTeam(WS, "L1", Instant.now(clock).plus(Duration.ofDays(30)));
        stub.programLicense("L1", "{\"id\":\"L1\",\"plan_id\":\"2002\",\"user_id\":\"55\","
            + "\"expiration\":null,\"is_cancelled\":false}");
        String body = event("E3", "license.updated", "L1");
        mvc.perform(post(PATH).contentType(APPLICATION_JSON)
                .header("X-Signature", sign(body)).content(body)).andExpect(status().isOk());
        int callsAfterFirst = stub.requestCount();
        mvc.perform(post(PATH).contentType(APPLICATION_JSON)
                .header("X-Signature", sign(body)).content(body)).andExpect(status().isOk());
        assertThat(stub.requestCount()).isEqualTo(callsAfterFirst);
        assertThat(mongoTemplate.findAll(BillingWebhookEvent.class)).hasSize(1);
    }

    @Test
    void unboundLicense_andIrrelevantType_areAcked_withoutStateChange() throws Exception {
        String unbound = event("E4", "license.created", "L-unbound");
        mvc.perform(post(PATH).contentType(APPLICATION_JSON)
                .header("X-Signature", sign(unbound)).content(unbound))
            .andExpect(status().isOk());
        String irrelevant = "{\"id\":\"E5\",\"type\":\"user.updated\"}";
        mvc.perform(post(PATH).contentType(APPLICATION_JSON)
                .header("X-Signature", sign(irrelevant)).content(irrelevant))
            .andExpect(status().isOk());
        assertThat(mongoTemplate.findAll(WorkspaceEntitlement.class)).isEmpty();
    }

    @Test
    void providerDownDuringRefresh_is503_andNoDedupRow_soRetryReprocesses() throws Exception {
        seedTeam(WS, "L1", Instant.now(clock).plus(Duration.ofDays(30)));
        stub.programStatus(500);
        String body = event("E6", "license.expired", "L1");
        mvc.perform(post(PATH).contentType(APPLICATION_JSON)
                .header("X-Signature", sign(body)).content(body))
            .andExpect(status().isServiceUnavailable());
        assertThat(mongoTemplate.findAll(BillingWebhookEvent.class)).isEmpty();
        WorkspaceEntitlement e = mongoTemplate.findAll(WorkspaceEntitlement.class).get(0);
        assertThat(e.getStatus()).isEqualTo(EntitlementStatus.ACTIVE);      // never downgraded on error
    }
}
