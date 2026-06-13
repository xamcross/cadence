package com.cadence.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Document(collection = "schedulerCheckpoints")
public class SchedulerCheckpoint {

    @Id
    private String id;

    private String taskName;
    private CheckpointStatus status;
    private Instant startedAt;
    private Instant completedAt;
    private Instant missedFireReplayedAt;

    public SchedulerCheckpoint() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTaskName() { return taskName; }
    public void setTaskName(String taskName) { this.taskName = taskName; }

    public CheckpointStatus getStatus() { return status; }
    public void setStatus(CheckpointStatus status) { this.status = status; }

    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }

    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }

    public Instant getMissedFireReplayedAt() { return missedFireReplayedAt; }
    public void setMissedFireReplayedAt(Instant missedFireReplayedAt) { this.missedFireReplayedAt = missedFireReplayedAt; }
}
