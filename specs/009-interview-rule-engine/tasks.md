---
description: "Task list for F12 — Interview Template & Rule Engine"
---

# Tasks: Interview Template & Rule Engine (F12)

**Input**: Design documents from `/specs/009-interview-rule-engine/`
**Prerequisites**: plan.md, spec.md, research.md (D1–D14), data-model.md, contracts/interview-template-api.md, quickstart.md

**Tests**: INCLUDED and TDD-ordered — constitution §VII (Test-First & Acceptance-Driven) is mandatory and plan.md enumerates the test files. Each story's tests are written FIRST and MUST fail before its implementation.

**Organization**: By user story. US1 (template management) and US2 (the rule engine) are P1; US3 (inheritance/override) is P2. **US1 is the MVP slice and a demonstrable end-to-end leg (§II)** — template CRUD browser→DB; **US2 adds the rule engine + the recruiter slot-preview** (the second §II leg). US2 depends on US1's persisted template existing.

**Reuse posture**: F12 is new in-stack business logic with **zero new dependency/infra/scheduler** (research D13). It consumes the **unchanged** `AvailabilityService.query` (D1), the F03 `WorkspaceConfig`/`WorkingHours`, the F02 RBAC + `RbacEndpointInventoryTest`, and the F10/F11 stub harness for seeded availability. The one additive change to existing code is a `ManagedCalendarEventRepository` count method + a second index on `managedCalendarEvents` (D5/D7). One new collection `interviewTemplates`; one new Mongock changeset `ChangeUnit008` (order "008", off the highest applied `007`).

## Format: `[ID] [P?] [Story?] Description with file path`

- **[P]**: parallelizable (different file, no dependency on an incomplete task)
- **[Story]**: US1/US2/US3 (story phases only)
- Backend root: `backend/src/main/java/com/cadence/`; tests: `backend/src/test/java/com/cadence/interview/`; frontend: `frontend/src/app/features/interview-templates/`

## Run flags (CLAUDE.md — every backend test/build invocation)

`JAVA_HOME=C:/jdk-24.0.1`, cached `gradle-9.4.0` binary, `-Dapi.version=1.41`, `DOCKER_HOST=npipe:////./pipe/docker_engine`, `-Dorg.gradle.java.installations.auto-download=false` (Principle X — zero downloads). First multi-class Testcontainers run after a recompile may throw the one-time `GenericContainer` class-init error — re-run.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: configuration + CI plumbing.

- [x] T001 In `backend/src/main/resources/application.yml` add an `interview.template.*` block: `default-slot-cadence-minutes: 15` (D3), and per-template caps `max-members: 25`, `max-pools: 10`, `max-blackouts: 50` (FR-024/D8). Reuse `calendar.api.max-window` for the compute-window clamp (do NOT add a new window key). No secrets.
- [x] T002 [P] Create `InterviewTemplateProperties` (`@ConfigurationProperties("interview.template")`) in `backend/src/main/java/com/cadence/config/InterviewTemplateProperties.java` binding the keys from T001 (auto-registers via the existing `@ConfigurationPropertiesScan`).
- [x] T003 [P] Extend the CI PII/log scan in `.github/workflows/ci.yml` with an `interviewTemplates` **template-name sentinel** (a known string seeded into a template name in `InterviewTemplateLogPiiScanTest`) so a regression that logs the name fails CI (D10, SC-010).

**Checkpoint**: config binds; CI gate declared.

---

## Phase 2: Foundational (Blocking Prerequisites)

**⚠️ CRITICAL**: every user story depends on this phase — the persisted domain, the repositories, the Mongock changeset, and the appended audit-event values.

