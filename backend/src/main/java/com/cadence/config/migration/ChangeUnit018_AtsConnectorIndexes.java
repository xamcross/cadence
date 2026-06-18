package com.cadence.config.migration;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.IndexOptions;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * F40 ATS Integration (Greenhouse) indexes (data-model section 5). Order "018" -- derived off the highest
 * APPLIED ChangeUnit ("017"), NOT the branch number. Never rename after applied. Native createIndex +
 * targeted dropIndex rollback (CLAUDE.md Mongock rules; never dropIndexes()). New collections (no dedupe
 * step); the candidates partial-unique index is additive.
 *
 * <p><b>Indexes on atsConnections:</b>
 * <ul>
 *   <li>unique {@code {workspaceId}} -- one connection per workspace; concurrent first-connect collides
 *       to a DuplicateKeyException treated as the idempotent success.</li>
 *   <li>non-unique {@code {status}} -- the poll iterates CONNECTED connections.</li>
 * </ul>
 *
 * <p><b>Indexes on atsWriteBacks:</b>
 * <ul>
 *   <li>unique {@code {workspaceId, idempotencyKey}} -- exactly-once enqueue.</li>
 *   <li>non-unique {@code {status, nextAttemptAt}} -- the outbound drain scan.</li>
 *   <li>non-unique {@code {workspaceId, candidateId, status}} -- the erasure cancel sweep.</li>
 * </ul>
 *
 * <p><b>Indexes on atsSyncRuns:</b>
 * <ul>
 *   <li>non-unique {@code {workspaceId, startedAt:-1}} -- the newest-first status read.</li>
 * </ul>
 *
 * <p><b>Index on candidates:</b>
 * <ul>
 *   <li>unique PARTIAL {@code {workspaceId, atsProvider, atsExternalRef}} over {@code {atsExternalRef:{$exists:true}}}
 *       -- the authoritative reconcile key. atsProvider/atsExternalRef are write=NON_NULL so a native
 *       candidate (null ref) is omitted from BSON and does NOT collide on the partial unique index
 *       (the F01 present-as-null footgun does not apply).</li>
 * </ul>
 */
@ChangeUnit(id = "018-ats-connector-indexes", order = "018", author = "system")
public class ChangeUnit018_AtsConnectorIndexes {

    private static final Document CONN_WORKSPACE_KEY = new Document("workspaceId", 1);
    private static final Document CONN_STATUS_KEY = new Document("status", 1);

    private static final Document WB_IDEMPOTENCY_KEY = new Document("workspaceId", 1).append("idempotencyKey", 1);
    private static final Document WB_DRAIN_KEY = new Document("status", 1).append("nextAttemptAt", 1);
    private static final Document WB_ERASURE_KEY =
        new Document("workspaceId", 1).append("candidateId", 1).append("status", 1);

    private static final Document SYNC_RUN_KEY = new Document("workspaceId", 1).append("startedAt", -1);

    private static final Document CANDIDATE_ATS_KEY =
        new Document("workspaceId", 1).append("atsProvider", 1).append("atsExternalRef", 1);

    @Execution
    public void execute(MongoTemplate mongoTemplate) {
        MongoCollection<Document> connections = mongoTemplate.getCollection("atsConnections");
        connections.createIndex(CONN_WORKSPACE_KEY, new IndexOptions().unique(true));
        connections.createIndex(CONN_STATUS_KEY);

        MongoCollection<Document> writeBacks = mongoTemplate.getCollection("atsWriteBacks");
        writeBacks.createIndex(WB_IDEMPOTENCY_KEY, new IndexOptions().unique(true));
        writeBacks.createIndex(WB_DRAIN_KEY);
        writeBacks.createIndex(WB_ERASURE_KEY);

        MongoCollection<Document> syncRuns = mongoTemplate.getCollection("atsSyncRuns");
        syncRuns.createIndex(SYNC_RUN_KEY);

        MongoCollection<Document> candidates = mongoTemplate.getCollection("candidates");
        candidates.createIndex(CANDIDATE_ATS_KEY, new IndexOptions().unique(true)
            .partialFilterExpression(new Document("atsExternalRef", new Document("$exists", true))));
    }

    @RollbackExecution
    public void rollback(MongoTemplate mongoTemplate) {
        MongoCollection<Document> connections = mongoTemplate.getCollection("atsConnections");
        connections.dropIndex(CONN_WORKSPACE_KEY);
        connections.dropIndex(CONN_STATUS_KEY);

        MongoCollection<Document> writeBacks = mongoTemplate.getCollection("atsWriteBacks");
        writeBacks.dropIndex(WB_IDEMPOTENCY_KEY);
        writeBacks.dropIndex(WB_DRAIN_KEY);
        writeBacks.dropIndex(WB_ERASURE_KEY);

        MongoCollection<Document> syncRuns = mongoTemplate.getCollection("atsSyncRuns");
        syncRuns.dropIndex(SYNC_RUN_KEY);

        MongoCollection<Document> candidates = mongoTemplate.getCollection("candidates");
        candidates.dropIndex(CANDIDATE_ATS_KEY);
    }
}
