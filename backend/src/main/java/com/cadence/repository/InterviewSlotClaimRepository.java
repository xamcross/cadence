package com.cadence.repository;

import com.cadence.domain.InterviewSlotClaim;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

/** F13 per-participant slot-claim store (data-model §3). */
public interface InterviewSlotClaimRepository extends MongoRepository<InterviewSlotClaim, String> {

    /** All claims for a booking (the release set). */
    List<InterviewSlotClaim> findByWorkspaceIdAndSchedulingRequestId(String workspaceId, String schedulingRequestId);
}
