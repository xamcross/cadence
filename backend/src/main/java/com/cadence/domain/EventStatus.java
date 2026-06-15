package com.cadence.domain;

/**
 * Lifecycle of a Cadence-managed calendar event record (F10, research D6/D10).
 */
public enum EventStatus {
    /** Created on the provider; the live state. */
    CREATED,
    /** Deleted (cancellation or successful rollback). */
    DELETED,
    /** A compensating delete exhausted its retry budget — an orphan may remain; reconcilable (FR-016a). */
    CLEANUP_INCOMPLETE
}
