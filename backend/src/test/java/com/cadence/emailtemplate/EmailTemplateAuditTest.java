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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** SC-008: each change-kind writes exactly one append-only audit row, tagged with its kind, ids only. */
class EmailTemplateAuditTest extends EmailTemplateItBase {

    private List<AuthAuditEvent> audits() {
        return mongoTemplate.findAll(AuthAuditEvent.class);
    }

    private long count(AuthEventType type, String outcome) {
        return audits().stream()
            .filter(e -> e.getEventType() == type && outcome.equals(e.getOutcome())).count();
    }

    @Test
    void createEditToneReset_eachEmitOneTaggedRow_noContent() throws Exception {
        Cookie admin = cookie(member("admin@x.com", Role.ADMIN));
        String t = "/api/internal/email-templates/INVITATION";

        // create override
        mvc.perform(put(t).cookie(admin).with(csrf()).contentType("application/json")
                .content("{\"stageKey\":\"BASE\",\"subject\":\"S {{workspace_name}}\",\"body\":\"Hi {{candidate_name}}\"}"))
            .andExpect(status().isOk());
        // edit (version 0 -> 1)
        mvc.perform(put(t).cookie(admin).with(csrf()).contentType("application/json")
                .content("{\"stageKey\":\"BASE\",\"subject\":\"S2 {{workspace_name}}\",\"body\":\"Hi2 {{candidate_name}}\",\"expectedVersion\":0}"))
            .andExpect(status().isOk());
        // tone
        mvc.perform(post(t + "/apply-tone").cookie(admin).with(csrf()).contentType("application/json")
                .content("{\"stageKey\":\"BASE\",\"tone\":\"FORMAL\",\"expectedVersion\":1}"))
            .andExpect(status().isOk());
        // reset
        mvc.perform(post(t + "/reset").cookie(admin).with(csrf()).contentType("application/json")
                .content("{\"stageKey\":\"BASE\",\"expectedVersion\":2}"))
            .andExpect(status().isOk());

        assertThat(count(AuthEventType.EMAIL_TEMPLATE_EDITED, "INVITATION/BASE/create_override")).isEqualTo(1);
        assertThat(count(AuthEventType.EMAIL_TEMPLATE_EDITED, "INVITATION/BASE/edit")).isEqualTo(1);
        assertThat(count(AuthEventType.EMAIL_TEMPLATE_EDITED, "INVITATION/BASE/tone_apply")).isEqualTo(1);
        assertThat(count(AuthEventType.EMAIL_TEMPLATE_RESET, "INVITATION/BASE/reset")).isEqualTo(1);

        // every email-template audit outcome is structurally <TYPE>/<stageKey>/<kind> — no content can leak
        assertThat(audits()).filteredOn(e -> e.getEventType() != null
                && e.getEventType().name().startsWith("EMAIL_TEMPLATE_"))
            .allSatisfy(e -> assertThat(e.getOutcome()).matches("^[A-Z0-9_]+/[A-Za-z0-9]+/[a-z_]+$"));
    }

    @Test
    void lockAndUnlock_eachEmitOneTaggedRow() throws Exception {
        Cookie admin = cookie(member("admin@x.com", Role.ADMIN));
        String t = "/api/internal/email-templates/REJECTION";
        mvc.perform(post(t + "/lock").cookie(admin).with(csrf()).contentType("application/json")
                .content("{\"stageKey\":\"BASE\"}")).andExpect(status().isOk());
        mvc.perform(post(t + "/unlock").cookie(admin).with(csrf()).contentType("application/json")
                .content("{\"stageKey\":\"BASE\",\"expectedVersion\":0}")).andExpect(status().isOk());
        assertThat(count(AuthEventType.EMAIL_TEMPLATE_LOCKED, "REJECTION/BASE/lock")).isEqualTo(1);
        assertThat(count(AuthEventType.EMAIL_TEMPLATE_UNLOCKED, "REJECTION/BASE/unlock")).isEqualTo(1);
    }

    @Test
    void variantEdit_emitsVariantEditKind() throws Exception {
        Cookie admin = cookie(member("admin@x.com", Role.ADMIN));
        seedStage(WS, "stage1");
        mvc.perform(put("/api/internal/email-templates/CONFIRMATION").cookie(admin).with(csrf())
                .contentType("application/json")
                .content("{\"stageKey\":\"stage1\",\"subject\":\"S {{workspace_name}}\",\"body\":\"Hi {{candidate_name}}\"}"))
            .andExpect(status().isOk());
        assertThat(count(AuthEventType.EMAIL_TEMPLATE_EDITED, "CONFIRMATION/stage1/variant_edit")).isEqualTo(1);
    }
}
