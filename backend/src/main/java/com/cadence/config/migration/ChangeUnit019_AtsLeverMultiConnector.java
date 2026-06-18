package com.cadence.config.migration;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.IndexOptions;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * F41 ATS Integration (Lever) multi-connector migration (data-model Delta 6). Order "019" -- derived off the
 * highest APPLIED ChangeUnit ("018"), NOT the branch number. Never rename after applied. Native createIndex +
 * targeted dropIndex rollback (CLAUDE.md Mongock rules; never dropIndexes()).
 *
 * <p><b>Why</b>: F40 enforced a unique {@code {workspaceId}} index on {@code atsConnections} (one connection
 * per workspace). F41 lets a workspace hold one Greenhouse AND one Lever connection, so the uniqueness key
 * must become {@code {workspaceId, provider}}. The {@code provider} field already exists on every row, so no
 * data back-fill is needed -- only the index changes.
 *
 * <p><b>Execution</b>:
 * <ul>
 *   <li>drop the unique {@code {workspaceId}} index on {@code atsConnections} (created by ChangeUnit018);</li>
 *   <li>create unique {@code {workspaceId, provider}} on {@code atsConnections};</li>
 *   <li>create the additive {@code {workspaceId, provider, startedAt:-1}} index on {@code atsSyncRuns}
 *       (the F40 {@code {workspaceId, startedAt:-1}} index stays -- different key, no collision -- and backs
 *       the per-provider "last successful sync" read).</li>
 * </ul>
 *
 * <p><b>Migration safety</b>: dropping unique {@code {workspaceId}} and creating unique
 * {@code {workspaceId, provider}} is non-destructive for existing single-provider data -- each workspace has at
 * most one Greenhouse row, which trivially satisfies the new compound key. Rollback recreates unique
 * {@code {workspaceId}} and is only safe before a second provider connects (the standard Mongock caveat).
 */
@ChangeUnit(id = "019-ats-lever-multi-connector", order = "019", author = "system")
public class ChangeUnit019_AtsLeverMultiConnector {

    private static final Document CONN_WORKSPACE_KEY = new Document("workspaceId", 1);
    private static final Document CONN_WORKSPACE_PROVIDER_KEY =
        new Document("workspaceId", 1).append("provider", 1);
    private static final Document SYNC_RUN_PROVIDER_KEY =
        new Document("workspaceId", 1).append("provider", 1).append("startedAt", -1);

    @Execution
    public void execute(MongoTemplate mongoTemplate) {
        MongoCollection<Document> connections = mongoTemplate.getCollection("atsConnections");
        // Drop the F40 unique {workspaceId} index, then create the F41 unique {workspaceId, provider}.
        connections.dropIndex(CONN_WORKSPACE_KEY);
        connections.createIndex(CONN_WORKSPACE_PROVIDER_KEY, new IndexOptions().unique(true));

        // Additive per-provider status index on atsSyncRuns (the F40 {workspaceId, startedAt:-1} stays).
        MongoCollection<Document> syncRuns = mongoTemplate.getCollection("atsSyncRuns");
        syncRuns.createIndex(SYNC_RUN_PROVIDER_KEY);
    }

    @RollbackExecution
    public void rollback(MongoTemplate mongoTemplate) {
        MongoCollection<Document> connections = mongoTemplate.getCollection("atsConnections");
        // Drop ONLY the F41 keys and restore the F40 unique {workspaceId}.
        connections.dropIndex(CONN_WORKSPACE_PROVIDER_KEY);
        connections.createIndex(CONN_WORKSPACE_KEY, new IndexOptions().unique(true));

        MongoCollection<Document> syncRuns = mongoTemplate.getCollection("atsSyncRuns");
        // Drop ONLY the new compound key; never touch the F40 {workspaceId, startedAt:-1} index.
        syncRuns.dropIndex(SYNC_RUN_PROVIDER_KEY);
    }
}
