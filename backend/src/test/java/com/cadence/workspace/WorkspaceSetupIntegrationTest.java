package com.cadence.workspace;

import com.cadence.api.WorkspaceDtos;
import com.cadence.domain.AuthAuditEvent;
import com.cadence.domain.AuthEventType;
import com.cadence.domain.Member;
import com.cadence.domain.Role;
import com.cadence.domain.WorkspaceConfig;
import com.cadence.service.WorkspaceConfigService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.MediaType;

import java.time.LocalTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** T022 (US1): first-run wizard — persistence, GDPR gate, re-completion 409, read-only invariant,
 *  concurrent first-run race, restart-persistence. */
class WorkspaceSetupIntegrationTest extends WorkspaceItBase {

    @Autowired WorkspaceConfigService service;

    private static final String VALID =
        "{\"name\":\"Acme\",\"timeZone\":\"Europe/London\","
        + "\"workingHours\":{\"start\":\"09:00\",\"end\":\"17:00\"},"
        + "\"slaSilenceWindowDays\":5,\"retentionPeriodDays\":365,\"retentionAcknowledged\":true}";

    @Test
    void validSetup_persistsConfigured() throws Exception {
        mvc.perform(post("/api/internal/workspace/setup").cookie(adminCookie()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(VALID))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.configured").value(true))
            .andExpect(jsonPath("$.slaSilenceWindowDays").value(5));
        WorkspaceConfig c = configs.findByWorkspaceId("ws1").orElseThrow();
        assertThat(c.getConfiguredAt()).isNotNull();
        assertThat(c.getRetentionAcknowledgedAt()).isNotNull();
    }

    @Test
    void ackMissing_refused_staysUnconfigured() throws Exception {
        String body = VALID.replace("\"retentionAcknowledged\":true", "\"retentionAcknowledged\":false");
        mvc.perform(post("/api/internal/workspace/setup").cookie(adminCookie()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("retention_not_acknowledged"));
        assertThat(configs.findByWorkspaceId("ws1")).isEmpty();
    }

    @Test
    void invalidField_refused_nothingPersisted() throws Exception {
        String body = VALID.replace("Europe/London", "Mars/Phobos").replace("365", "10");
        mvc.perform(post("/api/internal/workspace/setup").cookie(adminCookie()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("validation_failed"))
            .andExpect(jsonPath("$.fields.timeZone").exists());
        assertThat(configs.findByWorkspaceId("ws1")).isEmpty();
    }

    @Test
    void reCompletion_onConfiguredWorkspace_refused409() throws Exception {
        Cookie admin = adminCookie();
        mvc.perform(post("/api/internal/workspace/setup").cookie(admin).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(VALID)).andExpect(status().isOk());
        mvc.perform(post("/api/internal/workspace/setup").cookie(admin).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(VALID))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").value("already_configured"));
    }

    @Test
    void reads_neverCreateTheDocument() throws Exception {
        Cookie admin = adminCookie();
        mvc.perform(get("/api/internal/workspace/config").cookie(admin))
            .andExpect(status().isOk()).andExpect(jsonPath("$.configured").value(false));
        mvc.perform(get("/api/internal/auth/me").cookie(admin)).andExpect(status().isOk());
        assertThat(mongoTemplate.getCollection("workspaceConfig").countDocuments()).isZero();
    }

    @Test
    void concurrentFirstRun_exactlyOneConfigured_bothAttemptsAudited() throws Exception {
        member("admin@x.com", Role.ADMIN); // ensure an actor exists
        int n = 8;
        WorkspaceDtos.SetupRequest req = new WorkspaceDtos.SetupRequest("Acme", "Europe/London",
            new WorkspaceDtos.WorkingHoursDto(LocalTime.of(9, 0), LocalTime.of(17, 0)), 5, 365, true);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        for (int i = 0; i < n; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                    service.completeSetup("ws1", "admin", req);
                    ok.incrementAndGet();
                } catch (RuntimeException | InterruptedException ignored) {
                }
            });
        }
        ready.await();
        go.countDown();
        pool.shutdown();
        //noinspection ResultOfMethodCallIgnored
        pool.awaitTermination(15, TimeUnit.SECONDS);

        assertThat(ok.get()).isEqualTo(1);
        assertThat(configs.findAll()).hasSize(1);
        assertThat(configs.findByWorkspaceId("ws1").orElseThrow().getConfiguredAt()).isNotNull();
        List<AuthAuditEvent> audits = mongoTemplate.findAll(AuthAuditEvent.class);
        long completed = audits.stream().filter(a -> a.getEventType() == AuthEventType.WORKSPACE_CONFIGURED).count();
        long conflicts = audits.stream().filter(a -> a.getEventType() == AuthEventType.WORKSPACE_CONFIG_CHANGED
            && "setup_conflict".equals(a.getOutcome())).count();
        assertThat(completed).isEqualTo(1);
        assertThat(conflicts).isEqualTo(n - 1L); // both/all attempts audited (US1 AS-7)
    }

    @Test
    void settings_survive_coldConverterReload() throws Exception {
        mvc.perform(post("/api/internal/workspace/setup").cookie(adminCookie()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(VALID)).andExpect(status().isOk());
        // Read through a freshly-built cold MongoTemplate (no in-process cache) — settings unchanged.
        MongoTemplate cold = coldTemplate(false);
        WorkspaceConfig c = cold.findOne(
            org.springframework.data.mongodb.core.query.Query.query(
                org.springframework.data.mongodb.core.query.Criteria.where("workspaceId").is("ws1")),
            WorkspaceConfig.class);
        assertThat(c).isNotNull();
        assertThat(c.getName()).isEqualTo("Acme");
        assertThat(c.getTimeZone()).isEqualTo("Europe/London");
        assertThat(c.getSlaSilenceWindowDays()).isEqualTo(5);
        assertThat(c.getRetentionPeriodDays()).isEqualTo(365);
    }

    @Test
    void me_exposesWorkspaceConfiguredFlag() throws Exception {
        Member admin = member("admin@x.com", Role.ADMIN);
        mvc.perform(get("/api/internal/auth/me").cookie(cookie(admin)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.workspaceConfigured").value(false));
        service.completeSetup("ws1", admin.getId(), new WorkspaceDtos.SetupRequest("Acme", "Europe/London",
            new WorkspaceDtos.WorkingHoursDto(LocalTime.of(9, 0), LocalTime.of(17, 0)), 5, 365, true));
        mvc.perform(get("/api/internal/auth/me").cookie(cookie(admin)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.workspaceConfigured").value(true));
    }
}
