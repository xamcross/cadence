package com.cadence.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;

/**
 * The candidate data-subject record (F04). The candidate never has an account (distinct from
 * {@link Member}); F04 owns the GDPR-critical subset and later features (F13/F40/F42) extend it.
 *
 * <p>PII at rest: {@code name}, {@code email}, {@code phone} are AES-256-GCM ciphertext via the
 * registered {@code PiiStringConverter} (MongoPiiConfig). {@code emailHash} is a keyed HMAC used for
 * every lookup (the ciphertext is randomized and not query-able). On erasure the PII fields become
 * the marker {@code "[ERASED]"} and {@code emailHash} is set null — with {@code write = NON_NULL} the
 * key is omitted from BSON entirely, so the subject cannot be re-identified by recomputing it from a
 * known email (FR-006/SC-002).
 *
 * <p>{@code toString()} deliberately omits name/email/phone — never leak PII via logs (FR-023). Any
 * field a later feature adds MUST stay out of {@code toString()} unless non-PII.
 */
@Document(collection = "candidates")
public class Candidate {

    @Id
    private String id;

    private String workspaceId;

    /** Stored encrypted (converter-managed). Never logged. Marker "[ERASED]" after erasure. */
    private String name;
    /** Stored encrypted (converter-managed). Never logged. Marker "[ERASED]" after erasure. */
    private String email;
    /** Stored encrypted (converter-managed). Never logged. Marker "[ERASED]" after erasure. */
    private String phone;

    /**
     * HMAC-SHA-256(lowercased email, PII_PEPPER) — non-unique lookup key. write=NON_NULL so erasure
     * (set null) OMITS the field from BSON (no residual email-derived value; avoids index collision).
     */
    @Field(value = "emailHash", write = Field.Write.NON_NULL)
    private String emailHash;

    // --- Lawful basis (email-contact consent) ---
    private LawfulBasis lawfulBasis;            // null until recorded (fail-closed default)
    private Instant basisRecordedAt;
    private String basisActorMemberId;          // internal id only (non-PII)
    private boolean basisWithdrawn;
    private Instant basisWithdrawnAt;

    // --- Erasure ---
    private ErasureState erasureState = ErasureState.ACTIVE;
    private Instant erasedAt;

    // --- Retention ---
    private boolean retentionFlagged;
    private Instant retentionFlaggedAt;

    /** Retention age basis (GDPR last-activity) and the F00.1 {workspaceId,lastContactAt} index field. */
    private Instant lastContactAt;

    // --- Deliverability (F22) — operational, PII-adjacent (purged on erasure, data-model §2). The gate
    // reads isUndeliverable() (lowest-precedence deny); the bounce webhook writes these on a hard bounce.
    private boolean undeliverable;
    private DispatchOutcomeReason undeliverableReason; // value-free (e.g. HARD_BOUNCE); never provider free-text
    private Instant undeliverableAt;
    private Instant undeliverableClearedAt;

    private Instant createdAt;

    public Candidate() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }

    public String getEmailHash() { return emailHash; }
    public void setEmailHash(String emailHash) { this.emailHash = emailHash; }

    public LawfulBasis getLawfulBasis() { return lawfulBasis; }
    public void setLawfulBasis(LawfulBasis lawfulBasis) { this.lawfulBasis = lawfulBasis; }

    public Instant getBasisRecordedAt() { return basisRecordedAt; }
    public void setBasisRecordedAt(Instant basisRecordedAt) { this.basisRecordedAt = basisRecordedAt; }

    public String getBasisActorMemberId() { return basisActorMemberId; }
    public void setBasisActorMemberId(String basisActorMemberId) { this.basisActorMemberId = basisActorMemberId; }

    public boolean isBasisWithdrawn() { return basisWithdrawn; }
    public void setBasisWithdrawn(boolean basisWithdrawn) { this.basisWithdrawn = basisWithdrawn; }

    public Instant getBasisWithdrawnAt() { return basisWithdrawnAt; }
    public void setBasisWithdrawnAt(Instant basisWithdrawnAt) { this.basisWithdrawnAt = basisWithdrawnAt; }

    public ErasureState getErasureState() { return erasureState; }
    public void setErasureState(ErasureState erasureState) { this.erasureState = erasureState; }

    public Instant getErasedAt() { return erasedAt; }
    public void setErasedAt(Instant erasedAt) { this.erasedAt = erasedAt; }

    public boolean isRetentionFlagged() { return retentionFlagged; }
    public void setRetentionFlagged(boolean retentionFlagged) { this.retentionFlagged = retentionFlagged; }

    public Instant getRetentionFlaggedAt() { return retentionFlaggedAt; }
    public void setRetentionFlaggedAt(Instant retentionFlaggedAt) { this.retentionFlaggedAt = retentionFlaggedAt; }

    public Instant getLastContactAt() { return lastContactAt; }
    public void setLastContactAt(Instant lastContactAt) { this.lastContactAt = lastContactAt; }

    public boolean isUndeliverable() { return undeliverable; }
    public void setUndeliverable(boolean undeliverable) { this.undeliverable = undeliverable; }

    public DispatchOutcomeReason getUndeliverableReason() { return undeliverableReason; }
    public void setUndeliverableReason(DispatchOutcomeReason undeliverableReason) { this.undeliverableReason = undeliverableReason; }

    public Instant getUndeliverableAt() { return undeliverableAt; }
    public void setUndeliverableAt(Instant undeliverableAt) { this.undeliverableAt = undeliverableAt; }

    public Instant getUndeliverableClearedAt() { return undeliverableClearedAt; }
    public void setUndeliverableClearedAt(Instant undeliverableClearedAt) { this.undeliverableClearedAt = undeliverableClearedAt; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    /** Deliberately omits name/email/phone — never leak candidate PII via toString()/logs (FR-023). */
    @Override
    public String toString() {
        return "Candidate{id=" + id + ", workspaceId=" + workspaceId + ", erasureState=" + erasureState + "}";
    }
}
