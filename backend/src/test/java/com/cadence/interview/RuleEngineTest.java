package com.cadence.interview;

import com.cadence.domain.ComputedSlot;
import com.cadence.domain.InterviewTemplate;
import com.cadence.domain.PoolRule;
import com.cadence.domain.SlotComputationRequest;
import com.cadence.domain.SlotComputationResult;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SC-001 (+ duration>window, blackout∩WH precedence, pool-of-1, optional-never-gates, windowClamped):
 * per-rule truth tables, 0 violating slots. Pure unit tests over a fixed availability snapshot (UTC to
 * isolate from DST — DST has its own test). {@code now} is well before the range so future-only never
 * interferes.
 */
class RuleEngineTest {

    private static final Instant NOW = Instant.parse("2026-06-01T00:00:00Z");
    private static final LocalDate DAY = LocalDate.parse("2026-06-15");
    private static final LocalTime NINE = LocalTime.of(9, 0);
    private static final LocalTime FIVE_PM = LocalTime.of(17, 0);

    private SlotComputationRequest req(String templateId) {
        return new SlotComputationRequest(RuleEngineHarness.WS, templateId, DAY, DAY);
    }

    private static Instant at(String time) {
        return Instant.parse("2026-06-15T" + time + ":00Z");
    }

    @Test
    void duration_everySlotIsExactlyTheDuration_andAnchoredToWorkingDayStart() {
        InterviewTemplate t = RuleEngineHarness.template("t", 60, 30, 0, 0, 100, List.of("m1"), List.of());
        RuleEngineHarness h = new RuleEngineHarness(NOW).configured("UTC", NINE, FIVE_PM).template(t).free("m1");

        SlotComputationResult r = h.engine.compute(req("t"));

        assertThat(r.slots()).isNotEmpty();
        assertThat(r.slots()).allSatisfy(s ->
            assertThat(Duration.between(s.start(), s.end())).isEqualTo(Duration.ofMinutes(60)));
        assertThat(r.slots().get(0).start()).isEqualTo(at("09:00")); // cadence anchored to WH start
        // ascending order
        assertThat(r.slots()).isSortedAccordingTo((a, b) -> a.start().compareTo(b.start()));
    }

    @Test
    void requiredBusy_slotsNeverOverlapTheBusyBlock() {
        InterviewTemplate t = RuleEngineHarness.template("t", 60, 60, 0, 0, 100, List.of("m1"), List.of());
        RuleEngineHarness h = new RuleEngineHarness(NOW).configured("UTC", NINE, FIVE_PM).template(t)
            .busy("m1", at("10:00"), at("11:00"));

        SlotComputationResult r = h.engine.compute(req("t"));

        assertThat(r.slots()).isNotEmpty();
        assertThat(r.slots()).noneMatch(s -> overlaps(s, at("10:00"), at("11:00")));
    }

    @Test
    void buffer_slotsRespectBufferAroundCommitments() {
        InterviewTemplate t = RuleEngineHarness.template("t", 60, 60, 15, 15, 100, List.of("m1"), List.of());
        RuleEngineHarness h = new RuleEngineHarness(NOW).configured("UTC", NINE, FIVE_PM).template(t)
            .busy("m1", at("12:00"), at("13:00"));

        SlotComputationResult r = h.engine.compute(req("t"));

        assertThat(r.slots()).isNotEmpty();
        // No offered slot's BUFFERED window may overlap the busy block.
        assertThat(r.slots()).noneMatch(s ->
            overlaps(s.start().minus(Duration.ofMinutes(15)), s.end().plus(Duration.ofMinutes(15)),
                at("12:00"), at("13:00")));
    }

    @Test
    void blackout_alwaysWins_noSlotOverlapsBlackoutInsideWorkingHours() {
        InterviewTemplate t = RuleEngineHarness.template("t", 60, 60, 0, 0, 100, List.of("m1"), List.of());
        t.getBlackouts().add(new com.cadence.domain.BlackoutPeriod(at("12:00"), at("14:00")));
        RuleEngineHarness h = new RuleEngineHarness(NOW).configured("UTC", NINE, FIVE_PM).template(t).free("m1");

        SlotComputationResult r = h.engine.compute(req("t"));

        assertThat(r.slots()).isNotEmpty();
        assertThat(r.slots()).noneMatch(s -> overlaps(s, at("12:00"), at("14:00")));
    }

    @Test
    void durationLongerThanWorkingWindow_yieldsZeroSlots_notAnError() {
        InterviewTemplate t = RuleEngineHarness.template("t", 600, 60, 0, 0, 100, List.of("m1"), List.of());
        RuleEngineHarness h = new RuleEngineHarness(NOW).configured("UTC", NINE, FIVE_PM).template(t).free("m1");

        SlotComputationResult r = h.engine.compute(req("t"));

        assertThat(r.slots()).isEmpty();
    }

