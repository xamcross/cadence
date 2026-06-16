package com.cadence.config.migration;

import com.mongodb.client.model.IndexOptions;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * F22 inbound-webhook idempotency index (T040, SC-009). Order "011" — derived off the highest APPLIED
 * ChangeUnit ("010"), NOT the branch number. Never rename after applied. Native createIndex + targeted
 * dropIndex rollback (CLAUDE.md Mongock rules; never dropIndexes()).
 *
 * <ul>
 *   <li>UNIQUE {@code {eventId}} on {@code processedWebhookEvents} — a duplicate/out-of-order provider
 *       callback hits the unique index -> {@code DuplicateKeyException} -> exactly-once flip/notify (FR-019).</li>
 * </ul>
 */
@ChangeUnit(id = "011-webhook-event-indexes", order = "011", author = "system")
public class ChangeUnit011_WebhookEventIndexes {

    private static final Document EVENT_ID_KEY = new Document("eventId", 1);

    @Execution
    public void execute(MongoTemplate mongoTemplate) {
        mongoTemplate.getCollection("processedWebhookEvents")
            .createIndex(EVENT_ID_KEY, new IndexOptions().unique(true));
    }

    @RollbackExecution
    public void rollback(MongoTemplate mongoTemplate) {
        mongoTemplate.getCollection("processedWebhookEvents").dropIndex(EVENT_ID_KEY);
    }
}
