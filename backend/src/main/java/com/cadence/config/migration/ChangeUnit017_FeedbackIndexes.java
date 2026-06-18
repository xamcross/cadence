package com.cadence.config.migration;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.IndexOptions;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * F32 Interviewer Feedback indexes (data-model section 4). Order "017" -- derived off the highest APPLIED
 * ChangeUnit ("016"), NOT the branch number. Never rename after applied. Native createIndex + targeted
 * dropIndex rollback (CLAUDE.md Mongock rules; never dropIndexes()). New collection -> no dedupe step.
 *
 * <p><b>Indexes on feedbackRequests:</b>
 * <ul>
 *   <li>unique {@code {interviewEventId, interviewerMemberId}} -- de-dup one request per {occurrence,
 *       interviewer} (FR-003); both fields always non-null at insert, so a plain unique index.</li>
 *   <li>unique partial {@code {tokenHash}} over {@code {$exists:true}} -- the write-only token lookup (the
 *       F23 confirmTokenHash partial-unique precedent; {@code write=NON_NULL} on the field means present-as-null
 *       cannot occur, so the F01 collision footgun does not apply).</li>
 *   <li>non-unique {@code {status, nextReminderDueAt}} -- the reminder scan range (FR-015).</li>
 * </ul>
 *
 * <p>No {@code schedulingRequests} index -- the generation scan reuses the existing {@code {status,bookedStartAt}}
 * (ChangeUnit014). The {@code {interviewEventId, submittedAt}} index is already created by ChangeUnit001 and is
 * NOT recreated here.
 */
@ChangeUnit(id = "017-feedback-indexes", order = "017", author = "system")
public class ChangeUnit017_FeedbackIndexes {

    private static final Document DEDUP_KEY = new Document("interviewEventId", 1).append("interviewerMemberId", 1);
    private static final Document TOKEN_KEY = new Document("tokenHash", 1);
    private static final Document REMINDER_KEY = new Document("status", 1).append("nextReminderDueAt", 1);

    @Execution
    public void execute(MongoTemplate mongoTemplate) {
        MongoCollection<Document> feedback = mongoTemplate.getCollection("feedbackRequests");
        feedback.createIndex(DEDUP_KEY, new IndexOptions().unique(true));
        feedback.createIndex(TOKEN_KEY, new IndexOptions().unique(true)
            .partialFilterExpression(new Document("tokenHash", new Document("$exists", true))));
        feedback.createIndex(REMINDER_KEY);
    }

    @RollbackExecution
    public void rollback(MongoTemplate mongoTemplate) {
        MongoCollection<Document> feedback = mongoTemplate.getCollection("feedbackRequests");
        feedback.dropIndex(DEDUP_KEY);
        feedback.dropIndex(TOKEN_KEY);
        feedback.dropIndex(REMINDER_KEY);
    }
}
