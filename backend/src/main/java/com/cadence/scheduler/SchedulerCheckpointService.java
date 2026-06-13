package com.cadence.scheduler;

import com.cadence.domain.CheckpointStatus;
import com.cadence.domain.SchedulerCheckpoint;
import com.cadence.repository.SchedulerCheckpointRepository;
import net.logstash.logback.argument.StructuredArguments;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
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
    private final Map<String, Runnable> replayRegistry = new HashMap<>();

    public SchedulerCheckpointService(MongoTemplate mongoTemplate,
                                       SchedulerCheckpointRepository repository) {
        this.mongoTemplate = mongoTemplate;
        this.repository = repository;
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

        return mongoTemplate.findAndModify(
            query,
            update,
            FindAndModifyOptions.options().upsert(true).returnNew(true),
            SchedulerCheckpoint.class
        );
    }

    public void complete(String taskName) {
        Query query = Query.query(Criteria.where("taskName").is(taskName));
        Update update = new Update()
            .set("status", CheckpointStatus.COMPLETED)
            .set("completedAt", Instant.now());
        mongoTemplate.updateFirst(query, update, SchedulerCheckpoint.class);
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
                    // Stamp only after successful replay so a crash leaves the record retryable
                    Query query = Query.query(Criteria.where("taskName").is(checkpoint.getTaskName()));
                    Update update = new Update().set("missedFireReplayedAt", Instant.now());
                    mongoTemplate.updateFirst(query, update, SchedulerCheckpoint.class);
                } catch (Exception e) {
                    log.error("Replay action failed",
                        StructuredArguments.kv("taskName", checkpoint.getTaskName()));
                }
            } else {
                // No replay action registered — mark processed to prevent repeated logging
                Query query = Query.query(Criteria.where("taskName").is(checkpoint.getTaskName()));
                Update update = new Update().set("missedFireReplayedAt", Instant.now());
                mongoTemplate.updateFirst(query, update, SchedulerCheckpoint.class);
            }
        }
    }
}
