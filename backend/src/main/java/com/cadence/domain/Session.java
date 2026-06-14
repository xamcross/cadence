package com.cadence.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * The revocable session registry record backing the cookie JWT (research D1). The {@code id} is
 * the JWT {@code jti} so per-request validation is a primary-key read. A TTL index on
 * {@code absoluteExpiresAt} purges expired rows with no scheduler (constitution §IV).
 */
@Document(collection = "sessions")
public class Session {

    @Id
    private String id; // == JWT jti

    private String memberId;
    private String workspaceId;
    private Role role;

    private Instant createdAt;       // absolute-lifetime anchor
    private Instant lastSeenAt;      // sliding-idle anchor
    private Instant absoluteExpiresAt;
    private Instant idleExpiresAt;
    private boolean revoked;

    public Session() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getMemberId() { return memberId; }
    public void setMemberId(String memberId) { this.memberId = memberId; }

    public String getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; }

    public Role getRole() { return role; }
    public void setRole(Role role) { this.role = role; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getLastSeenAt() { return lastSeenAt; }
    public void setLastSeenAt(Instant lastSeenAt) { this.lastSeenAt = lastSeenAt; }

    public Instant getAbsoluteExpiresAt() { return absoluteExpiresAt; }
    public void setAbsoluteExpiresAt(Instant absoluteExpiresAt) { this.absoluteExpiresAt = absoluteExpiresAt; }

    public Instant getIdleExpiresAt() { return idleExpiresAt; }
    public void setIdleExpiresAt(Instant idleExpiresAt) { this.idleExpiresAt = idleExpiresAt; }

    public boolean isRevoked() { return revoked; }
    public void setRevoked(boolean revoked) { this.revoked = revoked; }
}
