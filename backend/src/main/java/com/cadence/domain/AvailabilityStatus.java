package com.cadence.domain;

/**
 * Why a member's busy list is or is not authoritative (F10, FR-004). A non-{@link #DATA} member is
 * <em>not schedulable</em> — the scheduler MUST NOT treat them as fully free.
 */
public enum AvailabilityStatus {
    /** Busy list is authoritative (empty list == genuinely free). */
    DATA,
    /** No calendar connection for this member (or no F10 client for their provider, e.g. Microsoft pre-F11). */
    NOT_CONNECTED,
    /** The member's grant is revoked / scope-deficient — they must reconnect. */
    NEEDS_RECONNECTION,
    /** A transient provider error after bounded retry — availability is temporarily unknown. */
    TEMPORARILY_UNAVAILABLE
}
