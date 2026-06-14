package com.cadence.rbac;

import com.cadence.BaseIntegrationTest;
import com.cadence.auth.AuthTestConfig;
import com.cadence.auth.MutableClock;
import com.cadence.domain.AuthAuditEvent;
import com.cadence.domain.AuthEventType;
import com.cadence.domain.Member;
import com.cadence.domain.Role;
import com.cadence.domain.Session;
import com.cadence.repository.MemberRepository;
import com.cadence.service.MemberService;
import com.cadence.service.RoleService;
import com.cadence.service.SessionService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * US1 role administration over HTTP (T021): persistence + audit, NEXT-request effect on the SAME
 * cookie (SC-003), self-elevation vector (SC-007), null/unknown role denied (FR-008), non-Admin 403,
 * and concurrent role change to one member (SC-012). Uses the fixed MutableClock so the cookie exp
 * check is deterministic.
 */
@AutoConfigureMockMvc
@Import(AuthTestConfig.class)
class RoleChangeIntegrationTest extends BaseIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired MemberService memberService;
    @Autowired MemberRepository members;
    @Autowired SessionService sessionService;
    @Autowired RoleService roleService;
    @Autowired MutableClock clock;

    @BeforeEach
    void cleanup() {
        clock.set(AuthTestConfig.FIXED_START);
        mongoTemplate.remove(new Query(), Member.class);
        mongoTemplate.remove(new Query(), Session.class);
        mongoTemplate.remove(new Query(), AuthAuditEvent.class);
    }

    private Member member(String email, Role role) {
        return memberService.create("ws1", email, email, role, null, null);
    }

    private Cookie cookie(Member m) {
        return new Cookie("cad_session", sessionService.issue(m).jwt());
    }

    private static String roleBody(String role) {
        return "{\"role\":\"" + role + "\"}";
    }

    @Test
    void adminChangesRole_persistedAndAudited() throws Exception {
        Member admin = member("admin@x.com", Role.ADMIN);
        Member target = member("rec@x.com", Role.RECRUITER);
        mvc.perform(patch("/api/internal/members/{id}/role", target.getId())
                .cookie(cookie(admin)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(roleBody("READ_ONLY")))
            .andExpect(status().isOk());
        assertThat(members.findById(target.getId()).orElseThrow().getRole()).isEqualTo(Role.READ_ONLY);
        List<AuthAuditEvent> audits = mongoTemplate.findAll(AuthAuditEvent.class);
        assertThat(audits).anyMatch(a -> a.getEventType() == AuthEventType.ROLE_CHANGED
            && target.getId().equals(a.getTargetMemberId())
            && a.getOldRole() == Role.RECRUITER && a.getNewRole() == Role.READ_ONLY);
    }

    @Test
    void roleChange_effectiveOnNextRequest_sameCookie() throws Exception {
        Member admin = member("admin@x.com", Role.ADMIN);
        Member target = member("rec@x.com", Role.RECRUITER);
        Cookie targetCookie = cookie(target); // issued while RECRUITER

        // A RECRUITER may list assignments today (Read-only is excluded) -> 200.
        mvc.perform(get("/api/internal/assignments").cookie(targetCookie)).andExpect(status().isOk());

        // Admin demotes the target to READ_ONLY.
        mvc.perform(patch("/api/internal/members/{id}/role", target.getId())
                .cookie(cookie(admin)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(roleBody("READ_ONLY")))
            .andExpect(status().isOk());

        // SAME cookie, no re-login, no clock advance -> now governed by READ_ONLY -> 403.
        mvc.perform(get("/api/internal/assignments").cookie(targetCookie)).andExpect(status().isForbidden());
    }

    @Test
    void nonAdmin_cannotChangeRole() throws Exception {
        Member recruiter = member("rec@x.com", Role.RECRUITER);
        Member target = member("other@x.com", Role.INTERVIEWER);
        mvc.perform(patch("/api/internal/members/{id}/role", target.getId())
                .cookie(cookie(recruiter)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(roleBody("READ_ONLY")))
            .andExpect(status().isForbidden());
        assertThat(members.findById(target.getId()).orElseThrow().getRole()).isEqualTo(Role.INTERVIEWER);
    }

    @Test
    void adminSelfDemotion_blockedWhenLastAdmin_allowedWhenAnotherRemains() throws Exception {
        Member onlyAdmin = member("admin@x.com", Role.ADMIN);
        // last admin self-demote -> 409
        mvc.perform(patch("/api/internal/members/{id}/role", onlyAdmin.getId())
                .cookie(cookie(onlyAdmin)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(roleBody("RECRUITER")))
            .andExpect(status().isConflict());
        assertThat(members.findById(onlyAdmin.getId()).orElseThrow().getRole()).isEqualTo(Role.ADMIN);

        // add a second admin -> self-demote now allowed
        member("admin2@x.com", Role.ADMIN);
        mvc.perform(patch("/api/internal/members/{id}/role", onlyAdmin.getId())
                .cookie(cookie(onlyAdmin)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(roleBody("RECRUITER")))
            .andExpect(status().isOk());
        assertThat(members.findById(onlyAdmin.getId()).orElseThrow().getRole()).isEqualTo(Role.RECRUITER);
    }

    @Test
    void memberWithNullRole_isDeniedEverywhere_neverAdmin() throws Exception {
        Member admin = member("admin@x.com", Role.ADMIN);
        Member broken = member("broken@x.com", Role.RECRUITER);
        Cookie brokenCookie = cookie(broken); // issued while the member has a valid role
        // Corrupt the persisted role to null (FR-008: a missing/unknown stored role -> least privilege,
        // never Admin), effective on the next request via the persisted-role authority (D3).
        broken.setRole(null);
        members.save(broken);
        mvc.perform(get("/api/internal/members").cookie(brokenCookie)).andExpect(status().isForbidden());
        // sanity: a real admin still gets 200
        mvc.perform(get("/api/internal/members").cookie(cookie(admin))).andExpect(status().isOk());
    }

    @Test
    void concurrentRoleChangeToSameMember_exactlyOneValue_bothAudited() throws Exception {
        member("admin@x.com", Role.ADMIN); // ensure an admin exists (not the target)
        Member target = member("t@x.com", Role.RECRUITER);
        int n = 2;
        Role[] desired = {Role.READ_ONLY, Role.INTERVIEWER};
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch go = new CountDownLatch(1);
        for (int i = 0; i < n; i++) {
            final Role r = desired[i];
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                    roleService.changeRole("ws1", "admin", target.getId(), r);
                } catch (RuntimeException | InterruptedException ignored) {
                }
            });
        }
        ready.await();
        go.countDown();
        pool.shutdown();
        //noinspection ResultOfMethodCallIgnored
        pool.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS);

        Role finalRole = members.findById(target.getId()).orElseThrow().getRole();
        assertThat(finalRole).isIn(Role.READ_ONLY, Role.INTERVIEWER); // exactly one of the two, no torn state
        // Both changes target a non-Admin with distinct desired roles, so neither is a no-op and
        // neither hits the last-Admin guard — BOTH must be audited (SC-012): exactly 2 rows. (The
        // torn-state / lost-write guarantee is covered separately by the finalRole assertion above.)
        long roleChanges = mongoTemplate.findAll(AuthAuditEvent.class).stream()
            .filter(a -> a.getEventType() == AuthEventType.ROLE_CHANGED).count();
        assertThat(roleChanges).isEqualTo(2);
    }
}
