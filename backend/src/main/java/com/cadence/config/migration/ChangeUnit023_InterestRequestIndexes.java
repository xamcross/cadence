package com.cadence.config.migration;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.IndexOptions;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * F70 Join / Express-Interest indexes (data-model section "Indexes"). Order "023" -- derived off the highest
 * APPLIED ChangeUnit ("022", F51 pipeline), NOT the branch number. Never rename after applied. Native createIndex
 * + targeted dropIndex rollback (CLAUDE.md Mongock rules; never dropIndexes()). New collection -> no dedupe step.
 * Pure ASCII source (the F30 NUL/binary lesson).
 *
 * <p>Four indexes on interestRequests:
 * <ul>
 *   <li>unique partial {@code {workspaceId, openEmailHash}} over {@code {openEmailHash:{$exists:true}}} -- one
 *       open request per email per workspace (dedup, FR-008/SC-007). The partial filter keys on the present
 *       value, so a terminal/erased row (openEmailHash omitted via write=NON_NULL) drops out and a fresh
 *       submission inserts cleanly (the F01 present-as-null collision footgun does not apply).</li>
 *   <li>{@code {workspaceId, status, submittedAt:-1}} -- admin queue list + status filter (FR-011), recent-first.</li>
 *   <li>{@code {workspaceId, submittedAt}} -- retention purge age scan (FR-021) AND the per-workspace flood-ceiling
 *       count gate (FR-018/R6).</li>
 *   <li>non-unique {@code {workspaceId, emailHash}} -- admin lookup / erasure-by-email discovery (FR-022). A
 *       distinct key pattern from the unique partial above (no MongoDB identical-key rejection -- the F42 lesson).</li>
 * </ul>
 */
@ChangeUnit(id = "023-interest-request-indexes", order = "023", author = "system")
public class ChangeUnit023_InterestRequestIndexes {

    private static final Document OPEN_EMAIL_KEY =
        new Document("workspaceId", 1).append("openEmailHash", 1);
    private static final Document WS_STATUS_SUBMITTED_KEY =
        new Document("workspaceId", 1).append("status", 1).append("submittedAt", -1);
    private static final Document WS_SUBMITTED_KEY =
        new Document("workspaceId", 1).append("submittedAt", 1);
    private static final Document WS_EMAIL_KEY =
        new Document("workspaceId", 1).append("emailHash", 1);

    @Execution
    public void execute(MongoTemplate mongoTemplate) {
        MongoCollection<Document> interest = mongoTemplate.getCollection("interestRequests");
        interest.createIndex(OPEN_EMAIL_KEY, new IndexOptions().unique(true)
            .partialFilterExpression(new Document("openEmailHash", new Document("$exists", true))));
        interest.createIndex(WS_STATUS_SUBMITTED_KEY);
        interest.createIndex(WS_SUBMITTED_KEY);
        interest.createIndex(WS_EMAIL_KEY);
    }

    @RollbackExecution
    public void rollback(MongoTemplate mongoTemplate) {
        MongoCollection<Document> interest = mongoTemplate.getCollection("interestRequests");
        interest.dropIndex(OPEN_EMAIL_KEY);
        interest.dropIndex(WS_STATUS_SUBMITTED_KEY);
        interest.dropIndex(WS_SUBMITTED_KEY);
        interest.dropIndex(WS_EMAIL_KEY);
    }
}
