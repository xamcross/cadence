package com.cadence.integration;

/**
 * A normalized inbound candidate record (F40, contract A). The connector flattens the provider's wire shape
 * to exactly this minimized field set (FR-029) — name/email/phone + the external reference + the associated
 * job/requisition + the raw stage label. Attachments, recruiter notes, custom fields, and EEOC data are
 * NEVER populated here (data minimization; the parse-discipline control).
 *
 * <p>{@code externalRef} is the authoritative reconcile key (the Greenhouse candidate id for the MVP; the
 * write-back note is addressed to it).
 */
public record AtsCandidateRecord(
    String externalRef,
    String name,
    String email,
    String phone,
    String externalJobId,
    String externalJobTitle,
    String stageLabel) {
}
