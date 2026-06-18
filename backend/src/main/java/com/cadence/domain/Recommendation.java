package com.cadence.domain;

/**
 * F32 fixed four-point overall recommendation (FR-008). The required field of the scorecard. Serialized inside
 * the encrypted {@code scorecardPayload} JSON (candidate-assessment PII) — never a top-level queryable field.
 */
public enum Recommendation {
    STRONG_YES,
    YES,
    NO,
    STRONG_NO
}
