package com.cadence.integration;

import java.time.Duration;

/**
 * A calendar API call failed (F10/F11, research D8/D7). {@code transient} = retryable (429 / 5xx /
 * network / a Google 403 rate-limit reason); a non-transient instance is fatal (e.g. a 400, or a 409 the
 * caller treats as idempotent success). Carries the HTTP status and the provider reason/code for the
 * caller — NEVER a token or event-content payload, and never the raw provider body (FR-017b/FR-023).
 *
 * <p>{@code retryAfter} (F11 D7) is set when the provider returned a {@code Retry-After} header (Graph
 * {@code 429}/{@code 503}); {@link CalendarApiRetry} waits {@code max(backoff+jitter, retryAfter)}. Null
 * for the common case (Google rarely sends it).
 */
public class CalendarApiException extends RuntimeException {

    private final boolean isTransient;
    private final Integer httpStatus;
    private final String providerReason;
    private final Duration retryAfter;

    public CalendarApiException(boolean isTransient, Integer httpStatus, String providerReason) {
        this(isTransient, httpStatus, providerReason, null);
    }

    public CalendarApiException(boolean isTransient, Integer httpStatus, String providerReason, Duration retryAfter) {
        super("calendar API call failed (status=" + httpStatus + ", reason=" + providerReason
            + ", transient=" + isTransient + ")");
        this.isTransient = isTransient;
        this.httpStatus = httpStatus;
        this.providerReason = providerReason;
        this.retryAfter = retryAfter;
    }

    public boolean isTransient() { return isTransient; }
    public Integer getHttpStatus() { return httpStatus; }
    public String getProviderReason() { return providerReason; }
    public Duration getRetryAfter() { return retryAfter; }
}
