package com.cadence.pipeline;

import com.cadence.BaseIntegrationTest;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F51 T017 — ChangeUnit022 created the three pipeline indexes: {@code requisitions {workspaceId,status}} and the two
 * {@code candidates} indexes ({@code {workspaceId,erasureState,createdAt}}, {@code {workspaceId,requisitionId}}).
 */
class PipelineIndexTest extends BaseIntegrationTest {

    private List<Document> keys(String collection) {
        List<Document> keys = new ArrayList<>();
        for (Document idx : mongoTemplate.getCollection(collection).listIndexes()) {
            keys.add((Document) idx.get("key"));
        }
        return keys;
    }

    @Test
    void changeUnit022_createsPipelineIndexes() {
        assertThat(keys("requisitions")).contains(new Document("workspaceId", 1).append("status", 1));
        assertThat(keys("candidates")).contains(
            new Document("workspaceId", 1).append("erasureState", 1).append("createdAt", 1));
        assertThat(keys("candidates")).contains(new Document("workspaceId", 1).append("requisitionId", 1));
    }
}
