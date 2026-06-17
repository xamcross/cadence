# Tasks: SLA Nudge Engine (F31)

**Input**: Design documents from `C:\Users\xamcr\Cadence\specs\017-sla-nudge-engine\`
**Prerequisites**: plan.md, spec.md, research.md (D1–D14), data-model.md, contracts/sla-nudge-api.md, quickstart.md

**Tests**: INCLUDED — constitution Principle VII (Test-First) is mandatory for business-logic/acceptance paths; the plan lists unit + Testcontainers + MockMvc + structural + Jasmine. Write each test FIRST and see it fail before implementing.

**Organization**: By user story (spec.md). All three stories are **P1** and ship together as the MVP increment (US1 = the policy input, US2 = visibility, US3 = the draft-and-send action). Multi-role plan-review fixes are folded into specific tasks (flagged ⚠️FIX).

## Path Conventions

Web app: `backend/src/main/java/com/cadence/...`, `backend/src/test/java/com/cadence/...`, `frontend/src/app/...`. Run flags (CLAUDE.md): `JAVA_HOME=C:/jdk-24.0.1`, cached `gradle-9.4.0`, `-Dapi.version=1.41`, `DOCKER_HOST=npipe:////./pipe/docker_engine`. Re-run once if the first multi-class Testcontainers run throws the one-time `GenericContainer` class-init error.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Config + the new enums the rest of the feature references. All new Java sources keep comments **pure ASCII** (the F30 NUL-byte/binary-detection lesson — scan new files with `git diff --numstat`, not just `.ps1`).

- [X] T001 [P] Create `backend/src/main/java/com/cadence/config/SlaProperties.java` (`@ConfigurationProperties("cadence.sla")`): `int amberMarginDays` default 1, `int defaultWindowDays` default 5 (the configured-but-zero fallback), `Duration scanInterval` default `PT5M`, `int scanBatchLimit` default 500. Add the `cadence.sla.*` block to `backend/src/main/resources/application.yml` (LF). ⚠️FIX (Backend) — `defaultWindowDays` backs FR-002 for any configured-but-zero edge; the scan separately SKIPS unconfigured workspaces (T021).
- [X] T002 [P] Create `backend/src/main/java/com/cadence/domain/SlaState.java` enum (`GREEN`, `AMBER`, `RED`) — server-computed, never persisted.
- [X] T003 [P] Create `backend/src/main/java/com/cadence/domain/SlaDraftStatus.java` enum (`OPEN`, `APPROVED`, `DISMISSED`, `INVALIDATED`).
- [X] T004 [P] Add append-only value `SLA_DRAFT_PENDING` to `backend/src/main/java/com/cadence/domain/RecruiterNotificationType.java` (do NOT reorder existing values).
- [X] T005 [P] Add append-only values `SLA_DRAFT_APPROVED`, `SLA_DRAFT_DISMISSED` to `backend/src/main/java/com/cadence/domain/CandidateEventType.java` (append after the existing F30 `STATUS_*` values; do NOT reorder).

---

## Phase 2: Foundational (Blocking Prerequisites)

**⚠️ CRITICAL**: No user-story work begins until this phase is complete — the draft entity, the migration, the cycle-break seam, the canonical-instant helper + its write-site wiring, and the no-oracle handler are shared by all three stories.

