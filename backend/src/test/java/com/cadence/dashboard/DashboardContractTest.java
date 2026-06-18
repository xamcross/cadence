package com.cadence.dashboard;

import com.cadence.domain.Role;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F50 US4 (SC-007, FR-020/021/022/026) — the 5-role access matrix for BOTH endpoints, the Read-only positive
 * read, the bad-window 400, workspace scoping, and the no-payload-on-403 guarantee.
 */
class DashboardContractTest extends DashboardItBase {

    private static final String READ = "/api/internal/dashboard";
    private static final String EXPORT = "/api/internal/dashboard/export";

    // ---- read: ADMIN/RECRUITER/READ_ONLY allowed; HM/INTERVIEWER denied ----

    @Test
    void read_admin_recruiter_readonly_200() throws Exception {
        for (Role r : new Role[]{Role.ADMIN, Role.RECRUITER, Role.READ_ONLY}) {
            mvc.perform(get(READ).cookie(cookie(member(r.name().toLowerCase() + "@x.test", r))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.window").value("LAST_30_DAYS"))
                .andExpect(jsonPath("$.timeToSchedule").exists())
                .andExpect(jsonPath("$.silenceList").isArray());
        }
    }

    @Test
    void read_hiringManager_and_interviewer_403_noPayload() throws Exception {
        for (Role r : new Role[]{Role.HIRING_MANAGER, Role.INTERVIEWER}) {
            mvc.perform(get(READ).cookie(cookie(member(r.name().toLowerCase() + "@x.test", r))))
                .andExpect(status().isForbidden())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                    org.hamcrest.Matchers.containsString("timeToSchedule"))));
        }
    }

    // ---- export: ADMIN/RECRUITER allowed; READ_ONLY/HM/INTERVIEWER denied ----

    @Test
    void export_admin_recruiter_200() throws Exception {
        for (Role r : new Role[]{Role.ADMIN, Role.RECRUITER}) {
            mvc.perform(get(EXPORT).cookie(cookie(member(r.name().toLowerCase() + "2@x.test", r))))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                    org.hamcrest.Matchers.containsString("attachment")));
        }
    }

    @Test
    void export_readOnly_hm_interviewer_403() throws Exception {
        for (Role r : new Role[]{Role.READ_ONLY, Role.HIRING_MANAGER, Role.INTERVIEWER}) {
            mvc.perform(get(EXPORT).cookie(cookie(member(r.name().toLowerCase() + "3@x.test", r))))
                .andExpect(status().isForbidden());
        }
    }

    // ---- window validation + workspace scoping ----

    @Test
    void badWindow_400_invalidRequest() throws Exception {
        mvc.perform(get(READ + "?window=BOGUS").cookie(cookie(member("a@x.test", Role.ADMIN))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("invalid_request"));
    }

    @Test
    void workspaceScoped_otherWorkspaceDataNeverReturned() throws Exception {
        // A silent candidate in a DIFFERENT workspace must never appear for a WS member (FR-022, no oracle).
        seedCandidate("foreign", "Foreign", NOW.minus(java.time.Duration.ofDays(10)),
            com.cadence.domain.CandidateStatusOutcome.IN_PROGRESS, com.cadence.domain.ErasureState.ACTIVE);
        mongoTemplate.updateFirst(
            org.springframework.data.mongodb.core.query.Query.query(
                org.springframework.data.mongodb.core.query.Criteria.where("_id").is("foreign")),
            org.springframework.data.mongodb.core.query.Update.update("workspaceId", "ws-other"),
            com.cadence.domain.Candidate.class);
        mvc.perform(get(READ).cookie(cookie(member("admin@x.test", Role.ADMIN))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.silenceList").isEmpty());
    }
}
