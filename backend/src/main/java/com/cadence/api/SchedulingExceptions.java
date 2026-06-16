package com.cadence.api;

import java.util.List;

/** F13 scheduling domain exceptions, mapped to the {error,message} envelope by SchedulingExceptionHandler. */
public final class SchedulingExceptions {

    private SchedulingExceptions() {}

    /** Malformed request body (missing templateId/slotId) → 400. */
    public static class InvalidRequestException extends RuntimeException {
        public InvalidRequestException(String message) { super(message); }
    }

    /** No rule-compliant slots in the searched window (FR-003) → 422. */
    public static class NoSlotsException extends RuntimeException {}

    /** Candidate not contactable at initiation (FR-004) → 409. */
    public static class NotContactableException extends RuntimeException {}

    /** A required participant's calendar is unavailable (FR-005) → 409; names the member ids. */
    public static class UnschedulableRequiredException extends RuntimeException {
        private final List<String> memberIds;
        public UnschedulableRequiredException(List<String> memberIds) { this.memberIds = memberIds; }
        public List<String> memberIds() { return memberIds; }
    }

    /** The scheduling token does not exist / is used / superseded — indistinguishable (FR-008) → 400. */
    public static class TokenInvalidException extends RuntimeException {}

    /** The scheduling token existed and has expired (FR-008) → 410. */
    public static class TokenExpiredException extends RuntimeException {}

    /** The chosen slot id is not in the request's snapshot → 400. */
    public static class SlotNotFoundException extends RuntimeException {}

    /** Another booking won this interviewer-time (FR-012) → 409. */
    public static class SlotTakenException extends RuntimeException {}

    /** The chosen slot is no longer compliant/free at confirm (FR-013) → 409. */
    public static class StaleSlotException extends RuntimeException {}

    /** Provider create failed and was rolled back; the candidate should retry (FR-015) → 409. */
    public static class BookingFailedException extends RuntimeException {}

    /** Booking partially failed and rollback could not complete (FR-016) → 409. */
    public static class CleanupIncompleteException extends RuntimeException {}

    /** Candidate became not-contactable/erased since send (FR-014) → 409; byte-identical across reasons. */
    public static class NotAvailableException extends RuntimeException {}

    /** Per-IP rate limit exceeded (FR-010) → 429. */
    public static class RateLimitedException extends RuntimeException {}

    // --- F20 Reschedule & Cancellation ---

    /** Self-service reschedule cap reached; the link is invalidated and the recruiter notified (FR-005) → 409. */
    public static class CapReachedException extends RuntimeException {}

    /** A reschedule found zero compliant alternatives; the original booking is retained (FR-007) → 422. */
    public static class RescheduleNoSlotsException extends RuntimeException {}

    /** The interview is within the self-service lead time before start (FR-004) → 409. (Past-interview → 410.) */
    public static class IneligibleException extends RuntimeException {}

    /** The candidate has no live BOOKED interview to reschedule/cancel → 409. */
    public static class NoActiveBookingException extends RuntimeException {}
}
