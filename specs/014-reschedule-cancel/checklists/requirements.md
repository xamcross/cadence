# Specification Quality Checklist: Flow A3 — Reschedule & Cancellation (F20)

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

- Items marked incomplete require spec updates before `/speckit.clarify` or `/speckit.plan`
- HTTP status codes (410/400/429/409) appear in requirements; these are retained as the established candidate-link **contract** carried forward from the backlog F14 "Token & expiry requirements" and the F13 spec (which uses the same convention), not as implementation detail — they define observable, testable behavior the candidate experiences and are intentionally consistent across F13/F14/F20/F30.

### Multi-role review (constitution C6 gate) — completed 2026-06-16

Reviewers: Business Analyst, QA Lead, Security/GDPR Lead, Backend/DevOps Lead (4 roles, ≥3 required).

Initial verdicts: BA APPROVE-WITH-NITS; QA, Security/GDPR, Backend/DevOps CHANGES-REQUESTED. Convergent MAJOR findings were all resolved in-spec:

- Same-time no-op now binding + must short-circuit before reservation → **FR-027**, **SC-013**.
- Anti-IDOR (target booking derived solely from credential binding) → **FR-017a**, **SC-014**.
- Erasure vs audit-immutability (PII-free audit entries survive erasure) → **FR-022**.
- Reschedule-cap counter timing (commit-only, cumulative, never on rollback/no-op) → **FR-005**, **SC-013**.
- Recovery sweep deterministic forward-vs-rollback rule via durable commit boundary → **FR-009**, **FR-023**.
- Observable atomic invariant (exactly one live event set; transient double-hold bounded) → **FR-009**.
- Daily-cap/availability carve-out for the booking being moved → **FR-006**.
- Required E2E reschedule test → **SC-012**.
- Erasure mid-flight removes both original and in-flight new reservation/events → **FR-024**.

Resolved MINORs: cancel-as-confirmed-POST-not-GET (FR-012); cap-breach message off the public endpoint / no oracle (FR-005, FR-018); idempotency-key reschedule-round discriminator (FR-014); `locationText` encrypted-at-rest carry-forward (FR-011); recruiter workspace-scoping (FR-025); reschedule-session supersession (FR-017b); recruiter-initiated zero-slots outcome (FR-007); internal-participant cancellation awareness (FR-013); F30/F51 scope reconciliation, derived "Reschedule in progress" state, new append-only audit event values, token-primitive reuse, recruiter-notification channel, and the inherited partial-overlap concurrency bound (Assumptions).
