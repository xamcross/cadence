package com.cadence.domain;

/**
 * Closed-vocabulary reason for an erasure request / decision (F04, FR-011/FR-013). Enum-typed and
 * server-validated so a candidate (or any caller) can NEVER store free-text PII on the request
 * record, which would survive the wipe and re-identify the subject.
 */
public enum ErasureReasonCode {
    OPERATOR,
    CANDIDATE_REQUEST,
    RETENTION,
    NOT_A_CANDIDATE,
    OTHER
}
