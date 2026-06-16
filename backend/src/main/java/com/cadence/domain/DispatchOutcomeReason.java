package com.cadence.domain;

/**
 * Value-free reason code for a dispatch/bounce outcome (F22, data-model §4). Maps the
 * {@code ContactPermissionGate.Reason} set plus the transport/bounce outcomes — it NEVER carries the
 * provider's free-text reason/description nor any submitted/PII value (D10). Safe to log via
 * {@code .name()} (never the enum itself — the F01.1 logstash {@code kv} footgun).
 */
public enum DispatchOutcomeReason {
    NONE,
    // --- consent-gate denials (ContactPermissionGate.Reason mirror) ---
    NO_BASIS,
    WITHDRAWN,
    ERASED,
    OVER_RETENTION,
    UNAVAILABLE,
    UNDELIVERABLE,
    // --- transport / send outcomes ---
    TRANSPORT_REJECTED,
    RETRY_EXHAUSTED,
    RENDER_FAILED,
    NO_PROVIDER_CONFIG,
    // --- reaper outcome: a stale-SENDING row the provider may already have accepted (operator triage) ---
    SENT_UNCONFIRMED,
    // --- provider webhook outcomes ---
    HARD_BOUNCE,
    SOFT_BOUNCE,
    COMPLAINT,
    DELIVERED
}