- [X] T006 [P] Create `backend/src/main/java/com/cadence/domain/SlaNudgeDraft.java` (`@Document("slaNudgeDrafts")`) per data-model §1: `id`, `workspaceId`, `candidateId`, `status` (`SlaDraftStatus`), `messageType` (`EmailMessageType`, always `SLA_HOLDING`), `detectedAt`, `actionedAt`, `actorMemberId`. **No PII.** `toString()` is id/status/ids only.
- [X] T007 [P] Create `backend/src/main/java/com/cadence/repository/SlaNudgeDraftRepository.java`: `Optional<SlaNudgeDraft> findFirstByWorkspaceIdAndCandidateIdAndStatus(String, String, SlaDraftStatus)`, `List<SlaNudgeDraft> findByWorkspaceIdAndStatus(String, SlaDraftStatus)`. (Insert + `DuplicateKeyException` de-dup is in the service.)
- [X] T008 ⚠️FIX (Backend+QA) Add an **overloaded** `List<Candidate> findByWorkspaceIdAndErasureStateAndLastContactAtBefore(String workspaceId, ErasureState state, Instant threshold, Pageable pageable)` to `backend/src/main/java/com/cadence/repository/CandidateRepository.java`. Do **NOT** change the existing 3-arg method (`RetentionService` depends on it). The scan pages with `PageRequest.of(0, scanBatchLimit)` so SC-013's bounded/index-backed guarantee holds.
- [X] T009 Create `backend/src/main/java/com/cadence/config/migration/ChangeUnit016_SlaNudgeIndexes.java` (`@ChangeUnit(id="016-sla-nudge-indexes", order="016", author="system")`): in `@Execution` create on `slaNudgeDrafts` a unique partial `{workspaceId:1, candidateId:1}` with `partialFilterExpression {status:"OPEN"}`, and a non-unique `{workspaceId:1, status:1}`. Native driver `createIndex` (the `ChangeUnit015` pattern). `@RollbackExecution` targeted `dropIndex` per index (never `dropIndexes()`). **No `candidates` index** (reuse the existing `{workspaceId,lastContactAt}` from `ChangeUnit001`). No dedupe step (new collection). Pure-ASCII comments.
- [X] T010 [P] ⚠️FIX (Backend, cycle-break) Create `backend/src/main/java/com/cadence/service/SlaDraftInvalidator.java` — a narrow interface `{ void invalidateOpenDraft(String workspaceId, String candidateId); }`. `CandidateErasureService` will depend on THIS, not the concrete `SlaNudgeService`, to avoid the `erasure → SLA → status → erasureRequest → erasure` constructor cycle (data-model §9).
- [X] T011 [P] Create `backend/src/main/java/com/cadence/service/CandidateActivityService.java` with `advanceLastContact(String workspaceId, String candidateId, Instant now)` — one value-free `mongoTemplate.updateFirst({_id, workspaceId, erasureState:ACTIVE}, $set lastContactAt=now)`, ACTIVE-guarded, idempotent. The single home of the canonical-instant write (research D1). Logs ids only (no enum→`kv`).
- [X] T012 ⚠️FIX (Backend) Wire the canonical-instant write at the qualifying sites that already exist (data-model §3, SC-014):
  - `backend/src/main/java/com/cadence/service/EmailDispatchService.java` — site 1: call `advanceLastContact(claimed.getWorkspaceId(), claimed.getCandidateId(), sentAt)` **after** the `SENDING→SENT` CAS, for candidate messages only (a candidateId is present). NOT a fold (that CAS is on `EmailDispatch.class`).
  - `backend/src/main/java/com/cadence/service/CandidateStatusService.java` — site 2: fold `.set("lastContactAt", now)` into the existing atomic publish `$set` (it is already an `Update` on `Candidate.class`, ACTIVE-guarded).
  - `backend/src/main/java/com/cadence/service/SlotReservationService.java` — site 3 (`book`, after the BOOKING→BOOKED CAS) and site 4 (`forwardCommitParent`, on the new booked round): call `advanceLastContact(...)`.
  (Site 5 — `SlaNudgeService.approve` synchronous advance — is implemented in US3/T034.)
