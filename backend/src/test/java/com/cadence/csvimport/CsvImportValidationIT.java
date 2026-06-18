package com.cadence.csvimport;

import com.cadence.api.CsvImportDtos;
import com.cadence.domain.CsvImportJobStatus;
import com.cadence.domain.CsvImportRowStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F42 US2: per-row validation + whole-file reject + status surface. SC-003 (100% valid imported, every invalid
 * reported; >80% commits zero), FR-008/FR-009/FR-010, US2-5 (status carries counts + per-row lists).
 */
class CsvImportValidationIT extends CsvImportItBase {

    @Test
    void mixedFile_importsValid_reportsEachInvalid_withStatusContract() {
        String csv = """
            name,email,stage,requisition,phone
            Ada Lovelace,ada@example.com,Onsite,Eng,
            ,missing-name@example.com,,,
            Bad Email,not-an-email,,,
            Alan Turing,alan@example.com,,,
            """;
        String jobId = uploadAndProcess(csv);

        assertThat(candidates.findAll()).hasSize(2); // only the 2 valid rows
        CsvImportDtos.JobStatusResponse s = importService.status(WS, jobId);
        assertThat(s.status()).isEqualTo(CsvImportJobStatus.COMPLETED.name());
        assertThat(s.importedCount()).isEqualTo(2);
        assertThat(s.rejectedCount()).isEqualTo(2);
        // US2-5: per-row error list with row number + field + value-free reason.
        assertThat(s.rowResults()).anySatisfy(r -> {
            assertThat(r.status()).isEqualTo(CsvImportRowStatus.REJECTED.name());
            assertThat(r.reason()).isIn("MISSING_REQUIRED", "INVALID_EMAIL");
            assertThat(r.failingField()).isIn("name", "email");
        });
    }

    @Test
    void overEightyPercentInvalid_rejectsWholeFile_zeroCommitted() {
        // 5 data rows, 5 invalid (100%) -> reject.
        String csv = """
            name,email
            ,a@example.com
            ,b@example.com
            Bad,nope
            Bad2,nope2
            ,c@example.com
            """;
        String jobId = uploadAndProcess(csv);
        assertThat(candidates.findAll()).isEmpty();
        assertThat(job(jobId).getStatus()).isEqualTo(CsvImportJobStatus.REJECTED);
        assertThat(job(jobId).getRejectionReason().name()).isEqualTo("TOO_MANY_INVALID");
        assertThat(files.findByJobId(jobId)).isEmpty(); // blob disposed on reject
    }

    @Test
    void exactlyEightyPercentInvalid_commitsTheOneValidRow() {
        // 5 data rows, 4 invalid = exactly 80% -> NOT rejected (boundary: > 0.80 rejects).
        String csv = """
            name,email
            Valid One,valid@example.com
            ,b@example.com
            Bad,nope
            ,c@example.com
            Bad2,nope2
            """;
        String jobId = uploadAndProcess(csv);
        assertThat(candidates.findAll()).hasSize(1);
        assertThat(job(jobId).getStatus()).isEqualTo(CsvImportJobStatus.COMPLETED);
        assertThat(job(jobId).getImportedCount()).isEqualTo(1);
    }

    @Test
    void missingRequiredHeader_rejectsSchemaInvalid() {
        String jobId = uploadAndProcess("fullname,mail\nAda,ada@example.com\n");
        assertThat(candidates.findAll()).isEmpty();
        assertThat(job(jobId).getStatus()).isEqualTo(CsvImportJobStatus.REJECTED);
        assertThat(job(jobId).getRejectionReason().name()).isEqualTo("SCHEMA_INVALID");
        assertThat(files.findByJobId(jobId)).isEmpty();
    }

    @Test
    void overRowCount_rejectsOverLimit() {
        // test profile cap is 200 rows.
        StringBuilder sb = new StringBuilder("name,email\n");
        for (int i = 0; i < 201; i++) {
            sb.append("Person ").append(i).append(",p").append(i).append("@example.com\n");
        }
        String jobId = uploadAndProcess(sb.toString());
        assertThat(candidates.findAll()).isEmpty();
        assertThat(job(jobId).getStatus()).isEqualTo(CsvImportJobStatus.REJECTED);
        assertThat(job(jobId).getRejectionReason().name()).isEqualTo("OVER_LIMIT");
    }

    @Test
    void emptyFileOfDataRows_completesWithZeroImported() {
        String jobId = uploadAndProcess("name,email\n");
        assertThat(candidates.findAll()).isEmpty();
        assertThat(job(jobId).getStatus()).isEqualTo(CsvImportJobStatus.COMPLETED);
        assertThat(job(jobId).getRejectionReason()).isNull();
    }
}
