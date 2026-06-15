package com.cadence.integration;

import com.cadence.config.CalendarApiProperties;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * Bounded exponential-backoff-plus-jitter retry for Google Calendar API calls (F10, research D8). Retries
 * ONLY a {@link CalendarApiException} whose {@code transient} flag is set, up to {@code calendar.api.
 * max-retries}; everything else (fatal {@code CalendarApiException}, {@link CalendarReconnectRequiredException},
 * {@link CalendarNotConnectedException}) propagates immediately. Jitter de-synchronises a panel's retries.
 * Shared, identical-behaviour with F11.
 */
@Component
public class CalendarApiRetry {

    private final CalendarApiProperties props;

    public CalendarApiRetry(CalendarApiProperties props) {
        this.props = props;
    }

    public <T> T execute(Supplier<T> action) {
        int attempt = 0;
        while (true) {
            try {
                return action.get();
            } catch (CalendarApiException e) {
                if (e.isTransient() && attempt < props.getMaxRetries()) {
                    attempt++;
                    // Honour a provider Retry-After (Graph 429/503) over the default jittered backoff (F11 D7).
                    sleep(nextWaitMillis(attempt, e.getRetryAfter()));
                    continue;
                }
                throw e; // transient budget exhausted (still transient) or fatal
            }
        }
    }

    /**
     * The wait before the given 1-based retry attempt: {@code max(backoffMillis(attempt), retryAfterMillis)}
     * (F11 D7). PURE and side-effect-free so it is unit-testable with no sleep (plan-review QA B1): tests
     * assert this directly rather than measuring wall-clock. {@code retryAfter} null -> just the jittered
     * backoff (Google's unchanged behaviour).
     */
    public long nextWaitMillis(int attempt, java.time.Duration retryAfter) {
        long backoff = backoffMillis(attempt);
        long ra = retryAfter == null ? 0 : Math.max(0, retryAfter.toMillis());
        return Math.max(backoff, ra);
    }

    /**
     * Backoff for the given 1-based attempt: {@code base * 2^(attempt-1)} plus a jitter in
     * {@code [0, base)}. Bound asserted by the unit test: {@code <= base * 2^(attempt-1) + base}.
     */
    public long backoffMillis(int attempt) {
        long base = props.getRetryBaseBackoff().toMillis();
        if (base <= 0) {
            return 0;
        }
        long exp = base << (attempt - 1); // base * 2^(attempt-1)
        long jitter = ThreadLocalRandom.current().nextLong(base);
        return exp + jitter;
    }

    private static void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
