package com.cadence.interview;

import com.cadence.BaseIntegrationTest;
import com.cadence.auth.AuthTestConfig;
import com.cadence.auth.MutableClock;
import com.cadence.domain.AuthAuditEvent;
import com.cadence.domain.InterviewTemplate;
import com.cadence.domain.ManagedCalendarEvent;
import com.cadence.domain.Member;
import com.cadence.domain.Role;
import com.cadence.domain.Session;
import com.cadence.domain.WorkingHours;
import com.cadence.domain.WorkspaceConfig;
import com.cadence.service.MemberService;
import com.cadence.service.SessionService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalTime;

/**
 * Shared fixture for the F12 interview-template integration/contract tests: MockMvc + member/cookie
 * helpers, a configured-workspace seeder, and remove-not-drop cleanup of every collection these tests
 * touch (CLAUDE.md F00.1 — dropCollection would drop the Mongock 004/007/008 indexes).
 */
@AutoConfigureMockMvc
@Import(AuthTestConfig.class)
abstract class InterviewItBase extends BaseIntegrationTest {

    protected static final String WS = "ws1";

    @Autowired protected MockMvc mvc;
    @Autowired protected MemberService memberService;
    @Autowired protected SessionService sessionService;
    @Autowired protected MutableClock clock;

    @BeforeEach
    void cleanInterview() {
        clock.set(AuthTestConfig.FIXED_START);
        mongoTemplate.remove(new Query(), Member.class);
        mongoTemplate.remove(new Query(), Session.class);
        mongoTemplate.remove(new Query(), AuthAuditEvent.class);
        mongoTemplate.remove(new Query(), WorkspaceConfig.class);
        mongoTemplate.remove(new Query(), InterviewTemplate.class);
        mongoTemplate.remove(new Query(), ManagedCalendarEvent.class);
    }

    protected Member member(String email, Role role) {
        return member(WS, email, role);
    }

    protected Member member(String workspaceId, String email, Role role) {
        return memberService.create(workspaceId, email, email, role, null, null);
    }

    protected Cookie cookie(Member m) {
        return new Cookie("cad_session", sessionService.issue(m).jwt());
    }

    /** Seed a configured workspace (so the rule engine can resolve zone + working hours). */
    protected void configuredWorkspace(String workspaceId, String zone, LocalTime start, LocalTime end) {
        WorkspaceConfig c = new WorkspaceConfig();
        c.setWorkspaceId(workspaceId);
        c.setName("Test WS");
        c.setConfiguredAt(Instant.parse("2026-01-01T00:00:00Z"));
        c.setTimeZone(zone);
        c.setWorkingHours(new WorkingHours(start, end));
        c.setSlaSilenceWindowDays(5);
        c.setRetentionPeriodDays(365);
        c.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        c.setUpdatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        mongoTemplate.save(c);
    }
}
