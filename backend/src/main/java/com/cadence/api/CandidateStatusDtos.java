package com.cadence.api;

import com.cadence.domain.CandidateStatusOutcome;

import java.time.LocalDate;

/** F30 request/response shapes (contracts A–E). The candidate view carries data + displayState only (no copy). */
public final class CandidateStatusDtos {

    private CandidateStatusDtos() {}

    /** Server-computed page state (D5). Precedence TERMINAL > PAST_DATE > PUBLISHED > UNDER_REVIEW. */
    public enum DisplayState { TERMINAL, PAST_DATE, PUBLISHED, UNDER_REVIEW }

    /**
     * Contract A — the candidate view. Minimal + escaped (Angular interpolation escapes on render); no
     * candidate id, no PII beyond the recruiter-authored status text the candidate is meant to see.
     * {@code stage}/{@code nextStep}/{@code expectedDate} are present only for the states that need them.
     */
    public record CandidateStatusView(DisplayState displayState, String stage, String nextStep,
                                      LocalDate expectedDate, CandidateStatusOutcome outcome, String workspaceZone) {}

    /** Contract C — recruiter publish request. */
    public record PublishStatusRequest(CandidateStatusOutcome outcome, String stage, String nextStep,
                                       LocalDate expectedDate) {}

    /**
     * Contract C/D — recruiter response: the persisted (decrypted) status + the resolved displayState +
     * the current candidate status link so the recruiter can copy/share it.
     */
    public record RecruiterStatusResponse(DisplayState displayState, CandidateStatusOutcome outcome, String stage,
                                          String nextStep, LocalDate expectedDate, String statusLink) {}

    /** Contract E — rotate-link response. */
    public record RotateLinkResponse(String statusLink) {}

    /** Contract B — candidate erasure-submit ack (indistinguishable across all cases). */
    public record ErasureAckResponse(String status) {
        public static ErasureAckResponse received() { return new ErasureAckResponse("received"); }
    }
}
