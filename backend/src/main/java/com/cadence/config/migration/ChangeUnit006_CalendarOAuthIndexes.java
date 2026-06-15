package com.cadence.config.migration;

import com.mongodb.client.model.IndexOptions;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.util.concurrent.TimeUnit;

/**
 * F01.1 calendar OAuth indexes (research D8 / data-model §5). Order "006" — never rename after applied.
 * Native createIndex + targeted dropIndex rollback (CLAUDE.md Mongock rules; never dropIndexes).
 *
 * <ul>
 *   <li>{@code calendarConnections {workspaceId,memberId,provider}} (UNIQUE): one connection per pair
 *       (FR-004) and the self/service lookup key. All fields non-null — no partial-index footgun.
 *   <li>{@code calendarOAuthState {expiresAt}} (TTL, expireAfter 0s): auto-reaps abandoned in-flight
 *       flows with no scheduled task. NOTE the native {@link IndexOptions#expireAfter} form — an
 *       index-key {@code Document("expireAfterSeconds",0)} would build a plain field index, not TTL.
 * </ul>
 */
@ChangeUnit(id = "006-calendar-oauth-indexes", order = "006", author = "system")
public class ChangeUnit006_CalendarOAuthIndexes {

    @Execution
    public void execute(MongoTemplate mongoTemplate) {
        mongoTemplate.getCollection("calendarConnections").createIndex(
            new Document("workspaceId", 1).append("memberId", 1).append("provider", 1),
            new IndexOptions().unique(true));

        mongoTemplate.getCollection("calendarOAuthState").createIndex(
            new Document("expiresAt", 1),
            new IndexOptions().expireAfter(0L, TimeUnit.SECONDS));
    }

    @RollbackExecution
    public void rollback(MongoTemplate mongoTemplate) {
        mongoTemplate.getCollection("calendarConnections").dropIndex(
            new Document("workspaceId", 1).append("memberId", 1).append("provider", 1));
        mongoTemplate.getCollection("calendarOAuthState").dropIndex(
            new Document("expiresAt", 1));
    }
}
