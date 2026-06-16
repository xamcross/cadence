package com.cadence.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;

/**
 * Maps F21 email-template exceptions to the shared {error,message} envelope (scoped to the controller,
 * like the F03/F12 handlers). AccessDeniedException from {@code @PreAuthorize} is intentionally NOT
 * handled here — it propagates to the filter chain's RestAccessDeniedHandler so a wrong-role refusal
 * stays a uniform 403. ScopedNotFoundException (foreign/missing template, stage, or candidate) is handled
 * by the global RbacExceptionHandler -> 404 "not_found" (no existence oracle). Validation never echoes the
 * submitted subject/body/token (value-free field map only — D12).
 */
@RestControllerAdvice(assignableTypes = EmailTemplateController.class)
public class EmailTemplateExceptionHandler {

    private static ResponseEntity<Map<String, Object>> body(HttpStatus status, String error, String message) {
        Map<String, Object> b = new HashMap<>();
        b.put("error", error);
        b.put("message", message);
        return ResponseEntity.status(status).body(b);
    }

    @ExceptionHandler(EmailTemplateExceptions.InvalidTemplateException.class)
    public ResponseEntity<Map<String, Object>> invalid(EmailTemplateExceptions.InvalidTemplateException e) {
        Map<String, Object> b = new HashMap<>();
        b.put("error", "invalid_template");
        b.put("message", "One or more template fields are invalid.");
        b.put("fields", e.getFields());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(b);
    }

    @ExceptionHandler(EmailTemplateExceptions.TemplateLockedException.class)
    public ResponseEntity<Map<String, Object>> locked(EmailTemplateExceptions.TemplateLockedException e) {
        return body(HttpStatus.FORBIDDEN, "template_locked",
            "This template is locked by an administrator and cannot be edited.");
    }

    @ExceptionHandler(EmailTemplateExceptions.StaleTemplateException.class)
    public ResponseEntity<Map<String, Object>> stale(EmailTemplateExceptions.StaleTemplateException e) {
        return body(HttpStatus.CONFLICT, "stale_template",
            "This template was changed by someone else. Reload and try again.");
    }
}
