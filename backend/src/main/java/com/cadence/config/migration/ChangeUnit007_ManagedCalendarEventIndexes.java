package com.cadence.config.migration;

import com.mongodb.client.model.IndexOptions;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * F10 managed-calendar-event indexes (data-model §1 / research D14). Order "007" — never rename after
 * applied. Native createIndex + targeted dropIndex rollback (CLAUDE.md Mongock rules; never dropIndexes).
 *
 * <ul>
 *   <li>{@code managedCalendarEvents {workspaceId,bookingRef,memberId,provider}} (UNIQUE): the
 *       idempotency claim (FR-010) and the per-participant lookup for update/delete. All fields non-null
 *       — no partial-index footgun.
 *   <li>{@code managedCalendarEvents {workspaceId,bookingRef}} (non-unique): enumerate all participants'
 *       events for rollback (FR-012) and cancel/reschedule (F20).
 * </ul>
 */
@ChangeUnit(id = "007-managed-calendar-event-indexes", order = "007", author = "system")
public class ChangeUnit007_ManagedCalendarEventIndexes {

    @Execution
    public void execute(MongoTemplate mongoTemplate) {
        mongoTemplate.getCollection("managedCalendarEvents").createIndex(
            new Document("workspaceId", 1).append("bookingRef", 1).append("memberId", 1).append("provider", 1),
            new IndexOptions().unique(true));

        mongoTemplate.getCollection("managedCalendarEvents").createIndex(
            new Document("workspaceId", 1).append("bookingRef", 1));
    }

    @RollbackExecution
    public void rollback(MongoTemplate mongoTemplate) {
        mongoTemplate.getCollection("managedCalendarEvents").dropIndex(
            new Document("workspaceId", 1).append("bookingRef", 1).append("memberId", 1).append("provider", 1));
        mongoTemplate.getCollection("managedCalendarEvents").dropIndex(
            new Document("workspaceId", 1).append("bookingRef", 1));
    }
}
