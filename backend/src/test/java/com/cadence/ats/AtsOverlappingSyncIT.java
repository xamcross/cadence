package com.cadence.ats;

import com.cadence.domain.Candidate;
import com.cadence.integration.AtsProvider;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F41 FR-022 / edge case: a Greenhouse sync and a Lever sync running concurrently for one workspace must not
 * double-import, cross-merge, or corrupt records. A shared start latch fires both threads at once for genuine
 * overlap (non-vacuous concurrency).
 */
class AtsOverlappingSyncIT extends AtsItBase {

    private static final String WS = "ws-overlap";

    @Test
    void concurrentGreenhouseAndLeverSyncDoNotInterfere() throws Exception {
        connect(WS);
        connectLever(WS);
        for (int i = 1; i <= 10; i++) {
            stub.addCandidate("gh-" + i, "GH", "N" + i, "gh" + i + "@example.com", "1", "ghjob", "GH Eng", "Screen");
            leverStub.addOpportunity("lv-" + i, "LV N" + i, "lv" + i + "@example.com", "2", "lvjob", "LV Eng", "Phone");
        }

        CountDownLatch start = new CountDownLatch(1);
        Runnable gh = () -> { await(start); sync(WS); };
        Runnable lv = () -> { await(start); syncLever(WS); };
        Thread tg = new Thread(gh);
        Thread tl = new Thread(lv);
        tg.start();
        tl.start();
        start.countDown(); // release both simultaneously
        tg.join(30_000);
        tl.join(30_000);

        List<Candidate> all = candidates.findAll();
        assertThat(all).hasSize(20); // 10 GH + 10 LV, no double-import, no cross-merge
        assertThat(all).filteredOn(c -> c.getAtsProvider() == AtsProvider.GREENHOUSE).hasSize(10);
        assertThat(all).filteredOn(c -> c.getAtsProvider() == AtsProvider.LEVER).hasSize(10);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
