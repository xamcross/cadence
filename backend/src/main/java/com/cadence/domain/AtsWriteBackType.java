package com.cadence.domain;

/**
 * The kind of scheduling activity an ATS write-back records on the external application
 * (F40, data-model section 2).
 */
public enum AtsWriteBackType {
    LINK_SENT,
    CONFIRMED,
    RESCHEDULED,
    CANCELLED,
    NO_SHOW,
    FEEDBACK_SUBMITTED
}
