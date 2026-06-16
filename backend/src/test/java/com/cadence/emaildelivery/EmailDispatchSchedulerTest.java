package com.cadence.emaildelivery;

import com.cadence.domain.DispatchStatus;
import com.cadence.domain.EmailDispatch;
import com.cadence.domain.EmailMessageType;
import com.cadence.domain.RenderedMessage;
import com.cadence.scheduler.EmailDispatchScheduler;
import com.cadence.service.EmailDispatchService;
import com.cadence.service.EmailDispatchService.DispatchResult;
import com.cadence.service.EmailTemplateService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * T046 (US4) — the scheduled worker. A future {@code scheduledFor} row sits PENDING until the time passes,
 * then the sweep fires it exactly once (one transport send). A row whose candidate became non-contactable
 * between enqueue and fire is REFUSED at fire time (the gate is re-evaluated, never cached — FR-007). The
 * test clock is advanced and {@code sweep()} invoked directly (no wall-clock dependence).
 */
class EmailDispatchSchedulerTest extends EmailDeliveryItBase {

    @Autowired EmailDispatchService dispatch;
    @Autowired EmailDispatchScheduler scheduler;
    @MockBean EmailTemplateService templates;

    @Test
    void futureRow_staysPending_thenFiresExactlyOnceAfterTimePasses() {
        seedContactableCandidate("c1", "Dana", "dana@example.com");
        when(templates.renderForSend(eq(WS), eq(EmailMessageType.CONFIRMATION), anyString(), eq("c1"), any()))
            .thenReturn(new RenderedMessage("Subject", "Body", "Body", List.of()));

        Instant future = Instant.now(clock).plus(Duration.ofHours(2));
        DispatchResult enqueued = dispatch.enqueue(
            WS, "c1", EmailMessageType.CONFIRMATION, "BASE", future, null, null);
        assertThat(enqueued.status()).isEqualTo(DispatchStatus.PENDING);

        // Before the time passes: a sweep picks up nothing, the row stays PENDING, zero sends.
        scheduler.sweep();
        assertThat(recordingTransport.sentCount()).isZero();
        assertThat(mongoTemplate.findById(enqueued.dispatchId(), EmailDispatch.class).getStatus())
            .isEqualTo(DispatchStatus.PENDING);

        // Advance past scheduledFor: the sweep fires it exactly once.
        clock.advance(Duration.ofHours(3));
        scheduler.sweep();
        assertThat(recordingTransport.sentCount()).isEqualTo(1);
        assertThat(mongoTemplate.findById(enqueued.dispatchId(), EmailDispatch.class).getStatus())
            .isEqualTo(DispatchStatus.SENT);

        // A second sweep does not resend (terminal row, idempotent).
        scheduler.sweep();
        assertThat(recordingTransport.sentCount()).isEqualTo(1);
    }

    @Test
    void candidateBecameNonContactableBeforeFire_isRefusedAtFireTime() {
        seedContactableCandidate("c1", "Dana", "dana@example.com");
        when(templates.renderForSend(eq(WS), eq(EmailMessageType.CONFIRMATION), anyString(), eq("c1"), any()))
            .thenReturn(new RenderedMessage("Subject", "Body", "Body", List.of()));

        Instant future = Instant.now(clock).plus(Duration.ofHours(2));
        DispatchResult enqueued = dispatch.enqueue(
            WS, "c1", EmailMessageType.CONFIRMATION, "BASE", future, null, null);
        assertThat(enqueued.status()).isEqualTo(DispatchStatus.PENDING);

        // The candidate withdraws consent AFTER enqueue but BEFORE fire.
        var c = mongoTemplate.findById("c1", com.cadence.domain.Candidate.class);
        c.setBasisWithdrawn(true);
        c.setBasisWithdrawnAt(Instant.now(clock));
        mongoTemplate.save(c);

        clock.advance(Duration.ofHours(3));
        scheduler.sweep();

        // Gate re-evaluated at fire time -> REFUSED, no transmission.
        assertThat(recordingTransport.sentCount()).isZero();
        assertThat(mongoTemplate.findById(enqueued.dispatchId(), EmailDispatch.class).getStatus())
            .isEqualTo(DispatchStatus.REFUSED);
    }
}
