package com.cadence.repository;

import com.cadence.domain.Candidate;
import com.cadence.domain.ErasureState;
import com.cadence.integration.AtsProvider;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.Instant;
import java.util.Collection;
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

    /**
     * F40: resolve an imported candidate by the authoritative ATS reconcile key (the unique PARTIAL index
     * {workspaceId,atsProvider,atsExternalRef}, ChangeUnit018). NO erasure filter — the caller resolves first,
     * then guards the update on erasureState==ACTIVE (the resolve-then-guarded-write resurrection defense).
     */
    Optional<Candidate> findByWorkspaceIdAndAtsProviderAndAtsExternalRef(
        String workspaceId, AtsProvider atsProvider, String atsExternalRef);

    /**
     * F50: batch name-load for the CAPPED dashboard silence list (data-model section F). Called only on the
     * truncated id set (<= silenceListCap) so the per-request name-decrypt is bounded (FR-010/FR-012). _id-backed,
     * no new index.
     */
    List<Candidate> findByWorkspaceIdAndIdIn(String workspaceId, Collection<String> ids);

    // --- F51 Pipeline View (data-model section "candidates") — bounded list reads (capped at scanCap). ---

    /**
     * Workspace-wide active candidates (Admin/Recruiter/Read-only pipeline). Backed by the
     * {workspaceId,erasureState,createdAt} index (ChangeUnit022); the {@link Pageable} caps the scan (FR-007/SC-002).
     */
    List<Candidate> findByWorkspaceIdAndErasureState(String workspaceId, ErasureState erasureState, Pageable pageable);

    /**
     * Hiring-Manager scoped active candidates: only those linked to an assigned requisition. Built scan-time FROM
     * {@code AssignmentService.assignedResourceIds(REQUISITION)} (never fetch-all-then-filter). An unassigned
     * candidate (no {@code requisitionId} in BSON) can never match the {@code $in}; an empty id set is short-circuited
     * by the caller to an empty page (FR-013/FR-014). Backed by {workspaceId,requisitionId} (ChangeUnit022).
     */
    List<Candidate> findByWorkspaceIdAndErasureStateAndRequisitionIdIn(
        String workspaceId, ErasureState erasureState, Collection<String> requisitionIds, Pageable pageable);

    /** Truncation-flag count: total active candidates in scope (for the response {@code truncated} flag, D4). */
    long countByWorkspaceIdAndErasureState(String workspaceId, ErasureState erasureState);

    /** HM truncation-flag count: total active candidates on the assigned requisitions. */
    long countByWorkspaceIdAndErasureStateAndRequisitionIdIn(
        String workspaceId, ErasureState erasureState, Collection<String> requisitionIds);
}
