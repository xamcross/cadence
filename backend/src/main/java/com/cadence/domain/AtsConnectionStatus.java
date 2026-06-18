package com.cadence.domain;

/**
 * Lifecycle state of a workspace's ATS connection (F40, data-model section 1).
 *
 * <p>State machine: {@code INTEGRATION_PENDING -> CONNECTED} (verify ok);
 * {@code CONNECTED -> NEEDS_REAUTH} (auth classify); {@code CONNECTED -> ERROR}
 * (transient/degraded); {@code * -> DISCONNECTED} (Admin disconnect, key destroyed);
 * {@code NEEDS_REAUTH -> CONNECTED} (re-verify).
 */
public enum AtsConnectionStatus {
    INTEGRATION_PENDING,
    CONNECTED,
    NEEDS_REAUTH,
    ERROR,
    DISCONNECTED
}
