package com.cadence.domain;

import java.util.List;

/**
 * Per-member availability result (F10). {@code status == DATA} with an empty {@code busy} list means the
 * member is genuinely free; any other status means not-schedulable with a distinguishable reason (FR-004).
 * Carries no event content.
 */
public record MemberAvailability(String memberId, AvailabilityStatus status, List<BusyInterval> busy) {}
