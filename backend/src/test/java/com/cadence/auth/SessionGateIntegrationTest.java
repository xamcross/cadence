package com.cadence.auth;

import com.cadence.BaseIntegrationTest;
import com.cadence.domain.Member;
import com.cadence.domain.MemberStatus;
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

import java.time.Duration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** US2: internal endpoints gated, candidate paths public, revocation/expiry effective next request. */
@AutoConfigureMockMvc
@Import(AuthTestConfig.class)
class SessionGateIntegrationTest extends BaseIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired MemberService memberService;
    @Autowired SessionService sessionService;
    @Autowired MemberRepository members;
    @Autowired SessionRepository sessions;
    @Autowired MutableClock clock;

    @BeforeEach
    void cleanup() {
        clock.set(AuthTestConfig.FIXED_START);
        mongoTemplate.remove(new Query(), Member.class);
        mongoTemplate.remove(new Query(), Session.class);
    }

    private Member activeMember() {
        return memberService.create("ws1", "gate@example.com", "Gate User", Role.RECRUITER, null, null);
    }

    private Cookie sessionCookie(Member m) {
        return new Cookie("cad_session", sessionService.issue(m).jwt());
    }

    @Test
    void noSession_returns401() throws Exception {
        mvc.perform(get("/api/internal/auth/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void validSession_returns200() throws Exception {
        mvc.perform(get("/api/internal/auth/me").cookie(sessionCookie(activeMember())))
            .andExpect(status().isOk());
    }

    @Test
    void candidatePath_isNotGated() throws Exception {
        mvc.perform(get("/api/candidate/__probe")).andExpect(status().isOk());
    }

    @Test
    void tamperedCookie_returns401() throws Exception {
        String jwt = sessionService.issue(activeMember()).jwt();
        String tampered = jwt.substring(0, jwt.length() - 2) + (jwt.endsWith("a") ? "bb" : "aa");
        mvc.perform(get("/api/internal/auth/me").cookie(new Cookie("cad_session", tampered)))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void absoluteExpiry_returns401() throws Exception {
        Cookie cookie = sessionCookie(activeMember());
        clock.advance(Duration.ofHours(9)); // past the 8h absolute TTL
        mvc.perform(get("/api/internal/auth/me").cookie(cookie)).andExpect(status().isUnauthorized());
    }

    @Test
    void deactivation_effectiveOnNextRequest() throws Exception {
        Member m = activeMember();
        Cookie cookie = sessionCookie(m);
        mvc.perform(get("/api/internal/auth/me").cookie(cookie)).andExpect(status().isOk());
        m.setStatus(MemberStatus.DEACTIVATED);
        members.save(m);
        mvc.perform(get("/api/internal/auth/me").cookie(cookie)).andExpect(status().isUnauthorized());
    }
}
