package com.cadence.config.migration;

import com.mongodb.client.model.IndexOptions;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * F02 RBAC indexes (research D10 / data-model). Order "003" — never rename after applied.
 * Native createIndex + targeted dropIndex rollback (CLAUDE.md Mongock rules).
 *
 * - members {workspaceId,role,status} (non-unique): backs the last-Admin guard predicate
 *   (conditional flip) and the recount (research D4). No null-collision risk (non-unique).
 * - assignments {workspaceId,memberId,resourceType}: scoped collection reads (FR-024).
 * - assignments {workspaceId,resourceType,resourceId,memberId} (unique): prevents duplicate
 *   assignment of the same resource to the same member.
 */
@ChangeUnit(id = "003-rbac-indexes", order = "003", author = "system")
public class ChangeUnit003_RbacIndexes {

    @Execution
    public void execute(MongoTemplate mongoTemplate) {
        mongoTemplate.getCollection("members").createIndex(
            new Document("workspaceId", 1).append("role", 1).append("status", 1));

        mongoTemplate.getCollection("assignments").createIndex(
            new Document("workspaceId", 1).append("memberId", 1).append("resourceType", 1));

        mongoTemplate.getCollection("assignments").createIndex(
            new Document("workspaceId", 1).append("resourceType", 1)
                .append("resourceId", 1).append("memberId", 1),
            new IndexOptions().unique(true));
    }

    @RollbackExecution
    public void rollback(MongoTemplate mongoTemplate) {
        mongoTemplate.getCollection("members").dropIndex(
            new Document("workspaceId", 1).append("role", 1).append("status", 1));
        mongoTemplate.getCollection("assignments").dropIndex(
            new Document("workspaceId", 1).append("memberId", 1).append("resourceType", 1));
        mongoTemplate.getCollection("assignments").dropIndex(
            new Document("workspaceId", 1).append("resourceType", 1)
                .append("resourceId", 1).append("memberId", 1));
    }
}
