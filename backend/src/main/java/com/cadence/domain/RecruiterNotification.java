package com.cadence.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * An in-app recruiter notification of a dispatch outcome (F22, T044). <b>Value-free</b>: workspaceId +
 * candidateId (internal ObjectId hex) + type + instant ONLY — never the recipient address, rendered
 * subject/body, merge values, or provider free-text (FR-013/D10). Un-encrypted by design (no PII at rest,
 * like {@code emailDispatches}). The pipeline UI surface that reads these is F51.
 */
@Document(collection = "recruiterNotifications")
public class RecruiterNotification {

    @Id
    private String id;

    private String workspaceId;

    /** Internal candidate ObjectId hex only. */
    private String candidateId;

    private RecruiterNotificationType type;

    private Instant createdAt;

    public RecruiterNotification() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; }

    public String getCandidateId() { return candidateId; }
    public void setCandidateId(String candidateId) { this.candidateId = candidateId; }

    public RecruiterNotificationType getType() { return type; }
    public void setType(RecruiterNotificationType type) { this.type = type; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    /** Ids + type + instant only — no candidate-resolvable value (FR-013). */
    @Override
    public String toString() {
        return "RecruiterNotification{id=" + id + ", workspaceId=" + workspaceId
            + ", candidateId=" + candidateId + ", type=" + type + ", createdAt=" + createdAt + "}";
    }
}
