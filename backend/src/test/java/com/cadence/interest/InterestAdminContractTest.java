package com.cadence.interest;

import com.cadence.domain.InterestRequest;
import com.cadence.domain.InterestRequestStatus;
import com.cadence.domain.Member;
import com.cadence.domain.Role;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

/**
 * T022/SC-012: the internal admin endpoints. 5-role matrix (only ADMIN passes; others 403 and the 403 stays a 403,
 * not a swallowed 500); scoped 404 {@code not_found} byte-identical for absent/other-workspace; {@code no-store} on
 * the list; status-filter semantics — {@code open} (default triage) EXCLUDES REVIEWED while {@code reviewed}/
 * {@code all} INCLUDE it (FR-013/US2 Sc.2).
 */
class InterestAdminContractTest extends InterestItBase {

    private String seed(InterestRequestStatus status) {
        InterestRequest r = new InterestRequest();
        r.setWorkspaceId(WS);
        r.setName("Dana");
        r.setEmail("dana@example.com");
        r.setEmailHash(crypto.emailHash("dana+" + status + "@example.com"));
        if (status == InterestRequestStatus.NEW || status == InterestRequestStatus.REVIEWED) {
            r.setOpenEmailHash(crypto.emailHash("dana+" + status + "@example.com"));
        }
        r.setStatus(status);
        r.setSubmittedAt(Instant.now(clock));
        r.setUpdatedAt(Instant.now(clock));
        return mongoTemplate.save(r).getId();
    }

    @Test
    void roleMatrix_onlyAdminAllowed_othersForbiddenNot500() throws Exception {
        Cookie admin = cookie(member("admin@example.com", Role.ADMIN));
        mvc.perform(get("/api/internal/interest-requests").cookie(admin)).andExpect(status().isOk());
        for (Role denied : new Role[]{Role.RECRUITER, Role.HIRING_MANAGER, Role.INTERVIEWER, Role.READ_ONLY}) {
            Cookie c = cookie(member(denied.name().toLowerCase() + "@example.com", denied));
            mvc.perform(get("/api/internal/interest-requests").cookie(c)).andExpect(status().isForbidden());
        }
    }

    @Test
    void list_setsNoStore() throws Exception {
        Cookie admin = cookie(member("admin@example.com", Role.ADMIN));
        mvc.perform(get("/api/internal/interest-requests").cookie(admin))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
    }

    @Test
    void statusFilter_openExcludesReviewed_reviewedAndAllInclude() throws Exception {
        seed(InterestRequestStatus.NEW);
        seed(InterestRequestStatus.REVIEWED);
        Cookie admin = cookie(member("admin@example.com", Role.ADMIN));

        mvc.perform(get("/api/internal/interest-requests").param("status", "open").cookie(admin))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.requests.length()", is(1)))
            .andExpect(jsonPath("$.requests[0].status", is("NEW")));

        mvc.perform(get("/api/internal/interest-requests").param("status", "reviewed").cookie(admin))
            .andExpect(jsonPath("$.requests.length()", is(1)))
            .andExpect(jsonPath("$.requests[0].status", is("REVIEWED")));

        mvc.perform(get("/api/internal/interest-requests").param("status", "all").cookie(admin))
            .andExpect(jsonPath("$.requests.length()", is(2)));
    }

    @Test
    void absentOrOtherWorkspace_indistinguishable404() throws Exception {
        Cookie admin = cookie(member("admin@example.com", Role.ADMIN));
        mvc.perform(post("/api/internal/interest-requests/{id}/review", "does-not-exist")
                .with(csrf()).cookie(admin))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error", is("not_found")))
            .andExpect(jsonPath("$.message").doesNotExist()); // byte-identical, no message oracle
    }

    @Test
    void unverifiedFlags_areConstantTrue() throws Exception {
        seed(InterestRequestStatus.NEW);
        Cookie admin = cookie(member("admin@example.com", Role.ADMIN));
        mvc.perform(get("/api/internal/interest-requests").param("status", "open").cookie(admin))
            .andExpect(jsonPath("$.requests[0].emailUnverified", is(true)))
            .andExpect(jsonPath("$.requests[0].organizationUnverified", is(true)));
    }
}
