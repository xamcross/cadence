package com.cadence.repository;

import com.cadence.domain.ErasureRequest;
import com.cadence.domain.RequestStatus;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface ErasureRequestRepository extends MongoRepository<ErasureRequest, String> {

    List<ErasureRequest> findByWorkspaceIdAndStatus(String workspaceId, RequestStatus status);

    /** F30 idempotency fast-path: the existing open request for a candidate (the unique index is the real guard). */
    Optional<ErasureRequest> findFirstByWorkspaceIdAndCandidateIdAndStatus(
        String workspaceId, String candidateId, RequestStatus status);
}
