package com.cadence.gdpr;

import com.cadence.BaseIntegrationTest;
import com.cadence.auth.AuthTestConfig;
import com.cadence.auth.MutableClock;
import com.cadence.config.MongoPiiConfig;
import com.cadence.domain.AuthAuditEvent;
import com.cadence.domain.Candidate;
import com.cadence.domain.CandidateAuditEvent;
import com.cadence.domain.ErasureRequest;
import com.cadence.domain.LawfulBasis;
import com.cadence.domain.Member;
import com.cadence.domain.Role;
import com.cadence.domain.SchedulerCheckpoint;
import com.cadence.domain.Session;
import com.cadence.domain.WorkspaceConfig;
import com.cadence.security.PiiCrypto;
import com.cadence.service.CandidateService;
import com.cadence.service.MemberService;
import com.cadence.service.SessionService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.convert.MappingMongoConverter;
import org.springframework.data.mongodb.core.convert.MongoCustomConversions;
import org.springframework.data.mongodb.core.convert.NoOpDbRefResolver;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

/**
 * Shared fixture for the F04 GDPR integration tests: MockMvc + member/cookie helpers, a candidate
 * seeding helper that goes through the production-path {@code CandidateService.create}, and the
 * remove-not-drop cleanup of every collection these tests touch (CLAUDE.md F00.1 — dropCollection
 * would drop the Mongock 001/005 indexes). Workspace is "ws1".
 */
@AutoConfigureMockMvc
@Import(AuthTestConfig.class)
abstract class GdprItBase extends BaseIntegrationTest {

    protected static final String WS = "ws1";

    @Autowired protected MockMvc mvc;
    @Autowired protected MemberService memberService;
    @Autowired protected SessionService sessionService;
    @Autowired protected CandidateService candidateService;
    @Autowired protected MutableClock clock;
    @Autowired protected MongoDatabaseFactory dbFactory;
    @Autowired protected PiiCrypto piiCrypto;

    @BeforeEach
    void cleanup() {
        clock.set(AuthTestConfig.FIXED_START);
        mongoTemplate.remove(new Query(), Member.class);
        mongoTemplate.remove(new Query(), Session.class);
        mongoTemplate.remove(new Query(), AuthAuditEvent.class);
        mongoTemplate.remove(new Query(), Candidate.class);
        mongoTemplate.remove(new Query(), CandidateAuditEvent.class);
        mongoTemplate.remove(new Query(), ErasureRequest.class);
        mongoTemplate.remove(new Query(), SchedulerCheckpoint.class);
        mongoTemplate.remove(new Query(), WorkspaceConfig.class);
    }

    protected Member member(String email, Role role) {
        return memberService.create(WS, email, email, role, null, null);
    }

    protected Cookie cookie(Member m) {
        return new Cookie("cad_session", sessionService.issue(m).jwt());
    }

    protected Cookie adminCookie() {
        return cookie(member("admin@x.com", Role.ADMIN));
    }

    protected Candidate seedCandidate(String name, String email, String phone) {
        return candidateService.create(WS, name, email, phone, Optional.empty(), "system");
    }

    protected Candidate seedCandidateWithBasis(String name, String email, String phone) {
        return candidateService.create(WS, name, email, phone, Optional.of(LawfulBasis.LEGITIMATE_INTEREST), "system");
    }

    /** A cold MongoTemplate (fresh PII converter) — reads as if after a restart, no in-process cache. */
    protected MongoTemplate coldTemplate() {
        MongoCustomConversions conversions = new MongoPiiConfig().mongoCustomConversions(piiCrypto);
        MongoMappingContext ctx = new MongoMappingContext();
        ctx.setSimpleTypeHolder(conversions.getSimpleTypeHolder());
        ctx.afterPropertiesSet();
        MappingMongoConverter converter = new MappingMongoConverter(NoOpDbRefResolver.INSTANCE, ctx);
        converter.setCustomConversions(conversions);
        converter.afterPropertiesSet();
        return new MongoTemplate(dbFactory, converter);
    }

    @SuppressWarnings("unused")
    private List<?> keepImports() { return List.of(); }
}
