package com.cadence.scheduler;

import com.cadence.domain.CheckpointStatus;
import com.cadence.domain.SchedulerCheckpoint;
import com.cadence.repository.SchedulerCheckpointRepository;
import com.mongodb.client.result.UpdateResult;
import net.logstash.logback.argument.StructuredArguments;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class SchedulerCheckpointService {

    private static final Logger log = LoggerFactory.getLogger(SchedulerCheckpointService.class);
    private static final Duration STALE_THRESHOLD = Duration.ofMinutes(15);

    private final MongoTemplate mongoTemplate;
    private final SchedulerCheckpointRepository repository;
    private final DeadLetterService deadLetterService;
    private final Map<String, Runnable> replayRegistry = new HashMap<>();

    public SchedulerCheckpointService(MongoTemplate mongoTemplate,
                                       SchedulerCheckpointRepository repository,
                                       DeadLetterService deadLetterService) {
        this.mongoTemplate = mongoTemplate;
        this.repository = repository;
        this.deadLetterService = deadLetterService;
    }

    public void registerReplayAction(String taskName, Runnable replayAction) {
        replayRegistry.put(taskName, replayAction);
    }

    public SchedulerCheckpoint start(String taskName) {
        Query query = Query.query(Criteria.where("taskName").is(taskName));
        Update update = new Update()
            .set("taskName", taskName)
            .set("status", CheckpointStatus.RUNNING)
            .set("startedAt", Instant.now())
            .unset("completedAt")
            .unset("missedFireReplayedAt");

        try {
            return mongoTemplate.findAndModify(
                query,
                update,
                FindAndModifyOptions.options().upsert(true).returnNew(true),
                SchedulerCheckpoint.class
            );
        } catch (DuplicateKeyException e) {
            // Concurrent first-fire: another thread inserted the document between our find and
            // the upsert, and the unique taskName index rejected our insert. The document now
            // exists, so re-issue as a plain update to still record the RUNNING state.
            return mongoTemplate.findAndModify(
                query,
                update,
                FindAndModifyOptions.options().returnNew(true),
                SchedulerCheckpoint.class
            );
        }
    }

    public void complete(String taskName) {
        Query query = Query.query(Criteria.where("taskName").is(taskName));
        Update update = new Update()
            .set("status", CheckpointStatus.COMPLETED)
            .set("completedAt", Instant.now());
        UpdateResult result = mongoTemplate.updateFirst(query, update, SchedulerCheckpoint.class);
        if (result.getMatchedCount() == 0) {
            // start() was never called for this task, or the checkpoint was evicted — surface it
            // rather than silently producing no COMPLETED record.
            log.warn("complete() matched no checkpoint to update",
                StructuredArguments.kv("taskName", taskName));
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void replayMissedFires() {
        Instant threshold = Instant.now().minus(STALE_THRESHOLD);
        List<SchedulerCheckpoint> stale =
            repository.findByStatusAndStartedAtBefore(CheckpointStatus.RUNNING, threshold);

        for (SchedulerCheckpoint checkpoint : stale) {
            log.warn("Replaying missed-fire checkpoint",
                StructuredArguments.kv("taskName", checkpoint.getTaskName()),
                StructuredArguments.kv("startedAt", checkpoint.getStartedAt()));

            Runnable replayAction = replayRegistry.get(checkpoint.getTaskName());
            if (replayAction != null) {
                try {
                    replayAction.run();
                    // Stamp only after successful replay so a crash leaves the record retryable.
                    markReplayed(checkpoint.getTaskName());
                } catch (Exception e) {
                    log.error("Replay action failed",
                        StructuredArguments.kv("taskName", checkpoint.getTaskName()));
                    // Record the replay failure so an operator gets visibility, mirroring the
                    // live @Scheduled path. Leave the checkpoint RUNNING so it remains retryable.
                    deadLetterService.recordFailure(checkpoint.getTaskName(), e, null);
                }
            } else {
                // No replay action registered — mark processed so the stale RUNNING document is
                // not re-selected (and re-logged) on every subsequent restart.
                markReplayed(checkpoint.getTaskName());
            }
        }
    }

    private void markReplayed(String taskName) {
        Query query = Query.query(Criteria.where("taskName").is(taskName));
        // Transition to COMPLETED as well as stamping the replay time: otherwise the checkpoint
        // stays RUNNING and is re-selected as "stale" on every restart, replaying forever.
        Update update = new Update()
            .set("missedFireReplayedAt", Instant.now())
            .set("status", CheckpointStatus.COMPLETED)
            .set("completedAt", Instant.now());
        mongoTemplate.updateFirst(query, update, SchedulerCheckpoint.class);
    }
}
