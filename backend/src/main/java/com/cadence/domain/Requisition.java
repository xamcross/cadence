package com.cadence.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;

/**
 * F51 requisition — a workspace-scoped job opening. The minimal-but-real concept the backlog assigns to
 * F51: title + open/closed state + an optional external label (an ATS job id/title or CSV requisition label,
 * surfaced to assist manual linking). Hiring Managers are scoped to requisitions through the existing
 * {@link Assignment} model ({@link ResourceType#REQUISITION}); candidates carry a {@code requisitionId} link.
 *
 * <p>No candidate PII, no secret — un-encrypted by design (the {@code interviewTemplates}/
 * {@code managedCalendarEvents} precedent). The {@code title} is a recruiter/Admin-authored job title (a
 * requisition attribute, not candidate PII) and is kept out of logs by discipline.
 */
@Document(collection = "requisitions")
public class Requisition {

    @Id
    private String id;

    private String workspaceId;
    private String title;
    private RequisitionStatus status;

    /** Optional ATS job id/title or CSV requisition label captured to assist manual linking (FR-011). Reference only. */
    @Field(value = "externalLabel", write = Field.Write.NON_NULL)
    private String externalLabel;

    private Instant createdAt;
    private String createdByMemberId;

    public Requisition() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public RequisitionStatus getStatus() { return status; }
    public void setStatus(RequisitionStatus status) { this.status = status; }

    public String getExternalLabel() { return externalLabel; }
    public void setExternalLabel(String externalLabel) { this.externalLabel = externalLabel; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public String getCreatedByMemberId() { return createdByMemberId; }
    public void setCreatedByMemberId(String createdByMemberId) { this.createdByMemberId = createdByMemberId; }
}
