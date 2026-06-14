package com.cadence.domain;

/** Lifecycle state of a workspace member. Deactivation revokes all sessions (FR-021). */
public enum MemberStatus {
    ACTIVE,
    DEACTIVATED
}
