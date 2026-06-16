package com.cadence.domain;

/**
 * Whether a {@link SchedulingRequest} is the initial booking attempt or a reschedule round (F20, data-model §1).
 * A reschedule books the new time under a NEW {@code bookingRef} (a new request, {@code mode=RESCHEDULE})
 * linked to the original via {@code parentRequestId}/{@code rootRequestId} — forced by the F10
 * {@code createForParticipant} idempotent fast-path which keys on {@code bookingRef} (research D1).
 * An absent {@code mode} on a pre-F20 row is treated as {@code INITIAL}.
 */
public enum SchedulingMode {
    INITIAL,
    RESCHEDULE
}
