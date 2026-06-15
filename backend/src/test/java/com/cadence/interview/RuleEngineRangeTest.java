package com.cadence.interview;

import com.cadence.domain.InterviewTemplate;
import com.cadence.domain.SlotComputationRequest;
import com.cadence.domain.SlotComputationResult;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Spec Edge Cases: a range wholly in the past, or with end ≤ start, yields an empty set (no error). */
class RuleEngineRangeTest {

    private static final LocalTime NINE = LocalTime.of(9, 0);
    private static final LocalTime FIVE_PM = LocalTime.of(17, 0);

    private InterviewTemplate template() {
        return RuleEngineHarness.template("t", 60, 60, 0, 0, 100, List.of("m1"), List.of());
    }

    @Test
    void rangeWhollyInThePast_yieldsEmpty() {
        InterviewTemplate t = template();
        // now is AFTER the requested day → no future slot exists.
        RuleEngineHarness h = new RuleEngineHarness(Instant.parse("2026-06-20T00:00:00Z"))
            .configured("UTC", NINE, FIVE_PM).template(t).free("m1");

        SlotComputationResult r = h.engine.compute(new SlotComputationRequest(
            RuleEngineHarness.WS, "t", LocalDate.parse("2026-06-15"), LocalDate.parse("2026-06-15")));

        assertThat(r.slots()).isEmpty();
    }

    @Test
    void rangeEndBeforeStart_yieldsEmpty() {
        InterviewTemplate t = template();
        RuleEngineHarness h = new RuleEngineHarness(Instant.parse("2026-06-01T00:00:00Z"))
            .configured("UTC", NINE, FIVE_PM).template(t).free("m1");

        SlotComputationResult r = h.engine.compute(new SlotComputationRequest(
            RuleEngineHarness.WS, "t", LocalDate.parse("2026-06-20"), LocalDate.parse("2026-06-15")));

        assertThat(r.slots()).isEmpty();
    }
}
