package com.cadence.pipeline;

import com.cadence.api.PipelineDtos.PipelinePage;
import com.cadence.api.PipelineDtos.PipelineRow;
import com.cadence.api.PipelineDtos.PipelineSort;
import com.cadence.api.PipelineDtos.PipelineStatusFilter;
import com.cadence.domain.Candidate;
import com.cadence.domain.CandidateAuditOutcome;
import com.cadence.domain.Member;
import com.cadence.domain.RequisitionStatus;
import com.cadence.domain.Role;
import com.cadence.domain.SlaState;
import com.cadence.service.CandidateErasureService;
import com.cadence.service.PipelineService;
import com.cadence.service.SlaNudgeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F51 T047 / SC-009: erasure interaction. {@code requisitionId} is a non-PII anchor RETAINED on erasure (no wipe
 * change); the erased candidate appears in NO role's pipeline (incl. an HM assigned to its requisition), enforced by
 * the active-state predicate, not by clearing the link.
 */
class PipelineErasureRegressionIT extends PipelineItBase {

    @Autowired CandidateErasureService erasure;
    @Autowired SlaNudgeService sla;

    private static final PipelineService.Filters ACTIVE_ALL =
        new PipelineService.Filters(PipelineStatusFilter.ACTIVE, null, null, null, null);

    @Test
    void requisitionIdRetainedOnErasure_andErasedAbsentFromAllRoles() {
        configuredWorkspace();
        seedRequisition("r1", "R1", RequisitionStatus.OPEN);
        Member hm = member("hm@x.test", Role.HIRING_MANAGER);
        seedAssignment(hm.getId(), "r1");
        seedActive("c1", "Ada", 1, "r1");

        boolean wiped = erasure.wipe(WS, "c1", CandidateAuditOutcome.OPERATOR, "admin");
        assertThat(wiped).isTrue();

        // requisitionId retained (non-PII anchor); name wiped to the marker.
        Candidate after = mongoTemplate.findById("c1", Candidate.class);
        assertThat(after).isNotNull();
        assertThat(after.getRequisitionId()).isEqualTo("r1");
        assertThat(after.getName()).isEqualTo("[ERASED]");

        // Absent from the recruiter (workspace-wide) AND the assigned HM pipeline.
        PipelinePage rec = pipelineService.list(WS, "rec", Role.RECRUITER, ACTIVE_ALL, PipelineSort.RECENT, 0, 50);
        assertThat(rec.rows()).extracting(PipelineRow::candidateId).doesNotContain("c1");
        PipelinePage hmPage = pipelineService.list(WS, hm.getId(), Role.HIRING_MANAGER, ACTIVE_ALL,
            PipelineSort.RECENT, 0, 50);
        assertThat(hmPage.rows()).isEmpty();
    }

    @Test
    void newRequisitionIdField_doesNotAlterExistingSlaRead() {
        // SC-009 regression: an active candidate carrying the new requisitionId still classifies correctly via the
        // existing F31 SLA read path (the additive field does not perturb existing candidate reads).
        configuredWorkspace();
        seedRequisition("r1", "R1", RequisitionStatus.OPEN);
        seedActive("c1", "Ada", 10, "r1");   // silent 10d, window 5 -> RED
        assertThat(sla.candidateSla(WS, "c1").slaState()).isEqualTo(SlaState.RED);
    }
}
