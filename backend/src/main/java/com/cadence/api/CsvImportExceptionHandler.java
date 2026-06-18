package com.cadence.api;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;

import java.util.HashMap;
import java.util.Map;

/**
 * F42 no-oracle envelope handler, scoped to {@link CsvImportController} only (the F40 {@code AtsExceptionHandler}
 * precedent). {@code @Order(HIGHEST_PRECEDENCE)} so this type-scoped advice wins over the global unordered
 * {@code RbacExceptionHandler} (keeps the {@code ScopedNotFoundException} 404 byte-identical — no "message"
 * divergence). Every envelope is value-free. The catch-all RE-THROWS security exceptions so an
 * {@code @PreAuthorize} denial still renders as 403 via the filter chain (not a 500).
 *
 * <p>An over-cap multipart upload raises {@link MaxUploadSizeExceededException}/{@link MultipartException} —
 * mapped here to a clean 400 (the F03 lesson — the raised container caps sit above the in-service gate, D9).
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = CsvImportController.class)
public class CsvImportExceptionHandler {

    private static ResponseEntity<Map<String, Object>> envelope(HttpStatus status, String error) {
        Map<String, Object> b = new HashMap<>();
        b.put("error", error);
        return ResponseEntity.status(status).body(b);
    }

    @ExceptionHandler(RbacExceptions.ScopedNotFoundException.class)
    public ResponseEntity<Map<String, Object>> notFound(RbacExceptions.ScopedNotFoundException e) {
        return envelope(HttpStatus.NOT_FOUND, "not_found");
    }

    @ExceptionHandler(CsvImportExceptions.InvalidStateException.class)
    public ResponseEntity<Map<String, Object>> invalidState(CsvImportExceptions.InvalidStateException e) {
        return envelope(HttpStatus.CONFLICT, "invalid_state");
    }

    @ExceptionHandler(CsvImportExceptions.RateLimitedException.class)
    public ResponseEntity<Map<String, Object>> rateLimited(CsvImportExceptions.RateLimitedException e) {
        return envelope(HttpStatus.TOO_MANY_REQUESTS, "rate_limited");
    }

    @ExceptionHandler({CsvImportExceptions.InvalidImportException.class,
        MaxUploadSizeExceededException.class, MultipartException.class, IllegalArgumentException.class})
    public ResponseEntity<Map<String, Object>> invalid(Exception e) {
        return envelope(HttpStatus.BAD_REQUEST, "invalid_import");
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, Object>> serverError(RuntimeException e) {
        if (e instanceof org.springframework.security.access.AccessDeniedException
            || e instanceof org.springframework.security.core.AuthenticationException) {
            throw e;
        }
        return envelope(HttpStatus.INTERNAL_SERVER_ERROR, "server_error");
    }
}
