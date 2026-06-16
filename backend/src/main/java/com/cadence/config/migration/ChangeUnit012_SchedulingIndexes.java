package com.cadence.config.migration;

import com.mongodb.client.model.IndexOptions;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * F13 single-stage scheduling indexes (data-model §7). Order "012" — derived off the highest APPLIED
 * ChangeUnit ("011"), NOT the branch number. Never rename after applied. Native createIndex + targeted
 * dropIndex rollback (CLAUDE.md Mongock rules; never dropIndexes()).
 *
 * <p>{@code schedulingRequests}: UNIQUE {@code {tokenHash}} (token lookup + the F00.1-reserved
 * scheduling-token uniqueness, D12); {@code {workspaceId,candidateId,createdAt:-1}} (per-candidate status
 * read); {@code {status,expiresAt}} (reaper expiry scan); {@code {status,updatedAt}} (reaper stuck-BOOKING
 * scan).
 *
 * <p>{@code interviewSlotClaims}: UNIQUE PARTIAL {@code {workspaceId,memberId,startAt}} over
 * {@code status == ACTIVE} (the cross-request double-booking guard, D3 — a RELEASED claim leaves the index
 * and stops colliding); {@code {workspaceId,schedulingRequestId}} (release-set lookup).
 */
@ChangeUnit(id = "012-scheduling-indexes", order = "012", author = "system")
public class ChangeUnit012_SchedulingIndexes {

    private static final Document SR_TOKEN = new Document("tokenHash", 1);
    private static final Document SR_HISTORY =
        new Document("workspaceId", 1).append("candidateId", 1).append("createdAt", -1);
    private static final Document SR_EXPIRY = new Document("status", 1).append("expiresAt", 1);
    private static final Document SR_STUCK = new Document("status", 1).append("updatedAt", 1);

    private static final Document CLAIM_KEY =
        new Document("workspaceId", 1).append("memberId", 1).append("startAt", 1);
    private static final Document CLAIM_RELEASE =
        new Document("workspaceId", 1).append("schedulingRequestId", 1);

    @Execution
    public void execute(MongoTemplate mongoTemplate) {
        var requests = mongoTemplate.getCollection("schedulingRequests");
        requests.createIndex(SR_TOKEN, new IndexOptions().unique(true));
        requests.createIndex(SR_HISTORY);
        requests.createIndex(SR_EXPIRY);
        requests.createIndex(SR_STUCK);

        var claims = mongoTemplate.getCollection("interviewSlotClaims");
        claims.createIndex(CLAIM_KEY, new IndexOptions().unique(true)
            .partialFilterExpression(new Document("status", "ACTIVE")));
        claims.createIndex(CLAIM_RELEASE);
    }

    @RollbackExecution
    public void rollback(MongoTemplate mongoTemplate) {
        var requests = mongoTemplate.getCollection("schedulingRequests");
        requests.dropIndex(SR_TOKEN);
        requests.dropIndex(SR_HISTORY);
        requests.dropIndex(SR_EXPIRY);
        requests.dropIndex(SR_STUCK);

        var claims = mongoTemplate.getCollection("interviewSlotClaims");
        claims.dropIndex(CLAIM_KEY);
        claims.dropIndex(CLAIM_RELEASE);
    }
}
