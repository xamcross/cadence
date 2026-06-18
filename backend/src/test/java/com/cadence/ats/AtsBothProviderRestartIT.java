package com.cadence.ats;

import com.cadence.domain.AtsWriteBackType;
import com.cadence.integration.AtsProvider;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F41 SC-007: a mid-cycle restart while BOTH providers have in-flight work produces no duplicate imports and no
 * duplicate / cross-provider timeline activities.
 *
 * <p><b>Honest bound (the F31/F32 precedent)</b>: this is a double-sweep idempotency proxy for a process
 * restart, not a true kill-9 — re-invoking the sync + drain exercises the same per-row CAS / unique-index
 * guards that a restart-replay would, which is what makes a restart safe.
 */
class AtsBothProviderRestartIT extends AtsItBase {

    private static final String WS = "ws-restart";
    private static final Instant EVENT_AT = Instant.parse("2026-07-01T10:00:00Z");

    @Test
    void bothProvidersRestartProducesNoDuplicates() {
        connect(WS);
        connectLever(WS);
        stub.addCandidate("gh-1", "GH", "Cand", "gh@example.com", "1", "ghjob", "GH Eng", "Screen");
        leverStub.addOpportunity("lv-1", "LV Cand", "lv@example.com", "2", "lvjob", "LV Eng", "Phone");

        // First pass: import + enqueue + deliver for both providers.
        sync(WS);
        syncLever(WS);
        String ghId = candidates.findByWorkspaceIdAndAtsProviderAndAtsExternalRef(WS, AtsProvider.GREENHOUSE, "gh-1").orElseThrow().getId();
        String lvId = candidates.findByWorkspaceIdAndAtsProviderAndAtsExternalRef(WS, AtsProvider.LEVER, "lv-1").orElseThrow().getId();
        writeBackService.enqueue(WS, ghId, AtsWriteBackType.CONFIRMED, EVENT_AT);
        writeBackService.enqueue(WS, lvId, AtsWriteBackType.CONFIRMED, EVENT_AT);
        writeBackScheduler.drain();

        // "Restart": replay the sync + drain for both providers.
        sync(WS);
        syncLever(WS);
        writeBackScheduler.drain();

        // No duplicate imports, exactly one note per provider, no cross-provider note.
        assertThat(candidates.findAll()).hasSize(2);
        assertThat(stub.notes("gh-1")).hasSize(1);
        assertThat(leverStub.notes("lv-1")).hasSize(1);
        assertThat(stub.notes("lv-1")).isEmpty();
        assertThat(leverStub.notes("gh-1")).isEmpty();
    }
}
