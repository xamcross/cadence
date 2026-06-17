package com.cadence.status;

import com.cadence.domain.Role;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F30 T024 — recruiter publish/read/rotate contract (contracts C/D/E). MockMvc with {@code .with(csrf())}.
 * publish 200; 400 invalid_status for in-progress missing date AND blank next-step (value-free); scoped 404;
 * 5-role matrix (ADMIN/RECRUITER allowed, HM/Interviewer/Read-only 403); rotate + recruiter GET.
 */
class RecruiterStatusContractTest extends StatusItBase {

    @Autowired ObjectMapper json;

    private String body(String outcome, String stage, String nextStep, String expectedDate) throws Exception {
        return json.writeValueAsString(Map.of(
            "outcome", outcome,
            "stage", stage == null ? "" : stage,
            "nextStep", nextStep == null ? "" : nextStep,
            "expectedDate", expectedDate == null ? "" : expectedDate));
    }

    @Test
    void publish_inProgress_returns200() throws Exception {
        configuredWorkspace();
        seedCandidate("c1", "Ada", "ada@x.test");
        var rec = member("rec@x.test", Role.RECRUITER);
        mvc.perform(put("/api/internal/candidates/{c}/status", "c1").cookie(cookie(rec)).with(csrf())
                .contentType(APPLICATION_JSON)
                .content(body("IN_PROGRESS", "Onsite", "Collecting feedback", "2026-09-01")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.displayState").value("PUBLISHED"))
            .andExpect(jsonPath("$.outcome").value("IN_PROGRESS"))
            .andExpect(jsonPath("$.statusLink").exists());
    }

    @Test
    void publish_inProgressMissingDate_returns400() throws Exception {
        configuredWorkspace();
        seedCandidate("c1", "Ada", "ada@x.test");
        var rec = member("rec@x.test", Role.RECRUITER);
        mvc.perform(put("/api/internal/candidates/{c}/status", "c1").cookie(cookie(rec)).with(csrf())
                .contentType(APPLICATION_JSON)
                .content(body("IN_PROGRESS", "Onsite", "Collecting feedback", null)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("invalid_status"));
    }

    @Test
    void publish_blankNextStep_returns400() throws Exception {
        configuredWorkspace();
        seedCandidate("c1", "Ada", "ada@x.test");
        var rec = member("rec@x.test", Role.RECRUITER);
        mvc.perform(put("/api/internal/candidates/{c}/status", "c1").cookie(cookie(rec)).with(csrf())
                .contentType(APPLICATION_JSON)
                .content(body("IN_PROGRESS", "Onsite", "   ", "2026-09-01")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("invalid_status"));
    }

    @Test
    void publish_foreignCandidate_returns404_scoped() throws Exception {
        configuredWorkspace();
        // candidate seeded in WS; the recruiter belongs to a DIFFERENT workspace.
        seedCandidate("c1", "Ada", "ada@x.test");
        var other = memberService.create("ws-other", "rec2@x.test", "rec2@x.test", Role.RECRUITER, null, null);
        mvc.perform(put("/api/internal/candidates/{c}/status", "c1").cookie(cookie(other)).with(csrf())
                .contentType(APPLICATION_JSON)
                .content(body("IN_PROGRESS", "Onsite", "Feedback", "2026-09-01")))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("not_found"));
    }

    @Test
    void roleMatrix_adminAndRecruiterAllowed_othersForbidden() throws Exception {
        configuredWorkspace();
        seedCandidate("c1", "Ada", "ada@x.test");
        String payload = body("IN_PROGRESS", "Onsite", "Feedback", "2026-09-01");

        for (Role role : List.of(Role.ADMIN, Role.RECRUITER)) {
            var m = member(role.name().toLowerCase() + "@x.test", role);
            mvc.perform(put("/api/internal/candidates/{c}/status", "c1").cookie(cookie(m)).with(csrf())
                    .contentType(APPLICATION_JSON).content(payload))
                .andExpect(status().isOk());
        }
        for (Role role : List.of(Role.HIRING_MANAGER, Role.INTERVIEWER, Role.READ_ONLY)) {
            var m = member(role.name().toLowerCase() + "@x.test", role);
            mvc.perform(put("/api/internal/candidates/{c}/status", "c1").cookie(cookie(m)).with(csrf())
                    .contentType(APPLICATION_JSON).content(payload))
                .andExpect(status().isForbidden());
        }
    }

    @Test
    void recruiterRead_returns200WithLink_andScoped404() throws Exception {
        configuredWorkspace();
        seedCandidate("c1", "Ada", "ada@x.test");
        var rec = member("rec@x.test", Role.RECRUITER);
        // Publish first so there's a status to read.
        mvc.perform(put("/api/internal/candidates/{c}/status", "c1").cookie(cookie(rec)).with(csrf())
                .contentType(APPLICATION_JSON).content(body("IN_PROGRESS", "Onsite", "Feedback", "2026-09-01")))
            .andExpect(status().isOk());

        mvc.perform(get("/api/internal/candidates/{c}/status", "c1").cookie(cookie(rec)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.statusLink").exists())
            .andExpect(jsonPath("$.stage").value("Onsite"));

        // Disallowed role -> 403.
        var hm = member("hm@x.test", Role.HIRING_MANAGER);
        mvc.perform(get("/api/internal/candidates/{c}/status", "c1").cookie(cookie(hm)))
            .andExpect(status().isForbidden());

        // Unknown candidate -> scoped 404.
        mvc.perform(get("/api/internal/candidates/{c}/status", "nope").cookie(cookie(rec)))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("not_found"));
    }

    @Test
    void rotate_returns200WithNewLink_and404OnForeign_403OnDisallowed() throws Exception {
        configuredWorkspace();
        seedCandidate("c1", "Ada", "ada@x.test");
        var rec = member("rec@x.test", Role.RECRUITER);

        mvc.perform(post("/api/internal/candidates/{c}/status/rotate-link", "c1").cookie(cookie(rec)).with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.statusLink").exists());

        var hm = member("hm@x.test", Role.HIRING_MANAGER);
        mvc.perform(post("/api/internal/candidates/{c}/status/rotate-link", "c1").cookie(cookie(hm)).with(csrf()))
            .andExpect(status().isForbidden());

        mvc.perform(post("/api/internal/candidates/{c}/status/rotate-link", "nope").cookie(cookie(rec)).with(csrf()))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("not_found"));
    }
}
