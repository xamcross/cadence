package com.cadence.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;

/**
 * A workspace member who can sign in. Distinct from a Candidate (who never has an account).
 *
 * PII at rest: {@code email} and {@code displayName} are stored as AES-256-GCM ciphertext via a
 * registered Spring Data property converter (MongoPiiConfig / PiiCrypto). {@code emailHash} is a
 * keyed HMAC of the lowercased email and is what the unique index and all lookups use, since the
 * ciphertext is randomized and not query-able (research D12).
 */
@Document(collection = "members")
public class Member {

    @Id
    private String id;

    private String workspaceId;

    /** Stored encrypted (converter-managed). Never logged. */
    private String email;

    /** HMAC-SHA-256(lowercased email, PII_PEPPER) — unique index + equality lookup key. */
    private String emailHash;

    /** Stored encrypted (converter-managed). Never logged. */
    private String displayName;

    private Role role;
    private MemberStatus status;

    private PasswordCredential passwordCredential;
    private SsoIdentity ssoIdentity;

    /**
     * Denormalised from ssoIdentity for the partial unique index {ssoProvider, ssoSubject}.
     * write=NON_NULL so password-only members OMIT these fields entirely (a persisted null would
     * match the partial filter {$exists:true} and collide on the unique index — BE-1).
     */
    @Field(value = "ssoProvider", write = Field.Write.NON_NULL)
    private String ssoProvider;
    @Field(value = "ssoSubject", write = Field.Write.NON_NULL)
    private String ssoSubject;

    private int failedLoginCount;
    private Instant lockedUntil;

    private Instant createdAt;
    private Instant updatedAt;

    public Member() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getEmailHash() { return emailHash; }
    public void setEmailHash(String emailHash) { this.emailHash = emailHash; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public Role getRole() { return role; }
    public void setRole(Role role) { this.role = role; }

    public MemberStatus getStatus() { return status; }
    public void setStatus(MemberStatus status) { this.status = status; }

    public PasswordCredential getPasswordCredential() { return passwordCredential; }
    public void setPasswordCredential(PasswordCredential passwordCredential) { this.passwordCredential = passwordCredential; }

    public SsoIdentity getSsoIdentity() { return ssoIdentity; }
    public void setSsoIdentity(SsoIdentity ssoIdentity) { this.ssoIdentity = ssoIdentity; }

    public String getSsoProvider() { return ssoProvider; }
    public void setSsoProvider(String ssoProvider) { this.ssoProvider = ssoProvider; }

    public String getSsoSubject() { return ssoSubject; }
    public void setSsoSubject(String ssoSubject) { this.ssoSubject = ssoSubject; }

    public int getFailedLoginCount() { return failedLoginCount; }
    public void setFailedLoginCount(int failedLoginCount) { this.failedLoginCount = failedLoginCount; }

    public Instant getLockedUntil() { return lockedUntil; }
    public void setLockedUntil(Instant lockedUntil) { this.lockedUntil = lockedUntil; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public boolean isActive() { return status == MemberStatus.ACTIVE; }
}
