package com.cadence.config.migration;

import com.mongodb.client.model.IndexOptions;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * F22 dispatch-outbox indexes (data-model §1). Order "010" — derived off the highest APPLIED ChangeUnit
 * ("009"), NOT the branch number. Never rename after applied. Native createIndex + targeted dropIndex
 * rollback (CLAUDE.md Mongock rules; never dropIndexes()).
 *
 * <ul>
 *   <li>UNIQUE {@code {workspaceId, idempotencyKey}} — the durable exactly-once guarantee (FR-009).</li>
 *   <li>{@code {status, nextAttemptAt}} — the scheduled due-row picker; covers the hot worker query (D6).</li>
 *   <li>SPARSE {@code {providerMessageRef}} — webhook event -> dispatch correlation (FR-019); null until sent.</li>
 *   <li>{@code {workspaceId, candidateId, createdAt:-1}} — per-candidate communications history (FR-014).</li>
 * </ul>
 */
@ChangeUnit(id = "010-email-dispatch-indexes", order = "010", author = "system")
public class ChangeUnit010_EmailDispatchIndexes {

    private static final Document UNIQUE_KEY =
        new Document("workspaceId", 1).append("idempotencyKey", 1);
    private static final Document DUE_KEY =
        new Document("status", 1).append("nextAttemptAt", 1);
    private static final Document PROVIDER_REF_KEY =
        new Document("providerMessageRef", 1);
    private static final Document HISTORY_KEY =
        new Document("workspaceId", 1).append("candidateId", 1).append("createdAt", -1);

    @Execution
    public void execute(MongoTemplate mongoTemplate) {
        var coll = mongoTemplate.getCollection("emailDispatches");
        coll.createIndex(UNIQUE_KEY, new IndexOptions().unique(true));
        coll.createIndex(DUE_KEY);
        coll.createIndex(PROVIDER_REF_KEY, new IndexOptions().sparse(true));
        coll.createIndex(HISTORY_KEY);
    }

    @RollbackExecution
    public void rollback(MongoTemplate mongoTemplate) {
        var coll = mongoTemplate.getCollection("emailDispatches");
        coll.dropIndex(UNIQUE_KEY);
        coll.dropIndex(DUE_KEY);
        coll.dropIndex(PROVIDER_REF_KEY);
        coll.dropIndex(HISTORY_KEY);
    }
}
