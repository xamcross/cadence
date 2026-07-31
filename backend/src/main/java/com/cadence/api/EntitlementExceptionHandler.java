package com.cadence.api;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 032 -- GLOBAL advice for exactly one exception: the 402 upgrade_required envelope (FR-013).
 * Global (no assignableTypes) because gated services throw it from many controllers (ATS today,
 * more later); it handles nothing else, so per-feature advices are unaffected.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class EntitlementExceptionHandler {

    @ExceptionHandler(BillingExceptions.UpgradeRequiredException.class)
    public ResponseEntity<Map<String, String>> upgradeRequired(BillingExceptions.UpgradeRequiredException e) {
        return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED)
            .body(Map.of("error", "upgrade_required"));
    }
}
