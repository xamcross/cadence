package com.cadence.calendar;

import com.cadence.domain.AuthEventType;
import com.cadence.domain.AvailabilityStatus;
import com.cadence.domain.CalendarProvider;
import com.cadence.domain.ConnectionStatus;
import com.cadence.domain.EventDetails;
import com.cadence.domain.MemberAvailability;
import com.cadence.domain.Member;
import com.cadence.domain.PanelBookingResult;
import com.cadence.domain.PanelBookingResult.MemberOutcome;
import com.cadence.domain.Role;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * US4 end-to-end resilience through the real RestClient -> classifier -> retry/reconnect wiring (not the
 * pure-unit path): 429-then-recover; insufficient-scope 403 and 401 flip NEEDS_RECONNECTION with no retry
 * (D9/B1); and a persistent-503 single create leaves NO partial row (FR-014).
 */
class CalendarApiResilienceIntegrationTest extends CalendarApiItBase {

    private EventDetails interview() {
        return details("Interview", "Room", Instant.parse("2026-06-20T15:00:00Z"),
            Instant.parse("2026-06-20T16:00:00Z"), ZoneOffset.UTC);
    }

    @Test
    void create_recoversAfter429s() {
        Member m = member("alex@x.com", Role.RECRUITER);
        connect(m, CalendarProvider.GOOGLE, "alex@gmail.com");
        gcal.program("POST", "/events", 429, 429, 201); // two throttles then success

        PanelBookingResult r = eventService.createPanelEvents(WS, "bk", panel(m.getId()), interview());

        assertThat(r.outcome()).isEqualTo(PanelBookingResult.PanelOutcome.CREATED);
        assertThat(gcal.count("POST", "/events")).isEqualTo(3); // 1 + 2 retries
    }

    @Test
    void create_insufficientScope403_flipsNeedsReconnection_noRetry() {
        Member m = member("nina@x.com", Role.RECRUITER);
        connect(m, CalendarProvider.GOOGLE, "nina@gmail.com");
        gcal.program("POST", "/events", "insufficientPermissions", 403); // stale freebusy-only grant (B1)

        PanelBookingResult r = eventService.createPanelEvents(WS, "bk", panel(m.getId()), interview());

        assertThat(r.perMember().get(0).outcome()).isEqualTo(MemberOutcome.NEEDS_RECONNECTION);
        assertThat(gcal.count("POST", "/events")).isEqualTo(1); // NOT retried
        assertThat(connectionRepo.findByWorkspaceIdAndMemberIdAndProvider(WS, m.getId(), CalendarProvider.GOOGLE)
            .orElseThrow().getStatus()).isEqualTo(ConnectionStatus.NEEDS_RECONNECTION);
        assertThat(reconnectAudits()).isGreaterThanOrEqualTo(1);
        assertThat(managedEvents.findByWorkspaceIdAndBookingRef(WS, "bk")).isEmpty(); // no row written
    }

    @Test
    void read_unauthorized401_flipsNeedsReconnection_noRetry() {
        Member m = member("ravi@x.com", Role.RECRUITER);
        connect(m, CalendarProvider.GOOGLE, "ravi@gmail.com");
        gcal.program("POST", "/freeBusy", 401);
        Instant start = Instant.parse("2026-06-16T00:00:00Z");

        MemberAvailability a = availabilityService
            .query(WS, start, start.plus(1, ChronoUnit.DAYS), List.of(m.getId())).get(0);

        assertThat(a.status()).isEqualTo(AvailabilityStatus.NEEDS_RECONNECTION);
        assertThat(gcal.count("POST", "/freeBusy")).isEqualTo(1); // NOT retried
        assertThat(connectionRepo.findByWorkspaceIdAndMemberIdAndProvider(WS, m.getId(), CalendarProvider.GOOGLE)
            .orElseThrow().getStatus()).isEqualTo(ConnectionStatus.NEEDS_RECONNECTION);
        assertThat(reconnectAudits()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void singleCreate_persistentTransient_leavesNoPartialRow() {
        Member m = member("tom@x.com", Role.RECRUITER);
        connect(m, CalendarProvider.GOOGLE, "tom@gmail.com");
        gcal.program("POST", "/events", 503); // persistent transient

        PanelBookingResult r = eventService.createPanelEvents(WS, "bk", panel(m.getId()), interview());

        assertThat(r.perMember().get(0).outcome()).isEqualTo(MemberOutcome.FAILED);
        assertThat(managedEvents.findByWorkspaceIdAndBookingRef(WS, "bk")).isEmpty(); // FR-014: no half-written row
        assertThat(gcal.liveEvents()).isEmpty();
    }

    private long reconnectAudits() {
        return mongoTemplate.count(Query.query(Criteria.where("eventType").is(AuthEventType.CALENDAR_RECONNECT_REQUIRED)),
            com.cadence.domain.AuthAuditEvent.class);
    }
}
