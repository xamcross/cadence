package com.cadence.status;

import com.cadence.api.CandidateStatusDtos.PublishStatusRequest;
import com.cadence.domain.Candidate;
import com.cadence.domain.CandidateStatusOutcome;
import com.cadence.service.CandidateStatusService;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F30 T026 (FR-016/Story2 AC-5): two simultaneous publishes resolve to a single consistent published status,
 * no partial/mixed state. Each publish is one atomic {@code $set} of all status fields, so the persisted
 * document always matches exactly one of the two writers (last-valid-write-wins) — never a stage from A with
 * a date from B. Gated by a latch so the two writers genuinely race.
 */
class ConcurrentPublishIT extends StatusItBase {

    @Autowired CandidateStatusService service;

    @RepeatedTest(5)
    void twoSimultaneousPublishes_yieldOneConsistentStatus() throws Exception {
        configuredWorkspace();
        seedCandidate("c1", "Ada", "ada@x.test");
        LocalDate dateA = LocalDate.now(clock).plusDays(2);
        LocalDate dateB = LocalDate.now(clock).plusDays(9);
        PublishStatusRequest a = new PublishStatusRequest(
            CandidateStatusOutcome.IN_PROGRESS, "STAGE_A", "NEXT_A", dateA);
        PublishStatusRequest b = new PublishStatusRequest(
            CandidateStatusOutcome.IN_PROGRESS, "STAGE_B", "NEXT_B", dateB);

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Runnable wa = () -> { await(start); service.publish(WS, "c1", "A", a); };
            Runnable wb = () -> { await(start); service.publish(WS, "c1", "B", b); };
            pool.submit(wa);
            pool.submit(wb);
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        Candidate c = mongoTemplate.findById("c1", Candidate.class);
        assertThat(c).isNotNull();
        // The persisted state is consistent with exactly ONE writer (no cross-mixed stage/next/date).
        boolean isA = "STAGE_A".equals(c.getStatusStage()) && "NEXT_A".equals(c.getStatusNextStep())
            && dateA.equals(c.getStatusExpectedDate());
        boolean isB = "STAGE_B".equals(c.getStatusStage()) && "NEXT_B".equals(c.getStatusNextStep())
            && dateB.equals(c.getStatusExpectedDate());
        assertThat(isA ^ isB).as("exactly one consistent published status (no partial/mixed state)").isTrue();
    }

    private static void await(CountDownLatch l) {
        try {
            l.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }
}
