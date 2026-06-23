package com.cadence.api;

import com.cadence.api.InterestDtos.SubmitRequest;
import com.cadence.api.InterestDtos.SubmitResponse;
import com.cadence.service.InterestRateLimiter;
import com.cadence.service.InterestRequestService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * F70 public no-login interest submit (contracts/interest-api.md) — rides the {@code @Order(2)}
 * {@code /api/public/**} permitAll/STATELESS/CSRF-exempt chain. Returns a byte-identical {@code 202
 * {"status":"received"}} for EVERY valid submission (member / pending-invite / existing-open / unknown are
 * indistinguishable by construction — the service does no such lookup); 400 {@code invalid_request} on field
 * validation; 429 {@code rate_limited}; a tripped honeypot / bot heuristic also returns the exact 202 with no row
 * written (no oracle). The workspace is resolved server-side (FR-019); it is NEVER in the request.
 */
@RestController
public class PublicInterestController {

    private final InterestRequestService service;
    private final InterestRateLimiter rateLimiter;

    public PublicInterestController(InterestRequestService service, InterestRateLimiter rateLimiter) {
        this.service = service;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping("/api/public/interest")
    public ResponseEntity<SubmitResponse> submit(@Valid @RequestBody SubmitRequest req,
                                                 HttpServletRequest http) {
        service.submit(new InterestRequestService.SubmitCommand(
                req.name(), req.email(), req.organization(), req.message(),
                req.website(), req.formRenderedAtMillis()),
            rateLimiter.resolveClientIp(http));
        return ResponseEntity.status(HttpStatus.ACCEPTED).cacheControl(CacheControl.noStore())
            .body(new SubmitResponse("received"));
    }
}
