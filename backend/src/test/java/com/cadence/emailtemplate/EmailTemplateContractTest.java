package com.cadence.emailtemplate;

import com.cadence.domain.Role;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** SC-007: role matrix, RenderedMessage shape (marker in-body), preview no-store, foreign-stage/candidate 404. */
class EmailTemplateContractTest extends EmailTemplateItBase {

    private static final Role[] NON_PERMITTED = {Role.HIRING_MANAGER, Role.INTERVIEWER, Role.READ_ONLY};

    private void expectForbidden(ResultActions ra) throws Exception {
        ra.andExpect(status().isForbidden());
    }

    @Test
    void nonPermittedRoles_areForbiddenOnEverySurface() throws Exception {
        for (Role role : NON_PERMITTED) {
            Cookie c = cookie(member(role.name().toLowerCase() + "@x.com", role));
            expectForbidden(mvc.perform(get("/api/internal/email-templates").cookie(c)));
            expectForbidden(mvc.perform(get("/api/internal/email-templates/INVITATION").cookie(c)));
            expectForbidden(mvc.perform(put("/api/internal/email-templates/INVITATION").cookie(c).with(csrf())
                .contentType("application/json").content("{}")));
            expectForbidden(mvc.perform(post("/api/internal/email-templates/INVITATION/apply-tone").cookie(c).with(csrf())
                .contentType("application/json").content("{}")));
            expectForbidden(mvc.perform(post("/api/internal/email-templates/INVITATION/reset").cookie(c).with(csrf())
                .contentType("application/json").content("{}")));
            expectForbidden(mvc.perform(post("/api/internal/email-templates/INVITATION/lock").cookie(c).with(csrf())
                .contentType("application/json").content("{}")));
            expectForbidden(mvc.perform(post("/api/internal/email-templates/INVITATION/preview").cookie(c).with(csrf())
                .contentType("application/json").content("{}")));
        }
    }

    @Test
    void recruiterCannotLock_butAdminCan() throws Exception {
        Cookie rec = cookie(member("rec@x.com", Role.RECRUITER));
        expectForbidden(mvc.perform(post("/api/internal/email-templates/INVITATION/lock").cookie(rec).with(csrf())
            .contentType("application/json").content("{\"stageKey\":\"BASE\"}")));
        Cookie admin = cookie(member("admin@x.com", Role.ADMIN));
        mvc.perform(post("/api/internal/email-templates/INVITATION/lock").cookie(admin).with(csrf())
            .contentType("application/json").content("{\"stageKey\":\"BASE\"}")).andExpect(status().isOk());
    }

    @Test
    void unauthenticated_isUnauthorized() throws Exception {
        mvc.perform(get("/api/internal/email-templates")).andExpect(status().isUnauthorized());
    }

    @Test
    void preview_returnsRenderedShape_withMissingMarkerInBody_andNoStore() throws Exception {
        Cookie admin = cookie(member("admin@x.com", Role.ADMIN));
        // CONFIRMATION permits interview_date; supply candidate_name only -> interview_date is missing.
        mvc.perform(put("/api/internal/email-templates/CONFIRMATION").cookie(admin).with(csrf())
                .contentType("application/json")
                .content("{\"stageKey\":\"BASE\",\"subject\":\"Hi {{candidate_name}}\",\"body\":\"On {{interview_date}} hi {{candidate_name}}\"}"))
            .andExpect(status().isOk());

        mvc.perform(post("/api/internal/email-templates/CONFIRMATION/preview").cookie(admin).with(csrf())
                .contentType("application/json").content("{\"stageKey\":\"BASE\",\"sampleValues\":{\"candidate_name\":\"Dana\"}}"))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
            .andExpect(jsonPath("$.subject").value("Hi Dana"))
            .andExpect(jsonPath("$.bodyText").value(org.hamcrest.Matchers.containsString("[[missing:interview_date]]")))
            .andExpect(jsonPath("$.bodyHtml").value(org.hamcrest.Matchers.containsString("[[missing:interview_date]]")))
            .andExpect(jsonPath("$.missingFields[0]").value("interview_date"));
    }

    @Test
    void foreignStageAndForeignCandidate_are404() throws Exception {
        Cookie admin = cookie(member("admin@x.com", Role.ADMIN));
        // a variant stageKey that is not an interview template in this workspace -> 404 (oracle-free)
        mvc.perform(get("/api/internal/email-templates/INVITATION?stageKey=nope").cookie(admin))
            .andExpect(status().isNotFound());
        // a preview candidateId not in this workspace -> 404
        mvc.perform(post("/api/internal/email-templates/INVITATION/preview").cookie(admin).with(csrf())
                .contentType("application/json").content("{\"stageKey\":\"BASE\",\"candidateId\":\"ghost\"}"))
            .andExpect(status().isNotFound());
    }

    @Test
    void invalidMessageType_is404() throws Exception {
        Cookie admin = cookie(member("admin@x.com", Role.ADMIN));
        mvc.perform(get("/api/internal/email-templates/NOT_A_TYPE").cookie(admin)).andExpect(status().isNotFound());
    }
}
