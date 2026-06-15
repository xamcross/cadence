package com.cadence.domain;

/** Candidate erasure lifecycle (F04, FR-006). One-way {@code ACTIVE -> ERASED}. */
public enum ErasureState {
    ACTIVE,
    ERASED
}
