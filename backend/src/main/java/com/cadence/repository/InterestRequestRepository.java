package com.cadence.repository;

import com.cadence.domain.InterestRequest;
import com.cadence.domain.InterestRequestStatus;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * F70 interest-request persistence. {@code findByWorkspaceIdAndId} is the scoped single-record read (a
 * cross-workspace/absent id is an empty Optional -> ScopedNotFoundException -> indistinguishable 404).
 * {@code findByWorkspaceIdAndStatusInOrderBySubmittedAtDesc} backs the admin queue (recent-first). The
 * {@code openEmailHash} finder resolves the coalesce target after a DuplicateKeyException; the count finder is
 * the durable per-workspace flood ceiling (R6).
 */
public interface InterestRequestRepository extends MongoRepository<InterestRequest, String> {

    Optional<InterestRequest> findByWorkspaceIdAndId(String workspaceId, String id);

    List<InterestRequest> findByWorkspaceIdAndEmailHash(String workspaceId, String emailHash);

    List<InterestRequest> findByWorkspaceIdAndStatusInOrderBySubmittedAtDesc(
        String workspaceId, List<InterestRequestStatus> statuses);

    /** The durable per-workspace flood-ceiling count (FR-018/R6). */
    long countByWorkspaceIdAndSubmittedAtAfter(String workspaceId, Instant after);

    /** Resolve the single open dedup target after a DuplicateKeyException on the unique partial index. */
    Optional<InterestRequest> findByWorkspaceIdAndOpenEmailHash(String workspaceId, String openEmailHash);

    /** Retention purge age scan (FR-021): rows older than the cutoff for a workspace. */
    List<InterestRequest> findByWorkspaceIdAndSubmittedAtBefore(String workspaceId, Instant before);
}
