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
 * US2 (SC-005): an interview straddling a US spring-forward boundary is written with the correct LOCAL
 * wall-clock for BOTH instants (it differs across the boundary) + the IANA timeZone — asserted on the
 * RECORDED Graph request body (dateTimeTimeZone is local-time + zone, no offset), not an Instant round-trip.
 * Naive UTC conversion would write 06:00/13:00; correct NY-local is 01:00/09:00.
 */
class MicrosoftEventDstTest extends CalendarApiItBase {

    @Test
    void eventAcrossDstBoundary_writesLocalWallClockAndIanaZone() {
        Member m = member("alex@x.com", Role.RECRUITER);
        connect(m, CalendarProvider.MICROSOFT, "alex@contoso.com");

        ZoneId ny = ZoneId.of("America/New_York");
        // 2026-03-08: clocks spring forward 02:00 EST(-05:00) -> 03:00 EDT(-04:00).
        Instant start = ZonedDateTime.of(2026, 3, 8, 1, 0, 0, 0, ny).toInstant();  // 01:00 NY local
        Instant end = ZonedDateTime.of(2026, 3, 8, 9, 0, 0, 0, ny).toInstant();    // 09:00 NY local
        EventDetails details = new EventDetails("Interview", "HQ", start, end, ny);

        eventService.createPanelEvents(WS, "dst", List.of(new Participant(m.getId(), ny)), details);

        String body = mscal.bodies("POST", "/events").get(0);
        assertThat(body).contains("\"timeZone\":\"America/New_York\"");
        assertThat(body).contains("2026-03-08T01:00:00"); // start — NY local before the transition
        assertThat(body).contains("2026-03-08T09:00:00"); // end   — NY local after the transition
        assertThat(body).doesNotContain("2026-03-08T06:00"); // would be the naive-UTC bug
    }
}
