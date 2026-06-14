package com.cadence.api;

import com.cadence.domain.Role;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * Maps F02 RBAC exceptions to the shared {error,message} envelope — never leaking a resource id,
 * content, or existence signal (FR-014/FR-025). NOTE: AccessDeniedException from method security
 * (@PreAuthorize) is intentionally NOT handled here — it propagates to the filter chain's
 * RestAccessDeniedHandler (research D5) so it cannot be confused with a not-found/last-admin case.
 */
@RestControllerAdvice
public class RbacExceptionHandler {

    private static ResponseEntity<Map<String, String>> body(HttpStatus status, String error, String message) {
        return ResponseEntity.status(status).body(Map.of("error", error, "message", message));
    }

    @ExceptionHandler(RbacExceptions.LastAdminException.class)
    public ResponseEntity<Map<String, String>> lastAdmin(RbacExceptions.LastAdminException e) {
        return body(HttpStatus.CONFLICT, "last_admin",
            "This is the last administrator and cannot be removed or downgraded.");
    }

    @ExceptionHandler(RbacExceptions.SelfElevationException.class)
    public ResponseEntity<Map<String, String>> selfElevation(RbacExceptions.SelfElevationException e) {
        return body(HttpStatus.FORBIDDEN, "forbidden", "You do not have access to this action.");
    }

    @ExceptionHandler({RbacExceptions.ScopedNotFoundException.class, RbacExceptions.NotAssignedException.class})
    public ResponseEntity<Map<String, String>> notFound(RuntimeException e) {
        // Identical for "missing" and "exists-but-not-yours" — no existence signal (FR-025/SC-015).
        return body(HttpStatus.NOT_FOUND, "not_found", "Not found.");
    }

    @ExceptionHandler(RbacExceptions.DuplicateAssignmentException.class)
    public ResponseEntity<Map<String, String>> duplicateAssignment(RbacExceptions.DuplicateAssignmentException e) {
        return body(HttpStatus.CONFLICT, "duplicate_assignment", "That resource is already assigned to this member.");
    }

    /**
     * A non-canonical Role enum value → 400 invalid_role, no persisted change (FR-031/SC-014);
     * any other unparseable body → a generic 400 bad_request (so a malformed assignment body or bad
     * JSON is not mislabelled as a role problem).
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> unreadable(HttpMessageNotReadableException e) {
        if (e.getMostSpecificCause() instanceof InvalidFormatException ife
            && ife.getTargetType() == Role.class) {
            return body(HttpStatus.BAD_REQUEST, "invalid_role", "Invalid role.");
        }
        return body(HttpStatus.BAD_REQUEST, "bad_request", "Invalid request.");
    }
}
