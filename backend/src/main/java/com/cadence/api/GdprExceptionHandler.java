package com.cadence.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;

/**
 * Maps F04 GDPR exceptions to the shared {error,message} envelope. As in F02/F03, AccessDeniedException
 * from @PreAuthorize is intentionally NOT handled here — it propagates to the filter chain's
 * RestAccessDeniedHandler so non-authorized refusals stay a uniform 403. Error bodies NEVER echo a
 * bound request DTO or any candidate field (no PII in error responses, FR-023).
 */
@RestControllerAdvice(assignableTypes = {
    CandidateGdprController.class, ErasureRequestController.class, RetentionController.class})
public class GdprExceptionHandler {

    private static ResponseEntity<Map<String, Object>> body(HttpStatus status, String error, String message) {
        Map<String, Object> b = new HashMap<>();
        b.put("error", error);
        b.put("message", message);
        return ResponseEntity.status(status).body(b);
    }

    @ExceptionHandler(GdprExceptions.InvalidBasisException.class)
    public ResponseEntity<Map<String, Object>> invalidBasis(GdprExceptions.InvalidBasisException e) {
        return body(HttpStatus.BAD_REQUEST, "invalid_basis", "An unknown lawful basis was supplied.");
    }

    @ExceptionHandler(GdprExceptions.InvalidReasonException.class)
    public ResponseEntity<Map<String, Object>> invalidReason(GdprExceptions.InvalidReasonException e) {
        return body(HttpStatus.BAD_REQUEST, "invalid_reason", "An unknown or missing reason code was supplied.");
    }

    @ExceptionHandler(GdprExceptions.RequestAlreadyResolvedException.class)
    public ResponseEntity<Map<String, Object>> alreadyResolved(GdprExceptions.RequestAlreadyResolvedException e) {
        return body(HttpStatus.CONFLICT, "already_resolved", "This erasure request has already been resolved.");
    }
}