    @Test
    void poolOfOne_behavesLikeRequired() {
        InterviewTemplate withFree = RuleEngineHarness.template("t1", 60, 60, 0, 0, 100, List.of(), List.of(pool1("m2")));
        InterviewTemplate withBusy = RuleEngineHarness.template("t2", 60, 60, 0, 0, 100, List.of(), List.of(pool1("m2")));

        SlotComputationResult free = new RuleEngineHarness(NOW).configured("UTC", NINE, FIVE_PM)
            .template(withFree).free("m2").engine.compute(req("t1"));
        SlotComputationResult busy = new RuleEngineHarness(NOW).configured("UTC", NINE, FIVE_PM)
            .template(withBusy).busy("m2", at("00:00"), at("23:59")).engine.compute(req("t2"));

        assertThat(free.slots()).isNotEmpty();
        assertThat(busy.slots()).isEmpty();
    }

    @Test
    void optionalParticipant_neverGatesASlot() {
        InterviewTemplate t = RuleEngineHarness.template("t", 60, 60, 0, 0, 100, List.of("m1"), List.of());
        t.setOptionalMemberIds(List.of("m2"));
        RuleEngineHarness h = new RuleEngineHarness(NOW).configured("UTC", NINE, FIVE_PM).template(t)
            .free("m1").busy("m2", at("00:00"), at("23:59")); // optional fully busy

        SlotComputationResult r = h.engine.compute(req("t"));

        assertThat(r.slots()).isNotEmpty(); // optional busyness did not reduce the offer
    }

    @Test
    void windowWiderThanMax_isClamped() {
        InterviewTemplate t = RuleEngineHarness.template("t", 60, 60, 0, 0, 100, List.of("m1"), List.of());
        RuleEngineHarness h = new RuleEngineHarness(NOW).configured("UTC", NINE, FIVE_PM).template(t).free("m1");

        // 60d max window; request a full year.
        SlotComputationResult r = h.engine.compute(new SlotComputationRequest(
            RuleEngineHarness.WS, "t", DAY, DAY.plusYears(1)));

        assertThat(r.windowClamped()).isTrue();
        Instant windowStart = DAY.atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
        assertThat(r.slots()).allSatisfy(s ->
            assertThat(s.start()).isBefore(windowStart.plus(Duration.ofDays(60))));
    }

    @Test
    void durationFitsButBuffersDont_yieldsZeroSlots() {
        // WH window 09:00-10:00 (60 min): a 60-min interview fits, but +15/15 buffers (90 min) does not.
        InterviewTemplate withBuffers = RuleEngineHarness.template("t1", 60, 60, 15, 15, 100, List.of("m1"), List.of());
        InterviewTemplate noBuffers = RuleEngineHarness.template("t2", 60, 60, 0, 0, 100, List.of("m1"), List.of());

        var b = new RuleEngineHarness(NOW).configured("UTC", NINE, LocalTime.of(10, 0)).template(withBuffers)
            .free("m1").engine.compute(req("t1"));
        var nb = new RuleEngineHarness(NOW).configured("UTC", NINE, LocalTime.of(10, 0)).template(noBuffers)
            .free("m1").engine.compute(req("t2"));

        assertThat(b.slots()).isEmpty();      // duration+buffers does not fit
        assertThat(nb.slots()).hasSize(1);     // 09:00-10:00 fits with no buffers
    }

    @Test
    void deterministic_sameInputsProduceIdenticalResult() {
        InterviewTemplate t = RuleEngineHarness.template("t", 60, 30, 0, 0, 100, List.of("m1"),
            List.of(new PoolRule(List.of("a", "b"), 1)));
        RuleEngineHarness h = new RuleEngineHarness(NOW).configured("UTC", NINE, FIVE_PM).template(t)
            .free("m1").free("a").busy("b", at("00:00"), at("23:59"));

        assertThat(h.engine.compute(req("t"))).isEqualTo(h.engine.compute(req("t")));
    }

    private static PoolRule pool1(String member) {
        return new PoolRule(List.of(member), 1);
    }

    private static boolean overlaps(ComputedSlot s, Instant from, Instant to) {
        return overlaps(s.start(), s.end(), from, to);
    }

    private static boolean overlaps(Instant aStart, Instant aEnd, Instant bStart, Instant bEnd) {
        return aStart.isBefore(bEnd) && bStart.isBefore(aEnd);
    }
}
