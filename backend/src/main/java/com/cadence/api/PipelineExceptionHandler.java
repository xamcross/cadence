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
 * F51 no-oracle envelope handler (the F31/F50 precedent). Scoped to {@link PipelineController} and
 * {@link RequisitionController} and {@code @Order(HIGHEST_PRECEDENCE)} so it WINS over the global (unordered)
 * {@code RbacExceptionHandler} for {@code ScopedNotFoundException} on these controllers (the global maps it WITH a
 * {@code message} -- byte-divergent, an existence oracle). Every envelope is value-free.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = {PipelineController.class, RequisitionController.class})
public class PipelineExceptionHandler {

    private static ResponseEntity<Map<String, Object>> envelope(HttpStatus status, String error) {
        Map<String, Object> b = new HashMap<>();
        b.put("error", error);
        return ResponseEntity.status(status).body(b);
    }

    @ExceptionHandler(RbacExceptions.ScopedNotFoundException.class)
    public ResponseEntity<Map<String, Object>> scopedNotFound(RbacExceptions.ScopedNotFoundException e) {
        return envelope(HttpStatus.NOT_FOUND, "not_found");
    }

    @ExceptionHandler(PipelineExceptions.InvalidRequestException.class)
    public ResponseEntity<Map<String, Object>> invalid(PipelineExceptions.InvalidRequestException e) {
        return envelope(HttpStatus.BAD_REQUEST, "invalid_request");
    }

    @ExceptionHandler(PipelineExceptions.SelectionTooLargeException.class)
    public ResponseEntity<Map<String, Object>> tooLarge(PipelineExceptions.SelectionTooLargeException e) {
        return envelope(HttpStatus.BAD_REQUEST, "selection_too_large");
    }

    /**
     * A malformed/unparseable JSON body -> 400 (not the catch-all 500). The F51 controllers take request bodies
     * (bulk/patch/assign/link), and this advice is HIGHEST_PRECEDENCE, so without an explicit mapping the
     * {@code HttpMessageNotReadableException} (a RuntimeException) would fall to the catch-all 500 (the contract
     * specifies 400 invalid_request for bad input).
     */
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> unreadable(
            org.springframework.http.converter.HttpMessageNotReadableException e) {
        return envelope(HttpStatus.BAD_REQUEST, "invalid_request");
    }

    /**
     * Catch-all value-free 500. <b>Security exceptions are RE-THROWN</b> -- an {@code @PreAuthorize} denial raises an
     * {@code AccessDeniedException} (a RuntimeException) that MUST propagate to the filter chain's 403 handler, not be
     * swallowed as a 500 (the F31 lesson).
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
