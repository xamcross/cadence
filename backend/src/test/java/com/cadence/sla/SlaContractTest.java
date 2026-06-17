package com.cadence.sla;

import com.cadence.domain.Role;
import com.cadence.scheduler.SlaNudgeScheduler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F31 T018/T029 contract: the recruiter SLA endpoints — 5-role matrix, no-store, and the no-existence-oracle 404
 * (a cross-workspace / unknown candidate or draft id is byte-identical to a foreign one, SC-016).
 */
class SlaContractTest extends SlaItBase {

    @Autowired SlaNudgeScheduler scheduler;

    @Test
    void silenceList_recruiter_200_noStore() throws Exception {
        configuredWorkspace();
        seedCandidate("c1", "Ada", "ada@x.test", 10);
        scheduler.sweep();
        var rec = member("rec@x.test", Role.RECRUITER);
        mvc.perform(get("/api/internal/sla/silence-list").cookie(cookie(rec)))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
            .andExpect(jsonPath("$.items[0].slaState").value("RED"));
    }

    @Test
    void candidateSla_recruiter_200() throws Exception {
        configuredWorkspace();
        seedCandidate("c1", "Ada", "ada@x.test", 1); // within window -> GREEN
        var rec = member("rec@x.test", Role.RECRUITER);
        mvc.perform(get("/api/internal/candidates/{c}/sla", "c1").cookie(cookie(rec)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.slaState").value("GREEN"));
    }

    @Test
    void candidateSla_unknownCandidate_indistinguishable404() throws Exception {
        configuredWorkspace();
        var rec = member("rec@x.test", Role.RECRUITER);
        mvc.perform(get("/api/internal/candidates/{c}/sla", "nope").cookie(cookie(rec)))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("not_found"))
            .andExpect(jsonPath("$.message").doesNotExist()); // value-free — no oracle (SC-016)
    }

    @Test
    void approve_unknownDraft_indistinguishable404() throws Exception {
        configuredWorkspace();
        var rec = member("rec@x.test", Role.RECRUITER);
        mvc.perform(post("/api/internal/sla/drafts/{d}/approve", "nope").cookie(cookie(rec)).with(csrf()))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("not_found"))
            .andExpect(jsonPath("$.message").doesNotExist());
    }

    @Test
    void candidateSla_hiringManager_403() throws Exception {
        configuredWorkspace();
        seedCandidate("c1", "Ada", "ada@x.test", 1);
        var hm = member("hm@x.test", Role.HIRING_MANAGER);
        mvc.perform(get("/api/internal/candidates/{c}/sla", "c1").cookie(cookie(hm)))
            .andExpect(status().isForbidden());
    }

    @Test
    void candidateSla_interviewer_403() throws Exception {
        configuredWorkspace();
        seedCandidate("c1", "Ada", "ada@x.test", 1);
        var iv = member("iv@x.test", Role.INTERVIEWER);
        mvc.perform(get("/api/internal/candidates/{c}/sla", "c1").cookie(cookie(iv)))
            .andExpect(status().isForbidden());
    }

    @Test
    void candidateSla_readOnly_403() throws Exception {
        configuredWorkspace();
        seedCandidate("c1", "Ada", "ada@x.test", 1);
        var ro = member("ro@x.test", Role.READ_ONLY);
        mvc.perform(get("/api/internal/candidates/{c}/sla", "c1").cookie(cookie(ro)))
            .andExpect(status().isForbidden());
    }
}
