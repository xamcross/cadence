package com.cadence.integration;

/**
 * A fully-rendered outbound message handed to the transport (F22, contract D). Carries the recipient
 * address + rendered subject/body — this is the ONE place a recipient/body lives in memory; it is never
 * persisted to the outbox and never logged. {@code messageId} is the SMTP {@code Message-ID} header
 * value (the idempotency-key hash — a best-effort provider-side dedup hint, research D5).
 */
public record OutboundEmail(String workspaceId, String toAddress, String subject, String htmlBody, String messageId) {}
