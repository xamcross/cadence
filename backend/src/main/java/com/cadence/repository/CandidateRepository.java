package com.cadence.repository;

import com.cadence.domain.Candidate;
import com.cadence.domain.ErasureState;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface CandidateRepository extends MongoRepository<Candidate, String> {

    Optional<Candidate> findByWorkspaceIdAndId(String workspaceId, String id);

    /** Lookup by the keyed email hash (non-unique index, ChangeUnit005). Never query the encrypted email. */
    List<Candidate> findByWorkspaceIdAndEmailHash(String workspaceId, String emailHash);

    /** Retention scan predicate: over-age, not-yet-erased (uses the {workspaceId,lastContactAt} index). */
    List<Candidate> findByWorkspaceIdAndErasureStateAndLastContactAtBefore(
        String workspaceId, ErasureState erasureState, Instant threshold);

    /**
     * F31 SLA breach/silence scan: the SAME predicate, paginated so the per-workspace read is bounded
     * (the F23 page-cap precedent — SC-013). Index-backed on {workspaceId,lastContactAt} (ChangeUnit001).
     * Distinct overload — the 3-arg method above is left unchanged for RetentionService.
     */
    List<Candidate> findByWorkspaceIdAndErasureStateAndLastContactAtBefore(
        String workspaceId, ErasureState erasureState, Instant threshold, Pageable pageable);

    /** Currently-flagged, active candidates — for the Admin review list and the flag-clear sweep. */
    List<Candidate> findByWorkspaceIdAndRetentionFlaggedTrueAndErasureState(
        String workspaceId, ErasureState erasureState);

    /**
     * F30: resolve an inbound status-page request by the keyed status-token hash (partial-unique index,
     * ChangeUnit015). Non-workspace-scoped — the token IS the auth (the F13 {@code findByTokenHash} precedent).
     * The caller folds "erased" into the same not-found so the view is not an existence oracle (FR-031).
     */
    Optional<Candidate> findByStatusTokenHash(String statusTokenHash);
}
