package com.cadence.ats;

import com.cadence.domain.Candidate;
import com.cadence.domain.CandidateAuditOutcome;
import com.cadence.domain.ErasureState;
import com.cadence.service.CandidateErasureService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F40 FR-015 (the GDPR load-bearing control): an erased candidate is NOT resurrected by a later sync. The
 * resolve-then-guarded-write finds the erased row by the retained external ref and no-ops the update; it never
 * falls through to insert a fresh PII-populated record.
 */
class AtsResurrectionGuardIT extends AtsItBase {

    private static final String WS = "ws-erase";

    @Autowired CandidateErasureService erasure;

    @Test
    void erasedCandidateIsNotResurrectedAndPiiIsNotRewritten() {
        connect(WS);
        stub.addCandidate("gh_app:1", "Jane", "Roe", "jane@example.com", "555-1", "job1", "Engineer", "Phone Screen");
        sync(WS);
        String id = candidates.findAll().get(0).getId();

        boolean wiped = erasure.wipe(WS, id, CandidateAuditOutcome.OPERATOR, "admin1");
        assertThat(wiped).isTrue();

        // The stub still serves the candidate (with a new stage) — a re-poll must NOT resurrect them.
        stub.updateStage("gh_app:1", "Onsite");
        sync(WS);

        assertThat(candidates.findAll()).hasSize(1);           // no new record created
        Candidate c = candidates.findById(id).orElseThrow();
        assertThat(c.getErasureState()).isEqualTo(ErasureState.ERASED);
        assertThat(c.getName()).isEqualTo("[ERASED]");          // PII NOT re-written
        assertThat(c.getEmail()).isEqualTo("[ERASED]");
        // Decision #8 exact field set: PII-adjacent cleared, the non-PII reconcile anchor retained.
        assertThat(c.getAtsStageLabel()).isNull();
        assertThat(c.getAtsExternalJobTitle()).isNull();
        assertThat(c.getAtsExternalRef()).isEqualTo("gh_app:1");
        assertThat(c.getAtsExternalJobId()).isEqualTo("job1");
    }
}
