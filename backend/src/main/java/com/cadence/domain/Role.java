package com.cadence.domain;

/**
 * Workspace member roles (constitution §VIII RBAC). F01 attaches the role to the session;
 * per-endpoint enforcement of the role is delivered by F02 (RBAC).
 */
public enum Role {
    ADMIN,
    RECRUITER,
    HIRING_MANAGER,
    INTERVIEWER,
    READ_ONLY
}
