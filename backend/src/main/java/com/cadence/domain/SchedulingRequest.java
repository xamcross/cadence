package com.cadence.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * One recruiter-initiated single-stage scheduling attempt (F13, data-model §1) — the booking aggregate.
 * Its {@code id} doubles as the calendar {@code bookingRef} (D9). <b>No {@code @Version}</b>: every status
 * transition is a {@code findAndModify} CAS (the F22 {@code EmailDispatch} precedent).
 *
 * <p>Holds ids/instants/enums only EXCEPT {@code locationText} — the recruiter-provided interview
 * location/dial-in, which must survive to the candidate's asynchronous confirm. It is encrypted at rest
 * by the {@code PiiStringConverter} (registered in {@code MongoPiiConfig}), {@code @JsonIgnore} +
 * {@code @Field(write=NON_NULL)}, and excluded from {@code toString()} and every candidate/log/audit
 * output (D2). The raw scheduling token is never stored — only its HMAC {@code tokenHash}.
 */
@Document(collection = "schedulingRequests")
public class SchedulingRequest {

    @Id
    private String id;

    private String workspaceId;
    private String candidateId;
    private String templateId;

    private SchedulingStatus status = SchedulingStatus.PENDING_SELECTION;

    /** HMAC-SHA-256(rawToken, TOKEN_PEPPER). Unique index. The raw token is never persisted. */
    private String tokenHash;

    private Instant sentAt;
    private Instant expiresAt;

    private LocalDate searchRangeStart;
    private LocalDate searchRangeEnd;

    private List<OfferedSlot> offeredSlots = new ArrayList<>();

    /**
     * Recruiter-provided interview location/dial-in. Encrypted at rest (converter-managed); never returned
     * to the candidate, never logged/audited. {@code write=NON_NULL} so a null is omitted from BSON.
     */
    @JsonIgnore
    @Field(value = "locationText", write = Field.Write.NON_NULL)
    private String locationText;

    private String chosenSlotId;
    private Instant bookedAt;
    private String supersededByRequestId;

    private SchedulingOutcomeReason lastOutcomeReason = SchedulingOutcomeReason.NONE;

    // --- F20 Reschedule & Cancellation (data-model §1) ---

    /** INITIAL booking or a RESCHEDULE round; absent on a pre-F20 row → treated as INITIAL. */
    private SchedulingMode mode = SchedulingMode.INITIAL;

    /**
     * Lineage root = the first (INITIAL) booking's id. An INITIAL row leaves this null (null-means-self);
     * a RESCHEDULE round sets it to {@code parent.rootRequestId != null ? parent.rootRequestId : parent.id}
     * so the whole chain shares one root for the cap derivation (research D5).
     */
    private String rootRequestId;

    /** The immediately-preceding booking this round supersedes on forward-commit (RESCHEDULE only). */
    private String parentRequestId;

    /**
     * The reschedule/cancel credential on the currently-BOOKED round (HMAC of a 256-bit token). Rotated on
     * each reschedule forward-commit; cleared via {@code $unset} on cancel/cap/ineligible. Partial-unique
     * {@code {$exists:true}} index. {@code write=NON_NULL} so a null is OMITTED from BSON (the F01
     * present-as-null collision footgun) — two cleared rows never collide. The raw token is never stored.
     */
    @JsonIgnore
    @Field(value = "manageTokenHash", write = Field.Write.NON_NULL)
    private String manageTokenHash;

    /** Set by a recruiter-initiated reschedule (D10) — drives the derived "Reschedule in progress" status. */
    private Instant rescheduleInvitedAt;

    /** Set on CANCELLED. */
    private Instant cancelledAt;

    /**
     * Set true by erasure (D9) when it CASes a BOOKED booking to CANCELLED synchronously but defers the
     * provider-side event removal to the reaper (keeps {@code wipe()} O(1)/non-blocking, FR-024). The reaper
     * teardown pass clears it. Normal interactive cancel tears down inline and never sets it.
     */
    private boolean calendarTeardownPending;

