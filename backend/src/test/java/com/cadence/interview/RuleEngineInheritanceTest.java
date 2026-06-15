package com.cadence.interview;

import com.cadence.domain.InterviewTemplate;
import com.cadence.domain.SlotComputationRequest;
import com.cadence.domain.SlotComputationResult;
import com.cadence.domain.WorkingHours;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * US3: a template inherits the workspace working hours + time zone unless overridden (each independently);
 * a later workspace change is reflected because resolution happens at compute time (by reference).
 */
class RuleEngineInheritanceTest {

    private static final Instant NOW = Instant.parse("2026-06-01T00:00:00Z");
    private static final LocalDate DAY = LocalDate.parse("2026-06-15"); // summer (BST / EDT)

    private SlotComputationRequest req(String id) {
        return new SlotComputationRequest(RuleEngineHarness.WS, id, DAY, DAY);
    }

    private static InterviewTemplate template(String id) {
        return RuleEngineHarness.template(id, 60, 60, 0, 0, 100, List.of("m1"), List.of());
    }

    private void assertAllWithin(SlotComputationResult r, String zoneId, LocalTime start, LocalTime end) {
        ZoneId zone = ZoneId.of(zoneId);
        assertThat(r.slots()).isNotEmpty();
        assertThat(r.slots()).allSatisfy(s -> {
            assertThat(ZonedDateTime.ofInstant(s.start(), zone).toLocalTime()).isAfterOrEqualTo(start);
            assertThat(ZonedDateTime.ofInstant(s.end(), zone).toLocalTime()).isBeforeOrEqualTo(end);
        });
    }

    @Test
    void inheritsWorkspaceHoursAndZone_whenNoOverride() {
        InterviewTemplate t = template("t");
        RuleEngineHarness h = new RuleEngineHarness(NOW)
            .configured("Europe/London", LocalTime.of(9, 0), LocalTime.of(17, 0)).template(t).free("m1");

        assertAllWithin(h.engine.compute(req("t")), "Europe/London", LocalTime.of(9, 0), LocalTime.of(17, 0));
    }

    @Test
    void workingHoursOverride_replacesWorkspaceHours() {
        InterviewTemplate t = template("t");
        t.setWorkingHoursOverride(new WorkingHours(LocalTime.of(7, 0), LocalTime.of(11, 0)));
        RuleEngineHarness h = new RuleEngineHarness(NOW)
            .configured("Europe/London", LocalTime.of(9, 0), LocalTime.of(17, 0)).template(t).free("m1");

        assertAllWithin(h.engine.compute(req("t")), "Europe/London", LocalTime.of(7, 0), LocalTime.of(11, 0));
    }

    @Test
    void zoneOverrideOnly_inheritsWorkspaceHoursInTheOverriddenZone() {
        InterviewTemplate t = template("t");
        t.setTimeZoneOverride("America/New_York");
        RuleEngineHarness h = new RuleEngineHarness(NOW)
            .configured("Europe/London", LocalTime.of(9, 0), LocalTime.of(17, 0)).template(t).free("m1");

        assertAllWithin(h.engine.compute(req("t")), "America/New_York", LocalTime.of(9, 0), LocalTime.of(17, 0));
    }

    @Test
    void laterWorkspaceHoursChange_isReflected() {
        InterviewTemplate t = template("t");
        RuleEngineHarness h = new RuleEngineHarness(NOW).template(t).free("m1");

        h.configured("UTC", LocalTime.of(9, 0), LocalTime.of(17, 0));
        int wide = h.engine.compute(req("t")).slots().size();
        h.configured("UTC", LocalTime.of(9, 0), LocalTime.of(12, 0)); // workspace narrows its hours
        int narrow = h.engine.compute(req("t")).slots().size();

        assertThat(narrow).isLessThan(wide); // inheritance is by reference at compute time
    }
}
