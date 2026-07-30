package com.cadence.domain;

/** 032 -- provider-mirrored license state (FR-002). CANCELLED still confers TEAM until expiresAt. */
public enum EntitlementStatus {
    ACTIVE,
    CANCELLED,
    EXPIRED
}
