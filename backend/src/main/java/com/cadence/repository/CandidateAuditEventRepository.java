package com.cadence.repository;

import com.cadence.domain.CandidateAuditEvent;
import org.springframework.data.repository.Repository;

import java.util.List;

/**
 * Append-only candidate audit log (F04, FR-015). Deliberately extends the bare {@link Repository}
 * marker (NOT {@code CrudRepository}/{@code MongoRepository}) so it exposes NO {@code delete*} or
 * {@code update*} method — append-only is structural, asserted by {@code AuditAppendOnlyTest}.
 * Appends are written via {@code MongoTemplate.insert(...)} in {@code CandidateAuditService}; only
 * read finders live here.
 */
public interface CandidateAuditEventRepository extends Repository<CandidateAuditEvent, String> {

    /** Full chronological log for a candidate; {@code _id} is the deterministic same-tick tiebreaker. */
    List<CandidateAuditEvent> findByWorkspaceIdAndCandidateIdOrderByOccurredAtAscIdAsc(
        String workspaceId, String candidateId);

    long countByCandidateId(String candidateId);
}
