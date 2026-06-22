package com.cadence.pipeline;

import com.cadence.api.PipelineDtos.PipelinePage;
import com.cadence.api.PipelineDtos.PipelineRow;
import com.cadence.api.PipelineDtos.PipelineSort;
import com.cadence.api.PipelineDtos.PipelineStatusFilter;
import com.cadence.domain.Member;
import com.cadence.domain.RequisitionStatus;
import com.cadence.domain.Role;
import com.cadence.service.PipelineService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F51 T030 / SC-004 / FR-013/FR-014: Hiring-Manager visibility is scoped server-side to assigned requisitions —
 * unassigned candidates are invisible, an empty assignment set yields an empty page (never unfiltered), and a
 * link move / requisition close flips visibility.
 */
class PipelineHmScopingIT extends PipelineItBase {

    private static final PipelineService.Filters ACTIVE_ALL =
        new PipelineService.Filters(PipelineStatusFilter.ACTIVE, null, null, null, null);

    private PipelinePage listAsHm(String memberId) {
        return pipelineService.list(WS, memberId, Role.HIRING_MANAGER, ACTIVE_ALL, PipelineSort.RECENT, 0, 50);
    }

    @Test
    void hmSeesOnlyAssignedRequisitionCandidates() {
        configuredWorkspace();
        seedRequisition("r1", "R1", RequisitionStatus.OPEN);
        seedRequisition("r2", "R2", RequisitionStatus.OPEN);
        Member hm = member("hm@x.test", Role.HIRING_MANAGER);
        seedAssignment(hm.getId(), "r1");
        seedActive("c1", "Ada", 1, "r1");
        seedActive("c2", "Bea", 1, "r2");   // other requisition
        seedActive("c3", "Cam", 1, null);   // unassigned

        PipelinePage page = listAsHm(hm.getId());
        assertThat(page.rows()).extracting(PipelineRow::candidateId).containsExactly("c1");
    }

    @Test
    void hmWithNoAssignments_emptyPage() {
        configuredWorkspace();
        seedRequisition("r1", "R1", RequisitionStatus.OPEN);
        seedActive("c1", "Ada", 1, "r1");
        Member hm = member("hm@x.test", Role.HIRING_MANAGER);

        PipelinePage page = listAsHm(hm.getId());
        assertThat(page.rows()).isEmpty();
        assertThat(page.totalInScope()).isZero();
    }

    @Test
    void linkMove_flipsVisibility() {
        configuredWorkspace();
        seedRequisition("r1", "R1", RequisitionStatus.OPEN);
        seedRequisition("r2", "R2", RequisitionStatus.OPEN);
        Member hm = member("hm@x.test", Role.HIRING_MANAGER);
        seedAssignment(hm.getId(), "r1");
        seedActive("c1", "Ada", 1, "r1");

        assertThat(listAsHm(hm.getId()).rows()).extracting(PipelineRow::candidateId).containsExactly("c1");
        requisitionService.linkCandidate(WS, "admin", "c1", "r2");   // move off r1
        assertThat(listAsHm(hm.getId()).rows()).isEmpty();
    }

    @Test
    void hm_outOfScopeRequisitionFilter_emptyRows_noOracle_viaController() throws Exception {
        configuredWorkspace();
        seedRequisition("r1", "R1", RequisitionStatus.OPEN);
        seedRequisition("r2", "R2", RequisitionStatus.OPEN);
        Member hm = member("hm@x.test", Role.HIRING_MANAGER);
        seedAssignment(hm.getId(), "r1");
        seedActive("c1", "Ada", 1, "r1");
        seedActive("c2", "Bea", 1, "r2");

        // HM is in the list matrix at 200, scoped to assigned requisitions.
        mvc.perform(get("/api/internal/pipeline").cookie(cookie(hm)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.rows.length()").value(1))
            .andExpect(jsonPath("$.rows[0].candidateId").value("c1"));
        // Filtering by a NOT-assigned requisition yields empty rows, never a disclosing 404 (contract §1).
        mvc.perform(get("/api/internal/pipeline").param("requisitionId", "r2").cookie(cookie(hm)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.rows.length()").value(0));
    }

    @Test
    void closedRequisition_droppedFromDefaultView() {
        configuredWorkspace();
        seedRequisition("r1", "R1", RequisitionStatus.CLOSED);
        Member hm = member("hm@x.test", Role.HIRING_MANAGER);
        seedAssignment(hm.getId(), "r1");
        seedActive("c1", "Ada", 1, "r1");

        assertThat(listAsHm(hm.getId()).rows()).isEmpty();      // terminal (closed req) excluded by default
        PipelinePage withClosed = pipelineService.list(WS, hm.getId(), Role.HIRING_MANAGER,
            new PipelineService.Filters(PipelineStatusFilter.INCLUDE_CLOSED, null, null, null, null),
            PipelineSort.RECENT, 0, 50);
        assertThat(withClosed.rows()).extracting(PipelineRow::candidateId).containsExactly("c1");
    }
}
