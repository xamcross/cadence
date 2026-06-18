package com.cadence.feedback;

import com.cadence.BaseIntegrationTest;
import com.cadence.auth.AuthTestConfig;
import com.cadence.auth.MutableClock;
import com.cadence.domain.AuthAuditEvent;
import com.cadence.domain.Candidate;
import com.cadence.domain.CandidateAuditEvent;
import com.cadence.domain.ClaimStatus;
import com.cadence.domain.ErasureState;
import com.cadence.domain.FeedbackRequest;
import com.cadence.domain.InterviewSlotClaim;
import com.cadence.domain.LawfulBasis;
import com.cadence.domain.Member;
import com.cadence.domain.MemberStatus;
import com.cadence.domain.RecruiterNotification;
import com.cadence.domain.Role;
import com.cadence.domain.SchedulingRequest;
import com.cadence.domain.SchedulingStatus;
import com.cadence.domain.Session;
import com.cadence.domain.WorkingHours;
import com.cadence.domain.WorkspaceConfig;
import com.cadence.repository.FeedbackRequestRepository;
import com.cadence.scheduler.FeedbackScheduler;
import com.cadence.security.TokenHasher;
import com.cadence.service.FeedbackService;
import com.cadence.service.MemberService;
import com.cadence.service.SessionService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shared fixture for the F32 Interviewer Feedback tests. Singleton MongoDBContainer (via
 * {@link BaseIntegrationTest}), the mutable test {@link MutableClock} (deterministic timing — never wall-clock
 * sleeps), MockMvc + member/cookie helpers, and seeders for a configured workspace, a candidate, a BOOKED
 * interview, and ACTIVE participant claims. Remove-not-drop cleanup (CLAUDE.md F00.1 — dropCollection would drop
 * the Mongock indexes incl. the partial {tokenHash} + unique {interviewEventId,interviewerMemberId}).
 */
@AutoConfigureMockMvc
@Import(AuthTestConfig.class)
abstract class FeedbackItBase extends BaseIntegrationTest {

    protected static final String WS = "ws1";

    @Autowired protected MockMvc mvc;
    @Autowired protected MemberService memberService;
    @Autowired protected SessionService sessionService;
    @Autowired protected MutableClock clock;
    @Autowired protected TokenHasher hasher;
    @Autowired protected FeedbackService feedbackService;
    @Autowired protected FeedbackScheduler scheduler;
    @Autowired protected FeedbackRequestRepository feedbackRepo;

    private static final AtomicInteger TEST_SEQ = new AtomicInteger(0);

    @BeforeEach
    void cleanFeedback() {
        clock.set(AuthTestConfig.FIXED_START.plusSeconds(60L * TEST_SEQ.incrementAndGet()));
        mongoTemplate.remove(new Query(), Member.class);
        mongoTemplate.remove(new Query(), Session.class);
        mongoTemplate.remove(new Query(), AuthAuditEvent.class);
        mongoTemplate.remove(new Query(), CandidateAuditEvent.class);
        mongoTemplate.remove(new Query(), WorkspaceConfig.class);
        mongoTemplate.remove(new Query(), Candidate.class);
        mongoTemplate.remove(new Query(), SchedulingRequest.class);
        mongoTemplate.remove(new Query(), InterviewSlotClaim.class);
        mongoTemplate.remove(new Query(), FeedbackRequest.class);
        mongoTemplate.remove(new Query(), RecruiterNotification.class);
    }

    protected Member member(String email, Role role) {
        return memberService.create(WS, email, email, role, null, null);
    }

    protected void deactivate(String memberId) {
        mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is(memberId)),
            new Update().set("status", MemberStatus.DEACTIVATED), Member.class);
    }

    protected Cookie cookie(Member m) {
        return new Cookie("cad_session", sessionService.issue(m).jwt());
    }

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

    protected Candidate seedCandidate(String id, String name, String email) {
        Instant now = Instant.now(clock);
        Candidate c = new Candidate();
        c.setId(id);
        c.setWorkspaceId(WS);
        c.setName(name);
        c.setEmail(email);
        c.setLawfulBasis(LawfulBasis.CONSENT);
        c.setBasisRecordedAt(Instant.parse("2026-01-01T00:00:00Z"));
        c.setErasureState(ErasureState.ACTIVE);
        c.setLastContactAt(now);
        c.setCreatedAt(now);
        return mongoTemplate.save(c);
    }

    /** A BOOKED interview whose start is {@code hoursAgo} hours before now (occurred if &gt; generationDelay). */
    protected SchedulingRequest seedBookedInterview(String id, String candidateId, long hoursAgo) {
        return seedInterview(id, candidateId, SchedulingStatus.BOOKED, hoursAgo);
    }

    protected SchedulingRequest seedInterview(String id, String candidateId, SchedulingStatus status, long hoursAgo) {
        Instant now = Instant.now(clock);
        SchedulingRequest r = new SchedulingRequest();
        r.setId(id);
        r.setWorkspaceId(WS);
        r.setCandidateId(candidateId);
        r.setStatus(status);
        // The F13 {tokenHash} index is PLAIN-unique (not partial) — two null-tokenHash rows collide (the F23
        // seeding footgun). Set a distinct value per interview.
        r.setTokenHash("hash-" + id);
        r.setBookedStartAt(now.minus(Duration.ofHours(hoursAgo)));
        r.setCreatedAt(now.minus(Duration.ofHours(hoursAgo + 1)));
        r.setUpdatedAt(now);
        return mongoTemplate.save(r);
    }

    protected void seedClaim(String reqId, String memberId, Instant startAt) {
        InterviewSlotClaim claim = new InterviewSlotClaim();
        claim.setWorkspaceId(WS);
        claim.setMemberId(memberId);
        claim.setSchedulingRequestId(reqId);
        claim.setStartAt(startAt);
        claim.setStatus(ClaimStatus.ACTIVE);
        claim.setCreatedAt(Instant.now(clock));
        mongoTemplate.save(claim);
    }
}
