package com.cadence.scheduling;

import com.cadence.domain.Role;
import com.cadence.domain.WorkspaceConfig;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F23 per-workspace cascade settings contract (FR-014/SC-011). ADMIN-only PATCH; cross-field validation
 * {@code 0 < escalation < lead <= cascadeQueryBound} (the global bound is PT72H).
 */
class NoShowSettingsContractTest extends SchedulingItBase {

    private void patchConfig(String body, com.cadence.domain.Member admin, int expectedStatus) throws Exception {
        mvc.perform(patch("/api/internal/workspace/config").cookie(cookie(admin)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().is(expectedStatus));
    }

    @Test
    void validOffsetsPersist() throws Exception {
        configuredWorkspace();
        var admin = member("admin@x.test", Role.ADMIN);
        patchConfig("{\"confirmationLeadTime\":\"PT12H\",\"unconfirmedEscalationDeadline\":\"PT3H\"}", admin, 200);

        WorkspaceConfig c = mongoTemplate.findById(
            mongoTemplate.findOne(org.springframework.data.mongodb.core.query.Query.query(
                org.springframework.data.mongodb.core.query.Criteria.where("workspaceId").is(WS)),
                WorkspaceConfig.class).getId(), WorkspaceConfig.class);
        assertThat(c.getConfirmationLeadTime()).isEqualTo(Duration.ofHours(12));
        assertThat(c.getUnconfirmedEscalationDeadline()).isEqualTo(Duration.ofHours(3));
    }

    @Test
    void escalationNotBeforeLead_rejected() throws Exception {
        configuredWorkspace();
        var admin = member("admin@x.test", Role.ADMIN);
        // escalation >= lead -> 400, prior settings retained (null).
        patchConfig("{\"confirmationLeadTime\":\"PT2H\",\"unconfirmedEscalationDeadline\":\"PT2H\"}", admin, 400);
        assertThat(mongoTemplate.findOne(org.springframework.data.mongodb.core.query.Query.query(
                org.springframework.data.mongodb.core.query.Criteria.where("workspaceId").is(WS)),
                WorkspaceConfig.class).getConfirmationLeadTime()).isNull();
    }

    @Test
    void leadAboveQueryBound_rejected() throws Exception {
        configuredWorkspace();
        var admin = member("admin@x.test", Role.ADMIN);
        // lead 100h > cascadeQueryBound 72h -> 400.
        patchConfig("{\"confirmationLeadTime\":\"PT100H\",\"unconfirmedEscalationDeadline\":\"PT2H\"}", admin, 400);
    }

    @Test
    void leadAtBound_accepted() throws Exception {
        configuredWorkspace();
        var admin = member("admin@x.test", Role.ADMIN);
        patchConfig("{\"confirmationLeadTime\":\"PT72H\",\"unconfirmedEscalationDeadline\":\"PT2H\"}", admin, 200);
    }

    @Test
    void nonAdmin_forbidden() throws Exception {
        configuredWorkspace();
        var rec = member("rec@x.test", Role.RECRUITER);
        patchConfig("{\"confirmationLeadTime\":\"PT12H\",\"unconfirmedEscalationDeadline\":\"PT3H\"}", rec, 403);
    }
}
