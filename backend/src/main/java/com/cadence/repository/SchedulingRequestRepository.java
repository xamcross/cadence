package com.cadence.repository;

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
}
