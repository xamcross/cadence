package com.cadence.domain;

/**
 * The recruiter-selected candidate status outcome (F30, data-model §2). Append-only.
 *
 * <ul>
 *   <li>{@code IN_PROGRESS} — the live, dated status (stage + next step + expected date required).</li>
 *   <li>{@code COMPLETE_OFFER} — terminal; honest concluded message, no date required.</li>
 *   <li>{@code COMPLETE_REJECTED} — terminal; honest concluded message, no date required.</li>
 * </ul>
 */
public enum CandidateStatusOutcome {
    IN_PROGRESS,
    COMPLETE_OFFER,
    COMPLETE_REJECTED
}
