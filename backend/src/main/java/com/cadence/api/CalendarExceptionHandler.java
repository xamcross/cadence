package com.cadence.api;

import com.cadence.integration.UnsupportedProviderException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;

/**
 * Maps F01.1 calendar exceptions to the shared {error,message} envelope (reusing the F01/F02 shape).
 * As elsewhere, AccessDeniedException from @PreAuthorize is NOT handled here — it propagates to the
 * filter chain's RestAccessDeniedHandler so refusals stay a uniform 403. Error bodies never echo a
 * token or account value.
 */
@RestControllerAdvice(assignableTypes = CalendarConnectionController.class)
public class CalendarExceptionHandler {

    @ExceptionHandler(UnsupportedProviderException.class)
    public ResponseEntity<Map<String, Object>> unsupportedProvider(UnsupportedProviderException e) {
        Map<String, Object> body = new HashMap<>();
        body.put("error", "unsupported_provider");
        body.put("message", "The requested calendar provider is not supported.");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }
}
