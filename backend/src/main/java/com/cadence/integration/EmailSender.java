package com.cadence.integration;

import java.util.Map;

/**
 * The constitution-named email integration boundary (the swappable provider seam, FR-003). Widened in
 * F22 with {@link #send(OutboundEmail)} for pre-rendered candidate messages — the legacy
 * {@link #sendEmail} (F01 member/operational mail) and {@link #sendSystemAlert} (F00.2 ops alert) are
 * preserved so making the transport real takes zero call-site edits. The {@code SmtpEmailSender}
 * ({@code @Primary}) delegates actual transmission to a {@link MailTransport} SPI; swapping the provider
 * = replace that bean (SC-007).
 */
public interface EmailSender {

    /** Member/operational mail (F01): resolve the member address + render an operational template. */
    void sendEmail(String toInternalId, String templateId, Map<String, String> mergeFields);

    /** Scheduler dead-letter / ops alert (F00.2) — sent to the configured ops address. */
    void sendSystemAlert(String taskName, String errorSummary);

    /** Pre-rendered candidate message (F22) — the candidate dispatch path supplies a built OutboundEmail. */
    SendOutcome send(OutboundEmail message);
}
