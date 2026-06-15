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
 * SC-005: an "any N of pool" offers a slot only when ≥ N distinct positively-free members exist, and
 * each offered slot is annotated PER POOL with exactly the qualifying members (so F13 can finalise each
 * pool independently).
 */
class RuleEnginePoolTest {

    private static final Instant NOW = Instant.parse("2026-06-01T00:00:00Z");
    private static final LocalDate DAY = LocalDate.parse("2026-06-15");
    private static final LocalTime NINE = LocalTime.of(9, 0);
    private static final LocalTime FIVE_PM = LocalTime.of(17, 0);

    private SlotComputationRequest req(String id) {
        return new SlotComputationRequest(RuleEngineHarness.WS, id, DAY, DAY);
    }

    @Test
    void twoPools_eachQuorumAnnotatedSeparately() {
        // pool0 {a,b} need 1 (a free, b busy) ; pool1 {c,d} need 1 (c busy, d free).
        InterviewTemplate t = RuleEngineHarness.template("t", 60, 60, 0, 0, 100, List.of(),
            List.of(RuleEngineHarness.pool(1, "a", "b"), RuleEngineHarness.pool(1, "c", "d")));
        RuleEngineHarness h = new RuleEngineHarness(NOW).configured("UTC", NINE, FIVE_PM).template(t)
            .free("a").busy("b", Instant.parse("2026-06-15T00:00:00Z"), Instant.parse("2026-06-15T23:59:00Z"))
            .busy("c", Instant.parse("2026-06-15T00:00:00Z"), Instant.parse("2026-06-15T23:59:00Z")).free("d");

        SlotComputationResult r = h.engine.compute(req("t"));

        assertThat(r.slots()).isNotEmpty();
        assertThat(r.slots()).allSatisfy(s -> {
            assertThat(s.qualifyingByPoolIndex().get(0)).containsExactly("a");
            assertThat(s.qualifyingByPoolIndex().get(1)).containsExactly("d");
        });
    }

    @Test
    void quorumOfTwoOfThree_listsExactlyTheFreeMembersSorted() {
        InterviewTemplate t = RuleEngineHarness.template("t", 60, 60, 0, 0, 100, List.of(),
            List.of(RuleEngineHarness.pool(2, "a", "b", "c")));
        RuleEngineHarness h = new RuleEngineHarness(NOW).configured("UTC", NINE, FIVE_PM).template(t)
            .free("a").free("c").busy("b", Instant.parse("2026-06-15T00:00:00Z"), Instant.parse("2026-06-15T23:59:00Z"));

        SlotComputationResult r = h.engine.compute(req("t"));

        assertThat(r.slots()).isNotEmpty();
        assertThat(r.slots()).allSatisfy(s -> assertThat(s.qualifyingByPoolIndex().get(0)).containsExactly("a", "c"));
    }

    @Test
    void quorumNotReached_yieldsNoSlots() {
        InterviewTemplate t = RuleEngineHarness.template("t", 60, 60, 0, 0, 100, List.of(),
            List.of(RuleEngineHarness.pool(2, "a", "b")));
        RuleEngineHarness h = new RuleEngineHarness(NOW).configured("UTC", NINE, FIVE_PM).template(t)
            .free("a").busy("b", Instant.parse("2026-06-15T00:00:00Z"), Instant.parse("2026-06-15T23:59:00Z"));

        assertThat(h.engine.compute(req("t")).slots()).isEmpty(); // only 1 of 2 free
    }
}
