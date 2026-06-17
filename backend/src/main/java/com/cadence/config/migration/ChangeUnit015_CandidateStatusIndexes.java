package com.cadence.config.migration;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.IndexOptions;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * F30 Candidate Status Page indexes + a REQUIRED dedupe (data-model section 8). Order "015" -- derived off
 * the highest APPLIED ChangeUnit ("014"), NOT the branch number. Never rename after applied. Native
 * createIndex + targeted dropIndex rollback (CLAUDE.md Mongock rules; never dropIndexes()). No new collection.
 *
 * <p><b>Dedupe FIRST (NOT optional):</b> a pre-F30 workspace can already hold two or more {@code PENDING}
 * {@code erasureRequests} for one candidate ({@code requestErasure} {@code save()}s unconditionally before
 * F30). A unique partial {@code {workspaceId,candidateId}} over {@code status:"PENDING"} would then fail to
 * build -- Mongock aborts on startup -- {@code deploy-all} fails mid-migration. So we group by
 * {@code {workspaceId,candidateId}} where {@code status:"PENDING"}, keep the earliest {@code createdAt}, and
 * flip the rest to {@code RESOLVED_REJECTED} (a terminal status) before creating the index.
 *
 * <p><b>Indexes:</b> partial-unique {@code {statusTokenHash}} on {@code candidates} (the F23
 * {@code confirmTokenHash} precedent -- partial NOT sparse, paired with {@code @Field(write=NON_NULL)}, so two
 * cleared rows never collide; the F01 present-as-null lesson); unique partial {@code {workspaceId,candidateId}}
 * over {@code status:"PENDING"} on {@code erasureRequests} (the candidate-intake idempotency guard, D7).
 */
@ChangeUnit(id = "015-candidate-status-indexes", order = "015", author = "system")
public class ChangeUnit015_CandidateStatusIndexes {

    private static final Document CANDIDATE_STATUS_TOKEN = new Document("statusTokenHash", 1);
    private static final Document ERASURE_PENDING = new Document("workspaceId", 1).append("candidateId", 1);

    @Execution
    public void execute(MongoTemplate mongoTemplate) {
        // (1) REQUIRED dedupe of pre-existing PENDING erasureRequests per (workspaceId,candidateId).
        dedupePendingErasureRequests(mongoTemplate);

        // (2) candidates: partial-unique {statusTokenHash} (resolves an inbound status-page request).
        MongoCollection<Document> candidates = mongoTemplate.getCollection("candidates");
        candidates.createIndex(CANDIDATE_STATUS_TOKEN, new IndexOptions().unique(true)
            .partialFilterExpression(new Document("statusTokenHash", new Document("$exists", true))));

        // (3) erasureRequests: unique partial {workspaceId,candidateId} over PENDING (idempotency guard).
        MongoCollection<Document> erasureRequests = mongoTemplate.getCollection("erasureRequests");
        erasureRequests.createIndex(ERASURE_PENDING, new IndexOptions().unique(true)
            .partialFilterExpression(new Document("status", "PENDING")));
    }

    /** Keep the earliest-created PENDING per (workspaceId,candidateId); flip the rest to RESOLVED_REJECTED. */
    private void dedupePendingErasureRequests(MongoTemplate mongoTemplate) {
        MongoCollection<Document> erasureRequests = mongoTemplate.getCollection("erasureRequests");
        // Group the PENDING rows; for any group with >1, keep the earliest createdAt and reject the rest.
        java.util.Map<String, List<Document>> byKey = new java.util.HashMap<>();
        for (Document row : erasureRequests.find(new Document("status", "PENDING"))) {
            String key = row.getString("workspaceId") + " " + row.getString("candidateId");
            byKey.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
        }
        for (List<Document> group : byKey.values()) {
            if (group.size() <= 1) {
                continue;
            }
            group.sort((a, b) -> {
                Object ca = a.get("createdAt");
                Object cb = b.get("createdAt");
                if (ca instanceof java.util.Date da && cb instanceof java.util.Date db) {
                    return da.compareTo(db);
                }
                return 0;
            });
            // Skip index 0 (the earliest survivor); reject the remainder.
            for (int i = 1; i < group.size(); i++) {
                erasureRequests.updateOne(new Document("_id", group.get(i).get("_id")),
                    new Document("$set", new Document("status", "RESOLVED_REJECTED")));
            }
        }
    }

    @RollbackExecution
    public void rollback(MongoTemplate mongoTemplate) {
        mongoTemplate.getCollection("candidates").dropIndex(CANDIDATE_STATUS_TOKEN);
        mongoTemplate.getCollection("erasureRequests").dropIndex(ERASURE_PENDING);
    }
}
