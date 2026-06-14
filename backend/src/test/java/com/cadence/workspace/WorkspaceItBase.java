package com.cadence.workspace;

import com.cadence.BaseIntegrationTest;
import com.cadence.auth.AuthTestConfig;
import com.cadence.auth.MutableClock;
import com.cadence.config.MongoPiiConfig;
import com.cadence.security.PiiCrypto;
import com.cadence.domain.AuthAuditEvent;
import com.cadence.domain.Member;
import com.cadence.domain.Role;
import com.cadence.domain.Session;
import com.cadence.domain.WorkspaceConfig;
import com.cadence.domain.WorkspaceLogo;
import com.cadence.repository.WorkspaceConfigRepository;
import com.cadence.repository.WorkspaceLogoRepository;
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

/**
 * Shared fixture for the F03 workspace integration tests: MockMvc + member/cookie helpers and the
 * remove-not-drop cleanup of every collection these tests touch (CLAUDE.md F00.1 — dropCollection
 * would drop the Mongock 004 / F01 indexes). Members/Sessions are cleaned too since they are seeded.
 */
@AutoConfigureMockMvc
@Import(AuthTestConfig.class)
abstract class WorkspaceItBase extends BaseIntegrationTest {

    @Autowired protected MockMvc mvc;
    @Autowired protected MemberService memberService;
    @Autowired protected SessionService sessionService;
    @Autowired protected WorkspaceConfigRepository configs;
    @Autowired protected WorkspaceLogoRepository logos;
    @Autowired protected MutableClock clock;
    @Autowired protected MongoDatabaseFactory dbFactory;
    @Autowired protected PiiCrypto piiCrypto;

    /**
     * A MongoTemplate with a freshly-constructed (COLD) converter over the live connection — proves a
     * value persists and is read back through a converter built from scratch (as on app restart), not
     * served from any in-process cache (QA-1). {@code withPii=true} registers the PII converter so the
     * encrypted credential decrypts; false reads plain settings.
     */
    protected MongoTemplate coldTemplate(boolean withPii) {
        MongoCustomConversions conversions = withPii
            ? new MongoPiiConfig().mongoCustomConversions(piiCrypto)
            : new MongoCustomConversions(List.of());
        MongoMappingContext ctx = new MongoMappingContext();
        ctx.setSimpleTypeHolder(conversions.getSimpleTypeHolder());
        ctx.afterPropertiesSet();
        MappingMongoConverter converter = new MappingMongoConverter(NoOpDbRefResolver.INSTANCE, ctx);
        converter.setCustomConversions(conversions);
        converter.afterPropertiesSet();
        return new MongoTemplate(dbFactory, converter);
    }

    @BeforeEach
    void cleanup() {
        clock.set(AuthTestConfig.FIXED_START);
        mongoTemplate.remove(new Query(), Member.class);
        mongoTemplate.remove(new Query(), Session.class);
        mongoTemplate.remove(new Query(), AuthAuditEvent.class);
        mongoTemplate.remove(new Query(), WorkspaceConfig.class);
        mongoTemplate.remove(new Query(), WorkspaceLogo.class);
    }

    protected Member member(String email, Role role) {
        return memberService.create("ws1", email, email, role, null, null);
    }

    protected Cookie cookie(Member m) {
        return new Cookie("cad_session", sessionService.issue(m).jwt());
    }

    /** A seeded ADMIN + its session cookie. */
    protected Cookie adminCookie() {
        return cookie(member("admin@x.com", Role.ADMIN));
    }
}
