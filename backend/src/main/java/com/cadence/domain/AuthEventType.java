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
    CALENDAR_RECONNECT_REQUIRED,
    // F10 Google Calendar event lifecycle (append-only — never reorder). The calendar-op-triggered
    // needs-reconnection occurrence (FR-020) REUSES CALENDAR_RECONNECT_REQUIRED above — no new value.
    CALENDAR_EVENT_CREATED,
    CALENDAR_EVENT_UPDATED,
    CALENDAR_EVENT_DELETED,
    CALENDAR_EVENT_CLEANUP_INCOMPLETE
}
