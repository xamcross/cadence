package com.cadence.domain;

/**
 * Why a required member made slots un-offerable (F12, FR-014). Mirrors the non-{@link AvailabilityStatus#DATA}
 * availability statuses so the caller (F13/F14) sees a reason DISTINCT from "busy" — never a silent "free".
 */
public enum UnschedulableReason {
    NOT_CONNECTED,
    NEEDS_RECONNECTION,
    TEMPORARILY_UNAVAILABLE;

    /** Map a non-DATA {@link AvailabilityStatus} to its reason; DATA has no reason (returns null). */
    public static UnschedulableReason from(AvailabilityStatus status) {
        return switch (status) {
            case NOT_CONNECTED -> NOT_CONNECTED;
            case NEEDS_RECONNECTION -> NEEDS_RECONNECTION;
            case TEMPORARILY_UNAVAILABLE -> TEMPORARILY_UNAVAILABLE;
            case DATA -> null;
        };
    }
}
