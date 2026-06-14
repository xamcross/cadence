package com.cadence.rbac;

import com.cadence.BaseIntegrationTest;
import com.cadence.auth.AuthTestConfig;
import com.cadence.domain.Member;
import com.cadence.domain.Role;
import com.cadence.domain.Session;
import com.cadence.service.MemberService;
import com.cadence.service.SessionService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Least-privilege matrix over the endpoints that exist today (T033, US3): member-administration is
 * Admin-only; assignment listing excludes Read-only; assignment creation is Admin-only. Later-feature
 * endpoints inherit the matrix via the inventory test (FR-022).
 */
@AutoConfigureMockMvc
@Import(AuthTestConfig.class)
class RoleMatrixContractTest extends BaseIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired MemberService memberService;
    @Autowired SessionService sessionService;

    @BeforeEach
    void cleanup() {
        mongoTemplate.remove(new Query(), Member.class);
        mongoTemplate.remove(new Query(), Session.class);
    }

    private Cookie cookieFor(Role role) {
        Member m = memberService.create("ws1", role.name().toLowerCase() + "@x.com", role.name(), role, null, null);
        return new Cookie("cad_session", sessionService.issue(m).jwt());
    }

    @Test
    void memberAdministration_isAdminOnly() throws Exception {
        mvc.perform(get("/api/internal/members").cookie(cookieFor(Role.ADMIN))).andExpect(status().isOk());
        for (Role role : new Role[]{Role.RECRUITER, Role.HIRING_MANAGER, Role.INTERVIEWER, Role.READ_ONLY}) {
            mvc.perform(get("/api/internal/members").cookie(cookieFor(role))).andExpect(status().isForbidden());
        }
    }

    @Test
    void assignmentListing_excludesReadOnly() throws Exception {
        for (Role role : new Role[]{Role.ADMIN, Role.RECRUITER, Role.HIRING_MANAGER, Role.INTERVIEWER}) {
            mvc.perform(get("/api/internal/assignments").cookie(cookieFor(role))).andExpect(status().isOk());
        }
        mvc.perform(get("/api/internal/assignments").cookie(cookieFor(Role.READ_ONLY)))
            .andExpect(status().isForbidden());
    }

    @Test
    void assignmentCreation_isAdminOnly() throws Exception {
        Member subject = memberService.create("ws1", "subject@x.com", "Subject", Role.HIRING_MANAGER, null, null);
        String body = "{\"resourceType\":\"REQUISITION\",\"resourceId\":\"req-1\"}";
        mvc.perform(post("/api/internal/members/{id}/assignments", subject.getId())
                .cookie(cookieFor(Role.RECRUITER)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/internal/members/{id}/assignments", subject.getId())
                .cookie(cookieFor(Role.ADMIN)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated());
    }
}
