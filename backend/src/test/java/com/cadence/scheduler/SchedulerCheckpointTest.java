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

import static org.assertj.core.api.Assertions.assertThat;

class SchedulerCheckpointTest extends BaseIntegrationTest {

    @Autowired
    private SchedulerCheckpointService checkpointService;

    @BeforeEach
    void setUp() {
        mongoTemplate.dropCollection(SchedulerCheckpoint.class);
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
    void replayPathSetsReplayedAtOnStaleRunningCheckpoint() {
        // Insert a stale RUNNING checkpoint (20 min old — beyond 15-min threshold)
        SchedulerCheckpoint stale = new SchedulerCheckpoint();
        stale.setTaskName("staleTask");
        stale.setStatus(CheckpointStatus.RUNNING);
        stale.setStartedAt(Instant.now().minus(20, ChronoUnit.MINUTES));
        mongoTemplate.save(stale);

        // Call the replay method directly (same logic as ApplicationReadyEvent listener)
        checkpointService.replayMissedFires();

        SchedulerCheckpoint replayed = mongoTemplate.findOne(
            Query.query(Criteria.where("taskName").is("staleTask")),
            SchedulerCheckpoint.class
        );
        assertThat(replayed).isNotNull();
        assertThat(replayed.getMissedFireReplayedAt()).isNotNull();
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
