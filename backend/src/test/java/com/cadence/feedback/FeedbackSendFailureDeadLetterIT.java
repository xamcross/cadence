package com.cadence.feedback;

import com.cadence.domain.DeadLetterRecord;
import com.cadence.domain.Member;
import com.cadence.domain.Role;
import com.cadence.integration.EmailSender;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.mongodb.core.query.Query;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

/**
 * SC-014 (the forced-failure leg, the F22 EmailDeliveryLogPiiScanTest precedent): when the interviewer mail
 * send throws (carrying a PII sentinel in its message), the dead-letter record MUST carry only the PII-free
 * cause-class summary — never the raw exception message that could leak content.
 */
class FeedbackSendFailureDeadLetterIT extends FeedbackItBase {

    private static final String SENTINEL = "SENTINELF32TEXTzz9";

    // Replaces the @Primary SmtpEmailSender for this context so the generation send throws with a PII sentinel.
    @MockBean EmailSender emailSender;

    @Test
    void sendFailure_deadLetterCarriesOnlyCauseClass_noPii() {
        Mockito.doThrow(new RuntimeException(SENTINEL))
            .when(emailSender).sendEmail(anyString(), anyString(), any());

        configuredWorkspace();
        seedCandidate("cand1", "Dana", "dana@example.com");
        Member a = member("a@example.com", Role.INTERVIEWER);
        seedBookedInterview("req1", "cand1", 4);
        seedClaim("req1", a.getId(), Instant.now(clock).minus(Duration.ofHours(4)));

        scheduler.sweep(); // generation send throws -> recorded to the dead letter (best-effort)

        List<DeadLetterRecord> records = mongoTemplate.findAll(DeadLetterRecord.class);
        assertThat(records).isNotEmpty();
        for (DeadLetterRecord r : records) {
            // PII-free cause-class summary only (the caller wraps with IllegalStateException(SimpleName)).
            assertThat(r.getErrorSummary()).doesNotContain(SENTINEL);
            assertThat(r.getErrorType()).doesNotContain(SENTINEL);
            assertThat(r.getErrorSummary()).contains("feedback_request_send_failed");
        }
        // The request row was still created (PENDING) — the reminder is the recovery send (honest bound).
        assertThat(feedbackRepo.findByWorkspaceIdAndInterviewEventId(WS, "req1")).hasSize(1);
        assertThat(mongoTemplate.count(new Query(), DeadLetterRecord.class)).isGreaterThanOrEqualTo(1);
    }
}
