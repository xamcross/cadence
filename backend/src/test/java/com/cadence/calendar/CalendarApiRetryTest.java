package com.cadence.calendar;

import com.cadence.config.CalendarApiProperties;
import com.cadence.integration.CalendarApiClassifier;
import com.cadence.integration.CalendarApiClassifier.Outcome;
import com.cadence.integration.CalendarApiException;
import com.cadence.integration.CalendarApiRetry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * US4 pure-unit: the reason-aware classifier truth table (D8 / plan-review m3 — Google overloads 403),
 * the backoff+jitter bound, and the retry loop's transient-vs-fatal behaviour. No Spring/Docker.
 */
class CalendarApiRetryTest {

    private CalendarApiRetry retry(int maxRetries, long baseMs) {
        CalendarApiProperties props = new CalendarApiProperties();
        props.setMaxRetries(maxRetries);
        props.setRetryBaseBackoff(Duration.ofMillis(baseMs));
        return new CalendarApiRetry(props);
    }

    @Test
    void classifier_truthTable() {
        assertThat(CalendarApiClassifier.classify(429, null)).isEqualTo(Outcome.TRANSIENT);
        assertThat(CalendarApiClassifier.classify(500, null)).isEqualTo(Outcome.TRANSIENT);
        assertThat(CalendarApiClassifier.classify(503, null)).isEqualTo(Outcome.TRANSIENT);
        assertThat(CalendarApiClassifier.classify(null, null)).isEqualTo(Outcome.TRANSIENT); // network
        assertThat(CalendarApiClassifier.classify(403, "rateLimitExceeded")).isEqualTo(Outcome.TRANSIENT);
        assertThat(CalendarApiClassifier.classify(403, "userRateLimitExceeded")).isEqualTo(Outcome.TRANSIENT);
        assertThat(CalendarApiClassifier.classify(403, "dailyLimitExceeded")).isEqualTo(Outcome.TRANSIENT);
        assertThat(CalendarApiClassifier.classify(403, "quotaExceeded")).isEqualTo(Outcome.TRANSIENT);
        assertThat(CalendarApiClassifier.classify(403, "insufficientPermissions")).isEqualTo(Outcome.RECONNECT);
        assertThat(CalendarApiClassifier.classify(403, "insufficientScope")).isEqualTo(Outcome.RECONNECT);
        assertThat(CalendarApiClassifier.classify(403, null)).isEqualTo(Outcome.RECONNECT);
        assertThat(CalendarApiClassifier.classify(401, null)).isEqualTo(Outcome.RECONNECT);
        assertThat(CalendarApiClassifier.classify(400, null)).isEqualTo(Outcome.FATAL);
        assertThat(CalendarApiClassifier.classify(409, null)).isEqualTo(Outcome.FATAL);
        assertThat(CalendarApiClassifier.classify(404, null)).isEqualTo(Outcome.FATAL);
    }

    @Test
    void backoff_isWithinBound() {
        long base = 10;
        CalendarApiRetry r = retry(3, base);
        for (int attempt = 1; attempt <= 4; attempt++) {
            long exp = base << (attempt - 1);
            for (int i = 0; i < 50; i++) {
                long delay = r.backoffMillis(attempt);
                assertThat(delay).isGreaterThanOrEqualTo(exp);          // >= base*2^(attempt-1)
                assertThat(delay).isLessThan(exp + base);               // < base*2^(attempt-1) + base (jitter)
            }
        }
    }

    @Test
    void transientThenSuccess_retriesAndSucceeds() {
        CalendarApiRetry r = retry(3, 0); // PT0 — no real sleep
        AtomicInteger calls = new AtomicInteger();
        String result = r.execute(() -> {
            if (calls.incrementAndGet() <= 2) {
                throw new CalendarApiException(true, 503, null);
            }
            return "ok";
        });
        assertThat(result).isEqualTo("ok");
        assertThat(calls.get()).isEqualTo(3); // 2 transient + 1 success
    }

    @Test
    void persistentTransient_exhaustsThenThrows() {
        CalendarApiRetry r = retry(3, 0);
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> r.execute(() -> {
            calls.incrementAndGet();
            throw new CalendarApiException(true, 503, null);
        })).isInstanceOf(CalendarApiException.class);
        assertThat(calls.get()).isEqualTo(4); // 1 initial + 3 retries
    }

    @Test
    void fatal_notRetried() {
        CalendarApiRetry r = retry(3, 0);
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> r.execute(() -> {
            calls.incrementAndGet();
            throw new CalendarApiException(false, 400, null);
        })).isInstanceOf(CalendarApiException.class);
        assertThat(calls.get()).isEqualTo(1);
    }
}
