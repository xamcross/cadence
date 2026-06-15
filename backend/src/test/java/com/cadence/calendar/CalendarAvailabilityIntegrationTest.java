package com.cadence.calendar;

import com.cadence.domain.AvailabilityStatus;
import com.cadence.domain.CalendarProvider;
import com.cadence.domain.MemberAvailability;
import com.cadence.domain.Member;
import com.cadence.domain.Role;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * US1 (SC-004, FR-004): free/busy returns ONLY intervals though the stub event holds sentinel content;
 * not-connected / needs-reconnection / transient map to DISTINCT statuses; empty + oversized windows.
 */
class CalendarAvailabilityIntegrationTest extends CalendarApiItBase {

    private static final String SENT_TITLE = "SENTINEL_MEETING_TITLE_zzz";
    private static final String SENT_ATTENDEE = "sentinel-attendee@example.invalid";

    @Test
    void freeBusy_returnsOnlyIntervals_neverContent() {
        Member m = member("alex@x.com", Role.RECRUITER);
        connect(m, CalendarProvider.GOOGLE, "alex@gmail.com");
        Instant start = Instant.parse("2026-06-16T00:00:00Z");
        Instant busyStart = Instant.parse("2026-06-16T13:00:00Z");
        Instant busyEnd = Instant.parse("2026-06-16T14:00:00Z");
        gcal.addBusy(busyStart, busyEnd, SENT_TITLE, SENT_ATTENDEE);

        List<MemberAvailability> r = availabilityService.query(WS, start, start.plus(2, ChronoUnit.DAYS), List.of(m.getId()));

        assertThat(r).hasSize(1);
        assertThat(r.get(0).status()).isEqualTo(AvailabilityStatus.DATA);
        assertThat(r.get(0).busy()).containsExactly(new com.cadence.domain.BusyInterval(busyStart, busyEnd));
        // Non-circular: the sentinel content existed server-side; it must not surface in our model.
        assertThat(r.get(0).toString()).doesNotContain(SENT_TITLE).doesNotContain(SENT_ATTENDEE);
    }

    @Test
    void notConnectedMember_isNotConnected_neverFree() {
        Member m = member("nina@x.com", Role.INTERVIEWER); // no connection
        Instant start = Instant.parse("2026-06-16T00:00:00Z");
        MemberAvailability a = availabilityService.query(WS, start, start.plus(1, ChronoUnit.DAYS), List.of(m.getId())).get(0);
        assertThat(a.status()).isEqualTo(AvailabilityStatus.NOT_CONNECTED);
    }

    @Test
    void revokedGrant_isNeedsReconnection() {
        Member m = member("ravi@x.com", Role.RECRUITER);
        connect(m, CalendarProvider.GOOGLE, "ravi@gmail.com");
        tokenService.markNeedsReconnection(WS, m.getId(), CalendarProvider.GOOGLE);
        Instant start = Instant.parse("2026-06-16T00:00:00Z");
        MemberAvailability a = availabilityService.query(WS, start, start.plus(1, ChronoUnit.DAYS), List.of(m.getId())).get(0);
        assertThat(a.status()).isEqualTo(AvailabilityStatus.NEEDS_RECONNECTION);
    }

    @Test
    void transientProviderError_isTemporarilyUnavailable() {
        Member m = member("tom@x.com", Role.RECRUITER);
        connect(m, CalendarProvider.GOOGLE, "tom@gmail.com");
        gcal.program("POST", "/freeBusy", 503); // persistent transient
        Instant start = Instant.parse("2026-06-16T00:00:00Z");
        MemberAvailability a = availabilityService.query(WS, start, start.plus(1, ChronoUnit.DAYS), List.of(m.getId())).get(0);
        assertThat(a.status()).isEqualTo(AvailabilityStatus.TEMPORARILY_UNAVAILABLE);
    }

    @Test
    void emptyWindow_isDataEmpty_notError() {
        Member m = member("ed@x.com", Role.RECRUITER);
        connect(m, CalendarProvider.GOOGLE, "ed@gmail.com");
        Instant t = Instant.parse("2026-06-16T00:00:00Z");
        MemberAvailability a = availabilityService.query(WS, t, t, List.of(m.getId())).get(0); // end == start
        assertThat(a.status()).isEqualTo(AvailabilityStatus.DATA);
        assertThat(a.busy()).isEmpty();
        assertThat(gcal.count("POST", "/freeBusy")).isZero(); // short-circuited, no provider call
    }

    @Test
    void oversizedWindow_isClampedToMaxWindow() {
        Member m = member("ola@x.com", Role.RECRUITER);
        connect(m, CalendarProvider.GOOGLE, "ola@gmail.com");
        Instant start = Instant.parse("2026-06-16T00:00:00Z");
        availabilityService.query(WS, start, start.plus(100, ChronoUnit.DAYS), List.of(m.getId()));
        // max-window is 60d (default) -> the freeBusy timeMax is clamped to start+60d, not start+100d.
        String body = gcal.bodies("POST", "/freeBusy").get(0);
        assertThat(body).contains(start.plus(60, ChronoUnit.DAYS).toString());
        assertThat(body).doesNotContain(start.plus(100, ChronoUnit.DAYS).toString());
    }
}
