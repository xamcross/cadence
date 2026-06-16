package com.cadence.domain;

/**
 * Value-free outcome reason recorded on a {@link SchedulingRequest} and in audit (F13, data-model §1).
 * Ids/enums only — never PII, never a token value.
 */
public enum SchedulingOutcomeReason {
    NONE,
    NO_SLOTS,
    NOT_CONTACTABLE,
    UNSCHEDULABLE_REQUIRED,
    SLOT_TAKEN,
    STALE_SLOT,
    BOOKING_FAILED,
    CLEANUP_INCOMPLETE,
    EXPIRED
}
