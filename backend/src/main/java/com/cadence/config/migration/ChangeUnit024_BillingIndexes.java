package com.cadence.config.migration;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.IndexOptions;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * 032 Freemius billing indexes. Order "024" -- derived off the highest APPLIED ChangeUnit ("023"),
 * NOT the branch number. Never rename after applied. Native createIndex + targeted dropIndex
 * rollback (CLAUDE.md Mongock rules; never dropIndexes()). New collections -- no dedupe step.
 * Pure ASCII source (the F30 NUL/binary lesson).
 *
 * <p>Three indexes:
 * <ul>
 *   <li>unique {@code {workspaceId}} on workspaceEntitlements -- one entitlement per workspace
 *       (FR-002); the claim-race loser gets DuplicateKeyException (SC-006).</li>
 *   <li>unique {@code {fsLicenseId}} on workspaceEntitlements -- one license can never back two
 *       workspaces (FR-006/SC-006). Always present on a bound row, so a plain unique index.</li>
 *   <li>unique {@code {eventId}} on billingWebhookEvents -- webhook replay suppression (FR-009),
 *       the ChangeUnit011 pattern in the billing-owned collection.</li>
 * </ul>
 */
@ChangeUnit(id = "024-billing-indexes", order = "024", author = "system")
public class ChangeUnit024_BillingIndexes {

    private static final Document WORKSPACE_KEY = new Document("workspaceId", 1);
    private static final Document LICENSE_KEY = new Document("fsLicenseId", 1);
    private static final Document EVENT_ID_KEY = new Document("eventId", 1);

    @Execution
    public void execute(MongoTemplate mongoTemplate) {
        MongoCollection<Document> entitlements = mongoTemplate.getCollection("workspaceEntitlements");
        entitlements.createIndex(WORKSPACE_KEY, new IndexOptions().unique(true));
        entitlements.createIndex(LICENSE_KEY, new IndexOptions().unique(true));
        mongoTemplate.getCollection("billingWebhookEvents")
            .createIndex(EVENT_ID_KEY, new IndexOptions().unique(true));
    }

    @RollbackExecution
    public void rollback(MongoTemplate mongoTemplate) {
        MongoCollection<Document> entitlements = mongoTemplate.getCollection("workspaceEntitlements");
        entitlements.dropIndex(WORKSPACE_KEY);
        entitlements.dropIndex(LICENSE_KEY);
        mongoTemplate.getCollection("billingWebhookEvents").dropIndex(EVENT_ID_KEY);
    }
}
