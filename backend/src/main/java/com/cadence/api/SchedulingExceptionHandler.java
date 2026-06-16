package com.cadence.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps F13 scheduling exceptions to the shared {error,message} envelope, scoped to the two F13 controllers
 * (the F22 precedent). {@code @PreAuthorize} AccessDeniedException is NOT handled here (propagates to the
 * chain's RestAccessDeniedHandler -> uniform 403). ScopedNotFoundException is handled globally -> 404 (no
 * existence oracle). Every envelope is value-free — never a token value, candidate name, or location.
 */
@RestControllerAdvice(assignableTypes = {SchedulingController.class, CandidateSchedulingController.class})
public class SchedulingExceptionHandler {

    private static ResponseEntity<Map<String, Object>> envelope(HttpStatus status, String error, String message) {
        Map<String, Object> b = new HashMap<>();
        b.put("error", error);
        if (message != null) b.put("message", message);
        return ResponseEntity.status(status).body(b);
    }

    @ExceptionHandler(SchedulingExceptions.InvalidRequestException.class)
    public ResponseEntity<Map<String, Object>> invalid(SchedulingExceptions.InvalidRequestException e) {
        return envelope(HttpStatus.BAD_REQUEST, "invalid_request", e.getMessage());
    }

    @ExceptionHandler(SchedulingExceptions.NoSlotsException.class)
    public ResponseEntity<Map<String, Object>> noSlots(SchedulingExceptions.NoSlotsException e) {
        return envelope(HttpStatus.UNPROCESSABLE_ENTITY, "no_slots",
            "No available slots in the searched window. Widen the window or adjust the template.");
    }

    @ExceptionHandler(SchedulingExceptions.NotContactableException.class)
    public ResponseEntity<Map<String, Object>> notContactable(SchedulingExceptions.NotContactableException e) {
        return envelope(HttpStatus.CONFLICT, "not_contactable", "This candidate cannot currently be contacted.");
    }

    @ExceptionHandler(SchedulingExceptions.UnschedulableRequiredException.class)
    public ResponseEntity<Map<String, Object>> unschedulable(SchedulingExceptions.UnschedulableRequiredException e) {
        Map<String, Object> b = new HashMap<>();
        b.put("error", "unschedulable_required_member");
        b.put("message", "A required interviewer's calendar is not connected.");
        b.put("memberIds", List.copyOf(e.memberIds())); // internal ids only — not PII
        return ResponseEntity.status(HttpStatus.CONFLICT).body(b);
    }

    @ExceptionHandler(SchedulingExceptions.TokenInvalidException.class)
    public ResponseEntity<Map<String, Object>> invalidToken(SchedulingExceptions.TokenInvalidException e) {
        return envelope(HttpStatus.BAD_REQUEST, "invalid", "This scheduling link is not valid.");
    }

    @ExceptionHandler(SchedulingExceptions.TokenExpiredException.class)
    public ResponseEntity<Map<String, Object>> expired(SchedulingExceptions.TokenExpiredException e) {
        return envelope(HttpStatus.GONE, "expired", "This link has expired — contact your recruiter.");
    }

    @ExceptionHandler(SchedulingExceptions.SlotNotFoundException.class)
    public ResponseEntity<Map<String, Object>> slotNotFound(SchedulingExceptions.SlotNotFoundException e) {
        return envelope(HttpStatus.BAD_REQUEST, "invalid", "That time is not one of the offered slots.");
    }

    @ExceptionHandler(SchedulingExceptions.SlotTakenException.class)
    public ResponseEntity<Map<String, Object>> slotTaken(SchedulingExceptions.SlotTakenException e) {
        return envelope(HttpStatus.CONFLICT, "slot_taken", "That time was just taken — please pick another.");
    }

    @ExceptionHandler(SchedulingExceptions.StaleSlotException.class)
    public ResponseEntity<Map<String, Object>> stale(SchedulingExceptions.StaleSlotException e) {
        return envelope(HttpStatus.CONFLICT, "slot_no_longer_available",
            "That time is no longer available — please pick another.");
    }

    @ExceptionHandler(SchedulingExceptions.BookingFailedException.class)
    public ResponseEntity<Map<String, Object>> bookingFailed(SchedulingExceptions.BookingFailedException e) {
        return envelope(HttpStatus.CONFLICT, "booking_failed",
            "We could not complete the booking — please try again or pick another time.");
    }

    @ExceptionHandler(SchedulingExceptions.CleanupIncompleteException.class)
    public ResponseEntity<Map<String, Object>> cleanupIncomplete(SchedulingExceptions.CleanupIncompleteException e) {
        return envelope(HttpStatus.CONFLICT, "cleanup_incomplete",
            "We hit a problem completing this booking — your recruiter will follow up.");
    }

    @ExceptionHandler(SchedulingExceptions.NotAvailableException.class)
    public ResponseEntity<Map<String, Object>> notAvailable(SchedulingExceptions.NotAvailableException e) {
        // Byte-identical across every deny reason — not a GDPR-status oracle.
        return envelope(HttpStatus.CONFLICT, "not_available", "This link is no longer available.");
    }

    @ExceptionHandler(SchedulingExceptions.RateLimitedException.class)
    public ResponseEntity<Map<String, Object>> rateLimited(SchedulingExceptions.RateLimitedException e) {
        return envelope(HttpStatus.TOO_MANY_REQUESTS, "rate_limited", "Too many requests — please slow down.");
    }
}
