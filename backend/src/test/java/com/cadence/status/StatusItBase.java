package com.cadence.status;

import com.cadence.BaseIntegrationTest;
import com.cadence.auth.AuthTestConfig;
import com.cadence.auth.MutableClock;
import com.cadence.domain.AuthAuditEvent;
import com.cadence.domain.Candidate;
import com.cadence.domain.CandidateAuditEvent;
import com.cadence.domain.ErasureRequest;
import com.cadence.domain.ErasureState;
import com.cadence.domain.LawfulBasis;
import com.cadence.domain.Member;
import com.cadence.domain.Role;
import com.cadence.domain.Session;
import com.cadence.domain.WorkingHours;
import com.cadence.domain.WorkspaceConfig;
import com.cadence.security.TokenHasher;
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
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shared fixture for the F30 Candidate Status Page integration/contract tests. Singleton MongoDBContainer
 * (via {@link BaseIntegrationTest}), the mutable test {@link MutableClock} (deterministic displayState +
 * audit timestamps), MockMvc + member/cookie helpers, a contactable-candidate + workspace-config seeder.
 * Remove-not-drop cleanup (CLAUDE.md F00.1 — dropCollection would drop the Mongock indexes incl. the
 * partial {statusTokenHash} / erasureRequests-PENDING indexes).
 */
@AutoConfigureMockMvc
@Import(AuthTestConfig.class)
abstract class StatusItBase extends BaseIntegrationTest {

    protected static final String WS = "ws1";

    @Autowired protected MockMvc mvc;
    @Autowired protected MemberService memberService;
    @Autowired protected SessionService sessionService;
    @Autowired protected MutableClock clock;
    @Autowired protected TokenHasher hasher;

    // The CandidateRateLimiter is an in-memory singleton keyed by (hashed IP, minute) and survives the per-test
    // DB cleanup. Each test runs in a UNIQUE minute (advance the frozen clock by a per-test offset) so a prior
    // test's limiter window never bleeds in.
    private static final AtomicInteger TEST_SEQ = new AtomicInteger(0);

    @BeforeEach
    void cleanStatus() {
        clock.set(AuthTestConfig.FIXED_START.plusSeconds(60L * TEST_SEQ.incrementAndGet()));
        mongoTemplate.remove(new Query(), Member.class);
        mongoTemplate.remove(new Query(), Session.class);
        mongoTemplate.remove(new Query(), AuthAuditEvent.class);
        mongoTemplate.remove(new Query(), CandidateAuditEvent.class);
        mongoTemplate.remove(new Query(), WorkspaceConfig.class);
        mongoTemplate.remove(new Query(), Candidate.class);
        mongoTemplate.remove(new Query(), ErasureRequest.class);
    }

    protected Member member(String email, Role role) {
        return memberService.create(WS, email, email, role, null, null);
    }

    protected Cookie cookie(Member m) {
        return new Cookie("cad_session", sessionService.issue(m).jwt());
    }

    /** Seed a configured workspace so the status service can resolve the zone. */
    protected void configuredWorkspace(String zone) {
        WorkspaceConfig c = new WorkspaceConfig();
        c.setWorkspaceId(WS);
        c.setName("Test WS");
        c.setConfiguredAt(Instant.parse("2026-01-01T00:00:00Z"));
        c.setTimeZone(zone);
        c.setWorkingHours(new WorkingHours(LocalTime.of(9, 0), LocalTime.of(17, 0)));
        c.setSlaSilenceWindowDays(5);
        c.setRetentionPeriodDays(365);
        c.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        c.setUpdatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        mongoTemplate.save(c);
    }

    protected void configuredWorkspace() {
        configuredWorkspace("UTC");
    }

    /** Seed a contactable candidate (ACTIVE, basis recorded; name+email encrypted via the converter). */
    protected Candidate seedCandidate(String id, String name, String email) {
        Candidate c = new Candidate();
        c.setId(id);
        c.setWorkspaceId(WS);
        c.setName(name);
        c.setEmail(email);
        c.setLawfulBasis(LawfulBasis.CONSENT);
        c.setBasisRecordedAt(Instant.parse("2026-01-01T00:00:00Z"));
        c.setErasureState(ErasureState.ACTIVE);
        c.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        return mongoTemplate.save(c);
    }
}
