package com.cadence.workspace;

import com.cadence.api.WorkspaceDtos;
import com.cadence.domain.Member;
import com.cadence.domain.Role;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T041 (US5): template-lock state persists + reads back; Admin-only. NOTE: the binding rule
 * "locked => a Recruiter cannot edit the template" is an F21 FORWARD CONTRACT enforced by F21
 * against this state; it is intentionally NOT exercised here (F03 owns only the lock state).
 */
class TemplateLockIntegrationTest extends WorkspaceItBase {

    @Autowired com.cadence.service.WorkspaceConfigService service;

    private Cookie configuredAdmin() {
        Member admin = member("admin@x.com", Role.ADMIN);
        service.completeSetup("ws1", admin.getId(), new WorkspaceDtos.SetupRequest("Acme", "Europe/London",
            new WorkspaceDtos.WorkingHoursDto(LocalTime.of(9, 0), LocalTime.of(17, 0)), 5, 365, true));
        return cookie(admin);
    }

    @Test
    void lockThenUnlock_persists() throws Exception {
        Cookie admin = configuredAdmin();
        mvc.perform(put("/api/internal/workspace/templates/interview_invite/lock").cookie(admin).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"locked\":true}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.templateLocks.interview_invite").value(true));
        assertThat(configs.findByWorkspaceId("ws1").orElseThrow().getTemplateLocks())
            .containsEntry("interview_invite", true);

        mvc.perform(put("/api/internal/workspace/templates/interview_invite/lock").cookie(admin).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"locked\":false}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.templateLocks.interview_invite").value(false));
    }

    @Test
    void invalidKey_rejected() throws Exception {
        Cookie admin = configuredAdmin();
        mvc.perform(put("/api/internal/workspace/templates/has.dot/lock").cookie(admin).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"locked\":true}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void nonAdmin_forbidden() throws Exception {
        configuredAdmin();
        Cookie recruiter = cookie(member("rec@x.com", Role.RECRUITER));
        mvc.perform(put("/api/internal/workspace/templates/invite/lock").cookie(recruiter).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"locked\":true}"))
            .andExpect(status().isForbidden());
    }
}
