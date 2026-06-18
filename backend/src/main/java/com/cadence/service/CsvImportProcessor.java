package com.cadence.service;

import com.cadence.config.ImportProperties;
import com.cadence.domain.Candidate;
import com.cadence.domain.CandidateOrigin;
import com.cadence.domain.CsvImportFile;
import com.cadence.domain.CsvImportJob;
import com.cadence.domain.CsvImportJobStatus;
import com.cadence.domain.CsvImportRejectReason;
import com.cadence.domain.CsvImportRowResult;
import com.cadence.domain.CsvImportRowStatus;
import com.cadence.domain.CsvRowFailureReason;
import com.cadence.domain.ErasureState;
import com.cadence.repository.CandidateRepository;
import com.cadence.repository.CsvImportFileRepository;
import com.cadence.repository.CsvImportJobRepository;
import com.cadence.security.PiiCrypto;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * F42 the heart of the import — parse (Commons CSV, confined here so the parser is swappable behind the
 * {@link CsvRow} seam), validate per-row, detect duplicates, and create/flag each row. The ONLY class in the
 * service layer that references {@code org.apache.commons.csv} (asserted structurally — the F22
 * {@code MailTransportSwapTest} precedent).
 *
 * <p><b>Restart-/concurrency-safe (SC-010/SC-013)</b>: a full re-run of a resumed/orphaned job is idempotent —
 * a row whose candidate this job already created is re-detected (origin=CSV_IMPORT + importJobId==this job) and
 * recorded IMPORTED again with the SAME candidate (never a duplicate), and the partial-unique
 * {@code {workspaceId,emailHash}} over {@code origin:CSV_IMPORT} index turns a concurrent same-new-email create
 * into a {@link DuplicateKeyException} that resolves to the single winner. Per-row results are recorded so a
 * resume skips nothing incorrectly; counts are recomputed deterministically from {@code rowResults}.
 *
 * <p><b>PII discipline</b>: no raw cell value is logged; a {@code CSVException} is reduced to its cause class
 * (never {@code getMessage()}) before it can reach a log/result/dead-letter (FR-017).
 */
@Service
public class CsvImportProcessor {

    private static final CSVFormat FORMAT = CSVFormat.DEFAULT.builder()
        .setHeader()
        .setSkipHeaderRecord(true)
        .setIgnoreSurroundingSpaces(true)
        .setIgnoreEmptyLines(true)
        .setTrim(true)
        .build();

    private final CsvImportJobRepository jobs;
    private final CsvImportFileRepository files;
    private final CandidateRepository candidates;
    private final CandidateService candidateService;
    private final CsvRowValidator validator;
    private final PiiCrypto crypto;
    private final ImportProperties props;
    private final Clock clock;

    public CsvImportProcessor(CsvImportJobRepository jobs, CsvImportFileRepository files,
                              CandidateRepository candidates, CandidateService candidateService,
                              CsvRowValidator validator, PiiCrypto crypto, ImportProperties props, Clock clock) {
        this.jobs = jobs;
        this.files = files;
        this.candidates = candidates;
        this.candidateService = candidateService;
        this.validator = validator;
        this.crypto = crypto;
        this.props = props;
        this.clock = clock;
    }

    /** Process one claimed (PROCESSING) job to a terminal/AWAITING state. Throws only on an unexpected error. */
    public void process(CsvImportJob job) {
        CsvImportFile file = files.findByJobId(job.getId()).orElse(null);
        if (file == null) {
            // Blob already disposed (e.g. a duplicate replay after completion) — nothing to do. Complete the
            // job so it never lingers in PROCESSING (the data is gone; this path is reached only on an
            // anomalous re-run of an already-finished job).
            Instant now = Instant.now(clock);
            job.setStatus(CsvImportJobStatus.COMPLETED);
            job.setUpdatedAt(now);
            if (job.getCompletedAt() == null) {
                job.setCompletedAt(now);
            }
            jobs.save(job);
            return;
        }

        ParseResult parsed = parse(file);
        if (parsed.schemaInvalid) {
            rejectWholeFile(job, CsvImportRejectReason.SCHEMA_INVALID);
            return;
        }

        List<ParsedRow> rows = parsed.rows;
        if (rows.size() > props.getMaxRowCount()) {
            rejectWholeFile(job, CsvImportRejectReason.OVER_LIMIT);
            return;
        }

        // Whole-file reject when failures/N > rejectRatio (N>0). Malformed rows count as failures (D7).
        int n = rows.size();
        long failures = rows.stream().filter(r -> r.failure != null).count();
        if (n > 0 && (double) failures / n > props.getRejectRatio()) {
            rejectWholeFile(job, CsvImportRejectReason.TOO_MANY_INVALID);
            return;
        }

        // Commit phase. Re-runnable: rebuild the intra-file seen-set + reuse already-recorded results.
        Map<Integer, CsvImportRowResult> existing = new LinkedHashMap<>();
        for (CsvImportRowResult rr : job.getRowResults()) {
            existing.put(rr.getRowNumber(), rr);
        }
        Set<String> seenEmailHashes = new HashSet<>();
        List<CsvImportRowResult> results = new ArrayList<>();
        boolean anyPending = false;

        for (ParsedRow pr : rows) {
            CsvImportRowResult result;
            if (pr.failure != null) {
                result = new CsvImportRowResult(pr.rowNumber, CsvImportRowStatus.REJECTED);
                result.setReason(pr.failure);
                result.setFailingField(pr.failingField);
            } else {
                String emailHash = crypto.emailHash(pr.row.email());
                if (!seenEmailHashes.add(emailHash)) {
                    // Intra-file duplicate: collapse to the first occurrence (counted once).
                    result = new CsvImportRowResult(pr.rowNumber, CsvImportRowStatus.SKIPPED);
                } else {
                    result = commitRow(job, pr.row, emailHash);
                }
                if (result.getStatus() == CsvImportRowStatus.DUPLICATE_PENDING) {
                    anyPending = true;
                }
            }
            results.add(result);
        }

        job.setRowResults(results);
        job.setTotalRows(n);
        recount(job);
        Instant now = Instant.now(clock);
        job.setUpdatedAt(now);
        if (anyPending) {
            job.setStatus(CsvImportJobStatus.AWAITING_DUPLICATE_DECISION); // keep the blob (merge needs the cells)
            jobs.save(job);
        } else {
            job.setStatus(CsvImportJobStatus.COMPLETED);
            job.setCompletedAt(now);
            disposeBlob(job);
            jobs.save(job);
        }
    }