- [x] T004 Create the persisted domain in `backend/src/main/java/com/cadence/domain/`: `InterviewTemplate` (`@Document("interviewTemplates")` — fields per data-model §1; `toString()` MUST omit `name`, may include member ids), the embedded `PoolRule` (`{ List<String> memberIds; int n; }`), the embedded `BlackoutPeriod` (`{ Instant start; Instant end; }`), and the `TemplateStatus` enum (`ACTIVE`, `RETIRED`). Ids + instants only — no PII/secret, no encryption converter (data-model §1).
- [x] T005 [P] Append four values to `AuthEventType` in `backend/src/main/java/com/cadence/domain/AuthEventType.java` — `INTERVIEW_TEMPLATE_CREATED`, `INTERVIEW_TEMPLATE_UPDATED`, `INTERVIEW_TEMPLATE_RETIRED`, `INTERVIEW_TEMPLATE_COMPUTE_REFUSED` (append-only — never reorder; D10).
- [x] T006 [P] Create `InterviewTemplateRepository extends MongoRepository<InterviewTemplate,String>` in `backend/src/main/java/com/cadence/repository/InterviewTemplateRepository.java` with `findByWorkspaceIdAndStatus(...)`, `findByWorkspaceId(...)`, and `findByWorkspaceIdAndId(String workspaceId, String id)` (scoped read → indistinguishable not-found, FR-006).
- [x] T007 [P] Add the daily-cap count method to `backend/src/main/java/com/cadence/repository/ManagedCalendarEventRepository.java`: `long countByWorkspaceIdAndMemberIdAndStatusNotInAndStartAtGreaterThanEqualAndStartAtLessThan(String workspaceId, String memberId, java.util.Collection<EventStatus> excluded, Instant dayStart, Instant nextDayStart)` (exclusion list `{DELETED,CLEANUP_INCOMPLETE}`, half-open day bound — D5/data-model §2). Additive; do not change existing methods.
- [x] T008 Create `ChangeUnit008_InterviewTemplateIndexes` (`@ChangeUnit(id="008-interview-template-indexes", order="008", author="system")`) in `backend/src/main/java/com/cadence/config/migration/ChangeUnit008_InterviewTemplateIndexes.java` creating, via native `createIndex`: `interviewTemplates {workspaceId,status}` (non-unique) and `managedCalendarEvents {workspaceId,memberId,startAt}` (non-unique); `@RollbackExecution` does targeted `dropIndex` of each (never `dropIndexes()`). Order "008" derives off the highest APPLIED changeset `007`, NOT the branch number (D7).
- [x] T009 Add an index-bootstrap assertion (extend the existing index test, e.g. `IndexBootstrapTest`, or a sibling under `backend/src/test/java/com/cadence/interview/`) verifying `listIndexes` shows both ChangeUnit008 indexes after startup. Clean via `mongoTemplate.remove(...)`, never `dropCollection` (CLAUDE.md F00.1).

**Checkpoint**: domain + repos + migration + audit values exist; stories can begin.

---

## Phase 3: User Story 1 - Create and manage an interview stage template (Priority: P1) 🎯 MVP

**Goal**: Recruiter/Admin can create, list, read, edit, and retire (soft-delete) a workspace-scoped interview template with full validation, browser→DB.

**Independent Test**: as a Recruiter, create a template, read it back (all fields intact), edit duration, retire it (leaves active list, still resolvable); as Interviewer/Read-only, create/edit is refused 403; an invalid field → value-free 400, nothing persisted; a foreign-workspace template id → 404.

### Tests for User Story 1 (write FIRST, must FAIL)

- [x] T010 [P] [US1] `InterviewTemplateValidationTest` (unit) in `backend/src/test/java/com/cadence/interview/InterviewTemplateValidationTest.java` — SC-008: each invalid case → **value-free** 400, 0 persisted: duration ≤ 0; slotCadence outside 1..duration; negative buffer; dailyCap < 1; zero required-and-pool; pool `n`=0 or > pool size; blackout end ≤ start; foreign-workspace member ref; member both required and in a pool; member in two pools; over-cap member/pool/blackout counts; invalid WH override (bad IANA zone, end ≤ start, overnight).
- [x] T011 [P] [US1] `InterviewTemplateCrudIntegrationTest` (Testcontainers) in `.../interview/InterviewTemplateCrudIntegrationTest.java` — create → read-back all fields; edit persists (non-retroactive); retire → `status=RETIRED`, not hard-deleted, still resolvable; each lifecycle writes the matching `INTERVIEW_TEMPLATE_*` audit row with **ids only** (no name/PII).
- [x] T012 [US1] `InterviewTemplateContractTest` (MockMvc) in `.../interview/InterviewTemplateContractTest.java` — the 5 CRUD endpoints × 5 roles (ADMIN/RECRUITER 200, HM/INTERVIEWER/READ_ONLY 403, per contract §D); cross-workspace `GET {id}` → 404 (scoped-not-found); foreign-workspace member in create → 400 `invalid_template`; and assert `RbacEndpointInventoryTest` stays green. *(US2 extends this same file for `/slots` — T029.)*

### Implementation for User Story 1

