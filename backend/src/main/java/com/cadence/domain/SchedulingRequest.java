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

    // --- F23 No-Show Defense (data-model §1) — confirmation-attendance lifecycle layered over BOOKED ---

    /**
     * Denormalized interview start instant (D2), set in the BOOKING->BOOKED CAS (covers initial + reschedule
     * rounds). The cascade's ONLY queryable start field. NOT the source of truth for candidate-facing
     * "is it past" checks — those use the in-memory chosen {@code OfferedSlot.start} (so the two never diverge
     * after a reschedule). Null on a non-BOOKED row.
     */
    private Instant bookedStartAt;

    /** Stage-1 stamp: confirmation request dispatched (or attempted, when not contactable). Null => not run. */
    private Instant confirmationRequestedAt;

    /**
     * The F23 confirm credential (HMAC of a 256-bit token) — minted at stage 1 ONLY when an email is sent.
     * Partial-unique {@code {$exists:true}} index; {@code write=NON_NULL} so a null is OMITTED from BSON
     * (the F01 present-as-null collision footgun) — two cleared rows never collide. Cleared via {@code $unset}
     * on erasure. Distinct from {@code tokenHash} (slot-pick) and {@code manageTokenHash} (F20). Raw never stored.
     */
    @JsonIgnore
    @Field(value = "confirmTokenHash", write = Field.Write.NON_NULL)
    private String confirmTokenHash;

    /**
     * Internal, value-free (D5): true when stage 1 found the candidate not contactable (no email, no confirm
     * token). NEVER a differential recruiter signal (the escalation is the same coarse INTERVIEW_UNCONFIRMED);
     * {@code @JsonIgnore} so it can never serialize onto a recruiter/F50 DTO.
     */
    @JsonIgnore
    private boolean confirmationNotRequestable;

    /** Set by the candidate confirm action (exactly once via CAS). Excludes the booking from escalation/no-show. */
    private Instant candidateConfirmedAt;

    /** Stage-2 stamp: the booking was escalated to the recruiter as unconfirmed (drives the observable state). */
    private Instant escalatedAt;

    /** Stage-3 stamp: the interview start was reached unconfirmed — the MVP no-show signal for F50 (FR-016). */
    private Instant noShowAt;

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

    public Instant getBookedStartAt() { return bookedStartAt; }
    public void setBookedStartAt(Instant bookedStartAt) { this.bookedStartAt = bookedStartAt; }

    public Instant getConfirmationRequestedAt() { return confirmationRequestedAt; }
    public void setConfirmationRequestedAt(Instant confirmationRequestedAt) { this.confirmationRequestedAt = confirmationRequestedAt; }

    public String getConfirmTokenHash() { return confirmTokenHash; }
    public void setConfirmTokenHash(String confirmTokenHash) { this.confirmTokenHash = confirmTokenHash; }

    public boolean isConfirmationNotRequestable() { return confirmationNotRequestable; }
    public void setConfirmationNotRequestable(boolean confirmationNotRequestable) { this.confirmationNotRequestable = confirmationNotRequestable; }

    public Instant getCandidateConfirmedAt() { return candidateConfirmedAt; }
    public void setCandidateConfirmedAt(Instant candidateConfirmedAt) { this.candidateConfirmedAt = candidateConfirmedAt; }

    public Instant getEscalatedAt() { return escalatedAt; }
    public void setEscalatedAt(Instant escalatedAt) { this.escalatedAt = escalatedAt; }

    public Instant getNoShowAt() { return noShowAt; }
    public void setNoShowAt(Instant noShowAt) { this.noShowAt = noShowAt; }

    /** Resolve the lineage root id (an INITIAL row roots on itself). */
    public String resolveRootRequestId() { return rootRequestId != null ? rootRequestId : id; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    /** Deliberately omits {@code locationText}, {@code tokenHash}, {@code manageTokenHash}, and {@code confirmTokenHash} — never leak via logs (D2). */
    @Override
    public String toString() {
        return "SchedulingRequest{id=" + id + ", workspaceId=" + workspaceId + ", candidateId=" + candidateId
            + ", templateId=" + templateId + ", status=" + status + ", mode=" + mode + ", slots=" + offeredSlots.size() + "}";
    }
}
