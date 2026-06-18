package com.cadence.service;

import com.cadence.api.AtsExceptions;
import com.cadence.domain.AtsConnection;
import com.cadence.domain.AtsConnectionStatus;
import com.cadence.domain.AtsWriteBack;
import com.cadence.domain.AtsWriteBackStatus;
import com.cadence.integration.AtsApiException;
import com.cadence.integration.AtsConnector;
import com.cadence.integration.AtsProvider;
import com.cadence.repository.AtsConnectionRepository;
import net.logstash.logback.argument.StructuredArguments;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Manages the per-workspace Greenhouse connection (F40, US1). {@code connect} verifies the credential against
 * the provider FIRST (no usable connection on failure — SC-010), then stores the API key encrypted via a
 * targeted {@code $set} (the {@code WorkspaceConfigService.setEmailConfig} precedent — the registered converter
 * encrypts the value at rest). {@code disconnect} clears the key via {@code $set null} (NEVER {@code $unset} —
 * the F03 ClassCastException trap) and cancels pending write-backs. The key is NEVER returned/logged (FR-003).
 */
@Service
public class AtsConnectionService {

    private static final Logger log = LoggerFactory.getLogger(AtsConnectionService.class);

    private final AtsConnectionRepository connections;
    private final MongoTemplate mongo;
    private final AtsWriteBackInvalidator writeBacks;
    private final Map<AtsProvider, AtsConnector> connectors = new EnumMap<>(AtsProvider.class);
    private final Clock clock;

    public AtsConnectionService(AtsConnectionRepository connections, MongoTemplate mongo,
                                AtsWriteBackInvalidator writeBacks, List<AtsConnector> connectorList, Clock clock) {
        this.connections = connections;
        this.mongo = mongo;
        this.writeBacks = writeBacks;
        for (AtsConnector c : connectorList) {
            connectors.put(c.provider(), c);
        }
        this.clock = clock;
    }

    /** Connection health projection (never the key) — drives the Admin status surface (SC-011). */
    public record Health(AtsProvider provider, AtsConnectionStatus status, boolean credentialSet,
                         Instant lastVerifiedAt, Instant lastSyncAt, boolean degraded, long deadLetterCount) {}

    /**
     * Verify the credential against the provider, then store it encrypted and mark the connection CONNECTED.
     * Throws {@link AtsExceptions.InvalidRequestException} (blank key) or {@link AtsExceptions.VerificationFailedException}
     * (rejected key) — neither stores a usable connection and neither echoes the key.
     */
    public Health connect(String workspaceId, AtsProvider provider, String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new AtsExceptions.InvalidRequestException();
        }
        AtsConnector connector = connectors.get(provider);
        if (connector == null) {
            throw new AtsExceptions.InvalidRequestException();
        }
        try {
            connector.verifyCredential(workspaceId, apiKey);
        } catch (AtsApiException e) {
            // Reduce the provider failure to a category — never persist/echo the key or the raw body (FR-003).
            log.warn("ATS connect verification failed {} {}",
                StructuredArguments.kv("workspaceId", workspaceId),
                StructuredArguments.kv("category", e.getCategory()));
            throw new AtsExceptions.VerificationFailedException();
        }
        Instant now = Instant.now(clock);
        // Upsert one connection per workspace; the converter encrypts apiKey on the $set value at rest.
        try {
            mongo.upsert(
                Query.query(Criteria.where("workspaceId").is(workspaceId)),
                new Update()
                    .set("workspaceId", workspaceId)
                    .set("provider", provider)
                    .set("apiKey", apiKey)
                    .set("status", AtsConnectionStatus.CONNECTED)
                    .set("lastVerifiedAt", now)
                    .set("lastErrorCategory", null)
                    .set("updatedAt", now)
                    .setOnInsert("createdAt", now),
                AtsConnection.class);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // Concurrent first-connect raced the unique {workspaceId} index — treat as the idempotent success it
            // is (both verified the same provider). Re-read and return the winner's health (data-model section 5).
        }
        return health(workspaceId);
    }

    /** Disconnect: destroy the key (encrypted-field $set null), stop sync/write-back, cancel pending write-backs. */
    public void disconnect(String workspaceId) {
        Instant now = Instant.now(clock);
        mongo.updateFirst(
            Query.query(Criteria.where("workspaceId").is(workspaceId)),
            new Update()
                .set("apiKey", null) // converter-managed -> $set null, NEVER $unset (F03 trap)
                .set("status", AtsConnectionStatus.DISCONNECTED)
                .set("syncCursor", null)
                .set("updatedAt", now),
            AtsConnection.class);
        writeBacks.cancelPendingForWorkspace(workspaceId);
    }

    /** Current health (never the key). Returns an INTEGRATION_PENDING default if no connection exists. */
    public Health health(String workspaceId) {
        AtsConnection c = connections.findByWorkspaceId(workspaceId).orElse(null);
        long deadLetters = mongo.count(
            Query.query(Criteria.where("workspaceId").is(workspaceId)
                .and("status").is(AtsWriteBackStatus.DEAD_LETTER)),
            AtsWriteBack.class);
        if (c == null) {
            return new Health(AtsProvider.GREENHOUSE, AtsConnectionStatus.INTEGRATION_PENDING, false,
                null, null, false, deadLetters);
        }
        boolean degraded = c.getStatus() == AtsConnectionStatus.ERROR
            || c.getStatus() == AtsConnectionStatus.NEEDS_REAUTH || deadLetters > 0;
        return new Health(c.getProvider(), c.getStatus(), c.isCredentialSet(),
            c.getLastVerifiedAt(), c.getLastSyncAt(), degraded, deadLetters);
    }
}