- [X] T013 ⚠️FIX (Security) Create `backend/src/main/java/com/cadence/api/SlaNudgeExceptionHandler.java` — `@RestControllerAdvice(assignableTypes = SlaNudgeController.class)`. It MUST itself `@ExceptionHandler(RbacExceptions.ScopedNotFoundException.class)` → byte-identical 404 `{"error":"not_found"}` (the global `RbacExceptionHandler` emits `{"error":"not_found","message":"Not found."}` WITH a message → byte-divergent oracle; override it here, SC-016); plus 400 `{"error":"invalid_request"}` and 429 `{"error":"rate_limited"}` value-free envelopes.
- [X] T014 Create `backend/src/main/java/com/cadence/service/SlaNudgeService.java` skeleton: `implements SlaDraftInvalidator`; inject `SlaNudgeDraftRepository`, `MongoTemplate`, `CandidateRepository`, `ContactPermissionGate`, `EmailDispatchService`, `RecruiterNotificationService`, `CandidateAuditService`, `EmailTemplateService` (F21 preview), `WorkspaceConfigService` (zone), `SlaProperties`, **`java.time.Clock`** (the `MutableClock`/`AuthTestConfig` pattern), and **`@Lazy ObjectProvider<CandidateStatusService>`** (⚠️FIX Backend — second, independent cycle-break for the `statusLinkFor` dependency). Stub the public methods (`classify`, `scanWorkspace`, `approve`, `dismiss`, `previewDraft`, `silenceList`, `invalidateOpenDraft`) — implemented in the story phases.

**Checkpoint**: Foundation ready — draft collection + migration, cycle-break seam, canonical-instant helper wired at sites 1–4, and the no-oracle handler exist. User stories can begin.

---

## Phase 3: User Story 1 - Admin defines the silence rule (Priority: P1) 🎯 MVP slice 1

**Goal**: An Admin sets the workspace maximum-silence window; it persists, is audited, is Admin-only, and feeds breach detection. (Mostly reuse — `WorkspaceConfig.slaSilenceWindowDays` + `validateSla` (1–30) + the settings endpoint already exist.)

**Independent Test**: `PATCH /api/internal/workspace/settings {slaSilenceWindowDays:5}` as Admin persists + audits + survives restart; a non-Admin is refused server-side; an out-of-range value is rejected with the prior value unchanged.

### Tests for User Story 1 (write FIRST, ensure they FAIL) ⚠️

- [X] T015 [P] [US1] Add to `backend/src/test/java/com/cadence/sla/SlaWindowSettingContractTest.java` (MockMvc, `.with(csrf())`): Admin sets `slaSilenceWindowDays=5` → 200 + persisted (SC-002); RECRUITER/HM/INTERVIEWER/READ_ONLY → 403; out-of-range (0, 31, negative) → 400 `invalid_request`, prior value unchanged (FR-004); audit record asserted (SC-011). Reuses the existing F03 settings endpoint — no new endpoint.

### Implementation for User Story 1

- [X] T016 [US1] Confirm/extend `backend/src/main/java/com/cadence/service/WorkspaceConfigService.java` `validateSla` bounds (1–30) and that the SLA-window change is audited via the existing `audit.configChanged(...)` path; no new field. If the audit code for `sla_window` is a placeholder, finalise it (SC-011). (No new controller — US1 is the existing settings surface.)

**Checkpoint**: The policy input is live and Admin-gated; breach detection (US2) can consume it.

---

## Phase 4: User Story 2 - Recruiter sees who is in silence (Priority: P1) 🎯 MVP slice 2

**Goal**: Per-candidate green/amber/red classification + a workspace silence list, server-computed under an injected clock in the workspace zone; breaches clear when a qualifying activity advances `lastContactAt`.

**Independent Test**: Seed candidates with `lastContactAt` in the well-within / amber-margin / past-window bands → silence-list + per-candidate endpoints return GREEN/AMBER/RED correctly; a qualifying activity flips RED→GREEN on the next read; a cross-workspace candidate id returns the indistinguishable 404.

### Tests for User Story 2 (write FIRST, ensure they FAIL) ⚠️

