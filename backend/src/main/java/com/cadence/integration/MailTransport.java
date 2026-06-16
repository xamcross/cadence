package com.cadence.integration;

/**
 * The thin transport SPI (F22, contract D) — the actual SMTP send, isolated so it is swappable and
 * testable. {@code SmtpMailTransport} (prod, JavaMailSender-backed) / {@code RecordingMailTransport}
 * (test) are the two impls. {@link SmtpEmailSender} delegates here; the provider swap is replacing this
 * bean with zero calling-service edits (SC-007).
 */
public interface MailTransport {

    /** Transmit a fully-rendered message; classify the outcome (accepted / transient / permanent). */
    SendOutcome transmit(OutboundEmail message);
}
