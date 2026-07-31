package com.cadence.api;

import com.cadence.domain.AtsConnectionStatus;
import com.cadence.integration.AtsProvider;

import java.time.Instant;

/**
 * F40 ATS API DTOs (contract B). The connect request carries the write-only key; NO response ever carries it
 * — only a derived {@code credentialSet} boolean (FR-003). All response rows are PII-free.
 */
public final class AtsDtos {

    private AtsDtos() {}

    public record ConnectRequest(String apiKey) {}

    public record HealthResponse(AtsProvider provider, AtsConnectionStatus status, boolean credentialSet,
                                 Instant lastVerifiedAt, Instant lastSyncAt, boolean degraded, long deadLetterCount,
                                 boolean pausedForPlan) {}

    public record SyncStatusResponse(Instant lastSyncAt, String lastOutcome, int processed, int created,
                                     int updated, int skipped) {}

    public record DeadLetterEntry(String writeBackId, String candidateId, String type, int attemptCount,
                                  String lastOutcomeCategory, Instant updatedAt) {}
}
