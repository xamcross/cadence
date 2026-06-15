package com.cadence.calendar;

import com.cadence.domain.AuthAuditEvent;
import com.cadence.domain.AuthEventType;
import com.cadence.domain.CalendarProvider;
import com.cadence.domain.EventStatus;
import com.cadence.domain.ManagedCalendarEvent;
import com.cadence.domain.Member;
import com.cadence.domain.PanelBookingResult;
import com.cadence.domain.Role;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * US2 (SC-002/SC-008/D13): Graph create reads back the SERVER-assigned id and persists it on a content-free
 * row + one audit; idempotent via transactionId; one provider call; the row survives a cold reload (T033).
 */
class MicrosoftEventCreateIntegrationTest extends CalendarApiItBase {

    private com.cadence.domain.EventDetails interview() {
        return details("Interview: SENTINEL_TITLE", "Room SENTINEL_LOC",
            Instant.parse("2026-06-20T15:00:00Z"), Instant.parse("2026-06-20T16:00:00Z"), ZoneOffset.UTC);
    }

    @Test
    void create_readsBackServerId_persistsContentFreeRow_oneAudit_oneCall() {
        Member m = member("alex@x.com", Role.RECRUITER);
        connect(m, CalendarProvider.MICROSOFT, "alex@contoso.com");

        PanelBookingResult r = eventService.createPanelEvents(WS, "bk1", panel(m.getId()), interview());

        assertThat(r.outcome()).isEqualTo(PanelBookingResult.PanelOutcome.CREATED);
        assertThat(r.perMember().get(0).outcome()).isEqualTo(PanelBookingResult.MemberOutcome.CREATED);

        // The stored providerEventId is the SERVER id read back from the create response (F11 D5).
        String serverId = mscal.liveEvents().iterator().next();
        assertThat(mscal.liveEvents()).hasSize(1);
        assertThat(r.perMember().get(0).providerEventId()).isEqualTo(serverId);

        ManagedCalendarEvent row = managedEvents
            .findByWorkspaceIdAndBookingRefAndMemberIdAndProvider(WS, "bk1", m.getId(), CalendarProvider.MICROSOFT)
            .orElseThrow();
        assertThat(row.getStatus()).isEqualTo(EventStatus.CREATED);
        assertThat(row.getProviderEventId()).isEqualTo(serverId);

        assertThat(mscal.count("POST", "/events")).as("SC-002 — one provider insert").isEqualTo(1);
        assertThat(auditCount(AuthEventType.CALENDAR_EVENT_CREATED)).isEqualTo(1);

        // Audit rows carry internal ids only — assert the persisted authAuditLog has NO event content.
        for (Document a : mongoTemplate.getCollection("authAuditLog").find()) {
            assertThat(a.toJson()).doesNotContain("SENTINEL_TITLE").doesNotContain("SENTINEL_LOC");
        }

        // D13: raw doc holds refs + instants only — NO subject/location/token.
        Document raw = mongoTemplate.getCollection("managedCalendarEvents").find().first();
        assertThat(raw).isNotNull();
        assertThat(raw.keySet()).doesNotContain("title", "subject", "location", "refreshToken", "accessToken");
        assertThat(raw.toJson()).doesNotContain("SENTINEL_TITLE").doesNotContain("SENTINEL_LOC");

        // T033: cold reload reads the row back (no converter needed; references are plaintext).
        MongoTemplate cold = coldTemplate();
        ManagedCalendarEvent coldRow = cold.findOne(
            Query.query(Criteria.where("workspaceId").is(WS).and("bookingRef").is("bk1")), ManagedCalendarEvent.class);
        assertThat(coldRow).isNotNull();
        assertThat(coldRow.getProviderEventId()).isEqualTo(serverId);
    }

    @Test
    void create_isIdempotent_sequential_viaTransactionId() {
        Member m = member("nina@x.com", Role.RECRUITER);
        connect(m, CalendarProvider.MICROSOFT, "nina@contoso.com");

        eventService.createPanelEvents(WS, "bk2", panel(m.getId()), interview());
        eventService.createPanelEvents(WS, "bk2", panel(m.getId()), interview()); // retry

        assertThat(managedEvents.findByWorkspaceIdAndBookingRef(WS, "bk2")).hasSize(1);
        assertThat(mscal.liveEvents()).hasSize(1);
        assertThat(mscal.count("POST", "/events")).isEqualTo(1); // 2nd create hit the fast-path, no provider call
        assertThat(auditCount(AuthEventType.CALENDAR_EVENT_CREATED)).isEqualTo(1);
    }

    private long auditCount(AuthEventType type) {
        return mongoTemplate.count(Query.query(Criteria.where("eventType").is(type)), AuthAuditEvent.class);
    }
}
