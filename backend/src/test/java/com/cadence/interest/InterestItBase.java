package com.cadence.interest;

import com.cadence.BaseIntegrationTest;
import com.cadence.auth.AuthTestConfig;
import com.cadence.auth.MutableClock;
import com.cadence.domain.AuthAuditEvent;
import com.cadence.domain.InterestRequest;
import com.cadence.domain.Invitation;
import com.cadence.domain.Member;
import com.cadence.domain.RecruiterNotification;
import com.cadence.domain.Role;
import com.cadence.domain.Session;
import com.cadence.domain.WorkspaceConfig;
import com.cadence.repository.InterestRequestRepository;
import com.cadence.security.PiiCrypto;
import com.cadence.service.InterestRequestService;
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
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shared fixture for the F70 interest tests. Singleton MongoDBContainer (via {@link BaseIntegrationTest}), the
 * mutable test {@link MutableClock} (deterministic timing — never wall-clock sleeps), MockMvc + member/cookie
 * helpers. The default workspace is {@code ws1} (application-test.yml override). Remove-not-drop cleanup
 * (CLAUDE.md F00.1 — dropCollection would drop the Mongock indexes incl. the unique partial {workspaceId,
 * openEmailHash}).
 */
@AutoConfigureMockMvc
@Import(AuthTestConfig.class)
abstract class InterestItBase extends BaseIntegrationTest {

    protected static final String WS = "ws1";

    @Autowired protected MockMvc mvc;
    @Autowired protected MemberService memberService;
    @Autowired protected SessionService sessionService;
    @Autowired protected MutableClock clock;
    @Autowired protected PiiCrypto crypto;
    @Autowired protected InterestRequestService interestService;
    @Autowired protected InterestRequestRepository interestRepo;

    private static final AtomicInteger TEST_SEQ = new AtomicInteger(0);

    @BeforeEach
    void cleanInterest() {
        // Advance well beyond the per-source rate-limit window (test ip-window = PT10M) each test so the
        // singleton InterestRateLimiter's in-memory window does not bleed counts across tests (the limiter is
        // advisory; the durable guard is the per-workspace DB ceiling). 20 minutes per test clears it.
        clock.set(AuthTestConfig.FIXED_START.plus(java.time.Duration.ofMinutes(20L * TEST_SEQ.incrementAndGet())));
        mongoTemplate.remove(new Query(), Member.class);
        mongoTemplate.remove(new Query(), Session.class);
        mongoTemplate.remove(new Query(), AuthAuditEvent.class);
        mongoTemplate.remove(new Query(), WorkspaceConfig.class);
        mongoTemplate.remove(new Query(), Invitation.class);
        mongoTemplate.remove(new Query(), InterestRequest.class);
        mongoTemplate.remove(new Query(), RecruiterNotification.class);
    }

    protected Member member(String email, Role role) {
        return memberService.create(WS, email, email, role, null, null);
    }

    protected Cookie cookie(Member m) {
        return new Cookie("cad_session", sessionService.issue(m).jwt());
    }

    protected void configuredWorkspace(int retentionPeriodDays) {
        WorkspaceConfig c = new WorkspaceConfig();
        c.setWorkspaceId(WS);
        c.setName("Test WS");
        c.setConfiguredAt(Instant.parse("2026-01-01T00:00:00Z"));
        c.setTimeZone("UTC");
        c.setRetentionPeriodDays(retentionPeriodDays);
        c.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        c.setUpdatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        mongoTemplate.save(c);
    }
}
