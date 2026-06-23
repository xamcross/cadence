package com.cadence.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;

/**
 * F70 — one prospective-member access-interest submission (data-model section "New collection: interestRequests").
 * PII fields {@code name}/{@code email}/{@code organization}/{@code message} are encrypted at rest via the
 * MongoPiiConfig converter; {@code emailHash}/{@code openEmailHash} are keyed HMAC values stored as-is (never the
 * plaintext or ciphertext email) and back the admin lookup + the open-dedup unique index.
 *
 * <p>{@code openEmailHash} mirrors {@code emailHash} ONLY while open (NEW/REVIEWED) and is {@code $unset} on
 * terminal/erasure; with {@code write=NON_NULL} a null is omitted from BSON so a terminal/erased row drops out of
 * the unique partial {@code {workspaceId,openEmailHash}} index (avoids the F01 present-as-null collision footgun).
 * {@code toString()} is PII-free (ids/status/instants only).
 */
@Document(collection = "interestRequests")
public class InterestRequest {

    @Id
    private String id;

    /** Server-resolved from {@code cadence.interest.default-workspace-id} (FR-019); never from submitter input. */
    private String workspaceId;

    /** Submitter-claimed name, encrypted at rest (MongoPiiConfig). */
    private String name;

    /** Submitter-claimed, UNVERIFIED email, encrypted at rest (MongoPiiConfig). */
    private String email;

    /** Keyed HMAC of the email (PiiCrypto.emailHash); stored as-is. Admin lookup + erasure discovery. */
    @Field(value = "emailHash", write = Field.Write.NON_NULL)
    private String emailHash;

    /** Mirrors {@code emailHash} only while open; $unset on terminal/erasure. Backs the open-dedup unique index. */
    @Field(value = "openEmailHash", write = Field.Write.NON_NULL)
    private String openEmailHash;

    /** Optional, encrypted at rest. */
    @Field(value = "organization", write = Field.Write.NON_NULL)
    private String organization;

    /** Optional purpose-limited free text, encrypted at rest. */
    @JsonIgnore
    @Field(value = "message", write = Field.Write.NON_NULL)
    private String message;

    private InterestRequestStatus status = InterestRequestStatus.NEW;

    /** First submission time; retention clock origin. */
    private Instant submittedAt;

    /** Last coalesced resubmit or status change. */
    private Instant updatedAt;

    /** The admin who last transitioned; null until acted on. */
    @Field(value = "lastActorMemberId", write = Field.Write.NON_NULL)
    private String lastActorMemberId;

    /** When the request was last transitioned; null until acted on. */
    @Field(value = "actionedAt", write = Field.Write.NON_NULL)
    private Instant actionedAt;

    /** Back-link to the resulting {@code invitations._id} when INVITED; null otherwise. */
    @Field(value = "invitationId", write = Field.Write.NON_NULL)
    private String invitationId;

    public InterestRequest() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getEmailHash() { return emailHash; }
    public void setEmailHash(String emailHash) { this.emailHash = emailHash; }

    public String getOpenEmailHash() { return openEmailHash; }
    public void setOpenEmailHash(String openEmailHash) { this.openEmailHash = openEmailHash; }

    public String getOrganization() { return organization; }
    public void setOrganization(String organization) { this.organization = organization; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public InterestRequestStatus getStatus() { return status; }
    public void setStatus(InterestRequestStatus status) { this.status = status; }

    public Instant getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(Instant submittedAt) { this.submittedAt = submittedAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public String getLastActorMemberId() { return lastActorMemberId; }
    public void setLastActorMemberId(String lastActorMemberId) { this.lastActorMemberId = lastActorMemberId; }

    public Instant getActionedAt() { return actionedAt; }
    public void setActionedAt(Instant actionedAt) { this.actionedAt = actionedAt; }

    public String getInvitationId() { return invitationId; }
    public void setInvitationId(String invitationId) { this.invitationId = invitationId; }

    /** Ids/status/instants only — NEVER name/email/organization/message (PII discipline, FR-009). */
    @Override
    public String toString() {
        return "InterestRequest{id=" + id + ", workspaceId=" + workspaceId
            + ", status=" + status + ", submittedAt=" + submittedAt + "}";
    }
}
