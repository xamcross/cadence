package com.cadence.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;

/**
 * 032 -- idempotency ledger for processed Freemius webhook events (FR-009), the F22
 * processedWebhookEvents pattern in a SEPARATE collection (distinct provider id namespace).
 * Insert-then-catch-DuplicateKeyException on the unique {eventId} index. Carries no payload
 * bodies and no PII -- event id, type, license id, outcome only.
 */
@Document(collection = "billingWebhookEvents")
public class BillingWebhookEvent {

    @Id
    private String id;

    /** Opaque Freemius event id -- the unique idempotency key (ChangeUnit024). */
    private String eventId;

    private String type;

    @Field(value = "fsLicenseId", write = Field.Write.NON_NULL)
    private String fsLicenseId;

    private Instant receivedAt;

    private String outcome;

    public BillingWebhookEvent() {}

    public BillingWebhookEvent(String eventId, String type, String fsLicenseId,
                               Instant receivedAt, String outcome) {
        this.eventId = eventId;
        this.type = type;
        this.fsLicenseId = fsLicenseId;
        this.receivedAt = receivedAt;
        this.outcome = outcome;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getFsLicenseId() { return fsLicenseId; }
    public void setFsLicenseId(String fsLicenseId) { this.fsLicenseId = fsLicenseId; }
    public Instant getReceivedAt() { return receivedAt; }
    public void setReceivedAt(Instant receivedAt) { this.receivedAt = receivedAt; }
    public String getOutcome() { return outcome; }
    public void setOutcome(String outcome) { this.outcome = outcome; }
}
