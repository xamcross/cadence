package com.cadence.domain;

/**
 * F42: candidate provenance. A {@code null} value (legacy/native docs that predate this field) is read as
 * {@link #NATIVE}. {@link #CSV_IMPORT} records carry no {@code atsProvider}, so the F40/F41 ATS reconcile
 * lookup can never match them (SC-014).
 */
public enum CandidateOrigin {
    NATIVE,
    ATS,
    CSV_IMPORT
}
