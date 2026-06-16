package com.cadence.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * A per-participant slot reservation (F13, data-model §3) — the cross-request double-booking guard (D3).
 * The unique <b>partial</b> index {@code {workspaceId, memberId, startAt}} over {@code status == ACTIVE}
 * makes the first booking to claim a given (member, start) the winner; a concurrent claim from a different
 * request gets a {@code DuplicateKeyException} (= slot taken). No PII (ids/instants/enum only).
 */
@Document(collection = "interviewSlotClaims")
public class InterviewSlotClaim {

    @Id
    private String id;

    private String workspaceId;
    private String memberId;
    private Instant startAt;
    private String schedulingRequestId;
    private ClaimStatus status = ClaimStatus.ACTIVE;
    private Instant createdAt;

    public InterviewSlotClaim() {}

    public InterviewSlotClaim(String workspaceId, String memberId, Instant startAt,
                              String schedulingRequestId, Instant createdAt) {
        this.workspaceId = workspaceId;
        this.memberId = memberId;
        this.startAt = startAt;
        this.schedulingRequestId = schedulingRequestId;
        this.status = ClaimStatus.ACTIVE;
        this.createdAt = createdAt;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; }

    public String getMemberId() { return memberId; }
    public void setMemberId(String memberId) { this.memberId = memberId; }

    public Instant getStartAt() { return startAt; }
    public void setStartAt(Instant startAt) { this.startAt = startAt; }

    public String getSchedulingRequestId() { return schedulingRequestId; }
    public void setSchedulingRequestId(String schedulingRequestId) { this.schedulingRequestId = schedulingRequestId; }

    public ClaimStatus getStatus() { return status; }
    public void setStatus(ClaimStatus status) { this.status = status; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
