package com.cadence.ats;

import com.cadence.config.AtsProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit (T052): the SC-004 recovery budget + the F22 reaper invariant must hold for the default config, so a
 * future config change cannot silently blow them.
 */
class AtsPropertiesBoundsTest {

    @Test
    void defaultsSatisfyRecoveryBudgetAndReaperInvariant() {
        AtsProperties p = new AtsProperties();

        long base = p.getRetryBaseBackoff().toMillis();
        long worstBackoff = base << Math.min(p.getRetryMaxAttempts(), 16); // base * 2^maxAttempts
        // SC-004: a recoverable write-back must clear well inside 15 minutes.
        assertThat(worstBackoff).isLessThan(Duration.ofMinutes(15).toMillis());

        // F22 invariant: the reaper must never race a live in-flight claim.
        assertThat(p.getReaperThreshold().toMillis())
            .isGreaterThan(p.getReadTimeout().toMillis() + worstBackoff);

        // FR-009: the poll interval must not exceed the 5-minute freshness target.
        assertThat(p.getPollInterval()).isLessThanOrEqualTo(Duration.ofMinutes(5));
    }
}