- [X] T017 [P] [US2] Unit test `backend/src/test/java/com/cadence/sla/SlaClassifierTest.java`: `classify(...)` green/amber/red across the three bands; **boundary determinism** at the window edge and **across a DST change** in the workspace zone under a `MutableClock` (SC-009); terminal-outcome and erased → never RED/AMBER (FR-008/FR-020); null `lastContactAt` fail-safe GREEN.
- [X] T018 [P] [US2] Contract test `backend/src/test/java/com/cadence/sla/SlaVisibilityContractTest.java` (MockMvc, `.with(csrf())`): `GET /api/internal/sla/silence-list` (AMBER+RED only, `no-store`) and `GET /api/internal/candidates/{id}/sla` for ADMIN/RECRUITER → 200; HM/INTERVIEWER/READ_ONLY → 403; cross-workspace / unknown / erased candidate → **indistinguishable 404** (SC-016).
- [X] T019 [P] [US2] Integration test `backend/src/test/java/com/cadence/sla/CandidateActivityIT.java` (Testcontainers): each of sites 1–4 (email SENT, status publish, booking, reschedule) advances `lastContactAt` and flips a previously-RED candidate to GREEN on reclassification (SC-014, the part covered by foundational wiring); the ACTIVE guard blocks advancing an erased candidate.

### Implementation for User Story 2

- [X] T020 [US2] Implement `SlaNudgeService.classify(candidate, windowDays, amberMarginDays, now, zone)` → `SlaState` in `backend/src/main/java/com/cadence/service/SlaNudgeService.java` per data-model §5: `breachCutoff = now − Duration.ofDays(windowDays)`, `amberCutoff = now − Duration.ofDays(max(0, windowDays − amberMarginDays))`; RED/AMBER/GREEN; erased + terminal overrides; null-`lastContactAt` → treat as `createdAt`, both-null → GREEN.
- [X] T021 ⚠️FIX (Backend) [US2] Implement `SlaNudgeService.silenceList(workspaceId)`: resolve the effective window (workspace value, else `SlaProperties.defaultWindowDays`); read the WIDER `findByWorkspaceIdAndErasureStateAndLastContactAtBefore(ws, ACTIVE, amberCutoff, PageRequest)` (⚠️ amber range, NOT breach cutoff — AMBER rows are not past `breachCutoff`); classify each in Java; join `openDraftId` from `slaNudgeDraftRepository.findByWorkspaceIdAndStatus(ws, OPEN)`; omit GREEN. Also `candidateSla(ws, candidateId)` (scoped read → `ScopedNotFoundException` on missing/cross-workspace).
- [X] T022 [P] [US2] Create `backend/src/main/java/com/cadence/api/SlaNudgeDtos.java`: `SilenceListItem(candidateId, slaState, lastActivityAt, openDraftId)`, `SilenceListResponse(items)`, `CandidateSlaResponse(candidateId, slaState, lastActivityAt, openDraftId)` per contract A/B.
- [X] T023 [US2] Create `backend/src/main/java/com/cadence/api/SlaNudgeController.java` — `@RestController @PreAuthorize("hasAnyRole('ADMIN','RECRUITER')")`; `GET /api/internal/sla/silence-list` and `GET /api/internal/candidates/{candidateId}/sla`, both `Cache-Control: no-store`, workspace-scoped via the principal. Confirm `/api/internal/**` coverage in `RbacEndpointInventoryTest` (class-level `@PreAuthorize` is the source of truth).

**Checkpoint**: Silence is visible and correct server-side; the badge can render.

---

## Phase 5: User Story 3 - System drafts a holding message for one-click approval (Priority: P1) 🎯 MVP slice 3

**Goal**: A scheduled scan drafts one holding message per breaching, non-suppressed, non-terminal candidate; the recruiter previews, approves (one consent-gated send, breach clears) or dismisses (nothing sent). No auto-send.

**Independent Test**: Drive a candidate into breach via `sweep()` under a `MutableClock` → exactly one OPEN draft + one `SLA_DRAFT_PENDING`; repeated/overlapping sweeps add nothing; approve → exactly one `emailDispatches` row + breach clears; dismiss → zero; an erased/no-consent candidate is never drafted; approving a since-erased candidate sends nothing.

### Tests for User Story 3 (write FIRST, ensure they FAIL) ⚠️

