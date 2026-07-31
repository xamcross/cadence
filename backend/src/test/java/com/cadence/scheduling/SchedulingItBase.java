package com.cadence.scheduling;

import com.cadence.BaseIntegrationTest;
import com.cadence.auth.AuthTestConfig;
import com.cadence.auth.MutableClock;
import com.cadence.domain.AuthAuditEvent;
import com.cadence.domain.Candidate;
import com.cadence.domain.ErasureState;
import com.cadence.domain.InterviewSlotClaim;
import com.cadence.domain.InterviewTemplate;
import com.cadence.domain.LawfulBasis;
import com.cadence.domain.Member;
import com.cadence.domain.OfferedSlot;
import com.cadence.domain.PoolRule;
import com.cadence.domain.Role;
import com.cadence.domain.SchedulingRequest;
import com.cadence.domain.SchedulingStatus;
import com.cadence.domain.Session;
import com.cadence.domain.TemplateStatus;
import com.cadence.domain.WorkingHours;
import com.cadence.domain.WorkspaceConfig;
import com.cadence.domain.WorkspaceEntitlement;
import com.cadence.security.SecureTokens;
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
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared fixture for the F13 single-stage-scheduling integration/contract tests. Singleton MongoDBContainer
 * (via {@link BaseIntegrationTest}), the mutable test {@link MutableClock} (from {@link AuthTestConfig} —
 * deterministic {@code updatedAt}/{@code expiresAt} control), MockMvc + member/cookie helpers, a contactable
 * candidate + interview-template + workspace-config seeder, and a direct {@link SchedulingRequest} seeder that
 * mints a known raw token (the {@code initiate} path never returns the raw token — it rides the transient
 * invitation email). Remove-not-drop cleanup of every collection these tests touch (CLAUDE.md F00.1 —
 * {@code dropCollection} would drop the Mongock 004/008/012 indexes incl. the partial claim index).
 */
@AutoConfigureMockMvc
@Import(AuthTestConfig.class)
abstract class SchedulingItBase extends BaseIntegrationTest {

    protected static final String WS = "ws1";

    @Autowired protected MockMvc mvc;
    @Autowired protected MemberService memberService;
    @Autowired protected SessionService sessionService;
    @Autowired protected MutableClock clock;
    @Autowired protected TokenHasher hasher;

    // The CandidateRateLimiter / AuthAuditService throttles are in-memory singletons keyed by (hashed value,
    // minute) and survive the per-test DB cleanup. Each test runs in a UNIQUE minute (advance the frozen clock
    // by a per-test offset off FIXED_START) so a prior test's limiter/throttle window never bleeds in.
    private static final java.util.concurrent.atomic.AtomicInteger TEST_SEQ =
        new java.util.concurrent.atomic.AtomicInteger(0);

