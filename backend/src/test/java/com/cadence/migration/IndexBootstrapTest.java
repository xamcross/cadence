package com.cadence.migration;

import com.cadence.BaseIntegrationTest;
import com.cadence.config.migration.ChangeUnit001_BootstrapIndexes;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.index.IndexInfo;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

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
    void mongockChangesetIsIdempotentOnReRun() {
        // The changeset already ran at startup. Re-running the exact same index creation must
        // not throw — createIndex with an identical spec is a no-op in MongoDB. This is the
        // F00.1 idempotency guarantee: Mongock skips applied changesets, but the underlying
        // operation must also be safe to repeat (e.g. if a redeploy re-applies it).
        ChangeUnit001_BootstrapIndexes changeUnit = new ChangeUnit001_BootstrapIndexes();
        assertThatCode(() -> changeUnit.execute(mongoTemplate)).doesNotThrowAnyException();

        // All six indexes remain present after the second run.
        allSixIndexesArePresentAfterStartup();
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
