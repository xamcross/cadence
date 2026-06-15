package com.cadence.calendar;

import com.cadence.domain.CalendarProvider;
import com.cadence.domain.EventDetails;
import com.cadence.domain.Member;
import com.cadence.domain.Participant;
import com.cadence.domain.Role;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * US2 (SC-005): an interview straddling a US spring-forward boundary is written with the correct UTC
 * offset for BOTH instants (it changes across the boundary) AND the IANA timeZone — asserted on the
 * RECORDED request body, not an Instant round-trip.
 */
class CalendarEventDstTest extends CalendarApiItBase {

    @Test
    void eventAcrossDstBoundary_writesCorrectOffsetsAndZone() {
        Member m = member("alex@x.com", Role.RECRUITER);
        connect(m, CalendarProvider.GOOGLE, "alex@gmail.com");

        ZoneId ny = ZoneId.of("America/New_York");
        // 2026-03-08: clocks spring forward 02:00 EST(-05:00) -> 03:00 EDT(-04:00).
        Instant start = ZonedDateTime.of(2026, 3, 8, 1, 0, 0, 0, ny).toInstant();  // 01:00 EST (-05:00)
        Instant end = ZonedDateTime.of(2026, 3, 8, 9, 0, 0, 0, ny).toInstant();    // 09:00 EDT (-04:00)
        EventDetails details = new EventDetails("Interview", "HQ", start, end, ny);

        eventService.createPanelEvents(WS, "dst", List.of(new Participant(m.getId(), ny)), details);

        String body = gcal.bodies("POST", "/events").get(0);
        assertThat(body).contains("\"timeZone\":\"America/New_York\"");
        assertThat(body).contains("-05:00"); // start, before the transition
        assertThat(body).contains("-04:00"); // end, after the transition
    }
}
