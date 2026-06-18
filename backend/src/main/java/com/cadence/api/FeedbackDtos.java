package com.cadence.api;

import com.cadence.domain.FeedbackRequestStatus;

import java.time.Instant;
import java.util.List;

/**
 * F32 wire DTOs (contracts/feedback-api.md). The public token endpoints use a 200 state-envelope (FORM / USED /
 * EXPIRED / SUBMITTED) so no status code distinguishes used/invalidated/unknown from expired (no state oracle,
 * FR-030). The recruiter read carries the decrypted scorecard (the recruiter is authorized to see it; never
 * logged, {@code no-store}).
 */
public final class FeedbackDtos {

    private FeedbackDtos() {}

    // ----- public token surface (US1) -----

    /** One competency rating {dimension, 1..4 score}. */
    public record Rating(String dimension, int score) {}

    /** GET /api/feedback/{token}: the BLANK form + a non-PII interview label. No prior content (write-only). */
    public record ScorecardFormView(String state, String interviewLabel,
                                    List<String> recommendationOptions, List<String> ratingDimensions) {}

    /** POST /api/feedback/{token} body. */
    public record ScorecardSubmission(String recommendation, List<Rating> ratings, String comment) {}

    /** POST result envelope (state = SUBMITTED / USED / EXPIRED). */
    public record SubmitResponse(String state) {}

    // ----- internal recruiter surface (US3) -----

    /** The decrypted submitted scorecard (recruiter read only). */
    public record ScorecardView(String recommendation, List<Rating> ratings, String comment) {}

    /** Per-interviewer status on one interview; {@code scorecard} non-null only when SUBMITTED. */
    public record InterviewFeedbackItem(String interviewerMemberId, FeedbackRequestStatus status,
                                        ScorecardView scorecard, Instant submittedAt) {}

    public record InterviewFeedbackView(String interviewEventId, List<InterviewFeedbackItem> items) {}

    /** One outstanding feedback request (the workspace pending list — ids only, no PII). */
    public record PendingItem(String interviewEventId, String interviewerMemberId, String candidateId,
                              int reminderLevelSent) {}

    public record PendingListResponse(List<PendingItem> items) {}
}
