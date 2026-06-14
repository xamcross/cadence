package com.cadence.rbac;

import com.cadence.BaseIntegrationTest;
import com.cadence.auth.AuthTestConfig;
import com.cadence.api.RbacExceptions;
import com.cadence.domain.Member;
import com.cadence.domain.MemberStatus;
import com.cadence.domain.Role;
import com.cadence.domain.Session;
import com.cadence.repository.MemberRepository;
import com.cadence.repository.SessionRepository;
import com.cadence.service.MemberService;
import com.cadence.service.RoleService;
import com.cadence.service.SessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Last-Admin guard (T022): single-actor refusal (SC-004), CONCURRENT double-demotion never strands
 * the workspace with zero active Admins (SC-013), and a refused deactivation leaves no partial state
 * (D4 ordering). Real MongoDB via Testcontainers; concurrency gated on a shared CountDownLatch (no
 * Thread.sleep).
 */
@Import(AuthTestConfig.class)
class LastAdminGuardIntegrationTest extends BaseIntegrationTest {

    @Autowired MemberService memberService;
    @Autowired MemberRepository members;
    @Autowired SessionRepository sessions;
    @Autowired SessionService sessionService;
    @Autowired RoleService roleService;

    @BeforeEach
    void cleanup() {
        mongoTemplate.remove(new Query(), Member.class);
        mongoTemplate.remove(new Query(), Session.class);
    }

    private Member admin(String email) {
        return memberService.create("ws1", email, email, Role.ADMIN, null, null);
    }

    private long activeAdmins() {
        return mongoTemplate.count(new Query(Criteria.where("workspaceId").is("ws1")
            .and("role").is(Role.ADMIN).and("status").is(MemberStatus.ACTIVE)), Member.class);
    }

    @Test
    void demotingTheOnlyAdmin_isRefused() {
        Member only = admin("only@x.com");
        assertThatThrownBy(() -> roleService.changeRole("ws1", "actor", only.getId(), Role.RECRUITER))
            .isInstanceOf(RbacExceptions.LastAdminException.class);
        assertThat(members.findById(only.getId()).orElseThrow().getRole()).isEqualTo(Role.ADMIN);
        assertThat(activeAdmins()).isEqualTo(1);
    }

    @Test
    void concurrentDoubleDemotion_neverZeroAdmins_atMostOneSucceeds() throws Exception {
        for (int iteration = 0; iteration < 20; iteration++) {
            mongoTemplate.remove(new Query(), Member.class);
            Member a1 = admin("a1@x.com");
            Member a2 = admin("a2@x.com");

            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);
            AtomicInteger successes = new AtomicInteger();
            for (Member a : new Member[]{a1, a2}) {
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        go.await();
                        roleService.changeRole("ws1", "actor", a.getId(), Role.RECRUITER);
                        successes.incrementAndGet();
                    } catch (RbacExceptions.LastAdminException ignored) {
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            ready.await();
            go.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

            // The invariant: never zero active Admins; at most one demotion succeeded.
            assertThat(activeAdmins()).isGreaterThanOrEqualTo(1);
            assertThat(successes.get()).isLessThanOrEqualTo(1);
        }
    }

    @Test
    void refusedDeactivationOfLastAdmin_leavesNoPartialState() {
        Member only = admin("only@x.com");
        // give the admin an active session to prove it is NOT revoked on a refused deactivation
        sessionService.issue(only);
        assertThatThrownBy(() -> roleService.guardedDeactivate("ws1", only.getId()))
            .isInstanceOf(RbacExceptions.LastAdminException.class);
        Member after = members.findById(only.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(MemberStatus.ACTIVE); // not deactivated
        assertThat(sessions.findByMemberId(only.getId())).allMatch(s -> !s.isRevoked()); // sessions intact
    }

    @Test
    void deactivatingAnAdmin_succeedsWhenAnotherRemains() {
        Member a1 = admin("a1@x.com");
        admin("a2@x.com");
        roleService.guardedDeactivate("ws1", a1.getId());
        assertThat(members.findById(a1.getId()).orElseThrow().getStatus()).isEqualTo(MemberStatus.DEACTIVATED);
        assertThat(activeAdmins()).isEqualTo(1);
    }
}
