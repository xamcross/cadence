package com.cadence.sla;

import com.cadence.BaseIntegrationTest;
import com.cadence.auth.AuthTestConfig;
import com.cadence.auth.MutableClock;
import com.cadence.domain.AuthAuditEvent;
import com.cadence.domain.Candidate;
import com.cadence.domain.CandidateAuditEvent;
import com.cadence.domain.EmailDispatch;
import com.cadence.domain.ErasureState;
import com.cadence.domain.LawfulBasis;
import com.cadence.domain.Member;
import com.cadence.domain.RecruiterNotification;
import com.cadence.domain.Role;
import com.cadence.domain.Session;
import com.cadence.domain.SlaNudgeDraft;
import com.cadence.domain.WorkingHours;
import com.cadence.domain.WorkspaceConfig;
import com.cadence.domain.WorkspaceEntitlement;
import com.cadence.security.TokenHasher;
import com.cadence.service.MemberService;
import com.cadence.service.SessionService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalTime;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shared fixture for the F31 SLA Nudge Engine integration/contract tests. Singleton MongoDBContainer (via
 * {@link BaseIntegrationTest}), the mutable test {@link MutableClock} (deterministic breach timing — stamp
 * {@code lastContactAt}, never wall-clock sleeps), MockMvc + member/cookie helpers, and a candidate seeder that
 * takes an explicit {@code lastContactAt}. Remove-not-drop cleanup (CLAUDE.md F00.1 — dropCollection would drop
 * the Mongock indexes incl. the partial {workspaceId,candidateId} OPEN-draft index).
 */
@AutoConfigureMockMvc
@Import(AuthTestConfig.class)
abstract class SlaItBase extends BaseIntegrationTest {

    protected static final String WS = "ws1";

    @Autowired protected MockMvc mvc;
    @Autowired protected MemberService memberService;
    @Autowired protected SessionService sessionService;
    @Autowired protected MutableClock clock;
    @Autowired protected TokenHasher hasher;

    private static final AtomicInteger TEST_SEQ = new AtomicInteger(0);

    @BeforeEach
    void cleanSla() {
        clock.set(AuthTestConfig.FIXED_START.plusSeconds(60L * TEST_SEQ.incrementAndGet()));
        mongoTemplate.remove(new Query(), Member.class);
        mongoTemplate.remove(new Query(), Session.class);
        mongoTemplate.remove(new Query(), AuthAuditEvent.class);
        mongoTemplate.remove(new Query(), CandidateAuditEvent.class);
        mongoTemplate.remove(new Query(), WorkspaceConfig.class);
        mongoTemplate.remove(new Query(), Candidate.class);
        mongoTemplate.remove(new Query(), SlaNudgeDraft.class);
        mongoTemplate.remove(new Query(), EmailDispatch.class);
        mongoTemplate.remove(new Query(), RecruiterNotification.class);
        // 032 T7: this suite predates billing and never modeled a plan -- seed Team so the SLA_NUDGES gate does
        // not block these pre-existing F31 fixtures. Cleared first: another package's ItBase sharing WS ("ws1")
        // may have left a stale row (no cross-package scoping).
        mongoTemplate.remove(Query.query(Criteria.where("workspaceId").is(WS)), WorkspaceEntitlement.class);
        WorkspaceEntitlement e = new WorkspaceEntitlement();
        e.setWorkspaceId(WS);
        e.setFsLicenseId("lic-" + WS + "-sla");
        e.setFsPlanId("2002");
        mongoTemplate.insert(e);
    }

    protected Member member(String email, Role role) {
        return memberService.create(WS, email, email, role, null, null);
    }

    protected Cookie cookie(Member m) {
        return new Cookie("cad_session", sessionService.issue(m).jwt());
    }

    /** A configured workspace with a 5-day silence window (zone UTC). */
    protected void configuredWorkspace() {
        WorkspaceConfig c = new WorkspaceConfig();
        c.setWorkspaceId(WS);
        c.setName("Test WS");
        c.setConfiguredAt(Instant.parse("2026-01-01T00:00:00Z"));
        c.setTimeZone("UTC");
        c.setWorkingHours(new WorkingHours(LocalTime.of(9, 0), LocalTime.of(17, 0)));
        c.setSlaSilenceWindowDays(5);
        c.setRetentionPeriodDays(365);
        c.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        c.setUpdatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        mongoTemplate.save(c);
    }

    /** A contactable (ACTIVE, consent) candidate whose last activity is {@code now - daysAgo} days. */
    protected Candidate seedCandidate(String id, String name, String email, long daysAgo) {
        Instant now = Instant.now(clock);
        Candidate c = new Candidate();
        c.setId(id);
        c.setWorkspaceId(WS);
        c.setName(name);
        c.setEmail(email);
        c.setLawfulBasis(LawfulBasis.CONSENT);
        c.setBasisRecordedAt(Instant.parse("2026-01-01T00:00:00Z"));
        c.setErasureState(ErasureState.ACTIVE);
        c.setLastContactAt(now.minus(java.time.Duration.ofDays(daysAgo)));
        c.setCreatedAt(now.minus(java.time.Duration.ofDays(daysAgo)));
        return mongoTemplate.save(c);
    }

    protected long emailDispatchCount() {
        return mongoTemplate.count(new Query(), EmailDispatch.class);
    }

    protected long openDraftCount() {
        return mongoTemplate.count(
            Query.query(org.springframework.data.mongodb.core.query.Criteria.where("status").is("OPEN")),
            SlaNudgeDraft.class);
    }
}
