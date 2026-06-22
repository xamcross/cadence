package com.cadence.pipeline;

import com.cadence.BaseIntegrationTest;
import com.cadence.auth.AuthTestConfig;
import com.cadence.auth.MutableClock;
import com.cadence.domain.Assignment;
import com.cadence.domain.AuthAuditEvent;
import com.cadence.domain.Candidate;
import com.cadence.domain.CandidateAuditEvent;
import com.cadence.domain.CandidateAuditOutcome;
import com.cadence.domain.CandidateEventType;
import com.cadence.domain.CandidateStatusOutcome;
import com.cadence.domain.EmailDispatch;
import com.cadence.domain.ErasureState;
import com.cadence.domain.FeedbackRequest;
import com.cadence.domain.FeedbackRequestStatus;
import com.cadence.domain.LawfulBasis;
import com.cadence.domain.Member;
import com.cadence.domain.Requisition;
import com.cadence.domain.RequisitionStatus;
import com.cadence.domain.ResourceType;
import com.cadence.domain.Role;
import com.cadence.domain.SchedulingRequest;
import com.cadence.domain.SchedulingStatus;
import com.cadence.domain.Session;
import com.cadence.domain.WorkingHours;
import com.cadence.domain.WorkspaceConfig;
import com.cadence.service.MemberService;
import com.cadence.service.PipelineService;
import com.cadence.service.RequisitionService;
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
 * Shared fixture for the F51 Pipeline View integration/contract tests. Singleton MongoDBContainer (via
 * {@link BaseIntegrationTest}); {@link MutableClock} pinned to a fixed NOW so SLA classification + poll freshness
 * are deterministic. Remove-not-drop cleanup (CLAUDE.md F00.1). Each seeded scheduling row gets a DISTINCT
 * {@code tokenHash} (the F23 plain-unique-index lesson) and feedback rows distinct {@code interviewEventId}/
 * {@code interviewerMemberId}/{@code tokenHash} (the F32 unique-index lesson).
 */
@AutoConfigureMockMvc
@Import(AuthTestConfig.class)
abstract class PipelineItBase extends BaseIntegrationTest {

    protected static final String WS = "ws1";
    protected static final Instant NOW = AuthTestConfig.FIXED_START;

    @Autowired protected MockMvc mvc;
    @Autowired protected MemberService memberService;
    @Autowired protected SessionService sessionService;
    @Autowired protected MutableClock clock;
    @Autowired protected PipelineService pipelineService;
    @Autowired protected RequisitionService requisitionService;

    private final AtomicInteger seq = new AtomicInteger(0);

    @BeforeEach
    void cleanPipeline() {
        clock.set(NOW);
        mongoTemplate.remove(new Query(), Member.class);
        mongoTemplate.remove(new Query(), Session.class);
        mongoTemplate.remove(new Query(), AuthAuditEvent.class);
        mongoTemplate.remove(new Query(), WorkspaceConfig.class);
        mongoTemplate.remove(new Query(), Candidate.class);
        mongoTemplate.remove(new Query(), Requisition.class);
        mongoTemplate.remove(new Query(), Assignment.class);
        mongoTemplate.remove(new Query(), SchedulingRequest.class);
        mongoTemplate.remove(new Query(), CandidateAuditEvent.class);
        mongoTemplate.remove(new Query(), EmailDispatch.class);
        mongoTemplate.remove(new Query(), FeedbackRequest.class);
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

    protected Requisition seedRequisition(String id, String title, RequisitionStatus status) {
        Requisition r = new Requisition();
        r.setId(id);
        r.setWorkspaceId(WS);
        r.setTitle(title);
        r.setStatus(status);
        r.setCreatedAt(NOW);
        r.setCreatedByMemberId("admin");
        return mongoTemplate.save(r);
    }

    protected Assignment seedAssignment(String hmMemberId, String requisitionId) {
        Assignment a = new Assignment();
        a.setWorkspaceId(WS);
        a.setMemberId(hmMemberId);
        a.setResourceType(ResourceType.REQUISITION);
        a.setResourceId(requisitionId);
        a.setCreatedAt(NOW);
        a.setCreatedByMemberId("admin");
        return mongoTemplate.save(a);
    }

    /** A candidate. {@code stage} is written to statusStage (encrypted). {@code requisitionId} may be null. */
    protected Candidate seedCandidate(String id, String name, String stage, Instant lastContactAt,
                                      CandidateStatusOutcome outcome, ErasureState erasureState, String requisitionId) {
        Candidate c = new Candidate();
        c.setId(id);
        c.setWorkspaceId(WS);
        c.setName(name);
        c.setEmail(id + "@x.test");
        c.setLawfulBasis(LawfulBasis.CONSENT);
        c.setBasisRecordedAt(Instant.parse("2026-01-01T00:00:00Z"));
        c.setErasureState(erasureState);
        c.setStatusOutcome(outcome);
        c.setStatusStage(stage);
        c.setLastContactAt(lastContactAt);
        c.setCreatedAt(lastContactAt == null ? NOW : lastContactAt);
        c.setRequisitionId(requisitionId);
        return mongoTemplate.save(c);
    }

    /** Convenience: an ACTIVE, in-progress candidate silent for {@code daysAgo} days, on the given requisition. */
    protected Candidate seedActive(String id, String name, long daysAgo, String requisitionId) {
        return seedCandidate(id, name, "Screening", NOW.minus(Duration.ofDays(daysAgo)),
            CandidateStatusOutcome.IN_PROGRESS, ErasureState.ACTIVE, requisitionId);
    }

    protected SchedulingRequest seedScheduling(String candidateId, SchedulingStatus status, Instant sentAt,
                                               Instant expiresAt, Instant bookedStartAt, Instant noShowAt) {
        SchedulingRequest r = new SchedulingRequest();
        r.setWorkspaceId(WS);
        r.setCandidateId(candidateId);
        r.setStatus(status);
        r.setTokenHash("hash-" + candidateId + "-" + seq.incrementAndGet());
        r.setSentAt(sentAt);
        r.setExpiresAt(expiresAt);
        r.setBookedStartAt(bookedStartAt);
        r.setNoShowAt(noShowAt);
        r.setCreatedAt(sentAt == null ? NOW : sentAt);
        return mongoTemplate.save(r);
    }

    protected void seedAudit(String candidateId, CandidateEventType type, Instant occurredAt) {
        CandidateAuditEvent e = new CandidateAuditEvent();
        e.setWorkspaceId(WS);
        e.setCandidateId(candidateId);
        e.setEventType(type);
        e.setOutcome(CandidateAuditOutcome.RECORDED);
        e.setActorMemberId("sys");
        e.setOccurredAt(occurredAt);
        mongoTemplate.insert(e);
    }

    /** A PENDING feedback request for the candidate (so the timeline shows feedbackPending=true). */
    protected void seedFeedbackPending(String candidateId, String payloadSentinel) {
        FeedbackRequest f = new FeedbackRequest();
        f.setWorkspaceId(WS);
        f.setCandidateId(candidateId);
        f.setStatus(FeedbackRequestStatus.PENDING);
        f.setInterviewEventId("evt-" + candidateId + "-" + seq.incrementAndGet());
        f.setInterviewerMemberId("iv-" + seq.incrementAndGet());
        f.setTokenHash("ftok-" + candidateId + "-" + seq.incrementAndGet());
        if (payloadSentinel != null) {
            f.setScorecardPayload(payloadSentinel);
        }
        f.setCreatedAt(NOW);
        mongoTemplate.save(f);
    }
}
