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
    DISPATCH_BOUNCED
}
