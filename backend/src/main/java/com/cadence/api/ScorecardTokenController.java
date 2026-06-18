package com.cadence.api;

import com.cadence.api.FeedbackDtos.ScorecardFormView;
import com.cadence.api.FeedbackDtos.ScorecardSubmission;
import com.cadence.api.FeedbackDtos.SubmitResponse;
import com.cadence.service.FeedbackService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * F32 public no-login scorecard endpoints (contract A/B) — on the {@code @Order(2)} permitAll/STATELESS chain.
 * The token is the only credential (write-only; the recruiter read is the sole content path). Rate-limited per
 * hashed IP (in {@link FeedbackService}). Returns a 200 state-envelope (FORM/USED/EXPIRED/SUBMITTED) so no
 * status code distinguishes used/invalidated/unknown from expired (no state oracle, FR-030). {@code no-store}.
 */
@RestController
public class ScorecardTokenController {

    private final FeedbackService service;

    public ScorecardTokenController(FeedbackService service) {
        this.service = service;
    }

    @GetMapping("/api/feedback/{token}")
    public ResponseEntity<ScorecardFormView> load(@PathVariable String token, HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(service.loadForm(token, request.getRemoteAddr()));
    }

    @PostMapping("/api/feedback/{token}")
    public ResponseEntity<SubmitResponse> submit(@PathVariable String token,
                                                 @RequestBody(required = false) ScorecardSubmission body,
                                                 HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(service.submit(token, body, request.getRemoteAddr()));
    }
}
