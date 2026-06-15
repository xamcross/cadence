package com.cadence.domain;

/**
 * Closed enumeration of material candidate audit events (F04, FR-014/FR-18). Codes only — an entry
 * carries no free text, so the candidate {@code auditLog} is non-PII by construction and survives
 * erasure as the accountability record.
 *
 * <p>The trailing values are forward-contract types appended by later features through
 * {@code CandidateAuditService} (MESSAGE_SENT -> F22, BOOKING_CHANGED -> F13, STAGE_CHANGED -> F51);
 * F04 itself never emits them.
 */
public enum CandidateEventType {
    RECORD_CREATED,
    BASIS_RECORDED,
    BASIS_WITHDRAWN,
    ERASURE_REQUESTED,
    ERASURE_REQUEST_CONFIRMED,
    ERASURE_REQUEST_REJECTED,
    ERASURE_COMPLETED,
    RETENTION_FLAGGED,
    RETENTION_FLAG_CLEARED,
    RETENTION_DELETED,
    // Forward-contract types (declared, not emitted by F04):
    MESSAGE_SENT,
    BOOKING_CHANGED,
    STAGE_CHANGED
}
