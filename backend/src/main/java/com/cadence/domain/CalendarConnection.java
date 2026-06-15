package com.cadence.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;

/**
 * One member's authorization to one calendar provider (F01.1). Addressed only by the natural key
 * {@code (workspaceId, memberId, provider)} — never by a credential value (research D3).
 *
 * <p>Secrets at rest: {@code refreshToken}, {@code accessToken} and {@code providerAccountId} are
 * AES-256-GCM ciphertext via the registered {@code PiiStringConverter} (MongoPiiConfig, research D2);
 * a raw-driver read sees only ciphertext (SC-002). They are {@code write = NON_NULL} so a null
 * (e.g. accessToken before first use, or after an invalid_grant) is omitted from BSON.
 *
 * <p>{@code toString()} deliberately omits all three secrets — never leak a token/account via logs.
 */
@Document(collection = "calendarConnections")
public class CalendarConnection {

    @Id
    private String id;

    private String workspaceId;
    private String memberId;
    private CalendarProvider provider;
    private ConnectionStatus status;

    /** Long-lived credential (encrypted). Retained on NEEDS_RECONNECTION so the member can reconnect. */
    @Field(value = "refreshToken", write = Field.Write.NON_NULL)
    private String refreshToken;

    /** Cached short-lived credential (encrypted); null until first use and nulled on invalid_grant. */
    @Field(value = "accessToken", write = Field.Write.NON_NULL)
    private String accessToken;

    private Instant accessTokenExpiresAt;
    private String scope;

    /** The connected account email/subject (PII, encrypted). Shown as "Connected as ...". Never queried. */
    @Field(value = "providerAccountId", write = Field.Write.NON_NULL)
    private String providerAccountId;

    /** Monotonic optimistic-CAS guard for concurrent refresh (research D5). */
    private long tokenVersion;

    private Instant connectedAt;
    private Instant lastRefreshAt;
    private Instant updatedAt;

    public CalendarConnection() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; }

    public String getMemberId() { return memberId; }
    public void setMemberId(String memberId) { this.memberId = memberId; }

    public CalendarProvider getProvider() { return provider; }
    public void setProvider(CalendarProvider provider) { this.provider = provider; }

    public ConnectionStatus getStatus() { return status; }
    public void setStatus(ConnectionStatus status) { this.status = status; }

    public String getRefreshToken() { return refreshToken; }
    public void setRefreshToken(String refreshToken) { this.refreshToken = refreshToken; }

    public String getAccessToken() { return accessToken; }
    public void setAccessToken(String accessToken) { this.accessToken = accessToken; }

    public Instant getAccessTokenExpiresAt() { return accessTokenExpiresAt; }
    public void setAccessTokenExpiresAt(Instant accessTokenExpiresAt) { this.accessTokenExpiresAt = accessTokenExpiresAt; }

    public String getScope() { return scope; }
    public void setScope(String scope) { this.scope = scope; }

    public String getProviderAccountId() { return providerAccountId; }
    public void setProviderAccountId(String providerAccountId) { this.providerAccountId = providerAccountId; }

    public long getTokenVersion() { return tokenVersion; }
    public void setTokenVersion(long tokenVersion) { this.tokenVersion = tokenVersion; }

    public Instant getConnectedAt() { return connectedAt; }
    public void setConnectedAt(Instant connectedAt) { this.connectedAt = connectedAt; }

    public Instant getLastRefreshAt() { return lastRefreshAt; }
    public void setLastRefreshAt(Instant lastRefreshAt) { this.lastRefreshAt = lastRefreshAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    /** Deliberately omits refreshToken/accessToken/providerAccountId — never leak secrets via logs. */
    @Override
    public String toString() {
        return "CalendarConnection{id=" + id + ", workspaceId=" + workspaceId + ", memberId=" + memberId
            + ", provider=" + provider + ", status=" + status + ", tokenVersion=" + tokenVersion + "}";
    }
}
