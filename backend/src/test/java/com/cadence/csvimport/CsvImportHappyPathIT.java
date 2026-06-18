package com.cadence.csvimport;

import com.cadence.domain.Candidate;
import com.cadence.domain.CandidateOrigin;
import com.cadence.domain.CsvImportJobStatus;
import com.cadence.integration.AtsProvider;
import com.cadence.service.ContactPermissionGate;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F42 US1: a clean CSV imports end-to-end. SC-001 (candidates appear), SC-007 (PII ciphertext at rest), SC-008
 * (consent fail-closed -> NO_BASIS), SC-012 (lifecycle parity), SC-014 (CSV provenance, never ATS-matched),
 * US1-2 (stage/requisition labels imported verbatim), FR-021a disposal.
 */
class CsvImportHappyPathIT extends CsvImportItBase {

    @Autowired ContactPermissionGate gate;

    @Test
    void cleanCsv_importsEveryRow_withProvenanceLabelsAndDisposal() {
        String csv = """
            name,email,stage,requisition,phone
            Ada Lovelace,ada@example.com,Phone Screen,Backend Eng,+15550100
            Alan Turing,alan@example.com,Onsite,Backend Eng,
            """;
        String jobId = uploadAndProcess(csv);

        List<Candidate> all = candidates.findAll();
        assertThat(all).hasSize(2);
        assertThat(job(jobId).getStatus()).isEqualTo(CsvImportJobStatus.COMPLETED);
        assertThat(job(jobId).getImportedCount()).isEqualTo(2);

        Candidate ada = all.stream().filter(c -> "ada@example.com".equals(c.getEmail())).findFirst().orElseThrow();
        assertThat(ada.getOrigin()).isEqualTo(CandidateOrigin.CSV_IMPORT);
        assertThat(ada.getImportJobId()).isEqualTo(jobId);
        // US1-2: stage/requisition imported verbatim.
        assertThat(ada.getImportStageLabel()).isEqualTo("Phone Screen");
        assertThat(ada.getImportRequisitionLabel()).isEqualTo("Backend Eng");

        // FR-021a: the raw blob is disposed on COMPLETED.
        assertThat(files.findByJobId(jobId)).isEmpty();
        assertThat(job(jobId).getFileId()).isNull();

        // SC-014: a CSV candidate has no atsProvider, so the ATS reconcile lookup can never match it.
        assertThat(candidates.findByWorkspaceIdAndAtsProviderAndAtsExternalRef(WS, AtsProvider.GREENHOUSE, "x"))
            .isEmpty();
        assertThat(ada.getAtsProvider()).isNull();
    }

    @Test
    void importedCandidate_isConsentGatedUntilBasisRecorded() {
        uploadAndProcess("name,email\nGrace Hopper,grace@example.com\n");
        Candidate c = candidates.findAll().get(0);
        assertThat(c.getLawfulBasis()).isNull();
        ContactPermissionGate.Decision d = gate.evaluate(WS, c.getId());
        assertThat(d.permit()).isFalse();
        assertThat(d.reason()).isEqualTo(ContactPermissionGate.Reason.NO_BASIS);
    }

    @Test
    void formulaAndSignLedCells_areStoredVerbatim_notMutated() {
        // FR-018/SC-006: cells are stored VERBATIM at ingestion (neutralization is export-only). A legitimate
        // +-led phone / --led name must NOT be corrupted with a leading quote.
        uploadAndProcess("name,email,phone\n-Bob,bob@example.com,+15550100\n");
        Candidate c = candidates.findAll().get(0);
        assertThat(c.getName()).isEqualTo("-Bob");      // not "'-Bob"
        assertThat(c.getPhone()).isEqualTo("+15550100"); // not "'+15550100"
    }

    @Test
    void importedCandidate_pii_isCiphertextAtRest() {
        uploadAndProcess("name,email,phone\nMarie Curie,marie@example.com,+15550111\n");
        Document raw = mongoTemplate.getCollection("candidates").find().first();
        assertThat(raw).isNotNull();
        assertThat(raw.getString("name")).isNotEqualTo("Marie Curie");
        assertThat(raw.getString("email")).isNotEqualTo("marie@example.com");
        assertThat(raw.getString("phone")).isNotEqualTo("+15550111");
        // emailHash IS stored as-is (keyed hash, not encrypted) so dedup can query it.
        assertThat(raw.getString("emailHash")).isNotBlank();
    }
}
