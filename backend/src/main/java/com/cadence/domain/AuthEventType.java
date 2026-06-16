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
    CALENDAR_EVENT_CLEANUP_INCOMPLETE,
    // F12 Interview Template & Rule Engine (append-only — never reorder). Ids only, no PII/name (D10).
    INTERVIEW_TEMPLATE_CREATED,
    INTERVIEW_TEMPLATE_UPDATED,
    INTERVIEW_TEMPLATE_RETIRED,
    INTERVIEW_TEMPLATE_COMPUTE_REFUSED,
    // F21 Email Template Library (append-only — never reorder). Ids + type/stage/kind only, no content/PII (D9).
    // EMAIL_TEMPLATE_EDITED carries the change-kind (create_override/edit/tone_apply/variant_edit) in `outcome`.
    EMAIL_TEMPLATE_EDITED,
    EMAIL_TEMPLATE_LOCKED,
    EMAIL_TEMPLATE_UNLOCKED,
    EMAIL_TEMPLATE_RESET,
    // F22 Email Delivery Channel (append-only — never reorder). Ids + type/reason literal only,
    // value-free (no recipient/subject/body/merge PII — D10).
    EMAIL_DISPATCH_SENT,
    EMAIL_DISPATCH_REFUSED,
    EMAIL_DISPATCH_FAILED,
    EMAIL_DISPATCH_BOUNCED,
    // F13 Single-Stage Scheduling (append-only — never reorder). Ids + outcome literal only,
    // value-free (no candidate name, no token value, no location — data-model §6).
    SCHEDULING_LINK_SENT,
    SCHEDULING_BOOKED,
    SCHEDULING_ROLLED_BACK,
    SCHEDULING_CLEANUP_INCOMPLETE,
    SCHEDULING_LINK_EXPIRED,
    SCHEDULING_REFUSED,
    // F20 Reschedule & Cancellation (append-only — never reorder). Value-free outcome literal only.
    SCHEDULING_RESCHEDULED,
    SCHEDULING_CANCELLED,
    SCHEDULING_CAP_REACHED,
    // F23 No-Show Defense (append-only — never reorder). Value-free outcome literal only. The recruiter
    // slot RELEASE reuses SCHEDULING_CANCELLED (it is a recruiter-initiated cancel).
    NOSHOW_CONFIRMATION_REQUESTED,
    NOSHOW_ATTENDANCE_CONFIRMED,
    NOSHOW_UNCONFIRMED_ESCALATED
}
