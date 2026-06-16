package com.cadence.config.migration;

import com.mongodb.client.model.IndexOptions;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * F20 reschedule/cancel indexes (data-model §6). Order "013" — derived off the highest APPLIED ChangeUnit
 * ("012"), NOT the branch number. Never rename after applied. Native createIndex + targeted dropIndex
 * rollback (CLAUDE.md Mongock rules; never dropIndexes()). No new collection — all on {@code schedulingRequests}.
 *
 * <p>UNIQUE PARTIAL {@code {manageTokenHash}} over {@code {$exists:true}} (the reschedule/cancel credential
 * lookup — partial NOT sparse, paired with {@code @Field(write=NON_NULL)}, so two cleared rows never collide;
 * the F01 present-as-null lesson). {@code {rootRequestId,mode,status}} (the cap-derivation count + lineage
 * reads, D5). {@code {mode,status,updatedAt}} (the reschedule forward-commit recovery scan, D3). PARTIAL
 * {@code {calendarTeardownPending}} over {@code true} (the erasure async-teardown reaper scan, D9).
 */
@ChangeUnit(id = "013-reschedule-indexes", order = "013", author = "system")
public class ChangeUnit013_RescheduleIndexes {

    private static final Document SR_MANAGE_TOKEN = new Document("manageTokenHash", 1);
    private static final Document SR_LINEAGE =
        new Document("rootRequestId", 1).append("mode", 1).append("status", 1);
    private static final Document SR_RECOVERY =
        new Document("mode", 1).append("status", 1).append("updatedAt", 1);
    private static final Document SR_TEARDOWN = new Document("calendarTeardownPending", 1);

    @Execution
    public void execute(MongoTemplate mongoTemplate) {
        var requests = mongoTemplate.getCollection("schedulingRequests");
        requests.createIndex(SR_MANAGE_TOKEN, new IndexOptions().unique(true)
            .partialFilterExpression(new Document("manageTokenHash", new Document("$exists", true))));
        requests.createIndex(SR_LINEAGE);
        requests.createIndex(SR_RECOVERY);
        requests.createIndex(SR_TEARDOWN, new IndexOptions()
            .partialFilterExpression(new Document("calendarTeardownPending", true)));
    }

    @RollbackExecution
    public void rollback(MongoTemplate mongoTemplate) {
        var requests = mongoTemplate.getCollection("schedulingRequests");
        requests.dropIndex(SR_MANAGE_TOKEN);
        requests.dropIndex(SR_LINEAGE);
        requests.dropIndex(SR_RECOVERY);
        requests.dropIndex(SR_TEARDOWN);
    }
}
