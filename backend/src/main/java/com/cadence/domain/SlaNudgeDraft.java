package com.cadence.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * F31 SLA holding-message draft (data-model section 1) — a recruiter-actionable record for a breaching
 * candidate. <b>No candidate PII at rest</b> (ids/enums/instants only — the {@code emailDispatches} precedent);
 * the rendered recipient/body stay transient. De-duplicated by a unique partial index on
 * {@code {workspaceId,candidateId}} over {@code status:OPEN} (ChangeUnit016); approve/dismiss/invalidate are
 * atomic {@code findAndModify} CAS off {@code status:OPEN}.
 */
@Document(collection = "slaNudgeDrafts")
public class SlaNudgeDraft {

    @Id
    private String id;

    private String workspaceId;
    private String candidateId;

    /** OPEN -> APPROVED / DISMISSED / INVALIDATED. Always non-null. */
    private SlaDraftStatus status;

    /** Always SLA_HOLDING in the MVP. */
    private EmailMessageType messageType;

    /** When the breach scan created the draft (injected Clock). */
    private Instant detectedAt;

    /** Set on approve/dismiss/invalidate; null while OPEN. */
    private Instant actionedAt;

    /** Recruiter/Admin who approved/dismissed; null for OPEN or a system INVALIDATED. */
    private String actorMemberId;

    public SlaNudgeDraft() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; }

    public String getCandidateId() { return candidateId; }
    public void setCandidateId(String candidateId) { this.candidateId = candidateId; }

    public SlaDraftStatus getStatus() { return status; }
    public void setStatus(SlaDraftStatus status) { this.status = status; }

    public EmailMessageType getMessageType() { return messageType; }
    public void setMessageType(EmailMessageType messageType) { this.messageType = messageType; }

    public Instant getDetectedAt() { return detectedAt; }
    public void setDetectedAt(Instant detectedAt) { this.detectedAt = detectedAt; }

    public Instant getActionedAt() { return actionedAt; }
    public void setActionedAt(Instant actionedAt) { this.actionedAt = actionedAt; }

    public String getActorMemberId() { return actorMemberId; }
    public void setActorMemberId(String actorMemberId) { this.actorMemberId = actorMemberId; }

    /** No PII to leak (there is none), but keep the discipline: id/status/ids only. */
    @Override
    public String toString() {
        return "SlaNudgeDraft{id=" + id + ", workspaceId=" + workspaceId
            + ", candidateId=" + candidateId + ", status=" + status + "}";
    }
}
