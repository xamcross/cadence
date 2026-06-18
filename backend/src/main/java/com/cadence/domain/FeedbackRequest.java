package com.cadence.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;

/**
 * F32 — the ask for one interviewer to score one interview occurrence, and (on submission) the captured
 * scorecard, in one record (data-model section 1). The ONLY PII at rest is the encrypted
 * {@code scorecardPayload}; everything else is ids/enums/instants + the hashed token.
 *
 * <p>{@code tokenHash} = HMAC of the write-only scorecard token (raw never persisted); a partial-unique
 * {@code {$exists:true}} index backs the inbound lookup; {@code write=NON_NULL} so a null is omitted from BSON
 * (avoids the F01 present-as-null collision). {@code scorecardPayload} is converter-encrypted (MongoPiiConfig) —
 * cleared with {@code $set null} on erasure (NEVER {@code $unset} — the F03 ClassCastException trap).
 */
@Document(collection = "feedbackRequests")
public class FeedbackRequest {

    @Id
    private String id;

    private String workspaceId;
    private String candidateId;

    /** = SchedulingRequest.id (the booking ref / interview occurrence). */
    private String interviewEventId;

    /** The interviewer (Member) who must submit; the request-email recipient. */
    private String interviewerMemberId;

    private FeedbackRequestStatus status = FeedbackRequestStatus.PENDING;

    /** HMAC of the write-only scorecard token. Partial-unique index; backs the inbound lookup. */
    @JsonIgnore
    @Field(value = "tokenHash", write = Field.Write.NON_NULL)
    private String tokenHash;

    /**
     * The scorecard token, stored REVERSIBLY-ENCRYPTED at rest (the F30 statusToken dual-store precedent):
     * the escalating reminders must re-send the SAME link, which an HMAC cannot recover — so the token is
     * encrypted (converter-managed, MongoPiiConfig), not plaintext. {@code @JsonIgnore}; never logged; cleared
     * with {@code $set null} on erasure (NEVER {@code $unset} — the converter ClassCastException trap).
     */
    @JsonIgnore
    @Field(value = "token", write = Field.Write.NON_NULL)
    private String token;

    private Instant expiresAt;

    /** 0 at generation; CAS-incremented per reminder (L1/L2/L3) — the per-{request,level} guard. */
    private int reminderLevelSent;

    /** When the next reminder is due; null once maxReminders reached or terminal. Backs the reminder scan. */
    private Instant nextReminderDueAt;

    private Instant lastReminderAt;

    /**
     * Encrypted JSON of the submitted scorecard {recommendation, ratings[], comment} (converter-managed —
     * the F13 locationText precedent). Null until submitted; the ONLY PII at rest.
     */
    @JsonIgnore
    @Field(value = "scorecardPayload", write = Field.Write.NON_NULL)
    private String scorecardPayload;

    /** Set on submission; null = pending (the {interviewEventId, submittedAt} index semantics). */
    private Instant submittedAt;

    private Instant createdAt;
    private Instant updatedAt;

    public FeedbackRequest() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; }

    public String getCandidateId() { return candidateId; }
    public void setCandidateId(String candidateId) { this.candidateId = candidateId; }

    public String getInterviewEventId() { return interviewEventId; }
    public void setInterviewEventId(String interviewEventId) { this.interviewEventId = interviewEventId; }

    public String getInterviewerMemberId() { return interviewerMemberId; }
    public void setInterviewerMemberId(String interviewerMemberId) { this.interviewerMemberId = interviewerMemberId; }

    public FeedbackRequestStatus getStatus() { return status; }
    public void setStatus(FeedbackRequestStatus status) { this.status = status; }

    public String getTokenHash() { return tokenHash; }
    public void setTokenHash(String tokenHash) { this.tokenHash = tokenHash; }

    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }

    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }

    public int getReminderLevelSent() { return reminderLevelSent; }
    public void setReminderLevelSent(int reminderLevelSent) { this.reminderLevelSent = reminderLevelSent; }

    public Instant getNextReminderDueAt() { return nextReminderDueAt; }
    public void setNextReminderDueAt(Instant nextReminderDueAt) { this.nextReminderDueAt = nextReminderDueAt; }

    public Instant getLastReminderAt() { return lastReminderAt; }
    public void setLastReminderAt(Instant lastReminderAt) { this.lastReminderAt = lastReminderAt; }

    public String getScorecardPayload() { return scorecardPayload; }
    public void setScorecardPayload(String scorecardPayload) { this.scorecardPayload = scorecardPayload; }

    public Instant getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(Instant submittedAt) { this.submittedAt = submittedAt; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    /** Ids/status/instants only — NEVER the tokenHash or scorecardPayload (PII discipline, FR-028). */
    @Override
    public String toString() {
        return "FeedbackRequest{id=" + id + ", workspaceId=" + workspaceId
            + ", interviewEventId=" + interviewEventId + ", interviewerMemberId=" + interviewerMemberId
            + ", status=" + status + ", reminderLevelSent=" + reminderLevelSent + "}";
    }
}
