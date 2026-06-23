package com.cadence.domain;

/**
 * Value-free recruiter-notification kind (F22, T044). NEVER carries any candidate-resolvable value.
 * Safe to log via {@code .name()} only (the F01.1 logstash {@code kv} footgun).
 */
public enum RecruiterNotificationType {
    /** The consent gate refused a dispatch (FR-008). */
    DISPATCH_REFUSED,
    /** A dispatch terminally failed — retry cap / render / provider rejection (FR-012). */
    DISPATCH_FAILED,
    /** A hard bounce / complaint flagged the candidate undeliverable (FR-017). */
    DISPATCH_BOUNCED,
    // F20 Reschedule & Cancellation (append-only — value-free).
    /** A candidate cancelled their own interview (FR-013). */
    INTERVIEW_CANCELLED_BY_CANDIDATE,
    /** A reschedule attempt found zero compliant alternatives; the original booking was retained (FR-007). */
    RESCHEDULE_NO_SLOTS,
    /** The candidate self-service reschedule cap was reached; the self-service link was invalidated (FR-005). */
    RESCHEDULE_CAP_REACHED,
    /** A calendar event could not be removed after retries — a residual orphan needs manual removal (FR-011/FR-012). */
    CALENDAR_CLEANUP_INCOMPLETE,
    // F23 No-Show Defense (append-only — value-free). The SINGLE coarse escalation alert: covers both a
    // candidate non-response AND a not-contactable booking, so it never discloses WHY (no GDPR/contactability
    // oracle to the recruiter, research D5). The one-tap "release slot" prompt keys off this.
    INTERVIEW_UNCONFIRMED,
    // F31 SLA Nudge Engine (append-only — value-free): a candidate breached the silence window and a holding
    // draft is queued for one-click recruiter approval. Workspace-scoped (any active Admin/Recruiter sees it),
    // so the "no assignable recruiter" fallback is inherent (research D11).
    SLA_DRAFT_PENDING,
    // F32 Interviewer Feedback (append-only — value-free): an interviewer's scorecard cannot be collected
    // (member deactivated/removed). Workspace-scoped so the fallback is inherent (FR-009).
    FEEDBACK_UNCOLLECTIBLE,
    // F40 Greenhouse ATS (append-only — value-free). Workspace-scoped operator alerts.
    /** An ATS write-back exhausted its retries and was dead-lettered (FR-018). */
    ATS_WRITEBACK_FAILED,
    /** An ATS inbound sync failed for a workspace; the connection is degraded (FR-019). */
    ATS_SYNC_FAILED,
    // F70 Join / Express-Interest (append-only — value-free): a new access-interest request was submitted.
    // Workspace-scoped (any active Admin sees it), null candidateId (the ATS_SYNC_FAILED precedent); never any
    // submitter PII, never emailed to the submitter (structural anti-amplification, SC-011).
    INTEREST_REQUEST
}
