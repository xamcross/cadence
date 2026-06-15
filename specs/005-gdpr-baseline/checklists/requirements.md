# Specification Quality Checklist: GDPR Baseline — Consent, Erasure & Audit Log (F04)

**Purpose**: Validate specification completeness and quality before proceeding to planning
**Created**: 2026-06-15
**Feature**: [spec.md](../spec.md)

## Content Quality

- [x] No implementation details (languages, frameworks, APIs)
- [x] Focused on user value and business needs
- [x] Written for non-technical stakeholders
- [x] All mandatory sections completed

> Note: The "Notes for Planning" and "Constitution Alignment" sections reference concrete mechanisms (Spring Security, AES-256-GCM, Mongock, `@Scheduled`) by name. These are deliberately quarantined as *informational planning hints* (the approved F02/F03 pattern) so the Constitution Check in plan.md passes cleanly; the mandatory spec sections (User Scenarios, Requirements, Success Criteria) remain technology-agnostic.

## Requirement Completeness

- [x] No [NEEDS CLARIFICATION] markers remain (scope decisions captured as Assumptions; the two flagged items resolved via `/speckit.clarify` Session 2026-06-15 — see spec `## Clarifications`)
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

## Multi-Role Sub-Agent Review (Constitution C6 — performed at spec stage)

Reviewers (2026-06-15): **Security/GDPR Lead** (CHANGES-REQUESTED), **Backend/DevOps Lead** (APPROVE-WITH-CHANGES), **QA Lead** (APPROVE-WITH-CHANGES), **Business Analyst** (APPROVE-WITH-CHANGES). Findings applied to the spec; the central security BLOCKER (residual `emailHash` re-identification) and all MAJORs are resolved. Re-verdict after fixes: **APPROVE-WITH-CHANGES → resolved to PASS for spec stage** (remaining items are plan.md decisions, flagged in Notes).

