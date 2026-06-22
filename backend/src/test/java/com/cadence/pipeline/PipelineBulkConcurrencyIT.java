package com.cadence.pipeline;

import com.cadence.api.PipelineDtos.BulkAction;
import com.cadence.api.PipelineDtos.BulkRequest;
import com.cadence.domain.EmailDispatch;
import com.cadence.service.PipelineBulkService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Query;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F51 T036 / FR-019 / SC-005: a concurrent (or retried) bulk update-email submit for the same candidate produces
 * EXACTLY ONE send — the {@code {workspaceId,idempotencyKey}} outbox uniqueness (inherited from F22) deduplicates.
 * Deterministic because the {@link com.cadence.auth.MutableClock} is pinned, so both threads derive the same
 * idempotency key (workspace|candidate|type|scheduledForMillis).
 */
class PipelineBulkConcurrencyIT extends PipelineItBase {

    @Autowired PipelineBulkService bulkService;

    @Test
    void concurrentBulkSameCandidate_exactlyOneSend() throws Exception {
        configuredWorkspace();
        seedActive("c1", "Ada", 1, null);
        BulkRequest req = new BulkRequest(BulkAction.SEND_UPDATE_EMAIL, List.of("c1"), null, null, null, null, null);

        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    bulkService.execute(WS, "rec", req, "127.0.0.1");
                } catch (Exception ignored) {
                    // any per-thread error is irrelevant — the assertion is on the resulting row count
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();              // release all threads at once
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        long dispatchRows = mongoTemplate.count(new Query(), EmailDispatch.class);
        assertThat(dispatchRows).isEqualTo(1);   // exactly one send despite N concurrent submits (FR-019)
    }
}
