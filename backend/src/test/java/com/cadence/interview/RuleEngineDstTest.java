package com.cadence.interview;

import com.cadence.domain.InterviewTemplate;
import com.cadence.domain.SlotComputationRequest;
import com.cadence.domain.SlotComputationResult;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SC-003: DST correctness with a pinned zone + fixed clock. Spring-forward (2026-03-08, 02:00→03:00):
 * the non-existent local hour yields 0 slots. Fall-back (2026-11-01, 02:00→01:00): the repeated 01:00
 * hour is offered EXACTLY once. Cadence/working-hours math is on absolute instants, so the day length
 * (23h/25h) does not corrupt the offer.
 */
class RuleEngineDstTest {

    private static final ZoneId NY = ZoneId.of("America/New_York");
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private long localStartCount(SlotComputationResult r, LocalTime local) {
        return r.slots().stream()
            .map(s -> ZonedDateTime.ofInstant(s.start(), NY).toLocalTime())
            .filter(local::equals)
            .count();
    }

    @Test
    void springForward_nonExistentLocalHour_yieldsNoSlotAtTheGap() {
        // WH 00:00-06:00 straddles the 02:00→03:00 gap on 2026-03-08.
        InterviewTemplate t = RuleEngineHarness.template("t", 60, 60, 0, 0, 100, List.of("m1"), List.of());
        RuleEngineHarness h = new RuleEngineHarness(NOW)
            .configured("America/New_York", LocalTime.of(0, 0), LocalTime.of(6, 0)).template(t).free("m1");

        SlotComputationResult r = h.engine.compute(new SlotComputationRequest(
            RuleEngineHarness.WS, "t", LocalDate.parse("2026-03-08"), LocalDate.parse("2026-03-08")));

        assertThat(localStartCount(r, LocalTime.of(2, 0))).isZero();   // the gap hour does not exist
        assertThat(localStartCount(r, LocalTime.of(1, 0))).isEqualTo(1);
        assertThat(localStartCount(r, LocalTime.of(3, 0))).isEqualTo(1);
    }

    @Test
    void springForward_bufferAfterLandingInGap_rejectsTheSlot() {
        // start 01:00 valid, duration 30 + bufferAfter 60 => bufferEnd 02:30 local = in the gap → rejected.
        InterviewTemplate t = RuleEngineHarness.template("t", 30, 60, 0, 60, 100, List.of("m1"), List.of());
        RuleEngineHarness h = new RuleEngineHarness(NOW)
            .configured("America/New_York", LocalTime.of(0, 0), LocalTime.of(6, 0)).template(t).free("m1");

        SlotComputationResult r = h.engine.compute(new SlotComputationRequest(
            RuleEngineHarness.WS, "t", LocalDate.parse("2026-03-08"), LocalDate.parse("2026-03-08")));

        assertThat(localStartCount(r, LocalTime.of(1, 0))).isZero(); // its buffer-after fell in the gap
    }

    @Test
    void fallBack_repeatedLocalHour_isOfferedExactlyOnce() {
        // WH 00:00-03:00 includes the repeated 01:00-01:59 hour on 2026-11-01.
        InterviewTemplate t = RuleEngineHarness.template("t", 60, 60, 0, 0, 100, List.of("m1"), List.of());
        RuleEngineHarness h = new RuleEngineHarness(NOW)
            .configured("America/New_York", LocalTime.of(0, 0), LocalTime.of(3, 0)).template(t).free("m1");

        SlotComputationResult r = h.engine.compute(new SlotComputationRequest(
            RuleEngineHarness.WS, "t", LocalDate.parse("2026-11-01"), LocalDate.parse("2026-11-01")));

        assertThat(localStartCount(r, LocalTime.of(1, 0))).isEqualTo(1); // not double-counted
    }
}
