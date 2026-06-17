package com.cadence.api;

import com.cadence.api.CandidateStatusDtos.PublishStatusRequest;
import com.cadence.api.CandidateStatusDtos.RecruiterStatusResponse;
import com.cadence.api.CandidateStatusDtos.RotateLinkResponse;
import com.cadence.service.CandidateStatusService;
import com.cadence.service.SessionService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * F30 recruiter status maintenance — {@code /api/internal/candidates/{candidateId}/status}. Class-level
 * {@code @PreAuthorize} is the single role source of truth (ADMIN|RECRUITER only — HM is NOT granted status
 * write, FR-010; satisfies RbacEndpointInventoryTest). The candidate is workspace-scoped from the principal
 * (foreign/missing/erased → ScopedNotFoundException → 404, oracle-free, mapped by
 * {@link CandidateStatusExceptionHandler}). Responses carry the decrypted status + the current status link;
 * no-store.
 */
@RestController
@RequestMapping("/api/internal/candidates/{candidateId}/status")
@PreAuthorize("hasAnyRole('ADMIN','RECRUITER')")
public class CandidateStatusAdminController {

    private final CandidateStatusService service;

    public CandidateStatusAdminController(CandidateStatusService service) {
        this.service = service;
    }

    @PutMapping
    public ResponseEntity<RecruiterStatusResponse> publish(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable String candidateId,
            @RequestBody(required = false) PublishStatusRequest req) {
        RecruiterStatusResponse r = service.publish(
            principal.workspaceId(), candidateId, principal.memberId(), req);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(r);
    }

    @GetMapping
    public ResponseEntity<RecruiterStatusResponse> read(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable String candidateId) {
        RecruiterStatusResponse r = service.readForRecruiter(principal.workspaceId(), candidateId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(r);
    }

    @PostMapping("/rotate-link")
    public ResponseEntity<RotateLinkResponse> rotate(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable String candidateId) {
        String link = service.rotateLink(principal.workspaceId(), candidateId, principal.memberId());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new RotateLinkResponse(link));
    }
}
