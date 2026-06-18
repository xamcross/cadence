package com.cadence.ats;

import com.cadence.domain.Candidate;
import com.cadence.integration.AtsProvider;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F41 US2 coexistence: with Greenhouse AND Lever connected in one workspace — no double-import across providers
 * (SC-013a), no cross-provider merge on a shared email (SC-013b), Lever burst-50 (SC-002), Lever PII ciphertext
 * (SC-012), and import grants no contact basis (SC-008).
 */
class AtsMultiConnectorIT extends AtsItBase {

    private static final String WS = "ws-multi";

    @Test
    void sameExternalRefInBothProvidersYieldsTwoDistinctRecords() {
        connect(WS);
        connectLever(WS);
        // The SAME external-ref string in both providers must stay two records (provider discriminates).
        stub.addCandidate("app-1", "Greenhouse", "Cand", "gh@example.com", "1", "ghjob", "GH Eng", "Screen");
        leverStub.addOpportunity("app-1", "Lever Cand", "lv@example.com", "2", "lvjob", "LV Eng", "Phone");
        sync(WS);
        syncLever(WS);

        List<Candidate> all = candidates.findAll();
        assertThat(all).hasSize(2); // SC-013a: no double-import; distinct (provider, externalRef)
        assertThat(all).anyMatch(c -> c.getAtsProvider() == AtsProvider.GREENHOUSE && "app-1".equals(c.getAtsExternalRef()))
            .anyMatch(c -> c.getAtsProvider() == AtsProvider.LEVER && "app-1".equals(c.getAtsExternalRef()));

        // Re-sync both -> still exactly two (idempotent, no extra rows).
        sync(WS);
        syncLever(WS);
        assertThat(candidates.findAll()).hasSize(2);
    }

    @Test
    void sharedEmailAcrossProvidersIsNotMerged() {
        connect(WS);
        connectLever(WS);
        stub.addCandidate("gh-1", "Shared", "Person", "shared@example.com", "1", "ghjob", "GH Eng", "Screen");
        sync(WS);
        leverStub.addOpportunity("lv-1", "Shared Person", "shared@example.com", "2", "lvjob", "LV Eng", "Phone");
        syncLever(WS);

        List<Candidate> all = candidates.findAll();
        assertThat(all).hasSize(2); // SC-013b: a record keyed to GREENHOUSE is never adopted by the LEVER sync
        assertThat(all).filteredOn(c -> c.getAtsProvider() == AtsProvider.LEVER).hasSize(1);
        assertThat(all).filteredOn(c -> c.getAtsProvider() == AtsProvider.GREENHOUSE).hasSize(1);
    }

    @Test
    void leverBurstOfFiftyImportedExactlyOnce() {
        connectLever(WS);
        for (int i = 1; i <= 50; i++) {
            leverStub.addOpportunity("lv-" + i, "Cand N" + i, "c" + i + "@example.com", "p" + i,
                "lvjob", "LV Eng", "Phone Screen");
        }
        syncLever(WS);
        assertThat(candidates.findAll()).hasSize(50); // SC-002
        Document run = mongoTemplate.getCollection("atsSyncRuns").find().first();
        assertThat(run).isNotNull();
        assertThat(run.getString("provider")).isEqualTo("LEVER");
        assertThat(run.getInteger("created")).isEqualTo(50);
    }

    @Test
    void leverCandidatePiiIsCiphertextAndHasNoContactBasis() {
        connectLever(WS);
        leverStub.addOpportunity("lv-1", "Jane Roe", "jane@example.com", "555-1", "lvjob", "LV Eng", "Phone Screen");
        syncLever(WS);

        Candidate c = candidates.findAll().get(0);
        assertThat(c.getAtsProvider()).isEqualTo(AtsProvider.LEVER);
        assertThat(c.getAtsStageLabel()).isEqualTo("Phone Screen");
        assertThat(c.getName()).isEqualTo("Jane Roe"); // decrypted on read
        // SC-008: an imported candidate has no lawful basis to be emailed until consent is recorded.
        assertThat(c.getLawfulBasis()).isNull();

        // SC-012: name/email/phone/stage are ciphertext at rest.
        Document raw = mongoTemplate.getCollection("candidates").find().first();
        assertThat(raw).isNotNull();
        assertThat(raw.getString("name")).isNotEqualTo("Jane Roe");
        assertThat(raw.getString("email")).isNotEqualTo("jane@example.com");
        assertThat(raw.getString("atsStageLabel")).isNotEqualTo("Phone Screen");
        // FR-029 minimization: no Lever links/tags/sources/origin/headline/archived leaked.
        assertThat(raw.toJson()).doesNotContain("SENTINEL");
    }
}
