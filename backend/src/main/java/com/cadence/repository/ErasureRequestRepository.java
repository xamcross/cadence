package com.cadence.repository;

import com.cadence.domain.ErasureRequest;
import com.cadence.domain.RequestStatus;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface ErasureRequestRepository extends MongoRepository<ErasureRequest, String> {

    List<ErasureRequest> findByWorkspaceIdAndStatus(String workspaceId, RequestStatus status);
}
