package com.cadence.csvimport;

import com.cadence.domain.CsvImportJob;
import com.cadence.service.CsvImportProcessor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F42 SC-013: two concurrent import jobs in the same workspace importing the SAME new email produce EXACTLY ONE
 * candidate — guaranteed by the partial-unique {@code {workspaceId,emailHash}} over {@code origin:CSV_IMPORT}
 * index + the insert/catch-DuplicateKeyException re-resolve (the loser records the row DUPLICATE_PENDING).
 * Gated latch so both threads hit the create at once (non-vacuous, the F40 overlapping-sync shape).
 */
class ConcurrentImportIT extends CsvImportItBase {

    @Autowired CsvImportProcessor processor;

    @Test
    void twoConcurrentJobs_sameNewEmail_produceOneCandidate() throws Exception {
        String csv = "name,email\nSame Person,same@example.com\n";
        CsvImportJob jobA = newAcceptedJob(csv);
        CsvImportJob jobB = newAcceptedJob(csv);

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Runnable run = () -> {
                try {
                    start.await();
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            };
            var fa = pool.submit(() -> { run.run(); processor.process(jobA); return null; });
            var fb = pool.submit(() -> { run.run(); processor.process(jobB); return null; });
            start.countDown();
            fa.get(30, TimeUnit.SECONDS);
            fb.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(candidates.findAll()).hasSize(1);

        // Non-vacuous: exactly one job IMPORTED the row; the loser flagged it DUPLICATE_PENDING (the
        // DuplicateKeyException re-resolve branch). Re-read both jobs.
        CsvImportJob a = jobs.findById(jobA.getId()).orElseThrow();
        CsvImportJob b = jobs.findById(jobB.getId()).orElseThrow();
        long imported = (a.getImportedCount() == 1 ? 1 : 0) + (b.getImportedCount() == 1 ? 1 : 0);
        long pending = (a.getDuplicatePendingCount() == 1 ? 1 : 0) + (b.getDuplicatePendingCount() == 1 ? 1 : 0);
        assertThat(imported).isEqualTo(1);
        assertThat(pending).isEqualTo(1);
    }

    private CsvImportJob newAcceptedJob(String csv) {
        String jobId = importService.accept(WS, ACTOR, "c.csv",
            csv.getBytes(StandardCharsets.UTF_8), "text/csv", null).jobId();
        return jobs.findById(jobId).orElseThrow();
    }
}
