package com.cadence.calendar;

import com.cadence.domain.AvailabilityStatus;
import com.cadence.domain.BusyInterval;
import com.cadence.domain.CalendarProvider;
import com.cadence.domain.EventDetails;
import com.cadence.domain.Member;
import com.cadence.domain.MemberAvailability;
import com.cadence.domain.PanelBookingResult;
import com.cadence.domain.PanelBookingResult.PanelOutcome;
import com.cadence.domain.Role;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * US4 (SC-009/SC-007): a mixed Google + Microsoft panel is read + booked through one provider-agnostic flow,
 * and a one-provider create failure rolls back the OTHER provider's event — both directions, zero orphans on
 * either stub. By construction: the provider map selects per member; the compensating-delete loop dispatches
 * per created entry's provider (no orchestration change).
 */
class MixedProviderPanelTest extends CalendarApiItBase {

    private static final Instant START = Instant.parse("2026-06-16T00:00:00Z");

    private EventDetails interview() {
        return details("Interview", "Room", Instant.parse("2026-06-20T15:00:00Z"),
            Instant.parse("2026-06-20T16:00:00Z"), ZoneOffset.UTC);
    }

    @Test
    void mixedPanel_availability_oneNormalisedSet() {
        Member g = member("g@x.com", Role.RECRUITER);
        Member ms = member("ms@x.com", Role.RECRUITER);
        connect(g, CalendarProvider.GOOGLE, "g@gmail.com");
        connect(ms, CalendarProvider.MICROSOFT, "ms@contoso.com");
        Instant gs = Instant.parse("2026-06-16T13:00:00Z");
        Instant ge = Instant.parse("2026-06-16T14:00:00Z");
        Instant mss = Instant.parse("2026-06-16T15:00:00Z");
        Instant mse = Instant.parse("2026-06-16T16:00:00Z");
        gcal.addBusy(gs, ge, "T", "a@x");
        mscal.addItem(mss, mse, "busy", "S", "L");

        List<MemberAvailability> r = availabilityService.query(WS, START, START.plus(2, ChronoUnit.DAYS),
            List.of(g.getId(), ms.getId()));

        assertThat(r).hasSize(2).allMatch(a -> a.status() == AvailabilityStatus.DATA);
        assertThat(byId(r, g.getId()).busy()).containsExactly(new BusyInterval(gs, ge));
        assertThat(byId(r, ms.getId()).busy()).containsExactly(new BusyInterval(mss, mse));
    }

    @Test
    void mixedPanel_booking_createsOnBoth() {
        Member g = member("g@x.com", Role.RECRUITER);
        Member ms = member("ms@x.com", Role.RECRUITER);
        connect(g, CalendarProvider.GOOGLE, "g@gmail.com");
        connect(ms, CalendarProvider.MICROSOFT, "ms@contoso.com");

        PanelBookingResult r = eventService.createPanelEvents(WS, "bk", panel(g.getId(), ms.getId()), interview());

        assertThat(r.outcome()).isEqualTo(PanelOutcome.CREATED);
        assertThat(gcal.liveEvents()).hasSize(1);
        assertThat(mscal.liveEvents()).hasSize(1);
    }

    @Test
    void microsoftFails_rollsBackGoogle_zeroOrphansEither() {
        Member g = member("g@x.com", Role.RECRUITER);
        Member ms = member("ms@x.com", Role.RECRUITER);
        connect(g, CalendarProvider.GOOGLE, "g@gmail.com");
        connect(ms, CalendarProvider.MICROSOFT, "ms@contoso.com");
        mscal.program("POST", "/events", 503); // Microsoft create persistently fails

        PanelBookingResult r = eventService.createPanelEvents(WS, "bk", panel(g.getId(), ms.getId()), interview());

        assertThat(r.outcome()).isEqualTo(PanelOutcome.ROLLED_BACK);
        assertThat(gcal.liveEvents()).as("Google event rolled back").isEmpty();
        assertThat(mscal.liveEvents()).as("no Microsoft orphan").isEmpty();
    }

    @Test
    void googleFails_rollsBackMicrosoft_zeroOrphansEither() {
        Member g = member("g@x.com", Role.RECRUITER);
        Member ms = member("ms@x.com", Role.RECRUITER);
        connect(g, CalendarProvider.GOOGLE, "g@gmail.com");
        connect(ms, CalendarProvider.MICROSOFT, "ms@contoso.com");
        gcal.program("POST", "/events", 503); // Google create persistently fails

        // Microsoft first, then Google -> Microsoft created, Google fails, Microsoft rolled back.
        PanelBookingResult r = eventService.createPanelEvents(WS, "bk", panel(ms.getId(), g.getId()), interview());

        assertThat(r.outcome()).isEqualTo(PanelOutcome.ROLLED_BACK);
        assertThat(mscal.liveEvents()).as("Microsoft event rolled back").isEmpty();
        assertThat(gcal.liveEvents()).as("no Google orphan").isEmpty();
    }

    private MemberAvailability byId(List<MemberAvailability> r, String id) {
        return r.stream().filter(a -> a.memberId().equals(id)).findFirst().orElseThrow();
    }
}
