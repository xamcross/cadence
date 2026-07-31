package com.cadence.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;

/**
 * 032 -- one row per UPGRADED workspace (spec Key Entities). Absence of a row means the FREE plan
 * (FR-001/FR-022); launch therefore needs no migration. Binding is insert-only under two unique
 * indexes ({workspaceId}, {fsLicenseId} -- ChangeUnit024); lifecycle updates are findAndModify CAS
 * in BillingService, so no {@code @Version}. Holds Freemius numeric ids ONLY -- never buyer
 * name/email/payment data (FR-002). {@code expiresAt} null means a lifetime license.
 */
@Document(collection = "workspaceEntitlements")
public class WorkspaceEntitlement {

    @Id
    private String id;

    private String workspaceId;

    private BillingPlan plan = BillingPlan.TEAM;

    private EntitlementStatus status = EntitlementStatus.ACTIVE;

    /** Freemius license id -- the claim key; unique among bound rows. */
    private String fsLicenseId;

    @Field(value = "fsUserId", write = Field.Write.NON_NULL)
    private String fsUserId;

    @Field(value = "fsPlanId", write = Field.Write.NON_NULL)
    private String fsPlanId;

    /** License effective end; null = lifetime. */
    @Field(value = "expiresAt", write = Field.Write.NON_NULL)
    private Instant expiresAt;

    private Instant boundAt;

    @Field(value = "lastVerifiedAt", write = Field.Write.NON_NULL)
    private Instant lastVerifiedAt;

    private Instant updatedAt;

    public WorkspaceEntitlement() {}

    /** FR-001: confers TEAM while not provider-EXPIRED and not past the effective end. */
    public boolean confersTeam(Instant now) {
        if (status == EntitlementStatus.EXPIRED) {
            return false;
        }
        return expiresAt == null || expiresAt.isAfter(now);
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; }
    public BillingPlan getPlan() { return plan; }
    public void setPlan(BillingPlan plan) { this.plan = plan; }
    public EntitlementStatus getStatus() { return status; }
    public void setStatus(EntitlementStatus status) { this.status = status; }
    public String getFsLicenseId() { return fsLicenseId; }
    public void setFsLicenseId(String fsLicenseId) { this.fsLicenseId = fsLicenseId; }
    public String getFsUserId() { return fsUserId; }
    public void setFsUserId(String fsUserId) { this.fsUserId = fsUserId; }
    public String getFsPlanId() { return fsPlanId; }
    public void setFsPlanId(String fsPlanId) { this.fsPlanId = fsPlanId; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public Instant getBoundAt() { return boundAt; }
    public void setBoundAt(Instant boundAt) { this.boundAt = boundAt; }
    public Instant getLastVerifiedAt() { return lastVerifiedAt; }
    public void setLastVerifiedAt(Instant lastVerifiedAt) { this.lastVerifiedAt = lastVerifiedAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    /** Ids/status/instants only -- no PII exists on this document at all. */
    @Override
    public String toString() {
        return "WorkspaceEntitlement{id=" + id + ", workspaceId=" + workspaceId
            + ", plan=" + plan + ", status=" + status + ", expiresAt=" + expiresAt + "}";
    }
}
