package com.cadence.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * Single-use forgotten-password link for fallback members (FR-020, FR-031, FR-035).
 * {@code tokenHash} is an HMAC of the 256-bit token. TTL index on {@code expiresAt}.
 */
@Document(collection = "passwordResets")
public class PasswordResetToken {

    @Id
    private String id;

    private String memberId;
    private String tokenHash;
    private ResetStatus status;

    private Instant createdAt;
    private Instant expiresAt;
    private Instant consumedAt;

    public PasswordResetToken() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getMemberId() { return memberId; }
    public void setMemberId(String memberId) { this.memberId = memberId; }

    public String getTokenHash() { return tokenHash; }
    public void setTokenHash(String tokenHash) { this.tokenHash = tokenHash; }

    public ResetStatus getStatus() { return status; }
    public void setStatus(ResetStatus status) { this.status = status; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }

    public Instant getConsumedAt() { return consumedAt; }
    public void setConsumedAt(Instant consumedAt) { this.consumedAt = consumedAt; }
}
