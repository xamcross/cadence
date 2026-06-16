package com.cadence.domain;

/**
 * Value-free recruiter-notification kind (F22, T044). NEVER carries any candidate-resolvable value.
 * Safe to log via {@code .name()} only (the F01.1 logstash {@code kv} footgun).
 */
public enum RecruiterNotificationType {
    /** The consent gate refused a dispatch (FR-008). */
    DISPATCH_REFUSED,
    /** A dispatch terminally failed — retry cap / render / provider rejection (FR-012). */
    DISPATCH_FAILED,
    /** A hard bounce / complaint flagged the candidate undeliverable (FR-017). */
    DISPATCH_BOUNCED,
    // F20 Reschedule & Cancellation (append-only — value-free).
    /** A candidate cancelled their own interview (FR-013). */
    INTERVIEW_CANCELLED_BY_CANDIDATE,
    /** A reschedule attempt found zero compliant alternatives; the original booking was retained (FR-007). */
    RESCHEDULE_NO_SLOTS,
    /** The candidate self-service reschedule cap was reached; the self-service link was invalidated (FR-005). */
    RESCHEDULE_CAP_REACHED,
    /** A calendar event could not be removed after retries — a residual orphan needs manual removal (FR-011/FR-012). */
    CALENDAR_CLEANUP_INCOMPLETE
}
