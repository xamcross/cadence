package com.cadence.scheduler;

import com.cadence.config.AtsProperties;
import com.cadence.domain.AtsWriteBack;
import com.cadence.domain.AtsWriteBackStatus;
import com.cadence.repository.AtsWriteBackRepository;
import com.cadence.service.AtsWriteBackService;
import jakarta.annotation.PostConstruct;
import net.logstash.logback.argument.StructuredArguments;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * F40 outbound write-back drain (US3) + reaper (US4). The drain claims PENDING-due rows through
 * {@link AtsWriteBackService#claimAndDeliver} (per-row CAS — a double-pick is a no-op). The reaper reconciles
 * crashed SENDING rows (no blind re-send — the SC-003 honest bound). Wrapped in
 * {@link SchedulerCheckpointService} + {@code @PostConstruct} replay (the F00.2 contract).
 */
@Component
public class AtsWriteBackScheduler {

    public static final String TASK_NAME = "ats-writeback-drain";

    private static final Logger log = LoggerFactory.getLogger(AtsWriteBackScheduler.class);

    private final SchedulerCheckpointService checkpoints;
    private final AtsWriteBackRepository repo;
    private final AtsWriteBackService writeBacks;
    private final AtsProperties props;
    private final Clock clock;

    public AtsWriteBackScheduler(SchedulerCheckpointService checkpoints, AtsWriteBackRepository repo,
                                 AtsWriteBackService writeBacks, AtsProperties props, Clock clock) {
        this.checkpoints = checkpoints;
        this.repo = repo;
        this.writeBacks = writeBacks;
        this.props = props;
        this.clock = clock;
    }

    @PostConstruct
    void registerReplay() {
        checkpoints.registerReplayAction(TASK_NAME, this::drain);
    }

    @Scheduled(fixedDelayString = "${cadence.ats.writeback-interval-ms:30000}")
    public void scheduledDrain() {
        drain();
    }

    @Scheduled(fixedDelayString = "${cadence.ats.reaper-interval-ms:60000}")
    public void scheduledReap() {
        writeBacks.reapStuck();
    }

    /** One drain pass (also the registered missed-fire replay action). */
    public void drain() {
        checkpoints.start(TASK_NAME);
        try {
            Instant now = Instant.now(clock);
            List<AtsWriteBack> due = repo.findDue(AtsWriteBackStatus.PENDING, now,
                PageRequest.of(0, props.getWritebackBatchLimit()));
            for (AtsWriteBack w : due) {
                writeBacks.claimAndDeliver(w.getId());
            }
            if (!due.isEmpty()) {
                log.info("ATS write-back drain {}", StructuredArguments.kv("due", due.size()));
            }
        } finally {
            checkpoints.complete(TASK_NAME);
        }
    }
}
