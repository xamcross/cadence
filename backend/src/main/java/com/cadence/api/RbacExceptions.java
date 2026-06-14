package com.cadence.api;

/** F02 RBAC domain exceptions, mapped to the {error,message} envelope by RbacExceptionHandler. */
public final class RbacExceptions {

    private RbacExceptions() {}

    /** Last active Administrator may not be removed/downgraded/deactivated (FR-005) → 409. */
    public static class LastAdminException extends RuntimeException {}

    /** A member tried to raise their own privilege (FR-006) → 403. */
    public static class SelfElevationException extends RuntimeException {}

    /**
     * Resource absent OR outside the caller's assignment — indistinguishable not-found (FR-025) → 404.
     * The SAME exception is thrown for both cases so the response cannot be an existence oracle.
     */
    public static class ScopedNotFoundException extends RuntimeException {}

    /** A scoped member attempted to read/write a resource outside their assignment (FR-032) → 404. */
    public static class NotAssignedException extends RuntimeException {}

    /** Duplicate (resourceType,resourceId,member) assignment → 409. */
    public static class DuplicateAssignmentException extends RuntimeException {}
}
