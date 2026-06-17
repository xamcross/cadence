# Implementation Plan: SLA Nudge Engine (F31)

**Branch**: `017-sla-nudge-engine` | **Date**: 2026-06-17 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/017-sla-nudge-engine/spec.md`

## Summary

F31 makes candidate silence **visible, measurable, and fixable** (backlog F31, Tier 2 P2; constitution §I "SLA nudges — draft-for-recruiter-approval mode only; auto-send is deferred"). A scheduled scan classifies every active candidate **green / amber / red** against the workspace silence window, and for a breaching candidate creates an honest holding-message **draft** that a recruiter sends with one click — or dismisses. **Nothing is sent automatically.**

Like F13/F20/F23/F30, F31 is **almost entirely orchestration of existing seams**: **no new runtime dependency, no broker, one new collection** (`slaNudgeDrafts`) and **one Mongock changeset** (`ChangeUnit016`, order "016" off the highest applied "015"). The reuse surface is unusually large and was verified against real source:

- **The silence policy already exists**: `WorkspaceConfig.slaSilenceWindowDays` (F03) + `WorkspaceConfigService.updateSettings` `validateSla` (bounds 1–30) + the `SettingsPatch`/`WorkspaceConfigResponse` DTOs. **US1 (FR-001/003/004) is already wired** — F31 consumes the setting; the only addition is using it for breach detection. (FR-002 default already flows from the global default when the field is unset.)
- **The holding template already exists**: `EmailMessageType.SLA_HOLDING`, the `BuiltInEmailTemplates` `SLA_HOLDING` body ("We're still working on your application", using `{{candidate_name}}/{{status_link}}/{{expected_date}}/{{recruiter_name}}/{{workspace_name}}`), and the `MergeTokenCatalogue` permission for `STATUS_LINK`+`EXPECTED_DATE`. **No new `EmailMessageType`, `MergeToken`, built-in body, or tone preset** (which would trip the F21 `@PostConstruct`/`BuiltInTemplateCompletenessTest`).
- **The send path already exists and is the authoritative gate**: `EmailDispatchService.enqueue(ws, candidateId, type, stageKey, scheduledFor, nonPiiContext, renderContextRef)` re-evaluates `ContactPermissionGate.evaluate` **after** winning the dispatch CAS, on every send, never cached. Approval routes through `enqueue` — it never constructs/sends mail itself — so a stale/ineligible draft physically cannot transmit (FR-016/FR-023).
- **The scheduler pattern already exists**: the F23 `NoShowDefenseScheduler` (own checkpoint name, `@PostConstruct registerReplayAction`, `@Scheduled(fixedDelay)`, per-workspace config cache, `SchedulerCheckpointService.start/complete`) is the template for `SlaNudgeScheduler` (checkpoint `"sla-nudge-scan"`).
- **The erasure/audit/notification seams already exist**: `CandidateErasureService.wipe` (single guarded `updateFirst` + `supersedeLiveScheduling` — F31 folds in best-effort open-draft invalidation), `CandidateAuditService.append`, `RecruiterNotificationService.notify`, `DeadLetterService.recordFailure` (PII-sanitising).

**The one load-bearing design decision — realise FR-005 by advancing the existing `lastContactAt`, not a new field (research D1).** The spec called for "a single canonical last-meaningful-activity instant advanced at qualifying write sites." `Candidate.lastContactAt` is exactly that field — documented as "GDPR last-activity" and explicitly flagged in `RetentionService` as "the activity-refresh path that moves `lastContactAt` forward is a forward concern of F13/F22" — but it is currently written **only** at candidate creation (`CandidateService.create`). F31 wires the dormant forward intent: advance `lastContactAt = now` at each qualifying commit (outbound candidate email **sent**, status published (F30), interview booked (F13), interview rescheduled (F20)) **and** synchronously on SLA-draft approval. This needs **no new field, no new index, no backfill** — the `{workspaceId, lastContactAt}` index (F00.1/`ChangeUnit005`) already backs the scan (SC-013), and `RetentionService`'s existing `findByWorkspaceIdAndErasureStateAndLastContactAtBefore` is the index-backed range query the scan reuses. Conflating SLA-silence and retention-age on one "last activity" instant is correct (an actively-progressing candidate is neither silent nor stale) and is the documented design.

The §II demonstrable leg (recruiter-facing, internal screen — Lighthouse/WCAG N/A per the F50/F51 precedent; **F31 ships no new candidate-facing page** — the holding email links to the already-WCAG-gated F30 status page): on the existing `/scheduling` recruiter view, a candidate's **amber/red** SLA badge and a pending **draft** appear; the recruiter previews the rendered holding message, clicks **Approve** → a real `SLA_HOLDING` email is enqueued through the consent-gated channel and dispatched (stub/real), carrying the candidate's F30 `{{status_link}}` — browser to database. **Dismiss** sends nothing.

## Technical Context

**Language/Version**: Java 21 (backend); TypeScript 5.4 / Angular 17.3 (frontend)
**Primary Dependencies**: Spring Boot 3.3.5 (web, data-mongodb, security w/ method security, actuator, aop, **scheduling**) — **no new runtime dependency**. Reuses F22 `EmailDispatchService.enqueue`/`ContactPermissionGate`/`RecruiterNotificationService`/`DeadLetterService`/`IdempotencyKeys`, F21 `EmailMessageType.SLA_HOLDING`/`BuiltInEmailTemplates`/`MergeTokenCatalogue`/`EmailTemplateService` preview/`MergeRenderer`, F03 `WorkspaceConfig.slaSilenceWindowDays`+`getTimeZone()`/`WorkspaceConfigService`, F04 `Candidate`/`CandidateRepository`/`CandidateErasureService`/`CandidateAuditService`, F30 `CandidateStatusService.statusLinkFor`/`statusOutcome`, F00.2 `SchedulerCheckpointService`/the F23 `NoShowDefenseScheduler` shape, F02 `@PreAuthorize`/`RbacExceptions.ScopedNotFoundException`/the `@RestControllerAdvice` no-oracle envelope, F01 `Clock`/`MutableClock` test pattern. Mongock 5.4.4; logstash-logback-encoder 9.0.
**Storage**: MongoDB 7.x (Atlas prod, Testcontainers `mongo:7` tests). **One new collection** `slaNudgeDrafts` (ids/instants/enums only — **no candidate PII at rest**, like `emailDispatches`). **No change to `candidates` schema** — F31 only *advances* the existing `lastContactAt` and *reads* `statusOutcome`/`erasureState`. **One new Mongock changeset** `ChangeUnit016_SlaNudgeIndexes` (order **"016"** off the highest applied **"015"**): unique **partial** `{workspaceId, candidateId}` over `status:OPEN` on `slaNudgeDrafts` (the F23 `confirmTokenHash` / F22 `emailDispatches` partial-unique precedent). The scan reuses the pre-existing `{workspaceId, lastContactAt}` index (F00.1) — no new index on `candidates`.
**Testing**: JUnit 5 + Mockito (unit: green/amber/red classification under `MutableClock` incl. boundary + DST in the workspace zone; amber-margin; stage-aware guardrail; structural no-auto-send call-graph). Testcontainers MongoDB (integration: scan creates exactly one draft across repeated/overlapping sweeps via the unique partial index; suppression for erased/no-consent/undeliverable; approve enqueues exactly one + advances `lastContactAt` + clears breach + audits; dismiss sends zero; gated concurrent-approve → one dispatch; erasure invalidates open draft + send-time gate refuses (SC-015); missed-fire replay no-dup (SC-006); index-backed range scan + 1,000-candidate (SC-013); each qualifying site advances `lastContactAt` parameterized (SC-014); `SENTINELF31*` PII scan across logs/dead-letter/audit/draft doc (SC-007)). MockMvc (contract: silence-list, per-candidate SLA, draft preview `no-store`+scoped-404 no-oracle (SC-016), approve/dismiss 5-role matrix + audit; window-set via the existing settings endpoint). Jasmine (recruiter SLA badge green/amber/red, approve/dismiss/preview, ≥44 px, inline messages — internal screen, axe advisory not blocking). `spring-security-test` (present).
**Target Platform**: Fly.io single Machine (backend), Cloudflare Pages (frontend) — topology unchanged.
**Project Type**: Web application (Spring Boot backend + Angular SPA) — both change.
**Performance Goals**: Scan completes within its `@Scheduled` interval (and a documented few-second bound) for ≥1,000 active candidates via the index-backed per-workspace range query (SC-013); no full-collection scan. No new candidate-facing page → no Lighthouse target (F31 surfaces are internal).
**Constraints**: No auto-send — structural absence verified by a call-graph test (only the recruiter approve service enqueues `SLA_HOLDING`; the scan never enqueues) (FR-010/SC-008). Send-time `ContactPermissionGate` is the single authoritative suppression point; approval routes through `enqueue` (FR-016/FR-023). One open draft per candidate (unique partial index; de-dup idempotent across overlapping sweeps) (FR-015). Concurrent approve → ≤1 dispatch (findAndModify CAS `{_id,status:OPEN}→APPROVED` + the F22 dispatch idempotency key) (FR-022/SC-010). Erasure best-effort invalidates the open draft; the send-time gate is authoritative (FR-021/SC-015). No candidate PII or recipient address in logs, dead-letter, audit, or the draft at rest (FR-024/FR-025/SC-007). Breach classification deterministic at boundary + DST under an injected `Clock` in the workspace zone (FR-007/SC-009). Stage-aware guardrail: terminal-outcome candidates are excluded from breach and never drafted (FR-008/FR-020). Drafting fires on **breach (red)**, not amber (FR-011).
**Scale/Scope**: One new collection (`slaNudgeDrafts`); one Mongock changeset (`ChangeUnit016`); one new scheduler (`SlaNudgeScheduler`), one new service (`SlaNudgeService` — classify, scan-create-draft, approve, dismiss, preview, silence-list), one new internal controller (`SlaNudgeController`) + DTOs + exception handler; new enums (`SlaState` GREEN/AMBER/RED, `SlaDraftStatus` OPEN/APPROVED/DISMISSED/INVALIDATED, `RecruiterNotificationType.SLA_DRAFT_PENDING`, `CandidateEventType.SLA_DRAFT_APPROVED`/`SLA_DRAFT_DISMISSED`); `SlaProperties` (amber margin, sweep interval, batch cap); modifies `CandidateErasureService.wipe` (fold open-draft invalidation), the four qualifying write sites + the email-SEND transition (advance `lastContactAt`); one recruiter Angular panel on the existing `/scheduling` view + an `sla-nudge.service.ts`; `ci.yml` PII scan extended with `SENTINELF31*`.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Gate | Question | Result |
|---|---|---|
| **C1** | Within MVP scope (spec §11)? | ✅ "SLA nudges — draft-for-recruiter-approval mode only; auto-send is deferred" is named in constitution §I and §11 MVP (backlog F31, Tier 2 P2). Auto-send is **structurally absent** (FR-010/SC-008), matching the deferral. Per-stage silence windows, the pipeline board (F51), and the dashboard silence metric (F50) are fenced out. |
| **C2** | New service, queue, or replica? | ✅ No broker/queue/cache. One new MongoDB **collection** (`slaNudgeDrafts`) is storage, not infrastructure (the F22 `emailDispatches` precedent). The scan is one more `@Scheduled` task on the F00.2 `SchedulerCheckpointService` pattern — no broker (constitution §IV async rule). |
| **C3** | Exposes candidate PII to unauthorized roles? | ✅ No. Recruiter endpoints are `@PreAuthorize("hasAnyRole('ADMIN','RECRUITER')")` + workspace-scoped via the candidate (scoped-404, no existence oracle). The draft stores **no PII** at rest (ids/enums/instants); preview decrypts merge fields under the role gate + `no-store` + never logs. The holding email goes only to consent-gated, non-erased, deliverable candidates (FR-019/FR-023). HM is **not** granted SLA actions (spec FR-018). |
| **C4** | Dependency outside the fixed stack? | ✅ No new dependency. Reuses F22/F21/F04/F03/F30/F00.2 seams; frontend reuses the existing Angular/HttpClient/role-guard patterns. |
| **C5** | New/modified Windows scripts with non-ASCII? | ✅ No new/modified `.ps1`/`.cmd`/`.bat`. CI PII-scan lines (ASCII) extended. New Java sources kept pure-ASCII in comments (the F30 NUL-byte/binary-detection lesson). |
| **C6** | Multi-role sub-agent review (≥3) scheduled? | ✅ Spec reviewed (4 roles, two loops, applied). This plan is reviewed in this command (below, against real source). Implementation review at task close. |
| **C7** | Downloads a build tool/runtime/CLI? | ✅ No. Reuses cached `gradle-9.4.0` + installed JDK; `npm ci` installs already-declared devDeps. No Playwright/Chromium (the E2E is the Testcontainers + Jasmine pairing against the real controller — the F14/F20/F23/F30 decision). |

**Initial gate: PASS.** No Complexity Tracking entries required.

**Post-Phase-1 re-check: PASS** — design adds one collection, one changeset, zero dependencies, zero new candidate-facing surface; reuses every F22/F21/F04/F03/F30/F00.2 seam. The only `candidates` interaction is advancing the existing `lastContactAt` (its documented forward intent) and reading `statusOutcome`/`erasureState`. Auto-send is structurally absent. See Phase 1 artifacts (research.md, data-model.md, contracts/, quickstart.md).

## Project Structure

### Documentation (this feature)

```text
specs/017-sla-nudge-engine/
├── plan.md              # This file
├── research.md          # Phase 0 — D1 lastContactAt-as-canonical-instant, D2 scan/scheduler, D3 draft entity+de-dup, D4 approve/dismiss+send-time gate, D5 classification+amber+DST, D6 suppression+stage guardrail, D7 no-auto-send structural, D8 erasure interaction, D9 template reuse, D10 RBAC/no-oracle, D11 notification scope, D12 ChangeUnit016, D13 frontend, D14 PII
├── data-model.md        # Phase 1 — SlaNudgeDraft + enums, lastContactAt write-site matrix, ChangeUnit016, classification rules, state transitions
├── quickstart.md        # Phase 1 — run/test/demo (set window → seed silent candidate → scan → badge red + draft → preview → approve → email enqueued → breach clears; dismiss → nothing)
├── contracts/
│   └── sla-nudge-api.md  # silence-list + per-candidate SLA + draft preview/approve/dismiss + window-set (reuse) + the lastContactAt advance contract + SLA_HOLDING enqueue
├── checklists/
│   └── requirements.md  # spec quality + multi-role spec-review notes (already present)
└── tasks.md             # Phase 2 (/speckit.tasks — NOT created here)
```

### Source Code (repository root)

```text
backend/src/main/java/com/cadence/
├── api/
│   ├── SlaNudgeController.java                  # NEW (internal, ADMIN|RECRUITER): GET /api/internal/sla/silence-list; GET /api/internal/candidates/{id}/sla; GET .../sla/draft/preview (no-store); POST .../sla/drafts/{draftId}/approve; POST .../sla/drafts/{draftId}/dismiss
│   ├── SlaNudgeDtos.java                        # NEW SilenceListItem(candidateId, slaState, lastActivityAt, openDraftId), CandidateSlaResponse, DraftPreviewResponse (rendered subject/body + missingFields), ActionResponse
│   └── SlaNudgeExceptionHandler.java            # NEW @RestControllerAdvice(assignableTypes=SlaNudgeController) — MUST itself @ExceptionHandler(ScopedNotFoundException) and render the SAME value-free body as its own not_found (the global RbacExceptionHandler returns {"error":"not_found","message":"Not found."} WITH a message -> byte-divergent oracle; override it here for byte-identical 404) + 400 invalid + 429
├── config/
│   ├── SlaProperties.java                       # NEW cadence.sla.* (amber-margin-days default 1; default-window-days fallback for a configured-but-zero edge; scan-interval; scan-batch-limit). The scan SKIPS unconfigured workspaces (configuredAt==null) — the RetentionService precedent — so FR-002's "default" means: configured workspaces always carry validated 1-30, unconfigured ones are not scanned
│   └── migration/
│       └── ChangeUnit016_SlaNudgeIndexes.java   # NEW order "016": unique partial {workspaceId,candidateId} over status:OPEN on slaNudgeDrafts (no candidates index — reuse F00.1 {workspaceId,lastContactAt})
├── domain/
│   ├── SlaNudgeDraft.java                       # NEW @Document("slaNudgeDrafts") — workspaceId, candidateId, status (SlaDraftStatus), messageType=SLA_HOLDING, detectedAt, actionedAt, actorMemberId; NO PII; @Field(write=NON_NULL) discipline on the partial-index status if needed (status always non-null → plain partial filter on OPEN)
│   ├── SlaDraftStatus.java                      # NEW enum OPEN, APPROVED, DISMISSED, INVALIDATED
│   ├── SlaState.java                            # NEW enum GREEN, AMBER, RED (server-computed, not persisted)
│   ├── RecruiterNotificationType.java           # MODIFIED + SLA_DRAFT_PENDING (append-only)
│   └── CandidateEventType.java                  # MODIFIED + SLA_DRAFT_APPROVED, SLA_DRAFT_DISMISSED (append-only)
├── repository/
│   ├── SlaNudgeDraftRepository.java             # NEW findFirstByWorkspaceIdAndCandidateIdAndStatus(...), findByWorkspaceIdAndStatus(...); insert + DuplicateKey de-dup
│   └── CandidateRepository.java                 # MODIFIED + an OVERLOADED findByWorkspaceIdAndErasureStateAndLastContactAtBefore(ws, state, threshold, Pageable) (the 3-arg method stays for RetentionService — do NOT change it in place); the scan passes PageRequest.of(0, scanBatchLimit)
├── scheduler/
│   └── SlaNudgeScheduler.java                   # NEW (the F23 NoShowDefenseScheduler shape) TASK_NAME "sla-nudge-scan"; @PostConstruct registerReplayAction; @Scheduled(fixedDelay); per-workspace slaSilenceWindowDays; reuse CandidateRepository.findByWorkspaceIdAndErasureStateAndLastContactAtBefore (index-backed, Pageable cap)
├── service/
│   ├── SlaNudgeService.java                     # NEW (implements SlaDraftInvalidator) classify(candidate,window,clock,zone)->SlaState; scanWorkspace (gate + terminal guardrail + paginated index-backed read + de-dup insert + notify); approve (CAS OPEN->APPROVED + advance lastContactAt + [statusLinkFor+enqueue SLA_HOLDING in ONE try/catch — F30 precedent] + audit); dismiss (CAS OPEN->DISMISSED + audit); previewDraft (F21 preview, no-store, scoped); silenceList (AMBER range = amberCutoff, classify in Java); invalidateOpenDraft(ws,candidateId). Injects CandidateStatusService via @Lazy/ObjectProvider (break the erasure->SLA->status->erasureRequest->erasure constructor cycle)
│   ├── SlaDraftInvalidator.java                 # NEW narrow interface { void invalidateOpenDraft(ws, candidateId); } — CandidateErasureService depends on THIS (not SlaNudgeService) so the wipe->invalidate edge does not pull CandidateStatusService into the constructor graph (cycle-break)
│   ├── CandidateActivityService.java            # NEW advanceLastContact(ws,candidateId,now) — single value-free updateFirst({_id,workspaceId,erasureState:ACTIVE}, $set lastContactAt=now), ACTIVE-guarded; used by all qualifying sites (DRY). Prefer this guarded helper over folding into the emailDispatches SENT $set (which is on EmailDispatch.class, not candidates)
│   ├── CandidateErasureService.java             # MODIFIED wipe(): after the winning $set, best-effort slaDraftInvalidator.invalidateOpenDraft(ws,candidateId) (CAS OPEN->INVALIDATED), alongside supersedeLiveScheduling
│   ├── EmailDispatchService.java                # MODIFIED dispatch(): on SENDING->SENT for a CANDIDATE message, advance lastContactAt (the generic outbound-contact site) — value-free
│   ├── CandidateStatusService.java              # MODIFIED publish(): advance lastContactAt in the same atomic $set (status publish is a qualifying activity)
│   └── SlotReservationService.java              # MODIFIED book()/forwardCommitParent(): advance lastContactAt on BOOKED / RESCHEDULED commit (qualifying activity)
backend/src/test/java/com/cadence/sla/           # NEW package (unit + Testcontainers + MockMvc + structural no-auto-send + PII scan)
frontend/src/app/features/scheduling/
├── scheduling.component.ts                       # MODIFIED + .sla-nudge-panel: per-candidate green/amber/red badge + pending-draft preview + Approve/Dismiss (signals, inline messages — internal screen)
└── sla-nudge.service.ts                          # NEW HttpClient service: getSla(candidateId), silenceList(), previewDraft(candidateId), approve(draftId), dismiss(draftId)
.github/workflows/ci.yml                          # MODIFIED PII scan + SENTINELF31* (candidate-name/status-link) across logs/dead-letter/audit
```

**Structure Decision**: Standard Cadence layout. F31 adds **one scheduler** (`SlaNudgeScheduler`, the F23 shape), **one service** (`SlaNudgeService`), **one internal controller**, and **one new collection** (`slaNudgeDrafts`) — everything else extends F22/F21/F04/F03/F30 seams. The `lastContactAt` advance is centralised in one helper (`CandidateActivityService.advanceLastContact`) called from the qualifying write sites, so the canonical-instant logic lives in one place (SC-014). The recruiter surface rides the existing `/scheduling` view (already `roleGuard('ADMIN','RECRUITER')`), an **internal screen** (Lighthouse/WCAG N/A per F50/F51) — F31 ships **no new candidate-facing page**, so no blocking axe/Lighthouse gate.

## Multi-role plan review (2026-06-17) — verdict: APPROVE-WITH-NITS (fixes applied)

Reviewers: Backend/Architecture, Security/GDPR, QA/DevOps — each verified claims against the **real source**. No BLOCKERs. The substantive findings were folded into the artifacts before `tasks.md`:

- **Spring constructor-injection cycle (Backend, SHOULD-FIX — would fail every `@SpringBootTest` at startup)**: wiring `SlaNudgeService` into `CandidateErasureService` closes the cycle `CandidateErasureService → SlaNudgeService → CandidateStatusService → ErasureRequestService → CandidateErasureService` (`CandidateStatusService` ctor-injects `ErasureRequestService` which ctor-injects `CandidateErasureService`). **Fixed** → `CandidateErasureService` depends on a narrow `SlaDraftInvalidator` interface (not the concrete service), and `SlaNudgeService` injects `CandidateStatusService` via `@Lazy`/`ObjectProvider` — two independent cycle-breaks. Pinned in the source tree + data-model §9.
- **Reused scan finder is UNBOUNDED (Backend + QA, SHOULD-FIX — contradicts SC-013)**: `CandidateRepository.findByWorkspaceIdAndErasureStateAndLastContactAtBefore(...)` returns a raw `List` with no `Pageable`. **Fixed** → add an **overloaded** `(…, Pageable)` finder (the 3-arg stays for `RetentionService`, unchanged in place); the scan pages with `PageRequest.of(0, scanBatchLimit)`. SC-013's query-plan assertion targets the paginated call.
- **Silence-list needs the AMBER range, not the breach cutoff (Backend, SHOULD-FIX)**: AMBER candidates have `lastContactAt >= breachCutoff`, so the drafting query won't return them. **Fixed** → the scan reads at `breachCutoff` (drafting); the silence-list reads at `amberCutoff = now − (windowDays − amberMarginDays)` and classifies AMBER/RED in Java (contract §A, data-model §5).
- **Per-workspace enumerator unnamed (QA, SHOULD-FIX)**: **Fixed** → the sweep iterates `workspaceConfigRepository.findAll()` (bounded by the small config count, not candidate volume — single-instance MVP), skipping unconfigured workspaces; per config computes its cutoff and runs the paginated finder (research D2).
- **No-oracle 404 body divergence (Security, SHOULD-FIX)**: the global `RbacExceptionHandler` renders `ScopedNotFoundException` as `{"error":"not_found","message":"Not found."}` (WITH message) — byte-divergent from the SLA handler's own `not_found`. **Fixed** → `SlaNudgeExceptionHandler` itself `@ExceptionHandler(ScopedNotFoundException)` and renders the identical value-free body, so {unknown, malformed, cross-workspace, erased} are byte-identical (SC-016).
- **Approve→`statusLinkFor` on an erased candidate throws 404 *before* the send-time gate (Security, SHOULD-FIX)**: `statusLinkFor` throws `ScopedNotFoundException` for an inactive candidate. **Fixed** → `approve` wraps `statusLinkFor` + `enqueue` in ONE try/catch (the F30 `SlotReservationService.sendConfirmations` precedent); a thrown link-resolution is a no-send approve outcome (fail-safe), and the contract pins the erased-at-approve case to the indistinguishable 404 / no-send rather than a separate `REFUSED_AT_SEND` (contract §D).
- **`advanceLastContact` must be the ACTIVE-guarded helper, not a fold into the `emailDispatches` SENT `$set` (Backend + Security, NIT)**: that CAS is on `EmailDispatch.class` (cannot set a candidate field) and is not ACTIVE-guarded. **Fixed** → site 1 is a separate `advanceLastContact(claimed.getWorkspaceId(), claimed.getCandidateId(), sentAt)` call after the SENT CAS (data-model §3 row 1); only `CandidateStatusService.publish` genuinely folds the set in (it is already an `Update` on `Candidate.class`, ACTIVE-guarded).
- **SC-010 primary guard is the draft CAS, not the idempotency key (Security, NIT)**: two approves computing different `now` would derive different F22 idempotency keys; the **draft `{_id,status:OPEN}→APPROVED` CAS** is the real single-winner guard (loser `matchedCount==0`), the key is the backstop. Pinned in data-model §6 / contract §D.
- **SC-014 site 5 tested independently (QA, NIT)**: the approve-synchronous advance must be asserted on its own (advance happens even if the dispatch is later REFUSED), not folded into the SENT case — pinned in contract test surface.
- **FR-005 booking-site clarification (Security + QA, NIT)**: a candidate slot-pick commits via `SlotReservationService.book` — a *meaningful interview milestone both parties committed to*, distinct from a passive page-view/erasure-submit/bounce; it does not let a candidate *self-suppress* by mere presence (research D1).
- **Index citation (Backend + QA, NIT)**: the `{workspaceId,lastContactAt}` index is created by **`ChangeUnit001`** (not `ChangeUnit005`, which explicitly does not recreate it). Corrected in research D1 / data-model §3-§4.

**Post-Phase-1 re-check after fixes: PASS.** No remaining blocking items. Residual mechanics for `tasks.md`: the exact `@Lazy`/`ObjectProvider` vs `SlaDraftInvalidator` split (one is sufficient — apply both for safety), the paginated finder signature, and the silence-list amber-range query.

## Complexity Tracking

No constitution violations — table intentionally empty.

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| (none) | — | — |
