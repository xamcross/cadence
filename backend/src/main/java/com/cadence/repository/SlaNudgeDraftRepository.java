package com.cadence.repository;

import com.cadence.domain.SlaDraftStatus;
import com.cadence.domain.SlaNudgeDraft;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface SlaNudgeDraftRepository extends MongoRepository<SlaNudgeDraft, String> {

    /** The current OPEN draft for a candidate (at most one, by the unique partial index). */
    Optional<SlaNudgeDraft> findFirstByWorkspaceIdAndCandidateIdAndStatus(
        String workspaceId, String candidateId, SlaDraftStatus status);

    /** All drafts in a given status for a workspace — backs the silence-list openDraftId join. */
    List<SlaNudgeDraft> findByWorkspaceIdAndStatus(String workspaceId, SlaDraftStatus status);
}
