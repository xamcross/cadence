package com.cadence.api;

import com.cadence.domain.Assignment;
import com.cadence.domain.Role;
import com.cadence.service.AssignmentService;
import com.cadence.service.SessionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Assignment management + the demonstrable server-side scoping surface (F02 US4, contracts).
 * Hiring Manager / Interviewer see and fetch ONLY their own assignments (scoped server-side);
 * Admin/Recruiter may view across the workspace. Read-only is excluded entirely.
 */
@RestController
public class AssignmentController {

    private final AssignmentService assignments;

    public AssignmentController(AssignmentService assignments) {
        this.assignments = assignments;
    }

    @GetMapping("/api/internal/assignments")
    @PreAuthorize("hasAnyRole('ADMIN','RECRUITER','HIRING_MANAGER','INTERVIEWER')")
    public ResponseEntity<List<RbacDtos.AssignmentView>> list(
            @AuthenticationPrincipal SessionService.Principal principal,
            @RequestParam(value = "memberId", required = false) String memberId) {
        String ws = principal.workspaceId();
        List<Assignment> result = isUnscoped(principal.role())
            ? assignments.listForWorkspace(ws, memberId)   // ?memberId always AND-ed with workspace
            : assignments.listForMember(ws, principal.memberId()); // HM/I: own only (FR-024/FR-026)
        return ResponseEntity.ok(result.stream().map(AssignmentController::view).toList());
    }

    @GetMapping("/api/internal/assignments/{assignmentId}")
    @PreAuthorize("hasAnyRole('ADMIN','RECRUITER','HIRING_MANAGER','INTERVIEWER')")
    public ResponseEntity<RbacDtos.AssignmentView> get(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable String assignmentId) {
        String ws = principal.workspaceId();
        Assignment a = isUnscoped(principal.role())
            ? assignments.getOrNotFound(ws, assignmentId)
            : assignments.getScopedOrNotFound(ws, principal.memberId(), assignmentId); // indistinguishable 404
        return ResponseEntity.ok(view(a));
    }

    @PostMapping("/api/internal/members/{memberId}/assignments")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, String>> create(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable String memberId,
            @Valid @RequestBody RbacDtos.AssignmentCreateRequest req) {
        Assignment a = assignments.create(
            principal.workspaceId(), principal.memberId(), memberId, req.resourceType(), req.resourceId());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("assignmentId", a.getId()));
    }

    @DeleteMapping("/api/internal/members/{memberId}/assignments/{assignmentId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable String memberId,
            @PathVariable String assignmentId) {
        assignments.delete(principal.workspaceId(), memberId, assignmentId);
        return ResponseEntity.noContent().build();
    }

    private static boolean isUnscoped(Role role) {
        return role == Role.ADMIN || role == Role.RECRUITER;
    }

    private static RbacDtos.AssignmentView view(Assignment a) {
        return new RbacDtos.AssignmentView(a.getId(), a.getResourceType(), a.getResourceId(), a.getMemberId());
    }
}
