# Implementation Plan: F50 Core Dashboard

**Branch**: `024-core-dashboard` | **Date**: 2026-06-18 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/024-core-dashboard/spec.md`

## Summary

The Core Dashboard is a **read-only** internal (staff-facing) lens over data other features already produce. It surfaces three MVP metrics for a user-selectable window (7 / 30 / 90 days, default 30) plus a CSV export:

1. **Median time-to-schedule** — `bookedAt − sentAt` over scheduling requests confirmed within the window.
2. **No-show rate** — `noShowAt`-stamped ÷ past qualifying interviews (booked, `bookedStartAt` in window and elapsed).
3. **Current silence list** — candidates at-risk/breached on their workspace SLA window, reusing the F31 `SlaNudgeService` classification verbatim.

**Technical approach**: A single new `DashboardService` computes figures on read via bounded, index-backed range scans over the existing `schedulingRequests` collection (which carries **no candidate PII** — ids/instants/enums only) and reuses `SlaNudgeService.silenceList(...)` for the silence surface (the only PII path — candidate names, decrypted under a fixed cap). **No new collection, no new runtime dependency, no scheduler, no broker.** One Mongock changeset (`ChangeUnit021`) adds two compound indexes on `schedulingRequests`. CSV export reuses the F42 `CsvInjectionEscaper`. Two endpoints (`GET /api/internal/dashboard`, `GET /api/internal/dashboard/export`) under the established `@PreAuthorize` + no-oracle-handler + `RbacEndpointInventoryTest` pattern. The frontend is one lazy Angular standalone feature behind a role guard (internal screen — no Lighthouse/WCAG gate, the F50/F51 precedent).

## Technical Context

**Language/Version**: Java 21 (backend, toolchain pinned); TypeScript 5.4 / Angular 17.3 (frontend)
**Primary Dependencies**: Spring Boot 3.3.5 (web, data-mongodb, security w/ method security, actuator, aop); Mongock 5.4.4; logstash-logback-encoder 9.0. **No new backend or frontend runtime dependency** — CSV injection-safety reuses the F42 `CsvInjectionEscaper`; SLA classification reuses F31 `SlaNudgeService`; aggregation is plain Spring Data Mongo range queries + in-memory median.
**Storage**: MongoDB 7.x (Atlas prod, Testcontainers `mongo:7` tests). **No new collection.** Reads `schedulingRequests` (F13/F20/F23), `candidates` (F04, silence-list names), `workspaceConfig` (F03, SLA window). Extends `authAuditLog` with one append-only event type (`DASHBOARD_EXPORTED`).
**Testing**: JUnit 5 + Testcontainers (singleton `mongo:7`), MockMvc (per-role contract), Jasmine (frontend). `MutableClock` for the windowed-metric determinism.
**Target Platform**: Fly.io single Machine (backend), Cloudflare Pages (frontend SPA).
**Project Type**: web (Angular SPA + Spring Boot single JAR + MongoDB).
**Performance Goals**: Dashboard read returns < 3 s (single-run, warm) for a workspace of ≥ 200 active candidates and ≥ 1,000 booked scheduling requests across the window (SC-008), all aggregations index-backed.
**Constraints**: Strictly read-only (no mutation, no outbound communication — structurally verified, SC-011); no plaintext PII in logs (SC-009); workspace-scoped server-side, no cross-workspace oracle (FR-022); silence list bounded by a fixed cap (FR-010); window constrained to a fixed enum so a crafted unbounded window cannot exhaust resources (FR-015).
**Scale/Scope**: ~2 backend endpoints, 1 service, 1 Mongock changeset (2 indexes), ~2 new repository finders, 1 new audit event type, 1 Angular lazy feature (component + service + route). Single-instance topology (§IV).

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Gate | Question | Result |
|---|---|---|
| **C1** | Within MVP scope (spec §11)? | **PASS** — "Core dashboard — time-to-schedule, no-show rate, current silence list" is an explicit §11 MVP item (constitution Principle I) and the F50 backlog entry. Per-requisition / HM scoping and the deferred analytics (recruiter-hours, NPS) are explicitly out (FR-026). |
| **C2** | New service, queue, or replica? | **PASS** — on-read aggregation only; **no new collection**, no scheduler, no broker, no cache tier. Two indexes added to an existing collection. |
| **C3** | Exposes candidate PII to unauthorized roles? | **PASS** — read restricted to Admin/Recruiter/Read-only; export to Admin/Recruiter; Interviewer denied; Hiring Manager deferred-and-denied (no requisition link exists; FR-026). Velocity metrics are PII-free by construction (schedulingRequests carry no PII). Silence list surfaces name + id + duration only, never email/phone, under a fixed decrypt cap. |
| **C4** | Dependency outside the fixed stack? | **PASS** — zero new dependencies; reuses `CsvInjectionEscaper`, `SlaNudgeService`, Spring Data Mongo. |
| **C5** | New/modified Windows scripts with non-ASCII? | **PASS** — no new `.ps1/.cmd/.bat`. New Java (incl. the Mongock changeset) keeps comments ASCII; new sources scanned for NUL/non-ASCII at task close (the F30 lesson). |
| **C6** | Multi-role sub-agent review (≥ 3 roles) scheduled? | **PASS** — spec already reviewed by BA/QA/Security; a second ≥ 3-role review (Backend/Security/QA) runs at implementation task close (the established two-loop pattern). |
| **C7** | Downloads any build tool/runtime/CLI? | **PASS** — uses the cached gradle-9.4.0 binary + installed JDK/Node; no downloads. |

**No complexity-tracking violations.** No architectural pattern beyond the minimum is introduced.

## Project Structure

### Documentation (this feature)

```text
specs/024-core-dashboard/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/           # Phase 1 output
│   └── dashboard-api.md
└── tasks.md             # Phase 2 output (/speckit.tasks — NOT created here)
```

### Source Code (repository root)

```text
backend/
  src/main/java/com/cadence/
    api/
      DashboardController.java          # NEW — GET /api/internal/dashboard + /export
      DashboardDtos.java                # NEW — wire records (snapshot, metric, silence row, window enum)
      DashboardExceptionHandler.java    # NEW — @Order(HIGHEST_PRECEDENCE) no-oracle envelope
    service/
      DashboardService.java             # NEW — on-read metric computation + silence-list join + CSV render
      DashboardProperties.java          # NEW — @ConfigurationProperties("cadence.dashboard") (silence cap)
    domain/
      AuthEventType.java                # MODIFIED — add DASHBOARD_EXPORTED (append-only)
    repository/
      SchedulingRequestRepository.java  # MODIFIED — add 2 windowed finders (velocity + no-show)
    config/migration/
      ChangeUnit021_DashboardIndexes.java  # NEW — 2 indexes on schedulingRequests
  src/test/java/com/cadence/dashboard/
    DashboardItBase.java                # NEW — seed helpers (booked/no-show/silent fixtures), MutableClock
    DashboardMetricsIT.java             # NEW — median (even-N, HALF_UP 1dp), no-show rate; future-dated EXCLUDED
                                        #        from denominator; div-zero -> applicable=false; reschedule NOT
                                        #        double-counted; two requests same candidate -> sampleCount==2;
                                        #        candidate-exactly-at-threshold (SC-003, FR-003/005/007)
    DashboardSilenceListIT.java         # NEW — terminal/erased excluded; ORDER most-overdue-first; cap==100;
                                        #        decrypt-count <= cap (not just response length); on-read freshness:
                                        #        breach a candidate -> next read shows it (SC-004/SC-005, FR-010/012)
    DashboardContractTest.java          # NEW — 5-role matrix BOTH endpoints; Read-only POSITIVE read 200 +
                                        #        export 403; HM 403/403; bad-window 400; cross-workspace-id-ignored
                                        #        as a SEPARATE method (SC-007, FR-020/021/022/026)
    DashboardExportIT.java              # NEW — CSV shape; injection-neutralised cell; non-terminating rate (2/7
                                        #        -> "28.6%"); erased excluded; export rows == screen silence length;
                                        #        one DASHBOARD_EXPORTED audit (no names); no server-side file
                                        #        (SC-006/SC-012, FR-017/018/019/019a/019b)
    DashboardReadOnlyStructuralTest.java# NEW — constant-pool scan of DashboardService.class ONLY: no dispatch/
                                        #        calendar/AuthAuditService/mutation refs (SC-011)
    DashboardLogPiiScanTest.java        # NEW — TRACE render with name sentinel: absent in logs+audit, PRESENT
                                        #        in the CSV cell (the egress) (SC-009)
    DashboardIndexTest.java             # NEW — assert ChangeUnit021 indexes exist
    DashboardRestartIT.java             # NEW — figures identical after cold MongoTemplate reload (SC-010)
    DashboardPerfIT.java                # NEW (@Tag("perf")) — seed 200 candidates + 1000 booked requests; one
                                        #        warm-up read (discarded) then assert < 3s with CI-safe margin;
                                        #        also asserts index-backed via explain (SC-008)

frontend/
  src/app/features/admin/dashboard/
    dashboard.component.ts              # NEW — standalone, $localize, window selector + 3 panels + export
    dashboard.component.scss            # NEW
    dashboard.component.spec.ts         # NEW — Jasmine: window switch recompute, empty/N-A states,
                                        #        export-hidden-for-readonly, export click calls service with
                                        #        the SELECTED window (stale-window regression guard)
    dashboard.service.ts                # NEW — HttpClient against /api/internal/dashboard
  src/app/app.routes.ts                 # MODIFIED — lazy route behind roleGuard('ADMIN','RECRUITER','READ_ONLY')

.github/workflows/ci.yml                # MODIFIED — add F50 SENTINEL PII scan patterns
```

**Structure Decision**: Web application (Option 2). Backend follows the established `api/` + `service/` + `repository/` + `config/migration/` layout; the dashboard mirrors the F31 SLA-nudge internal-feature shape (controller + dtos + `@Order(HIGHEST_PRECEDENCE)` handler + service + `@ConfigurationProperties`). Frontend follows the F42 `features/admin/*` internal-screen shape (lazy standalone component + service + role-guarded route). No new top-level modules.

## Complexity Tracking

> No Constitution Check violations. Table intentionally empty.

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| — | — | — |
