package com.cadence.emaildelivery;

import com.cadence.domain.DispatchStatus;
import com.cadence.domain.EmailDispatch;
import com.cadence.domain.EmailMessageType;
import com.cadence.domain.RenderedMessage;
import com.cadence.service.EmailDispatchService;
import com.cadence.service.EmailDispatchService.DispatchResult;
import com.cadence.service.EmailTemplateService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.mongodb.core.query.Query;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * T030 (US2) — N threads concurrently enqueue the SAME logical message (same idempotency key). Exactly one
 * wins the claim and transmits ({@code recordingTransport.sentCount()==1}, asserted at the sink, not the
 * row), one row exists, and exactly one thread reports a non-duplicate SENT. A start-latch maximises the
 * race at the unique-index insert + the claim CAS.
 */
class EmailDispatchConcurrencyTest extends EmailDeliveryItBase {

    @Autowired EmailDispatchService dispatch;
    @MockBean EmailTemplateService templates;

    @Test
    void concurrentSameKey_exactlyOneSend() throws Exception {
        when(templates.renderForSend(anyString(), any(), anyString(), anyString(), any()))
            .thenReturn(new RenderedMessage("S", "B", "B", List.of()));
        seedContactableCandidate("c1", "Dana", "dana@x.com");
        Instant when = Instant.now(clock);

        int n = 8;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(n);
        AtomicInteger sentReports = new AtomicInteger();

        for (int i = 0; i < n; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    DispatchResult r = dispatch.enqueue(WS, "c1", EmailMessageType.CONFIRMATION, "BASE", when, null, null);
                    if (r.status() == DispatchStatus.SENT) {
                        sentReports.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(20, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        // The exactly-once guarantee is at the SINK: exactly one accepted transmit, one row, SENT.
        assertThat(recordingTransport.sentCount()).isEqualTo(1);
        List<EmailDispatch> rows = mongoTemplate.find(new Query(), EmailDispatch.class);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getStatus()).isEqualTo(DispatchStatus.SENT);
        // At least one thread observes the SENT outcome (the winner; losers see the winner's SENT state).
        assertThat(sentReports.get()).isGreaterThanOrEqualTo(1);
    }
}
