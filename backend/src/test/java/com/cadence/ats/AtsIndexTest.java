package com.cadence.ats;

import org.bson.Document;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F41 ChangeUnit019 migration (FR-031/SC-013): the {@code atsConnections} uniqueness is migrated from
 * {@code {workspaceId}} to {@code {workspaceId, provider}}, and {@code atsSyncRuns} gains
 * {@code {workspaceId, provider, startedAt:-1}}. With a pre-existing single-Greenhouse row, a Lever row for the
 * same workspace inserts, but a second Greenhouse row for that workspace is rejected.
 */
class AtsIndexTest extends AtsItBase {

    private static final String WS = "ws-index";

    @Test
    void uniquenessIsWorkspacePlusProviderNotWorkspaceAlone() {
        var col = mongoTemplate.getCollection("atsConnections");

        boolean hasWorkspaceProviderUnique = false;
        boolean hasWorkspaceOnlyUnique = false;
        for (Document idx : col.listIndexes()) {
            Document key = idx.get("key", Document.class);
            boolean unique = Boolean.TRUE.equals(idx.getBoolean("unique"));
            if (key == null) {
                continue;
            }
            if (key.containsKey("workspaceId") && key.containsKey("provider") && key.size() == 2 && unique) {
                hasWorkspaceProviderUnique = true;
            }
            if (key.containsKey("workspaceId") && key.size() == 1 && unique) {
                hasWorkspaceOnlyUnique = true;
            }
        }
        assertThat(hasWorkspaceProviderUnique).as("unique {workspaceId, provider} exists").isTrue();
        assertThat(hasWorkspaceOnlyUnique).as("the old unique {workspaceId} is gone").isFalse();

        // A pre-existing Greenhouse row + a Lever row for the same workspace coexist; a 2nd Greenhouse row is rejected.
        col.insertOne(new Document("workspaceId", WS).append("provider", "GREENHOUSE").append("status", "CONNECTED"));
        col.insertOne(new Document("workspaceId", WS).append("provider", "LEVER").append("status", "CONNECTED"));
        assertThat(col.countDocuments(new Document("workspaceId", WS))).isEqualTo(2);
        assertThatThrownBy(() -> col.insertOne(
            new Document("workspaceId", WS).append("provider", "GREENHOUSE").append("status", "CONNECTED")))
            .isInstanceOf(com.mongodb.MongoWriteException.class);
    }

    @Test
    void syncRunsHasPerProviderIndex() {
        var col = mongoTemplate.getCollection("atsSyncRuns");
        boolean found = false;
        for (Document idx : col.listIndexes()) {
            Document key = idx.get("key", Document.class);
            if (key != null && key.containsKey("workspaceId") && key.containsKey("provider")
                && key.containsKey("startedAt")) {
                found = true;
            }
        }
        assertThat(found).as("atsSyncRuns {workspaceId, provider, startedAt:-1} exists").isTrue();
    }
}
