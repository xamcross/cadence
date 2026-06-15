# Implementation Plan: GDPR Baseline — Consent, Erasure & Audit Log

**Branch**: `005-gdpr-baseline` | **Date**: 2026-06-15 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/005-gdpr-baseline/spec.md`

## Summary

Lay the **GDPR foundation** that makes candidate personal data lawful to hold *before* any feature creates candidates (F04 precedes F13/F40/F42 in the delivery sequence). F04 introduces the **candidate** data-subject record (PII encrypted at rest, reusing the F01 `PiiStringConverter`/`emailHash` pattern) and four privacy guarantees keyed to a non-PII internal id:

1. **Lawful-basis (email consent) tracking** + a **contact-permission gate** (quad-state, **fail-closed**) that F22 will consult before every dispatch.
2. **Right to erasure** via one **shared wipe** — idempotent, indistinguishable (no existence oracle), destroying the email-derived key (not merely hiding it) — used by the operator-triggered, candidate-initiated, and retention-driven paths.
3. **Append-only per-candidate audit log** (`auditLog`, closed-enum codes, no free-text) that **survives erasure** and is the accountability record (FR-18).
4. **Retention enforcement** consuming F03's configured period: a checkpointed `@Scheduled` scan flags over-age records (age basis `lastContactAt`, strict boundary, self-clearing), the gate denies them, and an Administrator confirms deletion.

The load-bearing engineering decisions: erasure **destroys `emailHash`** (the deterministic HMAC) so the subject cannot be re-identified, and writes the completion audit **only on the CAS-winner** (idempotent guarded `findAndModify`); the candidate audit log is **non-PII by construction** (closed-enum codes, no free-text value column); candidate creation is a **service-seam forward contract** with **no HTTP create endpoint** in F04; the retention scan reuses the **F00.2 `SchedulerCheckpoint`** pattern (with a documented Clock caveat — the checkpoint service uses `Instant.now()`, so missed-fire is tested via a stale `RUNNING` row, not a `MutableClock`); and every authenticated endpoint sits under `/api/internal/**` so the F02 `RbacEndpointInventoryTest` enforces its role. **No new runtime dependency, no topology change** — single Spring Boot instance + MongoDB only; PII crypto, audit, and scheduler infrastructure are all reused.

## Technical Context

**Language/Version**: Java 21 (backend, toolchain pinned); TypeScript 5.4 / Angular 17.3 (frontend)
**Primary Dependencies**: Spring Boot 3.3.5 (web, data-mongodb, security w/ method security, actuator, aop, **scheduling**); Mongock 5.4.4; logstash-logback-encoder 9.0. **No new backend or frontend runtime dependency** — PII crypto reuses `PiiCrypto`/`PiiStringConverter`/`MongoPiiConfig`; the retention scan reuses `SchedulerCheckpointService` + `@Scheduled`. Test-only: `spring-security-test` (already present) for per-role authority post-processors.
**Storage**: MongoDB 7.x (Atlas prod, Testcontainers `mongo:7` tests). Adds three collections — **`candidates`** (data-subject record, PII encrypted), **`auditLog`** (candidate-keyed, append-only — index pre-created by ChangeUnit001), **`erasureRequests`**. Reads `WorkspaceConfig` (F03 retention period) and reuses `members`/`sessions` (actor) and `schedulerCheckpoints` (F00.2).
**Testing**: JUnit 5 + Testcontainers (integration: raw-driver ciphertext + residual-emailHash assertion, audit-survives-erasure, retention boundary + stale-checkpoint replay, cold-`MongoTemplate` restart-persistence), MockMvc (per-role 5×surface RBAC matrix + indistinguishable-erasure + TRACE secret/PII log scan), Mockito (unit: gate truth-table parameterized, validation), Jasmine (frontend GDPR route guards per role), Playwright (E2E: Admin GDPR surfaces + non-admin redirect).
**Target Platform**: Fly.io single Machine (backend JAR), Cloudflare Pages (Angular SPA), MongoDB Atlas.
**Project Type**: Web application (Angular frontend + Spring Boot backend).
**Performance Goals**: Gate is a single pure read (no write), cheap enough to call before every send (F22). Erasure is synchronous and O(1) (one `$set` + one append), well within the 2-second handler bound (SC-003). Retention scan is a daily checkpointed batch on an indexed predicate; no hot-path cost.
**Constraints**: Single instance + MongoDB only — no Redis/queue/cache, no object store (constitution §IV / C2); candidate PII encrypted at rest (non-deterministic, queried only via keyed hash) and never returned/logged (§VIII, FR-002/FR-023/FR-024); erasure destroys the email-derived key (FR-006); append-only candidate audit (FR-015); gate fails closed (FR-004); zero PII/secret in logs incl. DEBUG/TRACE (SC-010); zero tool downloads (§X); any new `.ps1` pure ASCII (§V).
**Scale/Scope**: MVP single workspace (tens–hundreds of members; candidate volume grows with later features). 7 user stories, 24 FRs (+FR-006a), 16 SCs.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Gate | Question | Result |
|---|---|---|
| **C1** | Within MVP scope (spec §11)? | ✅ PASS — F04 is Tier-0 Foundation MVP (§6 Privacy & compliance, §5.3 FR-18; constitution §VIII mandates consent + erasure + retention in the MVP). SAR/Art.15-20, EU residency deferred & documented; retention *enforcement* confirmed in-scope (Clarifications 2026-06-15). |
| **C2** | New service, queue, or replica? | ✅ PASS — three new MongoDB collections on the existing instance; retention scan uses `@Scheduled` + `SchedulerCheckpoint` (the §IV async rule — no broker); no cache/replica/object-store. |
| **C3** | Exposes candidate PII to unauthorized roles? | ✅ PASS — F04 *strengthens* candidate-PII protection: encrypted at rest, never logged, all operator surfaces Admin/Recruiter-only (FR-021), erasure + retention actively reduce held PII. No public/candidate read of candidate PII ships in F04. |
| **C4** | Dependency outside the fixed stack? | ✅ PASS — **zero new dependencies**; reuses Spring Security method security, F01 PII crypto, F00.2 scheduler. |
| **C5** | New/modified Windows scripts contain non-ASCII? | ✅ PLANNED — no new `.ps1` expected; the `ci.yml` change is YAML (LF). Any script change byte-scanned before done. |
| **C6** | Multi-role sub-agent review (≥3) scheduled? | ✅ DONE+SCHEDULED — spec reviewed by 4 roles; this plan reviewed by ≥3 roles (user-requested) before tasks; final review at task close. |
| **C7** | Downloads any build tool/runtime/CLI? | ✅ PASS — cached Gradle 9.4.0, installed JDK 21, existing Angular CLI; no downloads. |

**Initial gate: PASS** (no new dependency, no topology change, no stack change).

### Post-Design Re-Check (after Phase 1 + §VI plan review)

Completed after the multi-role plan review (Backend/DevOps, Security/GDPR, QA, Front-End — Backend & Security APPROVE-WITH-CHANGES, QA CHANGES-REQUESTED, Front-End REQUEST-CHANGES). All findings dispositioned in `checklists/requirements.md`; load-bearing corrections folded into `research.md`/`data-model.md`/`contracts/gdpr-api.md`/this plan. **Result: PASS, unchanged gate status** — every correction was a design/test precision fix; none added a dependency, service, or topology, and none moved a gate to FAIL.

BLOCKER/MAJOR-class items resolved in the artifacts:
1. **`[ERASED]` vs the encrypting converter** (BE/SEC-MAJOR) — `$set("email","[ERASED]")` stores *ciphertext of the marker*; the SC-002/SC-006 test now asserts read-back-decrypts-to-`[ERASED]` + raw `emailHash` **key absent**, not a literal-marker raw match (research D2, data-model §1).
2. **Erasure unknown-id → 200, not 404** (QA-MAJOR) — erasure does NOT use `ScopedNotFoundException`; byte-identical response across missing/erased/fresh (contracts, data-model §5).
3. **Gate positive-evaluation** (SEC-MAJOR) — permit ONLY on the explicit-good row; any unknown/corrupt state denies (research D4, data-model §6).
4. **CI scan would pass vacuously** for candidate name/phone (SEC/QA-MAJOR) — switched to seeded high-entropy **sentinels** + literal grep + a positive vacuity guard, driving the decrypt/read path (research D10).
5. **SC-003 non-blocking** is now a **structural O(1)** assertion (one `$set` + one append vs large history), not a flaky wall-clock bound (research D11, plan tests).
6. **Retention-delete must guard on `retentionFlagged==true`** (BE-MAJOR) — never wipe an unflagged ACTIVE candidate (contracts, research D8).
7. **Frontend guard API was fiction** (`requireRole`/`hasAnyRole`) — corrected to `roleGuard(...roles)`; per-surface role mapping, shell nav entries (Recruiter erasure reachable), candidate-id paste-field, all-four-surface guard specs (research D13, plan frontend tree).
8. **Append-only is now structural** — `CandidateAuditEvent` repo extends a narrow `Repository<>` (no `delete*`), audit codes are **enums** not Strings, `seq` replaced by the monotonic `_id` tiebreaker (data-model §2).
9. **CSRF** required on all mutating endpoints; tests use `.with(csrf())` (contracts, plan tests).

Key gate confirmations:
- **C2 holds** — collections on the existing instance; scan via `@Scheduled`+checkpoint (no broker); PII converter is a per-`(class,field)` registration on the existing `MongoCustomConversions` bean (no new bean).
- **C3 holds** — every candidate-PII surface is Admin/Recruiter-only; audit/gate/retention reads expose internal ids + codes only; erasure de-identifies (destroys `emailHash` key).
- **C4 / C7 unchanged** — zero new runtime dependencies, zero downloads.
- **§VIII** — encryption-at-rest + keyed-hash lookup (FR-002), fail-closed positive-eval gate (FR-004), shared wipe destroying the email-derived key (FR-006/S1), append-only enum-coded audit surviving erasure (FR-010/FR-014/FR-015), zero-PII logs with the sentinel-extended CI scan (FR-024/SC-010).

## Project Structure

### Documentation (this feature)

```text
specs/005-gdpr-baseline/
├── plan.md              # This file
├── research.md          # Phase 0 — decisions D1..D14
├── data-model.md        # Phase 1 — Candidate, CandidateAuditEvent, ErasureRequest; indexes; gate truth table
├── quickstart.md        # Phase 1 — local run + manual verification
├── contracts/
│   └── gdpr-api.md       # Phase 1 — REST + service-only (forward) contracts; RBAC matrix
├── checklists/
│   └── requirements.md  # Spec quality + spec/plan review logs
└── tasks.md             # Phase 2 (/speckit.tasks — NOT created here)
```

### Source Code (repository root)

```text
backend/src/main/java/com/cadence/
├── api/
│   ├── CandidateGdprController.java   # NEW — /api/internal/candidates/{id}/{erasure,basis,audit}
│   │                                  #   erasure+basis @PreAuthorize ADMIN|RECRUITER; audit ADMIN
│   ├── ErasureRequestController.java   # NEW — /api/internal/erasure-requests (list/confirm/reject), ADMIN
│   ├── RetentionController.java        # NEW — /api/internal/retention/{flagged,{id}/delete}, ADMIN
│   ├── GdprDtos.java                   # NEW — request/response records; NO candidate PII on any response
│   │                                  #   (codes/booleans/internal ids only); reason is a closed enum
│   └── GdprExceptionHandler.java       # NEW — @RestControllerAdvice → {error,message}; invalid_basis/
│                                       #   invalid_reason (400), already_resolved (409); reuses F01/F02 envelope
├── domain/
│   ├── Candidate.java                  # NEW — @Document("candidates"); name/email/phone (encrypted), emailHash
│   │                                  #   (@Field write=NON_NULL), lawfulBasis+withdrawal, erasureState+erasedAt,
│   │                                  #   retentionFlagged, lastContactAt; hand toString() omits PII
│   ├── CandidateAuditEvent.java        # NEW — @Document("auditLog"); candidateId, eventType(enum), outcome,
│   │                                  #   actorMemberId, occurredAt; ordered by (occurredAt,_id) — enum codes, NO free-text column
│   ├── ErasureRequest.java             # NEW — @Document("erasureRequests"); status, reasonCode(closed), decided*
│   ├── CandidateEventType.java         # NEW — closed enum (RECORD_CREATED..RETENTION_DELETED + forward types)
│   ├── LawfulBasis.java                # NEW — enum CONSENT|LEGITIMATE_INTEREST|CONTRACT
│   ├── ErasureState.java               # NEW — enum ACTIVE|ERASED
│   └── RequestStatus.java              # NEW — enum PENDING|RESOLVED_CONFIRMED|RESOLVED_REJECTED
├── repository/
│   ├── CandidateRepository.java        # NEW — findByWorkspaceIdAndEmailHash (non-unique); retention finders
│   ├── CandidateAuditEventRepository.java # NEW — narrow Repository<> (declared insert + findByCandidateIdOrderByOccurredAtAscIdAsc); NO delete*/update*
│   └── ErasureRequestRepository.java   # NEW — findByWorkspaceIdAndStatus
├── service/
│   ├── CandidateService.java           # NEW — create(...) canonical seam (D6): defaults, emailHash, optional basis,
│   │                                  #   RECORD_CREATED audit; record/withdraw basis. NO HTTP create endpoint.
│   ├── CandidateErasureService.java    # NEW — wipe(id,reason,actor): guarded findAndModify(ACTIVE), $set [ERASED]/
│   │                                  #   emailHash null, CAS-winner-only ERASURE_COMPLETED audit (D2); idempotent
│   ├── ContactPermissionGate.java      # NEW — evaluate(id) quad-state, fail-closed, fixed precedence (D4)
│   ├── ErasureRequestService.java      # NEW — requestErasure(id,reasonCode) intake (PII-free); confirm/reject guarded (D7)
│   ├── RetentionService.java           # NEW — flag/clear logic + Admin-confirmed delete (delegates wipe)
│   └── CandidateAuditService.java      # NEW — append-only writer; injected Clock; enum-only append signature; non-PII (D3)
├── scheduler/
│   └── RetentionScanTask.java          # NEW — @Scheduled daily; SchedulerCheckpoint start/complete + replay action;
│                                       #   lastContactAt < now-retention (strict), residual erasure/flag filter; self-clear
├── config/
│   ├── MongoPiiConfig.java             # MODIFIED — register PiiStringConverter for Candidate name/email/phone (D1)
│   └── migration/
│       └── ChangeUnit005_GdprIndexes.java # NEW — non-unique candidates{workspaceId,emailHash};
│                                       #   erasureRequests{workspaceId,status}. (auditLog + lastContactAt pre-exist)
└── (no security/* change — reuses F02 @Order(3) chain + RestAccessDeniedHandler unchanged)

backend/src/test/java/com/cadence/
└── gdpr/
    ├── ContactPermissionGateTest.java        # PURE Mockito UNIT (mocked CandidateRepository → hand-built Candidate states;
    │                                          #   EXEMPT from the seed-via-create rule): PARAMETERIZED truth table (SC-001),
    │                                          #   permit ONLY on the explicit-good row, precedence overlaps, fail-closed on
    │                                          #   error/missing/null/unrecognized (D4/S6). Integration permutations reach
    │                                          #   erased/withdrawn/flagged via REAL transitions, not raw field-setting.
    ├── CandidateErasureIntegrationTest.java   # US2: read-back-through-converter == "[ERASED]" + RAW-driver emailHash KEY ABSENT +
    │                                          #   ciphertext not original (SC-002/SC-006/S1; marker is stored ENCRYPTED, not literal);
    │                                          #   idempotent re-erase; concurrent triggers via CountDownLatch (N>=20) → ONE wipe,
    │                                          #   audit rows +EXACTLY 1 (losers append NOTHING) (SC-005); unknown-id → 200 BYTE-IDENTICAL
    │                                          #   to erased/fresh, NOT 404 (FR-009); wipe is O(1) (1 $set + 1 append) vs large seeded
    │                                          #   history — assert on the SERVICE call, not MockMvc wall-clock (SC-003)
    ├── CandidateAuditIntegrationTest.java     # US3: each event → one non-PII entry; ordered (occurredAt,_id) incl. same-tick (distinct _id);
    │                                          #   audit SURVIVES erasure — capture pre-wipe set, assert BYTE-IDENTICAL after + exactly one
    │                                          #   new ERASURE_COMPLETED (SC-008); append-only — reflectively assert repo has NO delete*/update*
    │                                          #   method + no DELETE/PUT/PATCH audit mapping (SC-007); empty/unknown read non-oracle
    │   # SC-013/SC-016 lifecycle assertions are FOLDED into existing tests (tasks-stage review): record→withdraw→re-record
    │   # flips the gate → CandidateBasisIntegrationTest (T025); erase→re-create independent record + create production-path
    │   # → CandidateErasureIntegrationTest (T030); the no-HTTP-create-endpoint route check → AuditAppendOnlyTest (T021).
    ├── ErasureRequestIntegrationTest.java     # US4: request(PII-free, enum reasonCode)→pending→confirm wipes+audits; reject no-wipe;
    │                                          #   double/concurrent confirm → 409/single wipe, audit +exactly 1 (SC-015/Q6);
    │                                          #   reject missing/unknown reasonCode → 400, stays PENDING (validation)
    ├── RetentionIntegrationTest.java          # US5: boundary fixtures set lastContactAt EXPLICITLY (sanctioned create() deviation) —
    │                                          #   at-period NOT flagged, one-tick-over IS (strict <, SC-014/Q4); gate denies over_retention;
    │                                          #   retention-delete guarded on retentionFlagged==true (unflagged ACTIVE NOT wiped, BE-MAJOR);
    │                                          #   lengthen→flag CLEARED → gate permits IFF basis recorded first, else deny no_basis (Q5/Q8);
    │                                          #   missed-fire via STALE RUNNING checkpoint (SC-009/B4), NOT MutableClock
    ├── GdprRbacContractTest.java              # SC-004: 5 surfaces × roles, ALL mutating calls .with(csrf()) (BE-MAJOR) → erasure/basis
    │                                          #   {ADMIN,RECRUITER ok; others 403}; audit/request/retention {ADMIN ok; 4 others 403};
    │                                          #   each 403 re-read asserts erasureState/basis/flag + audit-count UNCHANGED (no state change)
    ├── CandidateRestartPersistenceTest.java   # SC-006 cold path: re-read via a COLD MongoTemplate (new client + fresh
    │                                          #   MongoPiiConfig converter) decrypts PII to original (F03 cold-template pattern)
    └── GdprLogPiiScanTest.java                # SC-010: root TRACE; drive create→basis→gate→erase→request→retention→error;
                                               #   assert seeded name/email/phone (+message-content sentinels) absent at any level

frontend/src/app/
├── features/admin/gdpr/
│   ├── candidate-audit.component.ts      # NEW — Admin audit-log view; candidate id via PASTE/TEXT FIELD (no browser — F51);
│   │                                     #   i18n="@@gdpr.audit.*"; empty-state; role="alert" errors
│   ├── erasure-queue.component.ts         # NEW — Admin pending erasure-request queue (confirm/reject); empty-state
│   ├── retention-review.component.ts      # NEW — Admin flagged-records review + confirm delete; empty-state
│   ├── candidate-erasure-action.ts        # NEW — Admin|Recruiter erasure trigger + record/withdraw basis;
│   │                                     #   CONFIRM step before destructive erasure; $localize programmatic strings
│   └── gdpr.service.ts                    # NEW — typed HTTP for /api/internal GDPR endpoints; $localize error text
├── core/auth/role.guard.ts                # REUSED UNCHANGED — actual API is roleGuard(...roles: Role[]) varargs
│                                          #   (NO requireRole/hasAnyRole — already supports the mixed case)
├── features/shell/shell.component.ts      # MODIFIED — add Admin GDPR nav links under @if(role==='ADMIN'); add a
│                                          #   @if(role==='ADMIN' || role==='RECRUITER') entry for the erasure action
│                                          #   (else the Recruiter erasure surface is unreachable — US7 AS-2)
└── app.routes.ts                          # MODIFIED — erasure-action+basis → roleGuard('ADMIN','RECRUITER');
                                           #   candidate-audit/erasure-queue/retention-review → roleGuard('ADMIN')

frontend/src/app/features/admin/gdpr/candidate-audit.component.spec.ts     # Jasmine: Admin passes; each non-Admin → /not-authorized
frontend/src/app/features/admin/gdpr/erasure-queue.component.spec.ts       # Jasmine: Admin-only guard
frontend/src/app/features/admin/gdpr/retention-review.component.spec.ts    # Jasmine: Admin-only guard
frontend/src/app/features/admin/gdpr/candidate-erasure-action.spec.ts      # Jasmine: RECRUITER passes; HM/Interviewer/Read-only redirect
frontend/src/app/core/auth/role.guard.spec.ts                              # MODIFIED — cover BOTH guard-arg sets per role (SC-011):
                                                                           #   roleGuard('ADMIN') and roleGuard('ADMIN','RECRUITER')
frontend/e2e/gdpr.spec.ts                                                  # Playwright: Admin GDPR surfaces; Recruiter erasure; non-admin redirect

.github/workflows/ci.yml                                                # MODIFIED — extend the PII log-scan with CANDIDATE
                                                                        #   name/email/phone patterns (existing scan = member emails + F03 secrets)
```

**Test isolation note (shared singleton container)**: every new test class MUST clean `candidates`, `auditLog`, `erasureRequests`, and `authAuditLog` in `@BeforeEach` with `mongoTemplate.remove(new Query(), Type.class)` — never `dropCollection` (which drops the Mongock `001`/`005` indexes; CLAUDE.md F00.1 lesson). Any retention test base MUST **mandatorily** also clean `SchedulerCheckpoint.class` (a leftover `COMPLETED`/`RUNNING` row for the scan `taskName` contaminates the missed-fire/replay test — F00.1 cross-class lesson). **Seeding rule**: candidates are created via `CandidateService.create` (production-path), not raw `save` — with two **sanctioned deviations**: (a) the gate unit test hand-builds states over a mocked repo (no Mongo); (b) retention boundary fixtures set `lastContactAt` explicitly after `create` (the only way to reach a specific past instant).

**Structure Decision**: Web-application layout (constitution Reference Source Layout). F04 *extends* the F01/F02/F03 scaffold — three controllers (all `/api/internal/**`, Admin/Recruiter-scoped), three new collections + repositories, six services + one scheduled task, one Mongock changeset (`005`), and a small Admin/Recruiter frontend feature. It reuses the F02 `@Order(3)` security chain + `RestAccessDeniedHandler` unchanged, the F01 PII crypto + keyed-hash lookup, and the F00.2 scheduler checkpoint. It modifies exactly: `MongoPiiConfig` (+3 candidate fields) and `ci.yml` (candidate PII scan). No new top-level structure, no new dependency.

## Complexity Tracking

| Decision | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| Introduce the `Candidate` record now, with **no HTTP create endpoint** (creation is a service-seam forward contract) | F04 is the GDPR baseline and sits *before* any candidate-creating feature; consent/erasure/audit/retention need a real record to govern, and the create contract must centralize the GDPR defaults so F13/F40/F42 cannot diverge | Deferring the record to F13 would make F04's erasure/audit/gate untestable and unshippable (no subject to govern). Shipping a candidate-management API now over-scopes into F13/F42 territory (creation, dedup, CSV). The seam is a real §II contract (consumers land next in sequence and call it), exercised production-path by tests (SC-016) — not a stub. |
| Retention enforcement (scan + flag + gate-deny + Admin-confirmed delete) shipped in F04 | F03 FR-022 deferred enforcement to F04 and the stakeholder confirmed it ships here (Clarifications 2026-06-15); ISSUE-10 forbids display-only retention | Splitting it to its own item was the alternative but was explicitly rejected by the stakeholder. The *dispatch-time* comms-block remains F22's (no outbound path exists in F04), so F04 builds only what has a surface today (scan/flag/gate/confirm-delete) — not a stub. |
| Erasure destroys `emailHash` (not just stops querying it) + CAS-winner-only audit | The deterministic HMAC would otherwise re-identify the "erased" subject from a known email (Security BLOCKER S1); the guard makes erasure idempotent and audit-exact under concurrency | Leaving `emailHash` (merely unqueryable) fails Art. 17 de-identification — the central claim of the feature. A non-guarded wipe would double-audit/double-wipe under concurrency. |
| Candidate audit log = closed-enum codes, **no free-text value column** | Makes "non-PII" structural, not merely intended (Security S4) — a free-text column is the exact PII-leak vector, and the log must survive erasure as a clean accountability record | A `details` free-text field is simpler but would let any caller embed a candidate name/email, defeating FR-017 and making the surviving-erasure log itself re-identifying. |
| Reuse `SchedulerCheckpointService` **without** refactoring it to a `Clock` | The F00.2 checkpoint is proven and shared; refactoring it touches an unrelated subsystem and risks the existing F00.2 tests | Adding a `Clock` to the checkpoint would make the missed-fire test MutableClock-driven, but it is a larger cross-cutting change for marginal test convenience; F04 instead tests missed-fire via a seeded stale `RUNNING` row (the F00.2 test approach). Documented caveat, not a silent gap. |
