package com.cadence.repository;

import com.cadence.domain.SchedulingMode;
import com.cadence.domain.SchedulingRequest;
import com.cadence.domain.SchedulingStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * F13 scheduling-request store (data-model §1). The reaper finders are explicit {@code @Query} with a
 * {@link Pageable} cap (the F12 {@code InvalidMongoDbApiUsageException} lesson + an unbounded-scan guard).
 */
public interface SchedulingRequestRepository extends MongoRepository<SchedulingRequest, String> {

    Optional<SchedulingRequest> findByWorkspaceIdAndId(String workspaceId, String id);

    Optional<SchedulingRequest> findByTokenHash(String tokenHash);

    Optional<SchedulingRequest> findFirstByWorkspaceIdAndCandidateIdOrderByCreatedAtDesc(
        String workspaceId, String candidateId);

    /** Reaper: requests in {@code status} whose link has passed {@code expiresAt}. */
    @Query("{ 'status': ?0, 'expiresAt': { $lt: ?1 } }")
    List<SchedulingRequest> findExpired(SchedulingStatus status, Instant now, Pageable pageable);

    /** Reaper: requests stuck in {@code status} (BOOKING) older than {@code threshold}. */
    @Query("{ 'status': ?0, 'updatedAt': { $lt: ?1 } }")
    List<SchedulingRequest> findStuck(SchedulingStatus status, Instant threshold, Pageable pageable);

    // --- F20 Reschedule & Cancellation ---

    /** Resolve a booking from the reschedule/cancel manage credential (data-model §1). */
    Optional<SchedulingRequest> findByManageTokenHash(String manageTokenHash);

    /** The cap derivation (D5): committed reschedule rounds in a lineage. Pass RESCHEDULE + the committed statuses. */
    long countByRootRequestIdAndModeAndStatusIn(String rootRequestId, SchedulingMode mode, List<SchedulingStatus> statuses);

    /** The authoritative live booking for a candidate's status read (T033) — the live BOOKED row, not the newest child. */
    Optional<SchedulingRequest> findFirstByWorkspaceIdAndCandidateIdAndStatusOrderByCreatedAtDesc(
        String workspaceId, String candidateId, SchedulingStatus status);

    /**
     * Reaper forward-commit recovery (D3): RESCHEDULE rounds that reached BOOKED but whose parent cancel may
     * not have finished (crash window), older than the threshold. The parent-status check is a per-row CAS.
     */
    @Query("{ 'mode': ?0, 'status': ?1, 'updatedAt': { $lt: ?2 } }")
    List<SchedulingRequest> findRescheduleAwaitingForwardCommit(SchedulingMode mode, SchedulingStatus status,
                                                                Instant before, Pageable pageable);

    /** Reaper erasure-teardown (D9): CANCELLED bookings whose provider events still need removal. */
    @Query("{ 'calendarTeardownPending': true }")
    List<SchedulingRequest> findAwaitingCalendarTeardown(Pageable pageable);

    // --- F23 No-Show Defense (data-model §1, explicit @Query + Pageable cap — the F12 lesson) ---

    /** Resolve a booking from the F23 confirm credential. */
    Optional<SchedulingRequest> findByConfirmTokenHash(String confirmTokenHash);

    /**
     * Cascade stage 1: BOOKED bookings due for a confirmation request — start still in the FUTURE
     * ({@code $gt now}, so a past interview is never asked to confirm; stage 3 stamps it no-show) and within
     * the global query bound.
     */
    @Query("{ 'status': 'BOOKED', 'confirmationRequestedAt': null, 'bookedStartAt': { $gt: ?0, $lte: ?1 } }")
    List<SchedulingRequest> findConfirmationRequestDue(Instant now, Instant bound, Pageable pageable);

    /** Cascade stage 2: BOOKED, requested, unconfirmed, not yet escalated, start still in the future. */
    @Query("{ 'status': 'BOOKED', 'confirmationRequestedAt': { $ne: null }, 'candidateConfirmedAt': null, "
        + "'escalatedAt': null, 'bookedStartAt': { $gt: ?0, $lte: ?1 } }")
    List<SchedulingRequest> findEscalationDue(Instant now, Instant bound, Pageable pageable);

