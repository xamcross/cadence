package com.cadence.config.migration;

import com.mongodb.client.MongoCollection;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * F51 Pipeline View indexes (data-model / research D8). Order "022" -- derived off the highest APPLIED ChangeUnit
 * ("021", F50 dashboard), NOT the branch number. Never rename after applied. Native createIndex + targeted
 * dropIndex rollback (CLAUDE.md Mongock rules; never dropIndexes()). Pure ASCII (the F30 NUL/binary lesson).
 *
 * <p>Three indexes -- one new collection {@code requisitions}, two on the existing {@code candidates}:
 * <ul>
 *   <li>{@code requisitions {workspaceId, status}} -- list + filter dropdown + the requisition-management surface.</li>
 *   <li>{@code candidates {workspaceId, erasureState, createdAt}} -- the workspace-active pipeline list read + a
 *       stable createdAt sort/pagination key.</li>
 *   <li>{@code candidates {workspaceId, requisitionId}} -- the Hiring-Manager scoped read
 *       ({@code requisitionId $in [assignedIds]}). {@code requisitionId} is write=NON_NULL, so an unassigned
 *       candidate omits it and never matches.</li>
 * </ul>
 * The batch scheduling-status read reuses the existing {@code schedulingRequests {workspaceId,candidateId,createdAt:-1}}
 * (ChangeUnit012) via its {@code {workspaceId,candidateId}} prefix -- NO new scheduling index. The timeline reuses
 * the existing {@code auditLog {candidateId, occurredAt:-1}} (ChangeUnit001/005) -- NO new audit index. All three new
 * key patterns differ from every existing index pattern (no MongoDB identical-key rejection -- the F42 lesson).
 */
@ChangeUnit(id = "022-pipeline-indexes", order = "022", author = "system")
public class ChangeUnit022_PipelineIndexes {

    private static final Document REQUISITION_KEY =
        new Document("workspaceId", 1).append("status", 1);
    private static final Document CANDIDATE_ACTIVE_KEY =
        new Document("workspaceId", 1).append("erasureState", 1).append("createdAt", 1);
    private static final Document CANDIDATE_REQUISITION_KEY =
        new Document("workspaceId", 1).append("requisitionId", 1);

    @Execution
    public void execute(MongoTemplate mongoTemplate) {
        MongoCollection<Document> requisitions = mongoTemplate.getCollection("requisitions");
        requisitions.createIndex(REQUISITION_KEY);
        MongoCollection<Document> candidates = mongoTemplate.getCollection("candidates");
        candidates.createIndex(CANDIDATE_ACTIVE_KEY);
        candidates.createIndex(CANDIDATE_REQUISITION_KEY);
    }

    @RollbackExecution
    public void rollback(MongoTemplate mongoTemplate) {
        mongoTemplate.getCollection("requisitions").dropIndex(REQUISITION_KEY);
        MongoCollection<Document> candidates = mongoTemplate.getCollection("candidates");
        candidates.dropIndex(CANDIDATE_ACTIVE_KEY);
        candidates.dropIndex(CANDIDATE_REQUISITION_KEY);
    }
}
