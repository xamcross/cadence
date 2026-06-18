package com.cadence.domain;

import org.springframework.data.mongodb.core.mapping.Field;

/**
 * F42 per-row outcome embedded in {@link CsvImportJob}. Carries NO raw cell value (FR-017) — only the logical
 * row number, the status, the failing field name + a value-free reason enum, and internal candidate ids.
 *
 * <p>For a {@code DUPLICATE_PENDING} row the {@code emailHash} (keyed, non-PII-recoverable) and the matched
 * {@code existingCandidateId} are retained so the recruiter's later merge/skip can be applied; plaintext email
 * is NEVER stored (FR-021).
 */
public class CsvImportRowResult {

    private int rowNumber;
    private CsvImportRowStatus status;

    @Field(write = Field.Write.NON_NULL)
    private String failingField;
    @Field(write = Field.Write.NON_NULL)
    private CsvRowFailureReason reason;

    /** The keyed email hash for a duplicate row (non-PII; used to apply the deferred merge/skip). */
    @Field(write = Field.Write.NON_NULL)
    private String emailHash;
    /** The existing candidate a duplicate row matched (internal id). */
    @Field(write = Field.Write.NON_NULL)
    private String existingCandidateId;
    /** The candidate created (IMPORTED) or updated (MERGED) for this row (internal id). */
    @Field(write = Field.Write.NON_NULL)
    private String candidateId;

    public CsvImportRowResult() {}

    public CsvImportRowResult(int rowNumber, CsvImportRowStatus status) {
        this.rowNumber = rowNumber;
        this.status = status;
    }

    public int getRowNumber() { return rowNumber; }
    public void setRowNumber(int rowNumber) { this.rowNumber = rowNumber; }

    public CsvImportRowStatus getStatus() { return status; }
    public void setStatus(CsvImportRowStatus status) { this.status = status; }

    public String getFailingField() { return failingField; }
    public void setFailingField(String failingField) { this.failingField = failingField; }

    public CsvRowFailureReason getReason() { return reason; }
    public void setReason(CsvRowFailureReason reason) { this.reason = reason; }

    public String getEmailHash() { return emailHash; }
    public void setEmailHash(String emailHash) { this.emailHash = emailHash; }

    public String getExistingCandidateId() { return existingCandidateId; }
    public void setExistingCandidateId(String existingCandidateId) { this.existingCandidateId = existingCandidateId; }

    public String getCandidateId() { return candidateId; }
    public void setCandidateId(String candidateId) { this.candidateId = candidateId; }
}
