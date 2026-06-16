package com.cadence.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;

/**
 * Maps F22 candidate-send exceptions to the shared {error,...} envelope (scoped to the controller, like the
 * F03/F12/F21 handlers). {@code @PreAuthorize} AccessDeniedException is NOT handled here — it propagates to
 * the chain's RestAccessDeniedHandler (uniform 403 {@code forbidden}). ScopedNotFoundException (foreign/missing
 * candidate) is handled by the global RbacExceptionHandler -> 404 {@code not_found} (no existence oracle).
 * Every envelope is value-free — never the recipient/subject/body/merge values.
 */
@RestControllerAdvice(assignableTypes = CandidateEmailController.class)
public class EmailDeliveryExceptionHandler {

    @ExceptionHandler(EmailDeliveryExceptions.InvalidRequestException.class)
    public ResponseEntity<Map<String, Object>> invalid(EmailDeliveryExceptions.InvalidRequestException e) {
        Map<String, Object> b = new HashMap<>();
        b.put("error", "invalid_request");
        b.put("message", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(b);
    }

    @ExceptionHandler(EmailDeliveryExceptions.NotContactableException.class)
    public ResponseEntity<Map<String, Object>> notContactable(EmailDeliveryExceptions.NotContactableException e) {
        Map<String, Object> b = new HashMap<>();
        b.put("error", "not_contactable");
        b.put("reason", e.getReason()); // value-free reason code (ERASED/WITHDRAWN/...); never provider free-text
        return ResponseEntity.status(HttpStatus.CONFLICT).body(b);
    }
}
