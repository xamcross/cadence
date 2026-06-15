package com.cadence.integration;

/**
 * A Google Calendar API call failed (F10, research D8). {@code transient} = retryable (429 / 5xx /
 * network / a 403 rate-limit reason); a non-transient instance is fatal (e.g. a 400, or a 409 the caller
 * treats as idempotent success). Carries the HTTP status and the Google {@code errors[].reason} for the
 * caller — NEVER a token or event-content payload, and never the raw provider body (FR-017b).
 */
public class CalendarApiException extends RuntimeException {

    private final boolean isTransient;
    private final Integer httpStatus;
    private final String providerReason;

    public CalendarApiException(boolean isTransient, Integer httpStatus, String providerReason) {
        super("calendar API call failed (status=" + httpStatus + ", reason=" + providerReason
            + ", transient=" + isTransient + ")");
        this.isTransient = isTransient;
        this.httpStatus = httpStatus;
        this.providerReason = providerReason;
    }

    public boolean isTransient() { return isTransient; }
    public Integer getHttpStatus() { return httpStatus; }
    public String getProviderReason() { return providerReason; }
}
