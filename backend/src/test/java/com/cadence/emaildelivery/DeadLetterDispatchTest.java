package com.cadence.emaildelivery;

import com.cadence.domain.DeadLetterRecord;
import com.cadence.domain.DispatchStatus;
import com.cadence.domain.EmailMessageType;
import com.cadence.domain.RecruiterNotification;
import com.cadence.domain.RecruiterNotificationType;
import com.cadence.domain.RenderedMessage;
import com.cadence.integration.SendOutcome;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * T039 (US3, FR-012) — a terminal dispatch FAILED produces a {@link DeadLetterRecord} (candidate-id ONLY,
 * no PII) and a recruiter notification. The candidate id is a bare 24-hex ObjectId so the DeadLetter
 * sanitiser keeps it (a non-ObjectId would be [REDACTED]). The body sentinel must not leak into the record.
 */
class DeadLetterDispatchTest extends EmailDeliveryItBase {

    private static final String CANDIDATE_ID = "0123456789abcdef01234567"; // 24-hex ObjectId
    private static final String RECIPIENT = "dana@example.com";
    private static final String BODY_SENTINEL = "SENTINELF22BODY_zz9";

    @Autowired EmailDispatchService dispatch;
    @MockBean EmailTemplateService templates;

    @Test
    void terminalFailure_deadLetters_candidateIdOnly_andNotifies() {
        seedContactableCandidate(CANDIDATE_ID, "Dana", RECIPIENT);
        when(templates.renderForSend(eq(WS), eq(EmailMessageType.CONFIRMATION), anyString(), eq(CANDIDATE_ID), any()))
            .thenReturn(new RenderedMessage("Subject", BODY_SENTINEL, BODY_SENTINEL, List.of()));
        // A permanent transport rejection -> SENDING->FAILED + dead-letter (no retry).
        recordingTransport.enqueueOutcome(SendOutcome.permanentFailure("permanent_reject"));

        DispatchResult r = dispatch.enqueue(
            WS, CANDIDATE_ID, EmailMessageType.CONFIRMATION, "BASE", Instant.now(clock), null, null);
        assertThat(r.status()).isEqualTo(DispatchStatus.FAILED);

        List<DeadLetterRecord> records = mongoTemplate.findAll(DeadLetterRecord.class);
        assertThat(records).isNotEmpty();
        DeadLetterRecord rec = records.stream()
            .filter(d -> "email-dispatch".equals(d.getTaskName())).findFirst().orElseThrow();
        assertThat(rec.getAffectedCandidateId()).isEqualTo(CANDIDATE_ID); // bare ObjectId kept (candidate-id only)
        assertThat(rec.getErrorSummary()).doesNotContain(RECIPIENT).doesNotContain(BODY_SENTINEL);
        assertThat(rec.getErrorType()).doesNotContain(RECIPIENT).doesNotContain(BODY_SENTINEL);

        List<RecruiterNotification> notes = mongoTemplate.findAll(RecruiterNotification.class);
        assertThat(notes).extracting(RecruiterNotification::getType)
            .contains(RecruiterNotificationType.DISPATCH_FAILED);
        assertThat(notes).allMatch(n -> n.getCandidateId().equals(CANDIDATE_ID));
    }
}
