# Specification Quality Checklist: F51 Pipeline View

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

- Items marked incomplete require spec updates before `/speckit.clarify` or `/speckit.plan`.
- The most significant scope decision — that F51 introduces a *minimal* requisition concept (title + open/closed + assignment) solely to enable Hiring Manager scoping, rather than a full requisition-management surface — is documented in Assumptions and bounded in FR-008..FR-011.

## Multi-role review (2026-06-18)

Reviewed by Business Analyst, Security/GDPR, QA, and Backend reviewers. Initial verdicts: BA / Security / Backend = APPROVE-WITH-NITS; QA = CHANGES-NEEDED. All blocking findings were applied to the spec:

- **(QA blocker) Asserted-but-nonexistent "terminal" lifecycle field** → resolved: terminal is now defined strictly via existing state (`statusOutcome` completion + erasure); FR-003 + Assumptions explicitly state no new lifecycle field is invented.
- **(QA blocker) `requisitionId` erasure/regression semantics** → resolved: link declared non-PII, retained on erasure, exclusion by active-state predicate (FR-007, Key Entities); SC-009 + Dependencies demand regression coverage.
- **(Security) Bulk skip-reason GDPR oracle** → resolved: FR-018 + SC-006 now mandate a single coarse "not contactable" outcome, byte-identical across all causes; specific cause only in value-free internal audit.
- **(QA) Missing scenarios** → added: concurrent bulk double-submit (US3-4), erasure-during-bulk (US3-6), already-booked (US3-7), at/over-limit boundary (US3-5), empty/zero-event + out-of-scope-HM timeline (US4-4/5), empty workspace + no-stage candidate (US1-6/7), requisition close/unassign + linkage-move visibility flip (US2-6/7).
- **(QA) SC-002 ambiguity** → resolved: SC-002 now separates server-side compute from end-to-end render and defers exact page-size/target to plan.
- **(QA) Scheduling-status mapping undefined** → resolved: FR-005 now requires a single documented, unit-testable mapping.
- **(BA/Backend) FR-011 creep / under-scope** → resolved: FR-011 pinned to manual-confirm linking only; auto-reconciliation explicitly out of scope.
- **(BA) Personalization preserved** → added to FR-015.
- **(Security/QA) Audit + role-matrix precision** → FR-009 audits linkage changes; FR-012/SC-004 enumerate exact per-role outcomes incl. Read-only and Interviewer.

Backend reviewer's plan-level guidance (not spec changes, to carry into `/speckit.plan`): new `candidates {workspaceId, erasureState}` (or `+createdAt`) index for the list read; batch scheduling-status finder + index to avoid the per-candidate N+1; `Candidate.requisitionId` as `@Field(write=NON_NULL)`; new Mongock changeset order **"022"** off the highest applied **"021"** (F50), not the branch number.

## Plan multi-role review (2026-06-18)

Plan (`plan.md` + `research.md` + `data-model.md` + `contracts/pipeline-api.md`) reviewed by Backend, Security/GDPR, and QA reviewers against the live codebase. **All three = APPROVE-WITH-NITS; zero BLOCKERs.** Findings applied to the plan artifacts:

- **(Security #1, real oracle) Bulk `not_found` vs `not_contactable`** → collapsed to a single coarse `not_contactable` for every ineligible candidate (no erasure/existence oracle); no distinct `not_found` skip for a real candidate. (data-model, contract, research D5.)
- **(Security #2) `SchedulingService.initiate` throws `UnschedulableRequiredException(memberIds)`** → all initiate exceptions collapse to the coarse skip; member-id payload discarded, never in result/logs. (contract, data-model, plan test note.)
- **(Backend #2) Redundant `schedulingRequests {workspaceId,candidateId}` index** → dropped; batch read reuses the existing `ChangeUnit012 {workspaceId,candidateId,createdAt:-1}` prefix. ChangeUnit022 reduced to **3 indexes**. (research D8, data-model, plan.)
- **(Backend #1) `ContactPermissionGate.evaluate(ws,candidateId)` re-reads the DB** (no loaded-Candidate overload) → corrected wording; bounded per-candidate read accepted (within `bulk-max`), optional `evaluate(Candidate)` overload noted. (contract, plan.)
- **(Backend #3) Timeline finder real name** `findByWorkspaceIdAndCandidateIdOrderByOccurredAtAscIdAsc` (no `Pageable`); index `auditLog {candidateId, occurredAt:-1}` is candidateId-leading. Corrected. (data-model, plan.)
- **(Backend #4) SC-007 completeness** depends on `MESSAGE_SENT`/`BOOKING_CHANGED` emission sites existing → documented honest scope; F51 adds the `append(...)` call if a site is missing (no new event types). (data-model.)
- **(Backend #6) Requisition unassign** needs the assignment's `memberId` (resolve via `getOrNotFound`) since `AssignmentService.delete` requires it. (contract.)
- **(QA + Security) Added test scenarios**: TOCTOU async-backstop (`PipelineBulkToctouIT`), `classifyCandidate` delegation anti-drift (`SlaClassifyReuseTest`), include-closed/terminal filter + SC-001 completeness-vs-cap (`PipelineComposeIT`), scorecard-free-text-excluded (`PipelineTimelineIT`), FR-011 external-label surfacing + audit-on-change (`RequisitionContractIT`), SUPERSEDED/EXPIRED mapping edges.
- **(QA #6) Over-engineered perf assertion** → dropped the brittle explain()-plan gate; index-backing asserted by `PipelineIndexTest` (existence) + CI-safe wall-clock margin (the `DashboardPerfIT` precedent).

Verified-correct (no change): Mongock order "022" off applied "021"; no index-key-pattern collision (F42 trap); `CandidateErasureService.wipe` unchanged (requisitionId non-PII, retained, excluded by `erasureState=ACTIVE`); `SlaNudgeService.classify` is N+1-free; `principal.role()` exists for the visibility predicate; `@Order(HIGHEST_PRECEDENCE)` no-oracle handler + security-exception re-throw is the correct F31 template; HM `$in`-over-`assignedResourceIds` scoping with `@Field(write=NON_NULL)` is airtight; the async send-time gate is the authoritative TOCTOU backstop.

**Post-design Constitution re-check: still PASS on all gates (C1–C7).** No gate-affecting change resulted from the review (the fixes strengthen C3 and remove a redundant index; no new dependency/service/collection beyond the already-counted `requisitions`).
