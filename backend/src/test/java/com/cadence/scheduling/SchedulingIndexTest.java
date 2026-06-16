package com.cadence.scheduling;

import com.cadence.BaseIntegrationTest;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T015: ChangeUnit012 created the six F13 indexes — including the UNIQUE PARTIAL claim index over
 * {@code status == ACTIVE} (the cross-request double-booking guard, D3). Asserted via listIndexes against
 * the live (Mongock-migrated) collections. No mocks.
 */
class SchedulingIndexTest extends BaseIntegrationTest {

    @Test
    void schedulingRequests_hasTokenHistoryExpiryStuckIndexes() {
        List<Document> idx = indexes("schedulingRequests");

        Document token = byKey(idx, new Document("tokenHash", 1));
        assertThat(token).as("unique tokenHash index").isNotNull();
        assertThat(token.getBoolean("unique", false)).isTrue();

        assertThat(byKey(idx, new Document("workspaceId", 1).append("candidateId", 1).append("createdAt", -1)))
            .as("per-candidate history index").isNotNull();
        assertThat(byKey(idx, new Document("status", 1).append("expiresAt", 1)))
            .as("reaper expiry index").isNotNull();
        assertThat(byKey(idx, new Document("status", 1).append("updatedAt", 1)))
            .as("reaper stuck-BOOKING index").isNotNull();
    }

    @Test
    void interviewSlotClaims_hasUniquePartialActiveIndex_andReleaseIndex() {
        List<Document> idx = indexes("interviewSlotClaims");

        Document claim = byKey(idx, new Document("workspaceId", 1).append("memberId", 1).append("startAt", 1));
        assertThat(claim).as("claim key index").isNotNull();
        assertThat(claim.getBoolean("unique", false)).as("claim index is unique").isTrue();
        // The partial filter on status==ACTIVE is the load-bearing property — a RELEASED claim leaves the index.
        assertThat(claim.get("partialFilterExpression"))
            .as("claim index is partial on status==ACTIVE")
            .isEqualTo(new Document("status", "ACTIVE"));

        assertThat(byKey(idx, new Document("workspaceId", 1).append("schedulingRequestId", 1)))
            .as("release-set lookup index").isNotNull();
    }

    private List<Document> indexes(String collection) {
        List<Document> out = new ArrayList<>();
        mongoTemplate.getCollection(collection).listIndexes().forEach(out::add);
        return out;
    }

    private static Document byKey(List<Document> indexes, Document key) {
        return indexes.stream().filter(d -> key.equals(d.get("key"))).findFirst().orElse(null);
    }
}
