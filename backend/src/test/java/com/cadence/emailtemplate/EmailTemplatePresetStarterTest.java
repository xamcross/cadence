package com.cadence.emailtemplate;

import com.cadence.domain.AuthAuditEvent;
import com.cadence.domain.AuthEventType;
import com.cadence.domain.Role;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** apply-preset-starter mirrors apply-tone: guard ordering, oracle-free 404s, version semantics, audit kind. */
class EmailTemplatePresetStarterTest extends EmailTemplateItBase {

    private static final String URL = "/api/internal/email-templates/INVITATION/apply-preset-starter";

    private List<AuthAuditEvent> audits() {
        return mongoTemplate.findAll(AuthAuditEvent.class);
    }

    private long count(AuthEventType type, String outcome) {
        return audits().stream()
            .filter(e -> e.getEventType() == type && outcome.equals(e.getOutcome())).count();
    }

    @Test
    void materialisesVariant_fromPresetStarter_atVersionZero() throws Exception {
        Cookie rec = cookie(member("rec@x.com", Role.RECRUITER));
        seedStage(WS, "stage1");
        mvc.perform(post(URL).cookie(rec).with(csrf()).contentType("application/json")
                .content("{\"stageKey\":\"stage1\",\"presetKey\":\"TECH_DEEP_DIVE\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.stageKey").value("stage1"))
            .andExpect(jsonPath("$.source").value("OVERRIDE"))
            .andExpect(jsonPath("$.version").value(0))
            .andExpect(jsonPath("$.body").value(org.hamcrest.Matchers.containsString("development environment")));
        assertThat(count(AuthEventType.EMAIL_TEMPLATE_EDITED, "INVITATION/stage1/preset_starter_apply")).isEqualTo(1);
    }

    @Test
    void baseStageKey_isRefused400_valueFree() throws Exception {
        Cookie rec = cookie(member("rec2@x.com", Role.RECRUITER));
        mvc.perform(post(URL).cookie(rec).with(csrf()).contentType("application/json")
                .content("{\"stageKey\":\"BASE\",\"presetKey\":\"PHONE_SCREEN\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("invalid_template"))
            .andExpect(jsonPath("$.fields.stageKey").exists());
    }

    @Test
    void unknownPresetKey_isRefused400() throws Exception {
        Cookie rec = cookie(member("rec3@x.com", Role.RECRUITER));
        seedStage(WS, "stage1");
        mvc.perform(post(URL).cookie(rec).with(csrf()).contentType("application/json")
                .content("{\"stageKey\":\"stage1\",\"presetKey\":\"NOPE\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fields.presetKey").exists());
    }

    @Test
    void undeclaredTypeForPreset_isRefused400() throws Exception {
        // HM_INTRO declares INVITATION only -> REMINDER_24H has no starter.
        Cookie rec = cookie(member("rec4@x.com", Role.RECRUITER));
        seedStage(WS, "stage1");
        mvc.perform(post("/api/internal/email-templates/REMINDER_24H/apply-preset-starter")
                .cookie(rec).with(csrf()).contentType("application/json")
                .content("{\"stageKey\":\"stage1\",\"presetKey\":\"HM_INTRO\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fields.messageType").exists());
    }

    @Test
    void foreignStageKey_isOracleFree404() throws Exception {
        Cookie rec = cookie(member("rec5@x.com", Role.RECRUITER));
        seedStage("ws2", "foreignStage");
        mvc.perform(post(URL).cookie(rec).with(csrf()).contentType("application/json")
                .content("{\"stageKey\":\"foreignStage\",\"presetKey\":\"PHONE_SCREEN\"}"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("not_found"));
    }

    @Test
    void reapply_overwritesWithVersionBump_andStaleExpectedVersionIs409() throws Exception {
        Cookie rec = cookie(member("rec6@x.com", Role.RECRUITER));
        seedStage(WS, "stage1");
        mvc.perform(post(URL).cookie(rec).with(csrf()).contentType("application/json")
                .content("{\"stageKey\":\"stage1\",\"presetKey\":\"PHONE_SCREEN\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(0));
        mvc.perform(post(URL).cookie(rec).with(csrf()).contentType("application/json")
                .content("{\"stageKey\":\"stage1\",\"presetKey\":\"TECH_DEEP_DIVE\",\"expectedVersion\":0}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));
        mvc.perform(post(URL).cookie(rec).with(csrf()).contentType("application/json")
                .content("{\"stageKey\":\"stage1\",\"presetKey\":\"PHONE_SCREEN\"}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("stale_template"));
    }

    @Test
    void lockedVariant_isRefused403ForRecruiter() throws Exception {
        Cookie admin = cookie(member("admin@x.com", Role.ADMIN));
        Cookie rec = cookie(member("rec7@x.com", Role.RECRUITER));
        seedStage(WS, "stage1");
        mvc.perform(post("/api/internal/email-templates/INVITATION/lock").cookie(admin).with(csrf())
                .contentType("application/json").content("{\"stageKey\":\"stage1\"}"))
            .andExpect(status().isOk());
        mvc.perform(post(URL).cookie(rec).with(csrf()).contentType("application/json")
                .content("{\"stageKey\":\"stage1\",\"presetKey\":\"PHONE_SCREEN\"}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error").value("template_locked"));
    }

    @Test
    void nonPermittedRoles_areForbidden() throws Exception {
        seedStage(WS, "stage1");
        for (Role role : new Role[]{Role.HIRING_MANAGER, Role.INTERVIEWER, Role.READ_ONLY}) {
            Cookie c = cookie(member(role.name().toLowerCase() + "@x.com", role));
            mvc.perform(post(URL).cookie(c).with(csrf()).contentType("application/json")
                    .content("{\"stageKey\":\"stage1\",\"presetKey\":\"PHONE_SCREEN\"}"))
                .andExpect(status().isForbidden());
        }
    }
}
