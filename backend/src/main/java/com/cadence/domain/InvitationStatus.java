package com.cadence.domain;

/** Invitation lifecycle. Expiry is handled by a TTL index (no stored EXPIRED state). */
public enum InvitationStatus {
    PENDING,
    CONSUMED
}
