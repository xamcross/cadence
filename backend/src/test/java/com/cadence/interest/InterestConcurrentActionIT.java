package com.cadence.interest;

import com.cadence.api.InterestExceptions;
import com.cadence.domain.InterestRequest;
import com.cadence.domain.Invitation;
import com.cadence.domain.Member;
import com.cadence.domain.Role;
import com.cadence.service.InterestRequestService.SubmitCommand;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T024/FR-016: a GATED concurrent invite by 2 admins on one request yields exactly one InvitationService.create
 * call (one invitation row) + one 409 (the single-winner claim CAS); review/dismiss on an already-terminal request
 * is a 409 no-op.
 */
class InterestConcurrentActionIT extends InterestItBase {

    @Test
    void concurrentInvite_exactlyOneInvitation_oneConflict() throws Exception {
        configuredWorkspace(365);
        interestService.submit(new SubmitCommand("Dana", "race@example.com", null, null, null, null), "1.1.1.1");
        InterestRequest req = interestRepo.findAll().get(0);
        Member a1 = member("a1@example.com", Role.ADMIN);
        Member a2 = member("a2@example.com", Role.ADMIN);

        int n = 2;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger conflicts = new AtomicInteger();
        AtomicInteger ok = new AtomicInteger();
        Member[] admins = {a1, a2};
        for (int i = 0; i < n; i++) {
            final Member actor = admins[i];
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                    interestService.invite(WS, req.getId(), Role.RECRUITER, actor.getId(), "1.1.1.1");
                    ok.incrementAndGet();
                } catch (InterestExceptions.ConflictException e) {
                    conflicts.incrementAndGet();
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        ready.await(5, TimeUnit.SECONDS);
        go.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(20, TimeUnit.SECONDS)).isTrue();

        assertThat(ok.get()).isEqualTo(1);
        assertThat(conflicts.get()).isEqualTo(1);
        assertThat(mongoTemplate.findAll(Invitation.class)).hasSize(1);
    }

    @Test
    void reviewOrDismiss_onTerminal_isConflict() {
        configuredWorkspace(365);
        interestService.submit(new SubmitCommand("Dana", "term@example.com", null, null, null, null), "1.1.1.1");
        InterestRequest req = interestRepo.findAll().get(0);
        Member admin = member("admin@example.com", Role.ADMIN);
        interestService.dismiss(WS, req.getId(), admin.getId()); // -> DISMISSED (terminal)

        assertThatThrownBy(() -> interestService.review(WS, req.getId(), admin.getId()))
            .isInstanceOf(InterestExceptions.ConflictException.class);
        assertThatThrownBy(() -> interestService.dismiss(WS, req.getId(), admin.getId()))
            .isInstanceOf(InterestExceptions.ConflictException.class);
    }
}
