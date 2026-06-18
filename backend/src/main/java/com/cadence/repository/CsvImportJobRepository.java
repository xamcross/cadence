package com.cadence.repository;

import com.cadence.domain.CsvImportJob;
import com.cadence.domain.CsvImportJobStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * F42 import-job persistence. The due / expiry / orphan finders are explicit {@code @Query}+{@code Pageable}
 * (the F12 lesson — never a derived multi-criteria method, never unbounded).
 */
public interface CsvImportJobRepository extends MongoRepository<CsvImportJob, String> {

    /** Workspace-scoped status read (no-oracle 404 on miss). */
    Optional<CsvImportJob> findByWorkspaceIdAndId(String workspaceId, String id);

    /** Due-sweep: ACCEPTED jobs, oldest first. */
    @Query("{ 'status': ?0, 'createdAt': { $lte: ?1 } }")
    List<CsvImportJob> findDue(CsvImportJobStatus status, Instant now, Pageable pageable);

    /** TTL reaper: AWAITING_DUPLICATE_DECISION jobs past their expiry. */
    @Query("{ 'status': ?0, 'expiresAt': { $lte: ?1 } }")
    List<CsvImportJob> findExpiredAwaiting(CsvImportJobStatus status, Instant now, Pageable pageable);

    /** Orphan reaper: PROCESSING jobs whose worker died (updatedAt older than the threshold). */
    @Query("{ 'status': ?0, 'updatedAt': { $lte: ?1 } }")
    List<CsvImportJob> findOrphanedProcessing(CsvImportJobStatus status, Instant threshold, Pageable pageable);
}
