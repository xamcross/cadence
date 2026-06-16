package com.cadence.api;

import com.cadence.api.SchedulingDtos.InitiateRequest;
import com.cadence.api.SchedulingDtos.InitiateResponse;
import com.cadence.api.SchedulingDtos.StatusResponse;
import com.cadence.service.SchedulingService;
import com.cadence.service.SessionService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * F13 recruiter initiation + status (contract A) — {@code /api/internal/candidates/{id}/scheduling}.
 * Class-level {@code @PreAuthorize} is the single role source of truth (satisfies RbacEndpointInventoryTest).
 * Candidate/template are workspace-scoped (foreign/missing -> ScopedNotFoundException -> 404, oracle-free).
 * Responses carry ids/instants only — never the raw token or location; no-store.
 */
@RestController
@RequestMapping("/api/internal/candidates/{candidateId}/scheduling")
@PreAuthorize("hasAnyRole('ADMIN','RECRUITER')")
public class SchedulingController {

    private final SchedulingService service;

    public SchedulingController(SchedulingService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<InitiateResponse> initiate(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable String candidateId,
            @RequestBody(required = false) InitiateRequest req,
            HttpServletRequest http) {
        if (req == null || req.templateId() == null || req.templateId().isBlank()) {
            throw new SchedulingExceptions.InvalidRequestException("templateId is required.");
        }
        SchedulingService.InitiateResult result = service.initiate(
            principal.workspaceId(), principal.memberId(), candidateId,
            req.templateId(), req.locationText(), req.rangeStart(), req.rangeEnd(), http.getRemoteAddr());
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
            .body(InitiateResponse.from(result));
    }

    @GetMapping
    public ResponseEntity<StatusResponse> status(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable String candidateId) {
        SchedulingService.StatusView view = service.status(principal.workspaceId(), candidateId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(StatusResponse.from(view));
    }
}
