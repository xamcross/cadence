package com.cadence.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * Append-only, candidate-keyed, non-PII accountability record (F04, FR-014/FR-18). Distinct from the
 * member-keyed {@code authAuditLog}. Closed-enum codes only — no free-text value column — so it is
 * non-PII by construction and survives candidate erasure (it references the candidate by internal id
 * only). The {@code _id} ObjectId is the deterministic order tiebreaker (same-process monotonic),
 * so no separate sequence field is needed.
 *
 * <p>The collection index {@code {candidateId:1, occurredAt:-1}} is pre-created by ChangeUnit001.
 */
@Document(collection = "auditLog")
public class CandidateAuditEvent {

    @Id
    private String id;

    private String workspaceId;
    private String candidateId;
    private CandidateEventType eventType;
    private CandidateAuditOutcome outcome;
    private String actorMemberId;   // null => system (e.g. retention scan)
    private Instant occurredAt;

    public CandidateAuditEvent() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; }

    public String getCandidateId() { return candidateId; }
    public void setCandidateId(String candidateId) { this.candidateId = candidateId; }

    public CandidateEventType getEventType() { return eventType; }
    public void setEventType(CandidateEventType eventType) { this.eventType = eventType; }

    public CandidateAuditOutcome getOutcome() { return outcome; }
    public void setOutcome(CandidateAuditOutcome outcome) { this.outcome = outcome; }

    public String getActorMemberId() { return actorMemberId; }
    public void setActorMemberId(String actorMemberId) { this.actorMemberId = actorMemberId; }

    public Instant getOccurredAt() { return occurredAt; }
    public void setOccurredAt(Instant occurredAt) { this.occurredAt = occurredAt; }
}
