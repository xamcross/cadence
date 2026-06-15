package com.cadence.domain;

/**
 * Closed-vocabulary outcome/reason code for a candidate audit entry (F04, FR-014). Enum-typed (not a
 * free String) so no caller can inject candidate-derived text into the append-only log.
 */
public enum CandidateAuditOutcome {
    CREATED,
    RECORDED,
    WITHDRAWN,
    REQUESTED,
    CONFIRMED,
    REJECTED,
    FLAGGED,
    CLEARED,
    DELETED,
    // Erasure-completion reasons (which path performed the wipe):
    OPERATOR,
    CANDIDATE_REQUEST,
    RETENTION
}
