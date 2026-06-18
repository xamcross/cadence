package com.cadence.csvimport;

import com.cadence.BaseIntegrationTest;
import com.cadence.domain.Candidate;
import com.cadence.domain.CsvImportFile;
import com.cadence.domain.CsvImportJob;
import com.cadence.repository.CandidateRepository;
import com.cadence.repository.CsvImportFileRepository;
import com.cadence.repository.CsvImportJobRepository;
import com.cadence.scheduler.CsvImportScheduler;
import com.cadence.service.CsvImportService;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Query;

import java.nio.charset.StandardCharsets;

/**
 * Shared fixture for the F42 CSV import tests. Cleans the import collections + candidates per test (remove,
 * NEVER dropCollection — would drop the Mongock 020 indexes). Provides the upload-then-drive-sweep helper.
 */
abstract class CsvImportItBase extends BaseIntegrationTest {

    protected static final String WS = "ws-csv";
    protected static final String ACTOR = "member-csv";

    @Autowired protected CsvImportService importService;
    @Autowired protected CsvImportScheduler importScheduler;
    @Autowired protected CsvImportJobRepository jobs;
    @Autowired protected CsvImportFileRepository files;
    @Autowired protected CandidateRepository candidates;

    @BeforeEach
    void cleanCsv() {
        mongoTemplate.remove(new Query(), CsvImportJob.class);
        mongoTemplate.remove(new Query(), CsvImportFile.class);
        mongoTemplate.remove(new Query(), Candidate.class);
    }

    /**
     * Accept an upload (202) then run one sweep so the async processor commits it. Returns the jobId. IP is null
     * so the advisory per-IP limiter (a singleton shared across tests, capped at 5/min in the test profile)
     * never blocks — rate-limiting is asserted separately via the contract test, not the ITs.
     */
    protected String uploadAndProcess(String csv) {
        String jobId = importService.accept(WS, ACTOR, "candidates.csv",
            csv.getBytes(StandardCharsets.UTF_8), "text/csv", null).jobId();
        importScheduler.sweep();
        return jobId;
    }

    /** Accept only (no sweep) — for the "no work before 202" assertions. Null IP (see {@link #uploadAndProcess}). */
    protected String uploadOnly(String csv) {
        return importService.accept(WS, ACTOR, "candidates.csv",
            csv.getBytes(StandardCharsets.UTF_8), "text/csv", null).jobId();
    }

    protected CsvImportJob job(String jobId) {
        return jobs.findById(jobId).orElseThrow();
    }
}
