# Implementation Plan: F51 Pipeline View

**Branch**: `025-pipeline-view` | **Date**: 2026-06-18 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/025-pipeline-view/spec.md`

## Summary

The Pipeline View is the recruiter's primary working surface — a single sortable/filterable list of every active candidate, colour-coded by SLA health and scheduling progress, plus bulk actions and a per-candidate timeline. It is also the feature that **introduces the requisition concept and the candidate→requisition link** that F50/F40/F32 deferred here, and uses it to give Hiring Managers server-side scoped visibility (the `Assignment` model already reserves `ResourceType.REQUISITION`).

**Technical approach** — overwhelmingly orchestration over existing seams:

- **Pipeline list** (`GET /api/internal/pipeline`): one bounded, index-backed read of active candidates (workspace-wide for Admin/Recruiter/Read-only; requisition-scoped for Hiring Manager via `AssignmentService.assignedResourceIds(...)`), then compose each row in memory — **SLA colour reuses `SlaNudgeService` classification with zero extra queries** (all classifier inputs are already on the candidate doc), **stage** resolves the best decrypted label, and **scheduling status** comes from a single new batch read of `schedulingRequests` keyed on the candidate-id set, mapped through one documented pure function. Compose → filter → sort → paginate in memory (the F50 silence-list precedent), bounded by a configurable cap with an explicit `truncated` flag (no silent cap).
- **Requisitions** (minimal-but-real): a new `requisitions` collection (title + open/closed + optional external label; **no PII**) with Admin create/update and Admin/Recruiter/Read-only list; candidate→requisition linking by Admin/Recruiter (audited); Hiring-Manager→requisition assignment reusing `AssignmentService.create(..., REQUISITION, ...)`.
- **Bulk actions** (`POST /api/internal/pipeline/bulk`, Admin/Recruiter only): fan-out over the existing single-candidate seams — `EmailDispatchService.enqueue(... HOLD_UPDATE ...)` (per-candidate idempotency inherited from the unique dispatch key) and `SchedulingService.initiate(...)` (supersede semantics). A **synchronous** `ContactPermissionGate` pre-check yields a single coarse "not_contactable" skip (no GDPR oracle); the existing **asynchronous** send-time gate remains the authoritative backstop for the TOCTOU window.
- **Timeline** (`GET /api/internal/pipeline/candidates/{id}/timeline`): a read-only chronological projection over the candidate-keyed append-only `auditLog` (PII-free `CandidateAuditEvent` stream — reuses the F00.1 `{candidateId, occurredAt}` index) enriched with feedback-pending status; HM-scoped with the same no-oracle 404.

**One new collection** (`requisitions`), **one additive `Candidate.requisitionId` field** (`@Field(write=NON_NULL)`, nullable, non-PII, retained on erasure), **one Mongock changeset** (`ChangeUnit022`, 3–4 indexes), **no new runtime dependency, no scheduler, no broker, no queue**. `CandidateErasureService.wipe` is unchanged (erased candidates excluded by the `erasureState=ACTIVE` predicate). Frontend: one lazy Angular `pipeline` feature (list + timeline + bulk) and an Admin requisition-management surface, behind role guards; internal screens → no Lighthouse/WCAG gate (FR-025, the F50 precedent).

## Technical Context

**Language/Version**: Java 21 (backend, toolchain pinned); TypeScript 5.4 / Angular 17.3 (frontend)
**Primary Dependencies**: Spring Boot 3.3.5 (web, data-mongodb, security w/ method security, actuator, aop); Mongock 5.4.4; logstash-logback-encoder 9.0. **No new backend or frontend runtime dependency** — SLA classification reuses F31 `SlaNudgeService`; scoping reuses F02 `AssignmentService`; bulk send reuses F22 `EmailDispatchService`/`ContactPermissionGate` + F13 `SchedulingService`; timeline reuses the F04 candidate `auditLog`. Composition is plain Spring Data Mongo reads + in-memory compose/sort/filter.
**Storage**: MongoDB 7.x (Atlas prod, Testcontainers `mongo:7` tests). **One new collection** `requisitions` (workspaceId, title, status, externalLabel?, createdAt, createdByMemberId — **no PII/secret**, un-encrypted by design, the `interviewTemplates`/`managedCalendarEvents` precedent). **Extends `candidates`** with one additive field `requisitionId` (`@Field(write=NON_NULL)`, non-PII, retained on erasure). Reuses the F02 `assignments` collection (REQUISITION resource type), `schedulingRequests` (F13/F20/F23, batch status read), `auditLog` (F04 timeline), `authAuditLog` (extended). Reads `workspaceConfig` (F03 SLA window).
**Testing**: JUnit 5 + Testcontainers (singleton `mongo:7`), MockMvc (per-role contract), Jasmine (frontend). `MutableClock` for SLA/polling determinism; gated concurrency latch for the bulk idempotency test.
**Target Platform**: Fly.io single Machine (backend), Cloudflare Pages (frontend SPA).
**Project Type**: web (Angular SPA + Spring Boot single JAR + MongoDB).
**Performance Goals**: First page of a 200-active-candidate workspace returned (SLA + scheduling status computed) in **< 3 s** end-to-end on a desktop connection (SC-002), via exactly two index-backed reads (candidate page + batch scheduling) + in-memory compose.
**Constraints**: server-side role scoping with no cross-requisition existence oracle (FR-013, SC-004); zero plaintext PII in logs incl. bulk results/skip reasons and timeline (FR-024, SC-008); bulk non-contactable skip is a single coarse byte-identical reason (FR-018, SC-006); bulk safe-to-repeat (FR-019); bounded scan cap with explicit truncation (no silent cap); single-instance topology (§IV).
**Scale/Scope**: ~3 backend controllers (pipeline, requisitions, candidate-requisition-link) + 1 bulk endpoint, 2–3 services, 1 new collection + 1 candidate field, 1 Mongock changeset (3–4 indexes), ~5 new repository finders, new audit event types, 1 Angular `pipeline` lazy feature + an Admin requisition surface. Largest of the remaining MVP features (last in the delivery sequence).

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Gate | Question | Result |
|---|---|---|
| **C1** | Within MVP scope (spec §11)? | **PASS** — Pipeline View is §5.2 FR-14 (bulk actions) + §4 Pillar C, an explicit MVP backlog item (F51). The requisition concept is the linkage the backlog explicitly assigns to F51 (the F50 erratum). Bulk "close out" is deferred (documented, intentional narrowing); full requisition management is out of scope (FR-008..FR-011 bounded to title + open/closed + assignment). |
| **C2** | New service, queue, or replica? | **PASS** — one new MongoDB **collection** (`requisitions`), which is normal per-feature data modelling, not a new runtime service/queue/replica/cache. Bulk is a synchronous fan-out over the existing outbox/scheduling seams — no broker (§IV). No new `@Scheduled` task. |
| **C3** | Exposes candidate PII to unauthorized roles? | **PASS (load-bearing gate)** — this feature's core purpose. Visibility is enforced server-side: Admin/Recruiter/Read-only → workspace-wide; Hiring Manager → only candidates whose `requisitionId ∈ assignedResourceIds(REQUISITION)`; Interviewer → denied (403). Out-of-scope requisitions/candidates → byte-identical `ScopedNotFoundException` 404 (no oracle). Decrypted name/stage returned to authorized staff only, under a bounded cap, never logged. Bulk skip reason is a single coarse value (no consent/erasure oracle). |
| **C4** | Dependency outside the fixed stack? | **PASS** — zero new dependencies; reuses `SlaNudgeService`, `AssignmentService`, `EmailDispatchService`/`ContactPermissionGate`, `SchedulingService`, candidate `auditLog`, Spring Data Mongo. |
| **C5** | New/modified Windows scripts with non-ASCII? | **PASS** — no new `.ps1/.cmd/.bat`. New Java (incl. the Mongock changeset) keeps comments ASCII; new sources scanned for NUL/non-ASCII at task close (the F30 lesson). |
| **C6** | Multi-role sub-agent review (≥ 3 roles) scheduled? | **PASS** — spec already reviewed by BA/Security/QA/Backend (findings applied); a second ≥ 3-role review (Backend/Security/QA) runs at implementation task close (the established two-loop pattern). |
| **C7** | Downloads any build tool/runtime/CLI? | **PASS** — uses the cached gradle-9.4.0 binary + installed JDK/Node; no downloads. |

**No complexity-tracking violations.** A new domain collection is the minimum modelling for the requisition concept the backlog assigns to F51; no architectural pattern beyond the established controller/service/repository/changeset shape is introduced.

## Project Structure

### Documentation (this feature)

```text
specs/025-pipeline-view/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/           # Phase 1 output
│   └── pipeline-api.md
├── checklists/
│   └── requirements.md  # spec quality + multi-role review log
└── tasks.md             # Phase 2 output (/speckit.tasks — NOT created here)
```

### Source Code (repository root)

```text
backend/
  src/main/java/com/cadence/
    api/
      PipelineController.java               # NEW — GET /api/internal/pipeline (list) + POST /pipeline/bulk
                                            #        + GET /pipeline/candidates/{id}/timeline
      RequisitionController.java            # NEW — requisition CRUD-lite + HM assignment + candidate link
      PipelineDtos.java                     # NEW — wire records (pipeline row, page, bulk request/result,
                                            #        timeline event, requisition, link request, enums)
      PipelineExceptionHandler.java         # NEW — @Order(HIGHEST_PRECEDENCE) no-oracle envelope
                                            #        (assignableTypes = {PipelineController, RequisitionController})
    service/
      PipelineService.java                  # NEW — visibility predicate by role; compose row (SLA+stage+sched);
                                            #        sort/filter/paginate in memory; timeline projection
      PipelineBulkService.java              # NEW — fan-out over enqueue/initiate; synchronous ContactPermissionGate
                                            #        pre-check (gate.evaluate(ws,candidateId) per selected candidate,
                                            #        bounded by bulk-max; optional evaluate(Candidate) overload to
                                            #        reuse the loaded row); ALL deny/exception causes -> one coarse
                                            #        not_contactable (no oracle); per-candidate result; max guard.
                                            #        Async send-time gate remains the authoritative TOCTOU backstop
      RequisitionService.java               # NEW — create/update/list requisitions; candidate-link (audited);
                                            #        HM assignment via AssignmentService
      PipelineProperties.java               # NEW — @ConfigurationProperties("cadence.pipeline")
                                            #        (scan-cap, bulk-max, page-size)
      SlaNudgeService.java                  # MODIFIED — expose a thin public classifyCandidate(cfg, candidate, now)
                                            #            delegating to the EXISTING private classify(Candidate,
                                            #            windowDays, now) with the same amber-margin (no N+1, no drift)
    domain/
      Requisition.java                      # NEW — @Document; workspaceId/title/RequisitionStatus/externalLabel?/
                                            #        createdAt/createdByMemberId (no PII)
      RequisitionStatus.java                # NEW — OPEN, CLOSED
      Candidate.java                        # MODIFIED — add requisitionId (@Field(write=NON_NULL), nullable)
      AuthEventType.java                    # MODIFIED — REQUISITION_CREATED/UPDATED/HM_ASSIGNED/HM_UNASSIGNED
      CandidateEventType.java               # MODIFIED — REQUISITION_LINKED (append-only)
    repository/
      RequisitionRepository.java            # NEW — findByWorkspaceId(+Status), findByWorkspaceIdAndId
      CandidateRepository.java              # MODIFIED — findByWorkspaceIdAndErasureState(...,Pageable);
                                            #            findByWorkspaceIdAndErasureStateAndRequisitionIdIn(...)
                                            #            countByWorkspaceIdAndErasureState(...) (truncation flag)
      SchedulingRequestRepository.java      # MODIFIED — findByWorkspaceIdAndCandidateIdIn(...) (batch status,
                                            #            projected). NO new index — reuses ChangeUnit012's
                                            #            {workspaceId,candidateId,createdAt:-1} prefix
      CandidateAuditEventRepository.java    # REUSED — findByWorkspaceIdAndCandidateIdOrderByOccurredAtAscIdAsc
                                            #          (existing method; auditLog {candidateId,occurredAt:-1})
    config/migration/
      ChangeUnit022_PipelineIndexes.java    # NEW — 3 indexes: requisitions {workspaceId,status};
                                            #        candidates {workspaceId,erasureState,createdAt};
                                            #        candidates {workspaceId,requisitionId}
                                            #        (scheduling batch read reuses ChangeUnit012 prefix)
  src/test/java/com/cadence/pipeline/
    PipelineItBase.java                     # NEW — seed helpers (candidates across stages/SLA/sched + requisitions
                                            #        + HM assignments), MutableClock, distinct tokenHash per req
    PipelineListContractTest.java           # NEW — 5-role matrix: Admin/Recruiter 200 full; Read-only 200 full
                                            #        (no bulk/export); HM 200 scoped; Interviewer 403; out-of-scope
                                            #        requisition filter -> no-oracle; cross-workspace ignored
    PipelineHmScopingIT.java                # NEW — HM sees only assigned-req candidates; empty-assignment -> empty
                                            #        page (NOT unfiltered); unassigned candidate invisible to HM;
                                            #        link-move flips visibility; closed/unassigned req drops rows
    PipelineComposeIT.java                  # NEW — SLA colour == SlaNudgeService verdict (FR-004); scheduling-status
                                            #        mapping table (no-link/sent/booked/no-show/rescheduled/SUPERSEDED/
                                            #        EXPIRED edge rows); no-stage candidate -> "not started" + default
                                            #        colour; sort tie-breaker stable; erased absent (SC-009);
                                            #        default excludes terminal, INCLUDE_CLOSED reveals them, candidate
                                            #        on CLOSED requisition treated-as-closed (FR-003); SC-001 red-SLA
                                            #        filter returns the complete breaching set OR truncated:true (never
                                            #        silently drops a breacher below scan-cap)
    SlaClassifyReuseTest.java               # NEW — classifyCandidate(cfg,candidate,now) delegates to the identical
                                            #        static classify(...) with the same window/amber inputs (FR-004
                                            #        anti-drift; QA review #7)
    PipelineSchedulingStatusTest.java       # NEW — pure unit test of the status mapping function (FR-005)
    PipelineBulkIT.java                     # NEW — 8 selected (6 ok / 2 non-contactable) -> coarse skip;
                                            #        BYTE-IDENTICAL skip across {erased,withdrawn,over-retention,
                                            #        undeliverable,no-consent} AND unknown-at-exec (one reason, no
                                            #        not_found oracle); at-limit accepted / over-limit 400;
                                            #        per-candidate personalization; initiate-exception (incl.
                                            #        UnschedulableRequired member-ids) collapses to coarse skip,
                                            #        payload absent from result+logs; bulk 403 HM/Interviewer/RO
    PipelineBulkConcurrencyIT.java          # NEW — gated 2-thread same-selection -> exactly one send per candidate
                                            #        (email idempotency); scheduling-link supersede (one live link)
    PipelineBulkToctouIT.java               # NEW — candidate passes SYNC pre-check then erased before the outbox
                                            #        claim -> async send-time gate fail-closes: 0 sends, dispatch
                                            #        REFUSED, not resurrected (SC-006 authoritative backstop)
    PipelineTimelineIT.java                 # NEW — chronological order; HM own-candidate ok; HM out-of-scope 404
                                            #        (no oracle); empty timeline; erased -> no residual PII; feedback
                                            #        pending shown; SCORECARD_SUBMITTED appears as a label with NO
                                            #        payload/free-text (seed a PII sentinel, assert absent — FR-022)
    RequisitionContractIT.java              # NEW — create/update Admin-only (403 others); list Admin/Recruiter/RO;
                                            #        HM assign Admin-only; candidate link Admin/Recruiter + audited
                                            #        on BOTH set AND change (R1->R2 re-link audits the removal too);
                                            #        no-oracle 404 on unknown requisition/candidate; external label
                                            #        (atsExternalJobId/importRequisitionLabel) surfaced for manual
                                            #        link (FR-011)
    PipelineLogPiiScanTest.java             # NEW — TRACE compose + bulk + timeline with name/stage sentinels:
                                            #        absent in logs+audit+bulk-result+dispatch row
    PipelineIndexTest.java                  # NEW — assert ChangeUnit022 indexes exist
    PipelinePerfIT.java                     # NEW (@Tag("perf")) — 200 candidates + scheduling rows; warm-up read
                                            #        (discarded) then assert < 3s with CI-safe wall-clock margin (the
                                            #        DashboardPerfIT precedent). Index-BACKING is asserted by
                                            #        PipelineIndexTest (existence), NOT a brittle explain()-plan gate
    PipelineErasureRegressionIT.java        # NEW — requisitionId retained on erasure; erased absent from all roles;
                                            #        existing SLA-scan / scheduling reads unaffected (SC-009)

