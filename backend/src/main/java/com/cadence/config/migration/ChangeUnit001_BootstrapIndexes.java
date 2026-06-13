package com.cadence.config.migration;

import com.mongodb.client.model.IndexOptions;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;

@ChangeUnit(id = "001-bootstrap-indexes", order = "001", author = "system")
public class ChangeUnit001_BootstrapIndexes {

    @Execution
    public void execute(MongoTemplate mongoTemplate) {
        mongoTemplate.getCollection("interviews").createIndex(
            new Document("scheduledAt", 1).append("confirmationStatus", 1));

        mongoTemplate.getCollection("candidates").createIndex(
            new Document("workspaceId", 1).append("lastContactAt", 1));

        mongoTemplate.getCollection("feedbackRequests").createIndex(
            new Document("interviewEventId", 1).append("submittedAt", 1));

        mongoTemplate.getCollection("schedulingTokens").createIndex(
            new Document("token", 1),
            new IndexOptions().unique(true));

        mongoTemplate.getCollection("auditLog").createIndex(
            new Document("candidateId", 1).append("occurredAt", -1));

        mongoTemplate.getCollection("schedulerCheckpoints").createIndex(
            new Document("taskName", 1),
            new IndexOptions().unique(true));
    }

    @RollbackExecution
    public void rollback(MongoTemplate mongoTemplate) {
        // Drop only the specific indexes created above — dropIndexes() would destroy
        // indexes from other changesets running on the same collections.
        mongoTemplate.getCollection("interviews").dropIndex(
            new Document("scheduledAt", 1).append("confirmationStatus", 1));
        mongoTemplate.getCollection("candidates").dropIndex(
            new Document("workspaceId", 1).append("lastContactAt", 1));
        mongoTemplate.getCollection("feedbackRequests").dropIndex(
            new Document("interviewEventId", 1).append("submittedAt", 1));
        mongoTemplate.getCollection("schedulingTokens").dropIndex(
            new Document("token", 1));
        mongoTemplate.getCollection("auditLog").dropIndex(
            new Document("candidateId", 1).append("occurredAt", -1));
        mongoTemplate.getCollection("schedulerCheckpoints").dropIndex(
            new Document("taskName", 1));
    }
}
