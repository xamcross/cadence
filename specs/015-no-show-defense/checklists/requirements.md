# Specification Quality Checklist: Flow A4 — No-Show Defense (F23)

**Purpose**: Validate specification completeness and quality before proceeding to planning
**Created**: 2026-06-16
**Feature**: [spec.md](../spec.md)

## Content Quality

- [x] No implementation details (languages, frameworks, APIs)
- [x] Focused on user value and business needs
- [x] Written for non-technical stakeholders
- [x] All mandatory sections completed

## Requirement Completeness

- [x] No [NEEDS CLARIFICATION] markers remain
- [x] Requirements are testable and unambiguous
- [x] Success criteria are measurable
- [x] Success criteria are technology-agnostic (no implementation details)
- [x] All acceptance scenarios are defined
- [x] Edge cases are identified
- [x] Scope is clearly bounded
- [x] Dependencies and assumptions identified

## Feature Readiness

- [x] All functional requirements have clear acceptance criteria
- [x] User scenarios cover primary flows
- [x] Feature meets measurable outcomes defined in Success Criteria
- [x] No implementation details leak into specification

## Notes

- All checklist items pass. No `[NEEDS CLARIFICATION]` markers remain.
- The spec deliberately surfaces named existing primitives in the backlog reference, Assumptions, and Key Entities (e.g. `REMINDER_24H`, F20 cancellation/slot-release, F00.2 `SchedulerCheckpoint`) as **reuse pointers and scope anchors**, not as implementation prescriptions — consistent with the F20 spec house style. The functional requirements themselves remain capability-level and technology-agnostic.

### Multi-role sub-agent review (constitution C6 gate) — completed 2026-06-16

Four reviewers (Business Analyst, QA Lead, Security/GDPR Lead, Backend/DevOps Lead) reviewed the draft against the backlog, product spec, constitution gates, and the actual codebase. Findings applied:

- **Security (MUST-FIX)**: FR-006 tightened to require a **non-GET** explicit confirm (scanner/prefetch-safe, mirrors F20 FR-012); added **FR-024** (erasure halts the cascade, invalidates the confirm credential atomically with the wipe, runs async/non-blocking on the F04 completion path). FR-018 carries forward F20's "helpful refusal only after authentication" oracle nuance. FR-011 release pinned to an authenticated in-app non-GET action.
- **Security (C3 oracle)**: the not-contactable escalation is folded into a **single coarse "interview unconfirmed" recruiter alert** (FR-005/FR-010/Recruiter-Notification entity) — never discloses *why*, so no contactability/GDPR oracle.
- **QA (MUST-FIX)**: defined **credential expiry** (expires at interview start → 410, FR-017/FR-018, makes SC-008 verifiable); added a positive **"live confirmed booking"** definition (Terminology block); pinned **deadline-vs-start ordering** (FR-014). Added the **start-reached cascade stage** (FR-002/FR-016) so the no-show signal has a real trigger, a **one-sweep-interval fire tolerance** (FR-002), a **DST success criterion** (SC-013), and edges for multiple-bookings / confirmed-then-cancelled / start-passes-mid-sweep. SC-005 tightened to assert the recorded escalated state.
- **Backend mismatch**: the reused F20 recruiter-cancel primitive **notifies the candidate** — the earlier "no-notify by default" assumption was dropped (§I YAGNI); FR-011/Assumptions now reuse it as-is with a distinct no-show release reason.
- **Backend (plan.md directives captured in Assumptions)**: denormalized top-level booked-start instant + covering `{status, bookedStartAt, stage}` index (the start instant is not currently a queryable top-level field), per-booking **stage fields** (not new `SchedulingStatus` values), a distinct partial-unique confirm-token hash (`write=NON_NULL`), append-only `RecruiterNotificationType`/`AuthEventType` additions, F03 workspace-config extension, Mongock changeset next off `"013"`, and the enum→`kv` logging footgun.
- **Defaults pinned**: confirmation request 24 h before start; unconfirmed escalation 2 h before start (FR-015).
- Constitution gates C1–C6 all green; the C3 (PII oracle) and consent/erasure-wiring risks the Security reviewer flagged are resolved by the coarse-alert change and FR-024.
