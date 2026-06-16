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
}
