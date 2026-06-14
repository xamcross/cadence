package com.cadence.service;

import com.cadence.api.RbacExceptions;
import com.cadence.domain.Assignment;
import com.cadence.domain.ResourceType;
import com.cadence.repository.AssignmentRepository;
import com.cadence.repository.MemberRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Server-side data scoping primitive (F02 US4, FR-023..FR-027/FR-032). Hiring Managers are scoped to
 * their REQUISITION assignments and Interviewers to their INTERVIEW assignments. The reusable
 * {@link #requireAssigned} check is consumed by later features (F13 confirm-slot, F32 submit-feedback)
 * before a scoped write. Every query includes workspaceId, so cross-workspace access is impossible.
 */
@Service
public class AssignmentService {

    private final AssignmentRepository assignments;
    private final MemberRepository members;
    private final Clock clock;

    public AssignmentService(AssignmentRepository assignments, MemberRepository members, Clock clock) {
        this.assignments = assignments;
        this.members = members;
        this.clock = clock;
    }

    /** Resource ids assigned to a member of a given type — for scoping a collection read (FR-024). */
    public List<String> assignedResourceIds(String workspaceId, String memberId, ResourceType type) {
        return assignments.findByWorkspaceIdAndMemberIdAndResourceType(workspaceId, memberId, type)
            .stream().map(Assignment::getResourceId).toList();
    }

    public boolean isAssigned(String workspaceId, String memberId, ResourceType type, String resourceId) {
        return assignments.existsByWorkspaceIdAndResourceTypeAndResourceIdAndMemberId(
            workspaceId, type, resourceId, memberId);
    }

    /** Scoped-write guard (FR-032): throws if the member is not assigned the resource. */
    public void requireAssigned(String workspaceId, String memberId, ResourceType type, String resourceId) {
        if (!isAssigned(workspaceId, memberId, type, resourceId)) {
            throw new RbacExceptions.NotAssignedException();
        }
    }

    /** Caller's own assignments (Hiring Manager / Interviewer scoped list, FR-024/FR-026). */
    public List<Assignment> listForMember(String workspaceId, String memberId) {
        return assignments.findByWorkspaceIdAndMemberId(workspaceId, memberId);
    }

    /** All assignments in the workspace (Admin), or one member's (Admin/Recruiter with ?memberId=). */
    public List<Assignment> listForWorkspace(String workspaceId, String memberIdOrNull) {
        return memberIdOrNull == null
            ? assignments.findByWorkspaceId(workspaceId)
            : assignments.findByWorkspaceIdAndMemberId(workspaceId, memberIdOrNull);
    }

    /**
     * Scoped single-record fetch for Hiring Manager / Interviewer. Returns empty for BOTH a missing
     * id and an id owned by another member, so the caller throws ONE shared not-found — the response
     * is indistinguishable and cannot confirm a record's existence (FR-025/SC-015).
     */
    public Assignment getScopedOrNotFound(String workspaceId, String memberId, String assignmentId) {
        return assignments.findByWorkspaceIdAndIdAndMemberId(workspaceId, assignmentId, memberId)
            .orElseThrow(RbacExceptions.ScopedNotFoundException::new);
    }

    /** Unscoped fetch for Admin/Recruiter. */
    public Assignment getOrNotFound(String workspaceId, String assignmentId) {
        return assignments.findByWorkspaceIdAndId(workspaceId, assignmentId)
            .orElseThrow(RbacExceptions.ScopedNotFoundException::new);
    }

    /** Admin assigns a resource to a member; the unique index makes a duplicate a 409 (FR-017). */
    public Assignment create(String workspaceId, String createdByMemberId, String memberId,
                             ResourceType type, String resourceId) {
        // The target member MUST belong to the caller's workspace — never stamp a foreign member's id
        // into this workspace (indistinguishable 404 on mismatch, no cross-workspace leak).
        members.findById(memberId)
            .filter(m -> workspaceId.equals(m.getWorkspaceId()))
            .orElseThrow(RbacExceptions.ScopedNotFoundException::new);
        Assignment a = new Assignment();
        a.setWorkspaceId(workspaceId);
        a.setMemberId(memberId);
        a.setResourceType(type);
        a.setResourceId(resourceId);
        a.setCreatedByMemberId(createdByMemberId);
        a.setCreatedAt(Instant.now(clock));
        try {
            return assignments.save(a);
        } catch (DuplicateKeyException e) {
            throw new RbacExceptions.DuplicateAssignmentException();
        }
    }

    /** Delete an assignment, scoped to BOTH the workspace and the member named in the URL. */
    public void delete(String workspaceId, String memberId, String assignmentId) {
        Assignment a = assignments.findByWorkspaceIdAndIdAndMemberId(workspaceId, assignmentId, memberId)
            .orElseThrow(RbacExceptions.ScopedNotFoundException::new);
        assignments.delete(a);
    }
}
