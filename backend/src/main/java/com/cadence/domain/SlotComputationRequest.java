package com.cadence.domain;

import java.time.LocalDate;

/**
 * The internal input to {@code RuleEngine.compute} (F12, contract §E). The range is civil dates in the
 * applicable zone; the engine resolves it to a clamped absolute window. Carries NO member list — the
 * member set is read from the persisted, validated template (the D8 compute-path isolation control).
 */
public record SlotComputationRequest(String workspaceId, String templateId,
                                     LocalDate rangeStart, LocalDate rangeEnd,
                                     String excludeBookingRef) {
    /**
     * F20 carve-out (research D7): when recomputing slots for a reschedule, {@code excludeBookingRef} is the
     * booking being moved — its calendar events are NOT counted against its participants' daily cap, so a
     * reschedule is never falsely refused "no slots" because the interviewer is at cap solely on account of
     * the very interview being moved. Null for an initial computation.
     */
    public SlotComputationRequest(String workspaceId, String templateId, LocalDate rangeStart, LocalDate rangeEnd) {
        this(workspaceId, templateId, rangeStart, rangeEnd, null);
    }
}
