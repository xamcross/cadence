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
    PASSWORD_RESET_COMPLETED
}
