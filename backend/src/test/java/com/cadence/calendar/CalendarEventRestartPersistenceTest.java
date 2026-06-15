package com.cadence.calendar;

import com.cadence.domain.CalendarProvider;
import com.cadence.domain.ManagedCalendarEvent;
import com.cadence.domain.Member;
import com.cadence.domain.Role;
import com.cadence.integration.GoogleEventId;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/** US2: a managedCalendarEvents row survives a cold reload (no converter needed; references are plaintext). */
class CalendarEventRestartPersistenceTest extends CalendarApiItBase {

    @Test
    void managedEvent_readsBackAfterColdReload() {
        Member m = member("alex@x.com", Role.RECRUITER);
        connect(m, CalendarProvider.GOOGLE, "alex@gmail.com");
        eventService.createPanelEvents(WS, "bk", panel(m.getId()),
            details("Interview", "Room", Instant.parse("2026-06-20T15:00:00Z"),
                Instant.parse("2026-06-20T16:00:00Z"), ZoneOffset.UTC));

        MongoTemplate cold = coldTemplate();
        ManagedCalendarEvent row = cold.findOne(
            Query.query(Criteria.where("workspaceId").is(WS).and("bookingRef").is("bk")), ManagedCalendarEvent.class);

        assertThat(row).isNotNull();
        assertThat(row.getProviderEventId()).isEqualTo(GoogleEventId.of("bk", m.getId()));
        assertThat(row.getMemberId()).isEqualTo(m.getId());
    }
}
