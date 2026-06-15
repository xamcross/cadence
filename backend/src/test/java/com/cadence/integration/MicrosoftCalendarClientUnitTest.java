package com.cadence.integration;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F11 (D7 / QA B1): {@code Retry-After} parsing is a PURE function — both documented forms (delta-seconds
 * and HTTP-date), a past date clamped to zero, and malformed/absent -> null. No Spring, no sleep.
 */
class MicrosoftCalendarClientUnitTest {

    private static final Instant NOW = Instant.parse("2026-06-16T00:00:00Z");

    @Test
    void deltaSeconds() {
        assertThat(MicrosoftCalendarClient.parseRetryAfter("120", NOW)).isEqualTo(Duration.ofSeconds(120));
        assertThat(MicrosoftCalendarClient.parseRetryAfter("0", NOW)).isEqualTo(Duration.ZERO);
        assertThat(MicrosoftCalendarClient.parseRetryAfter("  7 ", NOW)).isEqualTo(Duration.ofSeconds(7));
    }

    @Test
    void httpDate_futureBecomesDelta() {
        String header = DateTimeFormatter.RFC_1123_DATE_TIME.format(NOW.plusSeconds(90).atOffset(ZoneOffset.UTC));
        assertThat(MicrosoftCalendarClient.parseRetryAfter(header, NOW)).isEqualTo(Duration.ofSeconds(90));
    }

    @Test
    void httpDate_inThePast_clampsToZero() {
        String header = DateTimeFormatter.RFC_1123_DATE_TIME.format(NOW.minusSeconds(60).atOffset(ZoneOffset.UTC));
        assertThat(MicrosoftCalendarClient.parseRetryAfter(header, NOW)).isEqualTo(Duration.ZERO);
    }

    @Test
    void malformedOrAbsent_isNull() {
        assertThat(MicrosoftCalendarClient.parseRetryAfter(null, NOW)).isNull();
        assertThat(MicrosoftCalendarClient.parseRetryAfter("   ", NOW)).isNull();
        assertThat(MicrosoftCalendarClient.parseRetryAfter("not-a-date", NOW)).isNull();
    }
}
