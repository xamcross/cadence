package com.cadence.workspace;

import com.cadence.api.WorkspaceDtos;
import com.cadence.domain.AuthAuditEvent;
import com.cadence.domain.AuthEventType;
import com.cadence.domain.Member;
import com.cadence.domain.Role;
import com.cadence.domain.WorkspaceConfig;
import com.cadence.service.WorkspaceConfigService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.time.LocalTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** T027 (US2): ongoing settings — persist, validation no-partial, PATCH-on-unconfigured 409,
 *  concurrent different-field (no lost update) and same-field (consistent, both audited). */
class WorkspaceSettingsIntegrationTest extends WorkspaceItBase {

    @Autowired WorkspaceConfigService service;

    private Member configure() {
        Member admin = member("admin@x.com", Role.ADMIN);
        service.completeSetup("ws1", admin.getId(), new WorkspaceDtos.SetupRequest("Acme", "Europe/London",
            new WorkspaceDtos.WorkingHoursDto(LocalTime.of(9, 0), LocalTime.of(17, 0)), 5, 365, true));
        return admin;
    }

    @Test
    void update_persists() throws Exception {
        Member admin = configure();
        mvc.perform(patch("/api/internal/workspace/config").cookie(cookie(admin)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"slaSilenceWindowDays\":7}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.slaSilenceWindowDays").value(7));
        assertThat(configs.findByWorkspaceId("ws1").orElseThrow().getSlaSilenceWindowDays()).isEqualTo(7);
    }

    @Test
    void invalidUpdate_refused_noPartialWrite() throws Exception {
        Member admin = configure();
        mvc.perform(patch("/api/internal/workspace/config").cookie(cookie(admin)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Beta\",\"slaSilenceWindowDays\":99}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("validation_failed"));
        WorkspaceConfig c = configs.findByWorkspaceId("ws1").orElseThrow();
        assertThat(c.getName()).isEqualTo("Acme"); // name NOT applied (all-or-nothing)
        assertThat(c.getSlaSilenceWindowDays()).isEqualTo(5);
    }

    @Test
    void patch_onUnconfiguredWorkspace_409() throws Exception {
        Member admin = member("admin@x.com", Role.ADMIN); // not configured
        mvc.perform(patch("/api/internal/workspace/config").cookie(cookie(admin)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"slaSilenceWindowDays\":7}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("not_configured"));
    }

    @Test
    void concurrentDifferentFieldEdits_bothPreserved() throws Exception {
        configure();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        pool.submit(() -> race(ready, go, new WorkspaceDtos.SettingsPatch(null, null, null, 7, null)));
        pool.submit(() -> race(ready, go, new WorkspaceDtos.SettingsPatch("Beta", null, null, null, null)));
        ready.await();
        go.countDown();
        pool.shutdown();
        //noinspection ResultOfMethodCallIgnored
        pool.awaitTermination(15, TimeUnit.SECONDS);
        WorkspaceConfig c = configs.findByWorkspaceId("ws1").orElseThrow();
        assertThat(c.getSlaSilenceWindowDays()).isEqualTo(7); // neither lost
        assertThat(c.getName()).isEqualTo("Beta");
    }

    @Test
    void concurrentSameFieldEdits_consistent_bothAudited() throws Exception {
        configure();
        int n = 6;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch go = new CountDownLatch(1);
        for (int i = 0; i < n; i++) {
            int sla = 1 + i; // distinct valid SLA values
            pool.submit(() -> race(ready, go, new WorkspaceDtos.SettingsPatch(null, null, null, sla, null)));
        }
        ready.await();
        go.countDown();
        pool.shutdown();
        //noinspection ResultOfMethodCallIgnored
        pool.awaitTermination(15, TimeUnit.SECONDS);
        int finalSla = configs.findByWorkspaceId("ws1").orElseThrow().getSlaSilenceWindowDays();
        assertThat(finalSla).isBetween(1, n); // exactly one submitted value, internally consistent
        long slaAudits = mongoTemplate.findAll(AuthAuditEvent.class).stream()
            .filter(a -> a.getEventType() == AuthEventType.WORKSPACE_CONFIG_CHANGED
                && "sla_window".equals(a.getOutcome())).count();
        assertThat(slaAudits).isEqualTo(n); // every attempt audited
    }

    private void race(CountDownLatch ready, CountDownLatch go, WorkspaceDtos.SettingsPatch patch) {
        ready.countDown();
        try {
            go.await();
            service.updateSettings("ws1", "admin", patch);
        } catch (RuntimeException | InterruptedException ignored) {
        }
    }
}
