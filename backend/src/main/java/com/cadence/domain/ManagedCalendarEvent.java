package com.cadence.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * One Cadence-created interview event projected onto one participant's calendar (F10, research D6/D14).
 * The durable handle for update/delete, the idempotency claim, and the rollback/reconciliation record.
 *
 * <p>Holds ONLY references + instants — NO event content (title/location/attendees) and NO token/secret —
 * so it needs no encryption converter (asserted by a raw-driver test). Uniquely keyed by
 * {@code (workspaceId, bookingRef, memberId, provider)} (Mongock 007 unique index).
 */
@Document(collection = "managedCalendarEvents")
public class ManagedCalendarEvent {

    @Id
    private String id;

    private String workspaceId;
    private String bookingRef;
    private String memberId;
    private CalendarProvider provider;
    /** Opaque provider event id (Google: the deterministic client-supplied id). Not PII, not a secret. */
    private String providerEventId;
    private EventStatus status;
    private Instant startAt;
    private Instant endAt;
    private Instant createdAt;
    private Instant updatedAt;

    public ManagedCalendarEvent() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; }
    public String getBookingRef() { return bookingRef; }
    public void setBookingRef(String bookingRef) { this.bookingRef = bookingRef; }
    public String getMemberId() { return memberId; }
    public void setMemberId(String memberId) { this.memberId = memberId; }
    public CalendarProvider getProvider() { return provider; }
    public void setProvider(CalendarProvider provider) { this.provider = provider; }
    public String getProviderEventId() { return providerEventId; }
    public void setProviderEventId(String providerEventId) { this.providerEventId = providerEventId; }
    public EventStatus getStatus() { return status; }
    public void setStatus(EventStatus status) { this.status = status; }
    public Instant getStartAt() { return startAt; }
    public void setStartAt(Instant startAt) { this.startAt = startAt; }
    public Instant getEndAt() { return endAt; }
    public void setEndAt(Instant endAt) { this.endAt = endAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    /** Content-free by construction — but explicit so a future field addition can't silently leak. */
    @Override
    public String toString() {
        return "ManagedCalendarEvent{id=" + id + ", workspaceId=" + workspaceId + ", bookingRef=" + bookingRef
            + ", memberId=" + memberId + ", provider=" + provider + ", providerEventId=" + providerEventId
            + ", status=" + status + "}";
    }
}
