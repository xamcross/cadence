package com.cadence.dashboard;

import com.cadence.BaseIntegrationTest;
import com.cadence.auth.AuthTestConfig;
import com.cadence.auth.MutableClock;
import com.cadence.domain.AuthAuditEvent;
import com.cadence.domain.Candidate;
import com.cadence.domain.CandidateStatusOutcome;
import com.cadence.domain.ErasureState;
import com.cadence.domain.LawfulBasis;
import com.cadence.domain.Member;
import com.cadence.domain.Role;
import com.cadence.domain.SchedulingRequest;
import com.cadence.domain.SchedulingStatus;
import com.cadence.domain.Session;
import com.cadence.domain.SlaNudgeDraft;
import com.cadence.domain.WorkingHours;
import com.cadence.domain.WorkspaceConfig;
import com.cadence.service.DashboardService;
import com.cadence.service.MemberService;
import com.cadence.service.SessionService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shared fixture for the F50 Core Dashboard integration/contract tests. Singleton MongoDBContainer (via
 * {@link BaseIntegrationTest}), the mutable test {@link MutableClock} pinned to a fixed NOW so the windowed
 * metrics, the past-interview check, and {@code daysSilent} are deterministic. Remove-not-drop cleanup
 * (CLAUDE.md F00.1 — dropCollection would drop the Mongock indexes). Seeds carry a DISTINCT {@code tokenHash}
 * per scheduling row (the F23 lesson: the {@code {tokenHash}} index is PLAIN-unique, so two null-tokenHash rows
 * collide).
 */
@AutoConfigureMockMvc
@Import(AuthTestConfig.class)
abstract class DashboardItBase extends BaseIntegrationTest {

    protected static final String WS = "ws1";
    /** Fixed NOW for every dashboard test (deterministic windows). */
    protected static final Instant NOW = AuthTestConfig.FIXED_START;

    @Autowired protected MockMvc mvc;
    @Autowired protected MemberService memberService;
    @Autowired protected SessionService sessionService;
    @Autowired protected MutableClock clock;
    @Autowired protected DashboardService dashboardService;

    private final AtomicInteger seq = new AtomicInteger(0);

    @BeforeEach
    void cleanDashboard() {
        clock.set(NOW);
        mongoTemplate.remove(new Query(), Member.class);
        mongoTemplate.remove(new Query(), Session.class);
        mongoTemplate.remove(new Query(), AuthAuditEvent.class);
        mongoTemplate.remove(new Query(), WorkspaceConfig.class);
        mongoTemplate.remove(new Query(), Candidate.class);
        mongoTemplate.remove(new Query(), SchedulingRequest.class);
        mongoTemplate.remove(new Query(), SlaNudgeDraft.class);
    }

    protected Instant now() {
        return Instant.now(clock);
    }

    protected Member member(String email, Role role) {
        return memberService.create(WS, email, email, role, null, null);
    }

    protected Cookie cookie(Member m) {
        return new Cookie("cad_session", sessionService.issue(m).jwt());
    }

    /** A configured workspace with a 5-day silence window (UTC), so the silence classification has a window. */
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

    /** A live BOOKED scheduling row. {@code noShowAt} null => attended; non-null => no-show. Distinct tokenHash. */
    protected SchedulingRequest seedBooked(String id, Instant sentAt, Instant bookedAt,
                                           Instant bookedStartAt, Instant noShowAt) {
        SchedulingRequest r = new SchedulingRequest();
        r.setId(id);
        r.setWorkspaceId(WS);
        r.setCandidateId("cand-" + id);
        r.setStatus(SchedulingStatus.BOOKED);
        r.setTokenHash("hash-" + id + "-" + seq.incrementAndGet());
        r.setSentAt(sentAt);
        r.setBookedAt(bookedAt);
        r.setBookedStartAt(bookedStartAt);
        r.setNoShowAt(noShowAt);
        r.setCreatedAt(sentAt);
        return mongoTemplate.save(r);
    }

    /** A scheduling row in an arbitrary status (for the not-double-counted / excluded-state cases). */
    protected SchedulingRequest seedStatus(String id, SchedulingStatus status, Instant bookedAt,
                                           Instant bookedStartAt) {
        SchedulingRequest r = new SchedulingRequest();
        r.setId(id);
        r.setWorkspaceId(WS);
        r.setCandidateId("cand-" + id);
        r.setStatus(status);
        r.setTokenHash("hash-" + id + "-" + seq.incrementAndGet());
        r.setSentAt(bookedAt == null ? null : bookedAt.minus(Duration.ofHours(1)));
        r.setBookedAt(bookedAt);
        r.setBookedStartAt(bookedStartAt);
        r.setCreatedAt(NOW.minus(Duration.ofDays(1)));
        return mongoTemplate.save(r);
    }

    /** A candidate whose last activity is {@code lastContactAt}; outcome/erasure control silence membership. */
    protected Candidate seedCandidate(String id, String name, Instant lastContactAt,
                                      CandidateStatusOutcome outcome, ErasureState erasureState) {
        Candidate c = new Candidate();
        c.setId(id);
        c.setWorkspaceId(WS);
        c.setName(name);
        c.setEmail(id + "@x.test");
        c.setLawfulBasis(LawfulBasis.CONSENT);
        c.setBasisRecordedAt(Instant.parse("2026-01-01T00:00:00Z"));
        c.setErasureState(erasureState);
        c.setStatusOutcome(outcome);
        c.setLastContactAt(lastContactAt);
        c.setCreatedAt(lastContactAt);
        return mongoTemplate.save(c);
    }

    /** Convenience: an ACTIVE, in-progress candidate silent for {@code daysAgo} days. */
    protected Candidate seedSilent(String id, String name, long daysAgo) {
        return seedCandidate(id, name, NOW.minus(Duration.ofDays(daysAgo)),
            CandidateStatusOutcome.IN_PROGRESS, ErasureState.ACTIVE);
    }
}
