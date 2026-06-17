package com.cadence.status;

import com.cadence.BaseIntegrationTest;
import com.cadence.config.migration.ChangeUnit015_CandidateStatusIndexes;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.query.Query;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * F30 T036 (the dedupe leg of ChangeUnit015): a pre-F30 workspace can hold ≥2 PENDING erasureRequests for one
 * candidate (the pre-F30 {@code requestErasure} {@code save()}s unconditionally). Re-running the changeset's
 * {@code @Execution} must dedupe FIRST (keep the earliest, reject the rest) so the unique partial index builds
 * cleanly instead of aborting startup. We drop the live index (Mongock already applied it), seed 2 PENDING
 * dupes, re-run execute(), and assert it builds + leaves exactly one PENDING.
 */
class ChangeUnit015DedupeTest extends BaseIntegrationTest {

    @AfterEach
    void cleanup() {
        mongoTemplate.remove(new Query(), com.cadence.domain.ErasureRequest.class);
        // Re-establish a clean state for the unique index (re-running execute is idempotent on createIndex).
        new ChangeUnit015_CandidateStatusIndexes().execute(mongoTemplate);
    }

    @Test
    void dedupeThenBuildsUniqueIndexCleanly_whenTwoPendingDuplicatesExist() {
        var requests = mongoTemplate.getCollection("erasureRequests");
        // Drop the live unique index so we can insert two PENDING duplicates (the pre-F30 state).
        try {
            requests.dropIndex(new Document("workspaceId", 1).append("candidateId", 1));
        } catch (RuntimeException ignored) {
            // index may not exist in some run orderings — fine.
        }
        requests.insertOne(pending("dup-ws", "dup-cand", new Date(1000L)));
        requests.insertOne(pending("dup-ws", "dup-cand", new Date(2000L))); // the later duplicate
        requests.insertOne(pending("dup-ws", "other-cand", new Date(1500L)));

        // Re-running the changeset must dedupe FIRST, then build the unique partial index without error.
        assertThatCode(() -> new ChangeUnit015_CandidateStatusIndexes().execute(mongoTemplate))
            .doesNotThrowAnyException();

        long pendingForDup = requests.countDocuments(
            new Document("workspaceId", "dup-ws").append("candidateId", "dup-cand").append("status", "PENDING"));
        assertThat(pendingForDup).as("exactly one PENDING survivor after dedupe").isEqualTo(1L);

        // The unique partial index now exists.
        List<Document> idx = new ArrayList<>();
        requests.listIndexes().forEach(idx::add);
        Document built = idx.stream()
            .filter(d -> new Document("workspaceId", 1).append("candidateId", 1).equals(d.get("key")))
            .findFirst().orElse(null);
        assertThat(built).isNotNull();
        assertThat(built.getBoolean("unique", false)).isTrue();
    }

    private static Document pending(String ws, String cand, Date createdAt) {
        return new Document("workspaceId", ws).append("candidateId", cand)
            .append("status", "PENDING").append("reasonCode", "CANDIDATE_REQUEST").append("createdAt", createdAt);
    }
}
