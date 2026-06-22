package com.cadence.pipeline;

import com.cadence.domain.Candidate;
import com.cadence.domain.LawfulBasis;
import com.cadence.domain.Role;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F51 T020 / FR-012 / SC-004: the pipeline list role matrix (HM scoping itself is {@link PipelineHmScopingIT}) —
 * Admin/Recruiter/Read-only 200; Interviewer 403; bad enum 400 no-oracle; no-store; workspace isolation.
 */
class PipelineListContractTest extends PipelineItBase {

    @Test
    void recruiter_200_noStore() throws Exception {
        configuredWorkspace();
        seedActive("c1", "Ada", 1, null);
        var rec = member("rec@x.test", Role.RECRUITER);
        mvc.perform(get("/api/internal/pipeline").cookie(cookie(rec)))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
            .andExpect(jsonPath("$.rows[0].candidateId").value("c1"));
    }

    @Test
    void admin_and_readOnly_200() throws Exception {
        configuredWorkspace();
        seedActive("c1", "Ada", 1, null);
        var admin = member("admin@x.test", Role.ADMIN);
        var ro = member("ro@x.test", Role.READ_ONLY);
        mvc.perform(get("/api/internal/pipeline").cookie(cookie(admin))).andExpect(status().isOk());
        mvc.perform(get("/api/internal/pipeline").cookie(cookie(ro))).andExpect(status().isOk());
    }

    @Test
    void interviewer_403() throws Exception {
        configuredWorkspace();
        var iv = member("iv@x.test", Role.INTERVIEWER);
        mvc.perform(get("/api/internal/pipeline").cookie(cookie(iv))).andExpect(status().isForbidden());
    }

    @Test
    void badSort_400_noOracle() throws Exception {
        configuredWorkspace();
        var rec = member("rec@x.test", Role.RECRUITER);
        mvc.perform(get("/api/internal/pipeline").param("sort", "BOGUS").cookie(cookie(rec)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("invalid_request"))
            .andExpect(jsonPath("$.message").doesNotExist());
    }

    @Test
    void workspaceIsolation_onlyOwnWorkspace() throws Exception {
        configuredWorkspace();
        seedActive("c1", "Ada", 1, null);
        // A candidate in another workspace must never be counted/returned.
        Candidate foreign = new Candidate();
        foreign.setId("foreign");
        foreign.setWorkspaceId("ws2");
        foreign.setName("Zed");
        foreign.setEmail("zed@x.test");
        foreign.setLawfulBasis(LawfulBasis.CONSENT);
        foreign.setCreatedAt(NOW);
        foreign.setLastContactAt(NOW);
        mongoTemplate.save(foreign);

        var rec = member("rec@x.test", Role.RECRUITER);
        mvc.perform(get("/api/internal/pipeline").cookie(cookie(rec)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalInScope").value(1))
            .andExpect(jsonPath("$.rows.length()").value(1));
    }
}
