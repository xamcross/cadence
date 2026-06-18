package com.cadence.ats;

import com.cadence.config.AtsProperties;
import com.cadence.integration.AtsApiException;
import com.cadence.integration.AtsApiRetry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unit (no wall-clock): nextWaitMillis = max(backoff, retryAfter); retry only TRANSIENT, never AUTH/FATAL (T010). */
class AtsApiRetryTest {

    private AtsApiRetry retry(long baseMillis, int maxAttempts) {
        AtsProperties p = new AtsProperties();
        p.setRetryBaseBackoff(Duration.ofMillis(baseMillis));
        p.setRetryMaxAttempts(maxAttempts);
        return new AtsApiRetry(p);
    }

    @Test
    void retryAfterDominatesBackoff() {
        AtsApiRetry r = retry(100, 3);
        assertThat(r.nextWaitMillis(1, Duration.ofSeconds(5))).isEqualTo(5000);
    }

    @Test
    void backoffUsedWhenNoRetryAfter() {
        AtsApiRetry r = retry(0, 3); // base 0 -> backoff 0
        assertThat(r.nextWaitMillis(1, null)).isEqualTo(0);
    }

    @Test
    void retriesTransientThenSucceeds() {
        AtsApiRetry r = retry(0, 3);
        AtomicInteger calls = new AtomicInteger();
        String out = r.execute(() -> {
            if (calls.getAndIncrement() < 2) {
                throw new AtsApiException(true, false, 503, "transient");
            }
            return "ok";
        });
        assertThat(out).isEqualTo("ok");
        assertThat(calls.get()).isEqualTo(3);
    }

    @Test
    void authIsNotRetried() {
        AtsApiRetry r = retry(0, 3);
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> r.execute(() -> {
            calls.incrementAndGet();
            throw new AtsApiException(false, true, 401, "auth");
        })).isInstanceOf(AtsApiException.class);
        assertThat(calls.get()).isEqualTo(1); // no retry on AUTH
    }
}
