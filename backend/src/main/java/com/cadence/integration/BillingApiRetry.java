package com.cadence.integration;

import com.cadence.config.BillingProperties;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * 032 -- bounded retry with jittered backoff for TRANSIENT billing API failures only (the
 * AtsApiRetry shape). Pure backoff math; tests zero the base backoff so no sleeps occur.
 */
@Component
public class BillingApiRetry {

    private final int maxAttempts;
    private final long baseBackoffMillis;

    public BillingApiRetry(BillingProperties props) {
        this.maxAttempts = props.getRetryMaxAttempts();
        this.baseBackoffMillis = props.getRetryBaseBackoff().toMillis();
    }

    public <T> T execute(Supplier<T> attempt) {
        BillingApiException last = null;
        for (int i = 0; i < maxAttempts; i++) {
            try {
                return attempt.get();
            } catch (BillingApiException e) {
                if (!e.isTransient()) {
                    throw e;
                }
                last = e;
                sleep(backoffMillis(i));
            }
        }
        throw last;
    }

    /** Exposed for backoff-shape assertions without sleeping. */
    long backoffMillis(int attemptIndex) {
        long base = baseBackoffMillis * (1L << attemptIndex);
        return base == 0 ? 0 : base + (long) (Math.random() * (base / 2.0));
    }

    private static void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BillingApiException(true, null, "interrupted");
        }
    }
}
