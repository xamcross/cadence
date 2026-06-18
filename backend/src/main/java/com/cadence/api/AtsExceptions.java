package com.cadence.api;

/**
 * F40 ATS controller exceptions, mapped to a no-oracle envelope by {@link AtsExceptionHandler}.
 */
public final class AtsExceptions {

    private AtsExceptions() {}

    /** The submitted request was malformed (e.g. a blank API key) -> 400 invalid_request. */
    public static class InvalidRequestException extends RuntimeException {
        public InvalidRequestException() { super("invalid_request"); }
    }

    /** Credential verification against the provider failed (bad/revoked key) -> 409 verification_failed. */
    public static class VerificationFailedException extends RuntimeException {
        public VerificationFailedException() { super("verification_failed"); }
    }
}
