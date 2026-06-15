# Specification Quality Checklist: Interview Template & Rule Engine (F12)

**Purpose**: Validate specification completeness and quality before proceeding to planning
**Created**: 2026-06-15
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

- Items marked incomplete require spec updates before `/speckit.clarify` or `/speckit.plan`.
- Resolved without [NEEDS CLARIFICATION] markers via documented Assumptions: (1) "any N of pool" = eligibility + per-pool qualifying-set annotation (concrete-panel binding deferred to F13); (2) daily cap counts Cadence-managed interviews, not arbitrary busy time; (3) availability-unknown is fail-safe (never assumed free); (4) slot-start cadence configurable, default 15 min, anchored to working-day start.

## Multi-role review (C6 gate) — 2026-06-15

Reviewed by four sub-agents (Business Analyst, QA Lead, Security/GDPR Lead, Backend/DevOps Lead); all returned **APPROVE-WITH-CHANGES**. Accepted findings folded into the spec:

- **Pool semantics**: per-pool qualifying-member annotation (FR-010, FR-021, SC-005); unknown-availability pool members excluded from quorum (FR-014, SC-004); distinct members per quorum, no member fills two seats; member-both-required-and-in-pool rejected at validation (FR-002, Edge Cases).
- **Daily cap**: enforced against *required* participants at compute time; pool-member cap deferred to F13's atomic re-validation; cross-computation cap is advisory (FR-012, Edge Cases, SC-002); counts managed-event start instants per civil day excluding DELETED/CLEANUP_INCOMPLETE (DST 23h/25h safe, SC-003).
- **FR-011** corrected: removed the incorrect "offered slots ≥ buffer apart" clause (candidate picks one); buffer is vs existing commitments + working-hours fit only.
- **Determinism** (FR-016): fixed availability snapshot, one read per participant, injected reference instant, stable ordering; **SC-007** split into a deterministic compute budget (no live network) with real-latency target owned by F13/F14.
- **Cadence anchor** (FR-008): working-day start in the applicable zone.
- **Security**: template name/free-text never logged or audited + CI sentinel (FR-022, FR-023, SC-010); value-free validation messages (FR-002, SC-008); same-workspace validation of every member reference (FR-002, FR-006, SC-009); explicit role gate / endpoint-inventory coverage of the compute path (FR-005, SC-009); per-template member/pool/blackout caps to bound fan-out / DoS (FR-024); member-erasure no-op affirmation (Assumptions).
- **Backend**: declared the new `managedCalendarEvents {workspaceId, memberId, startAt}` cap index + count query + status filter, and the `interviewTemplates {workspaceId, status}` index; Mongock `order` derived off the highest applied ChangeUnit (`007`) — carried into Assumptions/Dependencies for `plan.md`.
- **Optional participants** never gate a slot (FR-011 / Edge Cases); **retired-template compute** refused with a distinguishable error (FR-007, AS-2.9, SC-006).

## Multi-role PLAN review (C6 gate) — 2026-06-15

Plan (`plan.md` + `research.md` + `data-model.md` + `contracts/interview-template-api.md`) reviewed by three sub-agents (Backend/DevOps, Security/GDPR, QA), each verifying claims **against the actual F03/F10/F11 source**. All returned **APPROVE-WITH-CHANGES**; **all Constitution gates PASS** (no dependency/service/topology/scheduler added; ChangeUnit008 order "008" off the applied `007`; `AvailabilityService.query` signature + `EventStatus` enum + class-level `@PreAuthorize` inventory coverage confirmed in code). Accepted findings folded in:

- **DST idiom fix (Backend MAJOR)**: D4 now specifies the `LocalDateTime` round-trip mismatch (or `ZoneRules.getTransition(ldt).isGap()`) for spring-forward gap detection; removed the wrong `withEarlierOffsetAtOverlap` reference (that's for the fall-back *overlap*).
- **Cap query precision (Backend MINOR)**: switched to `…StatusNotIn…StartAtGreaterThanEqual…LessThan` — exclusion list `{DELETED,CLEANUP_INCOMPLETE}` (future-proof) + **half-open** civil-day bound (not inclusive `Between` → no next-midnight double-count); engine reads once/member and buckets by day in memory (research D5, data-model §2).
- **Compute-path isolation (Security MINOR ×2)**: D8 elevated to the **primary** isolation control — the engine passes `AvailabilityService` only the *persisted, validated* member ids (no request-supplied list; a `RuleEngine` test asserts this), with the service's `workspaceId`-scoped connection lookup as the stale-membership backstop (research D8, contract §E).
- **Audit cleanliness (Security MINOR)**: added a 4th append-only `AuthEventType` `INTERVIEW_TEMPLATE_COMPUTE_REFUSED` for the retired-compute refusal (not overloading `_RETIRED`); contract asserts the audit row (research D10, contract §B).
- **`toString()` (Security NIT)**: hard rule — MUST omit `name`; member ids MAY appear (data-model §1).
- **Two-pools double-count (QA MINOR)**: validation rejects a member appearing in two different pools, not only required+pool (FR-002, research D6/D8).
- **SC-007 de-flake (QA MAJOR ×2)**: the CI gate is now the deterministic, countable property — `Mockito.verify(availabilityService, times(1)).query(...)` + cap-read multiplicity (no vacuous in-memory snapshot) — with the latency number a JIT-warmed median logged informationally under a generous hard cap (not a bare `<50 ms` wall-clock gate).
- **Coverage gaps (QA MAJOR/MINOR)**: added mapped tests for `windowClamped==true` (positive FR-017), duration>working-window→0, blackout∩WH precedence, pool-of-1, **optional-never-gates** (silent-bug guard), member-left-workspace fail-safe, buffer-after in a spring-forward gap, range-in-past/empty/reversed→[], per-status distinguishable reason + busy-not-in-unschedulable, zone-relative civil-day cap boundary, and the 409 `COMPUTE_REFUSED` audit assertion (plan.md test list, quickstart map).

All findings applied; none moved a gate to FAIL or added scope outside the fixed stack.

## Multi-role TASKS review (C6 gate) — 2026-06-15

`tasks.md` (45 tasks) reviewed by two sub-agents: a **coverage/traceability** reviewer (APPROVE-WITH-CHANGES) and a **sequencing/TDD/parallelization/file-conflict** reviewer (APPROVE — NITs only). Verified: every SC-001..010 → a test task; all 6 contract endpoints → impl + contract assertion; every data-model entity/enum/index/repo method → a creation task; all reviewed edge cases → a test; TDD ordering (tests-first, must-fail, explicit run-green gates); [P] markers file-distinct; the US1→US2 same-file extensions (contract/controller/component-spec/DTOs) correctly sequential (not [P]); US1 is MVP-independent. Folded-in fixes:
- Added `SlotComputationRequest` (the internal engine input type) to **T031** (coverage MINOR).
- Added an `ArgumentCaptor` assertion to **T028** that the member-id set passed to `AvailabilityService.query` equals exactly the persisted template's validated members (the D8 compute-path isolation control — coverage MINOR).
- Fixed a stale repo-method name in `plan.md` (`StatusInAndStartAtBetween` → the correct `StatusNotInAndStartAtGreaterThanEqualAndStartAtLessThan` matching T007/D5).
- Listed the T014→T033 DTO same-file extension in the dependencies section (doc completeness NIT).
