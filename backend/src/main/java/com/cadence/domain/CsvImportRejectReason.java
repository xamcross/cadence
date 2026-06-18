package com.cadence.domain;

/** F42 whole-file reject cause (FR-008/FR-010/FR-004). */
public enum CsvImportRejectReason {
    SCHEMA_INVALID,
    TOO_MANY_INVALID,
    OVER_LIMIT
}
