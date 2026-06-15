package com.cadence.config.migration;

import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * F04 GDPR baseline indexes (research D12 / data-model). Order "005" — never rename after applied.
 * Native createIndex + targeted dropIndex rollback (CLAUDE.md Mongock rules).
 *
 * <p>Creates only the NEW indexes:
 * <ul>
 *   <li>candidates {workspaceId,emailHash} (NON-unique): email lookup. Non-unique avoids the
 *       null-collision footgun when erasure clears emailHash for multiple candidates, and a person
 *       may legitimately be a candidate for more than one requisition (dedup is F42's concern).
 *   <li>erasureRequests {workspaceId,status}: the Admin pending-queue read.
 *   <li>auditLog {workspaceId,candidateId,occurredAt}: the workspace-scoped candidate audit read
 *       (the ChangeUnit001 {candidateId,occurredAt:-1} index does not cover the workspaceId predicate
 *       nor the ascending sort used by the F04 finder).
 * </ul>
 * The {@code candidates {workspaceId,lastContactAt}} index already exists from ChangeUnit001 and is
 * NOT recreated here.
 */
@ChangeUnit(id = "005-gdpr-baseline-indexes", order = "005", author = "system")
public class ChangeUnit005_GdprIndexes {

    @Execution
    public void execute(MongoTemplate mongoTemplate) {
        mongoTemplate.getCollection("candidates").createIndex(
            new Document("workspaceId", 1).append("emailHash", 1));

        mongoTemplate.getCollection("erasureRequests").createIndex(
            new Document("workspaceId", 1).append("status", 1));

        mongoTemplate.getCollection("auditLog").createIndex(
            new Document("workspaceId", 1).append("candidateId", 1).append("occurredAt", 1));
    }

    @RollbackExecution
    public void rollback(MongoTemplate mongoTemplate) {
        mongoTemplate.getCollection("candidates").dropIndex(
            new Document("workspaceId", 1).append("emailHash", 1));
        mongoTemplate.getCollection("erasureRequests").dropIndex(
            new Document("workspaceId", 1).append("status", 1));
        mongoTemplate.getCollection("auditLog").dropIndex(
            new Document("workspaceId", 1).append("candidateId", 1).append("occurredAt", 1));
    }
}
