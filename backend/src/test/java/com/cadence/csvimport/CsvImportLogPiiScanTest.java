package com.cadence.csvimport;

import org.bson.Document;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F42 PII discipline (SC-005/FR-017). A mixed import is driven with high-entropy sentinels embedded in cell
 * values (name, email, and a formula-injection cell). After processing, NONE of the sentinels may appear as
 * plaintext in the persisted {@code csvImportJobs} doc — the per-row results are value-free (row number + field
 * + reason enum + ids only). The candidate's own name/email are encrypted at rest (asserted elsewhere). The
 * ci.yml SENTINELF42* scan is the captured-stdout backstop.
 */
class CsvImportLogPiiScanTest extends CsvImportItBase {

    private static final String NAME = "SENTINELF42NAME_zz9";
    private static final String EMAIL = "sentinelf42email_zz9@example.com";
    private static final String FORMULA = "=SENTINELF42FORMULA_zz9()";

    @Test
    void importJobDoc_carriesNoRawCellValues() {
        // A valid row (sentinel name + a formula-injection name cell — stored verbatim on the candidate,
        // encrypted) plus an invalid row (so a per-row REJECTED result is produced and must stay value-free).
        String csv = "name,email\n"
            + NAME + "," + EMAIL + "\n"
            + FORMULA + ",also-" + EMAIL + "\n"
            + ",missing-name-" + EMAIL + "\n";   // invalid -> REJECTED row result
        String jobId = uploadAndProcess(csv);

        for (Document d : mongoTemplate.getCollection("csvImportJobs").find()) {
            String json = d.toJson();
            assertThat(json).doesNotContain(NAME);
            assertThat(json).doesNotContain(EMAIL);
            assertThat(json).doesNotContain("SENTINELF42FORMULA_zz9");
        }
        // Sanity: the job actually processed (so the assertion is non-vacuous).
        assertThat(job(jobId).getTotalRows()).isGreaterThan(0);
    }
}
