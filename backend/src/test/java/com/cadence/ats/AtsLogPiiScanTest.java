package com.cadence.ats;

import com.cadence.domain.AtsWriteBackType;
import com.cadence.integration.AtsProvider;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F41 SC-005 (closes the F40 residual): drive connect (with a sentinel API key) + sync (with a sentinel
 * candidate name) + write-back + a failure, and assert neither the candidate name nor the Lever credential
 * appears in plaintext at rest (atsConnections / candidates / atsSyncRuns / atsWriteBacks). The CI stdout grep
 * (the SENTINELF41* block in ci.yml) is the captured-output backstop.
 */
class AtsLogPiiScanTest extends AtsItBase {

    private static final String WS = "ws-pii";
    private static final String NAME_SENTINEL = "SENTINELF41NAME_zz9";
    private static final String KEY_SENTINEL = "SENTINELF41KEY_zz9";
    private static final Instant EVENT_AT = Instant.parse("2026-07-01T10:00:00Z");

    @Test
    void noLeverCandidateNameOrCredentialAtRest() {
        seedTeamEntitlement(WS); // 032 T7: connect() gates on ATS_INTEGRATIONS before the credential check
        // Connect with a sentinel key (verify -> 200), then import a candidate whose name is a sentinel.
        connectionService.connect(WS, AtsProvider.LEVER, KEY_SENTINEL);
        leverStub.addOpportunity("lv-1", NAME_SENTINEL, "jane@example.com", "555-1", "lvjob", "LV Eng", "Phone Screen");
        syncLever(WS);

        String id = candidates.findAll().get(0).getId();
        writeBackService.enqueue(WS, id, AtsWriteBackType.CONFIRMED, EVENT_AT);
        writeBackScheduler.drain();

        // Drive a sync FAILURE (provider rejects the key) -> the error is reduced to a category, never the body/key.
        leverStub.program("GET", "/v1/opportunities", 401);
        syncLever(WS);

        // The credential is ciphertext, never the sentinel; the raw provider error BODY is never persisted.
        Document conn = mongoTemplate.getCollection("atsConnections").find().first();
        assertThat(conn).isNotNull();
        assertThat(conn.getString("apiKey")).isNotEqualTo(KEY_SENTINEL);
        assertThat(conn.toJson()).doesNotContain(KEY_SENTINEL).doesNotContain("SENTINELF41BODY_zz9");
        assertThat(conn.getString("lastErrorCategory")).isIn("auth", null); // a category, never the body/key

        // The candidate name is ciphertext, never the sentinel; the sync-run/write-back rows carry no name.
        Document cand = mongoTemplate.getCollection("candidates").find().first();
        assertThat(cand).isNotNull();
        assertThat(cand.toJson()).doesNotContain(NAME_SENTINEL);
        for (Document run : mongoTemplate.getCollection("atsSyncRuns").find()) {
            assertThat(run.toJson()).doesNotContain(NAME_SENTINEL).doesNotContain(KEY_SENTINEL);
        }
        for (Document wb : mongoTemplate.getCollection("atsWriteBacks").find()) {
            assertThat(wb.toJson()).doesNotContain(NAME_SENTINEL).doesNotContain(KEY_SENTINEL);
        }
    }
}
