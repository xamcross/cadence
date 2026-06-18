package com.cadence.domain;

/** F42 per-row outcome. */
public enum CsvImportRowStatus {
    IMPORTED,
    REJECTED,
    DUPLICATE_PENDING,
    MERGED,
    SKIPPED
}
