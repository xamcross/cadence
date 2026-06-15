package com.cadence.api;

import com.cadence.domain.ErasureReasonCode;
import com.cadence.domain.ErasureRequest;
import com.cadence.service.ErasureRequestService;
import com.cadence.service.SessionService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Admin review/decision surface for candidate-initiated erasure requests (F04, US4). Admin only
 * (class-level). The candidate-facing submission is F30 (it calls ErasureRequestService.requestErasure).
 */
@RestController
@RequestMapping("/api/internal/erasure-requests")
@PreAuthorize("hasRole('ADMIN')")
public class ErasureRequestController {

    private final ErasureRequestService service;

    public ErasureRequestController(ErasureRequestService service) {
        this.service = service;
    }

    /** Lists the PENDING erasure-request queue (the only status surfaced in F04). */
    @GetMapping
    public ResponseEntity<GdprDtos.RequestsResponse> list(
            @AuthenticationPrincipal SessionService.Principal principal) {
        List<GdprDtos.ErasureRequestResponse> reqs = service.listPending(principal.workspaceId()).stream()
            .map(ErasureRequestController::view).toList();
        return ResponseEntity.ok(new GdprDtos.RequestsResponse(reqs));
    }

    @PostMapping("/{id}/confirm")
    public ResponseEntity<GdprDtos.StatusResponse> confirm(
            @AuthenticationPrincipal SessionService.Principal principal, @PathVariable String id) {
        if (!service.confirm(principal.workspaceId(), id, principal.memberId())) {
            throw new GdprExceptions.RequestAlreadyResolvedException();
        }
        return ResponseEntity.ok(new GdprDtos.StatusResponse("RESOLVED_CONFIRMED"));
    }

    @PostMapping("/{id}/reject")
    public ResponseEntity<GdprDtos.StatusResponse> reject(
            @AuthenticationPrincipal SessionService.Principal principal, @PathVariable String id,
            @RequestBody GdprDtos.RejectRequest req) {
        ErasureReasonCode reason = parseReason(req.reasonCode()); // validate BEFORE the transition
        if (!service.reject(principal.workspaceId(), id, reason, principal.memberId())) {
            throw new GdprExceptions.RequestAlreadyResolvedException();
        }
        return ResponseEntity.ok(new GdprDtos.StatusResponse("RESOLVED_REJECTED"));
    }

    private static GdprDtos.ErasureRequestResponse view(ErasureRequest r) {
        return new GdprDtos.ErasureRequestResponse(
            r.getId(), r.getCandidateId(), r.getStatus().name(),
            r.getReasonCode() == null ? null : r.getReasonCode().name(), r.getCreatedAt());
    }

    private static ErasureReasonCode parseReason(String value) {
        if (value == null) {
            throw new GdprExceptions.InvalidReasonException();
        }
        try {
            return ErasureReasonCode.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new GdprExceptions.InvalidReasonException();
        }
    }
}