- [x] T013 [P] [US1] `InterviewTemplateExceptions` in `backend/src/main/java/com/cadence/api/InterviewTemplateExceptions.java` — `InvalidTemplateException` (→ 400 `invalid_template`, carries value-free per-field `details`) and `TemplateRetiredException` (→ 409 `template_retired`); reuse the F02 `ScopedNotFoundException` for 404. Wire into the existing `@RestControllerAdvice` envelope `{error, details}`.
- [x] T014 [P] [US1] `InterviewTemplateDtos` in `backend/src/main/java/com/cadence/api/InterviewTemplateDtos.java` — `CreateTemplateRequest`/`UpdateTemplateRequest`, `TemplateResponse` (per contract §C — `name` returned, member ids only, never email/display-name), and the field→TemplateResponse mapper.
- [x] T015 [US1] `InterviewTemplateService` in `backend/src/main/java/com/cadence/service/InterviewTemplateService.java` — CRUD + the full FR-002/FR-024 validation (incl. workspace-membership via `MemberRepository.findByWorkspaceId`, no dual-role, no member in two pools, per-template caps; **value-free** messages — D8/D10); soft-retire (FR-004); audit the three lifecycle events with ids only; log only ids/`.name()` Strings (never enums to `kv` — D10). Depends on T004, T006, T002.
- [x] T016 [US1] `InterviewTemplateController` in `backend/src/main/java/com/cadence/api/InterviewTemplateController.java` — `@RestController @RequestMapping("/api/internal/interview-templates")` with **class-level** `@PreAuthorize("hasAnyRole('ADMIN','RECRUITER')")` (D9); handlers `POST` (create), `GET` (list, `?status=`), `GET {id}` (scoped 404), `PUT {id}` (edit), `POST {id}/retire`; `@AuthenticationPrincipal SessionService.Principal` for workspace/actor (F03 pattern). Depends on T013, T014, T015.
- [x] T017 [US1] Run the US1 backend tests (T010–T012) with the run flags and confirm green.
- [x] T018 [P] [US1] `interview-templates.service.ts` in `frontend/src/app/features/interview-templates/interview-templates.service.ts` — typed HTTP client for the CRUD endpoints (relative `apiBaseUrl`).
- [x] T019 [US1] `interview-templates.component.ts` (standalone, Angular Material, `$localize`) in `.../interview-templates/interview-templates.component.ts` — list / create / edit / retire forms with client-side validation mirroring the server bounds. Depends on T018.
- [x] T020 [US1] In `frontend/src/app/app.routes.ts` add the `/interview-templates` route guarded to ADMIN/RECRUITER (reuse the F02 role guard); non-permitted roles routed to `/not-authorized`.
- [x] T021 [US1] `interview-templates.component.spec.ts` (Jasmine) in `.../interview-templates/interview-templates.component.spec.ts` — CRUD render + form-validation states. *(US2 extends this file for preview states — T036.)*

**Checkpoint**: US1 is fully functional and demonstrable end-to-end (template CRUD, RBAC-gated, validated, audited).

---

## Phase 4: User Story 2 - Compute compliant slots (the rule engine) (Priority: P1)

**Goal**: given a template + a date range, compute only the slots satisfying every rule against real availability; expose it as a Recruiter/Admin slot-preview (the second §II leg). F13/F14 own reservation/UI.

**Independent Test**: with seeded availability + a template, the engine returns exactly the compliant slots (duration, required-free + buffers, per-pool quorum on distinct positively-free members, daily cap, blackout, working hours, DST-correct), annotated per pool; an unknown required member → 0 slots + a distinguishable reason; `POST {id}/slots` returns the documented shape; a retired template → 409.

### Tests for User Story 2 (write FIRST, must FAIL)

