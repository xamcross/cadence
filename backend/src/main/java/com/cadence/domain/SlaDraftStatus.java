package com.cadence.domain;

/**
 * F31 lifecycle of an SLA holding-message draft (data-model section 1). One-way out of OPEN via an atomic
 * CAS; a new breach inserts a new OPEN row (the unique partial index permits at most one OPEN per candidate).
 */
public enum SlaDraftStatus {
    /** Awaiting recruiter action. */
    OPEN,
    /** Recruiter approved — the holding message was enqueued through the consent-gated channel. */
    APPROVED,
    /** Recruiter dismissed — nothing sent. */
    DISMISSED,
    /** Cleared by candidate erasure (best-effort; the authoritative no-send guard is the send-time gate). */
    INVALIDATED
}
