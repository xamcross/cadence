package com.cadence.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * A per-workspace override of one {@link EmailMessageType}, optionally scoped to one F12 interview stage
 * (F21, data-model §1). An un-overridden type has NO document and renders the built-in default (D1).
 *
 * <p>{@code stageKey} is NEVER null (D2): the literal {@code "BASE"} for the base override, or an F12
 * interview-template id for a per-stage variant — a non-null discriminator so one plain unique index
 * separates base from every variant (avoids the F01 partial-index footgun).
 *
 * <p>Holds only recruiter-authored content + internal ids — NO candidate PII, NO secret — so it needs
 * no encryption converter (asserted by a raw-driver test). {@link #toString()} omits {@code subject}
 * and {@code body} (authoring content is never leaked to logs — FR-019).
 */
@Document(collection = "emailTemplates")
public class EmailTemplate {

    /** The reserved base-template sentinel for {@code stageKey} (D2). */
    public static final String BASE = "BASE";

    @Id
    private String id;

    private String workspaceId;
    private EmailMessageType messageType;
    /** "BASE" for the base override, else an F12 interviewTemplates._id for a variant. NEVER null (D2). */
    private String stageKey = BASE;

    /** Plain text + {{tokens}}. Authoring content — never logged/audited. */
    private String subject;
    /** Plain text + {{tokens}}. Authoring content — never logged/audited. */
    private String body;

    private boolean locked;

    @Version
    private Long version;

    private String createdByMemberId;
    private String updatedByMemberId;
    private Instant createdAt;
    private Instant updatedAt;

    public EmailTemplate() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; }

    public EmailMessageType getMessageType() { return messageType; }
    public void setMessageType(EmailMessageType messageType) { this.messageType = messageType; }

    public String getStageKey() { return stageKey; }
    public void setStageKey(String stageKey) { this.stageKey = stageKey; }

    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }

    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }

    public boolean isLocked() { return locked; }
    public void setLocked(boolean locked) { this.locked = locked; }

    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }

    public String getCreatedByMemberId() { return createdByMemberId; }
    public void setCreatedByMemberId(String createdByMemberId) { this.createdByMemberId = createdByMemberId; }

    public String getUpdatedByMemberId() { return updatedByMemberId; }
    public void setUpdatedByMemberId(String updatedByMemberId) { this.updatedByMemberId = updatedByMemberId; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public boolean isBase() { return BASE.equals(stageKey); }

    /** Deliberately OMITS subject and body (authoring content — never leak via logs/toString). */
    @Override
    public String toString() {
        return "EmailTemplate{id=" + id + ", workspaceId=" + workspaceId + ", messageType=" + messageType
            + ", stageKey=" + stageKey + ", locked=" + locked + ", version=" + version + "}";
    }
}
