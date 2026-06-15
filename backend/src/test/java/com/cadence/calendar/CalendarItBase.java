package com.cadence.calendar;

import com.cadence.BaseIntegrationTest;
import com.cadence.auth.AuthTestConfig;
import com.cadence.auth.MutableClock;
import com.cadence.config.MongoPiiConfig;
import com.cadence.domain.AuthAuditEvent;
import com.cadence.domain.CalendarConnection;
import com.cadence.domain.CalendarProvider;
import com.cadence.domain.Member;
import com.cadence.domain.OAuthFlowState;
import com.cadence.domain.Role;
import com.cadence.domain.Session;
import com.cadence.repository.CalendarConnectionRepository;
import com.cadence.security.PiiCrypto;
import com.cadence.service.CalendarConnectionService;
import com.cadence.service.CalendarTokenService;
import com.cadence.service.MemberService;
import com.cadence.service.RoleService;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Shared fixture for the F01.1 calendar tests: a WireMock-stubbed provider (token/revoke endpoints),
 * MockMvc + member/cookie helpers, a production-path connect helper, and remove-not-drop cleanup of
 * every collection these tests touch (CLAUDE.md F00.1 — dropCollection would drop the Mongock 006
 * unique/TTL indexes). The provider URIs are pointed at WireMock via @DynamicPropertySource.
 */
@AutoConfigureMockMvc
@Import(AuthTestConfig.class)
abstract class CalendarItBase extends BaseIntegrationTest {

    protected static final String WS = "ws1";

    // Single static stub provider for the suite; reset per test. Dynamic port avoids collisions.
    protected static final StubProvider wm = new StubProvider();

    @DynamicPropertySource
    static void calendarProps(DynamicPropertyRegistry r) {
        String base = "http://localhost:" + wm.port();
        r.add("calendar.oauth.redirect-base-url", () -> "http://localhost:8080");
        r.add("calendar.oauth.refresh-retry-backoff", () -> "PT0S"); // no real sleeping in tests
        r.add("calendar.oauth.google.authorization-uri", () -> base + "/google/auth");
        r.add("calendar.oauth.google.token-uri", () -> base + "/google/token");
        r.add("calendar.oauth.google.revocation-uri", () -> base + "/google/revoke");
        r.add("calendar.oauth.microsoft.authorization-uri", () -> base + "/ms/auth");
        r.add("calendar.oauth.microsoft.token-uri", () -> base + "/ms/token");
        r.add("calendar.oauth.microsoft.revocation-uri", () -> base + "/ms/revoke");
    }

    @Autowired protected MockMvc mvc;
    @Autowired protected MemberService memberService;
    @Autowired protected SessionService sessionService;
    @Autowired protected RoleService roleService;
    @Autowired protected CalendarConnectionService connectionService;
    @Autowired protected CalendarTokenService tokenService;
    @Autowired protected CalendarConnectionRepository connectionRepo;
    @Autowired protected MutableClock clock;
    @Autowired protected MongoDatabaseFactory dbFactory;
    @Autowired protected PiiCrypto piiCrypto;

    @BeforeEach
    void cleanup() {
        clock.set(AuthTestConfig.FIXED_START);
        wm.reset();
        mongoTemplate.remove(new Query(), Member.class);
        mongoTemplate.remove(new Query(), Session.class);
        mongoTemplate.remove(new Query(), AuthAuditEvent.class);
        mongoTemplate.remove(new Query(), CalendarConnection.class);
        mongoTemplate.remove(new Query(), OAuthFlowState.class);
    }

    protected Member member(String email, Role role) {
        return memberService.create(WS, email, email, role, null, null);
    }

    protected Cookie cookie(Member m) {
        return new Cookie("cad_session", sessionService.issue(m).jwt());
    }

    /** A provider path prefix ("google" or "ms") for stubbing. */
    protected static String prefix(CalendarProvider p) {
        return p == CalendarProvider.GOOGLE ? "google" : "ms";
    }

    protected String tokenPath(CalendarProvider p) {
        return "/" + prefix(p) + "/token";
    }

    protected String revokePath(CalendarProvider p) {
        return "/" + prefix(p) + "/revoke";
    }

