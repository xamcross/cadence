package com.cadence.config.migration;

import com.mongodb.client.MongoCollection;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * F50 Core Dashboard indexes (data-model section C). Order "021" -- derived off the highest APPLIED ChangeUnit
 * ("020"), NOT the branch number. Never rename after applied. Native createIndex + targeted dropIndex rollback
 * (CLAUDE.md Mongock rules; never dropIndexes()). Pure ASCII (the F30 NUL/binary lesson).
 *
 * <p>Two compound indexes on the existing {@code schedulingRequests} -- no new collection:
 * <ul>
 *   <li>{@code {workspaceId, status, bookedAt}} -- backs the time-to-schedule window scan (workspace + BOOKED +
 *       bookedAt range).</li>
 *   <li>{@code {workspaceId, status, bookedStartAt}} -- backs the no-show window scan, workspace-leading. The
 *       existing F23 {@code {status, bookedStartAt}} index (ChangeUnit014) is global (not workspace-scoped), so
 *       a per-workspace dashboard scan needs this workspace-leading variant; the key patterns differ (leading
 *       field), so MongoDB accepts both.</li>
 * </ul>
 * The silence-list scan reuses the existing {@code candidates {workspaceId, lastContactAt}} index
 * (ChangeUnit001) via SlaNudgeService -- no new candidate index.
 */
@ChangeUnit(id = "021-dashboard-indexes", order = "021", author = "system")
public class ChangeUnit021_DashboardIndexes {

    private static final Document VELOCITY_KEY =
        new Document("workspaceId", 1).append("status", 1).append("bookedAt", 1);
    private static final Document NOSHOW_KEY =
        new Document("workspaceId", 1).append("status", 1).append("bookedStartAt", 1);

    @Execution
    public void execute(MongoTemplate mongoTemplate) {
        MongoCollection<Document> scheduling = mongoTemplate.getCollection("schedulingRequests");
        scheduling.createIndex(VELOCITY_KEY);
        scheduling.createIndex(NOSHOW_KEY);
    }

    @RollbackExecution
    public void rollback(MongoTemplate mongoTemplate) {
        MongoCollection<Document> scheduling = mongoTemplate.getCollection("schedulingRequests");
        scheduling.dropIndex(VELOCITY_KEY);
        scheduling.dropIndex(NOSHOW_KEY);
    }
}
