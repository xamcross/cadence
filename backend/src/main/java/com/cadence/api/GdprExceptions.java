package com.cadence.api;

/** F04 GDPR request exceptions mapped to the shared {error,message} envelope by GdprExceptionHandler. */
public final class GdprExceptions {

    private GdprExceptions() {}

    /** An unknown/absent lawful-basis enum value. */
    public static class InvalidBasisException extends RuntimeException {}

    /** An unknown/absent erasure reason-code enum value. */
    public static class InvalidReasonException extends RuntimeException {}

    /** Confirm/reject attempted on a request that is no longer PENDING. */
    public static class RequestAlreadyResolvedException extends RuntimeException {}
}
