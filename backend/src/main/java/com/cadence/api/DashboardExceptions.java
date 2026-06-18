package com.cadence.api;

/**
 * F50 Core Dashboard exception holder. A DEDICATED {@code InvalidRequestException} (NOT the same-named
 * {@code AtsExceptions}/{@code SchedulingExceptions}/{@code EmailDeliveryExceptions} variants — they are all
 * package {@code com.cadence.api}, so reusing one would force an FQN and bind the wrong type to this feature's
 * {@code @RestControllerAdvice}). Thrown by {@link DashboardWindow#parse} for an unrecognised window so the
 * window query param can never raise a {@code MethodArgumentTypeMismatchException} -> the catch-all 500 (the
 * F41 lesson); {@link DashboardExceptionHandler} maps it to a value-free 400.
 */
public final class DashboardExceptions {

    private DashboardExceptions() {}

    /** A malformed request argument (an unrecognised {@code window}) -> value-free 400 invalid_request. */
    public static class InvalidRequestException extends RuntimeException {
        public InvalidRequestException() {
            super("invalid_request");
        }
    }
}
