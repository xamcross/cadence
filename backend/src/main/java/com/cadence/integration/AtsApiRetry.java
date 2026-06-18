package com.cadence.integration;

import com.cadence.config.AtsProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * Bounded exponential-backoff-plus-jitter retry for Greenhouse API calls (F40, mirrors
 * {@link CalendarApiRetry}). Retries ONLY an {@link AtsApiException} whose {@code transient} flag is set,
 * up to {@code cadence.ats.retry-max-attempts}; an AUTH (needs-reauth) or FATAL exception propagates
 * immediately. Honours a provider {@code Retry-After} over the jittered backoff.
 */
@Component
public class AtsApiRetry {

    private final AtsProperties props;

    public AtsApiRetry(AtsProperties props) {
        this.props = props;
    }

    public <T> T execute(Supplier<T> action) {
        int attempt = 0;
        while (true) {
            try {
                return action.get();
            } catch (AtsApiException e) {
                if (e.isTransient() && !e.isNeedsReauth() && attempt < props.getRetryMaxAttempts()) {
                    attempt++;
                    sleep(nextWaitMillis(attempt, e.getRetryAfter()));
                    continue;
                }
                throw e; // transient budget exhausted, AUTH, or fatal
            }
        }
    }

    /**
     * The wait before the given 1-based retry attempt: {@code max(backoffMillis(attempt), retryAfterMillis)}.
     * PURE and side-effect-free so it is unit-testable with no sleep; tests assert this directly rather than
     * measuring wall-clock. {@code retryAfter} null -> just the jittered backoff.
     */
    public long nextWaitMillis(int attempt, Duration retryAfter) {
        long backoff = backoffMillis(attempt);
        long ra = retryAfter == null ? 0 : Math.max(0, retryAfter.toMillis());
        return Math.max(backoff, ra);
    }

    /** Backoff for the given 1-based attempt: {@code base * 2^(attempt-1)} plus a jitter in {@code [0, base)}. */
    public long backoffMillis(int attempt) {
        long base = props.getRetryBaseBackoff().toMillis();
        if (base <= 0) {
            return 0;
        }
        long capped = Math.min(attempt - 1, 16); // guard against overflow on a runaway attempt
        long exp = base << capped;
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
