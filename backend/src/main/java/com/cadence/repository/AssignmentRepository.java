package com.cadence.repository;

import com.cadence.domain.Assignment;
import com.cadence.domain.ResourceType;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface AssignmentRepository extends MongoRepository<Assignment, String> {

    List<Assignment> findByWorkspaceId(String workspaceId);

    List<Assignment> findByWorkspaceIdAndMemberId(String workspaceId, String memberId);

    List<Assignment> findByWorkspaceIdAndMemberIdAndResourceType(
        String workspaceId, String memberId, ResourceType resourceType);

    /** Workspace-scoped fetch (used by Admin/Recruiter, who are not assignment-scoped). */
    Optional<Assignment> findByWorkspaceIdAndId(String workspaceId, String id);

    /**
     * Scoped fetch for Hiring Manager / Interviewer: returns empty for BOTH "missing" and
     * "exists-but-not-yours", giving one shared not-found path (FR-025/SC-015).
     */
    Optional<Assignment> findByWorkspaceIdAndIdAndMemberId(String workspaceId, String id, String memberId);

    boolean existsByWorkspaceIdAndResourceTypeAndResourceIdAndMemberId(
        String workspaceId, ResourceType resourceType, String resourceId, String memberId);
}
