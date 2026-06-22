package com.cadence.service;

import com.cadence.api.PipelineDtos.RequisitionDto;
import com.cadence.api.PipelineExceptions;
import com.cadence.api.RbacExceptions;
import com.cadence.domain.Assignment;
import com.cadence.domain.AuthEventType;
import com.cadence.domain.Candidate;
import com.cadence.domain.CandidateAuditOutcome;
import com.cadence.domain.CandidateEventType;
import com.cadence.domain.ErasureState;
import com.cadence.domain.Requisition;
import com.cadence.domain.RequisitionStatus;
import com.cadence.domain.ResourceType;
import com.cadence.repository.RequisitionRepository;
import com.mongodb.client.result.UpdateResult;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * F51 minimal requisition management (FR-008..FR-011) + the candidate->requisition link (FR-009). Linking is
 * privilege-conferring (it grants/removes assigned Hiring-Manager visibility to a candidate's PII), so every
 * change is attributable: requisition create/update/assign/unassign -> {@code authAuditLog}; the candidate link
 * -> the candidate {@code auditLog} ({@code REQUISITION_LINKED}). The link write is active-state-guarded so it
 * cannot resurrect an erased candidate.
 */
@Service
public class RequisitionService {

    private final Clock clock;
    private final RequisitionRepository requisitions;
    private final MongoTemplate mongo;
    private final AssignmentService assignments;
    private final AuthAuditService authAudit;
    private final CandidateAuditService candidateAudit;

    public RequisitionService(Clock clock, RequisitionRepository requisitions, MongoTemplate mongo,
                              AssignmentService assignments, AuthAuditService authAudit,
                              CandidateAuditService candidateAudit) {
        this.clock = clock;
        this.requisitions = requisitions;
        this.mongo = mongo;
        this.assignments = assignments;
        this.authAudit = authAudit;
        this.candidateAudit = candidateAudit;
    }

    public RequisitionDto create(String workspaceId, String actorMemberId, String title, String externalLabel) {
        if (title == null || title.isBlank()) {
            throw new PipelineExceptions.InvalidRequestException();
        }
        Requisition r = new Requisition();
        r.setWorkspaceId(workspaceId);
        r.setTitle(title.trim());
        r.setStatus(RequisitionStatus.OPEN);
        r.setExternalLabel(externalLabel == null || externalLabel.isBlank() ? null : externalLabel.trim());
        r.setCreatedAt(Instant.now(clock));
        r.setCreatedByMemberId(actorMemberId);
        r = requisitions.save(r);
        authAudit.record(AuthEventType.REQUISITION_CREATED, workspaceId, actorMemberId, r.getId(), null);
        return toDto(r);
    }

    public List<RequisitionDto> list(String workspaceId, String statusRaw) {
        List<Requisition> rows;
        if (statusRaw == null || statusRaw.isBlank() || "ALL".equalsIgnoreCase(statusRaw)) {
            rows = requisitions.findByWorkspaceId(workspaceId);
        } else {
            rows = requisitions.findByWorkspaceIdAndStatus(workspaceId, parseStatus(statusRaw));
        }
        return rows.stream().map(RequisitionService::toDto).toList();
    }

    public RequisitionDto update(String workspaceId, String actorMemberId, String id, String title, String statusRaw) {
        Requisition r = requisitions.findByWorkspaceIdAndId(workspaceId, id)
            .orElseThrow(RbacExceptions.ScopedNotFoundException::new);
        if (title != null) {
            if (title.isBlank()) {
                throw new PipelineExceptions.InvalidRequestException();
            }
            r.setTitle(title.trim());
        }
        if (statusRaw != null && !statusRaw.isBlank()) {
            r.setStatus(parseStatus(statusRaw));
        }
        r = requisitions.save(r);
        authAudit.record(AuthEventType.REQUISITION_UPDATED, workspaceId, actorMemberId, r.getId(), null);
        return toDto(r);
    }

    /** Assign a Hiring Manager to a requisition (reuses the F02 assignment model). Idempotent on a duplicate. */
    public void assignHm(String workspaceId, String actorMemberId, String requisitionId, String memberId) {
        if (memberId == null || memberId.isBlank()) {
            throw new PipelineExceptions.InvalidRequestException();
        }
        requisitions.findByWorkspaceIdAndId(workspaceId, requisitionId)
            .orElseThrow(RbacExceptions.ScopedNotFoundException::new);
        try {
            assignments.create(workspaceId, actorMemberId, memberId, ResourceType.REQUISITION, requisitionId);
        } catch (RbacExceptions.DuplicateAssignmentException e) {
            // Already assigned -> idempotent success (no duplicate, no 500).
        }
        authAudit.record(AuthEventType.REQUISITION_HM_ASSIGNED, workspaceId, actorMemberId, requisitionId, null);
    }

    public void unassignHm(String workspaceId, String actorMemberId, String requisitionId, String assignmentId) {
        Assignment a = assignments.getOrNotFound(workspaceId, assignmentId);
        if (a.getResourceType() != ResourceType.REQUISITION || !requisitionId.equals(a.getResourceId())) {
            throw new RbacExceptions.ScopedNotFoundException();
        }
        assignments.delete(workspaceId, a.getMemberId(), assignmentId);
        authAudit.record(AuthEventType.REQUISITION_HM_UNASSIGNED, workspaceId, actorMemberId, requisitionId, null);
    }

    /**
     * Set or clear ({@code requisitionId == null}) a candidate's requisition link. Active-state-guarded
     * ({@code updateFirst} on erasureState==ACTIVE) so a link race with erasure no-ops (no resurrection); a
     * non-matching candidate (missing/erased) -> indistinguishable 404. Audited on every change (set/move/clear)
     * because it grants/removes Hiring-Manager visibility (FR-009).
     */
    public void linkCandidate(String workspaceId, String actorMemberId, String candidateId, String requisitionId) {
        if (requisitionId != null && !requisitionId.isBlank()) {
            requisitions.findByWorkspaceIdAndId(workspaceId, requisitionId)
                .orElseThrow(RbacExceptions.ScopedNotFoundException::new);
        }
        Query q = Query.query(Criteria.where("_id").is(candidateId)
            .and("workspaceId").is(workspaceId)
            .and("erasureState").is(ErasureState.ACTIVE));
        Update u = (requisitionId == null || requisitionId.isBlank())
            ? new Update().unset("requisitionId")
            : new Update().set("requisitionId", requisitionId);
        UpdateResult res = mongo.updateFirst(q, u, Candidate.class);
        if (res.getMatchedCount() == 0) {
            throw new RbacExceptions.ScopedNotFoundException();
        }
        candidateAudit.append(workspaceId, candidateId, CandidateEventType.REQUISITION_LINKED,
            CandidateAuditOutcome.RECORDED, actorMemberId);
    }

    private RequisitionStatus parseStatus(String raw) {
        try {
            return RequisitionStatus.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new PipelineExceptions.InvalidRequestException();
        }
    }

    private static RequisitionDto toDto(Requisition r) {
        return new RequisitionDto(r.getId(), r.getTitle(), r.getStatus().name(), r.getExternalLabel(),
            r.getCreatedAt());
    }
}
