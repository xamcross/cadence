package com.cadence.domain;

/**
 * Status of an outbound ATS write-back outbox row (F40, data-model section 2).
 *
 * <p>Transitions are {@code findAndModify} CAS in the service: {@code PENDING -> SENDING}
 * (claim); {@code SENDING -> DELIVERED} (provider accept); {@code SENDING -> PENDING}
 * (transient re-queue with backoff); {@code SENDING -> DEAD_LETTER} (fatal or cap);
 * {@code PENDING -> CANCELLED} (disconnect or candidate erasure sweep).
 */
public enum AtsWriteBackStatus {
    PENDING,
    SENDING,
    DELIVERED,
    DEAD_LETTER,
    CANCELLED
}
