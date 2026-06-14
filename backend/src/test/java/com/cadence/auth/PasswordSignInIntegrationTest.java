package com.cadence.auth;

import com.cadence.BaseIntegrationTest;
import com.cadence.domain.Member;
import com.cadence.domain.PasswordCredential;
import com.cadence.domain.Role;
import com.cadence.domain.Session;
import com.cadence.service.LoginAttemptService;
import com.cadence.service.MemberService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** US3: email+password sign-in — uniform failures, lockout + recovery, IP throttle. */
@AutoConfigureMockMvc
@Import(AuthTestConfig.class)
class PasswordSignInIntegrationTest extends BaseIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery";

    @Autowired MockMvc mvc;
    @Autowired MemberService memberService;
    @Autowired PasswordEncoder encoder;
    @Autowired LoginAttemptService attempts;
    @Autowired MutableClock clock;

    @BeforeEach
    void cleanup() {
        clock.set(AuthTestConfig.FIXED_START);
        attempts.resetIpCounters();
        mongoTemplate.remove(new Query(), Member.class);
        mongoTemplate.remove(new Query(), Session.class);
    }

    private Member memberWithPassword() {
        return memberService.create("ws1", "pw@example.com", "PW User", Role.RECRUITER,
            new PasswordCredential(encoder.encode(PASSWORD)), null);
    }

    private String body(String email, String password) {
        return "{\"workspaceId\":\"ws1\",\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
    }

    @Test
    void correctCredentials_succeed() throws Exception {
        memberWithPassword();
        mvc.perform(post("/api/public/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(body("pw@example.com", PASSWORD)))
            .andExpect(status().isOk())
            .andExpect(header().exists("Set-Cookie"))
            .andExpect(jsonPath("$.role").value("RECRUITER"));
    }

    @Test
    void wrongPassword_returnsGeneric401() throws Exception {
        memberWithPassword();
        mvc.perform(post("/api/public/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(body("pw@example.com", "wrong-password")))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("invalid_credentials"));
    }

    @Test
    void unknownEmail_returnsSameGeneric401() throws Exception {
        mvc.perform(post("/api/public/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(body("nobody@example.com", "whatever-password")))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("invalid_credentials"));
    }

    @Test
    void lockoutAfterFiveFailures_thenRecoversAfterWindow() throws Exception {
        memberWithPassword();
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/api/public/auth/login").contentType(MediaType.APPLICATION_JSON)
                    .content(body("pw@example.com", "wrong" + i)))
                .andExpect(status().isUnauthorized());
        }
        // Account now locked. Reset IP counter so we probe the LOCKED path (uniform 401, not 429).
        attempts.resetIpCounters();
        mvc.perform(post("/api/public/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(body("pw@example.com", PASSWORD)))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("invalid_credentials"));

        // After the lockout window passes, the correct password works again.
        clock.advance(Duration.ofMinutes(16));
        attempts.resetIpCounters();
        mvc.perform(post("/api/public/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(body("pw@example.com", PASSWORD)))
            .andExpect(status().isOk());
    }

    @Test
    void perIpThrottle_returns429() throws Exception {
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/api/public/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(body("nobody@example.com", "x")));
        }
        mvc.perform(post("/api/public/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(body("nobody@example.com", "x")))
            .andExpect(status().isTooManyRequests());
    }
}
