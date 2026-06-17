package com.cadence.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;

/**
 * F30 no-oracle envelope handler (research D6, contract cross-cutting). The existing
 * {@code SchedulingExceptionHandler} is {@code @RestControllerAdvice(assignableTypes={...scheduling})} and is
 * NOT inherited by the F30 controllers — without this advice, {@code StatusNotFoundException}/
 * {@code InvalidStatusPublishException} would fall through to the default {@code BasicErrorController}
 * {@code /error} body (timestamp/path varies by case → an existence oracle, or a 500). This is the
 * load-bearing piece that delivers SC-007/SC-010.
 *
 * <p>Scoped to the two F30 controllers. Every envelope is VALUE-FREE — never a token value, candidate
 * name, or the recruiter free text. The candidate erasure-submit 202 ack is returned directly by the
 * controller (it is the SAME 202 across {valid, unknown, malformed, erased} — no exception distinguishes
 * the cases), so it is not handled here.
 */
@RestControllerAdvice(assignableTypes = {CandidateStatusController.class, CandidateStatusAdminController.class})
public class CandidateStatusExceptionHandler {

    private static ResponseEntity<Map<String, Object>> envelope(HttpStatus status, String error, String message) {
        Map<String, Object> b = new HashMap<>();
        b.put("error", error);
        if (message != null) b.put("message", message);
        return ResponseEntity.status(status).body(b);
    }

    /** Unknown / malformed / erased status token — byte-identical 404 (no existence oracle, FR-031/SC-007). */
    @ExceptionHandler(CandidateStatusExceptions.StatusNotFoundException.class)
    public ResponseEntity<Map<String, Object>> notFound(CandidateStatusExceptions.StatusNotFoundException e) {
        return envelope(HttpStatus.NOT_FOUND, "not_found", null);
    }

    /** Recruiter scoped read/write to a foreign/erased candidate — indistinguishable 404 (FR-014). */
    @ExceptionHandler(RbacExceptions.ScopedNotFoundException.class)
    public ResponseEntity<Map<String, Object>> scopedNotFound(RbacExceptions.ScopedNotFoundException e) {
        return envelope(HttpStatus.NOT_FOUND, "not_found", null);
    }

    /** Dateless/contentless publish (FR-011/FR-012/SC-004) — value-free message. */
    @ExceptionHandler(CandidateStatusExceptions.InvalidStatusPublishException.class)
    public ResponseEntity<Map<String, Object>> invalid(CandidateStatusExceptions.InvalidStatusPublishException e) {
        return envelope(HttpStatus.BAD_REQUEST, "invalid_status", e.getMessage());
    }

    /** Per-IP rate limit exceeded (FR-030/SC-009) → 429. Reuses the F13 rate-limit exception. */
    @ExceptionHandler(SchedulingExceptions.RateLimitedException.class)
    public ResponseEntity<Map<String, Object>> rateLimited(SchedulingExceptions.RateLimitedException e) {
        return envelope(HttpStatus.TOO_MANY_REQUESTS, "rate_limited", "Too many requests — please slow down.");
    }
}