frontend/
  src/app/features/pipeline/
    pipeline-list.component.ts              # NEW — standalone, $localize; table + filters + sort + bulk select/action
    pipeline-list.component.scss            # NEW
    pipeline-list.component.spec.ts         # NEW — Jasmine: filter/sort, bulk-select, bulk disabled for RO/HM,
                                            #        per-candidate result render, empty state, poll refresh
    candidate-timeline.component.ts         # NEW — standalone drawer/detail; chronological events
    candidate-timeline.component.spec.ts    # NEW — Jasmine: order, empty state, out-of-scope 404 handling
    pipeline.service.ts                     # NEW — HttpClient against /api/internal/pipeline
  src/app/features/admin/requisitions/
    requisitions.component.ts               # NEW — Admin: create/close requisition + assign HM + link candidate
    requisitions.component.spec.ts          # NEW — Jasmine: create/close, assign, link
    requisitions.service.ts                 # NEW
  src/app/app.routes.ts                     # MODIFIED — lazy routes: pipeline (ADMIN/RECRUITER/READ_ONLY/HM),
                                            #            admin/requisitions (ADMIN; link also RECRUITER)
  src/app/features/shell/*                  # MODIFIED — nav links by role (Pipeline for 4 roles; Requisitions Admin)

.github/workflows/ci.yml                    # MODIFIED — add F51 SENTINEL PII scan patterns (name/stage)
```

**Structure Decision**: Web application (Option 2). Backend follows the established `api/` + `service/` + `repository/` + `config/migration/` layout and mirrors the F31/F50 internal-feature shape (controller + dtos + `@Order(HIGHEST_PRECEDENCE)` no-oracle handler + service + `@ConfigurationProperties`). The requisition is a new `@Document` modelled like the PII-free `interviewTemplates`/`managedCalendarEvents` collections. Frontend follows the F42/F50 internal-screen shape (lazy standalone components + service + role-guarded routes); the requisition admin surface sits under `features/admin/*` next to the dashboard.

## Complexity Tracking

> No Constitution Check violations. Table intentionally empty.

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| — | — | — |
