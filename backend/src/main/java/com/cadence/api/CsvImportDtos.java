package com.cadence.api;

import java.time.Instant;
import java.util.List;

/** F42 wire DTOs for the CSV import API (contracts/import-api.md). Enum-valued fields serialize as Strings. */
public final class CsvImportDtos {

    private CsvImportDtos() {}

    public record UploadAccepted(String jobId, String status) {}

    public record RowResultDto(int rowNumber, String status, String failingField, String reason,
                               String existingCandidateId, String candidateId) {}

    public record JobStatusResponse(String jobId, String status, String originalFilename,
                                    int totalRows, int importedCount, int rejectedCount,
                                    int duplicatePendingCount, int mergedCount, int skippedCount,
                                    String rejectionReason, List<RowResultDto> rowResults,
                                    Instant createdAt, Instant completedAt) {}

    /** A single per-row merge/skip decision. action: MERGE | SKIP. */
    public record Decision(int rowNumber, String action) {}

    /** decisions are applied per-row; defaultAction (optional) resolves any still-pending row not named. */
    public record ResolveRequest(List<Decision> decisions, String defaultAction) {}
}
