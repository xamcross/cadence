package com.cadence.domain;

/** Security-relevant authentication events recorded to the append-only auth audit log (FR-023). */
public enum AuthEventType {
    SIGN_IN_SUCCESS,
    SIGN_IN_FAILURE,
    SIGN_OUT,
    INVITATION_ISSUED,
    INVITATION_CONSUMED,
    MEMBER_DEACTIVATED,
    PASSWORD_RESET_REQUESTED,
    PASSWORD_RESET_COMPLETED,
    // F02 RBAC
    ROLE_CHANGED,
    AUTHORIZATION_DENIED,
    // F03 Workspace Setup & Configuration
    WORKSPACE_CONFIGURED,
    WORKSPACE_CONFIG_CHANGED,
    // F01.1 Calendar OAuth Token Store (append-only — never reorder)
    CALENDAR_CONNECTED,
    CALENDAR_DISCONNECTED,
    CALENDAR_RECONNECT_REQUIRED
}
