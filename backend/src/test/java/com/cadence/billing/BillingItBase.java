package com.cadence.billing;

import com.cadence.BaseIntegrationTest;
import com.cadence.auth.AuthTestConfig;
import com.cadence.auth.MutableClock;
import com.cadence.domain.BillingWebhookEvent;
import com.cadence.domain.Member;
import com.cadence.domain.Role;
import com.cadence.domain.Session;
import com.cadence.domain.WorkspaceEntitlement;
import com.cadence.service.MemberService;
import com.cadence.service.SessionService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

/**
 * 032 -- billing IT base (the WorkspaceItBase shape): MockMvc + fixed MutableClock + JVM-lifetime
 * StubFreemius wired into cadence.billing.base-url. Cleanup is remove-not-drop (Mongock indexes).
 */
@AutoConfigureMockMvc
@Import(AuthTestConfig.class)
public abstract class BillingItBase extends BaseIntegrationTest {

    protected static final String WS = "ws1";
    protected static final StubFreemius stub = new StubFreemius();

    @DynamicPropertySource
    static void billingProps(DynamicPropertyRegistry r) {
        r.add("cadence.billing.base-url", stub::baseUrl);
    }

    @Autowired protected MockMvc mvc;
    @Autowired protected MemberService memberService;
    @Autowired protected SessionService sessionService;
    @Autowired protected MutableClock clock;

    @BeforeEach
    void cleanBilling() {
        clock.set(AuthTestConfig.FIXED_START);
        stub.reset();
        mongoTemplate.remove(new Query(), Member.class);
        mongoTemplate.remove(new Query(), Session.class);
        mongoTemplate.remove(new Query(), WorkspaceEntitlement.class);
        mongoTemplate.remove(new Query(), BillingWebhookEvent.class);
    }

    protected Member member(String email, Role role) {
        return memberService.findByEmail(WS, email)
            .orElseGet(() -> memberService.create(WS, email, email, role, null, null));
    }

    protected Cookie cookie(Member m) {
        return new Cookie("cad_session", sessionService.issue(m).jwt());
    }

    protected Cookie adminCookie() {
        return cookie(member("admin@x.com", Role.ADMIN));
    }

    /** Seed a bound TEAM entitlement directly (bypasses claim -- for gate/lifecycle tests). */
    protected WorkspaceEntitlement seedTeam(String workspaceId, String licenseId, Instant expiresAt) {
        WorkspaceEntitlement e = new WorkspaceEntitlement();
        e.setWorkspaceId(workspaceId);
        e.setFsLicenseId(licenseId);
        e.setFsPlanId("2002");
        e.setExpiresAt(expiresAt);
        e.setBoundAt(Instant.now(clock));
        e.setUpdatedAt(Instant.now(clock));
        return mongoTemplate.insert(e);
    }
}
