package com.cadence.api;

import com.cadence.api.SlaNudgeDtos.ActionResponse;
import com.cadence.api.SlaNudgeDtos.CandidateSla;
import com.cadence.api.SlaNudgeDtos.DraftPreviewResponse;
import com.cadence.api.SlaNudgeDtos.SilenceListResponse;
import com.cadence.service.SessionService;
import com.cadence.service.SlaNudgeService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * F31 SLA Nudge recruiter surface — internal, {@code hasAnyRole('ADMIN','RECRUITER')} (HM/Interviewer/Read-only
 * refused; satisfies RbacEndpointInventoryTest via the class-level {@code @PreAuthorize}). Workspace-scoped from
 * the principal; a foreign/unknown candidate or draft id -> ScopedNotFoundException -> indistinguishable 404 via
 * {@link SlaNudgeExceptionHandler} (no existence oracle, SC-016). The silence-list and preview are {@code no-store}.
 *
 * <p>The SLA silence WINDOW is set via the existing F03 workspace-settings endpoint (US1) — F31 adds no endpoint
 * for it. This controller is read + the draft actions only. The scan drafts; only {@code approve} sends (FR-010).
 */
@RestController
@PreAuthorize("hasAnyRole('ADMIN','RECRUITER')")
public class SlaNudgeController {

    private final SlaNudgeService service;

    public SlaNudgeController(SlaNudgeService service) {
        this.service = service;
    }

    @GetMapping("/api/internal/sla/silence-list")
    public ResponseEntity<SilenceListResponse> silenceList(
            @AuthenticationPrincipal SessionService.Principal principal) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new SilenceListResponse(service.silenceList(principal.workspaceId())));
    }

    @GetMapping("/api/internal/candidates/{candidateId}/sla")
    public ResponseEntity<CandidateSla> candidateSla(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable String candidateId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(service.candidateSla(principal.workspaceId(), candidateId));
    }

    @GetMapping("/api/internal/candidates/{candidateId}/sla/draft/preview")
    public ResponseEntity<DraftPreviewResponse> preview(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable String candidateId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(service.previewDraft(principal.workspaceId(), candidateId));
    }

    @PostMapping("/api/internal/sla/drafts/{draftId}/approve")
    public ResponseEntity<ActionResponse> approve(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable String draftId) {
        return ResponseEntity.ok(service.approve(principal.workspaceId(), draftId, principal.memberId()));
    }

    @PostMapping("/api/internal/sla/drafts/{draftId}/dismiss")
    public ResponseEntity<ActionResponse> dismiss(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable String draftId) {
        return ResponseEntity.ok(service.dismiss(principal.workspaceId(), draftId, principal.memberId()));
    }
}
