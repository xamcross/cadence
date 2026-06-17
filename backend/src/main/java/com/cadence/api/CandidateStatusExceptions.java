package com.cadence.api;

/**
 * F30 Candidate Status Page domain exceptions, mapped to a value-free envelope by
 * {@link CandidateStatusExceptionHandler} (the no-oracle piece). The rate-limit (429) and scoped-404
 * reuse the existing {@link SchedulingExceptions.RateLimitedException} and
 * {@link RbacExceptions.ScopedNotFoundException} types.
 */
public final class CandidateStatusExceptions {

    private CandidateStatusExceptions() {}

    /**
     * The status token does not resolve to an active candidate — unknown / malformed / erased, all the
     * SAME exception so the candidate view response cannot be an existence oracle (FR-031/SC-007) → 404.
     */
    public static class StatusNotFoundException extends RuntimeException {}

    /**
     * A recruiter publish that violates the shape rules (IN_PROGRESS without stage/next-step/expected-date,
     * or terminal without a non-blank message) → 400 {@code invalid_status}. Message is VALUE-FREE
     * (field + rule, never the submitted text — the F12 precedent).
     */
    public static class InvalidStatusPublishException extends RuntimeException {
        public InvalidStatusPublishException(String message) { super(message); }
    }
}
