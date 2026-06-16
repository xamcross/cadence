package com.cadence.config.migration;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.IndexOptions;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.util.List;

/**
 * F23 No-Show Defense indexes + backfill (data-model §6). Order "014" — derived off the highest APPLIED
 * ChangeUnit ("013"), NOT the branch number. Never rename after applied. Native createIndex + targeted
 * dropIndex rollback (CLAUDE.md Mongock rules; never dropIndexes()). No new collection — all on
 * {@code schedulingRequests}.
 *
 * <p>{@code {status,bookedStartAt}} backs the three cascade stage scans (D1/D2). UNIQUE PARTIAL
 * {@code {confirmTokenHash}} over {@code {$exists:true}} (the confirm-credential lookup — partial NOT sparse,
 * paired with {@code @Field(write=NON_NULL)}, so two cleared rows never collide; the F01 present-as-null lesson).
 *
 * <p><b>Backfill (D2):</b> pre-F23 BOOKED rows have no {@code bookedStartAt} and would be invisible to the
 * cascade. This change backfills it from {@code offeredSlots[chosenSlotId].start} (idempotent — only rows
 * still missing the field). Forward-only; the rollback drops the indexes (it does not un-backfill a denormalized
 * field, which is harmless to leave).
 */
@ChangeUnit(id = "014-noshow-indexes", order = "014", author = "system")
public class ChangeUnit014_NoShowIndexes {

    private static final Document SR_CASCADE = new Document("status", 1).append("bookedStartAt", 1);
    private static final Document SR_CONFIRM_TOKEN = new Document("confirmTokenHash", 1);

    @Execution
    public void execute(MongoTemplate mongoTemplate) {
        MongoCollection<Document> requests = mongoTemplate.getCollection("schedulingRequests");
        requests.createIndex(SR_CASCADE);
        requests.createIndex(SR_CONFIRM_TOKEN, new IndexOptions().unique(true)
            .partialFilterExpression(new Document("confirmTokenHash", new Document("$exists", true))));

        // Backfill bookedStartAt on existing BOOKED rows from the chosen offered slot (idempotent).
        Document filter = new Document("status", "BOOKED")
            .append("bookedStartAt", new Document("$exists", false));
        for (Document row : requests.find(filter)) {
            Object chosenSlotId = row.get("chosenSlotId");
            List<?> offered = row.get("offeredSlots", List.class);
            if (chosenSlotId == null || offered == null) {
                continue;
            }
            for (Object o : offered) {
                if (!(o instanceof Document slot)) {
                    continue;
                }
                if (chosenSlotId.equals(slot.get("slotId")) && slot.get("start") != null) {
                    requests.updateOne(new Document("_id", row.get("_id")),
                        new Document("$set", new Document("bookedStartAt", slot.get("start"))));
                    break;
                }
            }
        }
    }

    @RollbackExecution
    public void rollback(MongoTemplate mongoTemplate) {
        MongoCollection<Document> requests = mongoTemplate.getCollection("schedulingRequests");
        requests.dropIndex(SR_CASCADE);
        requests.dropIndex(SR_CONFIRM_TOKEN);
    }
}
