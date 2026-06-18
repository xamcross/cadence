package com.cadence.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * F42 import job (one per upload). Holds the lifecycle, counters, and per-row results that drive the status
 * surface + audit. <b>No plaintext candidate PII at rest</b> — the raw bytes live in {@link CsvImportFile}
 * (encrypted, disposed on terminal), and {@code rowResults} carry only ids/enums/value-free reasons.
 *
 * <p>{@code originalFilename} is recruiter-chosen (low-sensitivity), returned on the status surface and
 * deliberately excluded from the PII sentinel scan; it MUST NEVER be logged (the one PII-adjacent String on
 * the hot path the scan won't catch).
 */
@Document(collection = "csvImportJobs")
public class CsvImportJob {

    @Id
    private String id;

    private String workspaceId;
    private String actorMemberId;
    private CsvImportJobStatus status;
    private String originalFilename;

    /** FK -> {@link CsvImportFile} while the blob exists; null after disposal. */
    @Field(write = Field.Write.NON_NULL)
    private String fileId;

    private int totalRows;
    private int importedCount;
    private int mergedCount;
    private int skippedCount;
    private int rejectedCount;
    private int duplicatePendingCount;

    private List<CsvImportRowResult> rowResults = new ArrayList<>();

    @Field(write = Field.Write.NON_NULL)
    private CsvImportRejectReason rejectionReason;

    private Instant expiresAt;
    private Instant createdAt;
    private Instant updatedAt;

    @Field(write = Field.Write.NON_NULL)
    private Instant completedAt;

    public CsvImportJob() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; }

    public String getActorMemberId() { return actorMemberId; }
    public void setActorMemberId(String actorMemberId) { this.actorMemberId = actorMemberId; }

    public CsvImportJobStatus getStatus() { return status; }
    public void setStatus(CsvImportJobStatus status) { this.status = status; }

    public String getOriginalFilename() { return originalFilename; }
    public void setOriginalFilename(String originalFilename) { this.originalFilename = originalFilename; }

    public String getFileId() { return fileId; }
    public void setFileId(String fileId) { this.fileId = fileId; }

    public int getTotalRows() { return totalRows; }
    public void setTotalRows(int totalRows) { this.totalRows = totalRows; }

    public int getImportedCount() { return importedCount; }
    public void setImportedCount(int importedCount) { this.importedCount = importedCount; }

    public int getMergedCount() { return mergedCount; }
    public void setMergedCount(int mergedCount) { this.mergedCount = mergedCount; }

    public int getSkippedCount() { return skippedCount; }
    public void setSkippedCount(int skippedCount) { this.skippedCount = skippedCount; }

    public int getRejectedCount() { return rejectedCount; }
    public void setRejectedCount(int rejectedCount) { this.rejectedCount = rejectedCount; }

    public int getDuplicatePendingCount() { return duplicatePendingCount; }
    public void setDuplicatePendingCount(int duplicatePendingCount) { this.duplicatePendingCount = duplicatePendingCount; }

    public List<CsvImportRowResult> getRowResults() { return rowResults; }
    public void setRowResults(List<CsvImportRowResult> rowResults) { this.rowResults = rowResults; }

    public CsvImportRejectReason getRejectionReason() { return rejectionReason; }
    public void setRejectionReason(CsvImportRejectReason rejectionReason) { this.rejectionReason = rejectionReason; }

    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }

    /** Deliberately omits originalFilename (PII-adjacent) and all row content. */
    @Override
    public String toString() {
        return "CsvImportJob{id=" + id + ", workspaceId=" + workspaceId + ", status=" + status + "}";
    }
}
