package com.cadence.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;

/**
 * One outbox document per logical outbound candidate message (F22, data-model §1).
 *
 * <p><b>No PII at rest</b> (FR-013/SC-006): candidate internal ObjectId hex + ids/instants/opaque refs
 * only. The recipient address, candidate name, rendered subject/body, and merge-field values are
 * <em>never</em> stored — they are re-derived at send time from the candidate record (+ the
 * shape-guarded {@code renderContextRef}). Un-encrypted by design, like {@code managedCalendarEvents}/
 * {@code interviewTemplates}/{@code emailTemplates}.
 *
 * <p><b>No {@code @Version}</b> (research D5): {@code @Version} engages only via
 * {@code MongoRepository.save(...)} and is silently ignored by {@code findAndModify}, and EVERY status
 * transition here is a raw {@code findAndModify} CAS. The unique {@code {workspaceId,idempotencyKey}}
 * index is the durable exactly-once guarantee; the CAS claim is the concurrency guarantee (the F00.2/
 * F01.1 precedent — neither uses {@code @Version}). {@code updatedAt} is set on every transition (the
 * reaper's staleness basis).
 *
 * <p>{@code toString()} omits any candidate-resolvable value (ids/status/instants only).
 */
@Document(collection = "emailDispatches")
public class EmailDispatch {

    @Id
    private String id;

    private String workspaceId;

    /** Internal candidate ObjectId hex only — the recipient is decrypted from {@code candidates} at send. */
    private String candidateId;

    private EmailMessageType messageType;

    /** F21 variant key ("BASE" or an interview-template id) used to resolve the template at render. */
    private String stageKey = EmailTemplate.BASE;

    /** {@code sha256(workspaceId|candidateId|messageType|scheduledForEpochMillis)}; unique with workspaceId. */
    private String idempotencyKey;

    private DispatchStatus status = DispatchStatus.PENDING;

    /** Incremented on each claim. */
    private int attemptCount;

    /** Due time; = trigger instant for immediate sends. */
    private Instant scheduledFor;

    /** Backoff gate for retries (<= scheduledFor initially). */
    private Instant nextAttemptAt;

    /** Set on SENT. */
    private Instant sentAt;

    private DispatchOutcomeReason lastOutcomeReason = DispatchOutcomeReason.NONE;

    /** Opaque provider message id (set on send; webhook correlation key). Sparse index until sent. */
    @Field(value = "providerMessageRef", write = Field.Write.NON_NULL)
    private String providerMessageRef;

    /** Optional non-PII reference to a source entity (e.g. bookingId) for deterministic re-render. Shape-guarded. */
    private String renderContextRef;

    private Instant createdAt;
    private Instant updatedAt;

    public EmailDispatch() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; }

    public String getCandidateId() { return candidateId; }
    public void setCandidateId(String candidateId) { this.candidateId = candidateId; }

    public EmailMessageType getMessageType() { return messageType; }
    public void setMessageType(EmailMessageType messageType) { this.messageType = messageType; }

    public String getStageKey() { return stageKey; }
    public void setStageKey(String stageKey) { this.stageKey = stageKey; }

    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }

    public DispatchStatus getStatus() { return status; }
    public void setStatus(DispatchStatus status) { this.status = status; }

    public int getAttemptCount() { return attemptCount; }
    public void setAttemptCount(int attemptCount) { this.attemptCount = attemptCount; }

    public Instant getScheduledFor() { return scheduledFor; }
    public void setScheduledFor(Instant scheduledFor) { this.scheduledFor = scheduledFor; }

    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public void setNextAttemptAt(Instant nextAttemptAt) { this.nextAttemptAt = nextAttemptAt; }

    public Instant getSentAt() { return sentAt; }
    public void setSentAt(Instant sentAt) { this.sentAt = sentAt; }

    public DispatchOutcomeReason getLastOutcomeReason() { return lastOutcomeReason; }
    public void setLastOutcomeReason(DispatchOutcomeReason lastOutcomeReason) { this.lastOutcomeReason = lastOutcomeReason; }

    public String getProviderMessageRef() { return providerMessageRef; }
    public void setProviderMessageRef(String providerMessageRef) { this.providerMessageRef = providerMessageRef; }

    public String getRenderContextRef() { return renderContextRef; }
    public void setRenderContextRef(String renderContextRef) { this.renderContextRef = renderContextRef; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    /** Ids + status + instants only — no candidate-resolvable value (FR-013/SC-006). */
    @Override
    public String toString() {
        return "EmailDispatch{id=" + id + ", workspaceId=" + workspaceId + ", candidateId=" + candidateId
            + ", messageType=" + messageType + ", stageKey=" + stageKey + ", status=" + status
            + ", attemptCount=" + attemptCount + ", lastOutcomeReason=" + lastOutcomeReason
            + ", scheduledFor=" + scheduledFor + ", sentAt=" + sentAt + "}";
    }
}
