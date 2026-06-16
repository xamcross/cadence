package com.cadence.emaildelivery;

import com.cadence.domain.EmailMessageType;
import com.cadence.domain.RenderedMessage;
import com.cadence.service.EmailDispatchService;
import com.cadence.service.EmailTemplateService;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * T050 — the dispatch metrics increment at the transition points (FR-024 / D11). A consenting send bumps
 * {@code cadence.email.dispatch.sent}; the value-free counter is published on the existing actuator registry.
 */
class EmailDispatchMetricsTest extends EmailDeliveryItBase {

    @Autowired EmailDispatchService dispatch;
    @Autowired MeterRegistry registry;
    @MockBean EmailTemplateService templates;

    @Test
    void sentCounter_incrementsOnSend() {
        seedContactableCandidate("c1", "Dana", "dana@example.com");
        when(templates.renderForSend(eq(WS), eq(EmailMessageType.CONFIRMATION), anyString(), eq("c1"), any()))
            .thenReturn(new RenderedMessage("Subject", "Body", "Body", List.of()));

        double before = registry.get("cadence.email.dispatch.sent").counter().count();
        dispatch.enqueue(WS, "c1", EmailMessageType.CONFIRMATION, "BASE", Instant.now(clock), null, null);
        double after = registry.get("cadence.email.dispatch.sent").counter().count();

        assertThat(after).isEqualTo(before + 1.0);
        // the PENDING-backlog gauge is registered and queryable
        assertThat(registry.get("cadence.email.dispatch.pending").gauge().value()).isGreaterThanOrEqualTo(0.0);
    }
}
