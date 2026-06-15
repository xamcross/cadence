package com.cadence.domain;

import java.util.List;

/**
 * Outcome of a panel event write (F10, research D10) — what F13's atomic booking reads to decide
 * commit / retry / roll back / surface a reconnect prompt.
 */
public record PanelBookingResult(PanelOutcome outcome, List<MemberEventResult> perMember) {

    /** Overall panel outcome. */
    public enum PanelOutcome {
        /** All participants' events created. */
        CREATED,
        /** A participant failed; the already-created events were all deleted (zero orphans). */
        ROLLED_BACK,
        /** Rollback ran but a compensating delete could not complete — an orphan may remain (FR-016a). */
        CLEANUP_INCOMPLETE
    }

    /** Per-participant outcome within the panel. */
    public enum MemberOutcome { CREATED, FAILED, ROLLED_BACK, CLEANUP_INCOMPLETE, NEEDS_RECONNECTION }

    public record MemberEventResult(String memberId, MemberOutcome outcome, String providerEventId) {}
}
