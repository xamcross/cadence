package com.cadence.ats;

import com.cadence.domain.Candidate;
import com.cadence.domain.WorkspaceEntitlement;
import com.cadence.integration.AtsProvider;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** F40 US2 inbound sync: import, stage update, reconcile precedence, burst, minimization, PII-at-rest. */
class AtsSyncIT extends AtsItBase {

    private static final String WS = "ws-sync";

    @Test
    void importsCandidateThenUpdatesStageWithoutDuplicate() {
        connect(WS);
        stub.addCandidate("gh_app:1", "Jane", "Roe", "jane@example.com", "555-1", "job1", "Engineer", "Phone Screen");
        sync(WS);

        List<Candidate> all = candidates.findAll();
        assertThat(all).hasSize(1);
        Candidate c = all.get(0);
        assertThat(c.getAtsProvider()).isEqualTo(AtsProvider.GREENHOUSE);
        assertThat(c.getAtsExternalRef()).isEqualTo("gh_app:1");
        assertThat(c.getAtsExternalJobId()).isEqualTo("job1");
        assertThat(c.getAtsExternalJobTitle()).isEqualTo("Engineer");
        assertThat(c.getName()).isEqualTo("Jane Roe");        // decrypted on read
        assertThat(c.getEmail()).isEqualTo("jane@example.com");
        assertThat(c.getAtsStageLabel()).isEqualTo("Phone Screen");
        // SC-008: an imported candidate has no lawful basis -> cannot be emailed until consent recorded.
        assertThat(c.getLawfulBasis()).isNull();

        stub.updateStage("gh_app:1", "Onsite");
        sync(WS);
        List<Candidate> after = candidates.findAll();
        assertThat(after).hasSize(1);                          // no duplicate
        assertThat(after.get(0).getAtsStageLabel()).isEqualTo("Onsite");
    }

    @Test
    void distinctExternalRefsSharingAnEmailAreNotMerged() {
        connect(WS);
        stub.addCandidate("gh_app:1", "Sam", "One", "shared@example.com", "1", "job1", "Eng", "Screen");
        stub.addCandidate("gh_app:2", "Sam", "Two", "shared@example.com", "2", "job2", "Eng", "Screen");
        sync(WS);
        assertThat(candidates.findAll()).hasSize(2); // external ref is authoritative; email never merges
    }

    @Test
    void minimizationDoesNotImportAttachmentsOrEeoc() {
        connect(WS);
        stub.addCandidate("gh_app:1", "Jane", "Roe", "jane@example.com", "555-1", "job1", "Engineer", "Phone Screen");
        sync(WS);
        // FR-029 non-circular: the stub seeds attachments/custom_fields/eeoc/tags with SENTINEL markers; assert
        // none reached the stored candidate document.
        Document raw = mongoTemplate.getCollection("candidates").find().first();
        assertThat(raw).isNotNull();
        assertThat(raw.toJson()).doesNotContain("SENTINEL");
        assertThat(raw).doesNotContainKeys("attachments", "custom_fields", "eeoc", "tags");
    }

    @Test
    void piiAndStageLabelAndCredentialAreCiphertextAtRest() {
        connect(WS);
        stub.addCandidate("gh_app:1", "Jane", "Roe", "jane@example.com", "555-1", "job1", "Engineer", "Phone Screen");
        sync(WS);
        // SC-012: name/email/phone/stage label ciphertext.
        Document raw = mongoTemplate.getCollection("candidates").find().first();
        assertThat(raw).isNotNull();
        assertThat(raw.getString("name")).isNotEqualTo("Jane Roe");
        assertThat(raw.getString("email")).isNotEqualTo("jane@example.com");
        assertThat(raw.getString("atsStageLabel")).isNotEqualTo("Phone Screen");
        // SC-006: the API key ciphertext (never the plaintext "test-key-...").
        Document conn = mongoTemplate.getCollection("atsConnections").find().first();
        assertThat(conn).isNotNull();
        assertThat(conn.getString("apiKey")).isNotEqualTo("test-key-" + WS);
        assertThat(conn.getString("apiKey")).isNotBlank();
    }

    @Test
    void burstOfFiftyImportedExactlyOnce() {
        connect(WS);
        for (int i = 1; i <= 50; i++) {
            stub.addCandidate("gh_app:" + i, "Cand", "N" + i, "c" + i + "@example.com", "p" + i,
                "job1", "Engineer", "Phone Screen");
        }
        sync(WS);
        assertThat(candidates.findAll()).hasSize(50);
        var run = mongoTemplate.getCollection("atsSyncRuns").find().first();
        assertThat(run).isNotNull();
        assertThat(run.getInteger("processed")).isEqualTo(50);
        assertThat(run.getInteger("created")).isEqualTo(50);
        assertThat(run.getString("outcome")).isEqualTo("SUCCESS");
    }

    /**
     * 032 T7 placement 3: a Team->Free downgrade leaves the connection CONNECTED (disconnect is a separate,
     * ungated, user action) but {@link com.cadence.scheduler.AtsSyncScheduler#sweep()} must skip it at
     * initiation -- zero requests reach the provider. Re-entitling makes the very next sweep pass sync normally.
     */
    @Test
    void sweepSkipsNonEntitledWorkspace_thenSyncsOnceReEntitled() {
        connect(WS); // seeds Team (F40 gate 1) + CONNECTED
        mongoTemplate.remove(Query.query(Criteria.where("workspaceId").is(WS)), WorkspaceEntitlement.class);
        stub.addCandidate("gh_app:1", "Jane", "Roe", "jane@example.com", "555-1", "job1", "Engineer", "Phone Screen");

        syncScheduler.sweep();

        assertThat(stub.count("GET", "/v1/candidates")).isZero();
        assertThat(candidates.findAll()).isEmpty();

        seedTeamEntitlement(WS);
        syncScheduler.sweep();

        assertThat(stub.count("GET", "/v1/candidates")).isEqualTo(1);
        assertThat(candidates.findAll()).hasSize(1);
    }
}
