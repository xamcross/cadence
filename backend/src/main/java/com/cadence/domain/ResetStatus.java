package com.cadence.domain;

/** Password-reset token lifecycle. Expiry is handled by a TTL index. */
public enum ResetStatus {
    PENDING,
    CONSUMED
}
