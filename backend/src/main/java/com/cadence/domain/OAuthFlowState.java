package com.cadence.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * Single-use, short-lived record binding an in-flight authorization-code flow to the initiating member
 * (research D4). The {@code id} IS the OAuth {@code state} nonce. Consumed by an atomic
 * {@code mongoTemplate.findAndRemove} on the callback (single-use); abandoned flows are auto-reaped by
 * the TTL index on {@code expiresAt} (ChangeUnit006). {@code pkceVerifier} is a one-time secret,
 * encrypted at rest via the registered converter.
 */
@Document(collection = "calendarOAuthState")
public class OAuthFlowState {

    @Id
    private String id; // the high-entropy state nonce

    private String workspaceId;
    private String memberId;
    private CalendarProvider provider;

    /** PKCE code_verifier (encrypted, converter-managed). Never logged. */
    private String pkceVerifier;

    private Instant createdAt;
    private Instant expiresAt;

    public OAuthFlowState() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; }

    public String getMemberId() { return memberId; }
    public void setMemberId(String memberId) { this.memberId = memberId; }

    public CalendarProvider getProvider() { return provider; }
    public void setProvider(CalendarProvider provider) { this.provider = provider; }

    public String getPkceVerifier() { return pkceVerifier; }
    public void setPkceVerifier(String pkceVerifier) { this.pkceVerifier = pkceVerifier; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }

    /** Omits pkceVerifier — never leak the one-time secret via logs. */
    @Override
    public String toString() {
        return "OAuthFlowState{id=" + id + ", memberId=" + memberId + ", provider=" + provider
            + ", expiresAt=" + expiresAt + "}";
    }
}
