# Tasks: F51 Pipeline View

**Input**: Design documents from `/specs/025-pipeline-view/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/pipeline-api.md, quickstart.md

**Tests**: INCLUDED — the constitution (Principle VII, test-first) and the plan mandate them; the plan lists the exact test files. Test tasks are written before their implementation and must fail first.

**Organization**: By user story — US1 recruiter pipeline list (P1, MVP), US2 Hiring-Manager scoped visibility + requisitions (P2), US3 bulk actions (P2), US4 candidate timeline (P3). The requisition concept + `Candidate.requisitionId` + indexes are **foundational** (US1's rows display the requisition; US2 manages it and scopes HMs by it). `PipelineService`/`PipelineController` are shared files, so tasks touching them are sequential (not `[P]`) across stories.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: different file, no dependency on an incomplete task → parallelizable
- **[Story]**: US1 / US2 / US3 / US4 (Setup/Foundational/Polish carry no story label)
- Backend root: `backend/src/main/java/com/cadence/`, tests `backend/src/test/java/com/cadence/`. Frontend: `frontend/src/app/`.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Test scaffolding + frontend skeletons.

- [X] T001 [P] Create backend test package `backend/src/test/java/com/cadence/pipeline/` and `PipelineItBase.java` (extends `BaseIntegrationTest`; `@Import(com.cadence.auth.AuthTestConfig.class)` (note the `com.cadence.auth` package) + `MutableClock`; `@BeforeEach` removes `Candidate`/`Requisition`/`Assignment`/`SchedulingRequest`/`CandidateAuditEvent`/`EmailDispatch`/`WorkspaceConfig`/`Member`/`Session`/`AuthAuditEvent`; seed helpers `seedCandidate(ws, name, stageLabel, lastContactAt, statusOutcome, erasureState, requisitionId)`, `seedRequisition(ws, title, status)`, `seedAssignment(ws, hmMemberId, requisitionId)`, `seedScheduling(ws, candidateId, status, sentAt, bookedStartAt, noShowAt)` — **distinct `tokenHash` per scheduling row (F23/F32 plain-unique-index lesson) and distinct `startAt`/member per BOOKED row (F23 `interviewSlotClaims` partial-unique lesson)**).
- [X] T002 [P] Create frontend feature folder `frontend/src/app/features/pipeline/` with stub standalone `pipeline-list.component.ts`, `candidate-timeline.component.ts`, `pipeline.service.ts`, `pipeline-list.component.scss` (no logic yet); and `frontend/src/app/features/admin/requisitions/` with stub `requisitions.component.ts` + `requisitions.service.ts`.

---

## Phase 2: Foundational (Blocking Prerequisites)

**⚠️ CRITICAL**: All user stories depend on this phase — the requisition concept, the candidate link, indexes, repo finders, DTOs, gated controller skeletons, the no-oracle handler, and the SLA reuse seam.

- [X] T003 [P] Create `Requisition` `@Document("requisitions")` (`id`, `workspaceId`, `title`, `RequisitionStatus status`, `String externalLabel` with `@Field(write=Field.Write.NON_NULL)`, `Instant createdAt`, `String createdByMemberId`) and `RequisitionStatus` enum (`OPEN`, `CLOSED`) in `backend/.../domain/`. No PII → un-encrypted (the `interviewTemplates` precedent).
- [X] T004 [P] Add `requisitionId` to `Candidate` (`@Field(value="requisitionId", write=Field.Write.NON_NULL)`, nullable `String`) in `backend/.../domain/Candidate.java`. **No converter (non-PII), no `wipe` change** — erased candidates are excluded by the `erasureState=ACTIVE` predicate, the link is retained as a non-PII anchor (data-model D2).
- [X] T005 [P] Append `REQUISITION_CREATED`, `REQUISITION_UPDATED`, `REQUISITION_HM_ASSIGNED`, `REQUISITION_HM_UNASSIGNED` to `backend/.../domain/AuthEventType.java` (end of list, append-only — never reorder).
- [X] T006 [P] Append `REQUISITION_LINKED` to `backend/.../domain/CandidateEventType.java` (end of list, append-only).
- [X] T007 [P] Create `RequisitionRepository` (`findByWorkspaceId`, `findByWorkspaceIdAndStatus`, `findByWorkspaceIdAndId`) in `backend/.../repository/RequisitionRepository.java`.
- [X] T008 [P] Add to `backend/.../repository/CandidateRepository.java`: `List<Candidate> findByWorkspaceIdAndErasureState(String ws, ErasureState s, Pageable p)`, `List<Candidate> findByWorkspaceIdAndErasureStateAndRequisitionIdIn(String ws, ErasureState s, Collection<String> reqIds, Pageable p)`, `long countByWorkspaceIdAndErasureState(String ws, ErasureState s)` (truncation flag).
- [X] T009 [P] Add to `backend/.../repository/SchedulingRequestRepository.java`: a **projected batch** finder `findByWorkspaceIdAndCandidateIdIn(String ws, Collection<String> candidateIds)` (project `{candidateId, status, sentAt, expiresAt, bookedStartAt, noShowAt, createdAt}` via `@Query(fields=...)`). **No new index** — served by the existing `ChangeUnit012 {workspaceId,candidateId,createdAt:-1}` prefix (research D8 / Backend review #2).
- [X] T010 Create `ChangeUnit022_PipelineIndexes` (`@ChangeUnit(id="022-pipeline-indexes", order="022", author="system")`) adding **3** indexes via native `createIndex`: `requisitions {workspaceId:1,status:1}`, `candidates {workspaceId:1,erasureState:1,createdAt:1}`, `candidates {workspaceId:1,requisitionId:1}`; rollback uses targeted `dropIndex(new Document(...))` per index (never `dropIndexes()`); **pure-ASCII comments, scan new source for NUL/non-ASCII** (the F30 lesson) in `backend/.../config/migration/`.
- [X] T011 [P] Create `PipelineProperties` (`@ConfigurationProperties("cadence.pipeline")`, `scanCap=1000`, `bulkMax=100`, `pageSize=50`) in `backend/.../service/PipelineProperties.java`; register defaults in `backend/src/main/resources/application.yml`.
- [X] T012 Modify `backend/.../service/SlaNudgeService.java` — add a thin public `SlaState classifyCandidate(WorkspaceConfig cfg, Candidate c, Instant now)` that delegates to the **existing private** `classify(Candidate, windowDays, now)` with the same amber-margin (no N+1, no drift — FR-004).
- [X] T013 [P] Create `PipelineExceptions.java` (`InvalidRequestException`, `SelectionTooLargeException` — do NOT reuse same-named variants from other packages, the F50 FQN-collision lesson) and `PipelineDtos.java` (records: `PipelineRow`, `PipelinePage(rows,page,size,totalInScope,truncated)`, `BulkRequest`, `BulkResult(candidateId,outcome,reason)`, `TimelineEvent(occurredAt,type,label)`, `TimelineResponse(candidateId,events,feedbackPending)`, `RequisitionDto`, `LinkRequest`, enums `PipelineSchedulingStatus`, `PipelineSort`, `PipelineStatusFilter`) in `backend/.../api/`.
- [X] T014 Create `PipelineService` skeleton in `backend/.../service/PipelineService.java`: inject `Clock`, `CandidateRepository`, `RequisitionRepository`, `SchedulingRequestRepository`, `SlaNudgeService`, `AssignmentService`, `WorkspaceConfigService`, `CandidateAuditEventRepository`, `PipelineProperties`; expose `PipelinePage list(actorWorkspaceId, actorMemberId, Role, filters, sort, page)` and `TimelineResponse timeline(ws, memberId, Role, candidateId)` returning empty/defaults for now (`now = Instant.now(clock)`).
- [X] T015 Create `PipelineController` in `backend/.../api/PipelineController.java`: class-level `@PreAuthorize("hasAnyRole('ADMIN','RECRUITER','READ_ONLY','HIRING_MANAGER')")` (Interviewer denied by exclusion); `GET /api/internal/pipeline` (query params per contract, `Cache-Control: no-store`, scopes to `principal.workspaceId()`/`role()`); plus `PipelineExceptionHandler` (`@Order(Ordered.HIGHEST_PRECEDENCE) @RestControllerAdvice(assignableTypes={PipelineController.class, RequisitionController.class})`) mapping `RbacExceptions.ScopedNotFoundException`→404 `{"error":"not_found"}` (no message), `PipelineExceptions.InvalidRequestException`→400 `invalid_request`, `PipelineExceptions.SelectionTooLargeException`→400 `selection_too_large`, catch-all 500 that **re-throws `AccessDeniedException`/`AuthenticationException`** (the F31 lesson).
- [X] T016 Create `RequisitionController` (`backend/.../api/RequisitionController.java`, class `@PreAuthorize("hasAnyRole('ADMIN','RECRUITER','READ_ONLY')")` with method-level overrides per contract §4) and `RequisitionService` skeleton (`backend/.../service/RequisitionService.java`, inject `Clock`, `RequisitionRepository`, `CandidateRepository` + `MongoTemplate` for the guarded link write, `AssignmentService`, `AuthAuditService`, `CandidateAuditService`) — endpoints return stubs for now.
- [X] T017 [P] Add `PipelineIndexTest` (extends `BaseIntegrationTest`) asserting the 3 `ChangeUnit022` indexes exist in `backend/src/test/java/com/cadence/pipeline/`.
- [X] T018 Run `RbacEndpointInventoryTest` and confirm every new `/api/internal/pipeline/**` and `/api/internal/requisitions/**` (and the `/candidates/{id}/requisition` link) handler is covered by declared role security (deny-by-default build gate stays green).

**Checkpoint**: skeleton compiles; pipeline + requisition endpoints gated + workspace-scoped; indexes present; inventory green.

---

## Phase 3: User Story 1 - Recruiter sees the whole pipeline at a glance (Priority: P1) 🎯 MVP

**Goal**: A workspace-wide, sortable/filterable list of active candidates with name, stage, SLA colour, and scheduling status for Admin/Recruiter/Read-only (Interviewer denied).
**Independent Test**: seed candidates across stages/SLA/scheduling states → list shows all active with correct chips; filters/sort narrow; erased excluded; SLA chip refreshes on next poll.

- [X] T019 [P] [US1] Write `PipelineSchedulingStatusTest` (pure unit, FAIL first) in `backend/src/test/java/com/cadence/pipeline/`: the full FR-005 mapping table — none→`NO_LINK_SENT`; `PENDING_SELECTION` not-expired→`LINK_SENT`; `PENDING_SELECTION` past `expiresAt` / `EXPIRED`→`EXPIRED`; `BOOKING`→`SLOT_PICKED`; `BOOKED`+`noShowAt==null`→`CONFIRMED`; `BOOKED`+`noShowAt!=null`→`NO_SHOW`; `RESCHEDULED` (no live BOOKED)→`RESCHEDULED`; `CANCELLING`/`CANCELLED`→`CANCELLED`; `SUPERSEDED`/`CLEANUP_INCOMPLETE` (no live row)→`NO_LINK_SENT`.
- [X] T020 [P] [US1] Write `PipelineListContractTest` (MockMvc, cookie-per-role, FAIL first): Admin 200 / Recruiter 200 / Read-only 200 (full workspace) / **Interviewer 403**; bad sort/filter enum → 400 `invalid_request`; cross-workspace id ignored (own data only, no oracle) as a separate method; `Cache-Control: no-store` asserted. (HM scoping is US2.)
- [X] T021 [P] [US1] Write `PipelineComposeIT` (FAIL first), distinct named methods: SLA colour **equals** the `SlaNudgeService.classifyCandidate`/static `classify(...)` verdict (FR-004 — the pure classifier the pipeline reuses, not the DB-reading `candidateSla`); scheduling-status per the mapping incl. SUPERSEDED/EXPIRED edges; **no-stage candidate → "Not started" + defined default colour**; sort tie-breaker stable (`lastActivityAt desc, then candidateId`); erased absent (SC-009); default excludes terminal (`statusOutcome` COMPLETE_OFFER/REJECTED) and `INCLUDE_CLOSED` reveals them; candidate on a CLOSED requisition treated-as-closed; **SC-001 red-SLA filter returns the complete breaching set OR sets `truncated:true` (never silently drops a breacher below `scanCap`)**; uses `MutableClock` for the poll-freshness (SC-003) assertion.
- [X] T022 [P] [US1] Write `SlaClassifyReuseTest` (FAIL first): `classifyCandidate(cfg, candidate, now)` delegates to the identical static `classify(...)` with the same window/amber inputs (FR-004 anti-drift).
- [X] T023 [US1] Implement the pure scheduling-status mapping function (resolve live-BOOKED-else-newest per candidate, then map) in `PipelineService` (or a small `PipelineSchedulingMapper`); make T019 green.
- [X] T024 [US1] Implement `PipelineService.list` for workspace-wide roles (Admin/Recruiter/Read-only): one bounded candidate read (`findByWorkspaceIdAndErasureState`, capped at `scanCap`; `truncated` when `countByWorkspaceIdAndErasureState > scanCap`, log ids/counts only) → SLA via `classifyCandidate` (no query) → stage via best decrypted label (`statusStage`→`atsStageLabel`→`importStageLabel`→`"Not started"`) → one batch scheduling read (`findByWorkspaceIdAndCandidateIdIn`) + mapping → batch requisition-title resolve (`findByWorkspaceId` once, map id→title) → in-memory filter (status/SLA/scheduling/stage/requisition + include-closed) → sort (+stable tie-break) → paginate; make T021/T022 green.
- [X] T025 [US1] Wire `GET /api/internal/pipeline` in `PipelineController` (role→predicate selection via `principal.role()`; param parsing → `InvalidRequestException`); make T020 green.
- [X] T026 [P] [US1] Frontend `pipeline-list.component.ts`: table with name/stage/SLA-chip/scheduling-chip/requisition columns; filter controls (stage, SLA, scheduling, requisition, include-closed); sort selector; 60s poll refresh (`interval`/signal, paused when tab hidden optional); all strings `$localize`. `pipeline.service.ts`: `GET {apiBaseUrl}/internal/pipeline` with params.
- [X] T027 [P] [US1] `pipeline-list.component.spec.ts` (Jasmine): filter narrows, sort calls service with new sort, empty-state renders, poll re-fetches.
- [X] T028 [P] [US1] Add lazy route `pipeline` behind `authGuard` + `roleGuard('ADMIN','RECRUITER','READ_ONLY','HIRING_MANAGER')` in `frontend/src/app/app.routes.ts`; add a "Pipeline" nav link (4 roles) in `frontend/src/app/features/shell/*`.

**Checkpoint**: US1 functional end-to-end (browser → DB) for Admin/Recruiter/Read-only; Interviewer denied.

---

## Phase 4: User Story 2 - Hiring Manager sees only their own requisitions (Priority: P2)

**Goal**: Introduce requisition management (create/close, assign HM, link candidate) and scope the HM pipeline server-side to assigned requisitions, with no existence oracle.
**Independent Test**: create R1/R2, assign HM to R1, link C1→R1 & C2→R2 → HM sees only C1; out-of-scope request reveals nothing; link-move & close flip visibility.

- [X] T029 [P] [US2] Write `RequisitionContractIT` (MockMvc, FAIL first): create/update Admin-only (403 R/RO/HM/I); list Admin/Recruiter/Read-only (403 I); HM assign Admin-only; candidate link Admin/Recruiter (403 RO/HM/I) and **audited (`REQUISITION_LINKED`) on BOTH set AND change** (R1→R2 re-link audits the move); no-oracle 404 on unknown requisition/candidate/member; external label (`atsExternalJobId`/`importRequisitionLabel`) surfaced for manual link (FR-011).
- [X] T030 [P] [US2] Write `PipelineHmScopingIT` (FAIL first), distinct methods: HM sees only assigned-requisition candidates; **empty assignment set → empty page (NOT unfiltered)**; unassigned (null `requisitionId`) candidate invisible to HM; link-move flips HM visibility on next load (US2-6); closed requisition / unassigned HM drops rows (US2-7); HM passing `?requisitionId=<not-mine>` → empty rows, no oracle; HM in the 5-role list matrix (200 scoped).
- [X] T031 [US2] Implement `RequisitionService`: `create`/`update`(title/status) with `REQUISITION_CREATED`/`REQUISITION_UPDATED` audit; `linkCandidate(ws, actor, candidateId, requisitionId|null)` via active-state-guarded `mongoTemplate.updateFirst({_id,workspaceId,erasureState:ACTIVE}, set requisitionId)` (validates requisition exists in ws → else `ScopedNotFoundException`) + `CandidateAuditService.append(REQUISITION_LINKED)` on set and change; `assignHm` via `AssignmentService.create(ws, admin, hmMemberId, REQUISITION, requisitionId)` + `REQUISITION_HM_ASSIGNED` audit; `unassignHm(ws, requisitionId, assignmentId)` resolves the assignment via `AssignmentService.getOrNotFound(ws, assignmentId)`, reads `assignment.getMemberId()`, then `AssignmentService.delete(ws, memberId, assignmentId)` + `REQUISITION_HM_UNASSIGNED` audit (Backend review #6).
- [X] T032 [US2] Implement `RequisitionController` endpoints per contract §4 (method-level `@PreAuthorize` overrides; `PUT /api/internal/candidates/{candidateId}/requisition` is Admin/Recruiter); make `RequisitionContractIT` green; re-run `RbacEndpointInventoryTest`.
- [X] T033 [US2] Implement the HM-scoped branch in `PipelineService.list`: `AssignmentService.assignedResourceIds(ws, memberId, REQUISITION)` → if empty, return empty page (short-circuit, never `$in []` semantics relied upon) → else `findByWorkspaceIdAndErasureStateAndRequisitionIdIn`; extract a shared `resolveScopedCandidateOrNotFound(ws, memberId, role, candidateId)` helper (HM must own the candidate's requisition else `ScopedNotFoundException`) for reuse by US4 timeline; make `PipelineHmScopingIT` green.
- [X] T034 [P] [US2] Frontend `features/admin/requisitions/requisitions.component.ts` + `requisitions.service.ts`: create/close requisition, assign/unassign HM, link candidate; `requisitions.component.spec.ts` (create/close, assign, link). Add lazy route `admin/requisitions` behind `roleGuard('ADMIN')`; expose a candidate→requisition link action on the pipeline row for Recruiter too; shell nav "Requisitions" (Admin).

**Checkpoint**: US1 + US2 — HM scoping enforced and proven; requisition surface live.

---

## Phase 5: User Story 3 - Bulk actions (Priority: P2)

**Goal**: Recruiter/Admin select many candidates and send a scheduling link or update email in one step, with per-candidate outcomes and a single coarse non-disclosing skip.
**Independent Test**: select contactable + non-contactable → each contactable actioned (personalised), each non-contactable `SKIPPED/not_contactable` (one reason), 0 sends to non-contactable, idempotent, max enforced, role-gated.

- [X] T035 [P] [US3] Write `PipelineBulkIT` (FAIL first): 8 selected (6 contactable / 2 not) → 6 enqueued, 2 `SKIPPED/not_contactable`; **BYTE-IDENTICAL skip reason across {erased, withdrawn, over-retention, undeliverable, no-consent} AND unknown-at-execution** (no distinct `not_found` for a real candidate — Security review #1); per-candidate **individual merge-render** asserted (FR-015); **`initiate` exceptions (incl. `UnschedulableRequiredException` carrying member ids) collapse to the coarse skip — payload absent from result + logs** (Security review #2); at-limit accepted / over-limit → 400 `selection_too_large` before any candidate touched; bulk 403 for HM/Interviewer/Read-only.
- [X] T036 [P] [US3] Write `PipelineBulkConcurrencyIT` (FAIL first): gated 2-thread same-selection update-email → exactly one send per candidate (the `{workspaceId,idempotencyKey}` outbox uniqueness); scheduling-link double-submit → one live link (supersede), documented second-invitation residual asserted as accepted.
- [X] T037 [P] [US3] Write `PipelineBulkToctouIT` (FAIL first): a candidate that **passes** the synchronous pre-check is erased before the outbox claim → the async send-time gate fail-closes (dispatch row `REFUSED`, 0 sends, not resurrected) — SC-006 authoritative backstop.
- [X] T038 [US3] Implement `PipelineBulkService` in `backend/.../service/PipelineBulkService.java`: reject `candidateIds.size > bulkMax` up front (`SelectionTooLargeException`); per candidate, synchronous `ContactPermissionGate.evaluate(ws, candidateId)` (or an `evaluate(Candidate)` overload over the loaded row) → deny ⇒ `BulkResult(candidateId, SKIPPED, "not_contactable")`; else fan-out (`EmailDispatchService.enqueue(ws, candidateId, HOLD_UPDATE, "BASE", scheduledFor, nonPiiCtx, ref)` for update; `SchedulingService.initiate(ws, principal.memberId(), candidateId, templateId, locationText, rangeStart, rangeEnd, requestIpOrNull)` for scheduling-link — thread the actor member id + ip, the real 8-arg signature) catching ALL exceptions → coarse `SKIPPED/not_contactable` (payload discarded); collect per-candidate results.
- [X] T039 [US3] Add `POST /api/internal/pipeline/bulk` to `PipelineController` with method-level `@PreAuthorize("hasAnyRole('ADMIN','RECRUITER')")` (RO/HM/I → 403), `Cache-Control: no-store`, body validation (`InvalidRequestException` for empty selection / missing templateId for scheduling-link / bad message type); make T035/T036/T037 green; re-run `RbacEndpointInventoryTest`.
- [X] T040 [P] [US3] Frontend: bulk multi-select + action menu (Send scheduling link / Send update email) in `pipeline-list.component.ts`, hidden/disabled for Read-only & Hiring Manager; per-candidate result panel (succeeded/skipped); update `pipeline-list.component.spec.ts` (bulk disabled for RO/HM, per-candidate result render, over-limit guard message).

**Checkpoint**: US1 + US2 + US3 — bulk actions safe, idempotent, role-gated, no-oracle.

---

## Phase 6: User Story 4 - Drill into a candidate's full timeline (Priority: P3)

**Goal**: A chronological, PII-free timeline of a candidate's email/scheduling/status/feedback events, HM-scoped.
**Independent Test**: candidate with mixed history → timeline in order with labels; feedback-pending shown; empty timeline ok; HM out-of-scope → no-oracle 404; no free-text/scorecard content.

- [X] T041 [P] [US4] Write `PipelineTimelineIT` (FAIL first): chronological order with human-readable labels; **an email-sent event actually appears on the timeline (non-vacuous SC-007 — seed a SENT dispatch, assert a `MESSAGE_SENT` label)** and a booking appears (`BOOKING_CHANGED`); HM own-candidate 200; **HM out-of-scope candidate → 404 byte-identical (no oracle)** via the shared scoping helper; empty timeline → 200 `events:[]`; erased candidate → no residual PII; feedback-pending indicator; **a SUBMITTED scorecard appears as a `SCORECARD_SUBMITTED` label with NO payload/free-text (seed a PII sentinel into the scorecard, assert absent)** (FR-022).
- [X] T042 [US4] **Add the missing candidate-`auditLog` emission sites** (SC-007 linchpin — these events currently go to `authAuditLog`, NOT the candidate `auditLog`, so the timeline would otherwise omit them): add `CandidateAuditService.append(ws, candidateId, MESSAGE_SENT, ...)` in `EmailDispatchService.dispatch` after the SENT CAS, and `CandidateAuditService.append(ws, candidateId, BOOKING_CHANGED, ...)` in `SlotReservationService.book` (and the cancel/reschedule sites) — ids/enums only, no PII. No new event types (the enum values already exist as forward-contract). Confirm each owning service before adding.
- [X] T043 [US4] Implement `PipelineService.timeline`: resolve+scope the candidate via the shared `resolveScopedCandidateOrNotFound` (T033); read `CandidateAuditEventRepository.findByWorkspaceIdAndCandidateIdOrderByOccurredAtAscIdAsc(ws, candidateId)`; map each `CandidateEventType`→static label; read feedback-pending from `feedbackRequests` for the candidate's interviews.
- [X] T044 [US4] Add `GET /api/internal/pipeline/candidates/{candidateId}/timeline` to `PipelineController` (`Cache-Control: no-store`); make `PipelineTimelineIT` green.
- [X] T045 [P] [US4] Frontend `candidate-timeline.component.ts` (chronological list, empty state, out-of-scope 404 handling) + `candidate-timeline.component.spec.ts`; open from a pipeline row.

**Checkpoint**: all four user stories independently functional.

---

## Phase 7: Polish & Cross-Cutting Concerns

- [X] T046 [P] `PipelineLogPiiScanTest`: TRACE-level compose + bulk + timeline with name + stage sentinels **seeded into all three stage-source fields** (`statusStage`/`atsStageLabel`/`importStageLabel`) → assert absent in logs + audit + bulk-result body + dispatch row (FR-024/SC-008).
- [X] T047 [P] `PipelineErasureRegressionIT`: `requisitionId` retained on erasure; an erased candidate (with retained `requisitionId`) appears in **no** role's pipeline or timeline (incl. an HM assigned to that requisition); existing `SlaNudgeService` scan + `SchedulingService` reads unaffected by the new field (SC-009).
- [X] T048 [P] `PipelinePerfIT` (`@Tag("perf")`): seed ≥200 active candidates + their scheduling rows; one discarded warm-up read, then assert the **first page (size = `pageSize`)** is returned `< 3s` with a CI-safe wall-clock margin (the `DashboardPerfIT` precedent). Index-backing is covered by `PipelineIndexTest` (existence) — **no brittle `explain()`-plan gate** (QA review #6).
- [X] T049 Extend `.github/workflows/ci.yml` with an F51 `SENTINEL` PII-scan block (candidate-name + stage-label sentinels matching exactly the strings seeded by `PipelineLogPiiScanTest`); pure-ASCII.
- [ ] T050 Run `quickstart.md` manual E2E (browser → DB): Scenario A (recruiter list), B (HM scoping), C (bulk), D (timeline); record results. **(NOT done in the automated pass — requires a human-driven browser session against a running stack; equivalent paths are covered by the MockMvc contract ITs + the Jasmine component specs. Run manually before release.)**
- [X] T051 Full verification: `gradlew test` (incl. `com.cadence.pipeline.*` + `RbacEndpointInventoryTest` + regression packages rbac/gdpr/sla/scheduling/feedback/migration), `ng test --watch=false`, `ng build --configuration production`; non-ASCII/NUL scan on all new Java sources (Principle V / C5).
- [X] T052 Multi-role sub-agent review (≥3 roles: Backend, Security, QA) per constitution C6; apply or report findings before close.

---

## Dependencies & Execution Order

### Phase order
- **Setup (P1)** → **Foundational (P2)** blocks everything → **US1 → US2 → US3 → US4** → **Polish**.
- **US2 depends on US1** for the shared `PipelineService.list` (T033 adds the HM branch to the method T024 created) and the `PipelineController` (shared file).
- **US3** depends on US1 (the list/selection exists) and reuses the bulk endpoint on the shared controller.
- **US4** depends on US2's `resolveScopedCandidateOrNotFound` helper (T033) for HM timeline scoping.

### Within a story
- Test task (FAIL first) → service implementation → wire into controller → frontend.
- Models before services; services before endpoints; shared-file edits sequential.

### Parallel opportunities
- Setup: T001 ∥ T002.
- Foundational: T003 ∥ T004 ∥ T005 ∥ T006 ∥ T007 ∥ T008 ∥ T009 ∥ T011 ∥ T013 (different files); T010 then T017 (index then index-test); T012/T014/T015/T016 sequential-ish (SLA seam → service → controller → requisition controller); T018 last.
- Per story: the `[P]` test tasks + frontend tasks run alongside; backend `PipelineService`/`PipelineController` edits are sequential (same files).
- US4: T041 (test) → T042 (emission sites) → T043 (timeline service) → T044 (controller) → T045 (frontend, `[P]`).
- Polish: T046 ∥ T047 ∥ T048 (distinct test files); T049–T052 sequential at the end.

---

## Parallel Example: User Story 1

```bash
# Tests first (all [P], different files):
Task: "PipelineSchedulingStatusTest (mapping) in src/test/java/com/cadence/pipeline/"
Task: "PipelineListContractTest (4-role matrix) in src/test/java/com/cadence/pipeline/"
Task: "PipelineComposeIT (SLA/stage/scheduling/erased/terminal) in src/test/java/com/cadence/pipeline/"
Task: "SlaClassifyReuseTest (anti-drift) in src/test/java/com/cadence/pipeline/"
# Frontend (after backend green):
Task: "pipeline-list.component.ts + pipeline.service.ts"
Task: "pipeline-list.component.spec.ts"
```

---

## Implementation Strategy

### MVP (US1 only)
Setup → Foundational → US1 → **STOP & validate** (Admin/Recruiter/Read-only see the whole pipeline, browser→DB; Interviewer denied). Deployable demo.

### Incremental
Add US2 (HM scoping + requisitions — the load-bearing privacy gate) → US3 (bulk) → US4 (timeline) → Polish. Each is an independently testable increment; none breaks the prior.

### SC coverage map
SC-001 → T021 (red-SLA completeness vs cap) · SC-002 → T048 (first page, size=pageSize) · SC-003 → T021/T024 (MutableClock poll) · SC-004 → T020/T029/T030 · SC-005 → T035/T036 · SC-006 → T035/T037 · SC-007 → T041/T042/T043 (non-vacuous: emission sites added in T042) · SC-008 → T046/T049 · SC-009 → T021/T047.

---

## Notes
- `[P]` = different file, no incomplete-task dependency.
- `PipelineService`/`PipelineController` are shared → cross-story edits sequential.
- Tests fail first (TDD); mark each task `[X]` on completion.
- Commit after each logical group on the feature branch (never `main`); **`git add -A` immediately before every commit** (the stale-index rule), then verify a clean `git status` before pushing.
- One new collection (`requisitions`), one additive `Candidate.requisitionId` (non-PII, retained on erasure), `ChangeUnit022` (3 indexes), no new runtime dependency, no scheduler, no broker.
- **T049** grep patterns MUST match the exact sentinel strings introduced by **T046** (`PipelineLogPiiScanTest`).
- Internal screens (FR-025) — **Lighthouse/WCAG gates are N/A** (the F50 precedent); no axe/Lighthouse CI gate for this feature.
- **Accepted residuals** (the F40/F42/F50 honest-residual precedent): (1) the list is bounded by `scanCap` with an explicit `truncated` flag — true server-side pagination on the computed SLA/scheduling sort fields is deferred; (2) a true double-submit of the scheduling-link bulk verb sends a second invitation email (supersede semantics — one live link, no double-booking); (3) bulk refusal of a since-ineligible candidate is reported as `not_contactable` synchronously where pre-checkable, with the async send-time gate as the authoritative 0-send backstop; (4) SC-007 timeline completeness depends on the `MESSAGE_SENT`/`BOOKING_CHANGED` emission sites, which T042 explicitly adds (they currently write only to `authAuditLog`), with T041 asserting they appear; (5) the PII scan asserts persisted docs + the CI grep is the captured-stdout backstop.
