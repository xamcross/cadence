package com.cadence.emaildelivery;

import com.cadence.config.EmailDeliveryProperties;
import com.cadence.domain.DispatchOutcomeReason;
import com.cadence.domain.DispatchStatus;
import com.cadence.domain.EmailDispatch;
import com.cadence.domain.EmailMessageType;
import com.cadence.domain.RenderedMessage;
import com.cadence.integration.SendOutcome;
import com.cadence.scheduler.EmailDispatchReaper;
import com.cadence.service.EmailDispatchService;
import com.cadence.service.EmailDispatchService.DispatchResult;
import com.cadence.service.EmailTemplateService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * T031 (US2) — driven deterministically by the test clock / updatedAt, never sleeps:
 * <ul>
 *   <li>a row stuck SENDING (updatedAt stamped into the past) -> the reaper marks it SENT_UNCONFIRMED
 *       with NO resend (FR-010);</li>
 *   <li>a transient transport failure re-queues then recovers to a single SENT on the next attempt.</li>
 * </ul>
 */
class EmailDispatchCrashWindowTest extends EmailDeliveryItBase {

    @Autowired EmailDispatchService dispatch;
    @Autowired EmailDispatchReaper reaper;
    @Autowired EmailDeliveryProperties props;
    @MockBean EmailTemplateService templates;

    private void stubRender() {
        when(templates.renderForSend(anyString(), any(), anyString(), anyString(), any()))
            .thenReturn(new RenderedMessage("S", "B", "B", List.of()));
    }

    @Test
    void staleSending_reapedToUnconfirmed_noResend() {
        // A crash-window row: SENDING with updatedAt well past the reaper threshold.
        EmailDispatch row = new EmailDispatch();
        row.setWorkspaceId(WS);
        row.setCandidateId("c1");
        row.setMessageType(EmailMessageType.CONFIRMATION);
        row.setStageKey("BASE");
        row.setIdempotencyKey("stale-key-1");
        row.setStatus(DispatchStatus.SENDING);
        row.setAttemptCount(1);
        Instant past = Instant.now(clock).minus(props.getReaperThreshold()).minusSeconds(60);
        row.setScheduledFor(past);
        row.setNextAttemptAt(past);
        row.setCreatedAt(past);
        row.setUpdatedAt(past);
        EmailDispatch saved = mongoTemplate.save(row);

        long reaped = reaper.reap();

        assertThat(reaped).isEqualTo(1);
        EmailDispatch after = mongoTemplate.findById(saved.getId(), EmailDispatch.class);
        assertThat(after.getStatus()).isEqualTo(DispatchStatus.SENT_UNCONFIRMED);
        assertThat(recordingTransport.totalCalls()).isZero(); // NO resend
    }

    @Test
    void freshSending_notReaped() {
        EmailDispatch row = new EmailDispatch();
        row.setWorkspaceId(WS);
        row.setCandidateId("c1");
        row.setMessageType(EmailMessageType.CONFIRMATION);
        row.setStageKey("BASE");
        row.setIdempotencyKey("fresh-key-1");
        row.setStatus(DispatchStatus.SENDING);
        Instant now = Instant.now(clock);
        row.setScheduledFor(now);
        row.setNextAttemptAt(now);
        row.setCreatedAt(now);
        row.setUpdatedAt(now); // fresh -> within threshold
        EmailDispatch saved = mongoTemplate.save(row);

        assertThat(reaper.reap()).isZero();
        assertThat(mongoTemplate.findById(saved.getId(), EmailDispatch.class).getStatus())
            .isEqualTo(DispatchStatus.SENDING);
    }

    @Test
    void transientFailure_recoversToSingleSent() {
        stubRender();
        seedContactableCandidate("c1", "Dana", "dana@x.com");
        // First transport attempt is a transient failure; the row re-queues PENDING (retryBaseBackoff=PT0S).
        recordingTransport.enqueueOutcome(SendOutcome.transientFailure("temp"));

        Instant when = Instant.now(clock);
        DispatchResult first = dispatch.enqueue(WS, "c1", EmailMessageType.CONFIRMATION, "BASE", when, null, null);
        assertThat(first.status()).isEqualTo(DispatchStatus.PENDING);
        assertThat(first.reason()).isEqualTo(DispatchOutcomeReason.TRANSPORT_REJECTED);

        // The row is due again (PT0S backoff). Re-run the same dispatch -> accepted -> single SENT.
        DispatchResult second = dispatch.dispatch(first.dispatchId(), null);
        assertThat(second.status()).isEqualTo(DispatchStatus.SENT);

        assertThat(recordingTransport.sentCount()).isEqualTo(1); // exactly one accepted send
        List<EmailDispatch> rows = mongoTemplate.find(new org.springframework.data.mongodb.core.query.Query(),
            EmailDispatch.class);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getStatus()).isEqualTo(DispatchStatus.SENT);
    }
}