    private Instant createdAt;
    private Instant updatedAt;

    public SchedulingRequest() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; }

    public String getCandidateId() { return candidateId; }
    public void setCandidateId(String candidateId) { this.candidateId = candidateId; }

    public String getTemplateId() { return templateId; }
    public void setTemplateId(String templateId) { this.templateId = templateId; }

    public SchedulingStatus getStatus() { return status; }
    public void setStatus(SchedulingStatus status) { this.status = status; }

    public String getTokenHash() { return tokenHash; }
    public void setTokenHash(String tokenHash) { this.tokenHash = tokenHash; }

    public Instant getSentAt() { return sentAt; }
    public void setSentAt(Instant sentAt) { this.sentAt = sentAt; }

    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }

    public LocalDate getSearchRangeStart() { return searchRangeStart; }
    public void setSearchRangeStart(LocalDate searchRangeStart) { this.searchRangeStart = searchRangeStart; }

    public LocalDate getSearchRangeEnd() { return searchRangeEnd; }
    public void setSearchRangeEnd(LocalDate searchRangeEnd) { this.searchRangeEnd = searchRangeEnd; }

    public List<OfferedSlot> getOfferedSlots() { return offeredSlots; }
    public void setOfferedSlots(List<OfferedSlot> offeredSlots) { this.offeredSlots = offeredSlots; }

    public String getLocationText() { return locationText; }
    public void setLocationText(String locationText) { this.locationText = locationText; }

    public String getChosenSlotId() { return chosenSlotId; }
    public void setChosenSlotId(String chosenSlotId) { this.chosenSlotId = chosenSlotId; }

    public Instant getBookedAt() { return bookedAt; }
    public void setBookedAt(Instant bookedAt) { this.bookedAt = bookedAt; }

    public String getSupersededByRequestId() { return supersededByRequestId; }
    public void setSupersededByRequestId(String supersededByRequestId) { this.supersededByRequestId = supersededByRequestId; }

    public SchedulingOutcomeReason getLastOutcomeReason() { return lastOutcomeReason; }
    public void setLastOutcomeReason(SchedulingOutcomeReason lastOutcomeReason) { this.lastOutcomeReason = lastOutcomeReason; }

    public SchedulingMode getMode() { return mode; }
    public void setMode(SchedulingMode mode) { this.mode = mode; }

    public String getRootRequestId() { return rootRequestId; }
    public void setRootRequestId(String rootRequestId) { this.rootRequestId = rootRequestId; }

    public String getParentRequestId() { return parentRequestId; }
    public void setParentRequestId(String parentRequestId) { this.parentRequestId = parentRequestId; }

    public String getManageTokenHash() { return manageTokenHash; }
    public void setManageTokenHash(String manageTokenHash) { this.manageTokenHash = manageTokenHash; }

    public Instant getRescheduleInvitedAt() { return rescheduleInvitedAt; }
    public void setRescheduleInvitedAt(Instant rescheduleInvitedAt) { this.rescheduleInvitedAt = rescheduleInvitedAt; }

    public Instant getCancelledAt() { return cancelledAt; }
    public void setCancelledAt(Instant cancelledAt) { this.cancelledAt = cancelledAt; }

    public boolean isCalendarTeardownPending() { return calendarTeardownPending; }
    public void setCalendarTeardownPending(boolean calendarTeardownPending) { this.calendarTeardownPending = calendarTeardownPending; }

    /** Resolve the lineage root id (an INITIAL row roots on itself). */
    public String resolveRootRequestId() { return rootRequestId != null ? rootRequestId : id; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    /** Deliberately omits {@code locationText}, {@code tokenHash}, and {@code manageTokenHash} — never leak via logs (D2). */
    @Override
    public String toString() {
        return "SchedulingRequest{id=" + id + ", workspaceId=" + workspaceId + ", candidateId=" + candidateId
            + ", templateId=" + templateId + ", status=" + status + ", mode=" + mode + ", slots=" + offeredSlots.size() + "}";
    }
}