    /** Stub the token endpoint for the authorization-code exchange. */
    protected void stubExchange(CalendarProvider p, String access, String refresh, long expiresIn, String account) {
        wm.stub(tokenPath(p), "grant_type=authorization_code", 200, tokenJson(access, refresh, expiresIn, account));
    }

    /** Stub the token endpoint for a refresh, returning a fresh access token. */
    protected void stubRefresh(CalendarProvider p, String access, String refresh, long expiresIn) {
        wm.stub(tokenPath(p), "grant_type=refresh_token", 200, tokenJson(access, refresh, expiresIn, null));
    }

    /** Stub the refresh endpoint to return an OAuth error (e.g. invalid_grant) with the given status. */
    protected void stubRefreshError(CalendarProvider p, int status, String oauthError) {
        wm.stub(tokenPath(p), "grant_type=refresh_token", status,
            oauthError == null ? "{}" : "{\"error\":\"" + oauthError + "\"}");
    }

    protected void stubRevoke(CalendarProvider p) {
        wm.stub(revokePath(p), "", 200, "");
    }

    protected void stubRevokeFails(CalendarProvider p) {
        wm.stub(revokePath(p), "", 500, "");
    }

    /**
     * Connect a member to a provider via the PRODUCTION start+callback path against the stub. Returns
     * the (re-read) persisted connection.
     */
    protected CalendarConnection connect(Member m, CalendarProvider p, String account) {
        stubExchange(p, "access-" + p.path(), "refresh-" + p.path(), 3600, account);
        connectionService.start(WS, m.getId(), p);
        OAuthFlowState state = mongoTemplate.findOne(new Query(), OAuthFlowState.class);
        connectionService.completeCallback(p, "code-xyz", state.getId(), null, WS, m.getId());
        return connectionRepo.findByWorkspaceIdAndMemberIdAndProvider(WS, m.getId(), p).orElseThrow();
    }

    /**
     * Connect a member with an id_token carrying ONLY a {@code sub} claim (no email/preferred_username/upn),
     * so {@code providerAccountId} is an opaque non-mailbox id (F11 D2a — the Microsoft client must treat
     * this as NEEDS_RECONNECTION rather than send a non-mailbox to getSchedule).
     */
    protected CalendarConnection connectSubOnly(Member m, CalendarProvider p, String sub) {
        String idToken = idTokenRaw("{\"sub\":\"" + sub + "\"}");
        String tokenJson = "{\"access_token\":\"access-" + p.path() + "\",\"refresh_token\":\"refresh-" + p.path()
            + "\",\"expires_in\":3600,\"scope\":\"openid\",\"id_token\":\"" + idToken + "\"}";
        wm.stub(tokenPath(p), "grant_type=authorization_code", 200, tokenJson);
        connectionService.start(WS, m.getId(), p);
        OAuthFlowState state = mongoTemplate.findOne(new Query(), OAuthFlowState.class);
        connectionService.completeCallback(p, "code-xyz", state.getId(), null, WS, m.getId());
        return connectionRepo.findByWorkspaceIdAndMemberIdAndProvider(WS, m.getId(), p).orElseThrow();
    }

    /** A cold MongoTemplate (fresh PII converter) — reads as if after a restart. */
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

    protected static String tokenJson(String access, String refresh, long expiresIn, String account) {
        StringBuilder b = new StringBuilder("{\"access_token\":\"").append(access).append("\"");
        if (refresh != null) {
            b.append(",\"refresh_token\":\"").append(refresh).append("\"");
        }
        b.append(",\"expires_in\":").append(expiresIn);
        b.append(",\"scope\":\"freebusy\"");
        if (account != null) {
            b.append(",\"id_token\":\"").append(idToken(account)).append("\"");
        }
        return b.append("}").toString();
    }

    /** Unsigned id_token (display-only; the gateway does not validate the signature). */
    protected static String idToken(String account) {
        return idTokenRaw("{\"email\":\"" + account + "\"}");
    }

    /** Unsigned id_token with a caller-supplied claims JSON payload. */
    protected static String idTokenRaw(String claimsJson) {
        return b64("{}") + "." + b64(claimsJson) + ".sig";
    }

    private static String b64(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }
}
