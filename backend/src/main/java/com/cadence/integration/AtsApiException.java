package com.cadence.integration;

import java.time.Duration;

/**
 * An ATS provider API call failed (F40, mirrors {@link CalendarApiException}). {@code transient} =
 * retryable (429 / 5xx / network / a Retry-After throttle); {@code needsReauth} = the stored credential is
 * rejected (401/403) so the connection must flip to NEEDS_REAUTH and NOT be retried; otherwise the failure
 * is fatal. Carries the HTTP status and a value-free {@code category} for the caller — NEVER the credential
 * and NEVER the raw provider response body (FR-003/FR-022).
 *
 * <p>{@code retryAfter} is set when the provider returned a {@code Retry-After} header; {@link AtsApiRetry}
 * waits {@code max(backoff+jitter, retryAfter)}.
 */
public class AtsApiException extends RuntimeException {

    private final boolean isTransient;
    private final boolean needsReauth;
    private final Integer httpStatus;
    private final String category;
    private final Duration retryAfter;

    public AtsApiException(boolean isTransient, boolean needsReauth, Integer httpStatus, String category) {
        this(isTransient, needsReauth, httpStatus, category, null);
    }

    public AtsApiException(boolean isTransient, boolean needsReauth, Integer httpStatus, String category,
                           Duration retryAfter) {
        super("ATS API call failed (status=" + httpStatus + ", category=" + category
            + ", transient=" + isTransient + ", needsReauth=" + needsReauth + ")");
        this.isTransient = isTransient;
        this.needsReauth = needsReauth;
        this.httpStatus = httpStatus;
        this.category = category;
        this.retryAfter = retryAfter;
    }

    public boolean isTransient() { return isTransient; }
    public boolean isNeedsReauth() { return needsReauth; }
    public Integer getHttpStatus() { return httpStatus; }
    public String getCategory() { return category; }
    public Duration getRetryAfter() { return retryAfter; }
}
