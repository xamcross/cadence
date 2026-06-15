package com.cadence.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;

/**
 * Maps F12 interview-template exceptions to the shared {error,message} envelope (scoped to the
 * controller, like the F03 handler). AccessDeniedException from {@code @PreAuthorize} is intentionally
 * NOT handled here — it propagates to the filter chain's RestAccessDeniedHandler so a wrong-role refusal
 * stays a uniform 403. ScopedNotFoundException (foreign/missing template) is handled by the global
 * RbacExceptionHandler -> 404 "not_found" (no existence oracle). Validation never echoes the bound DTO
 * (value-free field map only — D10).
 */
@RestControllerAdvice(assignableTypes = InterviewTemplateController.class)
public class InterviewTemplateExceptionHandler {

    private static ResponseEntity<Map<String, Object>> body(HttpStatus status, String error, String message) {
        Map<String, Object> b = new HashMap<>();
        b.put("error", error);
        b.put("message", message);
        return ResponseEntity.status(status).body(b);
    }

    @ExceptionHandler(InterviewTemplateExceptions.InvalidTemplateException.class)
    public ResponseEntity<Map<String, Object>> invalid(InterviewTemplateExceptions.InvalidTemplateException e) {
        Map<String, Object> b = new HashMap<>();
        b.put("error", "invalid_template");
        b.put("message", "One or more template fields are invalid.");
        b.put("fields", e.getFields());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(b);
    }

    @ExceptionHandler(InterviewTemplateExceptions.TemplateRetiredException.class)
    public ResponseEntity<Map<String, Object>> retired(InterviewTemplateExceptions.TemplateRetiredException e) {
        return body(HttpStatus.CONFLICT, "template_retired", "This template is retired and cannot be used for new scheduling.");
    }

    @ExceptionHandler(InterviewTemplateExceptions.WorkspaceNotConfiguredException.class)
    public ResponseEntity<Map<String, Object>> notConfigured(InterviewTemplateExceptions.WorkspaceNotConfiguredException e) {
        return body(HttpStatus.CONFLICT, "workspace_not_configured",
            "The workspace has no working hours or time zone set, and this template provides no override.");
    }
}
