package com.cadence.repository;

import com.cadence.domain.DispatchStatus;
import com.cadence.domain.EmailDispatch;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * F22 dispatch outbox. The {@code {workspaceId,idempotencyKey}} lookup backs the idempotent enqueue;
 * {@code findByProviderMessageRef} correlates an inbound webhook event to its row. The due-row finder is
 * an <b>explicit {@code @Query}</b> with a {@code Pageable} batch cap (the F12
 * {@code InvalidMongoDbApiUsageException} lesson — never a derived multi-criteria method, and never an
 * unbounded read into one scheduler tick). All status transitions themselves are {@code findAndModify}
 * CAS in the service, not via these finders.
 */
public interface EmailDispatchRepository extends MongoRepository<EmailDispatch, String> {

    Optional<EmailDispatch> findByWorkspaceIdAndIdempotencyKey(String workspaceId, String idempotencyKey);

    Optional<EmailDispatch> findByProviderMessageRef(String providerMessageRef);

    /** Due rows for the scheduled worker: a given status whose schedule + backoff gates have both elapsed. */
    @Query("{ 'status': ?0, 'scheduledFor': { $lte: ?1 }, 'nextAttemptAt': { $lte: ?1 } }")
    List<EmailDispatch> findDue(DispatchStatus status, Instant now, Pageable pageable);
}
