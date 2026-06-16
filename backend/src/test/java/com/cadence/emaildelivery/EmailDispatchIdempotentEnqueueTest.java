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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * T029 (US2) — a duplicate enqueue with the same idempotency key (same workspace/candidate/type/
 * scheduledFor) yields ONE row, ONE accepted transport send, and the second call reports the existing
 * row as an idempotent duplicate (no second send).
 */
class EmailDispatchIdempotentEnqueueTest extends EmailDeliveryItBase {

    @Autowired EmailDispatchService dispatch;
    @MockBean EmailTemplateService templates;

    @Test
    void duplicateEnqueue_oneRow_oneSend() {
        when(templates.renderForSend(anyString(), any(), anyString(), anyString(), any()))
            .thenReturn(new RenderedMessage("S", "B", "B", List.of()));
        seedContactableCandidate("c1", "Dana", "dana@x.com");
        Instant when = Instant.now(clock);

        DispatchResult first = dispatch.enqueue(WS, "c1", EmailMessageType.CONFIRMATION, "BASE", when, null, null);
        DispatchResult second = dispatch.enqueue(WS, "c1", EmailMessageType.CONFIRMATION, "BASE", when, null, null);

        assertThat(first.status()).isEqualTo(DispatchStatus.SENT);
        assertThat(first.idempotentDuplicate()).isFalse();
        assertThat(second.status()).isEqualTo(DispatchStatus.SENT);
        assertThat(second.idempotentDuplicate()).isTrue();
        assertThat(first.dispatchId()).isEqualTo(second.dispatchId());

        assertThat(recordingTransport.sentCount()).isEqualTo(1);
        List<EmailDispatch> rows = mongoTemplate.find(new Query(), EmailDispatch.class);
        assertThat(rows).hasSize(1);
    }
}