    /**
     * Resolve a single duplicate row by number (the merge path needs the original cells). Returns the parsed
     * {@link CsvRow} or empty if the blob/row is gone. Confines the parser to this class for the resolve flow.
     */
    public Optional<CsvRow> findRow(CsvImportJob job, int rowNumber) {
        CsvImportFile file = files.findByJobId(job.getId()).orElse(null);
        if (file == null) {
            return Optional.empty();
        }
        return parse(file).rows.stream()
            .filter(pr -> pr.failure == null && pr.rowNumber == rowNumber)
            .map(pr -> pr.row)
            .findFirst();
    }

    /** Dispose the raw blob and null the job's fileId (idempotent). */
    public void disposeBlob(CsvImportJob job) {
        if (job.getFileId() != null) {
            files.deleteByJobId(job.getId());
            job.setFileId(null);
        } else {
            files.findByJobId(job.getId()).ifPresent(f -> files.deleteByJobId(job.getId()));
        }
    }

    /** Recompute the counters from rowResults (deterministic; safe across replays). */
    public void recount(CsvImportJob job) {
        int imported = 0, merged = 0, skipped = 0, rejected = 0, pending = 0;
        for (CsvImportRowResult r : job.getRowResults()) {
            switch (r.getStatus()) {
                case IMPORTED -> imported++;
                case MERGED -> merged++;
                case SKIPPED -> skipped++;
                case REJECTED -> rejected++;
                case DUPLICATE_PENDING -> pending++;
            }
        }
        job.setImportedCount(imported);
        job.setMergedCount(merged);
        job.setSkippedCount(skipped);
        job.setRejectedCount(rejected);
        job.setDuplicatePendingCount(pending);
    }

    private CsvImportRowResult commitRow(CsvImportJob job, CsvRow row, String emailHash) {
        Optional<Candidate> mine = findOwnImport(job, emailHash);
        if (mine.isPresent()) {
            // Already created by THIS job (resume/replay) — idempotent IMPORTED, no double candidate.
            CsvImportRowResult r = new CsvImportRowResult(row.rowNumber(), CsvImportRowStatus.IMPORTED);
            r.setCandidateId(mine.get().getId());
            return r;
        }
        Optional<Candidate> existing = findActiveMatch(job.getWorkspaceId(), emailHash);
        if (existing.isPresent()) {
            CsvImportRowResult r = new CsvImportRowResult(row.rowNumber(), CsvImportRowStatus.DUPLICATE_PENDING);
            r.setEmailHash(emailHash);
            r.setExistingCandidateId(existing.get().getId());
            return r;
        }
        try {
            Candidate created = candidateService.createImported(job.getWorkspaceId(), row.name(), row.email(),
                blankToNull(row.phone()), blankToNull(row.stage()), blankToNull(row.requisition()),
                job.getId(), job.getActorMemberId());
            CsvImportRowResult r = new CsvImportRowResult(row.rowNumber(), CsvImportRowStatus.IMPORTED);
            r.setCandidateId(created.getId());
            return r;
        } catch (DuplicateKeyException e) {
            // Concurrent CSV create of the same new email lost the partial-unique race (SC-013). Re-resolve:
            // my own (a same-job double) -> IMPORTED; another job's -> DUPLICATE_PENDING.
            Optional<Candidate> own = findOwnImport(job, emailHash);
            if (own.isPresent()) {
                CsvImportRowResult r = new CsvImportRowResult(row.rowNumber(), CsvImportRowStatus.IMPORTED);
                r.setCandidateId(own.get().getId());
                return r;
            }
            CsvImportRowResult r = new CsvImportRowResult(row.rowNumber(), CsvImportRowStatus.DUPLICATE_PENDING);
            r.setEmailHash(emailHash);
            findActiveMatch(job.getWorkspaceId(), emailHash).ifPresent(c -> r.setExistingCandidateId(c.getId()));
            return r;
        }
    }

