package com.cadence.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * The single per-workspace configuration record (F03). One document per {@code workspaceId}
 * (unique index, ChangeUnit004). {@code configuredAt == null} means *unconfigured*; the wizard
 * sets it once (one-way) along with {@code retentionAcknowledgedAt} (the GDPR gate, FR-004).
 *
 * The {@code emailProviderCredential} is STRUCTURALLY write-only (research D2): it is encrypted at
 * rest by the {@code PiiStringConverter} registered in {@code MongoPiiConfig}, annotated
 * {@code @JsonIgnore} so it can never serialize onto any response, and excluded from {@code toString()}.
 * Only a derived {@code credentialSet} boolean is ever exposed (FR-016/FR-017).
 */
@Document(collection = "workspaceConfig")
public class WorkspaceConfig {

    @Id
    private String id;

    private String workspaceId;

    /** null => unconfigured; set once on wizard completion (one-way, FR-002/FR-006). */
    private Instant configuredAt;

    private String name;
    private String timeZone;
    private WorkingHours workingHours;
    private int slaSilenceWindowDays;
    private int retentionPeriodDays;

    /** GDPR acknowledgment evidence (set with configuredAt; immutable thereafter — FR-004). */
    private Instant retentionAcknowledgedAt;

    private String brandColor;
    private boolean hasLogo;

    private String emailSendingDomain;

    /**
     * Encrypted at rest via the PiiStringConverter (MongoPiiConfig). NEVER serialized (@JsonIgnore),
     * NEVER logged; only {@code credentialSet} is exposed. write=NON_NULL so an unset credential is
     * omitted from BSON entirely (research D2).
     */
    @JsonIgnore
    @Field(value = "emailProviderCredential", write = Field.Write.NON_NULL)
    private String emailProviderCredential;

    private Map<String, Boolean> templateLocks = new HashMap<>();

    private Instant createdAt;
    private Instant updatedAt;

    public WorkspaceConfig() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; }

    public Instant getConfiguredAt() { return configuredAt; }
    public void setConfiguredAt(Instant configuredAt) { this.configuredAt = configuredAt; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getTimeZone() { return timeZone; }
    public void setTimeZone(String timeZone) { this.timeZone = timeZone; }

    public WorkingHours getWorkingHours() { return workingHours; }
    public void setWorkingHours(WorkingHours workingHours) { this.workingHours = workingHours; }

    public int getSlaSilenceWindowDays() { return slaSilenceWindowDays; }
    public void setSlaSilenceWindowDays(int slaSilenceWindowDays) { this.slaSilenceWindowDays = slaSilenceWindowDays; }

    public int getRetentionPeriodDays() { return retentionPeriodDays; }
    public void setRetentionPeriodDays(int retentionPeriodDays) { this.retentionPeriodDays = retentionPeriodDays; }

    public Instant getRetentionAcknowledgedAt() { return retentionAcknowledgedAt; }
    public void setRetentionAcknowledgedAt(Instant retentionAcknowledgedAt) { this.retentionAcknowledgedAt = retentionAcknowledgedAt; }

    public String getBrandColor() { return brandColor; }
    public void setBrandColor(String brandColor) { this.brandColor = brandColor; }

    public boolean isHasLogo() { return hasLogo; }
    public void setHasLogo(boolean hasLogo) { this.hasLogo = hasLogo; }

    public String getEmailSendingDomain() { return emailSendingDomain; }
    public void setEmailSendingDomain(String emailSendingDomain) { this.emailSendingDomain = emailSendingDomain; }

    public String getEmailProviderCredential() { return emailProviderCredential; }
    public void setEmailProviderCredential(String emailProviderCredential) { this.emailProviderCredential = emailProviderCredential; }

    public Map<String, Boolean> getTemplateLocks() { return templateLocks; }
    public void setTemplateLocks(Map<String, Boolean> templateLocks) { this.templateLocks = templateLocks; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public boolean isConfigured() { return configuredAt != null; }
    public boolean isCredentialSet() { return emailProviderCredential != null; }

    /** Deliberately omits emailProviderCredential — never leak the secret via toString()/logs (D2). */
    @Override
    public String toString() {
        return "WorkspaceConfig{id=" + id + ", workspaceId=" + workspaceId
            + ", configured=" + isConfigured() + ", credentialSet=" + isCredentialSet() + "}";
    }
}
