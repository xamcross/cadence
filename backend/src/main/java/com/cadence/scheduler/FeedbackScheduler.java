package com.cadence.scheduler;

import com.cadence.config.FeedbackProperties;
import com.cadence.domain.FeedbackRequest;
import com.cadence.domain.FeedbackRequestStatus;
import com.cadence.domain.SchedulingRequest;
import com.cadence.repository.FeedbackRequestRepository;
import com.cadence.repository.SchedulingRequestRepository;
import com.cadence.service.FeedbackService;
import jakarta.annotation.PostConstruct;
import net.logstash.logback.argument.StructuredArguments;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

/**
 * F32 Interviewer Feedback sweep (the F23 {@code NoShowDefenseScheduler} shape). A fixed-delay sweep with two
 * stages — (1) generate requests for occurred interviews, (2) escalate reminders for unsubmitted requests — each
 * driven through a per-row CAS in {@link FeedbackService} (correctness rests on the CAS, not single-threading).
 * Wrapped in {@code SchedulerCheckpointService.start/complete} + a {@code @PostConstruct} replay registration
 * (the F00.2 contract) so a missed firing window replays once on {@code ApplicationReadyEvent}.
 *
 * <p>Both stages read an index-backed, {@code Pageable}-capped range; {@code now} is the injected {@link Clock}.
 * The scheduler has NO token-read path (the write-only structural guarantee, SC-008).
 */
@Component
public class FeedbackScheduler {

    public static final String TASK_NAME = "feedback-scan";

    private static final Logger log = LoggerFactory.getLogger(FeedbackScheduler.class);

    private final SchedulerCheckpointService checkpoints;
    private final SchedulingRequestRepository scheduling;
    private final FeedbackRequestRepository feedback;
    private final FeedbackService service;
    private final FeedbackProperties props;
    private final Clock clock;

    public FeedbackScheduler(SchedulerCheckpointService checkpoints, SchedulingRequestRepository scheduling,
                             FeedbackRequestRepository feedback, FeedbackService service,
                             FeedbackProperties props, Clock clock) {
        this.checkpoints = checkpoints;
        this.scheduling = scheduling;
        this.feedback = feedback;
        this.service = service;
        this.props = props;
        this.clock = clock;
    }

    @PostConstruct
    void registerReplay() {
        checkpoints.registerReplayAction(TASK_NAME, this::sweep);
    }

    @Scheduled(fixedDelayString = "${cadence.feedback.scan-interval:PT5M}")
    public void scheduled() {
        sweep();
    }

    /** One feedback pass (also the registered missed-fire replay action). */
    public void sweep() {
        checkpoints.start(TASK_NAME);
        try {
            Instant now = Instant.now(clock);
            PageRequest page = PageRequest.of(0, props.getScanBatchLimit());

            int generatedBatches = 0;
            int reminded = 0;

            // Stage 1: generate requests for interviews that have occurred (bookedStartAt passed by generationDelay).
            Instant cutoff = now.minus(props.getGenerationDelay());
            Instant lowerBound = now.minus(props.getGenerationQueryLowerBound());
            for (SchedulingRequest req : scheduling.findFeedbackGenerationDue(lowerBound, cutoff, page)) {
                service.generateForOccurredInterview(req, now);
                generatedBatches++;
            }

            // Stage 2: escalate reminders for unsubmitted requests whose next reminder is due.
            for (FeedbackRequest req : feedback.findReminderDue(FeedbackRequestStatus.PENDING, now, page)) {
                service.sendReminderIfDue(req, now);
                reminded++;
            }

            if (generatedBatches + reminded > 0) {
                log.info("feedback sweep {} {}",
                    StructuredArguments.kv("generatedBatches", generatedBatches),
                    StructuredArguments.kv("reminded", reminded));
            }
        } finally {
            checkpoints.complete(TASK_NAME);
        }
    }
}
