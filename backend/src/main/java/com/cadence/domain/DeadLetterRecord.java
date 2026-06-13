package com.cadence.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Document(collection = "deadLetterRecords")
public class DeadLetterRecord {

    @Id
    private String id;

    private String taskName;
    private Instant failedAt;
    private String errorType;
    private String errorSummary;
    private String affectedCandidateId;
    private Instant alertSentAt;

    public DeadLetterRecord() {}

    public DeadLetterRecord(String taskName, Instant failedAt, String errorType, String errorSummary) {
        this.taskName = taskName;
        this.failedAt = failedAt;
        this.errorType = errorType;
        this.errorSummary = errorSummary;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTaskName() { return taskName; }
    public void setTaskName(String taskName) { this.taskName = taskName; }

    public Instant getFailedAt() { return failedAt; }
    public void setFailedAt(Instant failedAt) { this.failedAt = failedAt; }

    public String getErrorType() { return errorType; }
    public void setErrorType(String errorType) { this.errorType = errorType; }

    public String getErrorSummary() { return errorSummary; }
    public void setErrorSummary(String errorSummary) { this.errorSummary = errorSummary; }

    public String getAffectedCandidateId() { return affectedCandidateId; }
    public void setAffectedCandidateId(String affectedCandidateId) { this.affectedCandidateId = affectedCandidateId; }

    public Instant getAlertSentAt() { return alertSentAt; }
    public void setAlertSentAt(Instant alertSentAt) { this.alertSentAt = alertSentAt; }
}
