package com.cadence.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * Admin-created, single-use, time-limited provisioning grant (FR-016..019, FR-033, FR-035).
 * {@code tokenHash} is an HMAC of the 256-bit link token (the raw token is only in the email).
 * A TTL index on {@code expiresAt} deletes expired rows (no stored EXPIRED state — BE-9).
 */
@Document(collection = "invitations")
public class Invitation {

    @Id
    private String id;

    private String workspaceId;

    /** Stored encrypted (converter-managed). */
    private String email;

    private Role role;
    private String tokenHash;
    private InvitationStatus status;
    private String invitedByMemberId;

    private Instant createdAt;
    private Instant expiresAt;
    private Instant consumedAt;

    public Invitation() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public Role getRole() { return role; }
    public void setRole(Role role) { this.role = role; }

    public String getTokenHash() { return tokenHash; }
    public void setTokenHash(String tokenHash) { this.tokenHash = tokenHash; }

    public InvitationStatus getStatus() { return status; }
    public void setStatus(InvitationStatus status) { this.status = status; }

    public String getInvitedByMemberId() { return invitedByMemberId; }
    public void setInvitedByMemberId(String invitedByMemberId) { this.invitedByMemberId = invitedByMemberId; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }

    public Instant getConsumedAt() { return consumedAt; }
    public void setConsumedAt(Instant consumedAt) { this.consumedAt = consumedAt; }
}