    private Optional<Candidate> findOwnImport(CsvImportJob job, String emailHash) {
        return candidates.findByWorkspaceIdAndEmailHash(job.getWorkspaceId(), emailHash).stream()
            .filter(c -> c.getErasureState() == ErasureState.ACTIVE)
            .filter(c -> c.getOrigin() == CandidateOrigin.CSV_IMPORT && job.getId().equals(c.getImportJobId()))
            .findFirst();
    }

    private Optional<Candidate> findActiveMatch(String workspaceId, String emailHash) {
        return candidates.findByWorkspaceIdAndEmailHash(workspaceId, emailHash).stream()
            .filter(c -> c.getErasureState() == ErasureState.ACTIVE)
            .findFirst();
    }

    private void rejectWholeFile(CsvImportJob job, CsvImportRejectReason reason) {
        Instant now = Instant.now(clock);
        job.setStatus(CsvImportJobStatus.REJECTED);
        job.setRejectionReason(reason);
        job.setUpdatedAt(now);
        job.setCompletedAt(now);
        disposeBlob(job);
        jobs.save(job);
    }

    private ParseResult parse(CsvImportFile file) {
        String text = new String(Base64.getDecoder().decode(file.getDataBase64()), StandardCharsets.UTF_8);
        text = stripBom(text);
        ParseResult out = new ParseResult();
        try (CSVParser parser = CSVParser.parse(new StringReader(text), FORMAT)) {
            Map<String, Integer> header = lowerHeader(parser);
            if (!header.containsKey("name") || !header.containsKey("email")) {
                out.schemaInvalid = true;
                return out;
            }
            long lastRecord = 1; // header is record 1
            var it = parser.iterator();
            while (true) {
                CSVRecord rec;
                try {
                    if (!it.hasNext()) {
                        break;
                    }
                    rec = it.next();
                } catch (RuntimeException malformed) {
                    // A structurally malformed record (e.g. unterminated quote) -> a per-row MALFORMED_ROW
                    // failure (value-free; the cause is NOT logged). Commons CSV cannot recover the tokenizer
                    // after this, so subsequent rows cannot be read; the file is NOT crashed (FR-009).
                    ParsedRow bad = new ParsedRow();
                    bad.rowNumber = (int) (lastRecord + 1);
                    bad.failure = CsvRowFailureReason.MALFORMED_ROW;
                    bad.failingField = null;
                    out.rows.add(bad);
                    break;
                }
                lastRecord = rec.getRecordNumber();
                CsvRow row = new CsvRow((int) rec.getRecordNumber(),
                    get(rec, header, "name"), get(rec, header, "email"), get(rec, header, "phone"),
                    get(rec, header, "stage"), get(rec, header, "requisition"));
                ParsedRow pr = new ParsedRow();
                pr.rowNumber = row.rowNumber();
                pr.row = row;
                pr.failure = validator.validate(row).orElse(null);
                pr.failingField = pr.failure == null ? null : validator.failingField(row);
                out.rows.add(pr);
            }
        } catch (Exception e) {
            // A header-level parse failure (e.g. empty/garbled header) -> schema invalid. Never log the cause text.
            out.schemaInvalid = true;
        }
        return out;
    }

    private static Map<String, Integer> lowerHeader(CSVParser parser) {
        Map<String, Integer> out = new LinkedHashMap<>();
        Map<String, Integer> raw = parser.getHeaderMap();
        if (raw != null) {
            raw.forEach((k, v) -> {
                if (k != null) {
                    out.put(k.trim().toLowerCase(java.util.Locale.ROOT), v);
                }
            });
        }
        return out;
    }

    private static String get(CSVRecord rec, Map<String, Integer> header, String col) {
        Integer idx = header.get(col);
        if (idx == null || idx >= rec.size()) {
            return null;
        }
        return rec.get(idx);
    }

    private static String stripBom(String s) {
        return (!s.isEmpty() && s.charAt(0) == '﻿') ? s.substring(1) : s;
    }

    private static String blankToNull(String s) {
        return (s == null || s.trim().isEmpty()) ? null : s;
    }

    private static final class ParseResult {
        boolean schemaInvalid;
        final List<ParsedRow> rows = new ArrayList<>();
    }

    private static final class ParsedRow {
        int rowNumber;
        CsvRow row;
        CsvRowFailureReason failure;
        String failingField;
    }
}
