package com.cadence.domain;

/**
 * State of an {@link InterviewSlotClaim} (F13, data-model §3). The unique partial index covers only
 * {@code ACTIVE} claims, so flipping to {@code RELEASED} removes a claim from the uniqueness constraint
 * (release = CAS, never a delete — preserves an audit trail).
 */
public enum ClaimStatus {
    ACTIVE,
    RELEASED
}