- [X] T024 [P] [US3] Integration test `backend/src/test/java/com/cadence/sla/SlaScanIT.java` (Testcontainers, `MutableClock`): a breaching candidate yields exactly one OPEN draft + one `SLA_DRAFT_PENDING`; **repeated and back-to-back `sweep()` create no second draft** (unique partial index, SC-003); unconfigured workspace skipped; index-backed scan asserted (query-plan / explain) and a 1,000-candidate workspace sweep within the bound (SC-013). ⚠️FIX (QA) **SC-006 explicit replay**: stamp a mid-scan `RUNNING` checkpoint, then invoke the registered replay action (`checkpoints`'s `registerReplayAction(TASK_NAME, this::sweep)` path, the F23/F00.2 pattern — not merely a second `sweep()`) and assert **no duplicate draft and no duplicate notification**. ⚠️FIX (QA) **FR-012 no-assignee**: a breaching candidate with no assigned/active recruiter still produces exactly one `SLA_DRAFT_PENDING` (the notification is workspace-scoped — see T032 — so the fallback is inherent; assert it is not silently dropped).
- [X] T025 [P] [US3] Integration test `backend/src/test/java/com/cadence/sla/SlaSuppressionIT.java`: no draft created for an erased / no-basis / withdrawn / over-retention / undeliverable candidate past the window (SC-005, each state); a terminal-outcome (COMPLETE_OFFER/COMPLETE_REJECTED) candidate is not drafted (SC-012).
- [X] T026 [P] [US3] Integration test `backend/src/test/java/com/cadence/sla/SlaApproveDismissIT.java`: approve → exactly one `emailDispatches` row enqueued (SLA_HOLDING) + `lastContactAt` advanced + breach clears + `SLA_DRAFT_APPROVED` audit, `result="SENT_ENQUEUED"` (SC-004/SC-011/SC-014 site 5 **asserted independently** — the advance happens even when the dispatch is later refused); dismiss → zero dispatches + `SLA_DRAFT_DISMISSED` audit; a dismissed candidate is re-draftable on the next sweep. ⚠️FIX (QA) **Async-gate case**: approve a draft whose candidate is now WITHDRAWN/over-retention/undeliverable → approve still returns `SENT_ENQUEUED` and enqueues, but driving the dispatch (`EmailDispatchScheduler`/`dispatch`) marks the `emailDispatches` row `REFUSED` and **transmits nothing** (assert the row status + zero transport sends; FR-023 authoritative gate). Erased candidate at approve → indistinguishable 404, no enqueue.
- [X] T027 [P] [US3] Concurrency test `backend/src/test/java/com/cadence/sla/SlaConcurrentApproveIT.java` (gated latch, the F22/F23 pattern): N threads approve the same draft → at most one `emailDispatches` row; the CAS loser returns `ALREADY_ACTIONED` (SC-010, the draft CAS is the primary guard).
- [X] T028 [P] [US3] Integration test `backend/src/test/java/com/cadence/sla/ErasureInvalidatesDraftIT.java`: erasing a candidate with an OPEN draft flips it `INVALIDATED` (best-effort) AND a subsequent approve of any draft for that candidate sends nothing (the send-time gate / `statusLinkFor` 404 refuses) — SC-015.
- [X] T029 [P] [US3] Contract test `backend/src/test/java/com/cadence/sla/SlaDraftActionContractTest.java` (MockMvc, `.with(csrf())`): preview (`no-store`, missing-field warning, scoped-404), approve, dismiss for ADMIN/RECRUITER → 200; others 403; cross-workspace draftId → indistinguishable 404 (SC-016); idempotent `ALREADY_ACTIONED` on re-approve/re-dismiss.
- [X] T030 [P] [US3] ⚠️FIX (Security) Structural test `backend/src/test/java/com/cadence/sla/NoAutoSendStructuralTest.java`: a call-graph / constant-pool scan (the F22 `MailTransportSwapTest` precedent) asserting `SlaNudgeScheduler` and `SlaNudgeService.scanWorkspace` hold NO reference to `EmailDispatchService`, and the only `enqueue(...SLA_HOLDING...)` caller is `SlaNudgeService.approve` (SC-008 — absence, not a flag).
- [X] T031 [P] [US3] PII-scan test `backend/src/test/java/com/cadence/sla/SlaLogPiiScanTest.java`: drive scan→draft→preview→approve→dispatch with `SENTINELF31NAME_*` (candidate name) and a `SENTINELF31LINK_*` status-token sentinel; assert absence in captured logs, the `slaNudgeDrafts` doc, the dead-letter record, and the audit entry (SC-007/FR-024/FR-025).

### Implementation for User Story 3

- [X] T032 ⚠️FIX (QA, FR-012) [US3] Implement `SlaNudgeService.scanWorkspace(workspaceConfig, now)` in `backend/src/main/java/com/cadence/service/SlaNudgeService.java`: skip if unconfigured; effective window; paginated `findByWorkspaceIdAndErasureStateAndLastContactAtBefore(ws, ACTIVE, breachCutoff, PageRequest.of(0, scanBatchLimit))`; per candidate — `gate.evaluate(...).permit()` else skip (FR-019), terminal `statusOutcome` else skip (FR-020); `repo.insert(new SlaNudgeDraft(OPEN, SLA_HOLDING, detectedAt=now))` and on success `recruiterNotificationService.notify(ws, candidateId, SLA_DRAFT_PENDING)`; catch `DuplicateKeyException` → no-op (FR-014/FR-015). **FR-012 fallback is inherent**: `RecruiterNotificationService.notify(ws, candidateId, type)` is **workspace-scoped** (no per-candidate recruiter assignment exists in the MVP candidate model — research D11), so any active Admin/Recruiter sees it; there is no assignee to resolve and a breach is never silently dropped. Add a brief comment stating this (so a later reader doesn't add a phantom assignee lookup). Log ids/`.name()` only.
- [X] T033 [US3] Create `backend/src/main/java/com/cadence/scheduler/SlaNudgeScheduler.java` (the F23 `NoShowDefenseScheduler` shape): `TASK_NAME="sla-nudge-scan"`; `@PostConstruct registerReplayAction(TASK_NAME, this::sweep)`; `@Scheduled(fixedDelayString="${cadence.sla.scan-interval:PT5M}")`; `sweep()` = `checkpoints.start(TASK_NAME)` → iterate `workspaceConfigRepository.findAll()` → `slaNudgeService.scanWorkspace(cfg, now(clock))` → `checkpoints.complete(TASK_NAME)`. Injected `Clock`. **No `EmailDispatchService` reference** (SC-008).
- [X] T034 ⚠️FIX (Security) [US3] Implement `SlaNudgeService.approve(workspaceId, draftId, actorMemberId)`: CAS `findAndModify({_id:draftId, workspaceId, status:OPEN} → APPROVED, actionedAt, actorMemberId)` (primary single-winner guard; loser → `ALREADY_ACTIONED`); on win — `advanceLastContact(ws, candidateId, now)` (site 5, clears breach); then **in ONE try/catch** (the F30 `sendConfirmations` precedent) resolve `status_link` via `statusLinkProvider.getObject().statusLinkFor(ws, candidateId)` and `emailDispatchService.enqueue(ws, candidateId, SLA_HOLDING, "BASE", now, Map.of("status_link",link,"expected_date",date), candidateId)` → return `result="SENT_ENQUEUED"`; a thrown `ScopedNotFoundException` (erased) is caught and rethrown so the handler renders the indistinguishable **404** (no send); audit `SLA_DRAFT_APPROVED`. Never construct/send mail directly. **⚠️FIX (QA): there is NO synchronous `REFUSED_AT_SEND` result** — the consent gate re-evaluates asynchronously inside `EmailDispatchService.dispatch` (after the dispatch claim), so a since-ineligible (withdrawn/over-retention/undeliverable) candidate is REFUSED on the `emailDispatches` row at send time, not in this response (contract §D).
- [X] T035 [US3] Implement `SlaNudgeService.dismiss(workspaceId, draftId, actorMemberId)`: CAS `{_id,workspaceId,status:OPEN} → DISMISSED`; sends nothing; audit `SLA_DRAFT_DISMISSED`; loser → `ALREADY_ACTIONED`. And `previewDraft(ws, candidateId)`: require an OPEN draft (else scoped-404); render `SLA_HOLDING` via `EmailTemplateService` preview (decrypt name, resolve `{{status_link}}`, `[[missing:...]]` warnings); return subject/body/missingFields; controller sets `Cache-Control: no-store`; never log the rendered output (FR-013/FR-024).
- [X] T036 [US3] Implement `SlaNudgeService.invalidateOpenDraft(ws, candidateId)` (the `SlaDraftInvalidator` impl): best-effort CAS `{workspaceId,candidateId,status:OPEN} → INVALIDATED`. Then modify `backend/src/main/java/com/cadence/service/CandidateErasureService.java` `wipe(...)`: after the winning guarded `updateFirst`, call `slaDraftInvalidator.invalidateOpenDraft(ws, candidateId)` alongside `supersedeLiveScheduling` (depend on the **interface**, not the concrete service — cycle-break, T010). (FR-021/SC-015.)
- [X] T037 [US3] Add preview/approve/dismiss endpoints to `backend/src/main/java/com/cadence/api/SlaNudgeController.java`: `GET /api/internal/candidates/{candidateId}/sla/draft/preview` (`no-store`), `POST /api/internal/sla/drafts/{draftId}/approve`, `POST /api/internal/sla/drafts/{draftId}/dismiss`; add the `DraftPreviewResponse(messageType, subject, body, missingFields)` and `ActionResponse(draftId, result)` records to `SlaNudgeDtos.java` per contract C/D/E.
- [X] T038 [P] [US3] Create `frontend/src/app/features/scheduling/sla-nudge.service.ts` (HttpClient + `environment.apiBaseUrl`): `getSla(candidateId)`, `silenceList()`, `previewDraft(candidateId)`, `approve(draftId)`, `dismiss(draftId)` returning typed `Observable`s (the `scheduling.service.ts` pattern).
- [X] T039 [US3] Extend `frontend/src/app/features/scheduling/scheduling.component.ts` with a `.sla-nudge-panel` (signals, FormsModule): a green/amber/red badge for the entered candidate, the pending-draft preview (subject/body + missing-field warning), and Approve/Dismiss buttons (≥44 px) with inline `slaMsg` feedback. **Internal screen** — Lighthouse/WCAG N/A (F50/F51 precedent); axe runs advisory only.
- [X] T040 [P] [US3] Jasmine `frontend/src/app/features/scheduling/scheduling.component.spec.ts` (extend): the SLA badge renders GREEN/AMBER/RED from the service; Approve calls the endpoint and clears the badge; Dismiss sends nothing; preview shows the missing-field warning; buttons ≥44 px.

**Checkpoint**: All three stories functional — Admin sets the window, recruiters see silence, the scan drafts and the recruiter sends/dismisses; no auto-send.

---

## Phase 6: Polish & Cross-Cutting Concerns

- [X] T041 [P] Extend `.github/workflows/ci.yml` PII scan with `SENTINELF31*` (candidate-name + status-link/token sentinels) across the F31 sources/tests; keep the scan lines pure ASCII (Principle V). Confirm no `sms|whatsapp|twilio` scope creep (the SLA channel is email-only).
- [X] T042 [P] Run `RbacEndpointInventoryTest` + the full `com.cadence.sla.*` suite green; confirm the one-time `GenericContainer` re-run note holds; verify no enum is ever passed to `StructuredArguments.kv(...)` (the logstash Jackson-3 crash) across the new sources.
- [X] T043 [P] Byte-level scan all new Java sources for non-ASCII / NUL (the F30 binary-detection lesson — `git diff --numstat` must not show `-`/`-`); confirm `ChangeUnit016` is text, order "016", never renumbered.
- [X] T044 Run `frontend` `ng test --watch=false` + `ng build --configuration production` clean (the SLA panel adds to the existing `/scheduling` chunk — no new lazy route, no new candidate page).
- [X] T045 Execute `quickstart.md` end-to-end (set window → seed silent candidate → scan → RED badge + draft → preview → approve → SLA_HOLDING enqueued → breach clears; dismiss → nothing) and record the result.
- [X] T046 Multi-role implementation review (≥3 roles: Backend, Security/GDPR, QA — constitution §VI/C6) against the real diff; apply or report findings before closing.

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: no dependencies — start immediately. T001–T005 all [P].
- **Foundational (Phase 2)**: depends on Setup — **BLOCKS all user stories**. Within it: T006/T007/T008/T010/T011 are [P]; T009 (migration) independent; T012 (write-site wiring) depends on T011; T013 [P]; T014 depends on T006/T007/T010/T011.
- **US1 (Phase 3)**: depends on Foundational. Smallest slice (reuse-heavy).
- **US2 (Phase 4)**: depends on Foundational (classify needs the helper + draft repo). Independent of US1 at code level (consumes the existing window).
- **US3 (Phase 5)**: depends on Foundational; consumes US2's `classify`/draft repo. Largest slice.
- **Polish (Phase 6)**: after all desired stories.

### User Story Dependencies

- **US1 (P1)**: independent — the existing settings endpoint.
- **US2 (P1)**: independent of US1/US3 at the read level (it can show GREEN/AMBER/RED with no drafts).
- **US3 (P1)**: reuses US2's `classify` + the draft repo; the demonstrable approve→send leg is the headline action.

### Within Each User Story

- Tests (Phase X.tests) written FIRST and failing before implementation (constitution §VII).
- Models/enums → repository → service → controller → frontend.
- Site-5 advance (T034) is asserted independently of the SENT advance (T026) so SC-014 is non-circular.

### Parallel Opportunities

- All of T001–T005 (Setup) in parallel.
- T006, T007, T008, T010, T011, T013 (Foundational) in parallel; T009 independent.
- US1, US2, US3 test files ([P]) authored in parallel once Foundational is done.
- Within US3: T024–T031 (tests) all [P]; T038/T040 (frontend) [P] with backend.

---

## Parallel Example: User Story 3 tests

```text
# Author these failing tests together (different files, no deps):
Task: SlaScanIT.java              (de-dup, idempotent sweep, index-backed 1000)   # T024
Task: SlaSuppressionIT.java       (erased/no-consent/undeliverable/terminal)      # T025
Task: SlaApproveDismissIT.java    (dispatch counts, breach clears, audit)         # T026
Task: SlaConcurrentApproveIT.java (gated latch, one dispatch)                      # T027
Task: ErasureInvalidatesDraftIT.java (invalidate + gate refusal)                  # T028
Task: NoAutoSendStructuralTest.java (call-graph, SC-008)                           # T030
Task: SlaLogPiiScanTest.java      (SENTINELF31* across logs/doc/dead-letter/audit) # T031
```

---

## Implementation Strategy

### MVP (all three P1 stories ship together)

1. Phase 1 Setup → Phase 2 Foundational (CRITICAL — blocks everything; includes the cycle-break seam and the canonical-instant wiring).
2. US1 (window input) → US2 (visibility) → US3 (draft + approve/dismiss).
3. **STOP and VALIDATE** at each checkpoint; run `quickstart.md` after US3.
4. Polish (CI PII scan, ASCII/NUL scan, frontend build, multi-role review) → demo browser-to-DB.

### Notes

- [P] = different files, no incomplete-task dependency. [US#] maps each task to its story.
- The two cycle-breaks (the `SlaDraftInvalidator` interface + `@Lazy ObjectProvider<CandidateStatusService>`) are BOTH applied (belt-and-braces) — verify the context starts (`./gradlew test --tests "*ApplicationContext*"` or any `@SpringBootTest`).
- Reuse, do NOT recreate: `EmailMessageType.SLA_HOLDING` + its built-in body + `MergeTokenCatalogue` tokens; `WorkspaceConfig.slaSilenceWindowDays` + `validateSla`; the F00.1 `{workspaceId,lastContactAt}` index. Adding any email-template artefact must move atomically with the F21 `@PostConstruct`/`BuiltInTemplateCompletenessTest` — avoided here.
- Drive all breach timing via the `MutableClock` / stamped `lastContactAt`, never wall-clock sleeps (the F23 lesson).
