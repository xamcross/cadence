package com.cadence.interest;

import com.cadence.domain.InterestRequest;
import com.cadence.domain.InterestRequestStatus;
import com.cadence.domain.Role;
import com.cadence.service.InterestRequestService.SubmitCommand;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T011: dedup coalesce. A GATED concurrent double-submit of the same email yields exactly one open row (the
 * DuplicateKeyException -> coalesce branch); a resubmit AFTER a terminal row creates a fresh NEW row (the
 * openEmailHash $unset on terminal drops the old row from the unique partial index).
 */
class InterestDedupeIT extends InterestItBase {

    private SubmitCommand cmd() {
        return new SubmitCommand("Dana", "dana.dedup@example.com", null, null, null, null);
    }

    @Test
    void concurrentDoubleSubmit_yieldsExactlyOneOpenRow() throws Exception {
        int n = 8;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger errors = new AtomicInteger();
        for (int i = 0; i < n; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                    interestService.submit(cmd(), "9.9.9." + Thread.currentThread().getId() % 250);
                } catch (RuntimeException e) {
                    errors.incrementAndGet();
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        ready.await(5, TimeUnit.SECONDS);
        go.countDown(); // release all at once (gated, non-vacuous)
        pool.shutdown();
        assertThat(pool.awaitTermination(20, TimeUnit.SECONDS)).isTrue();

        long open = interestRepo.findAll().stream()
            .filter(r -> r.getStatus() == InterestRequestStatus.NEW
                || r.getStatus() == InterestRequestStatus.REVIEWED)
            .count();
        assertThat(open).isEqualTo(1L);
        assertThat(interestRepo.findAll()).hasSize(1);
    }

    @Test
    void resubmitAfterTerminal_createsFreshNewRow() {
        interestService.submit(cmd(), "1.1.1.1");
        InterestRequest first = interestRepo.findAll().get(0);
        // Dismiss it (terminal; $unset openEmailHash).
        configuredWorkspace(365);
        var admin = member("admin@example.com", Role.ADMIN);
        interestService.dismiss(WS, first.getId(), admin.getId());

        // Resubmit the same email -> a brand-new NEW row (the old row's openEmailHash is gone from the index).
        interestService.submit(cmd(), "1.1.1.1");
        assertThat(interestRepo.findAll()).hasSize(2);
        long open = interestRepo.findAll().stream()
            .filter(r -> r.getStatus() == InterestRequestStatus.NEW)
            .count();
        assertThat(open).isEqualTo(1L);
    }
}
