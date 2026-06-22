package com.cadence.api;

import com.cadence.api.PipelineDtos.AssignRequest;
import com.cadence.api.PipelineDtos.CreateRequisitionRequest;
import com.cadence.api.PipelineDtos.LinkRequest;
import com.cadence.api.PipelineDtos.RequisitionDto;
import com.cadence.api.PipelineDtos.UpdateRequisitionRequest;
import com.cadence.service.RequisitionService;
import com.cadence.service.SessionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * F51 minimal requisition management + the candidate->requisition link (contracts/pipeline-api.md §4). Create /
 * update / assign / unassign are {@code ADMIN}-only (method-level); list is {@code ADMIN/RECRUITER/READ_ONLY}
 * (class-level); the candidate link is {@code ADMIN/RECRUITER}. Interviewer + Hiring Manager are denied by
 * deny-by-default. Errors flow through {@link PipelineExceptionHandler} (no-oracle 404). Covered by
 * {@code RbacEndpointInventoryTest}.
 */
@RestController
@PreAuthorize("hasAnyRole('ADMIN','RECRUITER','READ_ONLY')")
public class RequisitionController {

    private final RequisitionService service;

    public RequisitionController(RequisitionService service) {
        this.service = service;
    }

    @PostMapping("/api/internal/requisitions")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<RequisitionDto> create(
            @AuthenticationPrincipal SessionService.Principal principal,
            @RequestBody CreateRequisitionRequest body) {
        RequisitionDto dto = service.create(principal.workspaceId(), principal.memberId(),
            body == null ? null : body.title(), body == null ? null : body.externalLabel());
        return ResponseEntity.status(HttpStatus.CREATED).body(dto);
    }

    @GetMapping("/api/internal/requisitions")
    public ResponseEntity<List<RequisitionDto>> list(
            @AuthenticationPrincipal SessionService.Principal principal,
            @RequestParam(name = "status", required = false) String status) {
        return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore())
            .body(service.list(principal.workspaceId(), status));
    }

    @PatchMapping("/api/internal/requisitions/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<RequisitionDto> update(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable String id,
            @RequestBody UpdateRequisitionRequest body) {
        RequisitionDto dto = service.update(principal.workspaceId(), principal.memberId(), id,
            body == null ? null : body.title(), body == null ? null : body.status());
        return ResponseEntity.ok(dto);
    }

    @PostMapping("/api/internal/requisitions/{id}/assignments")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> assign(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable String id,
            @RequestBody AssignRequest body) {
        service.assignHm(principal.workspaceId(), principal.memberId(), id, body == null ? null : body.memberId());
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @DeleteMapping("/api/internal/requisitions/{id}/assignments/{assignmentId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> unassign(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable String id,
            @PathVariable String assignmentId) {
        service.unassignHm(principal.workspaceId(), principal.memberId(), id, assignmentId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/api/internal/candidates/{candidateId}/requisition")
    @PreAuthorize("hasAnyRole('ADMIN','RECRUITER')")
    public ResponseEntity<Void> link(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable String candidateId,
            @RequestBody LinkRequest body) {
        service.linkCandidate(principal.workspaceId(), principal.memberId(), candidateId,
            body == null ? null : body.requisitionId());
        return ResponseEntity.noContent().build();
    }
}
