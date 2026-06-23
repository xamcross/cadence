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
 * F70 no-oracle envelope handler, scoped to the public + internal interest controllers (the
 * {@link FeedbackExceptionHandler} precedent).
 *
 * <p><b>Load-bearing (the F31 lesson):</b> it MUST catch {@code ScopedNotFoundException} itself. The GLOBAL
 * {@code RbacExceptionHandler} maps it to {@code {"error":"not_found","message":"Not found."}} (WITH a message) —
 * byte-divergent from this handler-local {@code {"error":"not_found"}}, which would leak a cross-workspace
 * existence oracle. {@code @Order(HIGHEST_PRECEDENCE)} so this type-scoped advice wins. The bean-validation
 * failure ({@code MethodArgumentNotValidException}) maps to a value-free 400 {@code invalid_request} regardless of
 * which field failed (never echoes other stored data).
 *
 * <p>The catch-all 500 hardening MUST re-throw {@code AccessDeniedException}/{@code AuthenticationException} so an
 * {@code @PreAuthorize} denial stays a 403, not a swallowed 500 (the F31 fix).
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = {PublicInterestController.class, InterestRequestController.class})
public class InterestExceptionHandler {

    private static ResponseEntity<Map<String, Object>> envelope(HttpStatus status, String error) {
        Map<String, Object> b = new HashMap<>();
        b.put("error", error);
        return ResponseEntity.status(status).body(b);
    }

    /** Field validation failure (bean validation) -> value-free 400. */
    @ExceptionHandler(org.springframework.web.bind.MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> beanValidation(
            org.springframework.web.bind.MethodArgumentNotValidException e) {
        return envelope(HttpStatus.BAD_REQUEST, "invalid_request");
    }

    /** Domain-level invalid request (e.g. a missing role on invite) -> value-free 400. */
    @ExceptionHandler(InterestExceptions.InvalidRequestException.class)
    public ResponseEntity<Map<String, Object>> invalid(InterestExceptions.InvalidRequestException e) {
        return envelope(HttpStatus.BAD_REQUEST, "invalid_request");
    }

    /** A malformed/unreadable body -> value-free 400 (same envelope). */
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> unreadable(
            org.springframework.http.converter.HttpMessageNotReadableException e) {
        return envelope(HttpStatus.BAD_REQUEST, "invalid_request");
    }

    /** Per-source or per-workspace flood ceiling exceeded -> 429. */
    @ExceptionHandler(InterestExceptions.RateLimitedException.class)
    public ResponseEntity<Map<String, Object>> rateLimited(InterestExceptions.RateLimitedException e) {
        return envelope(HttpStatus.TOO_MANY_REQUESTS, "rate_limited");
    }

    /** A transition on an already-terminal / wrong-state request -> 409 (FR-016). */
    @ExceptionHandler(InterestExceptions.ConflictException.class)
    public ResponseEntity<Map<String, Object>> conflict(InterestExceptions.ConflictException e) {
        return envelope(HttpStatus.CONFLICT, "conflict");
    }

    /** Cross-workspace / unknown id -> byte-identical 404 (no existence oracle). */
    @ExceptionHandler(RbacExceptions.ScopedNotFoundException.class)
    public ResponseEntity<Map<String, Object>> scopedNotFound(RbacExceptions.ScopedNotFoundException e) {
        return envelope(HttpStatus.NOT_FOUND, "not_found");
    }

    /**
     * Catch-all: an unexpected runtime failure must render a VALUE-FREE 500, never the container {@code /error}
     * body. Security exceptions are RE-THROWN so {@code @PreAuthorize} 403s reach the chain's RestAccessDeniedHandler
     * (the F31 fix).
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
