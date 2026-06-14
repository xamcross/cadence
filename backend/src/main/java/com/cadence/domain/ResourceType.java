package com.cadence.domain;

/**
 * The kind of resource an {@link Assignment} scopes a member to (F02 RBAC server-side scoping).
 * REQUISITION scopes a Hiring Manager; INTERVIEW scopes an Interviewer. The resources themselves
 * are owned by later features (F13/F51 requisitions, F32 interviews); F02 owns the assignment link.
 */
public enum ResourceType {
    REQUISITION,
    INTERVIEW
}
