package com.cadence.rbac;

import com.cadence.BaseIntegrationTest;
import com.cadence.auth.AuthTestConfig;
import com.cadence.api.RbacExceptions;
import com.cadence.domain.Assignment;
import com.cadence.domain.Member;
import com.cadence.domain.ResourceType;
import com.cadence.domain.Role;
import com.cadence.domain.Session;
import com.cadence.service.AssignmentService;
import com.cadence.service.MemberService;
import com.cadence.service.SessionService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Server-side scoping (T036, US4): HM/Interviewer see only their own assignments (FR-024); empty set
 * with none (SC-006); out-of-assignment id is indistinguishable from a missing id (SC-015); scoped
 * write outside assignment refused (FR-032); cross-workspace ?memberId= yields nothing. Both
 * REQUISITION (Hiring Manager) and INTERVIEW (Interviewer) enum paths are exercised.
 */
@AutoConfigureMockMvc
@Import(AuthTestConfig.class)
class AssignmentScopingIntegrationTest extends BaseIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired MemberService memberService;
    @Autowired SessionService sessionService;
    @Autowired AssignmentService assignmentService;

    @BeforeEach
    void cleanup() {
        mongoTemplate.remove(new Query(), Member.class);
        mongoTemplate.remove(new Query(), Session.class);
        mongoTemplate.remove(new Query(), Assignment.class);
    }

    private Member member(String ws, String email, Role role) {
        return memberService.create(ws, email, email, role, null, null);
    }

    private Cookie cookie(Member m) {
        return new Cookie("cad_session", sessionService.issue(m).jwt());
    }

    @Test
    void hiringManager_seesOnlyOwnRequisitions_emptyWhenNone() throws Exception {
        Member hm1 = member("ws1", "hm1@x.com", Role.HIRING_MANAGER);
        Member hm2 = member("ws1", "hm2@x.com", Role.HIRING_MANAGER);
        assignmentService.create("ws1", "admin", hm1.getId(), ResourceType.REQUISITION, "req-1");

        mvc.perform(get("/api/internal/assignments").cookie(cookie(hm1)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].resourceId").value("req-1"));

        // hm2 has no assignments -> empty set, never the full workspace set.
        mvc.perform(get("/api/internal/assignments").cookie(cookie(hm2)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void interviewer_seesOnlyOwnInterviews() throws Exception {
        Member iv = member("ws1", "iv@x.com", Role.INTERVIEWER);
        assignmentService.create("ws1", "admin", iv.getId(), ResourceType.INTERVIEW, "int-9");
        mvc.perform(get("/api/internal/assignments").cookie(cookie(iv)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].resourceId").value("int-9"));
    }

    @Test
    void outOfAssignmentId_isIndistinguishableFromMissing() throws Exception {
        Member hm1 = member("ws1", "hm1@x.com", Role.HIRING_MANAGER);
        Member hm2 = member("ws1", "hm2@x.com", Role.HIRING_MANAGER);
        Assignment other = assignmentService.create("ws1", "admin", hm2.getId(), ResourceType.REQUISITION, "req-2");

        // hm1 fetching hm2's assignment by id -> 404 with the generic not_found body.
        String notYours = mvc.perform(get("/api/internal/assignments/{id}", other.getId()).cookie(cookie(hm1)))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("not_found"))
            .andReturn().getResponse().getContentAsString();

        // hm1 fetching a genuinely missing id -> identical response (no existence oracle).
        String missing = mvc.perform(get("/api/internal/assignments/{id}", "ffffffffffffffffffffffff").cookie(cookie(hm1)))
            .andExpect(status().isNotFound())
            .andReturn().getResponse().getContentAsString();

        assertThat(notYours).isEqualTo(missing);
    }

    @Test
    void interviewer_outOfAssignmentInterview_isIndistinguishableFromMissing() throws Exception {
        Member iv1 = member("ws1", "iv1@x.com", Role.INTERVIEWER);
        Member iv2 = member("ws1", "iv2@x.com", Role.INTERVIEWER);
        Assignment other = assignmentService.create("ws1", "admin", iv2.getId(), ResourceType.INTERVIEW, "int-2");

        String notYours = mvc.perform(get("/api/internal/assignments/{id}", other.getId()).cookie(cookie(iv1)))
            .andExpect(status().isNotFound())
            .andReturn().getResponse().getContentAsString();
        String missing = mvc.perform(get("/api/internal/assignments/{id}", "ffffffffffffffffffffffff").cookie(cookie(iv1)))
            .andExpect(status().isNotFound())
            .andReturn().getResponse().getContentAsString();
        assertThat(notYours).isEqualTo(missing); // INTERVIEW enum path covered, not just REQUISITION
    }

    @Test
    void scopedWriteOutsideAssignment_isRefused() {
        Member hm1 = member("ws1", "hm1@x.com", Role.HIRING_MANAGER);
        Member hm2 = member("ws1", "hm2@x.com", Role.HIRING_MANAGER);
        assignmentService.create("ws1", "admin", hm2.getId(), ResourceType.REQUISITION, "req-2");
        // The reusable scoped-write guard (consumed by later F13/F32) refuses an out-of-assignment write.
        assertThatThrownBy(() ->
            assignmentService.requireAssigned("ws1", hm1.getId(), ResourceType.REQUISITION, "req-2"))
            .isInstanceOf(RbacExceptions.NotAssignedException.class);
    }

    @Test
    void crossWorkspaceMemberIdFilter_yieldsEmpty() throws Exception {
        Member recruiter = member("ws1", "rec@x.com", Role.RECRUITER);
        Member foreignHm = member("ws2", "foreign@x.com", Role.HIRING_MANAGER);
        assignmentService.create("ws2", "admin2", foreignHm.getId(), ResourceType.REQUISITION, "req-x");
        // Recruiter in ws1 asking for a ws2 member's assignments -> AND-ed with caller workspace -> empty.
        mvc.perform(get("/api/internal/assignments").param("memberId", foreignHm.getId()).cookie(cookie(recruiter)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void readOnly_cannotListAssignments() throws Exception {
        Member ro = member("ws1", "ro@x.com", Role.READ_ONLY);
        mvc.perform(get("/api/internal/assignments").cookie(cookie(ro))).andExpect(status().isForbidden());
    }

    @Test
    void adminCannotAssignToAForeignWorkspaceMember() throws Exception {
        Member admin = member("ws1", "admin@x.com", Role.ADMIN);
        Member foreign = member("ws2", "foreign@x.com", Role.HIRING_MANAGER);
        // ws1 Admin targeting a ws2 member -> indistinguishable 404, no cross-workspace assignment row.
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/internal/members/{id}/assignments", foreign.getId())
                .cookie(cookie(admin))
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"resourceType\":\"REQUISITION\",\"resourceId\":\"req-z\"}"))
            .andExpect(status().isNotFound());
        assertThat(mongoTemplate.findAll(Assignment.class)).isEmpty();
    }
}
