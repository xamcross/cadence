package com.cadence.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;

/**
 * One outbox document per outbound ATS write-back activity (F40, data-model section 2).
 *
 * <p><b>No PII at rest</b>: candidate internal ObjectId hex + ids/instants/enums/opaque refs only.
 * Un-encrypted by design, like {@code emailDispatches}/{@code managedCalendarEvents}.
 *
 * <p><b>No {@code @Version}</b>: {@code @Version} engages only via {@code MongoRepository.save(...)}
 * and is silently ignored by {@code findAndModify}, and EVERY status transition here is a raw
 * {@code findAndModify} CAS. The unique {@code {workspaceId,idempotencyKey}} index is the durable
 * exactly-once guarantee; the CAS claim is the concurrency guarantee (the F22 outbox precedent).
 * {@code updatedAt} is set on every transition (the reaper's staleness basis).
 *
 * <p>{@code toString()} omits any candidate-resolvable value (ids/enums/instants only).
 */
@Document(collection = "atsWriteBacks")
public class AtsWriteBack {

    @Id
    private String id;

    private String workspaceId;

    /** Internal candidate ObjectId hex only (non-PII). */
    private String candidateId;

    /** The external application id the activity targets. */
    private String atsExternalRef;

    private AtsWriteBackType type;

    /** Length-prefixed sha256 of {@code {workspaceId,candidateId,type,eventInstantMillis}}; unique with workspaceId. */
    private String idempotencyKey;

    private AtsWriteBackStatus status = AtsWriteBackStatus.PENDING;

    /** The originating scheduling event instant (drives the note text; the workspace zone is applied at render). */
    private Instant eventAt;

    /** Backoff gate for retries. */
    private Instant nextAttemptAt;

    /** Incremented per claim; vs retry-max-attempts. */
    private int attemptCount;

    /** Opaque provider id on DELIVERED (best-effort; nullable). */
    @Field(value = "providerActivityRef", write = Field.Write.NON_NULL)
    private String providerActivityRef;

    /** Value-free category only. */
    private String lastOutcomeCategory;

    private Instant createdAt;
    private Instant updatedAt;

    public AtsWriteBack() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; }

    public String getCandidateId() { return candidateId; }
    public void setCandidateId(String candidateId) { this.candidateId = candidateId; }

    public String getAtsExternalRef() { return atsExternalRef; }
    public void setAtsExternalRef(String atsExternalRef) { this.atsExternalRef = atsExternalRef; }

    public AtsWriteBackType getType() { return type; }
    public void setType(AtsWriteBackType type) { this.type = type; }

    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }

    public AtsWriteBackStatus getStatus() { return status; }
    public void setStatus(AtsWriteBackStatus status) { this.status = status; }

    public Instant getEventAt() { return eventAt; }
    public void setEventAt(Instant eventAt) { this.eventAt = eventAt; }

    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public void setNextAttemptAt(Instant nextAttemptAt) { this.nextAttemptAt = nextAttemptAt; }

    public int getAttemptCount() { return attemptCount; }
    public void setAttemptCount(int attemptCount) { this.attemptCount = attemptCount; }

    public String getProviderActivityRef() { return providerActivityRef; }
    public void setProviderActivityRef(String providerActivityRef) { this.providerActivityRef = providerActivityRef; }

    public String getLastOutcomeCategory() { return lastOutcomeCategory; }
    public void setLastOutcomeCategory(String lastOutcomeCategory) { this.lastOutcomeCategory = lastOutcomeCategory; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    /** Ids + enums + instants only - no candidate-resolvable value. */
    @Override
    public String toString() {
        return "AtsWriteBack{id=" + id + ", workspaceId=" + workspaceId + ", candidateId=" + candidateId
            + ", atsExternalRef=" + atsExternalRef + ", type=" + type + ", status=" + status
            + ", attemptCount=" + attemptCount + ", eventAt=" + eventAt + ", nextAttemptAt=" + nextAttemptAt
            + ", lastOutcomeCategory=" + lastOutcomeCategory + "}";
    }
}
