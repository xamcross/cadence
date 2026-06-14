package com.cadence.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * Append-only, member-keyed, non-PII security event record (FR-023, FR-036). Separate from the
 * candidate-keyed {@code auditLog}. References members by internal id only so it survives PII
 * erasure; the source IP is stored only as a keyed HMAC (SEC-6), never raw.
 */
@Document(collection = "authAuditLog")
public class AuthAuditEvent {

    @Id
    private String id;

    private String workspaceId;
    private String memberId; // nullable when no member resolved (e.g. unknown-email login)
    private AuthEventType eventType;
    private Instant occurredAt;
    private String sourceIpHash; // HMAC, never raw IP
    private String outcome;      // short non-PII code

    public AuthAuditEvent() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; }

    public String getMemberId() { return memberId; }
    public void setMemberId(String memberId) { this.memberId = memberId; }

    public AuthEventType getEventType() { return eventType; }
    public void setEventType(AuthEventType eventType) { this.eventType = eventType; }

    public Instant getOccurredAt() { return occurredAt; }
    public void setOccurredAt(Instant occurredAt) { this.occurredAt = occurredAt; }

    public String getSourceIpHash() { return sourceIpHash; }
    public void setSourceIpHash(String sourceIpHash) { this.sourceIpHash = sourceIpHash; }

    public String getOutcome() { return outcome; }
    public void setOutcome(String outcome) { this.outcome = outcome; }
}
