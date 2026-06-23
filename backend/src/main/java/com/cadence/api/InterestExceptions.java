package com.cadence.api;

/** F70 interest domain exceptions, mapped to a byte-identical envelope by {@link InterestExceptionHandler}. */
public final class InterestExceptions {

    private InterestExceptions() {}

    /** Field validation failure (format/length/required) -> 400 {@code invalid_request}. Value-free. */
    public static class InvalidRequestException extends RuntimeException {}

    /** Per-source cap or the per-workspace DB ceiling exceeded (R6) -> 429 {@code rate_limited}. */
    public static class RateLimitedException extends RuntimeException {}

    /** A transition attempted on an already-terminal / wrong-state request -> 409 {@code conflict} (FR-016). */
    public static class ConflictException extends RuntimeException {}
}
