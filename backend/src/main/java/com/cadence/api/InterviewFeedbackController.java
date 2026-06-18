package com.cadence.api;

import com.cadence.api.FeedbackDtos.InterviewFeedbackView;
import com.cadence.api.FeedbackDtos.PendingListResponse;
import com.cadence.service.FeedbackService;
import com.cadence.service.SessionService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * F32 recruiter feedback-read surface (contract C/D) — internal, {@code hasAnyRole('ADMIN','RECRUITER')}
 * (HM/Interviewer/Read-only refused; HM scoped read deferred to F51 — no candidate->requisition link). Workspace
 * -scoped from the principal; a foreign/unknown interview id -> ScopedNotFoundException -> indistinguishable 404
 * via {@link FeedbackExceptionHandler} (no existence oracle, SC-011). {@code no-store} (PII read; never logged).
 */
@RestController
@PreAuthorize("hasAnyRole('ADMIN','RECRUITER')")
public class InterviewFeedbackController {

    private final FeedbackService service;

    public InterviewFeedbackController(FeedbackService service) {
        this.service = service;
    }

    @GetMapping("/api/internal/interviews/{schedulingRequestId}/feedback")
    public ResponseEntity<InterviewFeedbackView> interviewFeedback(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable String schedulingRequestId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(service.interviewFeedback(principal.workspaceId(), schedulingRequestId));
    }

    @GetMapping("/api/internal/feedback/pending")
    public ResponseEntity<PendingListResponse> pending(
            @AuthenticationPrincipal SessionService.Principal principal) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new PendingListResponse(service.pendingList(principal.workspaceId())));
    }
}
