package com.cadence.api;

/** F22 email-delivery domain exceptions, mapped to the {error,...} envelope by the handler. */
public final class EmailDeliveryExceptions {

    private EmailDeliveryExceptions() {}

    /**
     * The consent gate refused the dispatch at claim time (FR-006/FR-007) -> 409 {@code not_contactable}.
     * {@code reason} is the value-free reason code (e.g. ERASED/WITHDRAWN/UNDELIVERABLE) — recruiter-scoped,
     * never an external oracle and never the provider's free-text.
     */
    public static class NotContactableException extends RuntimeException {
        private final transient String reason;
        public NotContactableException(String reason) { this.reason = reason; }
        public String getReason() { return reason; }
    }

    /** Bad/missing messageType, unknown stageKey shape, or a null body -> 400 {@code invalid_request}. */
    public static class InvalidRequestException extends RuntimeException {
        public InvalidRequestException(String message) { super(message); }
    }
}
