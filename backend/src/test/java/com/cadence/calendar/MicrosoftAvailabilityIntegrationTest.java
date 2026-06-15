package com.cadence.calendar;

import com.cadence.domain.AvailabilityStatus;
import com.cadence.domain.BusyInterval;
import com.cadence.domain.CalendarProvider;
import com.cadence.domain.Member;
import com.cadence.domain.MemberAvailability;
import com.cadence.domain.Role;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * US1 (SC-004/SC-010/FR-002a/FR-003/FR-004): getSchedule returns ONLY {start,end,status} though items carry
 * sentinel content; every Graph status maps fail-safe; exact non-grid boundaries + all-day/recurring spans;
 * not-connected / sub-only / needs-reconnection / transient -> DISTINCT statuses; UTC-forced reads (FR-003a);
 * 5-member panel fans out one getSchedule/member (SC-001) with a single schedules entry (no chunking).
 */
class MicrosoftAvailabilityIntegrationTest extends CalendarApiItBase {

    private static final String SENT_SUBJECT = "SENTINEL_MS_SUBJECT_zzz";
    private static final String SENT_LOC = "SENTINEL_MS_LOCATION_zzz";
    private static final Instant START = Instant.parse("2026-06-16T00:00:00Z");

    private Member connected(String email) {
        Member m = member(email, Role.RECRUITER);
        connect(m, CalendarProvider.MICROSOFT, email.replace("@x.com", "@contoso.com"));
        return m;
    }

    private MemberAvailability queryOne(Member m) {
        return availabilityService.query(WS, START, START.plus(2, ChronoUnit.DAYS), List.of(m.getId())).get(0);
    }

    @Test
    void getSchedule_returnsOnlyIntervals_neverContent() {
        Member m = connected("alex@x.com");
        Instant busyStart = Instant.parse("2026-06-16T13:00:00Z");
        Instant busyEnd = Instant.parse("2026-06-16T14:00:00Z");
        mscal.addItem(busyStart, busyEnd, "busy", SENT_SUBJECT, SENT_LOC);

        MemberAvailability r = queryOne(m);

        assertThat(r.status()).isEqualTo(AvailabilityStatus.DATA);
        assertThat(r.busy()).containsExactly(new BusyInterval(busyStart, busyEnd));
        // Non-circular: the sentinel subject/location existed in the getSchedule scheduleItems server-side;
        // the parse-discipline mapper (start/end/status only) must keep them out of our model.
        assertThat(r.toString()).doesNotContain(SENT_SUBJECT).doesNotContain(SENT_LOC);
    }

    @Test
    void everyStatusMapsFailSafe_onlyFreeIsSchedulable() {
        Member m = connected("stat@x.com");
        Instant s = Instant.parse("2026-06-16T13:00:00Z");
        Instant e = Instant.parse("2026-06-16T14:00:00Z");
        // free -> schedulable (no interval); everything else -> busy (FR-002a, fail safe).
        assertSchedulable(m, s, e, "free", true);
        for (String status : List.of("busy", "tentative", "oof", "workingElsewhere", "unknown")) {
            assertSchedulable(m, s, e, status, false);
        }
    }

    private void assertSchedulable(Member m, Instant s, Instant e, String status, boolean free) {
        mscal.reset();
        mscal.addItem(s, e, status, SENT_SUBJECT, SENT_LOC);
        MemberAvailability r = queryOne(m);
        assertThat(r.status()).isEqualTo(AvailabilityStatus.DATA);
        if (free) {
            assertThat(r.busy()).as("status=free is schedulable").isEmpty();
        } else {
            assertThat(r.busy()).as("status=%s must block (never silently free)", status)
                .containsExactly(new BusyInterval(s, e));
        }
    }

    @Test
    void nonGridBoundary_andMultiSpan_areExact() {
        Member m = connected("span@x.com");
        Instant a1 = Instant.parse("2026-06-16T09:10:00Z");
        Instant a2 = Instant.parse("2026-06-16T09:25:00Z"); // off-grid 09:10-09:25
        Instant allDayStart = Instant.parse("2026-06-17T00:00:00Z");
        Instant allDayEnd = Instant.parse("2026-06-18T00:00:00Z"); // all-day span
        Instant b1 = Instant.parse("2026-06-16T11:00:00Z");
        Instant b2 = Instant.parse("2026-06-16T11:30:00Z");
        mscal.addItem(a1, a2, "busy", null, null);
        mscal.addItem(allDayStart, allDayEnd, "oof", null, null);
        mscal.addItem(b1, b2, "busy", null, null);

        MemberAvailability r = availabilityService.query(WS, START, START.plus(3, ChronoUnit.DAYS), List.of(m.getId())).get(0);

        assertThat(r.busy()).containsExactlyInAnyOrder(
            new BusyInterval(a1, a2), new BusyInterval(allDayStart, allDayEnd), new BusyInterval(b1, b2));
    }

