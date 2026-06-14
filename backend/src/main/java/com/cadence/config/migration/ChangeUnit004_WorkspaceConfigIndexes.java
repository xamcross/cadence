package com.cadence.config.migration;

import com.mongodb.client.model.IndexOptions;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * F03 Workspace Setup &amp; Configuration indexes (research D12 / data-model). Order "004" — never
 * rename after applied. Native createIndex + targeted dropIndex rollback (CLAUDE.md Mongock rules).
 *
 * - workspaceConfig {workspaceId} (unique): enforces the singleton config doc per workspace and
 *   backs the conditional-upsert concurrency guarantee (research D1/D4).
 * - workspaceLogo {workspaceId} (unique): one logo doc per workspace.
 *
 * All indexed fields are non-null, so no @Field(write=NON_NULL) partial-index footgun here.
 */
@ChangeUnit(id = "004-workspace-config-indexes", order = "004", author = "system")
public class ChangeUnit004_WorkspaceConfigIndexes {

    @Execution
    public void execute(MongoTemplate mongoTemplate) {
        mongoTemplate.getCollection("workspaceConfig").createIndex(
            new Document("workspaceId", 1), new IndexOptions().unique(true));

        mongoTemplate.getCollection("workspaceLogo").createIndex(
            new Document("workspaceId", 1), new IndexOptions().unique(true));
    }

    @RollbackExecution
    public void rollback(MongoTemplate mongoTemplate) {
        mongoTemplate.getCollection("workspaceConfig").dropIndex(new Document("workspaceId", 1));
        mongoTemplate.getCollection("workspaceLogo").dropIndex(new Document("workspaceId", 1));
    }
}
