package com.cadence.pipeline;

import com.cadence.api.PipelineDtos.PipelinePage;
import com.cadence.api.PipelineDtos.PipelineRow;
import com.cadence.api.PipelineDtos.PipelineSchedulingStatus;
import com.cadence.api.PipelineDtos.PipelineSort;
import com.cadence.api.PipelineDtos.PipelineStatusFilter;
import com.cadence.domain.CandidateStatusOutcome;
import com.cadence.domain.ErasureState;
import com.cadence.domain.Role;
import com.cadence.domain.SchedulingStatus;
import com.cadence.domain.SlaState;
import com.cadence.service.PipelineService;
import com.cadence.service.SlaNudgeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F51 T021 / SC-001 / SC-009: the pipeline compose — SLA colour equals the SlaNudgeService verdict (FR-004), the
 * scheduling-status mapping, the no-stage label, erased exclusion, and the terminal/include-closed filter.
 */
class PipelineComposeIT extends PipelineItBase {

    @Autowired SlaNudgeService sla;

    private static final PipelineService.Filters ACTIVE_ALL =
        new PipelineService.Filters(PipelineStatusFilter.ACTIVE, null, null, null, null);

    private PipelinePage listAsRecruiter(PipelineService.Filters f) {
        return pipelineService.list(WS, "rec", Role.RECRUITER, f, PipelineSort.RECENT, 0, 50);
    }

    private static PipelineRow row(PipelinePage p, String candidateId) {
        return p.rows().stream().filter(r -> r.candidateId().equals(candidateId)).findFirst().orElse(null);
    }

    @Test
    void slaColourEqualsNudgeEngineVerdict() {
        configuredWorkspace();
        seedActive("c1", "Ada", 10, null);  // silent 10d, window 5 -> RED
        seedActive("c2", "Bea", 1, null);   // within window -> GREEN
        PipelinePage page = listAsRecruiter(ACTIVE_ALL);
        assertThat(row(page, "c1").slaState()).isEqualTo(sla.candidateSla(WS, "c1").slaState()).isEqualTo(SlaState.RED);
        assertThat(row(page, "c2").slaState()).isEqualTo(sla.candidateSla(WS, "c2").slaState()).isEqualTo(SlaState.GREEN);
    }

    @Test
    void schedulingStatusMapping() {
        configuredWorkspace();
        seedActive("c1", "Ada", 1, null);
        seedScheduling("c1", SchedulingStatus.BOOKED, NOW.minus(Duration.ofDays(2)), null,
            NOW.plus(Duration.ofDays(1)), null);
        seedActive("c2", "Bea", 1, null);
        seedScheduling("c2", SchedulingStatus.BOOKED, NOW.minus(Duration.ofDays(2)), null,
            NOW.minus(Duration.ofHours(1)), NOW); // no-show
        seedActive("c3", "Cam", 1, null);          // no scheduling row
        PipelinePage page = listAsRecruiter(ACTIVE_ALL);
        assertThat(row(page, "c1").schedulingStatus()).isEqualTo(PipelineSchedulingStatus.CONFIRMED);
        assertThat(row(page, "c2").schedulingStatus()).isEqualTo(PipelineSchedulingStatus.NO_SHOW);
        assertThat(row(page, "c3").schedulingStatus()).isEqualTo(PipelineSchedulingStatus.NO_LINK_SENT);
    }

    @Test
    void liveBookedWins_overNewerRescheduledRow() {
        configuredWorkspace();
        seedActive("c1", "Ada", 1, null);
        // A live BOOKED row (older createdAt) and a RESCHEDULED row (newer createdAt) for the same candidate:
        // preferLiveBookedElseNewest must pick the BOOKED -> CONFIRMED regardless of recency (FR-005 lineage).
        seedScheduling("c1", SchedulingStatus.BOOKED, NOW.minus(Duration.ofDays(5)), null,
            NOW.plus(Duration.ofDays(1)), null);
        seedScheduling("c1", SchedulingStatus.RESCHEDULED, NOW.minus(Duration.ofDays(1)), null, null, null);
        assertThat(row(listAsRecruiter(ACTIVE_ALL), "c1").schedulingStatus())
            .isEqualTo(PipelineSchedulingStatus.CONFIRMED);
    }

    @Test
    void supersededOnly_mapsToNoLinkSent() {
        configuredWorkspace();
        seedActive("c1", "Ada", 1, null);
        seedScheduling("c1", SchedulingStatus.SUPERSEDED, NOW.minus(Duration.ofDays(2)), null, null, null);
        assertThat(row(listAsRecruiter(ACTIVE_ALL), "c1").schedulingStatus())
            .isEqualTo(PipelineSchedulingStatus.NO_LINK_SENT);
    }

    @Test
    void noStageCandidate_showsNotStarted() {
        configuredWorkspace();
        seedCandidate("c1", "Ada", null, NOW.minus(Duration.ofDays(1)),
            CandidateStatusOutcome.IN_PROGRESS, ErasureState.ACTIVE, null);
        assertThat(row(listAsRecruiter(ACTIVE_ALL), "c1").stage()).isEqualTo("Not started");
    }

    @Test
    void erasedCandidate_absent() {
        configuredWorkspace();
        seedCandidate("c1", "Ada", "Screening", NOW.minus(Duration.ofDays(1)),
            CandidateStatusOutcome.IN_PROGRESS, ErasureState.ERASED, null);
        assertThat(row(listAsRecruiter(ACTIVE_ALL), "c1")).isNull();
    }

    @Test
    void terminalCandidate_excludedByDefault_includedWithIncludeClosed() {
        configuredWorkspace();
        seedCandidate("c1", "Ada", "Offer", NOW.minus(Duration.ofDays(1)),
            CandidateStatusOutcome.COMPLETE_OFFER, ErasureState.ACTIVE, null);
        assertThat(row(listAsRecruiter(ACTIVE_ALL), "c1")).isNull();
        PipelineService.Filters includeClosed =
            new PipelineService.Filters(PipelineStatusFilter.INCLUDE_CLOSED, null, null, null, null);
        assertThat(row(listAsRecruiter(includeClosed), "c1")).isNotNull();
    }

    @Test
    void slaColourRefreshesOnClockAdvance() {
        configuredWorkspace();
        seedActive("c1", "Ada", 4, null);   // silent 4d, window 5 -> GREEN now
        assertThat(row(listAsRecruiter(ACTIVE_ALL), "c1").slaState()).isEqualTo(SlaState.GREEN);
        clock.set(NOW.plus(Duration.ofDays(2)));   // now silent 6d -> RED on the next read (FR-006/SC-003)
        assertThat(row(listAsRecruiter(ACTIVE_ALL), "c1").slaState()).isEqualTo(SlaState.RED);
    }

    @Test
    void redSlaFilter_returnsCompleteBreachingSet() {
        configuredWorkspace();
        seedActive("c1", "Ada", 10, null); // RED
        seedActive("c2", "Bea", 9, null);  // RED
        seedActive("c3", "Cam", 1, null);  // GREEN
        PipelineService.Filters redOnly =
            new PipelineService.Filters(PipelineStatusFilter.ACTIVE, null, SlaState.RED, null, null);
        PipelinePage page = listAsRecruiter(redOnly);
        assertThat(page.rows()).extracting(PipelineRow::candidateId).containsExactlyInAnyOrder("c1", "c2");
        assertThat(page.truncated()).isFalse();
    }
}
