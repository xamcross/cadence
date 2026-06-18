package com.cadence.domain;

import com.cadence.integration.AtsProvider;
import com.fasterxml.jackson.annotation.JsonIgnore;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;

/**
 * One ATS connection document per (workspace, provider) (F40/F41, data-model section 1). The unique
 * {@code {workspaceId, provider}} index (ChangeUnit019, migrated from F40's {@code {workspaceId}})
 * enforces one connection per provider per workspace, so Greenhouse and Lever coexist; a concurrent
 * first-connect races to a {@code DuplicateKeyException} (idempotent).
 *
 * <p>The {@code apiKey} is STRUCTURALLY write-only: it is encrypted at rest by the
 * {@code PiiStringConverter} registered in {@code MongoPiiConfig}, annotated {@code @JsonIgnore}
 * so it can never serialize onto any response, excluded from {@code toString()}, and never logged.
 * Only a derived {@code credentialSet} boolean is ever exposed. {@code write=NON_NULL} so an unset
 * key is omitted from BSON entirely; it is cleared on disconnect via {@code $set null} (never
 * {@code $unset} — the converter ClassCastException trap).
 */
@Document(collection = "atsConnections")
public class AtsConnection {

    @Id
    private String id;

    private String workspaceId;

    private AtsProvider provider;

    /**
     * Encrypted at rest via the PiiStringConverter (MongoPiiConfig). NEVER serialized (@JsonIgnore),
     * NEVER logged; only {@code credentialSet} is exposed. write=NON_NULL so an unset key is omitted
     * from BSON entirely.
     */
    @JsonIgnore
    @Field(value = "apiKey", write = Field.Write.NON_NULL)
    private String apiKey;

    private AtsConnectionStatus status;

    /** Set on a successful verify. */
    private Instant lastVerifiedAt;

    /** Set on a successful sync run. */
    private Instant lastSyncAt;

    /** Value-free category only (never a provider body); drives the degraded indicator. */
    private String lastErrorCategory;

    /** Opaque "updated-after" cursor for incremental polls (nullable). */
    @Field(value = "syncCursor", write = Field.Write.NON_NULL)
    private String syncCursor;

    private Instant createdAt;
    private Instant updatedAt;

    public AtsConnection() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; }

    public AtsProvider getProvider() { return provider; }
    public void setProvider(AtsProvider provider) { this.provider = provider; }

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    public AtsConnectionStatus getStatus() { return status; }
    public void setStatus(AtsConnectionStatus status) { this.status = status; }

    public Instant getLastVerifiedAt() { return lastVerifiedAt; }
    public void setLastVerifiedAt(Instant lastVerifiedAt) { this.lastVerifiedAt = lastVerifiedAt; }

    public Instant getLastSyncAt() { return lastSyncAt; }
    public void setLastSyncAt(Instant lastSyncAt) { this.lastSyncAt = lastSyncAt; }

    public String getLastErrorCategory() { return lastErrorCategory; }
    public void setLastErrorCategory(String lastErrorCategory) { this.lastErrorCategory = lastErrorCategory; }

    public String getSyncCursor() { return syncCursor; }
    public void setSyncCursor(String syncCursor) { this.syncCursor = syncCursor; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public boolean isCredentialSet() { return apiKey != null; }

    /** Deliberately omits apiKey - never leak the secret via toString()/logs. */
    @Override
    public String toString() {
        return "AtsConnection{id=" + id + ", workspaceId=" + workspaceId + ", provider=" + provider
            + ", status=" + status + ", lastVerifiedAt=" + lastVerifiedAt + ", lastSyncAt=" + lastSyncAt
            + ", createdAt=" + createdAt + ", updatedAt=" + updatedAt + "}";
    }
}
