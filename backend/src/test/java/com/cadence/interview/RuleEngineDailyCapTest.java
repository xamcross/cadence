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

/**
 * SC-002 daily cap (the cap arithmetic — the repository's status-exclusion filter is verified against a
 * real Mongo in the integration test). Pre-existing managed interviews and the within-computation offer
 * count are both bounded; the day boundary is zone-relative.
 */
class RuleEngineDailyCapTest {

    private static final Instant NOW = Instant.parse("2026-06-01T00:00:00Z");
    private static final LocalDate DAY = LocalDate.parse("2026-06-15");
    private static final LocalTime NINE = LocalTime.of(9, 0);
    private static final LocalTime FIVE_PM = LocalTime.of(17, 0);

    private SlotComputationRequest req(String id, String zone) {
        return new SlotComputationRequest(RuleEngineHarness.WS, id, DAY, DAY);
    }

    @Test
    void preExistingAtCap_offersNoFurtherSlotsThatDay() {
        InterviewTemplate t = RuleEngineHarness.template("t", 60, 60, 0, 0, 2, List.of("m1"), List.of());
        RuleEngineHarness h = new RuleEngineHarness(NOW).configured("UTC", NINE, FIVE_PM).template(t)
            .free("m1")
            .managed("m1", Instant.parse("2026-06-15T09:30:00Z"), Instant.parse("2026-06-15T11:30:00Z"));

        assertThat(h.engine.compute(req("t", "UTC")).slots()).isEmpty();
    }

    @Test
    void withinComputation_neverOffersMoreThanTheCapPerDay() {
        InterviewTemplate t = RuleEngineHarness.template("t", 60, 60, 0, 0, 2, List.of("m1"), List.of());
        RuleEngineHarness h = new RuleEngineHarness(NOW).configured("UTC", NINE, FIVE_PM).template(t).free("m1");

        SlotComputationResult r = h.engine.compute(req("t", "UTC"));

        assertThat(r.slots()).hasSize(2); // cap 2, zero pre-existing → exactly 2 offered for the day
    }

    @Test
    void capCountedPerZoneRelativeCivilDay_notUtcDay() {
        // zone NY; one managed event at 2026-06-15 22:00 NY (= 2026-06-16T02:00Z, the NEXT UTC day) still
        // counts toward the 06-15 NY civil day. cap 1 → 0 slots offered on 06-15.
        InterviewTemplate t = RuleEngineHarness.template("t", 60, 60, 0, 0, 1, List.of("m1"), List.of());
        RuleEngineHarness h = new RuleEngineHarness(NOW).configured("America/New_York", NINE, FIVE_PM).template(t)
            .free("m1").managed("m1", Instant.parse("2026-06-16T02:00:00Z"));

        assertThat(h.engine.compute(req("t", "America/New_York")).slots()).isEmpty();
    }
}
