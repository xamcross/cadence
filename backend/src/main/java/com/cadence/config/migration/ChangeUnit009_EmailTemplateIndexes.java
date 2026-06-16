package com.cadence.config.migration;

import com.mongodb.client.model.IndexOptions;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * F21 email-template index (data-model §6 / research D2). Order "009" — derived off the highest APPLIED
 * ChangeUnit ("008"), NOT the branch number. Never rename after applied. Native createIndex + targeted
 * dropIndex rollback (CLAUDE.md Mongock rules; never dropIndexes()).
 *
 * <ul>
 *   <li>{@code emailTemplates {workspaceId, messageType, stageKey}} (UNIQUE): one override per type+stage;
 *       {@code stageKey} is the non-null discriminator ("BASE" vs a variant id), so a plain unique index
 *       separates base from every variant — no partial-index footgun. The {workspaceId} /
 *       {workspaceId,messageType} prefixes also back library + variant listing.
 * </ul>
 */
@ChangeUnit(id = "009-email-template-indexes", order = "009", author = "system")
public class ChangeUnit009_EmailTemplateIndexes {

    private static final Document KEY =
        new Document("workspaceId", 1).append("messageType", 1).append("stageKey", 1);

    @Execution
    public void execute(MongoTemplate mongoTemplate) {
        mongoTemplate.getCollection("emailTemplates").createIndex(KEY, new IndexOptions().unique(true));
    }

    @RollbackExecution
    public void rollback(MongoTemplate mongoTemplate) {
        mongoTemplate.getCollection("emailTemplates").dropIndex(KEY);
    }
}
