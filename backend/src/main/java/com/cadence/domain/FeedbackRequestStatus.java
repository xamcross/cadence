package com.cadence.domain;

/**
 * F32 lifecycle of a feedback request (data-model section 2). One-way out of {@code PENDING} via a per-row CAS;
 * a terminal status removes the row from the reminder scan (FR-014).
 */
public enum FeedbackRequestStatus {
    /** Generated, awaiting the interviewer's submission. */
    PENDING,
    /** The interviewer submitted the scorecard (content in the encrypted payload). */
    SUBMITTED,
    /** Candidate erasure invalidated it (FR-023). */
    INVALIDATED,
    /** The interviewer was deactivated/removed — feedback cannot be collected (FR-009). */
    UNCOLLECTIBLE,
    /** The token TTL passed unsubmitted (FR-018). */
    EXPIRED
}
