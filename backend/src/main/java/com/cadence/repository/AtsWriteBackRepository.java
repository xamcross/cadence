package com.cadence.repository;

import com.cadence.domain.AtsWriteBack;
import com.cadence.domain.AtsWriteBackStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * F40 write-back outbox. The {@code {workspaceId,idempotencyKey}} lookup backs the idempotent enqueue;
 * the candidate+status finder backs the erasure sweep. The due-row and stuck-row finders are
 * <b>explicit {@code @Query}</b> with a {@code Pageable} batch cap (the F12
 * {@code InvalidMongoDbApiUsageException} lesson - never a derived multi-criteria method, and never an
 * unbounded read into one scheduler tick). All status transitions themselves are {@code findAndModify}
 * CAS in the service, not via these finders.
 */
public interface AtsWriteBackRepository extends MongoRepository<AtsWriteBack, String> {

    Optional<AtsWriteBack> findByWorkspaceIdAndIdempotencyKey(String workspaceId, String idempotencyKey);

    List<AtsWriteBack> findByWorkspaceIdAndCandidateIdAndStatus(String workspaceId, String candidateId, AtsWriteBackStatus status);

    /** The Admin dead-letter list for a workspace (no PII on these rows). */
    List<AtsWriteBack> findByWorkspaceIdAndStatus(String workspaceId, AtsWriteBackStatus status);

    /** Due rows for the drain scan: a given status whose backoff gate has elapsed. */
    @Query("{ 'status': ?0, 'nextAttemptAt': { $lte: ?1 } }")
    List<AtsWriteBack> findDue(AtsWriteBackStatus status, Instant now, Pageable pageable);

    long countByStatus(AtsWriteBackStatus status);

    /** Stuck in-flight rows for the reaper: a given status not touched since the staleness threshold. */
    @Query("{ 'status': ?0, 'updatedAt': { $lt: ?1 } }")
    List<AtsWriteBack> findStuck(AtsWriteBackStatus status, Instant threshold, Pageable pageable);
}