    @Test
    void notConnected_isNotConnected_neverFree() {
        Member m = member("nina@x.com", Role.INTERVIEWER); // no connection
        assertThat(queryOne(m).status()).isEqualTo(AvailabilityStatus.NOT_CONNECTED);
    }

    @Test
    void subOnlyAccount_isNeedsReconnection_notMalformedQuery() {
        Member m = member("ravi@x.com", Role.RECRUITER);
        connectSubOnly(m, CalendarProvider.MICROSOFT, "00000000-aaaa-bbbb-cccc-opaqueSub"); // no @, not a mailbox
        assertThat(queryOne(m).status()).isEqualTo(AvailabilityStatus.NEEDS_RECONNECTION);
        assertThat(mscal.count("POST", "/getSchedule")).as("no getSchedule with a non-mailbox").isZero();
    }

    @Test
    void revokedGrant_isNeedsReconnection() {
        Member m = connected("rev@x.com");
        tokenService.markNeedsReconnection(WS, m.getId(), CalendarProvider.MICROSOFT);
        assertThat(queryOne(m).status()).isEqualTo(AvailabilityStatus.NEEDS_RECONNECTION);
    }

    @Test
    void transientProviderError_isTemporarilyUnavailable() {
        Member m = connected("tom@x.com");
        mscal.program("POST", "/getSchedule", 503); // persistent transient
        assertThat(queryOne(m).status()).isEqualTo(AvailabilityStatus.TEMPORARILY_UNAVAILABLE);
    }

    @Test
    void emptyScheduleItems_isDataEmpty() {
        Member m = connected("ed@x.com"); // no items seeded -> value:[{scheduleItems:[]}]
        MemberAvailability r = queryOne(m);
        assertThat(r.status()).isEqualTo(AvailabilityStatus.DATA);
        assertThat(r.busy()).isEmpty();
    }

    @Test
    void emptyWindow_isDataEmpty_noProviderCall() {
        Member m = connected("zoe@x.com");
        MemberAvailability r = availabilityService.query(WS, START, START, List.of(m.getId())).get(0); // end == start
        assertThat(r.status()).isEqualTo(AvailabilityStatus.DATA);
        assertThat(r.busy()).isEmpty();
        assertThat(mscal.count("POST", "/getSchedule")).isZero();
    }

    @Test
    void readRequest_forcesUtc_andClampsOversizedWindow() {
        Member m = connected("ola@x.com");
        availabilityService.query(WS, START, START.plus(100, ChronoUnit.DAYS), List.of(m.getId()));
        String body = mscal.bodies("POST", "/getSchedule").get(0);
        assertThat(body).as("FR-003a — read times requested in UTC").contains("\"timeZone\":\"UTC\"");
        assertThat(body).as("window clamped to max-window (60d)").contains("2026-08-15");   // start + 60d
        assertThat(body).doesNotContain("2026-09-24");                                       // start + 100d
    }

    @Test
    void fivePersonPanel_oneGetScheduleEach_singleSchedulesEntry() {
        List<Member> panel = List.of(connected("p1@x.com"), connected("p2@x.com"), connected("p3@x.com"),
            connected("p4@x.com"), connected("p5@x.com"));
        List<String> ids = panel.stream().map(Member::getId).toList();

        List<MemberAvailability> r = availabilityService.query(WS, START, START.plus(2, ChronoUnit.DAYS), ids);

        assertThat(r).hasSize(5);
        assertThat(r).allMatch(a -> a.status() == AvailabilityStatus.DATA);
        assertThat(mscal.count("POST", "/getSchedule")).as("SC-001 — one getSchedule per member").isEqualTo(5);
        // No-chunking invariant: each request carries exactly one mailbox (so the ~20-mailbox cap never applies).
        for (String body : mscal.bodies("POST", "/getSchedule")) {
            assertThat(body).matches(".*\"schedules\":\\[\"[^\"]+\"\\].*");
        }
    }
}
