package com.cadence.api;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;

/**
 * F32 no-oracle envelope handler. Scoped to the two feedback controllers only.
 *
 * <p><b>Load-bearing (the F31 lesson):</b> it MUST catch {@code ScopedNotFoundException} itself. The GLOBAL
 * {@code RbacExceptionHandler} maps it to {@code {"error":"not_found","message":"Not found."}} (WITH a message)
 * — byte-divergent from a handler-local {@code {"error":"not_found"}}, which would leak an existence oracle on a
 * cross-workspace interview id (SC-011). {@code @Order(HIGHEST_PRECEDENCE)} so this type-scoped advice wins.
 *
 * <p>The catch-all 500 hardening MUST re-throw security exceptions so an {@code @PreAuthorize} denial stays a
 * 403 (the F31 fix), not a swallowed 500.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = {ScorecardTokenController.class, InterviewFeedbackController.class})
public class FeedbackExceptionHandler {

    private static ResponseEntity<Map<String, Object>> envelope(HttpStatus status, String error) {
        Map<String, Object> b = new HashMap<>();
        b.put("error", error);
        return ResponseEntity.status(status).body(b);
    }

    /** Cross-workspace / unknown interview id -> byte-identical 404 (no existence oracle, SC-011). */
    @ExceptionHandler(RbacExceptions.ScopedNotFoundException.class)
    public ResponseEntity<Map<String, Object>> scopedNotFound(RbacExceptions.ScopedNotFoundException e) {
        return envelope(HttpStatus.NOT_FOUND, "not_found");
    }

    /** An invalid scorecard submission -> value-free 400 (nothing persisted). */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> invalid(IllegalArgumentException e) {
        return envelope(HttpStatus.BAD_REQUEST, "invalid_scorecard");
    }

    /** Per-IP rate limit exceeded on the public endpoints -> 429. */
    @ExceptionHandler(SchedulingExceptions.RateLimitedException.class)
    public ResponseEntity<Map<String, Object>> rateLimited(SchedulingExceptions.RateLimitedException e) {
        return envelope(HttpStatus.TOO_MANY_REQUESTS, "rate_limited");
    }

    /**
     * Catch-all: an unexpected runtime failure (e.g. a payload (de)serialize error) must render a VALUE-FREE 500,
     * never the container {@code /error} body. Security exceptions are RE-THROWN so {@code @PreAuthorize} 403s
     * reach the filter chain's RestAccessDeniedHandler (the F31 fix).
     */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, Object>> serverError(RuntimeException e) {
        if (e instanceof org.springframework.security.access.AccessDeniedException
            || e instanceof org.springframework.security.core.AuthenticationException) {
            throw e;
        }
        return envelope(HttpStatus.INTERNAL_SERVER_ERROR, "server_error");
    }
}
