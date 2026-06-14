package com.cadence.api;

/** Domain exceptions for the auth API, mapped to HTTP by {@link AuthExceptionHandler}. */
public final class AuthExceptions {

    private AuthExceptions() {}

    /** Used/expired/unknown invitation or reset link -> 410 Gone (uniform, enumeration-safe). */
    public static class InvalidLinkException extends RuntimeException {
        public InvalidLinkException() { super("link_invalid"); }
    }

    /** Password fails policy -> 400 Bad Request. */
    public static class WeakPasswordException extends RuntimeException {
        public WeakPasswordException() { super("weak_password"); }
    }

    /** Email already an active member -> 409 Conflict. */
    public static class AlreadyMemberException extends RuntimeException {
        public AlreadyMemberException() { super("already_member"); }
    }

    /** Too many attempts -> 429 Too Many Requests. */
    public static class RateLimitedException extends RuntimeException {
        public RateLimitedException() { super("rate_limited"); }
    }
}
