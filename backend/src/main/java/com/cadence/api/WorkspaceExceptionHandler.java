package com.cadence.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;

import java.util.HashMap;
import java.util.Map;

/**
 * Maps F03 workspace-configuration exceptions to the shared {error,message} envelope. As in F02,
 * AccessDeniedException from @PreAuthorize is intentionally NOT handled here — it propagates to the
 * filter chain's RestAccessDeniedHandler so non-Admin refusals stay a uniform 403.
 *
 * Validation failures return a per-field map but NEVER echo the bound request DTO (so the
 * email-provider credential cannot leak via an error body — research D2/SEC-NIT-1).
 */
@RestControllerAdvice(assignableTypes = {WorkspaceConfigController.class, PublicBrandingController.class})
public class WorkspaceExceptionHandler {

    private static ResponseEntity<Map<String, Object>> body(HttpStatus status, String error, String message) {
        Map<String, Object> b = new HashMap<>();
        b.put("error", error);
        b.put("message", message);
        return ResponseEntity.status(status).body(b);
    }

    @ExceptionHandler(WorkspaceExceptions.AlreadyConfiguredException.class)
    public ResponseEntity<Map<String, Object>> alreadyConfigured(WorkspaceExceptions.AlreadyConfiguredException e) {
        return body(HttpStatus.CONFLICT, "already_configured", "This workspace has already been set up.");
    }

    @ExceptionHandler(WorkspaceExceptions.NotConfiguredException.class)
    public ResponseEntity<Map<String, Object>> notConfigured(WorkspaceExceptions.NotConfiguredException e) {
        return body(HttpStatus.CONFLICT, "not_configured", "This workspace has not been set up yet.");
    }

    @ExceptionHandler(WorkspaceExceptions.RetentionNotAcknowledgedException.class)
    public ResponseEntity<Map<String, Object>> retentionNotAcknowledged(
            WorkspaceExceptions.RetentionNotAcknowledgedException e) {
        return body(HttpStatus.BAD_REQUEST, "retention_not_acknowledged",
            "The data-retention period must be acknowledged to complete setup.");
    }

    @ExceptionHandler(WorkspaceExceptions.InvalidLogoException.class)
    public ResponseEntity<Map<String, Object>> invalidLogo(WorkspaceExceptions.InvalidLogoException e) {
        return body(HttpStatus.BAD_REQUEST, "invalid_logo", e.getMessage());
    }

    /** A logo larger than the multipart cap is refused with the same clean 400 as in-service size
     *  validation, not a generic 500 (FR-012; review BE-1/SEC-2). */
    @ExceptionHandler({MaxUploadSizeExceededException.class, MultipartException.class})
    public ResponseEntity<Map<String, Object>> tooLarge(Exception e) {
        return body(HttpStatus.BAD_REQUEST, "invalid_logo", "The logo must be 1 MB or smaller.");
    }

    @ExceptionHandler(WorkspaceExceptions.ValidationException.class)
    public ResponseEntity<Map<String, Object>> validation(WorkspaceExceptions.ValidationException e) {
        Map<String, Object> b = new HashMap<>();
        b.put("error", "validation_failed");
        b.put("message", "One or more settings are invalid.");
        b.put("fields", e.getFields());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(b);
    }
}
