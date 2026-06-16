package com.cadence.emaildelivery;

import com.cadence.BaseIntegrationTest;
import com.cadence.auth.AuthTestConfig;
import com.cadence.auth.MutableClock;
import com.cadence.domain.AuthAuditEvent;
import com.cadence.domain.Candidate;
import com.cadence.domain.DeadLetterRecord;
import com.cadence.domain.EmailDispatch;
import com.cadence.domain.ErasureState;
import com.cadence.domain.LawfulBasis;
import com.cadence.domain.Member;
import com.cadence.domain.ProcessedWebhookEvent;
import com.cadence.domain.RecruiterNotification;
import com.cadence.domain.Role;
import com.cadence.domain.SchedulerCheckpoint;
import com.cadence.domain.Session;
import com.cadence.integration.MailTransport;
import com.cadence.service.MemberService;
import com.cadence.service.SessionService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Shared fixture for the F22 email-delivery integration/contract tests (T018). Singleton MongoDBContainer
 * (via {@link BaseIntegrationTest}), the {@link RecordingMailTransport} as the {@code @Primary}
 * {@link MailTransport} (so the dummy SMTP host is never dialled), the mutable test {@link MutableClock}
 * (from {@link AuthTestConfig} — deterministic {@code updatedAt}/scheduled-time control), member/cookie
 * helpers, and remove-not-drop cleanup of every collection these tests touch (CLAUDE.md F00.1 —
 * {@code dropCollection} would drop the Mongock 004/010 indexes).
 */
@AutoConfigureMockMvc
@Import({AuthTestConfig.class, EmailDeliveryItBase.RecordingTransportConfig.class})
abstract class EmailDeliveryItBase extends BaseIntegrationTest {

    protected static final String WS = "ws1";

    @Autowired protected MockMvc mvc;
    @Autowired protected MemberService memberService;
    @Autowired protected SessionService sessionService;
    @Autowired protected MutableClock clock;
    @Autowired protected RecordingMailTransport recordingTransport;

    @TestConfiguration
    static class RecordingTransportConfig {
        @Bean
        @Primary
        RecordingMailTransport recordingMailTransport() {
            return new RecordingMailTransport();
        }
    }

    @BeforeEach
    void cleanEmailDelivery() {
        clock.set(AuthTestConfig.FIXED_START);
        recordingTransport.reset();
        mongoTemplate.remove(new Query(), Member.class);
        mongoTemplate.remove(new Query(), Session.class);
        mongoTemplate.remove(new Query(), AuthAuditEvent.class);
        mongoTemplate.remove(new Query(), EmailDispatch.class);
        mongoTemplate.remove(new Query(), Candidate.class);
        mongoTemplate.remove(new Query(), ProcessedWebhookEvent.class);
        mongoTemplate.remove(new Query(), RecruiterNotification.class);
        mongoTemplate.remove(new Query(), DeadLetterRecord.class);
        mongoTemplate.remove(new Query(), SchedulerCheckpoint.class);
    }

    protected Member member(String email, Role role) {
        return memberService.create(WS, email, email, role, null, null);
    }

    protected Cookie cookie(Member m) {
        return new Cookie("cad_session", sessionService.issue(m).jwt());
    }

    /** Seed a contactable candidate (ACTIVE, basis recorded, name+email encrypted via the converter). */
    protected Candidate seedContactableCandidate(String id, String name, String email) {
        Candidate c = newCandidate(id, name, email);
        c.setLawfulBasis(LawfulBasis.CONSENT);
        c.setBasisRecordedAt(java.time.Instant.parse("2026-01-01T00:00:00Z"));
        return mongoTemplate.save(c);
    }

    /** Seed a candidate with the given (mutable) state — callers tweak erasure/withdrawn/undeliverable. */
    protected Candidate newCandidate(String id, String name, String email) {
        Candidate c = new Candidate();
        c.setId(id);
        c.setWorkspaceId(WS);
        c.setName(name);
        c.setEmail(email);
        c.setErasureState(ErasureState.ACTIVE);
        c.setCreatedAt(java.time.Instant.parse("2026-01-01T00:00:00Z"));
        return c;
    }
}
