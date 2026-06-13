package com.cadence.scheduler;

import com.cadence.BaseIntegrationTest;
import com.cadence.domain.CheckpointStatus;
import com.cadence.domain.SchedulerCheckpoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class SchedulerCheckpointTest extends BaseIntegrationTest {

    @Autowired
    private SchedulerCheckpointService checkpointService;

    @BeforeEach
    void setUp() {
        // Delete documents rather than dropping the collection: the singleton container is
        // shared across all test classes, and dropCollection() would also drop the unique
        // taskName index created once by Mongock (which will not re-run), breaking
        // IndexBootstrapTest. Removing documents preserves the index.
        mongoTemplate.remove(new Query(), SchedulerCheckpoint.class);
    }

    @Test
    void writePathCreatesRunningDocumentBeforeTaskProceeds() {
        checkpointService.start("testTask");

        SchedulerCheckpoint checkpoint = mongoTemplate.findOne(
            Query.query(Criteria.where("taskName").is("testTask")),
            SchedulerCheckpoint.class
        );

        assertThat(checkpoint).isNotNull();
        assertThat(checkpoint.getStatus()).isEqualTo(CheckpointStatus.RUNNING);
        assertThat(checkpoint.getStartedAt()).isNotNull();
        assertThat(checkpoint.getCompletedAt()).isNull();

        checkpointService.complete("testTask");

        SchedulerCheckpoint completed = mongoTemplate.findOne(
            Query.query(Criteria.where("taskName").is("testTask")),
            SchedulerCheckpoint.class
        );
        assertThat(completed).isNotNull();
        assertThat(completed.getStatus()).isEqualTo(CheckpointStatus.COMPLETED);
        assertThat(completed.getCompletedAt()).isNotNull();
    }

    @Test
    void replayPathRunsRegisteredActionExactlyOnceAndCompletesCheckpoint() {
        // Register a counting replay action so we verify the action actually fires (not just
        // that a timestamp is stamped) and that it fires exactly once — the US9 "no duplicate
        // work" idempotency criterion.
        AtomicInteger replayCount = new AtomicInteger(0);
        checkpointService.registerReplayAction("staleTask", replayCount::incrementAndGet);

        // Insert a stale RUNNING checkpoint (20 min old — beyond the 15-min threshold).
        SchedulerCheckpoint stale = new SchedulerCheckpoint();
        stale.setTaskName("staleTask");
        stale.setStatus(CheckpointStatus.RUNNING);
        stale.setStartedAt(Instant.now().minus(20, ChronoUnit.MINUTES));
        mongoTemplate.save(stale);

        checkpointService.replayMissedFires();

        assertThat(replayCount.get()).isEqualTo(1);

        SchedulerCheckpoint replayed = mongoTemplate.findOne(
            Query.query(Criteria.where("taskName").is("staleTask")),
            SchedulerCheckpoint.class
        );
        assertThat(replayed).isNotNull();
        assertThat(replayed.getMissedFireReplayedAt()).isNotNull();
        // After a successful replay the checkpoint must reach COMPLETED, otherwise it stays
        // RUNNING and would be re-selected as stale (and replayed) on every restart.
        assertThat(replayed.getStatus()).isEqualTo(CheckpointStatus.COMPLETED);

        // A second replay pass must NOT run the action again — the checkpoint is no longer
        // RUNNING, so it is not re-selected.
        checkpointService.replayMissedFires();
        assertThat(replayCount.get()).isEqualTo(1);
    }

    @Test
    void idempotentStartDoesNotCreateDuplicateDocuments() {
        // Upsert semantics: calling start() twice with the same taskName must produce exactly one doc
        checkpointService.start("idempotentTask");
        checkpointService.start("idempotentTask");

        long count = mongoTemplate.count(
            Query.query(Criteria.where("taskName").is("idempotentTask")),
            SchedulerCheckpoint.class
        );
        assertThat(count).isEqualTo(1);
    }
}
