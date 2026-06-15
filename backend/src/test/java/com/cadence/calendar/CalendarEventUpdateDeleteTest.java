package com.cadence.calendar;

import com.cadence.domain.CalendarProvider;
import com.cadence.domain.EventDetails;
import com.cadence.domain.EventStatus;
import com.cadence.domain.Member;
import com.cadence.domain.Role;
import com.cadence.integration.GoogleEventId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/** US3 (FR-011/SC-008): update is a PATCH on the SAME event id; delete is idempotent (404-gone -> ok). */
class CalendarEventUpdateDeleteTest extends CalendarApiItBase {

    private EventDetails at(String startIso, String endIso) {
        return details("Interview", "Room", Instant.parse(startIso), Instant.parse(endIso), ZoneOffset.UTC);
    }

    @Test
    void update_patchesSameEventIdInPlace() {
        Member m = member("alex@x.com", Role.RECRUITER);
        connect(m, CalendarProvider.GOOGLE, "alex@gmail.com");
        eventService.createPanelEvents(WS, "bk", panel(m.getId()), at("2026-06-20T15:00:00Z", "2026-06-20T16:00:00Z"));
        String eventId = GoogleEventId.of("bk", m.getId());

        eventService.updatePanelEvents(WS, "bk", panel(m.getId()), at("2026-06-21T10:00:00Z", "2026-06-21T11:00:00Z"));

        assertThat(gcal.count("PATCH", "/events/" + eventId)).isEqualTo(1); // in place, same id
        assertThat(gcal.count("POST", "/events")).isEqualTo(1);             // no new insert
    }

    @Test
    void cancel_deletesEvent() {
        Member m = member("nina@x.com", Role.RECRUITER);
        connect(m, CalendarProvider.GOOGLE, "nina@gmail.com");
        eventService.createPanelEvents(WS, "bk", panel(m.getId()), at("2026-06-20T15:00:00Z", "2026-06-20T16:00:00Z"));
        String eventId = GoogleEventId.of("bk", m.getId());

        eventService.cancelBooking(WS, "bk");

        assertThat(gcal.count("DELETE", "/events/" + eventId)).isEqualTo(1);
        assertThat(gcal.liveEvents()).isEmpty();
        assertThat(managedEvents.findByWorkspaceIdAndBookingRef(WS, "bk").get(0).getStatus())
            .isEqualTo(EventStatus.DELETED);
    }

    @Test
    void deleteOfGoneEvent_isSuccess() {
        Member m = member("ed@x.com", Role.RECRUITER);
        connect(m, CalendarProvider.GOOGLE, "ed@gmail.com");
        eventService.createPanelEvents(WS, "bk", panel(m.getId()), at("2026-06-20T15:00:00Z", "2026-06-20T16:00:00Z"));
        gcal.program("DELETE", "/events", 404); // provider says already gone

        assertThatCode(() -> eventService.cancelBooking(WS, "bk")).doesNotThrowAnyException();
        assertThat(managedEvents.findByWorkspaceIdAndBookingRef(WS, "bk").get(0).getStatus())
            .isEqualTo(EventStatus.DELETED);
    }

    @Test
    void updateOfGoneEvent_isSuccess() {
        Member m = member("ola@x.com", Role.RECRUITER);
        connect(m, CalendarProvider.GOOGLE, "ola@gmail.com");
        eventService.createPanelEvents(WS, "bk", panel(m.getId()), at("2026-06-20T15:00:00Z", "2026-06-20T16:00:00Z"));
        gcal.program("PATCH", "/events", 410); // gone

        assertThatCode(() -> eventService.updatePanelEvents(WS, "bk", panel(m.getId()),
            at("2026-06-21T10:00:00Z", "2026-06-21T11:00:00Z"))).doesNotThrowAnyException();
    }
}
