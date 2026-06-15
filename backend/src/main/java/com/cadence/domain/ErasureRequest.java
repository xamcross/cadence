package com.cadence.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * A data-subject (candidate-initiated) erasure request routed to an Administrator (F04, US4). Carries
 * the candidate internal id + an enum reason code ONLY — never candidate free text (which would
 * survive the wipe and re-identify the subject, FR-011). Transitions PENDING -> resolved are guarded.
 */
@Document(collection = "erasureRequests")
public class ErasureRequest {

    @Id
    private String id;

    private String workspaceId;
    private String candidateId;
    private RequestStatus status;
    private ErasureReasonCode reasonCode;   // enum only, no free text
    private Instant createdAt;
    private String decidedByMemberId;
    private Instant decidedAt;

    public ErasureRequest() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; }

    public String getCandidateId() { return candidateId; }
    public void setCandidateId(String candidateId) { this.candidateId = candidateId; }

    public RequestStatus getStatus() { return status; }
    public void setStatus(RequestStatus status) { this.status = status; }

    public ErasureReasonCode getReasonCode() { return reasonCode; }
    public void setReasonCode(ErasureReasonCode reasonCode) { this.reasonCode = reasonCode; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public String getDecidedByMemberId() { return decidedByMemberId; }
    public void setDecidedByMemberId(String decidedByMemberId) { this.decidedByMemberId = decidedByMemberId; }

    public Instant getDecidedAt() { return decidedAt; }
    public void setDecidedAt(Instant decidedAt) { this.decidedAt = decidedAt; }
}
