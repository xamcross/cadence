package com.cadence.interview;

import com.cadence.domain.AvailabilityStatus;
import com.cadence.domain.InterviewTemplate;
import com.cadence.domain.MemberUnschedulable;
import com.cadence.domain.SlotComputationRequest;
import com.cadence.domain.SlotComputationResult;
import com.cadence.domain.UnschedulableReason;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SC-004 fail-safe: an unknown required member yields 0 slots with a DISTINGUISHABLE reason; a busy (but
 * known) required member is NOT in {@code unschedulable}; an unknown POOL member is excluded from the
 * quorum (never counted free). A member who left the workspace surfaces as {@code NOT_CONNECTED} — the
 * same fail-safe path.
 */
class RuleEngineFailSafeTest {

    private static final Instant NOW = Instant.parse("2026-06-01T00:00:00Z");
    private static final LocalDate DAY = LocalDate.parse("2026-06-15");
    private static final LocalTime NINE = LocalTime.of(9, 0);
    private static final LocalTime FIVE_PM = LocalTime.of(17, 0);

    private SlotComputationRequest req(String id) {
        return new SlotComputationRequest(RuleEngineHarness.WS, id, DAY, DAY);
    }

    @ParameterizedTest
    @CsvSource({"NOT_CONNECTED,NOT_CONNECTED", "NEEDS_RECONNECTION,NEEDS_RECONNECTION",
                "TEMPORARILY_UNAVAILABLE,TEMPORARILY_UNAVAILABLE"})
    void requiredUnknown_yieldsNoSlots_withItsOwnReason(AvailabilityStatus status, UnschedulableReason reason) {
        InterviewTemplate t = RuleEngineHarness.template("t", 60, 60, 0, 0, 100, List.of("m1"), List.of());
        RuleEngineHarness h = new RuleEngineHarness(NOW).configured("UTC", NINE, FIVE_PM).template(t)
            .status("m1", status);

        SlotComputationResult r = h.engine.compute(req("t"));

        assertThat(r.slots()).isEmpty();
        assertThat(r.unschedulable()).containsExactly(new MemberUnschedulable("m1", reason));
    }

    @Test
    void busyRequiredMember_isNotReportedUnschedulable() {
        InterviewTemplate t = RuleEngineHarness.template("t", 60, 60, 0, 0, 100, List.of("m1"), List.of());
        RuleEngineHarness h = new RuleEngineHarness(NOW).configured("UTC", NINE, FIVE_PM).template(t)
            .busy("m1", Instant.parse("2026-06-15T10:00:00Z"), Instant.parse("2026-06-15T11:00:00Z"));

        SlotComputationResult r = h.engine.compute(req("t"));

        assertThat(r.slots()).isNotEmpty(); // free outside 10-11
        assertThat(r.unschedulable()).isEmpty(); // busy != unschedulable
    }

    @Test
    void requiredMemberWithNoAvailabilityRow_isNotConnected_andYieldsNoSlots() {
        // Defensive branch: AvailabilityService returns NO row at all for a required id (distinct from the
        // returned-NOT_CONNECTED-status case above) — the engine treats the absent row as NOT_CONNECTED.
        InterviewTemplate t = RuleEngineHarness.template("t", 60, 60, 0, 0, 100, List.of("gone"), List.of());
        RuleEngineHarness h = new RuleEngineHarness(NOW).configured("UTC", NINE, FIVE_PM).template(t).omit("gone");

        SlotComputationResult r = h.engine.compute(req("t"));

        assertThat(r.slots()).isEmpty();
        assertThat(r.unschedulable()).containsExactly(new MemberUnschedulable("gone", UnschedulableReason.NOT_CONNECTED));
    }

    @Test
    void unknownPoolMember_isExcludedFromQuorum_neverCountedFree() {
        // pool {m2,m3} need 1; m2 NEEDS_RECONNECTION, m3 free → quorum reached by m3 only.
        InterviewTemplate ok = RuleEngineHarness.template("t1", 60, 60, 0, 0, 100, List.of(),
            List.of(RuleEngineHarness.pool(1, "m2", "m3")));
        SlotComputationResult r1 = new RuleEngineHarness(NOW).configured("UTC", NINE, FIVE_PM).template(ok)
            .status("m2", AvailabilityStatus.NEEDS_RECONNECTION).free("m3").engine.compute(req("t1"));
        assertThat(r1.slots()).isNotEmpty();
        assertThat(r1.slots()).allSatisfy(s -> assertThat(s.qualifyingByPoolIndex().get(0)).containsExactly("m3"));

        // pool {m2} need 1; m2 unknown → never reaches quorum → 0 slots (not silently free).
        InterviewTemplate none = RuleEngineHarness.template("t2", 60, 60, 0, 0, 100, List.of(),
            List.of(RuleEngineHarness.pool(1, "m2")));
        SlotComputationResult r2 = new RuleEngineHarness(NOW).configured("UTC", NINE, FIVE_PM).template(none)
            .status("m2", AvailabilityStatus.NOT_CONNECTED).engine.compute(req("t2"));
        assertThat(r2.slots()).isEmpty();
    }
}
