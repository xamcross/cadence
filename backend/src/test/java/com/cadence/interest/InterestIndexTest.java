package com.cadence.interest;

import com.cadence.BaseIntegrationTest;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T006/SC-007: the four ChangeUnit023 indexes bootstrap with the right keys/partial filter/uniqueness on
 * {@code interestRequests}. Asserts against the live collection index metadata (the F31 InterestIndexTest precedent).
 */
class InterestIndexTest extends BaseIntegrationTest {

    @Test
    void changeUnit023_createsTheFourInterestIndexes() {
        Map<String, Document> byKey = new HashMap<>();
        for (Document idx : mongoTemplate.getCollection("interestRequests").listIndexes()) {
            byKey.put(((Document) idx.get("key")).toJson(), idx);
        }

        // 1) unique partial {workspaceId, openEmailHash} over {openEmailHash:{$exists:true}}.
        Document openKey = new Document("workspaceId", 1).append("openEmailHash", 1);
        Document open = byKey.get(openKey.toJson());
        assertThat(open).as("unique partial open-email index").isNotNull();
        assertThat(open.getBoolean("unique", false)).isTrue();
        assertThat(open.get("partialFilterExpression"))
            .isEqualTo(new Document("openEmailHash", new Document("$exists", true)));

        // 2) {workspaceId, status, submittedAt:-1}.
        assertThat(byKey).containsKey(
            new Document("workspaceId", 1).append("status", 1).append("submittedAt", -1).toJson());

        // 3) {workspaceId, submittedAt}.
        assertThat(byKey).containsKey(new Document("workspaceId", 1).append("submittedAt", 1).toJson());

        // 4) non-unique {workspaceId, emailHash}.
        Document emailKey = new Document("workspaceId", 1).append("emailHash", 1);
        Document email = byKey.get(emailKey.toJson());
        assertThat(email).as("email-hash lookup index").isNotNull();
        assertThat(email.getBoolean("unique", false)).isFalse();
    }
}