- [x] T022 [P] [US2] `RuleEngineTest` (unit, seeded `MemberAvailability`) in `backend/src/test/java/com/cadence/interview/RuleEngineTest.java` — SC-001 per-rule, 0 violating slots: duration; cadence anchored to working-day start; required free + before/after buffers; blackout; working-hours fit (duration+buffers); **plus** duration > working-window → 0; blackout∩working-hours precedence (blackout wins); pool-of-1 "any 1" behaves like required; **optional member busy/unknown NEVER gates a slot** (silent-bug guard); a range wider than `max-window` → `windowClamped==true` and slots stop at the clamp (FR-017 positive test).
- [x] T023 [P] [US2] `RuleEngineDailyCapTest` (integration) in `.../interview/RuleEngineDailyCapTest.java` — SC-002: cap=2 with two seeded same-day `managedCalendarEvents` (status CREATED) → 0 more; within one computation never offers a 3rd for a required interviewer; `DELETED`/`CLEANUP_INCOMPLETE` rows do NOT consume cap; an event at 23:30 local (next UTC day) counts on the correct **zone-relative civil day**.
- [x] T024 [P] [US2] `RuleEngineDstTest` (unit) in `.../interview/RuleEngineDstTest.java` — SC-003 with pinned zone `America/New_York` (2026-03-08 spring-forward / 2026-11-01 fall-back) + injected `MutableClock`: the non-existent local hour → 0 slots (and a slot whose **buffer-after** lands in the gap → rejected); the repeated fall-back hour offered exactly once; the daily cap counted per civil day on the 23h/25h day; correct wall-clock instants.
- [x] T025 [P] [US2] `RuleEngineFailSafeTest` (unit) in `.../interview/RuleEngineFailSafeTest.java` — SC-004: a required member with each non-`DATA` status (`NOT_CONNECTED`/`NEEDS_RECONNECTION`/`TEMPORARILY_UNAVAILABLE`) → 0 slots, each mapped to its **own** distinguishable `UnschedulableReason`; a **busy** required member is NOT in `unschedulable`; an unknown-status pool member is excluded from the quorum (never counted free); a member that left the workspace → no availability → fail-safe (required → 0; pool quorum on the rest).
- [x] T026 [P] [US2] `RuleEnginePoolTest` (unit) in `.../interview/RuleEnginePoolTest.java` — SC-005: two pools each with a binding quorum → `qualifyingByPool` lists exactly the qualifying members per pool (distinguishing which member satisfies which pool).
- [x] T027 [P] [US2] `RuleEngineRangeTest` (unit) in `.../interview/RuleEngineRangeTest.java` — a range wholly in the past (via `MutableClock`) → `[]` (no past slot ever offered); range end ≤ start → `[]`; both no error (spec Edge Cases).
- [x] T028 [P] [US2] `RuleEnginePerfTest` (unit) in `.../interview/RuleEnginePerfTest.java` — SC-007 CI gate is the **deterministic, countable** property: `Mockito.verify(availabilityService, times(1)).query(...)` (exactly one panel read) and the cap-read multiplicity == distinct required-member count; **plus an `ArgumentCaptor` assertion that the member-id set passed to `query(...)` equals exactly the persisted template's validated members** (the D8 compute-path isolation control, contract §E — no extra/foreign ids); the latency number is a JIT-warmed median **logged informationally** under a generous hard cap — NOT a bare `<50 ms` wall-clock assertion (QA de-flake).
- [x] T029 [US2] Extend `InterviewTemplateContractTest` (the T012 file) — SC-006/009 for `POST {id}/slots`: full response shape (`slots` with `qualifyingByPool` + `unschedulable` + `windowClamped`); empty range → `[]` not error; retired template → 409 `template_retired` **and** an `INTERVIEW_TEMPLATE_COMPUTE_REFUSED` audit row is written; `Cache-Control: no-store` header; the `/slots` endpoint is in the 5-role matrix (ADMIN/RECRUITER 200, others 403).
- [x] T030 [P] [US2] `InterviewTemplateLogPiiScanTest` in `.../interview/InterviewTemplateLogPiiScanTest.java` — SC-010: TRACE scoped to `com.cadence`; drive CRUD + compute with a template-name sentinel actually fed through create→compute (non-vacuous) and a seeded member; assert the sentinel and any member email are ABSENT from logs; positive vacuity guard.

### Implementation for User Story 2

