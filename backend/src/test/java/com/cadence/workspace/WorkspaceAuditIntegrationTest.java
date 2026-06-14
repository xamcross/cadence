package com.cadence.workspace;

import com.cadence.api.WorkspaceDtos;
import com.cadence.domain.AuthAuditEvent;
import com.cadence.domain.AuthEventType;
import com.cadence.domain.Member;
import com.cadence.domain.Role;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** T049 (SC-003/SC-010/SC-013): config changes are audited with non-PII fields; retention records
 *  old/new; non-retention records the setting code with null old/new (never a credential value). */
class WorkspaceAuditIntegrationTest extends WorkspaceItBase {

    @Autowired com.cadence.service.WorkspaceConfigService service;

    private Member configure() {
        Member admin = member("admin@x.com", Role.ADMIN);
        service.completeSetup("ws1", admin.getId(), new WorkspaceDtos.SetupRequest("Acme", "Europe/London",
            new WorkspaceDtos.WorkingHoursDto(LocalTime.of(9, 0), LocalTime.of(17, 0)), 5, 365, true));
        return admin;
    }

    @Test
    void setupCompletion_recordsAcknowledgedRetention() {
        configure();
        AuthAuditEvent configured = mongoTemplate.findAll(AuthAuditEvent.class).stream()
            .filter(a -> a.getEventType() == AuthEventType.WORKSPACE_CONFIGURED).findFirst().orElseThrow();
        assertThat(configured.getNewValue()).isEqualTo("365"); // SC-003 acknowledged period evidence
        assertThat(configured.getMemberId()).isNotBlank();
    }

    @Test
    void retentionChange_recordsOldAndNew() throws Exception {
        Member admin = configure();
        mvc.perform(patch("/api/internal/workspace/config").cookie(cookie(admin)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"retentionPeriodDays\":730}"))
            .andExpect(status().isOk());
        AuthAuditEvent ev = mongoTemplate.findAll(AuthAuditEvent.class).stream()
            .filter(a -> "retention_period".equals(a.getOutcome())).findFirst().orElseThrow();
        assertThat(ev.getOldValue()).isEqualTo("365");
        assertThat(ev.getNewValue()).isEqualTo("730");
    }

    @Test
    void nonRetentionChange_hasNullOldNew() throws Exception {
        Member admin = configure();
        mvc.perform(patch("/api/internal/workspace/config").cookie(cookie(admin)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"slaSilenceWindowDays\":9}"))
            .andExpect(status().isOk());
        AuthAuditEvent ev = mongoTemplate.findAll(AuthAuditEvent.class).stream()
            .filter(a -> "sla_window".equals(a.getOutcome())).findFirst().orElseThrow();
        assertThat(ev.getOldValue()).isNull();
        assertThat(ev.getNewValue()).isNull();
    }

    @Test
    void auditRows_carryNoPii() {
        configure();
        List<AuthAuditEvent> all = mongoTemplate.findAll(AuthAuditEvent.class);
        assertThat(all).isNotEmpty();
        // Non-PII: no email/name appears in any auditable text field.
        for (AuthAuditEvent a : all) {
            assertThat(a.getOutcome() == null ? "" : a.getOutcome()).doesNotContain("@");
            assertThat(a.getNewValue() == null ? "" : a.getNewValue()).doesNotContain("@");
        }
    }
}
