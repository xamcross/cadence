# Specification Quality Checklist: Email Template Library

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
- The spec inherits the established Cadence house conventions (HTTP status codes, role names, scoped-not-found, audit-trail discipline) where they are part of the feature's contract; these are domain/contract vocabulary, not implementation leakage, consistent with the approved F02–F12 specs.
- Zero clarification markers: all open choices (override-by-reference defaults, in-house merge substitution, versioning depth, preview data source, dispatch-authorisation boundary vs F22/F31) were resolved with informed defaults documented in Assumptions, per the backlog's already-detailed F21 acceptance criteria.

## Multi-role sub-agent review (2026-06-15)

Reviewers: Business Analyst, QA Lead, Security/GDPR Lead. All three returned **APPROVE-WITH-CHANGES**; findings applied inline:

- **BA**: explicit US-F21-3 → F31 traceability mapping added (Assumptions); merge-token catalogue declared add-only/extensible; F21's verifiable slice of the no-auto-send contract sharpened (FR-020/SC-010).
- **QA**: missing-field warning pinned to a machine-detectable sentinel + present-but-empty handled (FR-014/SC-002); render output channel + injection neutralisation made testable (FR-015/SC-003); determinism, repeated-token, malformed-token, and idempotent-reset edge cases added; per-change-kind audit SC (SC-008); **frontend Jasmine coverage SC added (SC-011)** to close the backlog-flagged frontend-test gap.
- **Security**: subject-line CRLF/SMTP-header injection added as a distinct sink (FR-015/SC-003); link-token spoofing / system-produced URL tokens (FR-016); value-free render/preview error diagnostics (FR-018); candidate-id scoped-not-found in preview (FR-017); render-fan-out DoS bound (FR-022); template-content-is-not-PII classification (Assumptions).

## Multi-role PLAN review (2026-06-15)

Reviewers: Backend/DevOps Lead, Security/GDPR Lead, QA Lead — each verifying the plan's claims against the **actual** F02/F03/F04/F12 source and the spring-web bytecode. All three returned **APPROVE-WITH-CHANGES**; gate status unchanged (PASS). Findings applied inline to plan/research/data-model/contracts/spec:

- **Backend/DevOps** (verified: `ChangeUnit008` is the highest applied → `009` correct; `AuthAuditService.record`, `CandidateRepository.findByWorkspaceIdAndId`, `InterviewTemplateRepository.findByWorkspaceIdAndId`, `HtmlUtils` all present; no new dep): pinned the `@Version`-engages-only-via-`save()` write-path constraint + dual-catch `DuplicateKeyException`/`OptimisticLockingFailureException` (D8); added a `@PostConstruct` built-in/tone completeness check (D1/D10, SC-001); reserved-word guard on `stageKey="BASE"` (data-model); `@ConfigurationProperties` binding note; fixed the `TonePreset` typo.
- **Security/GDPR** (verified `HtmlUtils.htmlEscape` escapes only `< > " & '` and passes CR/LF/`U+2028`/controls through): **BLOCKER** — subject neutralisation now strips the full control + Unicode-line-separator set and is a transform distinct from the body's HTML escaping (D3, SC-003); **URL-typed tokens are `http(s)`-scheme-restricted before anchoring** so a preview `javascript:` value can't produce a clickable anchor (D3/D5); UTF-8 escape overload (preserves non-Latin names); preview PII path + audit confirmed clean.
- **QA**: **BLOCKER** — pinned the order-deterministic `bodyHtml` algorithm + `missingFields` first-occurrence ordering for the byte-identical assertion; **BLOCKER** — fixed the contract preview example so the body actually contains the `[[missing:<token>]]` marker and the contract test asserts it in-body; **BLOCKER** — the TRACE scan now drives a *failing* render/preview path with a PII sentinel; SC-010 is a structural "no transport reachable" test (not vacuous); added the un-overridden→live-constant-with-zero-rows assertion, the malformed-token truth table, and a hostile-surrounding-markup spoof test.

## Multi-role TASKS review (2026-06-15)

Reviewers: Backend/DevOps, QA, Delivery/PM. All three returned **APPROVE-WITH-CHANGES**; coverage confirmed complete (all 22 FRs → tasks, every planned file → a task, all 11 SCs → a test task, all 4 backlog ACs correctly scoped). Findings applied to `tasks.md`:

- **Consensus BLOCKER (all three)**: SC-008's seven audit change-kinds weren't all explicitly tested — added the **override-create** kind assertion to T017 and the **variant-edit** kind to T038 (the seventh kind), so all seven (create/edit/tone/lock/unlock/variant-edit/reset) are asserted exactly-one-row.
- **Backend**: T016 now proves its own `DuplicateKeyException`→409 dual-catch leg in the US1 MVP slice (not deferred to US3); T006 names the `"BASE"` base sentinel explicitly.
- **QA**: T033 now asserts the locked-template contract cells for **all five roles** (not just Recruiter-vs-Admin); T026 makes the repeated-token case explicit and adds `U+2029` to the subject-strip payload set.
- **PM**: T027 clarifies FR-020's "approval/lock metadata" is the `locked` flag only in the F21 slice (approval workflow is wholly F22/F31, not built here); confirmed zero out-of-scope work (no `EmailSender`/dispatch/SLA-draft/candidate-pages/auto-send) and a genuine §II browser→DB leg per story.

## Multi-role IMPLEMENTATION review (2026-06-16)

Mandatory C6 review of the delivered diff (Backend/DevOps, Security/GDPR, QA), each reading the actual source. Verified locally: full backend suite green (incl. the new `com.cadence.emailtemplate.*`); frontend `ng test` 36/36 + `ng build` clean.

**Loop 1** — Backend **LGTM-WITH-NITS**, Security **LGTM-WITH-NITS**, QA **CHANGES-REQUIRED** (2 BLOCKERs). Findings applied:
- **QA BLOCKER**: variant resolution/fall-back (SC-006/US4) was untested → added `EmailTemplateCrudIntegrationTest.variantResolutionAndFallback` (variant preferred / base+built-in fallback / reset-to-fallback).
- **QA BLOCKER**: lock/unlock audit rows (2 of 7 SC-008 kinds) unasserted → added `EmailTemplateAuditTest.lockAndUnlock_eachEmitOneTaggedRow`; all 7 change-kinds now asserted.
- **QA SHOULD**: added over-cap body-length + token-count cases (`EmailTemplateValidationTest`) and a `@TestPropertySource` variant-cap test (`EmailTemplateVariantCapTest`); rewrote the subject test to exercise U+2028/U+2029/U+0085 via code points.
- **Security SHOULD**: reordered `MergeRenderer.substituteHtml` to convert authored `\n→<br>` BEFORE substitution, so a merge value's own newline can never become markup (safe-by-construction); added `bodyValueWithNewline_doesNotProduceMarkup`.
- **Backend NIT**: `setLocked` now enforces the variant cap when materialising a new variant.

**Loop 2** (verification) — QA **LGTM** (all 4 prior findings RESOLVED, non-vacuous), Security **LGTM-WITH-NITS** (the SHOULD resolved; one doc-only nit — the `MergeRenderer` Javadoc described the old ordering — fixed). No loop 3 needed.
