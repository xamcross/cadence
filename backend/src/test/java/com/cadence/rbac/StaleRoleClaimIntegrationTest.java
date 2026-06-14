package com.cadence.rbac;

import com.cadence.BaseIntegrationTest;
import com.cadence.auth.AuthTestConfig;
import com.cadence.auth.MutableClock;
import com.cadence.domain.Member;
import com.cadence.domain.Role;
import com.cadence.domain.Session;
import com.cadence.repository.MemberRepository;
import com.cadence.repository.SessionRepository;
import com.cadence.service.MemberService;
import com.cadence.service.SessionService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * FR-002 / SC-007 stale-claim vector (T031): the authorization role is the PERSISTED member role,
 * never the signed session claim. Issue a session while ADMIN (the cookie's JWT claim + the session
 * snapshot are ADMIN), demote the member in the DB, then replay the SAME valid signed cookie — the
 * request is governed by READ_ONLY (403 on an admin-only endpoint). The fixed MutableClock keeps the
 * cookie exp deterministic. This tests claim-vs-DB precedence, not signature rejection.
 */
@AutoConfigureMockMvc
@Import(AuthTestConfig.class)
class StaleRoleClaimIntegrationTest extends BaseIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired MemberService memberService;
    @Autowired MemberRepository members;
    @Autowired SessionService sessionService;
    @Autowired SessionRepository sessions;
    @Autowired MutableClock clock;

    @BeforeEach
    void cleanup() {
        clock.set(AuthTestConfig.FIXED_START);
        mongoTemplate.remove(new Query(), Member.class);
        mongoTemplate.remove(new Query(), Session.class);
    }

    @Test
    void staleAdminClaim_doesNotGrantAccess_persistedRoleWins() {
        Member m = memberService.create("ws1", "person@x.com", "Person", Role.ADMIN, null, null);
        SessionService.Issued issued = sessionService.issue(m); // claim + snapshot = ADMIN
        Cookie cookie = new Cookie("cad_session", issued.jwt());

        // Demote in the DB directly (simulating an Admin's role change).
        m.setRole(Role.READ_ONLY);
        members.save(m);

        // Same signed cookie (valid signature, ADMIN claim) -> admin endpoint must be 403.
        try {
            mvc.perform(get("/api/internal/members").cookie(cookie)).andExpect(status().isForbidden());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        // The session snapshot is unchanged (non-authoritative) — proves the DB role, not the
        // snapshot/claim, drove the decision.
        Session stored = sessions.findById(issued.session().getId()).orElseThrow();
        assertThat(stored.getRole()).isEqualTo(Role.ADMIN);
    }
}
