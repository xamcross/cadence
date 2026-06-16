package com.cadence.emailtemplate;

import com.cadence.domain.Role;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** SC-005: locking governs Recruiters, not Admins; lock is Admin-only; optimistic stale -> 409. */
class EmailTemplateLockingTest extends EmailTemplateItBase {

    private static final String T = "/api/internal/email-templates/REJECTION";
    private static final String LOCK_BODY = "{\"stageKey\":\"BASE\"}";
    private static final String EDIT_BODY =
        "{\"stageKey\":\"BASE\",\"subject\":\"S {{workspace_name}}\",\"body\":\"Bye {{candidate_name}}\"}";

    @Test
    void lockBlocksRecruiterMutations_butNotViewOrPreview_andAdminCanStillEditAndUnlock() throws Exception {
        Cookie admin = cookie(member("admin@x.com", Role.ADMIN));
        Cookie rec = cookie(member("rec@x.com", Role.RECRUITER));

        mvc.perform(post(T + "/lock").cookie(admin).with(csrf()).contentType("application/json").content(LOCK_BODY))
            .andExpect(status().isOk()).andExpect(jsonPath("$.locked").value(true));

        // Recruiter mutations refused with template_locked (no state change)
        mvc.perform(put(T).cookie(rec).with(csrf()).contentType("application/json").content(EDIT_BODY))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("template_locked"));
        mvc.perform(post(T + "/apply-tone").cookie(rec).with(csrf()).contentType("application/json")
                .content("{\"stageKey\":\"BASE\",\"tone\":\"FORMAL\"}"))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("template_locked"));
        mvc.perform(post(T + "/reset").cookie(rec).with(csrf()).contentType("application/json").content("{\"stageKey\":\"BASE\"}"))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("template_locked"));

        // Recruiter can still view and preview a locked template
        mvc.perform(get(T).cookie(rec)).andExpect(status().isOk());
        mvc.perform(post(T + "/preview").cookie(rec).with(csrf()).contentType("application/json")
                .content("{\"stageKey\":\"BASE\",\"sampleValues\":{\"candidate_name\":\"Dana\"}}"))
            .andExpect(status().isOk());

        // Admin can edit a locked template, then unlock it
        mvc.perform(put(T).cookie(admin).with(csrf()).contentType("application/json")
                .content("{\"stageKey\":\"BASE\",\"subject\":\"S {{workspace_name}}\",\"body\":\"Bye {{candidate_name}}\",\"expectedVersion\":0}"))
            .andExpect(status().isOk());
        mvc.perform(post(T + "/unlock").cookie(admin).with(csrf()).contentType("application/json")
                .content("{\"stageKey\":\"BASE\",\"expectedVersion\":1}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.locked").value(false));
    }

    @Test
    void recruiterCannotLockOrUnlock() throws Exception {
        Cookie rec = cookie(member("rec@x.com", Role.RECRUITER));
        mvc.perform(post(T + "/lock").cookie(rec).with(csrf()).contentType("application/json").content(LOCK_BODY))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("forbidden"));
        mvc.perform(post(T + "/unlock").cookie(rec).with(csrf()).contentType("application/json").content(LOCK_BODY))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("forbidden"));
    }

    @Test
    void staleVersion_isConflict() throws Exception {
        Cookie admin = cookie(member("admin@x.com", Role.ADMIN));
        mvc.perform(put("/api/internal/email-templates/INVITATION").cookie(admin).with(csrf())
                .contentType("application/json")
                .content("{\"stageKey\":\"BASE\",\"subject\":\"S {{workspace_name}}\",\"body\":\"Hi {{candidate_name}}\"}"))
            .andExpect(status().isOk()); // version now 0
        // editing with a wrong expectedVersion (5) -> 409 stale_template
        mvc.perform(put("/api/internal/email-templates/INVITATION").cookie(admin).with(csrf())
                .contentType("application/json")
                .content("{\"stageKey\":\"BASE\",\"subject\":\"S2 {{workspace_name}}\",\"body\":\"Hi2 {{candidate_name}}\",\"expectedVersion\":5}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("stale_template"));
    }
}
