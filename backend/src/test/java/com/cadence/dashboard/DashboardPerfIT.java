package com.cadence.dashboard;

import com.cadence.api.DashboardWindow;
import com.cadence.domain.Candidate;
import com.cadence.domain.CandidateStatusOutcome;
import com.cadence.domain.ErasureState;
import com.cadence.domain.LawfulBasis;
import com.cadence.domain.SchedulingRequest;
import com.cadence.domain.SchedulingStatus;
import com.cadence.scheduler.NoShowDefenseScheduler;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F50 T034 (SC-008) — the dashboard read returns within budget for a realistic workspace (>=200 active
 * candidates + >=1000 booked requests), all aggregations index-backed. Tagged {@code perf} so it can be excluded
 * from the fast suite. One discarded warm-up read precedes the timed read; the bound is CI-safe (the target is 3s).
 */
@Tag("perf")
class DashboardPerfIT extends DashboardItBase {

    @Autowired
    NoShowDefenseScheduler noShowDefenseScheduler;

    @Test
    void read_under3sBudget_forLargeWorkspace() {
        configuredWorkspace();
        Instant past = NOW.minus(Duration.ofDays(5));

        List<Candidate> cands = new ArrayList<>();
        for (int i = 0; i < 250; i++) {
            Candidate c = new Candidate();
            c.setId("perf-c" + i);
            c.setWorkspaceId(WS);
            c.setName("Name" + i);
            c.setEmail("perf" + i + "@x.test");
            c.setLawfulBasis(LawfulBasis.CONSENT);
            c.setBasisRecordedAt(Instant.parse("2026-01-01T00:00:00Z"));
            c.setErasureState(ErasureState.ACTIVE);
            c.setStatusOutcome(CandidateStatusOutcome.IN_PROGRESS);
            c.setLastContactAt(NOW.minus(Duration.ofDays(10)));
            c.setCreatedAt(NOW.minus(Duration.ofDays(10)));
            cands.add(c);
        }
        mongoTemplate.insertAll(cands);

        List<SchedulingRequest> reqs = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            SchedulingRequest r = new SchedulingRequest();
            r.setId("perf-r" + i);
            r.setWorkspaceId(WS);
            r.setCandidateId("perf-c" + (i % 250));
            r.setStatus(SchedulingStatus.BOOKED);
            r.setTokenHash("perf-hash-" + i);
            r.setSentAt(past.minus(Duration.ofHours(3)));
            r.setBookedAt(past);
            r.setBookedStartAt(past);
            if (i % 5 == 0) {
                r.setNoShowAt(past);
            } else {
                // Attended interviews are confirmed ones. Also keeps the fixture out of the no-show
                // sweep's stage-3 candidate set (candidateConfirmedAt null would make these rows
                // stampable by the background tick, corrupting noShowCount mid-test).
                r.setCandidateConfirmedAt(past.minus(Duration.ofHours(1)));
            }
            r.setCreatedAt(past);
            reqs.add(r);
        }
        mongoTemplate.insertAll(reqs);

        // Force a no-show sweep pass between seeding and the timed read: the fixture must be immune
        // to the background NoShowDefenseScheduler tick (60s fixedDelay in the shared IT context),
        // which otherwise stamps up to a 200-row batch of overdue unconfirmed rows mid-test and
        // inflates noShowCount (the 2026-07-31 CI flake).
        noShowDefenseScheduler.sweep();

        dashboardService.snapshot(WS, DashboardWindow.LAST_90_DAYS); // warm-up (discarded)

        long start = System.nanoTime();
        var snap = dashboardService.snapshot(WS, DashboardWindow.LAST_90_DAYS);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        assertThat(snap.noShow().qualifyingCount()).isEqualTo(1000);
        assertThat(snap.noShow().noShowCount()).isEqualTo(200);
        // CI-safe margin over the 3s target.
        assertThat(elapsedMs).as("dashboard read elapsed ms").isLessThan(5000L);
    }
}
