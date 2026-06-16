package com.cadence.domain;

/**
 * The {@link EmailDispatch} outbox state machine (F22, data-model §3). All transitions are raw
 * {@code findAndModify} CAS (no {@code @Version} on the row — the unique {workspaceId,idempotencyKey}
 * index is the durable guarantee, the CAS claim is the concurrency guarantee — research D5).
 *
 * <ul>
 *   <li>{@code PENDING} — enqueued; awaiting a claim (or a future {@code scheduledFor}).</li>
 *   <li>{@code SENDING} — claimed by exactly one worker; transmitting.</li>
 *   <li>{@code SENT} — the transport accepted the message.</li>
 *   <li>{@code SENT_UNCONFIRMED} — the stale-SENDING reaper marked a crash-window row; NO resend (FR-010).</li>
 *   <li>{@code FAILED} — retry cap exhausted / permanent send error; dead-lettered + notified.</li>
 *   <li>{@code BOUNCED} — a hard-bounce webhook flipped a previously-SENT row.</li>
 *   <li>{@code REFUSED} — the consent gate denied at claim time; terminal, audited, notified.</li>
 * </ul>
 */
public enum DispatchStatus {
    PENDING,
    SENDING,
    SENT,
    SENT_UNCONFIRMED,
    FAILED,
    BOUNCED,
    REFUSED
}
