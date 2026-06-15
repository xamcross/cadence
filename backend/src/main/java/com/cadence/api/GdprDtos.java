package com.cadence.api;

import java.time.Instant;
import java.util.List;

/**
 * F04 GDPR request/response shapes. Responses carry NO candidate PII — only internal ids, enum-name
 * codes, booleans, and timestamps. Request enum fields are received as String and parsed server-side
 * (-> 400 invalid_basis / invalid_reason) so an unknown value is a clean validation error.
 */
public final class GdprDtos {

    private GdprDtos() {}

    public record BasisRequest(String lawfulBasis) {}

    public record RejectRequest(String reasonCode) {}

    public record StatusResponse(String status) {}

    public record BasisRecordedResponse(boolean basisRecorded) {}

    public record BasisWithdrawnResponse(boolean basisWithdrawn) {}

    public record AuditEntryResponse(String eventType, String outcome, String actorMemberId, Instant occurredAt) {}

    public record AuditLogResponse(List<AuditEntryResponse> entries) {}

    public record ErasureRequestResponse(String id, String candidateId, String status, String reasonCode, Instant createdAt) {}

    public record RequestsResponse(List<ErasureRequestResponse> requests) {}

    public record FlaggedResponse(String candidateId, Instant retentionFlaggedAt, Instant lastContactAt) {}

    public record FlaggedListResponse(List<FlaggedResponse> flagged) {}
}
