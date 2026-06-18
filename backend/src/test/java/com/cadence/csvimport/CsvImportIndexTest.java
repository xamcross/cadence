package com.cadence.csvimport;

import com.cadence.BaseIntegrationTest;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** F42 (T062): ChangeUnit020 created every CSV-import index, incl. the unique-partial CSV emailHash index. */
class CsvImportIndexTest extends BaseIntegrationTest {

    @Test
    void changeUnit020_createsAllImportIndexes() {
        assertThat(indexKeys("csvImportJobs")).contains("workspaceId_1__id_1", "status_1_createdAt_1",
            "status_1_expiresAt_1", "status_1_updatedAt_1");

        List<Document> fileIdx = indexes("csvImportFiles");
        assertThat(fileIdx).anySatisfy(d -> {
            assertThat(((Document) d.get("key")).keySet()).containsExactly("jobId");
            assertThat(d.getBoolean("unique", false)).isTrue();
        });
        // The TTL backstop on createdAt.
        assertThat(fileIdx).anySatisfy(d -> assertThat(d.containsKey("expireAfterSeconds")).isTrue());

        // The unique-partial CSV emailHash index on candidates (SC-013 provability). Triple key to avoid
        // colliding with the ChangeUnit005 {workspaceId,emailHash} non-unique index.
        assertThat(indexes("candidates")).anySatisfy(d -> {
            assertThat(((Document) d.get("key")).keySet()).containsExactly("workspaceId", "origin", "emailHash");
            assertThat(d.getBoolean("unique", false)).isTrue();
            assertThat(d.containsKey("partialFilterExpression")).isTrue();
        });
    }

    private List<Document> indexes(String collection) {
        List<Document> out = new ArrayList<>();
        mongoTemplate.getCollection(collection).listIndexes().forEach(out::add);
        return out;
    }

    private List<String> indexKeys(String collection) {
        List<String> names = new ArrayList<>();
        indexes(collection).forEach(d -> names.add(d.getString("name")));
        return names;
    }
}
