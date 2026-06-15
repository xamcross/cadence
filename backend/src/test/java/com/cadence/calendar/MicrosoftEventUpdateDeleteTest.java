package com.cadence.calendar;

import com.cadence.domain.CalendarProvider;
import com.cadence.domain.EventDetails;
import com.cadence.domain.EventStatus;
import com.cadence.domain.Member;
import com.cadence.domain.Role;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/** US3 (FR-011/SC-008): update is a PATCH on the STORED server id; delete is idempotent (404/410-gone -> ok). */
class MicrosoftEventUpdateDeleteTest extends CalendarApiItBase {

    private EventDetails at(String startIso, String endIso) {
        return details("Interview", "Room", Instant.parse(startIso), Instant.parse(endIso), ZoneOffset.UTC);
    }

    private String createAndGetId(Member m, String bk) {
        eventService.createPanelEvents(WS, bk, panel(m.getId()), at("2026-06-20T15:00:00Z", "2026-06-20T16:00:00Z"));
        return managedEvents.findByWorkspaceIdAndBookingRefAndMemberIdAndProvider(WS, bk, m.getId(), CalendarProvider.MICROSOFT)
            .orElseThrow().getProviderEventId();
    }

    @Test
    void update_patchesStoredServerIdInPlace() {
        Member m = member("alex@x.com", Role.RECRUITER);
        connect(m, CalendarProvider.MICROSOFT, "alex@contoso.com");
        String eventId = createAndGetId(m, "bk");

        eventService.updatePanelEvents(WS, "bk", panel(m.getId()), at("2026-06-21T10:00:00Z", "2026-06-21T11:00:00Z"));

        assertThat(mscal.count("PATCH", "/events/" + eventId)).isEqualTo(1); // in place, the stored id
        assertThat(mscal.count("POST", "/events")).isEqualTo(1);             // no new insert
    }

    @Test
    void cancel_deletesEvent() {
        Member m = member("nina@x.com", Role.RECRUITER);
        connect(m, CalendarProvider.MICROSOFT, "nina@contoso.com");
        String eventId = createAndGetId(m, "bk");

        eventService.cancelBooking(WS, "bk");

        assertThat(mscal.count("DELETE", "/events/" + eventId)).isEqualTo(1);
        assertThat(mscal.liveEvents()).isEmpty();
        assertThat(managedEvents.findByWorkspaceIdAndBookingRef(WS, "bk").get(0).getStatus())
            .isEqualTo(EventStatus.DELETED);
    }

    @Test
    void deleteOfGoneEvent_isSuccess() {
        Member m = member("ed@x.com", Role.RECRUITER);
        connect(m, CalendarProvider.MICROSOFT, "ed@contoso.com");
        createAndGetId(m, "bk");
        mscal.program("DELETE", "/events", 404); // already gone

        assertThatCode(() -> eventService.cancelBooking(WS, "bk")).doesNotThrowAnyException();
        assertThat(managedEvents.findByWorkspaceIdAndBookingRef(WS, "bk").get(0).getStatus())
            .isEqualTo(EventStatus.DELETED);
    }

    @Test
    void updateOfGoneEvent_isSuccess() {
        Member m = member("ola@x.com", Role.RECRUITER);
        connect(m, CalendarProvider.MICROSOFT, "ola@contoso.com");
        createAndGetId(m, "bk");
        mscal.program("PATCH", "/events", 410); // gone

        assertThatCode(() -> eventService.updatePanelEvents(WS, "bk", panel(m.getId()),
            at("2026-06-21T10:00:00Z", "2026-06-21T11:00:00Z"))).doesNotThrowAnyException();
    }
}
