package com.cadence.config.migration;

import com.mongodb.client.model.IndexOptions;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * F12 interview-template indexes (data-model §5 / research D7). Order "008" — derived off the highest
 * APPLIED ChangeUnit ("007"), NOT the branch number. Never rename after applied. Native createIndex +
 * targeted dropIndex rollback (CLAUDE.md Mongock rules; never dropIndexes()).
 *
 * <ul>
 *   <li>{@code interviewTemplates {workspaceId,status}} (non-unique): list active templates per
 *       workspace (FR-004/FR-006).
 *   <li>{@code managedCalendarEvents {workspaceId,memberId,startAt}} (non-unique): a SECOND index on
 *       the existing F10 collection backing the per-member-per-day daily-cap read (D5). All fields
 *       non-null — no partial-index footgun.
 * </ul>
 */
@ChangeUnit(id = "008-interview-template-indexes", order = "008", author = "system")
public class ChangeUnit008_InterviewTemplateIndexes {

    @Execution
    public void execute(MongoTemplate mongoTemplate) {
        mongoTemplate.getCollection("interviewTemplates").createIndex(
            new Document("workspaceId", 1).append("status", 1),
            new IndexOptions());

        mongoTemplate.getCollection("managedCalendarEvents").createIndex(
            new Document("workspaceId", 1).append("memberId", 1).append("startAt", 1),
            new IndexOptions());
    }

    @RollbackExecution
    public void rollback(MongoTemplate mongoTemplate) {
        mongoTemplate.getCollection("interviewTemplates").dropIndex(
            new Document("workspaceId", 1).append("status", 1));
        mongoTemplate.getCollection("managedCalendarEvents").dropIndex(
            new Document("workspaceId", 1).append("memberId", 1).append("startAt", 1));
    }
}
