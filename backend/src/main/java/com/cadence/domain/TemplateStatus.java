package com.cadence.domain;

/** Lifecycle of an interview stage template (F12, FR-004). Retire is a soft-delete — never hard-deleted. */
public enum TemplateStatus {
    /** Offered for starting new scheduling. */
    ACTIVE,
    /** Retired: not offered for new scheduling, but retained for audit and in-flight bookings. */
    RETIRED
}
