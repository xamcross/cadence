package com.cadence.csvimport;

import com.cadence.domain.CsvImportJobStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F42 SC-010 restart idempotency. Honest bound (the F31/F40 precedent): this is a DOUBLE-SWEEP proxy, not a true
 * kill-9 — a second sweep over a completed/re-queued job must produce no duplicate candidates and no lost rows.
 * The partial-unique CSV emailHash index + the own-import detection make a full re-run idempotent.
 */
class CsvImportRestartIT extends CsvImportItBase {

    @Test
    void doubleSweep_producesNoDuplicateCandidates() {
        String csv = """
            name,email
            Ada,ada@example.com
            Alan,alan@example.com
            """;
        uploadAndProcess(csv);          // first sweep completes the job
        importScheduler.sweep();        // second sweep — must be a no-op (job COMPLETED, not due)
        assertThat(candidates.findAll()).hasSize(2);
    }

    @Test
    void orphanedProcessingJob_isReQueuedAndResumesToCompletion() {
        // A worker that claimed the job (PROCESSING) then died mid-run leaves the blob present + a stale
        // updatedAt. uploadOnly creates ACCEPTED + blob; force it to PROCESSING/stale WITHOUT processing.
        String jobId = uploadOnly("name,email\nAda,ada@example.com\nAlan,alan@example.com\n");
        mongoTemplate.updateFirst(
            org.springframework.data.mongodb.core.query.Query.query(
                org.springframework.data.mongodb.core.query.Criteria.where("_id").is(jobId)),
            new org.springframework.data.mongodb.core.query.Update()
                .set("status", CsvImportJobStatus.PROCESSING)
                .set("updatedAt", java.time.Instant.now().minusSeconds(3600)),
            com.cadence.domain.CsvImportJob.class);

        importScheduler.sweep(); // requeueOrphans -> ACCEPTED, then drainDue -> claim -> process -> COMPLETED

        assertThat(candidates.findAll()).hasSize(2); // resumed, no lost rows, no duplicates
        assertThat(job(jobId).getStatus()).isEqualTo(CsvImportJobStatus.COMPLETED);
    }
}
