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
 * F50 no-oracle envelope handler (the F31 precedent). Scoped to {@link DashboardController} only and
 * {@code @Order(HIGHEST_PRECEDENCE)} so it WINS over the global (unordered) {@code RbacExceptionHandler} for
 * {@code ScopedNotFoundException} on this controller (the global maps it WITH a {@code message} -- byte-divergent,
 * an existence oracle). Every envelope is value-free.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = DashboardController.class)
public class DashboardExceptionHandler {

    private static ResponseEntity<Map<String, Object>> envelope(HttpStatus status, String error) {
        Map<String, Object> b = new HashMap<>();
        b.put("error", error);
        return ResponseEntity.status(status).body(b);
    }

    /** Unrecognised {@code window} -> value-free 400 (the F41 parse-as-String lesson). */
    @ExceptionHandler(DashboardExceptions.InvalidRequestException.class)
    public ResponseEntity<Map<String, Object>> invalid(DashboardExceptions.InvalidRequestException e) {
        return envelope(HttpStatus.BAD_REQUEST, "invalid_request");
    }

    /**
     * Precautionary -- the dashboard read has no single-record lookup that 404s, but mapping it here (the F31
     * pattern) keeps a future scoped read byte-identical and oracle-free.
     */
    @ExceptionHandler(RbacExceptions.ScopedNotFoundException.class)
    public ResponseEntity<Map<String, Object>> scopedNotFound(RbacExceptions.ScopedNotFoundException e) {
        return envelope(HttpStatus.NOT_FOUND, "not_found");
    }

    /**
     * Catch-all value-free 500. <b>Security exceptions are RE-THROWN</b> -- an {@code @PreAuthorize} denial
     * raises an {@code AccessDeniedException} (a RuntimeException) that MUST propagate to the filter chain's 403
     * handler, not be swallowed as a 500 (the F31 lesson).
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
