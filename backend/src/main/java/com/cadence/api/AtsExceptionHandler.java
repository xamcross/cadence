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
 * F40 no-oracle envelope handler, scoped to {@link AtsConnectionController} only (the F31
 * {@code SlaNudgeExceptionHandler} precedent). {@code @Order(HIGHEST_PRECEDENCE)} so this type-scoped advice
 * wins over the global unordered {@code RbacExceptionHandler}. Every envelope is value-free and never echoes
 * the credential or a provider body (FR-003). The catch-all RE-THROWS security exceptions so an
 * {@code @PreAuthorize} denial still renders as a 403 via the filter chain (not a 500).
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = AtsConnectionController.class)
public class AtsExceptionHandler {

    private static ResponseEntity<Map<String, Object>> envelope(HttpStatus status, String error) {
        Map<String, Object> b = new HashMap<>();
        b.put("error", error);
        return ResponseEntity.status(status).body(b);
    }

    @ExceptionHandler(AtsExceptions.InvalidRequestException.class)
    public ResponseEntity<Map<String, Object>> invalid(AtsExceptions.InvalidRequestException e) {
        return envelope(HttpStatus.BAD_REQUEST, "invalid_request");
    }

    @ExceptionHandler(AtsExceptions.VerificationFailedException.class)
    public ResponseEntity<Map<String, Object>> verificationFailed(AtsExceptions.VerificationFailedException e) {
        return envelope(HttpStatus.CONFLICT, "verification_failed");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> illegal(IllegalArgumentException e) {
        return envelope(HttpStatus.BAD_REQUEST, "invalid_request");
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
