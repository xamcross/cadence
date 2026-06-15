package com.cadence.interview;

import com.cadence.domain.AuthEventType;
import com.cadence.domain.Member;
import com.cadence.domain.Role;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** SC-009/SC-006: RBAC matrix, /slots shape, retired→409+audit, cross-workspace 404, foreign-member 400, no-store. */
class InterviewTemplateContractTest extends InterviewItBase {

    private static final Role[] NON_PERMITTED = {Role.HIRING_MANAGER, Role.INTERVIEWER, Role.READ_ONLY};

    @Autowired ObjectMapper mapper;

    private String createBody(String requiredId) {
        return "{\"name\":\"Onsite\",\"durationMinutes\":45,\"slotCadenceMinutes\":15,"
            + "\"bufferBeforeMinutes\":0,\"bufferAfterMinutes\":0,\"dailyCapPerInterviewer\":2,"
            + "\"requiredMemberIds\":[\"" + requiredId + "\"]}";
    }

    @Test
    void nonPermittedRoles_areForbiddenOnEverySurface() throws Exception {
        for (Role role : NON_PERMITTED) {
            Cookie c = cookie(member(role.name().toLowerCase() + "@x.com", role));
            expectForbidden(post("/api/internal/interview-templates").cookie(c).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{}"));
            expectForbidden(get("/api/internal/interview-templates").cookie(c));
            expectForbidden(get("/api/internal/interview-templates/x").cookie(c));
            expectForbidden(put("/api/internal/interview-templates/x").cookie(c).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{}"));
            expectForbidden(post("/api/internal/interview-templates/x/retire").cookie(c).with(csrf()));
            expectForbidden(post("/api/internal/interview-templates/x/slots").cookie(c).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"rangeStart\":\"2026-06-15\",\"rangeEnd\":\"2026-06-16\"}"));
        }
    }

    @Test
    void unauthenticated_isUnauthorized() throws Exception {
        mvc.perform(get("/api/internal/interview-templates")).andExpect(status().isUnauthorized());
    }

    @Test
    void recruiterAndAdmin_canManage_andComputeReturnsTheDocumentedShape() throws Exception {
        configuredWorkspace(WS, "UTC", LocalTime.of(9, 0), LocalTime.of(17, 0));
        Cookie rec = cookie(member("rec@x.com", Role.RECRUITER));
        Member interviewer = member("int@x.com", Role.INTERVIEWER);

        String id = create(rec, createBody(interviewer.getId()));

        // ADMIN can also list.
        mvc.perform(get("/api/internal/interview-templates").cookie(cookie(member("admin@x.com", Role.ADMIN))))
            .andExpect(status().isOk());

        // /slots: a required-but-unconnected member yields empty slots + a distinguishable reason; no-store.
        mvc.perform(post("/api/internal/interview-templates/" + id + "/slots").cookie(rec).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"rangeStart\":\"2026-06-15\",\"rangeEnd\":\"2026-06-20\"}"))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
            .andExpect(jsonPath("$.slots").isArray())
            .andExpect(jsonPath("$.windowClamped").value(false))
            .andExpect(jsonPath("$.unschedulable[0].memberId").value(interviewer.getId()))
            .andExpect(jsonPath("$.unschedulable[0].reason").value("NOT_CONNECTED"));
    }

    @Test
    void computeAgainstRetiredTemplate_is409_andAudited() throws Exception {
        configuredWorkspace(WS, "UTC", LocalTime.of(9, 0), LocalTime.of(17, 0));
        Cookie rec = cookie(member("rec@x.com", Role.RECRUITER));
        Member interviewer = member("int@x.com", Role.INTERVIEWER);
        String id = create(rec, createBody(interviewer.getId()));
        mvc.perform(post("/api/internal/interview-templates/" + id + "/retire").cookie(rec).with(csrf()))
            .andExpect(status().isOk());

        mvc.perform(post("/api/internal/interview-templates/" + id + "/slots").cookie(rec).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"rangeStart\":\"2026-06-15\",\"rangeEnd\":\"2026-06-20\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").value("template_retired"));

        assertThat(mongoTemplate.count(
                new Query(Criteria.where("eventType").is(AuthEventType.INTERVIEW_TEMPLATE_COMPUTE_REFUSED.name())),
                "authAuditLog"))
            .isEqualTo(1);
    }

    @Test
    void crossWorkspaceTemplate_is404() throws Exception {
        Cookie rec = cookie(member("rec@x.com", Role.RECRUITER));
        Member interviewer = member("int@x.com", Role.INTERVIEWER);
        String id = create(rec, createBody(interviewer.getId()));

        Cookie otherWs = cookie(member("ws2", "r2@x.com", Role.RECRUITER));
        mvc.perform(get("/api/internal/interview-templates/" + id).cookie(otherWs))
            .andExpect(status().isNotFound());
    }

    @Test
    void foreignWorkspaceMemberInCreate_is400InvalidTemplate() throws Exception {
        Cookie rec = cookie(member("rec@x.com", Role.RECRUITER));
        mvc.perform(post("/api/internal/interview-templates").cookie(rec).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(createBody("000000000000000000000000")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("invalid_template"));
    }

    private String create(Cookie c, String body) throws Exception {
        String json = mvc.perform(post("/api/internal/interview-templates").cookie(c).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(json).get("id").asText();
    }

    private void expectForbidden(MockHttpServletRequestBuilder req) throws Exception {
        mvc.perform(req).andExpect(status().isForbidden());
    }
}
