package com.cadence.emaildelivery;

import com.cadence.config.EmailDeliveryProperties;
import com.cadence.domain.AuthAuditEvent;
import com.cadence.domain.AuthEventType;
import com.cadence.domain.Candidate;
import com.cadence.domain.DispatchOutcomeReason;
import com.cadence.domain.DispatchStatus;
import com.cadence.domain.EmailMessageType;
import com.cadence.domain.ErasureState;
import com.cadence.service.EmailDispatchService;
import com.cadence.service.EmailDispatchService.DispatchResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T019 (US1) — every consent-gate refusal reason yields REFUSED, zero transport sends, and a value-free
 * EMAIL_DISPATCH_REFUSED audit; plus FR-004: a workspace with no email-provider config fails cleanly with
 * NO_PROVIDER_CONFIG recorded and no transmit (no silent drop).
 */
class EmailDispatchConsentGateTest extends EmailDeliveryItBase {

    @Autowired EmailDispatchService dispatch;
    @Autowired EmailDeliveryProperties props;

    private DispatchResult send(String candidateId) {
        return dispatch.enqueue(WS, candidateId, EmailMessageType.CONFIRMATION, "BASE",
            Instant.now(clock), null, null);
    }

    private void assertRefused(String candidateId, DispatchOutcomeReason expected) {
        DispatchResult r = send(candidateId);
        assertThat(r.status()).isEqualTo(DispatchStatus.REFUSED);
        assertThat(r.reason()).isEqualTo(expected);
        assertThat(recordingTransport.totalCalls()).isZero();
        assertThat(recordingTransport.sentCount()).isZero();
        // value-free audit: outcome is the reason literal, no PII
        List<AuthAuditEvent> audits = mongoTemplate.find(
            Query.query(Criteria.where("eventType").is(AuthEventType.EMAIL_DISPATCH_REFUSED)), AuthAuditEvent.class);
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0).getOutcome()).isEqualTo(expected.name());
    }

    @Test
    void erased_refused() {
        Candidate c = newCandidate("c1", "Dana", "dana@x.com");
        c.setErasureState(ErasureState.ERASED);
        mongoTemplate.save(c);
        assertRefused("c1", DispatchOutcomeReason.ERASED);
    }

    @Test
    void overRetention_refused() {
        Candidate c = newCandidate("c1", "Dana", "dana@x.com");
        c.setLawfulBasis(com.cadence.domain.LawfulBasis.CONSENT);
        c.setRetentionFlagged(true);
        mongoTemplate.save(c);
        assertRefused("c1", DispatchOutcomeReason.OVER_RETENTION);
    }

    @Test
    void withdrawn_refused() {
        Candidate c = newCandidate("c1", "Dana", "dana@x.com");
        c.setLawfulBasis(com.cadence.domain.LawfulBasis.CONSENT);
        c.setBasisWithdrawn(true);
        mongoTemplate.save(c);
        assertRefused("c1", DispatchOutcomeReason.WITHDRAWN);
    }

    @Test
    void noBasis_refused() {
        mongoTemplate.save(newCandidate("c1", "Dana", "dana@x.com")); // lawfulBasis null
        assertRefused("c1", DispatchOutcomeReason.NO_BASIS);
    }

    @Test
    void missingCandidate_isScopedNotFound_oracleFree() {
        // A missing/foreign candidate is a 404 (ScopedNotFoundException), NOT a gate refusal — and never transmits.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> send("c-ghost"))
            .isInstanceOf(com.cadence.api.RbacExceptions.ScopedNotFoundException.class);
        assertThat(recordingTransport.totalCalls()).isZero();
    }

    @Test
    void undeliverable_refused() {
        Candidate c = newCandidate("c1", "Dana", "dana@x.com");
        c.setLawfulBasis(com.cadence.domain.LawfulBasis.CONSENT);
        c.setUndeliverable(true);
        mongoTemplate.save(c);
        assertRefused("c1", DispatchOutcomeReason.UNDELIVERABLE);
    }

    @Test
    void noProviderConfig_failsCleanly_noTransmit() {
        // Blank the app-level default password so no sender resolves (FR-004).
        String saved = props.getSmtp().getPassword();
        props.getSmtp().setPassword("");
        try {
            seedContactableCandidate("c1", "Dana", "dana@x.com");
            DispatchResult r = send("c1");
            assertThat(r.status()).isEqualTo(DispatchStatus.FAILED);
            assertThat(r.reason()).isEqualTo(DispatchOutcomeReason.NO_PROVIDER_CONFIG);
            // No candidate transmit (a dead-letter ops alert is a separate operational concern).
            assertThat(recordingTransport.callsTo("dana@x.com")).isZero();
            List<AuthAuditEvent> audits = mongoTemplate.find(
                Query.query(Criteria.where("eventType").is(AuthEventType.EMAIL_DISPATCH_FAILED)), AuthAuditEvent.class);
            assertThat(audits).hasSize(1);
            assertThat(audits.get(0).getOutcome()).isEqualTo(DispatchOutcomeReason.NO_PROVIDER_CONFIG.name());
        } finally {
            props.getSmtp().setPassword(saved);
        }
    }
}