| # | Role | Severity | Finding | Resolution |
|---|------|----------|---------|------------|
| S1 | Sec | **BLOCKER** | Erasure left the deterministic `emailHash` in the doc → anyone with DB access recomputes it from a known email and re-identifies the "erased" subject; FR-006 only made it *unqueryable* | FR-006 now **overwrites the email-derived key to null** (destroy, not hide); SC-002/SC-006 assert no residual email-derived value via raw-driver read |
| S2 | Sec | MAJOR | Derived candidate-PII stores that later features create (message bodies, ATS payloads, dead-letters) weren't bound to the shared wipe → Art. 17 erasure structurally incomplete once F13/F22 land | Added **FR-006a erasure-participant forward contract**: every later PII store MUST be reachable by the shared wipe |
| S3 | Sec | MAJOR | Erasure-request record could carry candidate free-text PII and survive the wipe, re-identifying the subject | FR-011/FR-013 forbid free-text candidate PII at intake (internal id + bounded reason code only; sanitize, don't trust caller) |
| S4 | Sec/QA | MAJOR | Audit "non-PII" was intent, not structural; no retention ceiling stated (storage-limitation) | FR-014 closed-enum codes only, **no free-text value field**; FR-015 states the legitimate-interest basis + consciously-accepted no-ceiling |
| S5 | Sec | MAJOR | Consent record's fate on erasure undefined (still personal data, or Art. 7(1) evidence?) | FR-006 chooses retain-as-evidence with internal-ids-only; identifying fields destroyed |
| S6 | Sec | MAJOR | Gate fail-closed asserted for known deny-states but not for error/unknown/missing | FR-004 + SC-001: gate **fails closed (deny)** on any error/missing/unknown; error branch tested |
| B1 | BE | MAJOR | Retention **age basis** (createdAt vs lastContactAt) left dangling — load-bearing for index coverage + legal semantics | FR-018 resolves to **`lastContactAt`** (GDPR last-activity); existing F00.1 index covers it |
| B2 | BE | MAJOR | `erasureState` not in the scan index → "covering predicate" claim wrong | FR-018 reworded to index-supported scan + erasure-state residual filter; Notes give the alt (neutralize `lastContactAt`) |
| B3 | BE | MAJOR | `emailHash` neutralization on a unique index would null-collide across erased candidates (F01 footgun); constant sentinel also collides | FR-002 makes the email index **non-unique**; Notes pin `$set null` + `@Field(write=NON_NULL)`, never a sentinel on a unique key |
| B4 | BE | MAJOR | `SchedulerCheckpointService` uses `Instant.now()`, not `Clock` → missed-fire test can't use MutableClock | Notes "Clock caveat": test missed-fire via a stale `RUNNING` checkpoint (F00.2 approach), not MutableClock |
| B5 | BE | MINOR | Audit timestamp field must be `occurredAt` to hit the pre-built index; CAS-winner-only audit write; no `/api/candidate` controller in F04 | Folded into Notes (audit doc, emailHash safety, Authorization) |
| Q1/BA | QA/BA | MAJOR | SAR/portability (Art. 15/20) + rectification (Art. 16) silently absent from a "GDPR Baseline" | Assumption added: explicitly **deferred** (not in backlog F04), audit log is the future SAR data foundation |
| Q2 | QA | MAJOR | Re-record-after-withdrawal and erased-then-recreate cycles untested | SC-013 + edge cases added |
| Q3 | QA | MAJOR | Gate deny-**precedence** order never pinned → SC-001 non-deterministic for overlaps | FR-004 pins **erased > over-retention > withdrawn > no-basis** |
| Q4 | QA | MAJOR | Retention boundary `>` vs `>=` undefined | FR-018/SC-014: **strictly exceeds** (at-boundary not flagged) |
| Q5 | QA | MAJOR | Retention flag never cleared when period lengthened → gate wrongly keeps denying | FR-019/SC-014: scan **clears stale flags**, gate returns *permit* |
| Q6 | QA | MAJOR | Erasure-request double/concurrent confirm unguarded | FR-012/SC-015: guarded pending→resolved transition; concurrent = single wipe |
| Q7 | QA/BE/BA | MINOR | `202/async` (backlog) silently softened to "2s"; `[ERASED]` literal not pinned; SC-003 measurement basis; audit ordering tiebreaker; concurrent-erasure audit count | FR-006 pins `[ERASED]`; FR-016 deterministic tiebreaker; SC-003 server-side handler duration; SC-005 exactly-one audit; Assumption reconciles 202 (sync wipe is O(1); status code = plan decision) |
| BA1 | BA | MAJOR | Create-path demonstrability seam only implied → planner could over-scope (a create API) or read it as untested | FR-005 + SC-016: canonical-create exercised production-path by integration test; **no HTTP create endpoint** |
| BA2 | BA | MAJOR | Default-lawful-basis was an open decision while FR-004/005 already assumed it; no surface to record/withdraw a basis (US1 not demonstrable end-to-end) | FR-003 adds an **operator record/withdraw-basis surface** (Admin/Recruiter) + RBAC row (FR-021); Assumption resolves default = **fail-closed**, stakeholder-reversible |
| BA3 | BA | MINOR | EU data residency (§6) + member-data exclusion should be explicit-not-silent | Assumptions added (residency = infra/Enterprise out of scope; member-data exclusion already present); backup-residency acknowledged |

**Two items flagged for stakeholder confirmation — both RESOLVED (`/speckit.clarify`, Session 2026-06-15):**
1. **Retention scanner/enforcement** ships **inside F04** (not split) — confirmed; F04 includes US5 / FR-018–FR-020.
2. **Default lawful basis on create** = **fail-closed** (no workspace-default auto-basis) — confirmed; FR-005 final.

Both recorded in the spec's `## Clarifications` section and folded into the affected Assumptions; no open clarification items remain.

## Multi-Role Sub-Agent Review — PLAN stage (Constitution C6, 2026-06-15)

Reviewers: **Backend/DevOps**, **Security/GDPR**, **QA**, **Front-End**. Verdicts: Backend APPROVE-WITH-CHANGES; Security APPROVE-WITH-CHANGES; QA CHANGES-REQUESTED (2 BLOCKER); Front-End REQUEST-CHANGES. All findings dispositioned below; load-bearing fixes folded into `plan.md`/`research.md`/`data-model.md`/`contracts/gdpr-api.md`. Constitution re-check after fixes: **PASS** (no gate to FAIL). The de-identification BLOCKER (S1) and the authorization model were verified **correctly delivered** by the plan; the corrections are design/test precision, not architecture changes.

| # | Role | Severity | Finding | Resolution |
|---|------|----------|---------|------------|
| P1 | BE/SEC | MAJOR | `$set("email","[ERASED]")` goes through the encrypting converter → stores **ciphertext of the marker**, so the SC-002 "literal `[ERASED]` via raw driver" assertion is self-contradictory with encryption-at-rest | research D2 / data-model §1 — test asserts read-back-decrypts-to-`[ERASED]` + raw `emailHash` **key absent** + ciphertext≠original; not a literal-marker raw match |
| P2 | QA/BE | MAJOR | FR-009 indistinguishable response: erasure on an **unknown id** must return **200**, not the F02 `ScopedNotFoundException` 404 (D9 cited the 404 pattern — contradiction) | contracts + data-model §5 — erasure does NOT use 404; byte-identical 200 across missing/erased/fresh |
| P3 | SEC | MAJOR | Gate could fall through to **permit** on a future/corrupt enum (`!= ERASED` style) | research D4 / data-model §6 — **positive evaluation**: permit ONLY on the explicit-good row; everything else (incl. unknown) denies |
| P4 | SEC/QA | MAJOR | CI candidate name/phone scan **passes vacuously** (no regex matches a free-form name) | research D10 — seeded high-entropy **sentinels** + literal grep + positive vacuity guard; drive the audit read (decrypt) + error path |
| P5 | QA | BLOCKER | SC-003 "2 s" had no deterministic measurement seam (MockMvc wall-clock is flaky) | research D11 / plan — assert **structural O(1)** (one `$set` + one append vs large seeded history) on the SERVICE call, not the round-trip |
| P6 | QA | BLOCKER | Gate truth-table is **unseedable** via the only sanctioned `create()` seam, and the plan called it both "unit" and bound by the seed rule | plan — it is a **pure Mockito unit** over a mocked repo (EXEMPT from seed-via-create); integration permutations use REAL transitions |
| P7 | BE | MAJOR | `retention/{id}/delete` could wipe an **unflagged ACTIVE** candidate | contracts / research D8 — guarded update on `retentionFlagged==true`; unflagged/unknown is a no-op same-shape response |
| P8 | FE | MAJOR | Plan cited **non-existent** guard APIs (`requireRole`/`hasAnyRole`); real API is `roleGuard(...roles)` | research D13 / plan — corrected; per-surface role mapping made explicit |
| P9 | FE | MAJOR | Admin-only shell nav → the **Recruiter erasure surface is unreachable** (US7 AS-2); no candidate-id entry point (browser is F51) | plan — add Admin GDPR nav + an `ADMIN||RECRUITER` erasure entry; candidate id via paste/text field; browse deferred to F51 |
| P10 | QA/FE | MAJOR | SC-011 per-role guard coverage repeated the F03 single-route hole (Admin-only vs Admin+Recruiter not both tested) | plan — guard spec covers BOTH `roleGuard('ADMIN')` and `roleGuard('ADMIN','RECRUITER')` + all four surface specs |
| P11 | BE/QA | MAJOR | Append-only was "by convention"; `seq` counter source unspecified | data-model §2 — repo extends a narrow `Repository<>` (no `delete*`); audit codes are **enums** not Strings; `seq` replaced by the monotonic **`_id`** tiebreaker; reflective no-mutation self-test |
| P12 | QA | MAJOR | Audit-survives-erasure asserted presence, not immutability; concurrent-erasure loser audit count ambiguous (F03 T3) | plan — capture pre-wipe set, assert **byte-identical** after + exactly one new `ERASURE_COMPLETED`; losers append nothing (count +1) |
| P13 | QA/BE | MAJOR | Retention boundary fixtures unreachable via `create()` (`lastContactAt=now`); flag-clear test asserts `permit` without a basis | plan — boundary fixtures set `lastContactAt` explicitly (sanctioned deviation); flag-clear → permit IFF basis recorded, else `no_basis` |
| P14 | BE | MAJOR | New mutating endpoints are **CSRF-protected** (F02 chain); tests omitting `.with(csrf())` would 403 for the wrong reason | contracts + plan — CSRF note added; all mutating MockMvc calls use `.with(csrf())` |
| P15 | SEC/QA | MINOR | `reasonCode` (intake + Admin reject) typed `String` "closed vocabulary" — not enforced | data-model §3/§5 / contracts — `ErasureReasonCode` **enum**, server-validated → 400 `invalid_reason` |
| P16 | BE/FE | MINOR/NIT | Replay action must register pre-`ApplicationReadyEvent`; `$localize` for programmatic strings; confirm step + `role="alert"` + empty states; SC-016 via `RequestMappingHandlerMapping`; `schedulerCheckpoints` cleanup mandatory; enum-list alignment | Folded into research D8/D10/D13, plan frontend tree + isolation note |

## Multi-Role Sub-Agent Review — TASKS stage (Constitution C6, 2026-06-15)

Reviewers: **QA/Coverage**, **Backend sequencing**, **Traceability/format**. Verdicts: QA APPROVE-WITH-CHANGES; Backend APPROVE-WITH-CHANGES (sequencing/Mongock/PII-converter/scheduler-replay/Clock/RBAC all **verified against the real code**); Traceability CHANGES-REQUESTED (one unmapped plan test file). No BLOCKER invalidating the DAG; format-clean, IDs T001–T056 sequential, every contract endpoint + data-model entity + plan source file mapped (after fixes). Fixes applied to `tasks.md`/`plan.md`:

| # | Role | Severity | Finding | Resolution |
|---|------|----------|---------|------------|
| K1 | QA/Trace (×3) | MAJOR | The plan's `CandidateLifecycleIntegrationTest` had no task → **SC-016** (no HTTP create endpoint; create production-path) and **SC-013** second half (erase→re-create independent record) untested | **Folded** (no renumber): SC-016 route check → T021 (`RequestMappingHandlerMapping` asserts no `POST /api/internal/candidates`); SC-013 fresh-record + create production-path → T030; re-record-after-withdrawal already in T025. Plan test-tree note updated to record the fold. |
| K2 | BE | MAJOR | T009 bare `Repository<>` does **not** inherit `save`/`insert` — a custom-named append parses as a query and fails at startup | T009 now declares the **reserved** signature `<S extends CandidateAuditEvent> S insert(S)` (or writes via `mongoTemplate.insert`) |
| K3 | Trace | MINOR | Parallel example grouped `T013/T014` though T014 depends on T013 | Reworded: `T011/T012/T013` parallel, then `T014` |
| K4 | Trace | MINOR | T017 lacked `[P]` while same-phase file-peers T018/T019 had it | Added `[P]` to T017 |
| K5 | QA/BE | MINOR | T020 asserted brittle literal index names | T020 now asserts by **key spec** (auto-name tolerant) |
| K6 | BE/Trace | NIT | Stale `...SeqAsc` finder name + "monotonic seq" wording (the `seq` field was replaced by `_id`) | `plan.md`/`spec.md` updated to the `_id`-tiebreaker finder/wording |

Backend reviewer **PASS-verified** against the real codebase: Mongock `order="005"` + no-recreate of ChangeUnit001 indexes + non-unique emailHash; `MongoPiiConfig` 3-field registration on the existing bean; `PiiCrypto.emailHash` reuse; `$set("[ERASED]")` encrypts-and-decrypts-back while `$set(emailHash,null)`+`@Field(write=NON_NULL)` omits the key (avoids the F03 `$unset` CCE); `SchedulerCheckpointService.start/complete/registerReplayAction` + `@PostConstruct`-before-`ApplicationReadyEvent` replay; Clock discipline with the stale-checkpoint missed-fire exception; `RbacEndpointInventoryTest` stays green with per-method/class `@PreAuthorize`.

## Multi-Role Sub-Agent Review — IMPLEMENTATION stage (Constitution C6, 2026-06-15)

Reviewers: **Security/GDPR**, **Backend correctness**, **Front-End** — all reviewed the implemented code. Verdicts: Security APPROVE-WITH-CHANGES; Backend ship-ready on the GDPR-critical paths (2 MAJOR); Front-End APPROVE-WITH-CHANGES (1 MAJOR). **No BLOCKER.** All three verified the core GDPR guarantees hold in code: de-identification (emailHash `$unset`, PII→`[ERASED]`), idempotent CAS audit-once, fail-closed positive-eval gate, non-PII enum-only append-only audit, no-PII logs (`toString` omits PII), correct RBAC, and CSRF (the app wires `withXsrfConfiguration`). Findings applied:

| # | Role | Severity | Finding | Resolution |
|---|------|----------|---------|------------|
| I1 | BE | MAJOR | `ErasureRequestController.list` accepted a `status` param but ignored it (always PENDING) — silent wrong-data | Removed the dead param; documented pending-only (matches the contract + frontend) |
| I2 | BE | MAJOR | Candidate audit read filtered by `workspaceId` but no index covered it (ChangeUnit001 is `{candidateId,occurredAt:-1}`, wrong sort direction) | Added `auditLog {workspaceId,candidateId,occurredAt}` to ChangeUnit005 (not yet deployed) |
| I3 | SEC | MAJOR (FE) | Erasure-queue **reject** silently hardcoded reason `OTHER` — no reason picker, defeating the audit trail | Added a localized reason `<select>` per row; the chosen `ErasureReasonCode` is sent |
| I4 | SEC | MINOR | `recordBasis`/`withdrawBasis` lacked an `erasureState==ACTIVE` guard → post-erasure basis mutation | Added `.and("erasureState").is(ACTIVE)` to both (no-op + no audit on an erased record) |
| I5 | FE | MINOR | Retention-delete was single-click (destructive); `retentionFlaggedAt` not shown | Added a two-step confirm (mirroring erasure-action) + render `retentionFlaggedAt` |
| I6 | BE/SEC | MINOR/NIT | Wipe Javadoc said `findAndModify` (code uses `updateFirst`); unused `findByIdAndWorkspaceId`; scan comment overstated the activity-refresh path; duplicate `id="cid"`; audit `track` key | Javadoc corrected; dead method removed; comment trimmed; ids namespaced (`erase-cid`/`audit-cid`); `track $index` |

After fixes: backend F04 suite + `RbacEndpointInventoryTest` green; frontend build clean + 16 unit tests pass.

## Notes

- F04 implementation is **complete and verified**: 12 backend test classes green (gate truth-table incl. fail-closed/precedence; raw-driver ciphertext + emailHash-key-absent; 20-thread concurrent single-wipe/single-audit; indistinguishable 200-not-404; audit survives erasure; retention strict boundary + stale-checkpoint missed-fire + flag-clear; double-confirm 409; append-only structural test; TRACE sentinel log scan); full suite (F01+F02+F03+F04) green including `RbacEndpointInventoryTest`; frontend `ng build` clean + `ng test` 16/16. CI `ci.yml` extended with the candidate-PII sentinel scan.
- **Two tasks deferred this session** (noted in tasks.md, unchecked): **T050** (Playwright E2E — needs a running stack; the unit+integration coverage is comprehensive) and **T053** (standalone `CandidateRestartPersistenceTest` — SC-006 ciphertext-at-rest is already verified by the raw-driver read in `CandidateErasureIntegrationTest` + the proven F03 cold-template helper, which is present in `GdprItBase`).
- Items marked incomplete require spec updates before `/speckit.plan`.
- Scope-boundary decisions are recorded as documented Assumptions (F04 = GDPR foundation, not a candidate-management UI; candidate creation owned by F13/F40/F41/F42 through F04's canonical-record contract; retention enforcement binds in F04 per F03 FR-022; email-gate enforced at dispatch in F22; candidate-facing erasure submission wired by F30), per the speckit informed-guess guidance and the F02/F03 precedent.
- Both previously-flagged decisions are now **resolved** (`/speckit.clarify` Session 2026-06-15): retention enforcement ships **inside F04** (not split); default lawful basis on create is **fail-closed** (no workspace-default auto-basis). No open clarification items remain — ready for `/speckit.plan`.
