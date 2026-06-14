package com.cadence.workspace;

import com.cadence.api.WorkspaceDtos;
import com.cadence.domain.AuthAuditEvent;
import com.cadence.domain.AuthEventType;
import com.cadence.domain.Member;
import com.cadence.domain.Role;
import com.cadence.domain.WorkspaceConfig;
import com.cadence.service.WorkspaceConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.MediaType;

import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** T036 (US4): credential encrypted at rest, never returned (incl. entity serialize), rotate/unset
 *  audited, domain validation, cold-converter restart decrypt. */
class EmailConfigIntegrationTest extends WorkspaceItBase {

    @Autowired WorkspaceConfigService service;
    @Autowired ObjectMapper objectMapper;

    private static final String SENTINEL = "SG.SENTINEL_DO_NOT_LOG_abcdef0123456789";

    private Cookie configuredAdmin() {
        Member admin = member("admin@x.com", Role.ADMIN);
        service.completeSetup("ws1", admin.getId(), new WorkspaceDtos.SetupRequest("Acme", "Europe/London",
            new WorkspaceDtos.WorkingHoursDto(LocalTime.of(9, 0), LocalTime.of(17, 0)), 5, 365, true));
        return cookie(admin);
    }

    private void setEmail(Cookie admin, String domain, String credential) throws Exception {
        mvc.perform(put("/api/internal/workspace/email").cookie(admin).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sendingDomain\":\"" + domain + "\",\"credential\":\"" + credential + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.credentialSet").value(true))
            .andExpect(jsonPath("$.credential").doesNotExist());
    }

    @Test
    void credential_ciphertextAtRest_viaRawDriver() throws Exception {
        setEmail(configuredAdmin(), "careers.acme.com", SENTINEL);
        Document raw = mongoTemplate.getCollection("workspaceConfig").find().first();
        assertThat(raw).isNotNull();
        String stored = raw.getString("emailProviderCredential");
        assertThat(stored).isNotNull().isNotEqualTo(SENTINEL);
        assertThat(stored).doesNotContain(SENTINEL);
    }

    @Test
    void credential_neverReturned_anyRole_norOnEntitySerialize() throws Exception {
        Cookie admin = configuredAdmin();
        setEmail(admin, "careers.acme.com", SENTINEL);
        mvc.perform(get("/api/internal/workspace/config").cookie(admin))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.credentialSet").value(true))
            .andExpect(jsonPath("$.emailProviderCredential").doesNotExist())
            .andExpect(jsonPath("$.credential").doesNotExist());
        // Even serializing the entity itself must not leak it (@JsonIgnore — structural never-return).
        WorkspaceConfig c = configs.findByWorkspaceId("ws1").orElseThrow();
        String json = objectMapper.writeValueAsString(c);
        assertThat(json).doesNotContain(SENTINEL).doesNotContain("emailProviderCredential");
        assertThat(c.toString()).doesNotContain(SENTINEL);
    }

    @Test
    void rotateThenUnset_audited() throws Exception {
        Cookie admin = configuredAdmin();
        setEmail(admin, "careers.acme.com", SENTINEL);
        setEmail(admin, "careers.acme.com", "SG.ROTATED_value_98765"); // rotate
        mvc.perform(delete("/api/internal/workspace/email/credential").cookie(admin).with(csrf()))
            .andExpect(status().isNoContent());
        mvc.perform(get("/api/internal/workspace/config").cookie(admin))
            .andExpect(jsonPath("$.credentialSet").value(false));
        long emailAudits = mongoTemplate.findAll(AuthAuditEvent.class).stream()
            .filter(a -> a.getEventType() == AuthEventType.WORKSPACE_CONFIG_CHANGED
                && "email_config".equals(a.getOutcome())).count();
        assertThat(emailAudits).isEqualTo(3); // set + rotate + unset
        // No audit row carries the credential value.
        assertThat(mongoTemplate.findAll(AuthAuditEvent.class))
            .noneMatch(a -> SENTINEL.equals(a.getNewValue()) || SENTINEL.equals(a.getOldValue()));
    }

    @Test
    void malformedDomain_rejected() throws Exception {
        Cookie admin = configuredAdmin();
        mvc.perform(put("/api/internal/workspace/email").cookie(admin).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sendingDomain\":\"x\\u0000.com\",\"credential\":\"k\"}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("validation_failed"));
    }

    @Test
    void credential_decryptsAfterColdConverterReload() throws Exception {
        setEmail(configuredAdmin(), "careers.acme.com", SENTINEL);
        MongoTemplate cold = coldTemplate(true); // fresh PII converter, as on restart
        WorkspaceConfig c = cold.findOne(
            Query.query(Criteria.where("workspaceId").is("ws1")), WorkspaceConfig.class);
        assertThat(c).isNotNull();
        assertThat(c.getEmailProviderCredential()).isEqualTo(SENTINEL); // decrypts to original
    }
}
