package com.cadence.gdpr;

import com.cadence.BaseIntegrationTest;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** T020: ChangeUnit005 created the new GDPR indexes; the ChangeUnit001 indexes are present (not recreated). */
class GdprIndexBootstrapTest extends BaseIntegrationTest {

    @Test
    void newAndPreExistingIndexesArePresent() {
        assertHasKey("candidates", new Document("workspaceId", 1).append("emailHash", 1));   // ChangeUnit005
        assertHasKey("erasureRequests", new Document("workspaceId", 1).append("status", 1)); // ChangeUnit005
        assertHasKey("candidates", new Document("workspaceId", 1).append("lastContactAt", 1)); // ChangeUnit001
        assertHasKey("auditLog", new Document("candidateId", 1).append("occurredAt", -1));    // ChangeUnit001
    }

    private void assertHasKey(String collection, Document expectedKey) {
        boolean found = false;
        for (Document idx : mongoTemplate.getCollection(collection).listIndexes()) {
            if (expectedKey.equals(idx.get("key"))) {
                found = true;
                break;
            }
        }
        assertThat(found).withFailMessage("index %s missing on %s", expectedKey, collection).isTrue();
    }
}
