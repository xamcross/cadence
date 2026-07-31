package com.cadence.calendar;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.cadence.domain.CalendarProvider;
import com.cadence.domain.EventDetails;
import com.cadence.domain.Member;
import com.cadence.domain.Role;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SC-003 / FR-017a/b/FR-018a: across preview + create + update + a retry-path failing call (cleanup
 * incomplete), NO sentinel from FIVE categories — event title, location, dial-in/phone, attendee email,
 * provider-account email — appears in any {@code com.cadence} log even at TRACE. Positive vacuity guard
 * proves the path actually logged.
 */
class CalendarEventLogPiiScanTest extends CalendarApiItBase {

    private static final String SENT_TITLE = "SENTINELTITLE_zz9";
    private static final String SENT_LOC = "SENTINELLOCATION_room42";
    private static final String SENT_DIALIN = "SENTINELDIALIN15550009999";
    private static final String SENT_ATTENDEE = "sentinel-attendee-7g@example.invalid";
    private static final String SENT_ACCOUNT = "sentinel-acct-3k@example.invalid";

    @Test
    void noEventContentOrEmail_appearsInLogs_atTrace() {
        Member a = member("a@x.com", Role.RECRUITER);
        Member b = member("b@x.com", Role.RECRUITER);
        connect(a, CalendarProvider.GOOGLE, SENT_ACCOUNT);
        connect(b, CalendarProvider.GOOGLE, "b@gmail.com");

        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        Logger cadence = (Logger) LoggerFactory.getLogger("com.cadence");
        Level previous = cadence.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        cadence.setLevel(Level.TRACE);
        try {
            EventDetails details = new EventDetails(SENT_TITLE, SENT_LOC + " " + SENT_DIALIN + " " + SENT_ATTENDEE,
                Instant.parse("2026-06-20T15:00:00Z"), Instant.parse("2026-06-20T16:00:00Z"), ZoneOffset.UTC);

            eventService.createPanelEvents(WS, "bk", panel(a.getId()), details);
            eventService.updatePanelEvents(WS, "bk", panel(a.getId()), details);
            availabilityService.previewSelf(WS, a.getId(), Instant.parse("2026-06-20T00:00:00Z"),
                Instant.parse("2026-06-27T00:00:00Z"));
            // retry-path failure -> cleanup incomplete (logs a warn with ids only).
            gcal.program("POST", "/events", 201, 503, 503, 503, 503);
            gcal.program("DELETE", "/events", 503);
            eventService.createPanelEvents(WS, "bk2", panel(a.getId(), b.getId()), details);

            boolean ranThePath = false;
            for (ILoggingEvent e : new java.util.ArrayList<>(appender.list)) { // snapshot: async threads may still append (CME guard)
                String line = e.getFormattedMessage();
                if (line == null) {
                    continue;
                }
                assertThat(line).doesNotContain(SENT_TITLE);
                assertThat(line).doesNotContain(SENT_LOC);
                assertThat(line).doesNotContain(SENT_DIALIN);
                assertThat(line).doesNotContain(SENT_ATTENDEE);
                assertThat(line).doesNotContain(SENT_ACCOUNT);
                if (line.contains("calendar event")) {
                    ranThePath = true;
                }
            }
            assertThat(ranThePath).as("the calendar event path actually logged (no vacuous pass)").isTrue();
        } finally {
            cadence.setLevel(previous);
            root.detachAppender(appender);
        }
    }
}
