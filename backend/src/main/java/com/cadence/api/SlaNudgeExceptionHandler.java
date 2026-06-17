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
 * F31 no-oracle envelope handler (research D10). Scoped to {@link SlaNudgeController} only.
 *
 * <p><b>Load-bearing:</b> it MUST catch {@code ScopedNotFoundException} itself. The GLOBAL
 * {@code RbacExceptionHandler} maps {@code ScopedNotFoundException} to
 * {@code {"error":"not_found","message":"Not found."}} (WITH a message) -- byte-divergent from a handler-local
 * {@code {"error":"not_found"}}. Without this local override, a cross-workspace candidate (global handler)
 * would be distinguishable from an unknown draft id (this handler), defeating the no-existence-oracle
 * guarantee (SC-016). Every envelope is value-free.
 */
// HIGHEST_PRECEDENCE so this type-scoped advice WINS over the global (unordered) RbacExceptionHandler for
// ScopedNotFoundException on this controller -- otherwise the global's {"error":"not_found","message":"Not found."}
// (WITH a message) leaks an existence oracle (the byte-divergence the plan review flagged; SC-016).
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = SlaNudgeController.class)
public class SlaNudgeExceptionHandler {

    private static ResponseEntity<Map<String, Object>> envelope(HttpStatus status, String error) {
        Map<String, Object> b = new HashMap<>();
        b.put("error", error);
        return ResponseEntity.status(status).body(b);
    }

    /** Unknown / malformed / cross-workspace / erased candidate or draft -- byte-identical 404 (SC-016). */
    @ExceptionHandler(RbacExceptions.ScopedNotFoundException.class)
    public ResponseEntity<Map<String, Object>> scopedNotFound(RbacExceptions.ScopedNotFoundException e) {
        return envelope(HttpStatus.NOT_FOUND, "not_found");
    }

    /** A malformed request argument -- value-free 400. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> invalid(IllegalArgumentException e) {
        return envelope(HttpStatus.BAD_REQUEST, "invalid_request");
    }

    /**
     * Catch-all (Security review L1): an unexpected runtime failure on the PREVIEW path (a malformed template /
     * converter decrypt error from {@code renderForSend}) must NOT fall through to the container {@code /error}
     * handler, whose divergent/stack-bearing body could leak content. Render a VALUE-FREE 500. The more specific
     * 404/400 handlers above still win for their exception types.
     *
     * <p><b>Security exceptions are RE-THROWN</b> — an {@code @PreAuthorize} denial raises an
     * {@code AccessDeniedException} (a RuntimeException); it MUST propagate to the filter chain's
     * RestAccessDeniedHandler (403), not be swallowed here as a 500 (the RbacExceptionHandler note).
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