- [x] T031 [P] [US2] Create the transient engine I/O types in `backend/src/main/java/com/cadence/domain/`: `SlotComputationRequest` (`{workspaceId, templateId, rangeStart, rangeEnd}` — the internal `RuleEngine.compute` input, contract §E), `ComputedSlot` (start/end `Instant` + `zoneId` + `requiredMemberIds` + `Map<Integer,List<String>> qualifyingByPoolIndex`), `SlotComputationResult` (`slots` + `windowClamped` + `unschedulable`), `MemberUnschedulable` (`{memberId, UnschedulableReason}`), and `UnschedulableReason` enum (mirrors the non-`DATA` `AvailabilityStatus` values) — data-model §3.
- [x] T032 [US2] `RuleEngine` service in `backend/src/main/java/com/cadence/service/RuleEngine.java` (D2/D4/D6/D14): resolve template by `{workspaceId,id}` (RETIRED → `TemplateRetiredException`); resolve zone + working hours (override else workspace `WorkspaceConfig`, **by reference**); issue **one** `AvailabilityService.query` with **only the persisted template's validated member ids** (D8 isolation); per civil day, walk the cadence on `ZonedDateTime` anchored to working-day start, reject spring-forward gap starts via the `LocalDateTime` round-trip check (D4); apply required-free+buffer, per-pool quorum on distinct positively-free members, blackout, working-hours fit; enforce the daily cap for required members by reading `managedCalendarEvents` once per member over the window (`StatusNotIn`) and bucketing by zone civil day + a running in-computation counter (D5); inject `Clock` for future-only; emit deterministically-ordered `ComputedSlot`s (slots by start, member ids ascending). Log only ids/`.name()` Strings (D10). Depends on T031, T007, T015 (template read).
- [x] T033 [US2] Add the compute endpoint to `InterviewTemplateController` (the T016 file): `POST {id}/slots` taking `SlotPreviewRequest` (`rangeStart`/`rangeEnd` civil dates), returning `SlotComputationResult` 200 with `Cache-Control: no-store`; `TemplateRetiredException` → 409 + an `INTERVIEW_TEMPLATE_COMPUTE_REFUSED` audit (ids only); foreign id → 404. Add `SlotPreviewRequest` to `InterviewTemplateDtos`. Depends on T032.
- [x] T034 [US2] Run the US2 backend tests (T022–T030) with the run flags and confirm green.
- [x] T035 [P] [US2] `slot-preview.component.ts` (standalone) in `.../interview-templates/slot-preview.component.ts` — date-range form → `POST {id}/slots` → render the computed slots (per-slot qualifying participants) and a distinct "unschedulable" panel (reason per member); reuse `interview-templates.service.ts` (add the `slots` call). `$localize` all strings.
- [x] T036 [US2] Extend `interview-templates.component.spec.ts` (the T021 file) with the preview render states (slots / empty / unschedulable-reason).
- [x] T037 [US2] `interview-templates.spec.ts` (Playwright) in `frontend/e2e/interview-templates.spec.ts` — E2E: Recruiter creates a template → opens preview for a date range → sees computed slots (against the seeded stub).

**Checkpoint**: US2 functional — the rule engine + recruiter slot-preview work end-to-end; US1 still green.

---

## Phase 5: User Story 3 - Inherit workspace defaults, override per template (Priority: P2)

**Goal**: a template uses the workspace WH/time zone by default; a per-template override replaces them; a later workspace change is reflected for inheriting templates (by reference).

**Independent Test**: a template with no override → slots within workspace WH/zone; a template overriding to 07:00–11:00 / another zone → slots only within those; change the workspace WH → an inheriting template's computed slots shift accordingly.

### Tests for User Story 3 (write FIRST, must FAIL)

- [x] T038 [P] [US3] `RuleEngineInheritanceTest` (integration) in `backend/src/test/java/com/cadence/interview/RuleEngineInheritanceTest.java` — US3 AS-1/2/3: inherit workspace WH/zone when no override; use the override when present (validated to F03 bounds); after mutating the workspace `WorkingHours` (F03), an inheriting template's computed slots reflect the new hours (by-reference, not a creation-time copy).

### Implementation for User Story 3

- [x] T039 [US3] Confirm/refine the zone+WH resolution in `RuleEngine` (override-else-workspace, resolved live per computation) and the override-field validation in `InterviewTemplateService` (valid IANA zone, end strictly after start, no overnight — the same bounds F03 enforces). Most of this lands in T032/T015; this task closes any gap so T038 passes. Depends on T032, T015.
- [x] T040 [US3] Run T038 with the run flags and confirm green.

**Checkpoint**: all three user stories independently functional.

---

## Phase 6: Polish & Cross-Cutting Concerns

