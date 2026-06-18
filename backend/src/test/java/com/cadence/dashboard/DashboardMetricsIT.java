package com.cadence.dashboard;

import com.cadence.api.DashboardDtos.DashboardSnapshot;
import com.cadence.api.DashboardWindow;
import com.cadence.domain.SchedulingStatus;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F50 US1 (SC-003, FR-001/003/004/005/007) — time-to-schedule median + no-show rate correctness, pinned to a
 * single expected value (even-N HALF_UP 1dp; per-request counting; future-dated excluded; div-zero -> N/A).
 */
class DashboardMetricsIT extends DashboardItBase {

    /** Seed a BOOKED row confirmed {@code daysAgoBooked} ago taking {@code durationHours} to book. */
    private void seedVelocity(String id, double durationHours, long daysAgoBooked, Instant startAt) {
        Instant bookedAt = NOW.minus(Duration.ofDays(daysAgoBooked));
        Instant sentAt = bookedAt.minus(Duration.ofMinutes((long) (durationHours * 60)));
        seedBooked(id, sentAt, bookedAt, startAt, null);
    }

    @Test
    void median_evenN_isMeanOfTwoCentral_halfUpOneDecimal() {
        // durations 10,20,30,40h -> sorted -> even median (20+30)/2 = 25.0h. Start in the future so they do not
        // pollute the no-show denominator.
        Instant future = NOW.plus(Duration.ofDays(3));
        seedVelocity("v1", 10, 1, future);
        seedVelocity("v2", 20, 1, future);
        seedVelocity("v3", 30, 1, future);
        seedVelocity("v4", 40, 1, future);
        var tts = dashboardService.snapshot(WS, DashboardWindow.LAST_30_DAYS).timeToSchedule();
        assertThat(tts.hasData()).isTrue();
        assertThat(tts.sampleCount()).isEqualTo(4);
        assertThat(tts.medianHours()).isEqualTo(25.0);
    }

    @Test
    void median_oddN_isMiddle() {
        Instant future = NOW.plus(Duration.ofDays(3));
        seedVelocity("v1", 10, 1, future);
        seedVelocity("v2", 20, 1, future);
        seedVelocity("v3", 30, 1, future);
        var tts = dashboardService.snapshot(WS, DashboardWindow.LAST_30_DAYS).timeToSchedule();
        assertThat(tts.medianHours()).isEqualTo(20.0);
        assertThat(tts.sampleCount()).isEqualTo(3);
    }

    @Test
    void noShowRate_pastInterviewsOnly_withCounts() {
        Instant pastStart = NOW.minus(Duration.ofDays(2));
        Instant pastBooked = NOW.minus(Duration.ofDays(3));
        for (int i = 0; i < 10; i++) {
            seedBooked("ns" + i, pastBooked, pastBooked, pastStart, i < 2 ? pastStart : null);
        }
        var ns = dashboardService.snapshot(WS, DashboardWindow.LAST_30_DAYS).noShow();
        assertThat(ns.applicable()).isTrue();
        assertThat(ns.qualifyingCount()).isEqualTo(10);
        assertThat(ns.noShowCount()).isEqualTo(2);
        assertThat(ns.rate()).isEqualTo(0.2);
    }

    @Test
    void noShow_futureDated_excludedFromDenominator() {
        Instant past = NOW.minus(Duration.ofDays(2));
        Instant future = NOW.plus(Duration.ofDays(2));
        seedBooked("p1", past, past, past, null);
        seedBooked("p2", past, past, past, null);
        seedBooked("p3", past, past, past, null);
        seedBooked("f1", past, past, future, null);   // future start -> excluded
        seedBooked("f2", past, past, future, null);
        var ns = dashboardService.snapshot(WS, DashboardWindow.LAST_30_DAYS).noShow();
        assertThat(ns.qualifyingCount()).isEqualTo(3);
    }

    @Test
    void noShow_zeroDenominator_notApplicable_neverZeroPercent() {
        Instant future = NOW.plus(Duration.ofDays(2));
        seedBooked("f1", NOW.minus(Duration.ofDays(1)), NOW.minus(Duration.ofDays(1)), future, null);
        var ns = dashboardService.snapshot(WS, DashboardWindow.LAST_30_DAYS).noShow();
        assertThat(ns.applicable()).isFalse();
        assertThat(ns.rate()).isNull();
    }

    @Test
    void reschedule_notDoubleCounted_onlyLiveBookedRound() {
        Instant future = NOW.plus(Duration.ofDays(3));
        Instant bookedAt = NOW.minus(Duration.ofDays(1));
        // The superseded parent is RESCHEDULED; the live round is BOOKED. Only the BOOKED row contributes.
        seedStatus("parent", SchedulingStatus.RESCHEDULED, bookedAt, future);
        seedBooked("child", bookedAt.minus(Duration.ofHours(5)), bookedAt, future, null);
        var tts = dashboardService.snapshot(WS, DashboardWindow.LAST_30_DAYS).timeToSchedule();
        assertThat(tts.sampleCount()).isEqualTo(1);
    }

    @Test
    void twoRequestsSameCandidate_countedPerRequest() {
        Instant future = NOW.plus(Duration.ofDays(3));
        Instant b1 = NOW.minus(Duration.ofDays(2));
        Instant b2 = NOW.minus(Duration.ofDays(1));
        // Same candidateId, two distinct booked requests -> two samples (counting unit is the request, FR-003).
        seedBooked("cand-r1", b1.minus(Duration.ofHours(2)), b1, future, null);
        seedBooked("cand-r2", b2.minus(Duration.ofHours(2)), b2, future, null);
        var tts = dashboardService.snapshot(WS, DashboardWindow.LAST_30_DAYS).timeToSchedule();
        assertThat(tts.sampleCount()).isEqualTo(2);
    }

    @Test
    void emptyWorkspace_allPanelsEmpty_noError() {
        DashboardSnapshot snap = dashboardService.snapshot(WS, DashboardWindow.LAST_30_DAYS);
        assertThat(snap.timeToSchedule().hasData()).isFalse();
        assertThat(snap.noShow().applicable()).isFalse();
        assertThat(snap.silenceList()).isEmpty();
    }

    @Test
    void windowPredatesFirstActivity_cleanEmptyNotError() {
        // Activity 20 days ago; a LAST_7_DAYS window excludes it -> empty metrics, no error.
        Instant old = NOW.minus(Duration.ofDays(20));
        seedBooked("o1", old.minus(Duration.ofHours(2)), old, old, null);
        DashboardSnapshot snap = dashboardService.snapshot(WS, DashboardWindow.LAST_7_DAYS);
        assertThat(snap.timeToSchedule().hasData()).isFalse();
        assertThat(snap.noShow().applicable()).isFalse();
    }
}
