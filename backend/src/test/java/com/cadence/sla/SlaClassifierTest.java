package com.cadence.sla;

import com.cadence.domain.CandidateStatusOutcome;
import com.cadence.domain.ErasureState;
import com.cadence.domain.SlaState;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F31 T017 (SC-001/SC-009) — pure-unit classification of green/amber/red against the silence window. No Spring,
 * no container. {@code SlaNudgeService.classify} is package-static; invoked via reflection so we don't need to
 * construct the service. {@code Duration.ofDays} is absolute, so a DST change in the workspace zone cannot flap
 * the boundary — asserted by classifying instants straddling a spring-forward in America/New_York.
 */
class SlaClassifierTest {

    private static final int WINDOW = 5;
    private static final int AMBER_MARGIN = 1;

    private static SlaState classify(Instant lastContactAt, CandidateStatusOutcome outcome,
                                     ErasureState erasureState, Instant now) throws Exception {
        Method m = Class.forName("com.cadence.service.SlaNudgeService").getDeclaredMethod(
            "classify", Instant.class, Instant.class, CandidateStatusOutcome.class, ErasureState.class,
            int.class, int.class, Instant.class);
        m.setAccessible(true);
        return (SlaState) m.invoke(null, lastContactAt, null, outcome, erasureState, WINDOW, AMBER_MARGIN, now);
    }

    @Test
    void wellWithin_isGreen() throws Exception {
        Instant now = Instant.parse("2026-06-10T12:00:00Z");
        assertThat(classify(now.minus(Duration.ofDays(1)), null, ErasureState.ACTIVE, now)).isEqualTo(SlaState.GREEN);
    }

    @Test
    void withinAmberMargin_isAmber() throws Exception {
        Instant now = Instant.parse("2026-06-10T12:00:00Z");
        // window 5, amber margin 1 -> amber when older than 4 days (and not yet 5).
        assertThat(classify(now.minus(Duration.ofDays(4)).minusSeconds(1), null, ErasureState.ACTIVE, now))
            .isEqualTo(SlaState.AMBER);
    }

    @Test
    void pastWindow_isRed() throws Exception {
        Instant now = Instant.parse("2026-06-10T12:00:00Z");
        assertThat(classify(now.minus(Duration.ofDays(6)), null, ErasureState.ACTIVE, now)).isEqualTo(SlaState.RED);
    }

    @Test
    void exactBoundary_isDeterministic() throws Exception {
        Instant now = Instant.parse("2026-06-10T12:00:00Z");
        // Exactly at the cutoff (not strictly before) -> NOT red. One second older -> red.
        assertThat(classify(now.minus(Duration.ofDays(5)), null, ErasureState.ACTIVE, now)).isNotEqualTo(SlaState.RED);
        assertThat(classify(now.minus(Duration.ofDays(5)).minusSeconds(1), null, ErasureState.ACTIVE, now))
            .isEqualTo(SlaState.RED);
    }

    @Test
    void dstCrossing_doesNotFlap() throws Exception {
        // US spring-forward 2026-03-08 02:00 local. "now" just after; lastContact 6 absolute days earlier.
        ZoneId ny = ZoneId.of("America/New_York");
        Instant now = ZonedDateTime.of(2026, 3, 10, 9, 0, 0, 0, ny).toInstant();
        Instant sixDaysAbsolute = now.minus(Duration.ofDays(6));
        assertThat(classify(sixDaysAbsolute, null, ErasureState.ACTIVE, now)).isEqualTo(SlaState.RED);
        Instant oneDayAbsolute = now.minus(Duration.ofDays(1));
        assertThat(classify(oneDayAbsolute, null, ErasureState.ACTIVE, now)).isEqualTo(SlaState.GREEN);
    }

    @Test
    void erased_neverSurfaced() throws Exception {
        Instant now = Instant.parse("2026-06-10T12:00:00Z");
        assertThat(classify(now.minus(Duration.ofDays(30)), null, ErasureState.ERASED, now)).isEqualTo(SlaState.GREEN);
    }

    @Test
    void terminalOutcome_neverSurfaced() throws Exception {
        Instant now = Instant.parse("2026-06-10T12:00:00Z");
        assertThat(classify(now.minus(Duration.ofDays(30)), CandidateStatusOutcome.COMPLETE_OFFER,
            ErasureState.ACTIVE, now)).isEqualTo(SlaState.GREEN);
        assertThat(classify(now.minus(Duration.ofDays(30)), CandidateStatusOutcome.COMPLETE_REJECTED,
            ErasureState.ACTIVE, now)).isEqualTo(SlaState.GREEN);
    }
}
