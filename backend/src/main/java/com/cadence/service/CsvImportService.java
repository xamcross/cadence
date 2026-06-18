package com.cadence.service;

import com.cadence.api.CsvImportDtos;
import com.cadence.api.CsvImportExceptions;
import com.cadence.api.RbacExceptions;
import com.cadence.config.ImportProperties;
import com.cadence.domain.CandidateAuditOutcome;
import com.cadence.domain.CandidateEventType;
import com.cadence.domain.Candidate;
import com.cadence.domain.CsvImportFile;
import com.cadence.domain.CsvImportJob;
import com.cadence.domain.CsvImportJobStatus;
import com.cadence.domain.CsvImportRowResult;
import com.cadence.domain.CsvImportRowStatus;
import com.cadence.domain.ErasureState;
import com.cadence.repository.CsvImportFileRepository;
import com.cadence.repository.CsvImportJobRepository;
import com.mongodb.client.result.UpdateResult;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * F42 CSV import orchestration: {@link #accept} (store blob + insert job, 202), {@link #status} (workspace-
 * scoped no-oracle read), {@link #resolve} (merge/skip duplicates). Parsing/commit is the async
 * {@link CsvImportProcessor} (driven by the scheduler); merge re-parses the blob via the processor so the
 * Commons-CSV dependency stays confined there.
 */
@Service
public class CsvImportService {

    private final CsvImportJobRepository jobs;
    private final CsvImportFileRepository files;
    private final CsvImportProcessor processor;
    private final MongoTemplate mongoTemplate;
    private final CandidateAuditService audit;
    private final CandidateRateLimiter rateLimiter;
    private final ImportProperties props;
    private final Clock clock;

    public CsvImportService(CsvImportJobRepository jobs, CsvImportFileRepository files, CsvImportProcessor processor,
                            MongoTemplate mongoTemplate, CandidateAuditService audit, CandidateRateLimiter rateLimiter,
                            ImportProperties props, Clock clock) {
        this.jobs = jobs;
        this.files = files;
        this.processor = processor;
        this.mongoTemplate = mongoTemplate;
        this.audit = audit;
        this.rateLimiter = rateLimiter;
        this.props = props;
        this.clock = clock;
    }

    /**
     * Accept an upload: in-service size/empty gate, advisory rate-limit, store the raw bytes (encrypted) +
     * insert an ACCEPTED job. NO parse/validate/commit here (SC-002/SC-013). The originalFilename is stored
     * for the status surface and NEVER logged.
     */
    public CsvImportDtos.UploadAccepted accept(String workspaceId, String actorMemberId, String filename,
                                               byte[] bytes, String contentType, String ip) {
        if (!rateLimiter.tryAcquire(ip)) {
            throw new CsvImportExceptions.RateLimitedException();
        }
        if (bytes == null || bytes.length == 0) {
            throw new CsvImportExceptions.InvalidImportException();
        }
        if (bytes.length > props.getMaxFileSize().toBytes()) {
            throw new CsvImportExceptions.InvalidImportException();
        }
        Instant now = Instant.now(clock);

        CsvImportJob job = new CsvImportJob();
        job.setWorkspaceId(workspaceId);
        job.setActorMemberId(actorMemberId);
        job.setStatus(CsvImportJobStatus.ACCEPTED);
        job.setOriginalFilename(filename);
        job.setExpiresAt(now.plus(props.getJobTtl()));
        job.setCreatedAt(now);
        job.setUpdatedAt(now);
        job = jobs.save(job);

        CsvImportFile file = new CsvImportFile();
        file.setJobId(job.getId());
        file.setWorkspaceId(workspaceId);
        file.setDataBase64(Base64.getEncoder().encodeToString(bytes));
        file.setContentType(contentType);
        file.setSizeBytes(bytes.length);
        file.setCreatedAt(now);
        file = files.save(file);

        job.setFileId(file.getId());
        jobs.save(job);

        return new CsvImportDtos.UploadAccepted(job.getId(), job.getStatus().name());
    }

    /** Workspace-scoped status read; an unknown/cross-workspace id is an indistinguishable 404 (SC-015). */
    public CsvImportDtos.JobStatusResponse status(String workspaceId, String jobId) {
        CsvImportJob job = jobs.findByWorkspaceIdAndId(workspaceId, jobId)
            .orElseThrow(RbacExceptions.ScopedNotFoundException::new);
        return toResponse(job);
    }

    /**
     * Apply per-row merge/skip decisions (+ optional default for the rest). Allowed only from
     * AWAITING_DUPLICATE_DECISION (else 409). Idempotent: an already-resolved row is skipped (no double-count).
     * MERGE is an atomic active-state-guarded update (no erased-PII resurrection — FR-014).
     */
    public CsvImportDtos.JobStatusResponse resolve(String workspaceId, String actorMemberId, String jobId,
                                                   CsvImportDtos.ResolveRequest req) {
        CsvImportJob job = jobs.findByWorkspaceIdAndId(workspaceId, jobId)
            .orElseThrow(RbacExceptions.ScopedNotFoundException::new);
        if (job.getStatus() != CsvImportJobStatus.AWAITING_DUPLICATE_DECISION) {
            throw new CsvImportExceptions.InvalidStateException();
        }
        String defaultAction = req == null ? null : normalize(req.defaultAction());
        java.util.Map<Integer, String> perRow = new java.util.HashMap<>();
        if (req != null && req.decisions() != null) {
            for (CsvImportDtos.Decision d : req.decisions()) {
                if (d == null) continue;
                String a = normalize(d.action());
                if (!"MERGE".equals(a) && !"SKIP".equals(a)) {
                    throw new CsvImportExceptions.InvalidImportException();
                }
                perRow.put(d.rowNumber(), a);
            }
        }

        for (CsvImportRowResult r : job.getRowResults()) {
            if (r.getStatus() != CsvImportRowStatus.DUPLICATE_PENDING) {
                continue; // idempotent: already resolved / not a duplicate
            }
            String action = perRow.getOrDefault(r.getRowNumber(), defaultAction);
            if (action == null) {
                continue; // left pending until decided (or TTL skip-default)
            }
            if ("MERGE".equals(action)) {
                applyMerge(job, r, actorMemberId);
            } else {
                r.setStatus(CsvImportRowStatus.SKIPPED);
            }
        }

        boolean stillPending = job.getRowResults().stream()
            .anyMatch(r -> r.getStatus() == CsvImportRowStatus.DUPLICATE_PENDING);
        processor.recount(job);
        Instant now = Instant.now(clock);
        job.setUpdatedAt(now);
        if (!stillPending) {
            job.setStatus(CsvImportJobStatus.COMPLETED);
            job.setCompletedAt(now);
            processor.disposeBlob(job);
        }
        jobs.save(job);
        return toResponse(job);
    }

    private void applyMerge(CsvImportJob job, CsvImportRowResult r, String actorMemberId) {
        Optional<CsvRow> cells = processor.findRow(job, r.getRowNumber());
        if (r.getExistingCandidateId() == null || cells.isEmpty()) {
            // Cannot recover the row/target — fail safe to skip (never resurrect, never guess).
            r.setStatus(CsvImportRowStatus.SKIPPED);
            return;
        }
        CsvRow row = cells.get();
        Update u = new Update();
        boolean any = false;
        if (notBlank(row.name())) { u.set("name", row.name()); any = true; }
        if (notBlank(row.phone())) { u.set("phone", row.phone()); any = true; }
        if (notBlank(row.stage())) { u.set("importStageLabel", row.stage()); any = true; }
        if (notBlank(row.requisition())) { u.set("importRequisitionLabel", row.requisition()); any = true; }
        // Atomic active-state guard: a merge racing an erasure no-ops (matchedCount 0), no PII resurrection.
        Query q = Query.query(Criteria.where("_id").is(r.getExistingCandidateId())
            .and("workspaceId").is(job.getWorkspaceId())
            .and("erasureState").is(ErasureState.ACTIVE));
        if (any) {
            UpdateResult res = mongoTemplate.updateFirst(q, u, Candidate.class);
            if (res.getMatchedCount() == 1) {
                r.setStatus(CsvImportRowStatus.MERGED);
                r.setCandidateId(r.getExistingCandidateId());
                audit.append(job.getWorkspaceId(), r.getExistingCandidateId(),
                    CandidateEventType.STAGE_CHANGED, CandidateAuditOutcome.RECORDED, actorMemberId);
                return;
            }
            // erased between flag and resolve -> skip-equivalent, no resurrection.
            r.setStatus(CsvImportRowStatus.SKIPPED);
            return;
        }
        // All CSV cells blank -> nothing to merge; treat as merged-noop only if target is still ACTIVE.
        long active = mongoTemplate.count(q, Candidate.class);
        r.setStatus(active == 1 ? CsvImportRowStatus.MERGED : CsvImportRowStatus.SKIPPED);
        if (active == 1) {
            r.setCandidateId(r.getExistingCandidateId());
        }
    }

    private CsvImportDtos.JobStatusResponse toResponse(CsvImportJob job) {
        List<CsvImportDtos.RowResultDto> rows = job.getRowResults().stream()
            .map(r -> new CsvImportDtos.RowResultDto(r.getRowNumber(), r.getStatus().name(),
                r.getFailingField(), r.getReason() == null ? null : r.getReason().name(),
                r.getExistingCandidateId(), r.getCandidateId()))
            .toList();
        return new CsvImportDtos.JobStatusResponse(job.getId(), job.getStatus().name(), job.getOriginalFilename(),
            job.getTotalRows(), job.getImportedCount(), job.getRejectedCount(), job.getDuplicatePendingCount(),
            job.getMergedCount(), job.getSkippedCount(),
            job.getRejectionReason() == null ? null : job.getRejectionReason().name(),
            rows, job.getCreatedAt(), job.getCompletedAt());
    }

    private static String normalize(String s) {
        return s == null ? null : s.trim().toUpperCase(Locale.ROOT);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }
}
