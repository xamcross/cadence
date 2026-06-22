package com.cadence.repository;

import com.cadence.domain.Requisition;
import com.cadence.domain.RequisitionStatus;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

/** F51 requisition store. All reads are workspace-scoped; backed by the {workspaceId,status} index (ChangeUnit022). */
public interface RequisitionRepository extends MongoRepository<Requisition, String> {

    List<Requisition> findByWorkspaceId(String workspaceId);

    List<Requisition> findByWorkspaceIdAndStatus(String workspaceId, RequisitionStatus status);

    Optional<Requisition> findByWorkspaceIdAndId(String workspaceId, String id);
}
