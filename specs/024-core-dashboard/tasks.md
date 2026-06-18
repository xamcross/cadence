# Tasks: F50 Core Dashboard

**Input**: Design documents from `/specs/024-core-dashboard/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/dashboard-api.md, quickstart.md

**Tests**: INCLUDED — the constitution (Principle VII, test-first) and plan mandate them; the plan lists the exact test files. Test tasks are written before their implementation and must fail first.

**Organization**: By user story (US1 velocity metrics P1, US2 silence list P1, US3 CSV export P2, US4 role access P2). The dashboard is read-only orchestration over existing seams — **no new collection**; `DashboardService` and `DashboardController` are shared files, so tasks touching them are sequential (not `[P]`) across stories.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: different file, no dependency on an incomplete task → parallelizable
- **[Story]**: US1 / US2 / US3 / US4 (Setup/Foundational/Polish carry no story label)
- Backend root: `backend/src/main/java/com/cadence/`, tests `backend/src/test/java/com/cadence/`. Frontend: `frontend/src/app/`.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Test scaffolding + frontend skeleton.

- [X] T001 [P] Create backend test package `backend/src/test/java/com/cadence/dashboard/` and `DashboardItBase.java` (extends `BaseIntegrationTest`; `@BeforeEach` removes `SchedulingRequest`/`Candidate`/`WorkspaceConfig`/`Member`/`Session`/`AuthAuditEvent`; seed helpers `seedBookedRequest(ws, candidateId, sentAt, bookedAt, bookedStartAt)`, `seedNoShow(...)`, `seedSilentCandidate(ws, name, lastContactAt, outcome, erasureState)`; distinct `tokenHash` per seeded row — the F23 collision lesson; wires `@Import(AuthTestConfig.class)` + `MutableClock`).
- [X] T002 [P] Create frontend feature folder `frontend/src/app/features/admin/dashboard/` with stub standalone `dashboard.component.ts` + `dashboard.service.ts` + `dashboard.component.scss` (no logic yet).

---

## Phase 2: Foundational (Blocking Prerequisites)

**⚠️ CRITICAL**: All user stories depend on this phase. Builds the shared skeleton, indexes, repo finders, DTOs, gated endpoints.

- [X] T003 [P] Append `DASHBOARD_EXPORTED` to the `AuthEventType` enum (end of list, append-only — never reorder) in `backend/.../domain/AuthEventType.java`.
- [X] T004 [P] Create `DashboardWindow` enum (`LAST_7_DAYS`/`LAST_30_DAYS`/`LAST_90_DAYS`; `static DashboardWindow parse(String)` → throws a **NEW `DashboardExceptions.InvalidRequestException`** for unknown/blank-but-present — do NOT reuse the existing `AtsExceptions`/`SchedulingExceptions`/`EmailDeliveryExceptions` same-named variants (FQN-collision + wrong handler binding); defaults handled by caller; `Instant windowStart(Instant now)` = `now − {7|30|90} days`) in `backend/.../api/DashboardDtos.java` (+ new `backend/.../api/DashboardExceptions.java`).
- [X] T005 [P] Create `DashboardProperties` (`@ConfigurationProperties("cadence.dashboard")`, `silenceListCap=100`, `medianSampleCap=5000`) in `backend/.../service/DashboardProperties.java`; register defaults in `backend/src/main/resources/application.yml`.
- [X] T006 Create `ChangeUnit021_DashboardIndexes` (`@ChangeUnit(id="021-dashboard-indexes", order="021", author="system")`) adding `{workspaceId:1,status:1,bookedAt:1}` and `{workspaceId:1,status:1,bookedStartAt:1}` on `schedulingRequests` via native `createIndex`; rollback uses targeted `dropIndex(new Document(...))` per index (never `dropIndexes()`); **pure-ASCII comments, scan for NUL/non-ASCII** (the F30 lesson) in `backend/.../config/migration/`.
- [X] T007 [P] Add repository methods to `backend/.../repository/SchedulingRequestRepository.java`: `long countQualifyingNoShowDenominator(ws, windowStart, now)` and `long countNoShows(ws, windowStart, now)` (Mongo `count` `@Query`, status `BOOKED`, `bookedStartAt {$gt:windowStart,$lte:now}`, numerator adds `noShowAt:{$ne:null}`); and a **projected** median read `@Query(value="{ 'workspaceId':?0, 'status':'BOOKED', 'bookedAt':{ $gt:?1, $lte:?2 } }", fields="{ 'sentAt':1, 'bookedAt':1 }")` returning a `List<SchedulingRequest>` with a `Pageable` cap.
- [X] T008 [P] Add `List<Candidate> findByWorkspaceIdAndIdIn(String workspaceId, Collection<String> ids)` to `backend/.../repository/CandidateRepository.java` (the bounded batch name-load; `_id`-backed, no new index).
- [X] T009 Create response records in `backend/.../api/DashboardDtos.java`: `DashboardSnapshot(window, generatedAt, timeToSchedule, noShow, silenceList)`, `TimeToScheduleMetric(hasData, medianHours, sampleCount)`, `NoShowMetric(applicable, rate, noShowCount, qualifyingCount)`, `SilenceRow(candidateId, candidateName, severity, daysSilent)` — no email/phone field on any record.
- [X] T010 Create `DashboardService` skeleton in `backend/.../service/DashboardService.java`: injects `Clock`, `SchedulingRequestRepository`, `CandidateRepository`, `SlaNudgeService`, `DashboardProperties`, `CsvInjectionEscaper` (do NOT inject `WorkspaceConfigService` — the SLA window is read transitively inside `SlaNudgeService`, so a direct inject would be dead weight); **holds NO reference to `EmailDispatchService`/`EmailSender`/`CalendarEventService`/`CalendarProviderClient`/`AuthAuditService` and no repository mutation call**; expose `DashboardSnapshot snapshot(String workspaceId, DashboardWindow window)` returning empty/default metrics + empty silence list for now (`now = Instant.now(clock)`).
- [X] T011 Create `DashboardController` in `backend/.../api/DashboardController.java`: class-level `@PreAuthorize("hasAnyRole('ADMIN','RECRUITER','READ_ONLY')")`; `GET /api/internal/dashboard` reads `window` query param (String → `DashboardWindow.parse`, null→`LAST_30_DAYS`), scopes to `principal.workspaceId()`, `Cache-Control: no-store`; plus `DashboardExceptionHandler` (`@Order(Ordered.HIGHEST_PRECEDENCE) @RestControllerAdvice(assignableTypes=DashboardController.class)`) mapping `DashboardExceptions.InvalidRequestException`→400 `invalid_request`, `RbacExceptions.ScopedNotFoundException`→404 `not_found` (precautionary — the dashboard read has no single-record lookup that 404s, but the mapping mirrors F31 so a future scoped read is covered), catch-all 500 that **re-throws `AccessDeniedException`/`AuthenticationException`** (the F31 lesson).
- [X] T012 [P] Add `DashboardIndexTest` (extends `BaseIntegrationTest`) asserting both `ChangeUnit021` indexes exist on `schedulingRequests` in `backend/src/test/java/com/cadence/dashboard/`.
- [X] T013 Run `RbacEndpointInventoryTest` and confirm `/api/internal/dashboard` (and later `/export`) are covered by declared role security (deny-by-default build gate stays green).

**Checkpoint**: skeleton compiles; endpoint gated + workspace-scoped; indexes present; inventory green.

---

## Phase 3: User Story 1 - Scheduling velocity & no-show rate (Priority: P1) 🎯 MVP

**Goal**: Median time-to-schedule + no-show rate for the selected window, with empty/N-A states.
**Independent Test**: seed booked + past interviews (some no-show) → dashboard shows correct median + no-show rate for the window; change window → recompute.

- [X] T014 [P] [US1] Write `DashboardMetricsIT` (FAIL first) in `backend/src/test/java/com/cadence/dashboard/`: median odd/even (even-N = mean of two central, HALF_UP 1dp); no-show rate `2/10` and non-terminating `2/7`; **future-dated `bookedStartAt > now` excluded from denominator**; all-future → `applicable=false`; **reschedule lineage not double-counted** (parent `RESCHEDULED`, one `BOOKED`); **reschedule-across-window-boundary** (final `bookedStartAt`/`bookedAt` attribution — a row whose final live start moved into/out of the window is counted by its final value); **window predates first activity** → clean empty/low-count (not error); **two requests same candidate → `sampleCount==2`**; candidate-exactly-at-threshold; uses `MutableClock`.
- [X] T015 [US1] Implement time-to-schedule in `DashboardService` (projected capped read T007; `Duration.between(sentAt,bookedAt)`; in-memory sort; odd/even median; `medianHours` HALF_UP 1dp; `hasData=false` when `sampleCount==0`; honest bound + value-free log if `medianSampleCap` hit).
- [X] T016 [US1] Implement no-show rate in `DashboardService` (T007 `count` queries; `applicable=false` when `qualifyingCount==0`; `rate=noShowCount/qualifyingCount`) — same file, sequential after T015.
- [X] T017 [US1] Wire both metrics into `snapshot(...)`; `DashboardController` returns them; make `DashboardMetricsIT` green.
- [X] T018 [P] [US1] Frontend `dashboard.component.ts`: window selector (3 fixed options, held in session/component state), two metric panels with empty/N-A states; `dashboard.service.ts` `GET {apiBaseUrl}/internal/dashboard?window=`; all strings `$localize`.
- [X] T019 [P] [US1] `dashboard.component.spec.ts`: window switch recomputes (service called with new window); empty + N-A states render.

**Checkpoint**: US1 functional end-to-end (browser → DB) for the velocity metrics.

---

## Phase 4: User Story 2 - Current silence list (Priority: P1)

**Goal**: Live breached/at-risk candidate list, capped, name-only, most-overdue first.
**Independent Test**: seed candidates past/near the SLA window (+ terminal/erased) → list shows only active non-terminal breaches, ordered, capped.

- [X] T020 [P] [US2] Write `DashboardSilenceListIT` (FAIL first), each as a **distinct named test method**: terminal-outcome (`COMPLETE_OFFER`/`COMPLETE_REJECTED`) excluded — note this is provided by the shared `SlaNudgeService.classify` (returns GREEN for terminal), so the test guards the seam, not new dashboard code; erased excluded; **order most-overdue-first** (`lastActivityAt` asc); **cap == `silenceListCap`**; **decrypt count ≤ cap** — seed > cap breaches and assert `findByWorkspaceIdAndIdIn` is invoked with ≤ cap ids via `@SpyBean CandidateRepository` (pin the spy mechanism); **on-read freshness** as its own method (SC-005 owner: advance clock to breach a candidate → next read includes it); names never email/phone.
- [X] T021 [US2] Implement silence list in `DashboardService`: `SlaNudgeService.silenceList(ws)` (already excludes GREEN/terminal/erased via `classify` — do NOT re-filter) → sort by `CandidateSla.lastActivityAt` asc → truncate to `silenceListCap` → `findByWorkspaceIdAndIdIn` on the truncated ids only → build `SilenceRow(candidateId, name, slaState.name(), Duration.between(candidateSla.lastActivityAt(), now).toDays())` — same file, sequential after US1.
- [X] T022 [US2] Wire silence list into `snapshot(...)`; make `DashboardSilenceListIT` green.
- [X] T023 [P] [US2] Frontend: silence-list panel (name + severity chip + daysSilent) in `dashboard.component.ts`; `dashboard.component.spec.ts` asserts rendering + empty list.

**Checkpoint**: US1 + US2 both functional and independently testable.

---

## Phase 5: User Story 3 - CSV export (Priority: P2)

**Goal**: Download the current snapshot as injection-safe CSV, audited, not persisted.
**Independent Test**: Admin exports → CSV matches screen for the window; a `=`-leading name is inert; erased absent; one audit event.

- [X] T024 [P] [US3] Write `DashboardExportIT` (FAIL first): CSV section/row shape; **injection-neutralised cell** (`=`/`+`/`-`/`@`-leading name via `CsvInjectionEscaper`); no-show percentage `2/7 → "28.6%"` (HALF_UP 1dp); erased candidate absent; **export row count == screen silence-list length** (same capped snapshot); exactly one `DASHBOARD_EXPORTED` audit whose detail is the literal `"window=<W>;rows=<N>"` (matching T026 exactly) and **no names**; **no server-side file** persisted.
- [X] T025 [US3] Implement `renderCsv(DashboardSnapshot)` in `DashboardService`: build in-memory from the **same** snapshot; every candidate-derived cell via `CsvInjectionEscaper.escapeForSpreadsheet(...)`; percentage HALF_UP 1dp.
- [X] T026 [US3] Add `GET /api/internal/dashboard/export` to `DashboardController` with **method-level** `@PreAuthorize("hasAnyRole('ADMIN','RECRUITER')")` (overrides class rule → Read-only denied), `text/csv` + `Content-Disposition: attachment; filename="dashboard-<window>.csv"`, streamed body; after building, call `authAuditService.record(AuthEventType.DASHBOARD_EXPORTED, ws, principal.memberId(), "window=<W>;rows=<N>", null)` (the audit is the **controller's** job, not the service); `DashboardExceptionHandler` covers it; make `DashboardExportIT` green.
- [X] T027 [P] [US3] Frontend: export button rendered only for ADMIN/RECRUITER, triggers `/internal/dashboard/export?window=<selected>` download; `dashboard.component.spec.ts`: export hidden for Read-only + export click uses the **selected** window (stale-window regression guard).
- [X] T028 [P] [US3] Add lazy route `admin/dashboard` behind `roleGuard('ADMIN','RECRUITER','READ_ONLY')` chained after `authGuard` in `frontend/src/app/app.routes.ts`.

**Checkpoint**: US1 + US2 + US3 functional; export safe + audited.

---

## Phase 6: User Story 4 - Role-appropriate access (Priority: P2)

**Goal**: Enforce + prove the access matrix and workspace isolation.
**Independent Test**: call both endpoints as each role → matrix holds; cross-workspace id ignored.

- [X] T029 [US4] Write `DashboardContractTest` (MockMvc, `@Import(AuthTestConfig.class)`, cookie-per-role like `SlaContractTest`/`CsvImportContractTest`): full 5-role matrix on **both** endpoints — Admin 200/200, Recruiter 200/200, **Read-only 200 (positive read) / 403 (export)**, **Hiring Manager 403/403**, Interviewer 403/403; bad `window` → 400; **cross-workspace-id-ignored as a separate test method** (a caller passing another workspace's id receives only their own data, no oracle); assert every 403 returns the standard `forbidden` envelope with **no metric/silence payload leaked** in the body (FR-020). Internal endpoints use `.with(csrf())`.
- [X] T030 [US4] If any matrix assertion fails, fix the `@PreAuthorize` placement on `DashboardController` (read class-level incl. `READ_ONLY`; export method-level ADMIN/RECRUITER) — verify `DashboardContractTest` + `RbacEndpointInventoryTest` green.

**Checkpoint**: access matrix enforced and proven; FR-026 HM-denied is a hard assertion.

---

## Phase 7: Polish & Cross-Cutting Concerns

- [X] T031 [P] `DashboardReadOnlyStructuralTest`: constant-pool scan of **`DashboardService.class` only** (mirror `NoAutoSendStructuralTest`) asserting no reference to `EmailDispatchService`/`EmailSender`/`CalendarEventService`/`CalendarProviderClient`/`AuthAuditService` and no `save`/`insert`/`updateFirst`/`findAndModify`; reflection check it exposes no write method (SC-011).
- [X] T032 [P] `DashboardLogPiiScanTest`: TRACE-level render with a candidate-name sentinel → assert absent in logs + the export audit, **present** in the CSV cell (the deliberate egress) (SC-009).
- [X] T033 [P] `DashboardRestartIT`: figures identical after a cold `MongoTemplate`/converter reload (the F03 precedent) (SC-010).
- [X] T034 [P] `DashboardPerfIT` (`@Tag("perf")`): seed ≥200 active candidates + ≥1000 booked requests across the window; one discarded warm-up read, then assert `< 3s` with a CI-safe margin + index-backed via `explain` (SC-008).
- [X] T035 Extend `.github/workflows/ci.yml` with an F50 `SENTINEL` PII-scan block (candidate-name/email sentinels seeded by `DashboardLogPiiScanTest`); pure-ASCII.
- [X] T036 Run `quickstart.md` manual E2E (browser → DB): Admin/Recruiter see metrics + silence + export; window persists; Read-only no export; Interviewer/HM denied; record results.
- [X] T037 Full verification: `gradlew test` (incl. `com.cadence.dashboard.*` + `RbacEndpointInventoryTest`), `ng test --watch=false`, `ng build --configuration production`; non-ASCII/NUL scan on all new Java sources (Principle V / C5).
- [X] T038 Multi-role sub-agent review (≥3 roles: Backend, Security, QA) per constitution C6; apply or report findings before close.

---

## Dependencies & Execution Order

### Phase order
- **Setup (P1)** → **Foundational (P2)** blocks everything → **US1 → US2 → US3 → US4** → **Polish**.
- US1 and US2 are both P1 but **share `DashboardService`/`snapshot()`**, so US2's service work (T021/T022) is sequential after US1's (T015–T017). Their tests and frontend tasks are `[P]`.
- US3 (export) depends on US1+US2 because it renders the **same snapshot** (T025 needs metrics + silence list populated).
- US4 (contract test) depends on both endpoints existing (after T026), but the security annotations themselves land in T011 (read) + T026 (export).

### Within a story
- Test task (FAIL first) → service implementation → wire into snapshot/controller → frontend.

### Parallel opportunities
- Setup: T001 ∥ T002.
- Foundational: T003 ∥ T004 ∥ T005 ∥ T007 ∥ T008 (different files); T006 then T012 (index then index-test); T009/T010/T011/T013 sequential-ish (DTOs → service → controller → inventory).
- Per story: the `[P]` test + frontend tasks run alongside; backend service edits are sequential (same file).
- Polish: T031 ∥ T032 ∥ T033 ∥ T034 (distinct test files); T035–T038 sequential at the end.

---

## Implementation Strategy

### MVP (US1 only)
Setup → Foundational → US1 → **STOP & validate** (velocity metrics browser→DB). Deployable demo.

### Incremental
Add US2 (silence list) → US3 (export) → US4 (access proof) → Polish. Each is an independently testable increment; none breaks the prior.

### SC coverage map
SC-001/002 → T014/T017/T018/T019 · SC-003 → T014/T015/T016 · SC-004 → T020/T021 · SC-005 → T020 (on-read freshness) · SC-006 → T024/T025 · SC-007 → T029 · SC-008 → T034 · SC-009 → T032/T035 · SC-010 → T033 · SC-011 → T031 · SC-012 → T024/T026.

---

## Notes
- `[P]` = different file, no incomplete-task dependency.
- `DashboardService`/`DashboardController` are shared → cross-story edits sequential.
- Tests fail first (TDD); mark each task `[X]` on completion.
- Commit after each logical group on the feature branch (never `main`); stage with `git add -A` immediately before commit (the stale-index rule).
- No new collection, no new runtime dependency, no scheduler — read-only orchestration.
- **T035** grep patterns MUST match the exact sentinel strings introduced by **T032** (`DashboardLogPiiScanTest`).
- **Accepted residual** (the F23/F31 honest-residual precedent): the "concurrent SLA refresh" edge case is not deterministically tested — the on-read (no-precompute) model makes a stale read impossible (every read re-classifies via `SlaNudgeService` at `now`), so there is no precompute window to race; documented here rather than covered by a flaky concurrency test.
