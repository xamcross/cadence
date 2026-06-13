package com.cadence.migration;

import com.cadence.BaseIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.index.IndexInfo;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IndexBootstrapTest extends BaseIntegrationTest {

    @Test
    void allSixIndexesArePresentAfterStartup() {
        assertIndexExists("interviews", "scheduledAt", "confirmationStatus");
        assertIndexExists("candidates", "workspaceId", "lastContactAt");
        assertIndexExists("feedbackRequests", "interviewEventId", "submittedAt");
        assertIndexExists("schedulingTokens", "token");
        assertIndexExists("auditLog", "candidateId", "occurredAt");
        assertIndexExists("schedulerCheckpoints", "taskName");
    }

    @Test
    void schedulingTokenIndexIsUnique() {
        List<IndexInfo> indexes = mongoTemplate.indexOps("schedulingTokens").getIndexInfo();
        boolean hasUniqueTokenIndex = indexes.stream()
            .anyMatch(idx -> idx.isUnique() && idx.getIndexFields().stream()
                .anyMatch(f -> "token".equals(f.getKey())));
        assertThat(hasUniqueTokenIndex).isTrue();
    }

    @Test
    void schedulerCheckpointIndexIsUnique() {
        List<IndexInfo> indexes = mongoTemplate.indexOps("schedulerCheckpoints").getIndexInfo();
        boolean hasUniqueTaskNameIndex = indexes.stream()
            .anyMatch(idx -> idx.isUnique() && idx.getIndexFields().stream()
                .anyMatch(f -> "taskName".equals(f.getKey())));
        assertThat(hasUniqueTaskNameIndex).isTrue();
    }

    private void assertIndexExists(String collection, String... fields) {
        List<IndexInfo> indexes = mongoTemplate.indexOps(collection).getIndexInfo();
        for (String field : fields) {
            boolean found = indexes.stream()
                .anyMatch(idx -> idx.getIndexFields().stream()
                    .anyMatch(f -> field.equals(f.getKey())));
            assertThat(found)
                .as("Expected index on field '%s' in collection '%s'", field, collection)
                .isTrue();
        }
    }
}
