package com.cadence.calendar;

import com.cadence.domain.EventDetails;
import com.cadence.domain.ManagedCalendarEvent;
import com.cadence.domain.Participant;
import com.cadence.repository.ManagedCalendarEventRepository;
import com.cadence.service.AvailabilityService;
import com.cadence.service.CalendarEventService;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Shared fixture for the F10 Google Calendar tests. Extends the F01.1 {@link CalendarItBase} (OAuth stub +
 * member/cookie helpers + production-path connect) and adds the {@link StubGoogleCalendar} for the
 * Calendar API, pointed at via {@code calendar.api.google.base-url}. Retry backoff is zeroed so retry
 * assertions add no wall-clock. Cleans {@code managedCalendarEvents} with remove (never dropCollection —
 * would drop the Mongock 007 indexes).
 */
abstract class CalendarApiItBase extends CalendarItBase {

    protected static final StubGoogleCalendar gcal = new StubGoogleCalendar();

    @DynamicPropertySource
    static void googleApiProps(DynamicPropertyRegistry r) {
        r.add("calendar.api.google.base-url", gcal::baseUrl);
        r.add("calendar.api.retry-base-backoff", () -> "PT0S");
    }

    @Autowired protected AvailabilityService availabilityService;
    @Autowired protected CalendarEventService eventService;
    @Autowired protected ManagedCalendarEventRepository managedEvents;

    @BeforeEach
    void cleanCalendarApi() {
        gcal.reset();
        mongoTemplate.remove(new Query(), ManagedCalendarEvent.class);
    }

    protected EventDetails details(String title, String location, Instant start, Instant end, ZoneId zone) {
        return new EventDetails(title, location, start, end, zone);
    }

    protected Participant participant(String memberId) {
        return new Participant(memberId, ZoneOffset.UTC);
    }

    protected List<Participant> panel(String... memberIds) {
        return java.util.Arrays.stream(memberIds).map(this::participant).toList();
    }
}
