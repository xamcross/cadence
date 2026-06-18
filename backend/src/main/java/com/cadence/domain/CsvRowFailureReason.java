package com.cadence.domain;

/** F42 value-free per-row validation failure reason (FR-009/FR-017 — never carries the offending cell value). */
public enum CsvRowFailureReason {
    MISSING_REQUIRED,
    INVALID_EMAIL,
    MALFORMED_ROW,
    FIELD_TOO_LONG
}
