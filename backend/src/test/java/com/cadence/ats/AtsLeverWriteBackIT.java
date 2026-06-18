package com.cadence.ats;

import com.cadence.domain.AtsWriteBack;
import com.cadence.domain.AtsWriteBackStatus;
import com.cadence.domain.AtsWriteBackType;
import com.cadence.integration.AtsProvider;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F41 US3 write-back routing: a Lever candidate's activity reaches ONLY the Lever timeline, a Greenhouse
 * candidate's ONLY Greenhouse (SC-013c), and two providers' write-backs with otherwise-identical params are two
 * distinct rows routed correctly (idempotency-key cross-provider non-collision).
 */
class AtsLeverWriteBackIT extends AtsItBase {

    private static final String WS = "ws-lever-wb";
    private static final Instant EVENT_AT = Instant.parse("2026-07-01T10:00:00Z");

    private String importGreenhouse(String ref) {
        connect(WS);
        stub.addCandidate(ref, "GH", "Cand", ref + "@example.com", "1", "ghjob", "GH Eng", "Screen");
        sync(WS);
        return candidates.findByWorkspaceIdAndAtsProviderAndAtsExternalRef(WS, AtsProvider.GREENHOUSE, ref)
            .orElseThrow().getId();
    }

    private String importLever(String ref) {
        connectLever(WS);
        leverStub.addOpportunity(ref, "LV Cand", ref + "@example.com", "2", "lvjob", "LV Eng", "Phone");
        syncLever(WS);
        return candidates.findByWorkspaceIdAndAtsProviderAndAtsExternalRef(WS, AtsProvider.LEVER, ref)
            .orElseThrow().getId();
    }

    @Test
    void leverWriteBackReachesLeverOnly() {
        String leverId = importLever("lv-1");
        writeBackService.enqueue(WS, leverId, AtsWriteBackType.CONFIRMED, EVENT_AT);
        writeBackScheduler.drain();

        assertThat(leverStub.notes("lv-1")).hasSize(1);          // delivered to Lever
        assertThat(stub.count("POST", "/notes")).isZero();        // never POSTed to Greenhouse
        AtsWriteBack row = writeBacks.findAll().get(0);
        assertThat(row.getProvider()).isEqualTo(AtsProvider.LEVER);
        assertThat(row.getStatus()).isEqualTo(AtsWriteBackStatus.DELIVERED);
    }

    @Test
    void nativeLeverlessCandidateEnqueuesNothing() {
        // SC-013c: a candidate with no ATS link produces no write-back (provider-agnostic enqueue guard).
        connectLever(WS);
        com.cadence.domain.Candidate native_ = new com.cadence.domain.Candidate();
        native_.setWorkspaceId(WS);
        native_.setErasureState(com.cadence.domain.ErasureState.ACTIVE);
        native_.setCreatedAt(Instant.now());
        String id = candidates.insert(native_).getId();
        writeBackService.enqueue(WS, id, AtsWriteBackType.CONFIRMED, EVENT_AT);
        assertThat(writeBacks.findAll()).isEmpty();
    }

    @Test
    void leverDeadLetterSurfacesOnPerProviderHealth() {
        // SC-011: a Lever write-back that fails fatally dead-letters and surfaces on the Lever health card.
        String leverId = importLever("lv-1");
        leverStub.program("POST", "/notes", 400); // FATAL -> immediate dead-letter
        writeBackService.enqueue(WS, leverId, AtsWriteBackType.CONFIRMED, EVENT_AT);
        writeBackScheduler.drain();
        assertThat(writeBacks.findAll().get(0).getStatus()).isEqualTo(AtsWriteBackStatus.DEAD_LETTER);
        var health = connectionService.health(WS, AtsProvider.LEVER);
        assertThat(health.deadLetterCount()).isEqualTo(1);
        assertThat(health.degraded()).isTrue();
    }

    @Test
    void twoProvidersIdenticalParamsAreDistinctRowsRoutedCorrectly() {
        String ghId = importGreenhouse("gh-1");
        String lvId = importLever("lv-1");
        // Same type + eventAt for both candidates -> distinct idempotency keys (distinct candidateId).
        writeBackService.enqueue(WS, ghId, AtsWriteBackType.CONFIRMED, EVENT_AT);
        writeBackService.enqueue(WS, lvId, AtsWriteBackType.CONFIRMED, EVENT_AT);
        assertThat(writeBacks.findAll()).hasSize(2);

        writeBackScheduler.drain();
        writeBackScheduler.drain();

        // Greenhouse note went to Greenhouse, Lever note to Lever — no cross-provider mis-route (SC-013c).
        List<String> ghNotes = stub.notes(
            // the Greenhouse stub keys notes by the application id it assigns == the external ref
            "gh-1");
        assertThat(ghNotes).hasSize(1);
        assertThat(leverStub.notes("lv-1")).hasSize(1);
        assertThat(stub.notes("lv-1")).isEmpty();
        assertThat(leverStub.notes("gh-1")).isEmpty();
        assertThat(writeBacks.findAll()).allMatch(w -> w.getStatus() == AtsWriteBackStatus.DELIVERED);
    }
}
