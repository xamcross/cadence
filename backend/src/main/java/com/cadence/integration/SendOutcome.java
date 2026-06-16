package com.cadence.integration;

/**
 * The result of a transport send (F22, contract D). {@code transientError} drives the retry/terminal
 * classification (research D5): a transient failure re-queues with backoff until the cap, a permanent
 * failure goes straight to FAILED + dead-letter. {@code reasonCode} is a value-free code (NEVER the
 * provider's free-text). The {@code transient} keyword is reserved in Java, hence {@code transientError}.
 */
public record SendOutcome(boolean accepted, String providerMessageRef, boolean transientError, String reasonCode) {

    public static SendOutcome accepted(String providerMessageRef) {
        return new SendOutcome(true, providerMessageRef, false, null);
    }

    public static SendOutcome transientFailure(String reasonCode) {
        return new SendOutcome(false, null, true, reasonCode);
    }

    public static SendOutcome permanentFailure(String reasonCode) {
        return new SendOutcome(false, null, false, reasonCode);
    }
}
