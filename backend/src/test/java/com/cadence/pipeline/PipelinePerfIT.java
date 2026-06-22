package com.cadence.pipeline;

import com.cadence.api.PipelineDtos.PipelinePage;
import com.cadence.api.PipelineDtos.PipelineSort;
import com.cadence.api.PipelineDtos.PipelineStatusFilter;
import com.cadence.domain.Role;
import com.cadence.service.PipelineService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F51 T048 / SC-002: a 200-active-candidate workspace returns the first page (size = pageSize) with SLA + scheduling
 * status composed in under 3s. Warm-up read discarded; CI-safe wall-clock margin (the DashboardPerfIT precedent).
 * {@code @Tag("perf")} so it is excluded from the default suite. Index-backing is asserted by PipelineIndexTest.
 */
@Tag("perf")
class PipelinePerfIT extends PipelineItBase {

    @Test
    void firstPageOf200Candidates_under3s() {
        configuredWorkspace();
        for (int i = 0; i < 200; i++) {
            seedActive("p" + i, "Cand" + i, (i % 8), null);
        }
        PipelineService.Filters f = new PipelineService.Filters(PipelineStatusFilter.ACTIVE, null, null, null, null);

        pipelineService.list(WS, "rec", Role.RECRUITER, f, PipelineSort.RECENT, 0, 50); // warm-up (discarded)

        long t0 = System.nanoTime();
        PipelinePage page = pipelineService.list(WS, "rec", Role.RECRUITER, f, PipelineSort.RECENT, 0, 50);
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

        assertThat(page.rows()).hasSize(50);          // first page = pageSize
        assertThat(page.totalInScope()).isEqualTo(200);
        assertThat(elapsedMs).isLessThan(3000);       // CI-safe margin against the <3s SC-002 target
    }
}
