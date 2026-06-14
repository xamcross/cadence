package com.cadence.config.migration;

import com.mongodb.client.model.IndexOptions;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.util.concurrent.TimeUnit;

/**
 * F01 auth indexes (research D6 / data-model). Order "002" — never rename after applied.
 * Native createIndex + targeted dropIndex rollback (CLAUDE.md Mongock rules). TTL indexes let
 * expired sessions/invitations/resets self-clean with no scheduler (constitution §IV).
 */
@ChangeUnit(id = "002-auth-indexes", order = "002", author = "system")
public class ChangeUnit002_AuthIndexes {

    @Execution
    public void execute(MongoTemplate mongoTemplate) {
        // members: unique per-workspace email (on the keyed hash, not the ciphertext)
        mongoTemplate.getCollection("members").createIndex(
            new Document("workspaceId", 1).append("emailHash", 1),
            new IndexOptions().unique(true));

        // members: SSO identity uniqueness — partial so password-only members (no ssoProvider) are exempt
        mongoTemplate.getCollection("members").createIndex(
            new Document("ssoProvider", 1).append("ssoSubject", 1),
            new IndexOptions().unique(true)
                .partialFilterExpression(new Document("ssoProvider", new Document("$exists", true))));

        // sessions: revoke-all-by-member + TTL purge of expired sessions
        mongoTemplate.getCollection("sessions").createIndex(new Document("memberId", 1));
        mongoTemplate.getCollection("sessions").createIndex(
            new Document("absoluteExpiresAt", 1),
            new IndexOptions().expireAfter(0L, TimeUnit.SECONDS));

        // invitations: unique token lookup + TTL expiry
        mongoTemplate.getCollection("invitations").createIndex(
            new Document("tokenHash", 1), new IndexOptions().unique(true));
        mongoTemplate.getCollection("invitations").createIndex(
            new Document("expiresAt", 1), new IndexOptions().expireAfter(0L, TimeUnit.SECONDS));

        // passwordResets: unique token lookup + TTL expiry
        mongoTemplate.getCollection("passwordResets").createIndex(
            new Document("tokenHash", 1), new IndexOptions().unique(true));
        mongoTemplate.getCollection("passwordResets").createIndex(
            new Document("expiresAt", 1), new IndexOptions().expireAfter(0L, TimeUnit.SECONDS));

        // authAuditLog: member-keyed audit queries
        mongoTemplate.getCollection("authAuditLog").createIndex(
            new Document("memberId", 1).append("occurredAt", -1));
    }

    @RollbackExecution
    public void rollback(MongoTemplate mongoTemplate) {
        mongoTemplate.getCollection("members").dropIndex(
            new Document("workspaceId", 1).append("emailHash", 1));
        mongoTemplate.getCollection("members").dropIndex(
            new Document("ssoProvider", 1).append("ssoSubject", 1));
        mongoTemplate.getCollection("sessions").dropIndex(new Document("memberId", 1));
        mongoTemplate.getCollection("sessions").dropIndex(new Document("absoluteExpiresAt", 1));
        mongoTemplate.getCollection("invitations").dropIndex(new Document("tokenHash", 1));
        mongoTemplate.getCollection("invitations").dropIndex(new Document("expiresAt", 1));
        mongoTemplate.getCollection("passwordResets").dropIndex(new Document("tokenHash", 1));
        mongoTemplate.getCollection("passwordResets").dropIndex(new Document("expiresAt", 1));
        mongoTemplate.getCollection("authAuditLog").dropIndex(
            new Document("memberId", 1).append("occurredAt", -1));
    }
}