    /** Cascade stage 3: BOOKED, unconfirmed, start reached, no-show not yet stamped. */
    @Query("{ 'status': 'BOOKED', 'candidateConfirmedAt': null, 'noShowAt': null, 'bookedStartAt': { $lte: ?0 } }")
    List<SchedulingRequest> findNoShowDue(Instant now, Pageable pageable);

    // --- F32 Interviewer Feedback generation (explicit @Query + Pageable cap — the F12 lesson) ---

    /**
     * Generation scan (research D2): BOOKED occurrences whose start has passed by {@code generationDelay}
     * (so the interview has plausibly ended) and within the query window floor, not yet generated. The
     * {@code {status,bookedStartAt}} index (ChangeUnit014) covers status + the range; {@code feedbackGeneratedAt:
     * null} is a bounded in-memory residual (the F23 {@code confirmationRequestedAt:null} precedent). A
     * CANCELLED / RESCHEDULED occurrence is excluded by {@code status:BOOKED} (FR-006).
     */
    @Query("{ 'status': 'BOOKED', 'feedbackGeneratedAt': null, 'bookedStartAt': { $gt: ?0, $lte: ?1 } }")
    List<SchedulingRequest> findFeedbackGenerationDue(Instant lowerBound, Instant cutoff, Pageable pageable);

    // --- F50 Core Dashboard (data-model section F; index-backed by ChangeUnit021) ---

    /**
     * No-show denominator: live BOOKED interviews whose start is in the window AND has already passed
     * ({@code $gt windowStart, $lte now}). A future-dated interview ({@code bookedStartAt > now}) is excluded
     * (it cannot be a no-show); a CANCELLED/RESCHEDULED row is excluded by {@code status:BOOKED} (final state,
     * one row per lineage -> no double-count). A Mongo {@code count} -- no documents loaded (FR-005).
     */
    @Query(value = "{ 'workspaceId': ?0, 'status': 'BOOKED', 'bookedStartAt': { $gt: ?1, $lte: ?2 } }",
        count = true)
    long countNoShowDenominator(String workspaceId, Instant windowStart, Instant now);

    /** No-show numerator: the qualifying denominator subset stamped no-show ({@code noShowAt != null}; FR-004). */
    @Query(value = "{ 'workspaceId': ?0, 'status': 'BOOKED', 'noShowAt': { $ne: null }, "
        + "'bookedStartAt': { $gt: ?1, $lte: ?2 } }", count = true)
    long countNoShows(String workspaceId, Instant windowStart, Instant now);

    /**
     * Time-to-schedule sample: live BOOKED requests confirmed within the window (windowed on {@code bookedAt}).
     * PROJECTED to {@code {sentAt, bookedAt}} only -- never loads {@code offeredSlots}/the encrypted
     * {@code locationText} -- and {@link Pageable}-capped ({@code medianSampleCap}, a DoS backstop). One row per
     * lineage ({@code status:BOOKED}, the final live round) -> reschedule rounds are not double-counted (FR-003).
     */
    @Query(value = "{ 'workspaceId': ?0, 'status': 'BOOKED', 'bookedAt': { $gt: ?1, $lte: ?2 } }",
        fields = "{ 'sentAt': 1, 'bookedAt': 1 }")
    List<SchedulingRequest> findBookedForVelocity(String workspaceId, Instant windowStart, Instant now,
                                                  Pageable pageable);

    // --- F51 Pipeline View: batch scheduling-status read for the pipeline list (data-model "schedulingRequests") ---

    /**
     * Batch read of ALL scheduling rows for a (bounded) candidate-id set, PROJECTED to the status-relevant fields
     * only (never loads {@code offeredSlots}/the encrypted {@code locationText}). The pipeline resolves each
     * candidate's display status in memory (live BOOKED else newest by {@code createdAt}). Served by the
     * {@code {workspaceId,candidateId,createdAt:-1}} prefix (ChangeUnit012) — NO new index. The id set is already
     * capped by the candidate-page scan, so no extra {@link Pageable} is needed (FR-005/SC-002).
     */
    @Query(value = "{ 'workspaceId': ?0, 'candidateId': { $in: ?1 } }",
        fields = "{ 'candidateId': 1, 'status': 1, 'sentAt': 1, 'expiresAt': 1, 'bookedStartAt': 1, "
            + "'noShowAt': 1, 'createdAt': 1 }")
    List<SchedulingRequest> findByWorkspaceIdAndCandidateIdIn(String workspaceId, java.util.Collection<String> candidateIds);
}
