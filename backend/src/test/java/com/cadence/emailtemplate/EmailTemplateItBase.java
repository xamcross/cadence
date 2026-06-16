package com.cadence.emailtemplate;

import com.cadence.BaseIntegrationTest;
import com.cadence.auth.AuthTestConfig;
import com.cadence.auth.MutableClock;
import com.cadence.domain.AuthAuditEvent;
import com.cadence.domain.Candidate;
import com.cadence.domain.EmailTemplate;
import com.cadence.domain.ErasureState;
import com.cadence.domain.InterviewTemplate;
import com.cadence.domain.Member;
import com.cadence.domain.Role;
import com.cadence.domain.Session;
import com.cadence.domain.TemplateStatus;
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

/**
 * Shared fixture for the F21 email-template integration/contract tests: MockMvc + member/cookie helpers,
 * candidate + interview-stage seeders, and remove-not-drop cleanup of every collection these tests touch
 * (CLAUDE.md F00.1 — dropCollection would drop the Mongock 004/008/009 indexes).
 */
@AutoConfigureMockMvc
@Import(AuthTestConfig.class)
abstract class EmailTemplateItBase extends BaseIntegrationTest {

    protected static final String WS = "ws1";

    @Autowired protected MockMvc mvc;
    @Autowired protected MemberService memberService;
    @Autowired protected SessionService sessionService;
    @Autowired protected MutableClock clock;

    @BeforeEach
    void cleanEmailTemplate() {
        clock.set(AuthTestConfig.FIXED_START);
        mongoTemplate.remove(new Query(), Member.class);
        mongoTemplate.remove(new Query(), Session.class);
        mongoTemplate.remove(new Query(), AuthAuditEvent.class);
        mongoTemplate.remove(new Query(), EmailTemplate.class);
        mongoTemplate.remove(new Query(), InterviewTemplate.class);
        mongoTemplate.remove(new Query(), Candidate.class);
    }

    protected Member member(String email, Role role) {
        return memberService.create(WS, email, email, role, null, null);
    }

    protected Cookie cookie(Member m) {
        return new Cookie("cad_session", sessionService.issue(m).jwt());
    }

    /** Seed a candidate (name stored encrypted via the converter; decrypted on read for preview). */
    protected Candidate seedCandidate(String workspaceId, String id, String name) {
        Candidate c = new Candidate();
        c.setId(id);
        c.setWorkspaceId(workspaceId);
        c.setName(name);
        c.setErasureState(ErasureState.ACTIVE);
        c.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        return mongoTemplate.save(c);
    }

    /** Seed a minimal active interview-stage template (the variant stageKey target). */
    protected InterviewTemplate seedStage(String workspaceId, String id) {
        InterviewTemplate t = new InterviewTemplate();
        t.setId(id);
        t.setWorkspaceId(workspaceId);
        t.setName("Stage");
        t.setStatus(TemplateStatus.ACTIVE);
        t.setDurationMinutes(45);
        t.setSlotCadenceMinutes(15);
        t.setDailyCapPerInterviewer(2);
        t.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        t.setUpdatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        return mongoTemplate.save(t);
    }
}
