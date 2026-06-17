package com.cadence.api;

import com.cadence.domain.EmailMessageType;
import com.cadence.domain.SlaState;

import java.time.Instant;
import java.util.List;

/**
 * F31 SLA Nudge wire DTOs (contracts A-E). All value-free: ids, enums, instants, and the recruiter-facing
 * rendered preview (subject/body) which the recruiter is authorized to see. No token, no candidate id in a URL.
 */
public final class SlaNudgeDtos {

    private SlaNudgeDtos() {}

    /** Contract A item / Contract B response: a candidate's communication-health state + open-draft ref. */
    public record CandidateSla(String candidateId, SlaState slaState, Instant lastActivityAt, String openDraftId) {}

    /** Contract A: the workspace silence list (AMBER + RED only). */
    public record SilenceListResponse(List<CandidateSla> items) {}

    /** Contract C: the rendered SLA holding-message preview (no-store; never logged). */
    public record DraftPreviewResponse(EmailMessageType messageType, String subject, String body,
                                       List<String> missingFields) {}

    /** Contract D/E: the result of an approve/dismiss action. */
    public record ActionResponse(String draftId, String result) {}
}
