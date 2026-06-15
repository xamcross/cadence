package com.cadence.calendar;

import com.cadence.domain.AuthEventType;
import com.cadence.domain.CalendarProvider;
import com.cadence.domain.EventStatus;
import com.cadence.domain.ManagedCalendarEvent;
import com.cadence.domain.Member;
import com.cadence.domain.PanelBookingResult;
import com.cadence.domain.Role;
import com.cadence.integration.GoogleEventId;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/** US2 (SC-002/SC-008/D14): create persists a content-free row + one audit; idempotent; one provider call. */
class CalendarEventCreateIntegrationTest extends CalendarApiItBase {

    private com.cadence.domain.EventDetails interview() {
        return details("Interview: SENTINEL_TITLE", "Room SENTINEL_LOC",
            Instant.parse("2026-06-20T15:00:00Z"), Instant.parse("2026-06-20T16:00:00Z"), ZoneOffset.UTC);
    }

    @Test
    void create_persistsContentFreeRow_oneAudit_oneProviderCall() {
        Member m = member("alex@x.com", Role.RECRUITER);
        connect(m, CalendarProvider.GOOGLE, "alex@gmail.com");

        PanelBookingResult r = eventService.createPanelEvents(WS, "bk1", panel(m.getId()), interview());

        assertThat(r.outcome()).isEqualTo(PanelBookingResult.PanelOutcome.CREATED);
        String expectedId = GoogleEventId.of("bk1", m.getId());
        assertThat(r.perMember().get(0).outcome()).isEqualTo(PanelBookingResult.MemberOutcome.CREATED);
        assertThat(r.perMember().get(0).providerEventId()).isEqualTo(expectedId);

        ManagedCalendarEvent row = managedEvents
            .findByWorkspaceIdAndBookingRefAndMemberIdAndProvider(WS, "bk1", m.getId(), CalendarProvider.GOOGLE)
            .orElseThrow();
        assertThat(row.getStatus()).isEqualTo(EventStatus.CREATED);
        assertThat(row.getProviderEventId()).isEqualTo(expectedId);

        // SC-002 structural: exactly one provider insert per participant.
        assertThat(gcal.count("POST", "/events")).isEqualTo(1);

        // One CALENDAR_EVENT_CREATED audit; audit rows carry no content (internal ids only).
        assertThat(auditCount(AuthEventType.CALENDAR_EVENT_CREATED)).isEqualTo(1);

        // D14: raw doc holds refs + instants only — NO title/summary/location/token.
        Document raw = mongoTemplate.getCollection("managedCalendarEvents").find().first();
        assertThat(raw).isNotNull();
        assertThat(raw.keySet()).doesNotContain("title", "summary", "location", "refreshToken", "accessToken");
        assertThat(raw.toJson()).doesNotContain("SENTINEL_TITLE").doesNotContain("SENTINEL_LOC");
    }

    @Test
    void create_isIdempotent_sequential() {
        Member m = member("nina@x.com", Role.RECRUITER);
        connect(m, CalendarProvider.GOOGLE, "nina@gmail.com");

        eventService.createPanelEvents(WS, "bk2", panel(m.getId()), interview());
        eventService.createPanelEvents(WS, "bk2", panel(m.getId()), interview()); // retry

        assertThat(managedEvents.findByWorkspaceIdAndBookingRef(WS, "bk2")).hasSize(1);
        assertThat(gcal.count("POST", "/events")).isEqualTo(1); // second create claimed nothing
        assertThat(auditCount(AuthEventType.CALENDAR_EVENT_CREATED)).isEqualTo(1);
    }

    private long auditCount(AuthEventType type) {
        return mongoTemplate.count(Query.query(Criteria.where("eventType").is(type)),
            com.cadence.domain.AuthAuditEvent.class);
    }
}
