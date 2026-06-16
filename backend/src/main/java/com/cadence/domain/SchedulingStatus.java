package com.cadence.domain;

/**
 * Lifecycle of a {@link SchedulingRequest} (F13, data-model §4). All transitions are {@code findAndModify}
 * CAS (no {@code @Version}). PENDING_SELECTION -> BOOKING (a confirm won the request CAS) -> BOOKED |
 * back to PENDING_SELECTION (re-validate/claim/rollback failed) | CLEANUP_INCOMPLETE (orphan surfaced).
 * EXPIRED / SUPERSEDED are terminal non-bookable states.
 */
public enum SchedulingStatus {
    PENDING_SELECTION,
    BOOKING,
    BOOKED,
    EXPIRED,
    SUPERSEDED,
    CLEANUP_INCOMPLETE
}
