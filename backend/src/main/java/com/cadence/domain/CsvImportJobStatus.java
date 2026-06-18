package com.cadence.domain;

/**
 * F42 import-job lifecycle. {@code ACCEPTED} -> {@code PROCESSING} is the single-winner claim CAS; the
 * terminal states ({@code COMPLETED}, {@code REJECTED}, {@code FAILED}, {@code EXPIRED}) all dispose the raw
 * blob. {@code AWAITING_DUPLICATE_DECISION} is non-terminal — it auto-expires to {@code EXPIRED} (defaulting
 * unresolved duplicates to skip) past the TTL (FR-021a/D8).
 */
public enum CsvImportJobStatus {
    ACCEPTED,
    PROCESSING,
    AWAITING_DUPLICATE_DECISION,
    COMPLETED,
    REJECTED,
    FAILED,
    EXPIRED
}
