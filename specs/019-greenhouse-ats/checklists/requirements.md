# Specification Quality Checklist: ATS Integration — Greenhouse (F40)

**Purpose**: Validate specification completeness and quality before proceeding to planning
**Created**: 2026-06-18
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

- All checklist items pass after one revision round.
- Zero [NEEDS CLARIFICATION] markers in the spec: every open question (auth mechanism, near-real-time mechanism, write-back trigger set, "pipeline view" surfacing, stage-mapping depth) is resolvable with a reasonable default grounded in the backlog (F40), the constitution (§I/§IV/§VIII), and the existing codebase. Defaults are recorded in Assumptions.
- **One recommended `/speckit.clarify` topic** carried in Assumptions, not blocking: the **API-key vs OAuth credential model** for the target Greenhouse account (the backlog text says both). Default = API key. Worth confirming with the user before plan finalization because it shapes the secret/refresh model (FR-002/FR-003).
- One naming reference to the `AtsConnector` interface is retained because it is named in the constitution's Dependency Policy and the F40 acceptance criteria; it denotes a required abstraction boundary, not an implementation choice.

## Multi-role review (constitution C6) — 2026-06-18

Four role reviewers (Business Analyst, QA Lead, Security/GDPR Lead, Backend/DevOps Lead) reviewed the draft. **All four: APPROVE-WITH-NITS, zero BLOCKERS.** Applied SHOULD-FIX items:

- FR-008 reconciliation precedence — external-ref authoritative; email merges only when no external-ref present; never merge two distinct external-refs sharing an email (BA/QA/Security).
- New **SC-012** — imported candidate PII ciphertext-at-rest verification (was unverified; QA).
- FR-011 — webhook authenticity pinned to constant-time HMAC over the raw body before persistence + treat as re-pull trigger, not trusted PII source + ack-no-action on unknown ref (Security/Backend).
- New **FR-028** — imported candidates inherit existing candidate RBAC + HM requisition scoping (C3; Security).
- New **FR-029** — data minimization: only enumerated fields; exclude attachments/notes/custom/EEOC (Security).
- FR-003 — forbid credential/raw provider error body in error, sync-failure, and dead-letter records (Security/QA).
- FR-015 — active-state-guarded write so inbound sync cannot resurrect erased PII; erasure sweeps the write-back queue (Security).
- FR-005 — disconnect cancels pending write-backs (BA).
- FR-004 — pinned the non-Admin health-visibility default (Recruiter health-only; HM/Interviewer/Read-only none) (BA/QA).
- FR-022 — added phone to the no-log list; FR-024 — concrete audit fields; FR-019 — named the status surface; SC-011 — added the integration-pending/error states.
- Assumptions — added storage lawful-basis / controller-processor / DPA posture (Security), write-back idempotency honest bound (Backend), no-show/feedback write-back named trade-off (BA), strengthened the auth assumption (BA).

Deferred to plan/DoD (not spec defects): plain `RestClient` no-SDK build, F00.1 index declarations, Greenhouse rate-limit pacing for the burst case, and carrying the mandatory security re-review at live-credential promotion into the plan DoD.

## Clarifications applied (`/speckit.clarify`) — Session 2026-06-18

Four high-impact decisions resolved and encoded into the spec (`## Clarifications`):

1. **Credential model = workspace API key (Harvest-style)**, not OAuth — cheapest/simplest to build; live Greenhouse API access is a customer-side subscription deferred to live-credential promotion. → FR-001, Assumptions.
2. **Inbound freshness = scheduled poll only** (≤5-min interval); **no inbound webhook endpoint**. This *supersedes* the loop-1 Security fix that had pinned a webhook HMAC on FR-011 — there is no longer an inbound webhook, so FR-011 now mandates authenticated-pull-only and forbids any unauthenticated ingestion endpoint (a net attack-surface reduction). → FR-009/FR-011/FR-012, edge cases, Assumptions.
3. **Write-back set expanded** to include candidate no-show (F23) and interviewer-feedback-submitted (F32) in addition to the scheduling lifecycle. → FR-013, US3, Assumptions.
4. **Stage = raw external free-text label** (plus job/requisition ref); no internal stage enum/mapping. → FR-006/FR-010, Stage entity, Assumptions.

No new [NEEDS CLARIFICATION] markers introduced. Spec ready for `/speckit.plan`.
