package com.cadence.ats;

import com.cadence.domain.AtsWriteBackStatus;
import com.cadence.domain.AtsWriteBackType;
import com.cadence.domain.Candidate;
import com.cadence.domain.CandidateAuditOutcome;
import com.cadence.domain.ErasureState;
import com.cadence.integration.AtsProvider;
import com.cadence.service.CandidateErasureService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F41 US4 SC-015: erasing a Lever candidate wipes PII / retains the reconcile anchor / makes a re-poll a guarded
 * no-op (no resurrection); and disconnecting Lever cancels ONLY Lever's pending write-backs while a coexisting
 * Greenhouse queue is untouched.
 */
class AtsLeverErasureIT extends AtsItBase {

    private static final String WS = "ws-lever-erase";
    private static final Instant EVENT_AT = Instant.parse("2026-07-01T10:00:00Z");

    @Autowired CandidateErasureService erasure;

    @Test
    void erasedLeverCandidateIsNotResurrected() {
        connectLever(WS);
        leverStub.addOpportunity("lv-1", "Jane Roe", "jane@example.com", "555-1", "lvjob", "LV Eng", "Phone");
        syncLever(WS);
        String id = candidates.findAll().get(0).getId();

        assertThat(erasure.wipe(WS, id, CandidateAuditOutcome.OPERATOR, "admin1")).isTrue();

        leverStub.updateStage("lv-1", "Onsite");
        syncLever(WS);

        assertThat(candidates.findAll()).hasSize(1); // no resurrection
        Candidate c = candidates.findById(id).orElseThrow();
        assertThat(c.getErasureState()).isEqualTo(ErasureState.ERASED);
        assertThat(c.getName()).isEqualTo("[ERASED]");
        assertThat(c.getAtsStageLabel()).isNull();              // PII-adjacent wiped
        assertThat(c.getAtsExternalRef()).isEqualTo("lv-1");    // anchor retained
        assertThat(c.getAtsProvider()).isEqualTo(AtsProvider.LEVER);
    }

    @Test
    void disconnectLeverCancelsOnlyLeverPendingWriteBacks() {
        connect(WS);
        connectLever(WS);
        // A pending write-back for each provider's candidate.
        String ghId = importGreenhouse("gh-1");
        String lvId = importLever("lv-1");
        writeBackService.enqueue(WS, ghId, AtsWriteBackType.CONFIRMED, EVENT_AT);
        writeBackService.enqueue(WS, lvId, AtsWriteBackType.CONFIRMED, EVENT_AT);

        connectionService.disconnect(WS, AtsProvider.LEVER);

        // Lever's pending row is CANCELLED; Greenhouse's pending row is untouched (still PENDING).
        var leverRow = writeBacks.findAll().stream().filter(w -> w.getProvider() == AtsProvider.LEVER).findFirst().orElseThrow();
        var ghRow = writeBacks.findAll().stream().filter(w -> w.getProvider() == AtsProvider.GREENHOUSE).findFirst().orElseThrow();
        assertThat(leverRow.getStatus()).isEqualTo(AtsWriteBackStatus.CANCELLED);
        assertThat(ghRow.getStatus()).isEqualTo(AtsWriteBackStatus.PENDING);
    }

    private String importGreenhouse(String ref) {
        stub.addCandidate(ref, "GH", "Cand", ref + "@example.com", "1", "ghjob", "GH Eng", "Screen");
        sync(WS);
        return candidates.findByWorkspaceIdAndAtsProviderAndAtsExternalRef(WS, AtsProvider.GREENHOUSE, ref)
            .orElseThrow().getId();
    }

    private String importLever(String ref) {
        leverStub.addOpportunity(ref, "LV Cand", ref + "@example.com", "2", "lvjob", "LV Eng", "Phone");
        syncLever(WS);
        return candidates.findByWorkspaceIdAndAtsProviderAndAtsExternalRef(WS, AtsProvider.LEVER, ref)
            .orElseThrow().getId();
    }
}