- [x] T041 Run the **full backend suite** (run flags) and confirm green — regression gate: F01/F02/F03/F04, F10, F11, `RbacEndpointInventoryTest`, and the index-bootstrap test all stay green alongside the new `com.cadence.interview.*` tests.
- [x] T042 [P] Run `ng test --watch=false` and `ng build` from `frontend/` — confirm Jasmine green and a clean production build.
- [x] T043 Confirm no new `.ps1`/`.cmd`/`.bat` were added (Principle V — none expected; if any, byte-scan for non-ASCII = 0 matches and record the parse result); run the `quickstart.md` manual + verification steps.
- [x] T044 Append an **Implementation Notes (009-interview-rule-engine)** section to `CLAUDE.md` capturing the load-bearing F12 lessons (engine on unchanged `AvailabilityService`; DST gap via `LocalDateTime` round-trip; the `managedCalendarEvents` cap index/query + `StatusNotIn` + half-open day; D8 compute-path isolation = persisted-validated member ids only; the SC-007 verify-not-wall-clock de-flake; value-free messages + name-never-logged; ChangeUnit008 order off applied `007`).
- [x] T045 **Mandatory multi-role sub-agent implementation review (C6, ≥3 roles)** of the delivered diff (Backend/DevOps, Security/GDPR, QA) including an actual compile/test run (Principle V/VI); apply or explicitly report every finding before task closure.

---

## Dependencies & Execution Order

### Phase dependencies

- **Setup (P1: T001–T003)**: no dependencies — start immediately.
- **Foundational (P2: T004–T009)**: depends on Setup — **BLOCKS all user stories**.
- **US1 (P3: T010–T021)**: depends on Foundational. The MVP.
- **US2 (P4: T022–T037)**: depends on Foundational AND on US1's `InterviewTemplate`/service/controller (it reads the persisted template and extends the US1 controller + contract + frontend feature).
- **US3 (P5: T038–T040)**: depends on US2 (the engine's zone/WH resolution) — a thin P2 refinement.
- **Polish (P6: T041–T045)**: depends on all desired stories.

### Within each story

- Tests (TDD) written first and MUST fail before implementation.
- Models → services → endpoints → frontend → run-green.
- US1's controller/contract/frontend/DTO files are **extended** by US2 (T012→T029 contract, T016→T033 controller, T021→T036 component spec, T014→T033 DTOs add `SlotPreviewRequest`) — all sequential same-file tasks (no [P]).

### Parallel opportunities

- Setup: T002, T003 in parallel.
- Foundational: T005, T006, T007 in parallel (T004 first for the domain; T008 after T004; T009 after T008).
- US1 tests T010, T011 in parallel (T012 touches the contract file extended later); impl T013, T014 in parallel, then T015 → T016; frontend T018 ∥ then T019/T020/T021.
- US2 tests T022–T028 and T030 all in parallel (different files); T029 extends the contract file (sequential). Impl T031 ∥, then T032 → T033.

---

## Parallel Example: User Story 2 tests

```bash
# Launch the independent US2 rule-engine test files together (all different files):
Task: "RuleEngineTest in backend/src/test/java/com/cadence/interview/RuleEngineTest.java"
Task: "RuleEngineDailyCapTest in .../interview/RuleEngineDailyCapTest.java"
Task: "RuleEngineDstTest in .../interview/RuleEngineDstTest.java"
Task: "RuleEngineFailSafeTest in .../interview/RuleEngineFailSafeTest.java"
Task: "RuleEnginePoolTest in .../interview/RuleEnginePoolTest.java"
Task: "RuleEngineRangeTest in .../interview/RuleEngineRangeTest.java"
Task: "RuleEnginePerfTest in .../interview/RuleEnginePerfTest.java"
Task: "InterviewTemplateLogPiiScanTest in .../interview/InterviewTemplateLogPiiScanTest.java"
```

---

## Implementation Strategy

### MVP first (US1 only)

1. Setup (Phase 1) → Foundational (Phase 2).
2. US1 (Phase 3) → **STOP and validate**: template CRUD works browser→DB, RBAC-gated, validated, audited. Demo-able.

### Incremental delivery

1. Setup + Foundational → foundation ready.
2. US1 → template management (MVP, demo).
3. US2 → the rule engine + recruiter slot-preview (the core value; the second §II leg).
4. US3 → per-template WH/zone override (P2 refinement).
5. Polish → full regression + review + docs.

### Notes

- [P] = different files, no incomplete-task dependency.
- Verify each story's tests fail before implementing.
- Commit after each task or logical group; never push to `main` directly (PR per the workflow).
- Test isolation: clean `interviewTemplates`/`managedCalendarEvents`/`calendarConnections`/`authAuditLog` in `@BeforeEach` with `mongoTemplate.remove(...)`, never `dropCollection` (CLAUDE.md F00.1); seed availability via the F10/F11 stubs; use the injected `MutableClock` for any time-relative assertion.
