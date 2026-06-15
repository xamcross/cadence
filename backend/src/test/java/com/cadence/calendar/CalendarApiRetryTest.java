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
    void classifyGraph_truthTable() {
        // F11 (D6): Graph throttling is 429 (NOT 403 like Google), so ANY 403 -> RECONNECT regardless of
        // the error code — including a quota-looking code that the Google classifier would call TRANSIENT.
        assertThat(CalendarApiClassifier.classifyGraph(429, null)).isEqualTo(Outcome.TRANSIENT);
        assertThat(CalendarApiClassifier.classifyGraph(500, null)).isEqualTo(Outcome.TRANSIENT);
        assertThat(CalendarApiClassifier.classifyGraph(503, "ServiceUnavailable")).isEqualTo(Outcome.TRANSIENT);
        assertThat(CalendarApiClassifier.classifyGraph(null, null)).isEqualTo(Outcome.TRANSIENT); // network
        assertThat(CalendarApiClassifier.classifyGraph(401, null)).isEqualTo(Outcome.RECONNECT);
        assertThat(CalendarApiClassifier.classifyGraph(403, "ErrorAccessDenied")).isEqualTo(Outcome.RECONNECT);
        assertThat(CalendarApiClassifier.classifyGraph(403, "rateLimitExceeded")).isEqualTo(Outcome.RECONNECT); // NOT transient for Graph
        assertThat(CalendarApiClassifier.classifyGraph(403, null)).isEqualTo(Outcome.RECONNECT);
        assertThat(CalendarApiClassifier.classifyGraph(400, null)).isEqualTo(Outcome.FATAL);
        assertThat(CalendarApiClassifier.classifyGraph(404, null)).isEqualTo(Outcome.FATAL);
        assertThat(CalendarApiClassifier.classifyGraph(409, null)).isEqualTo(Outcome.FATAL);
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

    // F11 (D7 / QA B1): the Retry-After wait is a PURE function — tested directly, never by wall-clock.

    @Test
    void nextWaitMillis_takesMaxOfBackoffAndRetryAfter() {
        CalendarApiRetry r = retry(3, 10);
        // A large Retry-After dominates the jittered backoff deterministically.
        assertThat(r.nextWaitMillis(1, Duration.ofSeconds(30))).isEqualTo(30_000);
        // Null Retry-After -> pure backoff in [base, base*2).
        for (int i = 0; i < 50; i++) {
            assertThat(r.nextWaitMillis(1, null)).isGreaterThanOrEqualTo(10).isLessThan(20);
        }
        // Zero / smaller-than-backoff Retry-After -> backoff wins (>= base).
        assertThat(r.nextWaitMillis(1, Duration.ZERO)).isGreaterThanOrEqualTo(10);
        assertThat(r.nextWaitMillis(1, Duration.ofMillis(3))).isGreaterThanOrEqualTo(10);
    }

    @Test
    void nextWaitMillis_zeroBackoff_usesRetryAfter() {
        CalendarApiRetry r = retry(3, 0); // PT0S base -> backoff 0
        assertThat(r.nextWaitMillis(1, null)).isZero();
        assertThat(r.nextWaitMillis(1, Duration.ofSeconds(2))).isEqualTo(2_000);
    }
}
