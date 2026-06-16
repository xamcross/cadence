package com.cadence.emaildelivery;

import com.cadence.domain.CheckpointStatus;
import com.cadence.domain.DispatchOutcomeReason;
import com.cadence.domain.DispatchStatus;
import com.cadence.domain.EmailDispatch;
import com.cadence.domain.EmailMessageType;
import com.cadence.domain.RenderedMessage;
import com.cadence.domain.SchedulerCheckpoint;
import com.cadence.scheduler.EmailDispatchScheduler;
import com.cadence.scheduler.SchedulerCheckpointService;
import com.cadence.service.EmailTemplateService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * T047 (US4) — missed-fire replay (downtime spanning a scheduledFor). A stale {@code RUNNING} checkpoint for
 * the sweep task triggers the registered {@code @PostConstruct} replay action exactly once on
 * {@code replayMissedFires()}, firing the due row a single time (no duplicate). The checkpoint is then
 * COMPLETED so a subsequent replay does not re-run it.
 */
class EmailDispatchMissedFireTest extends EmailDeliveryItBase {

    @Autowired EmailDispatchScheduler scheduler;
    @Autowired SchedulerCheckpointService checkpoints;
    @MockBean EmailTemplateService templates;

    /** Seed a due PENDING row directly (scheduledFor/nextAttemptAt = test-now), bypassing the inline enqueue run. */
    private EmailDispatch seedDuePending(String candidateId) {
        EmailDispatch d = new EmailDispatch();
        d.setWorkspaceId(WS);
        d.setCandidateId(candidateId);
        d.setMessageType(EmailMessageType.CONFIRMATION);
        d.setStageKey("BASE");
        d.setIdempotencyKey("idem-" + candidateId);
        d.setStatus(DispatchStatus.PENDING);
        d.setLastOutcomeReason(DispatchOutcomeReason.NONE);
        d.setScheduledFor(Instant.now(clock));
        d.setNextAttemptAt(Instant.now(clock));
        d.setCreatedAt(Instant.now(clock));
        d.setUpdatedAt(Instant.now(clock));
        return mongoTemplate.save(d);
    }

    @Test
    void staleRunningCheckpoint_replaysSweepOnce_noDuplicate() {
        seedContactableCandidate("c1", "Dana", "dana@example.com");
        when(templates.renderForSend(eq(WS), eq(EmailMessageType.CONFIRMATION), anyString(), eq("c1"), any()))
            .thenReturn(new RenderedMessage("Subject", "Body", "Body", List.of()));
        EmailDispatch row = seedDuePending("c1");

        // A stale RUNNING checkpoint for the sweep task (downtime — startedAt older than the 15-min threshold,
        // which the checkpoint service evaluates against wall-clock now).
        SchedulerCheckpoint cp = new SchedulerCheckpoint();
        cp.setTaskName(EmailDispatchScheduler.TASK_NAME);
        cp.setStatus(CheckpointStatus.RUNNING);
        cp.setStartedAt(Instant.now().minus(Duration.ofMinutes(30)));
        mongoTemplate.save(cp);

        // Replay (fired on ApplicationReadyEvent in production) runs the registered sweep once.
        checkpoints.replayMissedFires();

        assertThat(recordingTransport.sentCount()).isEqualTo(1);
        assertThat(mongoTemplate.findById(row.getId(), EmailDispatch.class).getStatus())
            .isEqualTo(DispatchStatus.SENT);

        // The checkpoint is no longer stale-RUNNING -> a second replay is a no-op (no duplicate send).
        SchedulerCheckpoint after = mongoTemplate.findOne(
            org.springframework.data.mongodb.core.query.Query.query(
                org.springframework.data.mongodb.core.query.Criteria.where("taskName")
                    .is(EmailDispatchScheduler.TASK_NAME)), SchedulerCheckpoint.class);
        assertThat(after.getStatus()).isEqualTo(CheckpointStatus.COMPLETED);

        checkpoints.replayMissedFires();
        assertThat(recordingTransport.sentCount()).isEqualTo(1);
    }
}
