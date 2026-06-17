package com.cadence.config.migration;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.IndexOptions;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * F31 SLA Nudge Engine indexes (data-model section 4). Order "016" -- derived off the highest APPLIED
 * ChangeUnit ("015"), NOT the branch number. Never rename after applied. Native createIndex + targeted
 * dropIndex rollback (CLAUDE.md Mongock rules; never dropIndexes()). New collection -> no dedupe step.
 *
 * <p><b>Indexes on slaNudgeDrafts:</b> a unique partial {@code {workspaceId,candidateId}} over
 * {@code status:"OPEN"} (at most one open draft per candidate -- the F22 emailDispatches / F23
 * confirmTokenHash partial-unique precedent; the partial filter keys on the present value "OPEN", so the
 * F01 present-as-null collision footgun does not apply), and a non-unique {@code {workspaceId,status}} that
 * backs the workspace draft-queue / silence-list openDraftId read.
 *
 * <p>No {@code candidates} index -- the breach range scan reuses the existing {@code {workspaceId,lastContactAt}}
 * index created by ChangeUnit001.
 */
@ChangeUnit(id = "016-sla-nudge-indexes", order = "016", author = "system")
public class ChangeUnit016_SlaNudgeIndexes {

    private static final Document OPEN_DRAFT_KEY = new Document("workspaceId", 1).append("candidateId", 1);
    private static final Document WS_STATUS_KEY = new Document("workspaceId", 1).append("status", 1);

    @Execution
    public void execute(MongoTemplate mongoTemplate) {
        MongoCollection<Document> drafts = mongoTemplate.getCollection("slaNudgeDrafts");
        drafts.createIndex(OPEN_DRAFT_KEY, new IndexOptions().unique(true)
            .partialFilterExpression(new Document("status", "OPEN")));
        drafts.createIndex(WS_STATUS_KEY);
    }

    @RollbackExecution
    public void rollback(MongoTemplate mongoTemplate) {
        MongoCollection<Document> drafts = mongoTemplate.getCollection("slaNudgeDrafts");
        drafts.dropIndex(OPEN_DRAFT_KEY);
        drafts.dropIndex(WS_STATUS_KEY);
    }
}
