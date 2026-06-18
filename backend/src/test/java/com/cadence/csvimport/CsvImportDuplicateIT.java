package com.cadence.csvimport;

import com.cadence.api.CsvImportDtos;
import com.cadence.domain.Candidate;
import com.cadence.domain.CandidateAuditOutcome;
import com.cadence.domain.CsvImportJobStatus;
import com.cadence.domain.CsvImportRowStatus;
import com.cadence.domain.LawfulBasis;
import com.cadence.service.CandidateErasureService;
import com.cadence.service.CandidateService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F42 US3: duplicate detection + merge/skip + intra-file dedup + erasure-race + TTL expiry.
 * SC-004 (never silently committed; intra-file collapse), FR-011/012/013/014, SC-015 (TTL expiry).
 */
class CsvImportDuplicateIT extends CsvImportItBase {

    @Autowired CandidateService candidateService;
    @Autowired CandidateErasureService erasureService;

    @Test
    void existingEmail_isFlaggedPending_cleanRowsCommit() {
        candidateService.create(WS, "Existing", "dup@example.com", null, Optional.of(LawfulBasis.CONSENT), ACTOR);
        String jobId = uploadAndProcess("""
            name,email
            New One,new@example.com
            Dup Person,dup@example.com
            """);
        // The clean row committed; the duplicate is pending (not a new candidate).
        assertThat(candidates.findAll()).hasSize(2); // the existing + the 1 new
        assertThat(job(jobId).getStatus()).isEqualTo(CsvImportJobStatus.AWAITING_DUPLICATE_DECISION);
        assertThat(job(jobId).getDuplicatePendingCount()).isEqualTo(1);
        assertThat(job(jobId).getImportedCount()).isEqualTo(1);
    }

    @Test
    void intraFileDuplicate_collapsesToOne() {
        String jobId = uploadAndProcess("""
            name,email
            Ada,ada@example.com
            Ada Again,ada@example.com
            """);
        assertThat(candidates.findAll()).hasSize(1);
        assertThat(job(jobId).getStatus()).isEqualTo(CsvImportJobStatus.COMPLETED);
        assertThat(job(jobId).getImportedCount()).isEqualTo(1);
        assertThat(job(jobId).getSkippedCount()).isEqualTo(1);
    }

    @Test
    void resolveMerge_updatesNonEmptyFieldsOnly_andCompletes() {
        Candidate existing = candidateService.create(WS, "Old Name", "dup@example.com", "+1999",
            Optional.of(LawfulBasis.CONSENT), ACTOR);
        String jobId = uploadAndProcess("""
            name,email,stage,requisition
            New Name,dup@example.com,Onsite,Eng-7
            """);
        int pendingRow = job(jobId).getRowResults().get(0).getRowNumber();

        importService.resolve(WS, "resolver", jobId,
            new CsvImportDtos.ResolveRequest(List.of(new CsvImportDtos.Decision(pendingRow, "MERGE")), null));

        Candidate merged = candidates.findByWorkspaceIdAndId(WS, existing.getId()).orElseThrow();
        assertThat(merged.getName()).isEqualTo("New Name");        // overwritten (non-empty)
        assertThat(merged.getPhone()).isEqualTo("+1999");          // blank cell left existing unchanged
        assertThat(merged.getImportStageLabel()).isEqualTo("Onsite");
        assertThat(merged.getImportRequisitionLabel()).isEqualTo("Eng-7");
        assertThat(merged.getLawfulBasis()).isEqualTo(LawfulBasis.CONSENT); // never touched by merge
        assertThat(job(jobId).getStatus()).isEqualTo(CsvImportJobStatus.COMPLETED);
        assertThat(candidates.findAll()).hasSize(1); // no second record
        assertThat(files.findByJobId(jobId)).isEmpty(); // blob disposed when complete
    }

    @Test
    void resolveSkip_leavesExistingUnchanged() {
        Candidate existing = candidateService.create(WS, "Keep Me", "dup@example.com", null,
            Optional.of(LawfulBasis.CONSENT), ACTOR);
        String jobId = uploadAndProcess("name,email\nOther Name,dup@example.com\n");
        int row = job(jobId).getRowResults().get(0).getRowNumber();
        importService.resolve(WS, "resolver", jobId,
            new CsvImportDtos.ResolveRequest(List.of(new CsvImportDtos.Decision(row, "SKIP")), null));
        assertThat(candidates.findByWorkspaceIdAndId(WS, existing.getId()).orElseThrow().getName())
            .isEqualTo("Keep Me");
        assertThat(job(jobId).getStatus()).isEqualTo(CsvImportJobStatus.COMPLETED);
    }

    @Test
    void mergeRacingErasure_noResurrection() {
        Candidate existing = candidateService.create(WS, "Erase Me", "dup@example.com", null,
            Optional.of(LawfulBasis.CONSENT), ACTOR);
        String jobId = uploadAndProcess("name,email\nNew Name,dup@example.com\n");
        int row = job(jobId).getRowResults().get(0).getRowNumber();
        // Erase between flag and resolve.
        erasureService.wipe(WS, existing.getId(), CandidateAuditOutcome.OPERATOR, ACTOR);

        importService.resolve(WS, "resolver", jobId,
            new CsvImportDtos.ResolveRequest(List.of(new CsvImportDtos.Decision(row, "MERGE")), null));

        Candidate after = candidates.findByWorkspaceIdAndId(WS, existing.getId()).orElseThrow();
        assertThat(after.getName()).isEqualTo("[ERASED]"); // merge no-opped, no resurrection
        assertThat(job(jobId).getRowResults().get(0).getStatus()).isEqualTo(CsvImportRowStatus.SKIPPED);
    }

    @Test
    void unresolvedJob_pastTtl_expiresToSkip_andDisposesBlob() {
        candidateService.create(WS, "Existing", "dup@example.com", null, Optional.of(LawfulBasis.CONSENT), ACTOR);
        String jobId = uploadAndProcess("name,email\nDup,dup@example.com\n");
        assertThat(job(jobId).getStatus()).isEqualTo(CsvImportJobStatus.AWAITING_DUPLICATE_DECISION);

        // Stamp expiresAt into the past (no wall-clock sleep — the F23 lesson) then sweep.
        mongoTemplate.updateFirst(
            org.springframework.data.mongodb.core.query.Query.query(
                org.springframework.data.mongodb.core.query.Criteria.where("_id").is(jobId)),
            new org.springframework.data.mongodb.core.query.Update().set("expiresAt",
                java.time.Instant.now().minusSeconds(3600)),
            com.cadence.domain.CsvImportJob.class);
        importScheduler.sweep();

        assertThat(job(jobId).getStatus()).isEqualTo(CsvImportJobStatus.EXPIRED);
        assertThat(job(jobId).getSkippedCount()).isEqualTo(1);
        assertThat(files.findByJobId(jobId)).isEmpty();
    }
}
