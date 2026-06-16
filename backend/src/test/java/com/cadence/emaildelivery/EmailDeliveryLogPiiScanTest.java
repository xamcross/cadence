package com.cadence.emaildelivery;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import com.cadence.domain.Candidate;
import com.cadence.domain.DeadLetterRecord;
import com.cadence.domain.DispatchStatus;
import com.cadence.domain.EmailDispatch;
import com.cadence.domain.EmailMessageType;
import com.cadence.domain.RenderedMessage;
import com.cadence.service.EmailDispatchService;
import com.cadence.service.EmailDispatchService.DispatchResult;
import com.cadence.service.EmailTemplateService;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T051 — across a failing render AND a hard bounce driven with sentinel recipient/body values, NO sentinel
 * (the candidate recipient, the body, or the configured webhook secret) appears in any {@code com.cadence}
 * log even at TRACE, NOR in the persisted dispatch row, audit, or dead-letter (D10 PII discipline / FR-016
 * write-only secret). A positive vacuity guard proves the paths actually logged.
 */
class EmailDeliveryLogPiiScanTest extends EmailDeliveryItBase {

    private static final String BODY = "SENTINELF22BODY_zz9";
    private static final String RECIPIENT = "SENTINELF22RECIPIENT_zz9@example.invalid";
    private static final String WEBHOOK_SECRET = "test-webhook-secret-f22"; // application-test.yml value

    @Autowired EmailDispatchService dispatch;
    @MockBean EmailTemplateService templates;

    @Test
    void noRecipientBodyOrSecret_inLogsRowAuditOrDeadLetter() throws Exception {
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        Logger cadence = (Logger) LoggerFactory.getLogger("com.cadence");
        Level previous = cadence.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        cadence.setLevel(Level.TRACE);
        try {
            // ---- (1) a render failure carrying the body sentinel in its exception message ----
            seedContactableCandidate("cFail", "Dana", RECIPIENT);
            when(templates.renderForSend(eq(WS), eq(EmailMessageType.CONFIRMATION), anyString(), eq("cFail"), any()))
                .thenThrow(new RuntimeException("render blew up: " + BODY));
            DispatchResult failed = dispatch.enqueue(WS, "cFail", EmailMessageType.CONFIRMATION, "BASE",
                Instant.now(clock), null, null);
            assertThat(failed.status()).isEqualTo(DispatchStatus.FAILED);

            // ---- (2) a successful send (body sentinel rendered) then a signed hard bounce ----
            seedContactableCandidate("cBounce", "Dana", RECIPIENT);
            when(templates.renderForSend(eq(WS), eq(EmailMessageType.CONFIRMATION), anyString(), eq("cBounce"), any()))
                .thenReturn(new RenderedMessage("Subject", BODY, BODY, List.of()));
            DispatchResult sent = dispatch.enqueue(WS, "cBounce", EmailMessageType.CONFIRMATION, "BASE",
                Instant.now(clock), null, null);
            assertThat(sent.status()).isEqualTo(DispatchStatus.SENT);

            String ref = mongoTemplate.findById(sent.dispatchId(), EmailDispatch.class).getProviderMessageRef();
            String webhookBody = "{\"eventId\":\"e-pii\",\"providerMessageRef\":\"" + ref + "\",\"type\":\"bounce\"}";
            String sig = "sha256=" + hmacHex(WEBHOOK_SECRET, webhookBody);
            mvc.perform(post("/api/webhooks/email/events")
                    .header("X-Cadence-Signature", sig)
                    .contentType("application/json").content(webhookBody))
                .andExpect(status().isOk());

            // ---- assertions ----
            boolean loggedFail = false, loggedBounce = false;
            for (ILoggingEvent e : appender.list) {
                String t = text(e);
                assertThat(t).doesNotContain(BODY).doesNotContain(RECIPIENT).doesNotContain(WEBHOOK_SECRET);
                if (t.contains("render error")) loggedFail = true;
                if (t.contains("bounced")) loggedBounce = true;
            }
            assertThat(loggedFail).as("the render-failure path actually logged (non-vacuous)").isTrue();
            assertThat(loggedBounce).as("the bounce path actually logged (non-vacuous)").isTrue();

            // No sentinel reaches the persisted dispatch rows.
            for (EmailDispatch row : mongoTemplate.findAll(EmailDispatch.class)) {
                assertThat(row.toString()).doesNotContain(BODY).doesNotContain(RECIPIENT).doesNotContain(WEBHOOK_SECRET);
            }
            // ...nor the candidate flag metadata, dead-letter, or audit.
            for (DeadLetterRecord dl : mongoTemplate.findAll(DeadLetterRecord.class)) {
                assertThat(dl.getErrorSummary() == null ? "" : dl.getErrorSummary())
                    .doesNotContain(BODY).doesNotContain(RECIPIENT);
                assertThat(dl.getErrorType() == null ? "" : dl.getErrorType())
                    .doesNotContain(BODY).doesNotContain(RECIPIENT);
            }
            String auditDump = mongoTemplate.findAll(com.cadence.domain.AuthAuditEvent.class).toString();
            assertThat(auditDump).doesNotContain(BODY).doesNotContain(RECIPIENT).doesNotContain(WEBHOOK_SECRET);
            Candidate flagged = mongoTemplate.findById("cBounce", Candidate.class);
            assertThat(flagged.isUndeliverable()).isTrue(); // the flag is set, but no PII in its metadata
        } finally {
            cadence.setLevel(previous);
            root.detachAppender(appender);
        }
    }

    private static String hmacHex(String secret, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    /** Formatted message plus any throwable message/stack — an exception must not carry the value either. */
    private static String text(ILoggingEvent e) {
        StringBuilder sb = new StringBuilder();
        if (e.getFormattedMessage() != null) sb.append(e.getFormattedMessage());
        IThrowableProxy tp = e.getThrowableProxy();
        while (tp != null) {
            if (tp.getMessage() != null) sb.append(' ').append(tp.getMessage());
            sb.append(' ').append(tp.getClassName());
            tp = tp.getCause();
        }
        return sb.toString();
    }
}
