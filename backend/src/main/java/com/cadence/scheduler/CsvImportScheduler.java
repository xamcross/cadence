package com.cadence.scheduler;

import com.cadence.config.ImportProperties;
import com.cadence.domain.CsvImportJob;
import com.cadence.domain.CsvImportJobStatus;
import com.cadence.domain.CsvImportRowResult;
import com.cadence.domain.CsvImportRowStatus;
import com.cadence.repository.CsvImportJobRepository;
import com.cadence.service.CsvImportProcessor;
import com.mongodb.client.result.UpdateResult;
import jakarta.annotation.PostConstruct;
import net.logstash.logback.argument.StructuredArguments;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * F42 async import worker (the F22 {@code EmailDispatchScheduler} shape). A fixed-delay {@link #sweep()}:
 * <ol>
 *   <li>claims each due ACCEPTED job via a single-winner {@code findAndModify ACCEPTED->PROCESSING} CAS and
 *       runs {@link CsvImportProcessor#process}; an uncaught error -> FAILED + dispose blob + dead-letter
 *       (cause class only, never the message — the F22 PII lesson);</li>
 *   <li>TTL reaper: AWAITING_DUPLICATE_DECISION jobs past expiry -> default the remaining duplicates to SKIP,
 *       dispose the blob, EXPIRED (FR-021a);</li>
 *   <li>orphan reaper: a PROCESSING job whose worker died (updatedAt older than processing-threshold) is
 *       RE-QUEUED to ACCEPTED so the next sweep RESUMES it (no lost rows — SC-010); a logic error never leaves
 *       a job PROCESSING because the per-job try/catch flips it to FAILED.</li>
 * </ol>
 * Wrapped in {@link SchedulerCheckpointService#start}/{@code complete} + a {@code @PostConstruct} replay action
 * (the F00.2 contract). Correctness rests on the per-job CAS + the partial-unique candidate index, NOT on
 * single-threading.
 */
@Component
public class CsvImportScheduler {

    public static final String TASK_NAME = "csv-import-sweep";

    private static final Logger log = LoggerFactory.getLogger(CsvImportScheduler.class);

    private final SchedulerCheckpointService checkpoints;
    private final CsvImportJobRepository jobs;
    private final CsvImportProcessor processor;
    private final DeadLetterService deadLetters;
    private final MongoTemplate mongoTemplate;
    private final ImportProperties props;
    private final Clock clock;

    public CsvImportScheduler(SchedulerCheckpointService checkpoints, CsvImportJobRepository jobs,
                              CsvImportProcessor processor, DeadLetterService deadLetters,
                              MongoTemplate mongoTemplate, ImportProperties props, Clock clock) {
        this.checkpoints = checkpoints;
        this.jobs = jobs;
        this.processor = processor;
        this.deadLetters = deadLetters;
        this.mongoTemplate = mongoTemplate;
        this.props = props;
        this.clock = clock;
    }

    @PostConstruct
    void registerReplay() {
        // Operational invariant (D8, documented in ImportProperties): processing-threshold must exceed the REAL
        // sweep interval + max per-job time so the orphan/RESOLVING recovery never races a live worker. It is NOT
        // asserted at startup because the test profile deliberately parks sweep-fixed-delay far out (tests drive
        // sweep() directly), which would make a naive threshold>sweep-delay check spuriously fail.
        checkpoints.registerReplayAction(TASK_NAME, this::sweep);
    }

    @Scheduled(fixedDelayString = "${cadence.import.sweep-fixed-delay:PT5S}")
    public void scheduled() {
        sweep();
    }

    public void sweep() {
        checkpoints.start(TASK_NAME);
        try {
            requeueOrphans();
            drainDue();
            expireAwaiting();
        } finally {
            checkpoints.complete(TASK_NAME);
        }
    }

    private void drainDue() {
        Instant now = Instant.now(clock);
        List<CsvImportJob> due = jobs.findDue(CsvImportJobStatus.ACCEPTED, now,
            PageRequest.of(0, props.getSweepBatchLimit()));
        for (CsvImportJob row : due) {
            CsvImportJob claimed = claim(row.getId());
            if (claimed == null) {
                continue; // lost the claim race (no-op)
            }
            try {
                processor.process(claimed);
            } catch (RuntimeException e) {
                // FAILED: dispose the blob, flip status, dead-letter the CAUSE CLASS only (never getMessage()
                // — a CSVException can echo a raw cell; the F22 lesson). jobId is a bare ObjectId (safe).
                try {
                    processor.disposeBlob(claimed);
                } catch (RuntimeException ignore) {
                    // best-effort disposal; the TTL index is the backstop
                }
                Instant t = Instant.now(clock);
                mongoTemplate.updateFirst(
                    Query.query(Criteria.where("_id").is(claimed.getId())),
                    new Update().set("status", CsvImportJobStatus.FAILED).set("fileId", null)
                        .set("updatedAt", t).set("completedAt", t),
                    CsvImportJob.class);
                deadLetters.recordFailure(TASK_NAME,
                    new IllegalStateException("csv_import_failed: " + e.getClass().getSimpleName()),
                    claimed.getId());
            }
        }
    }

    /** ACCEPTED -> PROCESSING single-winner claim; returns the claimed row or null on a lost race. */
    private CsvImportJob claim(String jobId) {
        return mongoTemplate.findAndModify(
            Query.query(Criteria.where("_id").is(jobId).and("status").is(CsvImportJobStatus.ACCEPTED)),
            new Update().set("status", CsvImportJobStatus.PROCESSING).set("updatedAt", Instant.now(clock)),
            FindAndModifyOptions.options().returnNew(true),
            CsvImportJob.class);
    }

    private void expireAwaiting() {
        Instant now = Instant.now(clock);
        List<CsvImportJob> expired = jobs.findExpiredAwaiting(CsvImportJobStatus.AWAITING_DUPLICATE_DECISION, now,
            PageRequest.of(0, props.getSweepBatchLimit()));
        for (CsvImportJob candidate : expired) {
            // Atomically CLAIM AWAITING -> EXPIRED so the reaper never clobbers a concurrent recruiter resolve
            // (which first flips AWAITING -> RESOLVING). Only the CAS winner disposes the blob + skip-defaults.
            CsvImportJob job = mongoTemplate.findAndModify(
                Query.query(Criteria.where("_id").is(candidate.getId())
                    .and("status").is(CsvImportJobStatus.AWAITING_DUPLICATE_DECISION)),
                new Update().set("status", CsvImportJobStatus.EXPIRED).set("updatedAt", now).set("completedAt", now),
                FindAndModifyOptions.options().returnNew(true),
                CsvImportJob.class);
            if (job == null) {
                continue; // resolve won the race — leave it alone
            }
            for (CsvImportRowResult r : job.getRowResults()) {
                if (r.getStatus() == CsvImportRowStatus.DUPLICATE_PENDING) {
                    r.setStatus(CsvImportRowStatus.SKIPPED); // safe default for an abandoned decision
                }
            }
            processor.recount(job);
            processor.disposeBlob(job);
            jobs.save(job);
        }
    }

    private void requeueOrphans() {
        Instant threshold = Instant.now(clock).minus(props.getProcessingThreshold());
        // A worker that died mid-process leaves a stale PROCESSING job -> re-queue to ACCEPTED so the next sweep
        // RESUMES it (process() re-runs idempotently: the partial-unique index prevents double candidates,
        // everything else is deterministic recompute).
        recoverStale(CsvImportJobStatus.PROCESSING, CsvImportJobStatus.ACCEPTED, threshold);
        // A recruiter resolve that died mid-apply leaves a stale RESOLVING job -> recover to AWAITING so the
        // decision can be retried (or the TTL reaper can skip-default it).
        recoverStale(CsvImportJobStatus.RESOLVING, CsvImportJobStatus.AWAITING_DUPLICATE_DECISION, threshold);
    }

    private void recoverStale(CsvImportJobStatus from, CsvImportJobStatus to, Instant threshold) {
        List<CsvImportJob> orphans = jobs.findOrphanedProcessing(from, threshold,
            PageRequest.of(0, props.getSweepBatchLimit()));
        for (CsvImportJob job : orphans) {
            UpdateResult r = mongoTemplate.updateFirst(
                Query.query(Criteria.where("_id").is(job.getId()).and("status").is(from)),
                new Update().set("status", to).set("updatedAt", Instant.now(clock)),
                CsvImportJob.class);
            if (r.getModifiedCount() == 1) {
                log.warn("Recovered stale CSV import job {} {}",
                    StructuredArguments.kv("jobId", job.getId()), StructuredArguments.kv("from", from.name()));
            }
        }
    }
}
