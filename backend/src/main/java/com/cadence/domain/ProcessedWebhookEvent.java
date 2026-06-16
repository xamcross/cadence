package com.cadence.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * A processed inbound-webhook event id (F22, T040 — SC-009 idempotent intake). The bounce service inserts
 * one row per provider {@code eventId} BEFORE applying the (non-transactional, ordered) flips; the unique
 * {@code eventId} index makes a duplicate/out-of-order replay a {@code DuplicateKeyException} -> no-op, so a
 * hard bounce produces exactly one candidate flag + one notification.
 *
 * <p><b>Value-free</b>: the provider {@code eventId} is an opaque token + an instant only — never the
 * recipient, subject, body, or the provider's free-text reason (D10).
 */
@Document(collection = "processedWebhookEvents")
public class ProcessedWebhookEvent {

    @Id
    private String id;

    /** Opaque provider event id — the unique idempotency key (ChangeUnit011). */
    private String eventId;

    private Instant processedAt;

    public ProcessedWebhookEvent() {}

    public ProcessedWebhookEvent(String eventId, Instant processedAt) {
        this.eventId = eventId;
        this.processedAt = processedAt;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }

    public Instant getProcessedAt() { return processedAt; }
    public void setProcessedAt(Instant processedAt) { this.processedAt = processedAt; }

    @Override
    public String toString() {
        return "ProcessedWebhookEvent{id=" + id + ", eventId=" + eventId + ", processedAt=" + processedAt + "}";
    }
}
