package com.cadence.api;

import com.cadence.domain.Candidate;
import com.cadence.service.RetentionService;
import com.cadence.service.SessionService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Admin retention-enforcement surface (F04, US5). Lists scan-flagged candidates and performs the
 * Admin-confirmed deletion (guarded so only a flagged candidate is wiped). Admin only (class-level).
 * The flag/clear scan itself is the scheduled RetentionScanTask, not an HTTP endpoint.
 */
@RestController
@RequestMapping("/api/internal/retention")
@PreAuthorize("hasRole('ADMIN')")
public class RetentionController {

    private final RetentionService service;

    public RetentionController(RetentionService service) {
        this.service = service;
    }

    @GetMapping("/flagged")
    public ResponseEntity<GdprDtos.FlaggedListResponse> flagged(
            @AuthenticationPrincipal SessionService.Principal principal) {
        List<GdprDtos.FlaggedResponse> list = service.listFlagged(principal.workspaceId()).stream()
            .map(RetentionController::view).toList();
        return ResponseEntity.ok(new GdprDtos.FlaggedListResponse(list));
    }

    /** Admin-confirmed deletion. Identical 200 for flagged/not-flagged/unknown (no oracle). */
    @PostMapping("/{candidateId}/delete")
    public ResponseEntity<GdprDtos.StatusResponse> delete(
            @AuthenticationPrincipal SessionService.Principal principal, @PathVariable String candidateId) {
        service.confirmDelete(principal.workspaceId(), candidateId, principal.memberId());
        return ResponseEntity.ok(new GdprDtos.StatusResponse("erased"));
    }

    private static GdprDtos.FlaggedResponse view(Candidate c) {
        return new GdprDtos.FlaggedResponse(c.getId(), c.getRetentionFlaggedAt(), c.getLastContactAt());
    }
}
