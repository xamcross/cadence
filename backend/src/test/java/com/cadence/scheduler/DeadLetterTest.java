package com.cadence.scheduler;

import com.cadence.BaseIntegrationTest;
import com.cadence.domain.DeadLetterRecord;
import com.cadence.integration.EmailSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

class DeadLetterTest extends BaseIntegrationTest {

    @MockBean
    private EmailSender emailSender;

    @Autowired
    private DeadLetterService deadLetterService;

    private static final Pattern EMAIL_PATTERN =
        Pattern.compile("[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}");

    @BeforeEach
    void setUp() {
        // Delete documents rather than dropping the collection: the singleton container is
        // shared across all test classes, so dropping collections would destroy
        // Mongock-created indexes that other tests rely on. Removing documents is sufficient.
        mongoTemplate.remove(new Query(), DeadLetterRecord.class);
    }

    @Test
    void deadLetterRecordIsWrittenAndAlertSentOnUncaughtException() {
        RuntimeException ex = new RuntimeException("Connection to service failed");

        deadLetterService.recordFailure("testSchedulerTask", ex, null);

        List<DeadLetterRecord> records = mongoTemplate.findAll(DeadLetterRecord.class);
        assertThat(records).hasSize(1);

        DeadLetterRecord record = records.get(0);
        assertThat(record.getTaskName()).isEqualTo("testSchedulerTask");
        assertThat(record.getErrorType()).isEqualTo(RuntimeException.class.getName());
        assertThat(record.getErrorSummary()).isEqualTo("Connection to service failed");

        ArgumentCaptor<String> taskNameCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> summaryCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailSender).sendSystemAlert(taskNameCaptor.capture(), summaryCaptor.capture());

        assertThat(taskNameCaptor.getValue()).isEqualTo("testSchedulerTask");
        assertThat(summaryCaptor.getValue()).isEqualTo("Connection to service failed");
    }

    @Test
    void errorSummaryWithEmailIsRedacted() {
        RuntimeException ex = new RuntimeException("Failed to email test@example.com regarding interview");

        deadLetterService.recordFailure("emailTask", ex, null);

        List<DeadLetterRecord> records = mongoTemplate.find(
            Query.query(Criteria.where("taskName").is("emailTask")), DeadLetterRecord.class);
        assertThat(records).hasSize(1);

        String summary = records.get(0).getErrorSummary();
        assertThat(EMAIL_PATTERN.matcher(summary).find())
            .as("errorSummary must not contain an email address")
            .isFalse();
        assertThat(summary).contains("[REDACTED]");
    }

    @Test
    void deadLetterRecordIsPersistedEvenWhenAlertSenderThrows() {
        // The dead-letter write must never be aborted by an alert-channel failure (T038).
        doThrow(new RuntimeException("smtp unavailable"))
            .when(emailSender).sendSystemAlert(anyString(), anyString());

        RuntimeException ex = new RuntimeException("Processing failed");
        deadLetterService.recordFailure("resilientTask", ex, null);

        List<DeadLetterRecord> records = mongoTemplate.find(
            Query.query(Criteria.where("taskName").is("resilientTask")), DeadLetterRecord.class);
        assertThat(records).hasSize(1);
        // Alert failed, so alertSentAt must remain null — but the record still exists.
        assertThat(records.get(0).getAlertSentAt()).isNull();
    }

    @Test
    void nonObjectIdCandidateIdIsRedacted() {
        // A caller that mistakenly passes an email (or any non-ObjectId) as the candidate
        // identifier must not leak it into storage (§VIII zero-PII).
        RuntimeException ex = new RuntimeException("Processing failed");
        deadLetterService.recordFailure("redactTask", ex, "john.doe@example.com");

        List<DeadLetterRecord> records = mongoTemplate.find(
            Query.query(Criteria.where("taskName").is("redactTask")), DeadLetterRecord.class);
        assertThat(records).hasSize(1);

        String candidateId = records.get(0).getAffectedCandidateId();
        assertThat(candidateId).isEqualTo("[REDACTED]");
        assertThat(EMAIL_PATTERN.matcher(candidateId).find()).isFalse();
    }

    @Test
    void affectedCandidateIdIsInternalObjectIdNotEmail() {
        RuntimeException ex = new RuntimeException("Processing failed");
        String internalId = "507f1f77bcf86cd799439011";

        deadLetterService.recordFailure("candidateTask", ex, internalId);

        List<DeadLetterRecord> records = mongoTemplate.find(
            Query.query(Criteria.where("taskName").is("candidateTask")), DeadLetterRecord.class);
        assertThat(records).hasSize(1);

        String candidateId = records.get(0).getAffectedCandidateId();
        assertThat(candidateId).isEqualTo(internalId);
        assertThat(EMAIL_PATTERN.matcher(candidateId).find())
            .as("affectedCandidateId must not be an email address")
            .isFalse();
    }
}
