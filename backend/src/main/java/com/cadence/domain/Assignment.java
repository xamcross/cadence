package com.cadence.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * Binds a member to a scoped resource (F02 RBAC server-side scoping, FR-023). A Hiring Manager is
 * assigned REQUISITIONs; an Interviewer is assigned INTERVIEWs. All fields are always present (no
 * nullable indexed field), so the F01 partial-index null-collision footgun does not apply here.
 * Non-PII: references members/resources by internal id only.
 */
@Document(collection = "assignments")
public class Assignment {

    @Id
    private String id;

    private String workspaceId;
    private String memberId;
    private ResourceType resourceType;
    private String resourceId;
    private Instant createdAt;
    private String createdByMemberId;

    public Assignment() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; }

    public String getMemberId() { return memberId; }
    public void setMemberId(String memberId) { this.memberId = memberId; }

    public ResourceType getResourceType() { return resourceType; }
    public void setResourceType(ResourceType resourceType) { this.resourceType = resourceType; }

    public String getResourceId() { return resourceId; }
    public void setResourceId(String resourceId) { this.resourceId = resourceId; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public String getCreatedByMemberId() { return createdByMemberId; }
    public void setCreatedByMemberId(String createdByMemberId) { this.createdByMemberId = createdByMemberId; }
}
