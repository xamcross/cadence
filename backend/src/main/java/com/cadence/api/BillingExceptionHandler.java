package com.cadence.api;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;

/** 032 -- claim/checkout error envelopes, scoped to BillingController (the CsvImport advice shape). */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = BillingController.class)
public class BillingExceptionHandler {

    private static ResponseEntity<Map<String, Object>> envelope(HttpStatus status, String error) {
        Map<String, Object> b = new HashMap<>();
        b.put("error", error);
        return ResponseEntity.status(status).body(b);
    }

    @ExceptionHandler(BillingExceptions.ClaimRejectedException.class)
    public ResponseEntity<Map<String, Object>> claimRejected(BillingExceptions.ClaimRejectedException e) {
        return envelope(HttpStatus.CONFLICT, e.code());
    }

    @ExceptionHandler(BillingExceptions.ClaimUnavailableException.class)
    public ResponseEntity<Map<String, Object>> claimUnavailable(BillingExceptions.ClaimUnavailableException e) {
        return envelope(HttpStatus.SERVICE_UNAVAILABLE, "billing_unavailable");
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
