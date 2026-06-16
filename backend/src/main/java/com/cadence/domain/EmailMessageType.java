package com.cadence.domain;

/** The fixed set of outbound message types the F21 template library covers (data-model §1). */
public enum EmailMessageType {
    INVITATION,
    CONFIRMATION,
    REMINDER_24H,
    REMINDER_1H,
    HOLD_UPDATE,
    REJECTION,
    FEEDBACK_REQUEST,
    SLA_HOLDING
}
