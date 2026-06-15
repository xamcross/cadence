package com.cadence.calendar;

import com.cadence.domain.AvailabilityStatus;
import com.cadence.domain.CalendarProvider;
import com.cadence.domain.MemberAvailability;
import com.cadence.domain.Member;
import com.cadence.domain.Role;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** US1 (SC-001): a 5-member panel reads via the bounded fan-out; a Microsoft member is NOT_CONNECTED pre-F11. */
class CalendarPanelAvailabilityTest extends CalendarApiItBase {

    @Test
    void fivePersonPanel_allReturnData_withinBudget() {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Member m = member("p" + i + "@x.com", Role.INTERVIEWER);
            connect(m, CalendarProvider.GOOGLE, "p" + i + "@gmail.com");
            ids.add(m.getId());
        }
        Instant start = Instant.parse("2026-06-16T00:00:00Z");

        long t0 = System.nanoTime();
        List<MemberAvailability> r = availabilityService.query(WS, start, start.plus(2, ChronoUnit.DAYS), ids);
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

        assertThat(r).hasSize(5);
        assertThat(r).allMatch(a -> a.status() == AvailabilityStatus.DATA);
        assertThat(gcal.count("POST", "/freeBusy")).isEqualTo(5);
        assertThat(elapsedMs).as("5-person panel within 5s (SC-001)").isLessThan(5_000);
    }

    @Test
    void microsoftConnectedMember_isNotConnectedPreF11() {
        Member m = member("mo@x.com", Role.INTERVIEWER);
        connect(m, CalendarProvider.MICROSOFT, "mo@outlook.com"); // no F10 client for Microsoft yet
        Instant start = Instant.parse("2026-06-16T00:00:00Z");
        MemberAvailability a = availabilityService.query(WS, start, start.plus(1, ChronoUnit.DAYS), List.of(m.getId())).get(0);
        assertThat(a.status()).isEqualTo(AvailabilityStatus.NOT_CONNECTED);
    }
}
