package com.cadence.repository;

import com.cadence.domain.CheckpointStatus;
import com.cadence.domain.SchedulerCheckpoint;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface SchedulerCheckpointRepository extends MongoRepository<SchedulerCheckpoint, String> {

    Optional<SchedulerCheckpoint> findByTaskName(String taskName);

    List<SchedulerCheckpoint> findByStatusAndStartedAtBefore(CheckpointStatus status, Instant threshold);
}
