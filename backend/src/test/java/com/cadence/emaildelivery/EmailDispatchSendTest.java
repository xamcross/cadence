package com.cadence.emaildelivery;

import com.cadence.domain.AuthAuditEvent;
import com.cadence.domain.AuthEventType;
import com.cadence.domain.DispatchOutcomeReason;
import com.cadence.domain.DispatchStatus;
import com.cadence.domain.EmailDispatch;
import com.cadence.domain.EmailMessageType;
import com.cadence.domain.RenderedMessage;
import com.cadence.integration.OutboundEmail;
import com.cadence.service.EmailDispatchService;
import com.cadence.service.EmailDispatchService.DispatchResult;
import com.cadence.service.EmailTemplateService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * T020 (US1) — a consenting candidate sends: status SENT, one accepted transport send, an
 * EMAIL_DISPATCH_SENT audit, and NO PII on the persisted row (recipient/subject/body live only in the
 * transient OutboundEmail at the sink). SC-001 inline-latency is asserted against the recording sink.
 * Plus the render-failure edge: a template that fails to render -> FAILED/RENDER_FAILED, zero sends.
 *
 * <p>EmailTemplateService is mocked so the render outcome is controlled (the F21 renderer has its own
 * unit suite); the rest of the dispatch path (gate, transport, outbox CAS, audit) is real.
 */
class EmailDispatchSendTest extends EmailDeliveryItBase {

    private static final String BODY_SENTINEL = "SENTINELF22BODY_zz9";
    private static final String RECIPIENT = "dana@example.com";

    @Autowired EmailDispatchService dispatch;
    @MockBean EmailTemplateService templates;

    private DispatchResult send() {
        return dispatch.enqueue(WS, "c1", EmailMessageType.CONFIRMATION, "BASE", Instant.now(clock), null, null);
    }

    @Test
    void consentingCandidate_sends_audits_noPiiOnRow() {
        seedContactableCandidate("c1", "Dana", RECIPIENT);
        when(templates.renderForSend(eq(WS), eq(EmailMessageType.CONFIRMATION), anyString(), eq("c1"), any()))
            .thenReturn(new RenderedMessage("Subject", BODY_SENTINEL, BODY_SENTINEL, List.of()));

        long start = System.nanoTime();
        DispatchResult r = send();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(r.status()).isEqualTo(DispatchStatus.SENT);
        assertThat(recordingTransport.sentCount()).isEqualTo(1);
        assertThat(elapsedMs).isLessThan(60_000); // SC-001 inline-latency bound (against the recording sink)

        // the transmitted message carries the recipient + body...
        OutboundEmail sent = recordingTransport.messages().get(0);
        assertThat(sent.toAddress()).isEqualTo(RECIPIENT);
        assertThat(sent.htmlBody()).contains(BODY_SENTINEL);

        // ...but the persisted outbox row carries NO recipient/subject/body (only ids/status).
        EmailDispatch row = mongoTemplate.findById(r.dispatchId(), EmailDispatch.class);
        assertThat(row.getStatus()).isEqualTo(DispatchStatus.SENT);
        assertThat(row.getSentAt()).isNotNull();
        assertThat(row.getProviderMessageRef()).isNotBlank();
        assertThat(row.toString()).doesNotContain(RECIPIENT).doesNotContain(BODY_SENTINEL);

        List<AuthAuditEvent> audits = mongoTemplate.find(
            Query.query(Criteria.where("eventType").is(AuthEventType.EMAIL_DISPATCH_SENT)), AuthAuditEvent.class);
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0).getOutcome()).isEqualTo(EmailMessageType.CONFIRMATION.name());
    }

    @Test
    void renderFailure_failsClosed_zeroSends() {
        seedContactableCandidate("c1", "Dana", RECIPIENT);
        when(templates.renderForSend(eq(WS), eq(EmailMessageType.CONFIRMATION), anyString(), eq("c1"), any()))
            .thenThrow(new RuntimeException("boom " + BODY_SENTINEL)); // sentinel must not leak to logs/row

        DispatchResult r = send();
        assertThat(r.status()).isEqualTo(DispatchStatus.FAILED);
        assertThat(r.reason()).isEqualTo(DispatchOutcomeReason.RENDER_FAILED);
        // No broken message reaches the candidate (a dead-letter ops alert may go to the ops address).
        assertThat(recordingTransport.callsTo(RECIPIENT)).isZero();

        EmailDispatch row = mongoTemplate.findById(r.dispatchId(), EmailDispatch.class);
        assertThat(row.getStatus()).isEqualTo(DispatchStatus.FAILED);
        assertThat(row.getLastOutcomeReason()).isEqualTo(DispatchOutcomeReason.RENDER_FAILED);
        assertThat(row.toString()).doesNotContain(BODY_SENTINEL);
    }
}
