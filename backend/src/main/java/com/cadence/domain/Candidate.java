package com.cadence.domain;

import com.cadence.integration.AtsProvider;
import com.fasterxml.jackson.annotation.JsonIgnore;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;
import java.time.LocalDate;

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

    // --- F30 Candidate Status Page (data-model §1) — additive, candidate-visible status -----------------
    /** Recruiter free text — short stage label. Encrypted at rest (converter). PII; never logged. */
    @JsonIgnore
    @Field(value = "statusStage", write = Field.Write.NON_NULL)
    private String statusStage;
    /** Recruiter free text — plain-English next step / terminal message. Encrypted at rest. PII; never logged. */
    @JsonIgnore
    @Field(value = "statusNextStep", write = Field.Write.NON_NULL)
    private String statusNextStep;
    /** Required for IN_PROGRESS; nullable for terminal. Compared in the WORKSPACE zone (D5). */
    private LocalDate statusExpectedDate;
    /** IN_PROGRESS / COMPLETE_OFFER / COMPLETE_REJECTED. Null until first publish. */
    private CandidateStatusOutcome statusOutcome;
    /** Null => never published => displayState UNDER_REVIEW (FR-006). */
    private Instant statusPublishedAt;
    /** Internal member id (non-PII). */
    private String statusPublishedByMemberId;
    /**
     * The raw status access token, AES-256-GCM at rest (converter-managed, REVERSIBLE — the F01.1 OAuth
     * refresh-token precedent). Decrypted only to build the link (D2/D9). write=NON_NULL so an absent token
     * is omitted from BSON. Never logged. Cleared on erasure via $set null (NOT $unset — converter trap).
     */
    @JsonIgnore
    @Field(value = "statusToken", write = Field.Write.NON_NULL)
    private String statusToken;
    /**
     * {@code TokenHasher.hashToken(raw)} — deterministic HMAC, partial-unique indexed; resolves inbound
     * requests. NOT converter-managed (already a hash). write=NON_NULL so an absent token is omitted from
     * the partial index (the F01 present-as-null lesson). Never logged. Cleared on erasure via $unset.
     */
    @Field(value = "statusTokenHash", write = Field.Write.NON_NULL)
    private String statusTokenHash;

    // --- F40 ATS integration (data-model section 4) — additive ATS-link fields. All write=NON_NULL so a
    // native (non-imported) candidate omits them from BSON and does NOT collide on the partial-unique
    // {workspaceId,atsProvider,atsExternalRef} index (the F01 present-as-null lesson). atsExternalRef is the
    // authoritative reconcile key and is RETAINED on erasure (the resurrection anchor — see CandidateErasureService).
    @Field(value = "atsProvider", write = Field.Write.NON_NULL)
    private AtsProvider atsProvider;
    @Field(value = "atsExternalRef", write = Field.Write.NON_NULL)
    private String atsExternalRef;
    @Field(value = "atsExternalJobId", write = Field.Write.NON_NULL)
    private String atsExternalJobId;
    /** Requisition title — NOT candidate PII; kept out of logs by discipline. Cleared on erasure. */
    @Field(value = "atsExternalJobTitle", write = Field.Write.NON_NULL)
    private String atsExternalJobTitle;
    /**
     * Raw external pipeline-stage label (free text). PII-adjacent (FR-022 no-log); encrypted at rest via the
     * registered converter (MongoPiiConfig). @JsonIgnore + omitted from toString. Cleared on erasure via $set null.
     */
    @JsonIgnore
    @Field(value = "atsStageLabel", write = Field.Write.NON_NULL)
    private String atsStageLabel;
    /** Last successful inbound ATS update for this candidate. */
    private Instant atsSyncedAt;

    // --- F42 CSV import (data-model section 3) — additive provenance + CSV content. origin/importJobId are
    // non-PII and RETAINED on erasure (the ATS reconcile-anchor precedent). importStageLabel is PII-adjacent
    // free text -> encrypted (MongoPiiConfig) + cleared via $set null on erasure (NEVER $unset — the F03 trap).
    // importRequisitionLabel is a requisition attribute (not candidate PII) -> plaintext, cleared on erasure.
    // All four are write=NON_NULL so a native/ATS candidate omits them from BSON and does not collide on the
    // partial-unique {workspaceId,emailHash} over origin:CSV_IMPORT index (the F01 present-as-null lesson).
    @Field(value = "origin", write = Field.Write.NON_NULL)
    private CandidateOrigin origin;
    @Field(value = "importJobId", write = Field.Write.NON_NULL)
    private String importJobId;
    /** CSV stage free text. Encrypted at rest (converter). PII-adjacent; never logged. Cleared $set null on erasure. */
    @JsonIgnore
    @Field(value = "importStageLabel", write = Field.Write.NON_NULL)
    private String importStageLabel;
    /** CSV requisition reference (free text). Not candidate PII; kept out of logs by discipline. Cleared on erasure. */
    @Field(value = "importRequisitionLabel", write = Field.Write.NON_NULL)
    private String importRequisitionLabel;

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

    public String getStatusStage() { return statusStage; }
    public void setStatusStage(String statusStage) { this.statusStage = statusStage; }

    public String getStatusNextStep() { return statusNextStep; }
    public void setStatusNextStep(String statusNextStep) { this.statusNextStep = statusNextStep; }

    public LocalDate getStatusExpectedDate() { return statusExpectedDate; }
    public void setStatusExpectedDate(LocalDate statusExpectedDate) { this.statusExpectedDate = statusExpectedDate; }

    public CandidateStatusOutcome getStatusOutcome() { return statusOutcome; }
    public void setStatusOutcome(CandidateStatusOutcome statusOutcome) { this.statusOutcome = statusOutcome; }

    public Instant getStatusPublishedAt() { return statusPublishedAt; }
    public void setStatusPublishedAt(Instant statusPublishedAt) { this.statusPublishedAt = statusPublishedAt; }

    public String getStatusPublishedByMemberId() { return statusPublishedByMemberId; }
    public void setStatusPublishedByMemberId(String statusPublishedByMemberId) { this.statusPublishedByMemberId = statusPublishedByMemberId; }

    public String getStatusToken() { return statusToken; }
    public void setStatusToken(String statusToken) { this.statusToken = statusToken; }

    public String getStatusTokenHash() { return statusTokenHash; }
    public void setStatusTokenHash(String statusTokenHash) { this.statusTokenHash = statusTokenHash; }

    public AtsProvider getAtsProvider() { return atsProvider; }
    public void setAtsProvider(AtsProvider atsProvider) { this.atsProvider = atsProvider; }

    public String getAtsExternalRef() { return atsExternalRef; }
    public void setAtsExternalRef(String atsExternalRef) { this.atsExternalRef = atsExternalRef; }

    public String getAtsExternalJobId() { return atsExternalJobId; }
    public void setAtsExternalJobId(String atsExternalJobId) { this.atsExternalJobId = atsExternalJobId; }

    public String getAtsExternalJobTitle() { return atsExternalJobTitle; }
    public void setAtsExternalJobTitle(String atsExternalJobTitle) { this.atsExternalJobTitle = atsExternalJobTitle; }

    public String getAtsStageLabel() { return atsStageLabel; }
    public void setAtsStageLabel(String atsStageLabel) { this.atsStageLabel = atsStageLabel; }

    public Instant getAtsSyncedAt() { return atsSyncedAt; }
    public void setAtsSyncedAt(Instant atsSyncedAt) { this.atsSyncedAt = atsSyncedAt; }

    public CandidateOrigin getOrigin() { return origin; }
    public void setOrigin(CandidateOrigin origin) { this.origin = origin; }

    public String getImportJobId() { return importJobId; }
    public void setImportJobId(String importJobId) { this.importJobId = importJobId; }

    public String getImportStageLabel() { return importStageLabel; }
    public void setImportStageLabel(String importStageLabel) { this.importStageLabel = importStageLabel; }

    public String getImportRequisitionLabel() { return importRequisitionLabel; }
    public void setImportRequisitionLabel(String importRequisitionLabel) { this.importRequisitionLabel = importRequisitionLabel; }

    /**
     * Deliberately omits name/email/phone (FR-023) AND the F30 statusStage/statusNextStep (PII) +
     * statusToken/statusTokenHash (credential) — never leak PII or a token via toString()/logs (FR-033/FR-034).
     */
    @Override
    public String toString() {
        return "Candidate{id=" + id + ", workspaceId=" + workspaceId + ", erasureState=" + erasureState + "}";
    }
}
