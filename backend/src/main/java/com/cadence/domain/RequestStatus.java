package com.cadence.domain;

/** Lifecycle of a candidate-initiated erasure request (F04, FR-011). Transitions are guarded. */
public enum RequestStatus {
    PENDING,
    RESOLVED_CONFIRMED,
    RESOLVED_REJECTED
}
