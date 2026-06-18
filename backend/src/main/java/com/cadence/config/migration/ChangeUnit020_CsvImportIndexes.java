package com.cadence.config.migration;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.IndexOptions;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.util.concurrent.TimeUnit;

/**
 * F42 Standalone CSV Import indexes (data-model section 4). Order "020" -- derived off the highest APPLIED
 * ChangeUnit ("019"), NOT the branch number. Never rename after applied. Native createIndex + targeted
 * dropIndex rollback (CLAUDE.md Mongock rules; never dropIndexes()). Pure ASCII (the F30 NUL/binary lesson).
 *
 * <p><b>Indexes on csvImportJobs:</b>
 * <ul>
 *   <li>{@code {workspaceId, _id}} -- workspace-scoped status read (no-oracle 404).</li>
 *   <li>{@code {status, createdAt}} -- the due-sweep finder (ACCEPTED, oldest first).</li>
 *   <li>{@code {status, expiresAt}} -- the TTL/expiry reaper finder.</li>
 *   <li>{@code {status, updatedAt}} -- the orphan-PROCESSING reaper finder.</li>
 * </ul>
 *
 * <p><b>Indexes on csvImportFiles:</b>
 * <ul>
 *   <li>unique {@code {jobId}} -- one blob per job; the read/dispose key.</li>
 *   <li>TTL {@code {createdAt}} expireAfter 48h -- defense-in-depth disposal backstop (the application reaper
 *       is the primary disposer, D8). 48h is comfortably longer than the 24h job-ttl so the index never
 *       deletes a blob still needed by an in-window awaiting-decision job.</li>
 * </ul>
 *
 * <p><b>Indexes on candidates:</b>
 * <ul>
 *   <li>non-unique {@code {workspaceId, origin}} -- later pipeline reads by provenance (F50/F51).</li>
 *   <li>unique PARTIAL {@code {workspaceId, origin, emailHash}} over {@code {emailHash:{$exists:true},
 *       origin:"CSV_IMPORT"}} -- makes the CSV-create path collide deterministically so two concurrent import
 *       jobs of the same new email resolve to exactly one candidate (SC-013). The key is the TRIPLE (not the
 *       2-field {@code {workspaceId,emailHash}}) so it does NOT collide with the existing non-unique
 *       {@code {workspaceId,emailHash}} index (ChangeUnit005) -- MongoDB rejects two indexes with an identical
 *       key pattern. Within the partial set {@code origin} is constant ("CSV_IMPORT"), so uniqueness on the
 *       triple is exactly uniqueness on {@code {workspaceId,emailHash}} for CSV rows. An erased CSV candidate
 *       ({@code emailHash} $unset) drops out of the partial set; a NATIVE/ATS candidate is excluded -- the F01
 *       present-as-null footgun does not apply.</li>
 * </ul>
 */
@ChangeUnit(id = "020-csv-import-indexes", order = "020", author = "system")
public class ChangeUnit020_CsvImportIndexes {

    private static final Document JOB_WORKSPACE_KEY = new Document("workspaceId", 1).append("_id", 1);
    private static final Document JOB_DUE_KEY = new Document("status", 1).append("createdAt", 1);
    private static final Document JOB_EXPIRY_KEY = new Document("status", 1).append("expiresAt", 1);
    private static final Document JOB_ORPHAN_KEY = new Document("status", 1).append("updatedAt", 1);

    private static final Document FILE_JOB_KEY = new Document("jobId", 1);
    private static final Document FILE_TTL_KEY = new Document("createdAt", 1);

    private static final Document CAND_ORIGIN_KEY = new Document("workspaceId", 1).append("origin", 1);
    // Triple key (NOT {workspaceId,emailHash}) so it does not collide with the ChangeUnit005 non-unique index.
    private static final Document CAND_CSV_EMAIL_KEY =
        new Document("workspaceId", 1).append("origin", 1).append("emailHash", 1);

    @Execution
    public void execute(MongoTemplate mongoTemplate) {
        MongoCollection<Document> jobs = mongoTemplate.getCollection("csvImportJobs");
        jobs.createIndex(JOB_WORKSPACE_KEY);
        jobs.createIndex(JOB_DUE_KEY);
        jobs.createIndex(JOB_EXPIRY_KEY);
        jobs.createIndex(JOB_ORPHAN_KEY);

        MongoCollection<Document> files = mongoTemplate.getCollection("csvImportFiles");
        files.createIndex(FILE_JOB_KEY, new IndexOptions().unique(true));
        files.createIndex(FILE_TTL_KEY, new IndexOptions().expireAfter(48L, TimeUnit.HOURS));

        MongoCollection<Document> candidates = mongoTemplate.getCollection("candidates");
        candidates.createIndex(CAND_ORIGIN_KEY);
        candidates.createIndex(CAND_CSV_EMAIL_KEY, new IndexOptions().unique(true)
            .partialFilterExpression(new Document("emailHash", new Document("$exists", true))
                .append("origin", "CSV_IMPORT")));
    }

    @RollbackExecution
    public void rollback(MongoTemplate mongoTemplate) {
        MongoCollection<Document> jobs = mongoTemplate.getCollection("csvImportJobs");
        jobs.dropIndex(JOB_WORKSPACE_KEY);
        jobs.dropIndex(JOB_DUE_KEY);
        jobs.dropIndex(JOB_EXPIRY_KEY);
        jobs.dropIndex(JOB_ORPHAN_KEY);

        MongoCollection<Document> files = mongoTemplate.getCollection("csvImportFiles");
        files.dropIndex(FILE_JOB_KEY);
        files.dropIndex(FILE_TTL_KEY);

        MongoCollection<Document> candidates = mongoTemplate.getCollection("candidates");
        candidates.dropIndex(CAND_ORIGIN_KEY);
        candidates.dropIndex(CAND_CSV_EMAIL_KEY);
    }
}
