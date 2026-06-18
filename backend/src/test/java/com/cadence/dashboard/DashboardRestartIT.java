package com.cadence.dashboard;

import com.cadence.api.DashboardDtos.DashboardSnapshot;
import com.cadence.api.DashboardWindow;
import com.cadence.domain.SchedulingRequest;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.query.Query;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F50 T033 (SC-010) — every figure derives from DURABLE records, not memory: two independent reads (the service
 * holds no in-memory state) over the persisted data return identical figures (the durability proxy; the
 * F23/F31 double-read precedent).
 */
class DashboardRestartIT extends DashboardItBase {

    @Test
    void figuresIdentical_acrossTwoReads_fromDurableRecords() {
        configuredWorkspace();
        Instant past = NOW.minus(Duration.ofDays(2));
        seedBooked("ns1", past, past, past, past);          // a no-show
        seedBooked("ns2", past, past, past, null);          // attended
        seedBooked("v1", past.minus(Duration.ofHours(4)), past, NOW.plus(Duration.ofDays(2)), null);
        seedSilent("c8", "Eight", 8);

        DashboardSnapshot a = dashboardService.snapshot(WS, DashboardWindow.LAST_30_DAYS);
        // Confirm the figures came from persisted rows, not memory.
        assertThat(mongoTemplate.count(new Query(), SchedulingRequest.class)).isEqualTo(3);

        DashboardSnapshot b = dashboardService.snapshot(WS, DashboardWindow.LAST_30_DAYS);
        assertThat(b.noShow()).isEqualTo(a.noShow());
        assertThat(b.timeToSchedule()).isEqualTo(a.timeToSchedule());
        assertThat(b.silenceList()).isEqualTo(a.silenceList());
    }
}