    @BeforeEach
    void cleanScheduling() {
        clock.set(AuthTestConfig.FIXED_START.plusSeconds(60L * TEST_SEQ.incrementAndGet()));
        mongoTemplate.remove(new Query(), Member.class);
        mongoTemplate.remove(new Query(), Session.class);
        mongoTemplate.remove(new Query(), AuthAuditEvent.class);
        mongoTemplate.remove(new Query(), WorkspaceConfig.class);
        mongoTemplate.remove(new Query(), InterviewTemplate.class);
        mongoTemplate.remove(new Query(), Candidate.class);
        mongoTemplate.remove(new Query(), SchedulingRequest.class);
        mongoTemplate.remove(new Query(), InterviewSlotClaim.class);
        mongoTemplate.remove(new Query(), com.cadence.domain.EmailDispatch.class);
        mongoTemplate.remove(new Query(), com.cadence.domain.RecruiterNotification.class);
        // 032 T7: this suite predates billing and never modeled a plan -- seed Team so the no-show stage-1
        // gate (NO_SHOW_DEFENSE) does not block these pre-existing F23 fixtures. Cleared first: another
        // package's ItBase using the same WS ("ws1") may have left a stale row (no cross-package scoping).
        mongoTemplate.remove(Query.query(Criteria.where("workspaceId").is(WS)), WorkspaceEntitlement.class);
        WorkspaceEntitlement e = new WorkspaceEntitlement();
        e.setWorkspaceId(WS);
        e.setFsLicenseId("lic-" + WS + "-scheduling");
        e.setFsPlanId("2002");
        mongoTemplate.insert(e);
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

    protected void configuredWorkspace() {
        configuredWorkspace(WS, "UTC", LocalTime.of(9, 0), LocalTime.of(17, 0));
    }

    /** Seed a contactable candidate (ACTIVE, basis recorded; name+email encrypted via the converter). */
    protected Candidate seedContactableCandidate(String id, String name, String email) {
        Candidate c = newCandidate(id, name, email);
        c.setLawfulBasis(LawfulBasis.CONSENT);
        c.setBasisRecordedAt(Instant.parse("2026-01-01T00:00:00Z"));
        return mongoTemplate.save(c);
    }

    protected Candidate newCandidate(String id, String name, String email) {
        Candidate c = new Candidate();
        c.setId(id);
        c.setWorkspaceId(WS);
        c.setName(name);
        c.setEmail(email);
        c.setErasureState(ErasureState.ACTIVE);
        c.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        return c;
    }

    /** Seed an ACTIVE interview template with one required member (zone inherits the workspace). */
    protected InterviewTemplate seedTemplate(String requiredMemberId) {
        return seedTemplate(List.of(requiredMemberId), List.of());
    }

    protected InterviewTemplate seedTemplate(List<String> requiredMemberIds, List<PoolRule> pools) {
        InterviewTemplate t = new InterviewTemplate();
        t.setWorkspaceId(WS);
        t.setName("Onsite");
        t.setStatus(TemplateStatus.ACTIVE);
        t.setDurationMinutes(60);
        t.setSlotCadenceMinutes(60);
        t.setBufferBeforeMinutes(0);
        t.setBufferAfterMinutes(0);
        t.setDailyCapPerInterviewer(100);
        t.setRequiredMemberIds(new ArrayList<>(requiredMemberIds));
        t.setPools(new ArrayList<>(pools));
        t.setCreatedByMemberId("seed");
        t.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        t.setUpdatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        return mongoTemplate.save(t);
    }

    /** A persisted PENDING_SELECTION request + a known raw token. The caller supplies the offered slots. */
    protected static final class Seeded {
        final SchedulingRequest request;
        final String rawToken;
        Seeded(SchedulingRequest request, String rawToken) {
            this.request = request;
            this.rawToken = rawToken;
        }
    }

    protected Seeded seedPendingRequest(String candidateId, String templateId, String locationText,
                                        List<OfferedSlot> slots) {
        String raw = SecureTokens.newToken();
        Instant now = Instant.now(clock);
        SchedulingRequest req = new SchedulingRequest();
        req.setWorkspaceId(WS);
        req.setCandidateId(candidateId);
        req.setTemplateId(templateId);
        req.setStatus(SchedulingStatus.PENDING_SELECTION);
        req.setTokenHash(hasher.hashToken(raw));
        req.setSentAt(now);
        req.setExpiresAt(now.plusSeconds(72 * 3600));
        req.setSearchRangeStart(LocalDate.now(clock));
        req.setSearchRangeEnd(LocalDate.now(clock).plusDays(10));
        req.setOfferedSlots(new ArrayList<>(slots));
        req.setLocationText(locationText);
        req.setCreatedAt(now);
        req.setUpdatedAt(now);
        SchedulingRequest saved = mongoTemplate.save(req);
        return new Seeded(saved, raw);
    }

    /** F20: a persisted BOOKED booking (INITIAL, mode default) with a known raw MANAGE token + one ACTIVE claim. */
    protected Seeded seedBookedRequest(String candidateId, String templateId, String locationText, OfferedSlot chosen,
                                       String requiredMemberId) {
        String rawManage = SecureTokens.newToken();
        Instant now = Instant.now(clock);
        SchedulingRequest req = new SchedulingRequest();
        req.setWorkspaceId(WS);
        req.setCandidateId(candidateId);
        req.setTemplateId(templateId);
        req.setStatus(SchedulingStatus.BOOKED);
        req.setMode(com.cadence.domain.SchedulingMode.INITIAL);
        req.setTokenHash(hasher.hashToken(SecureTokens.newToken()));   // the (consumed) slot-pick token
        req.setManageTokenHash(hasher.hashToken(rawManage));
        req.setSentAt(now);
        req.setExpiresAt(now.plusSeconds(72 * 3600));
        req.setBookedAt(now);
        req.setBookedStartAt(chosen.getStart());   // F23: denormalized start (set in the real BOOKED CAS)
        req.setChosenSlotId(chosen.getSlotId());
        req.setSearchRangeStart(LocalDate.now(clock));
        req.setSearchRangeEnd(LocalDate.now(clock).plusDays(10));
        req.setOfferedSlots(new ArrayList<>(List.of(chosen)));
        req.setLocationText(locationText);
        req.setCreatedAt(now);
        req.setUpdatedAt(now);
        SchedulingRequest saved = mongoTemplate.save(req);
        InterviewSlotClaim claim = new InterviewSlotClaim(WS, requiredMemberId, chosen.getStart(), saved.getId(), now);
        mongoTemplate.save(claim);
        return new Seeded(saved, rawManage);
    }

    /** One offered slot: required member ids + per-pool qualifying candidate lists. */
    protected OfferedSlot slot(String slotId, Instant start, Instant end, List<String> required,
                               List<List<String>> poolCandidates) {
        OfferedSlot s = new OfferedSlot();
        s.setSlotId(slotId);
        s.setStart(start);
        s.setEnd(end);
        s.setZoneId("UTC");
        s.setRequiredMemberIds(new ArrayList<>(required));
        List<List<String>> pools = new ArrayList<>();
        for (List<String> p : poolCandidates) pools.add(new ArrayList<>(p));
        s.setPoolCandidates(pools);
        return s;
    }
}
