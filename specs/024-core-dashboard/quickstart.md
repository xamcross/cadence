# Quickstart: F50 Core Dashboard

How to run, exercise, and verify the Core Dashboard locally.

## Prerequisites

- Docker running (Testcontainers `mongo:7` for tests; `docker run` MongoDB for manual dev).
- `JAVA_HOME=C:/jdk-24.0.1`; cached gradle-9.4.0 binary; `-Dapi.version=1.41`; `DOCKER_HOST=npipe:////./pipe/docker_engine` (the documented local run flags). No tool downloads (Principle X / C7).

## Build & test (backend)

```powershell
# From backend/ — full suite (singleton mongo:7 container)
..\gradlew.bat test -Dapi.version=1.41

# F50 only
..\gradlew.bat test --tests "com.cadence.dashboard.*" -Dapi.version=1.41
```

> The first multi-class Testcontainers run after a recompile may throw the one-time `GenericContainer` class-init error — re-run.

Key F50 tests:
- `DashboardMetricsIT` — median (even-N, HALF_UP 1dp), no-show rate, future-dated excluded from denominator, div-zero→N-A, reschedule-not-double-counted, two-requests-one-candidate→sampleCount==2, threshold candidate (SC-003, FR-003/005/007).
- `DashboardSilenceListIT` — terminal/erased excluded, most-overdue-first ordering, cap==100, decrypt-count≤cap, on-read freshness (SC-004/SC-005).
- `DashboardContractTest` — 5-role matrix both endpoints, Read-only positive-read + export-403, HM 403/403, bad-window 400, cross-workspace-ignored separate method (SC-007).
- `DashboardExportIT` — CSV shape, injection-neutralised cell, non-terminating rate (2/7→"28.6%"), erased excluded, export-rows==screen-length, one `DASHBOARD_EXPORTED` audit (no names), no server-side file (SC-006/SC-012).
- `DashboardReadOnlyStructuralTest` — constant-pool scan of `DashboardService` only: no dispatch/calendar/audit/mutation refs (SC-011).
- `DashboardLogPiiScanTest` — TRACE render: name sentinel absent in logs+audit, present in the CSV cell (SC-009).
- `DashboardIndexTest` — `ChangeUnit021` indexes present.
- `DashboardRestartIT` — figures identical after cold `MongoTemplate` reload (SC-010).
- `DashboardPerfIT` (`@Tag("perf")`) — 200 candidates + 1000 booked requests, warm-up then <3s + index-backed via explain (SC-008).

## Build & test (frontend)

```powershell
# From frontend/
ng test --watch=false
ng build --configuration production
```

Jasmine `dashboard.component.spec.ts` covers: window switch recomputes, empty/N-A states render, export control hidden for Read-only.

## Manual end-to-end (the §II demonstrable leg)

1. Start backend (`gradlew bootRun`, local Mongo) + frontend (`ng serve`).
2. Sign in as an **Admin** or **Recruiter**.
3. Navigate to **`/admin/dashboard`**.
4. Seed/observe:
   - With booked + past interviews in the workspace → median time-to-schedule and no-show rate render for the default 30-day window; change to 7 / 90 days → both recompute; the choice persists across navigation in-session.
   - With candidates past their SLA window → the silence list shows them (red/amber), most-overdue first, names only (no email/phone).
   - An empty workspace → "no data for this window" + "no interviews yet" + empty silence list (no errors/zeros).
5. Click **Export CSV** → a `dashboard-<window>.csv` downloads matching the screen; open in a spreadsheet → a name beginning `=`/`+`/`-`/`@` is inert text.
6. Sign in as **Read-only** → dashboard visible, **no** export control; a direct `GET /api/internal/dashboard/export` → 403.
7. Sign in as **Interviewer** or **Hiring Manager** → `/admin/dashboard` redirects to `/not-authorized`; direct `GET /api/internal/dashboard` → 403.

## Acceptance → test mapping

| Success Criterion | Verified by |
|---|---|
| SC-001 single-view metrics | `DashboardMetricsIT` + manual step 4 |
| SC-002 window recompute + persist | `dashboard.component.spec.ts` + manual step 4 |
| SC-003 metric math (median/no-show) | `DashboardMetricsIT` (even-N + threshold seeds) |
| SC-004 silence-list membership | `DashboardSilenceListIT` |
| SC-005 breach within one refresh | `DashboardSilenceListIT` (classify parity with `SlaNudgeService`) |
| SC-006 CSV injection-safe + erased excluded | `DashboardExportIT` |
| SC-007 role matrix + workspace scope | `DashboardContractTest` |
| SC-008 < 3 s @ 200 candidates / 1000 requests | `DashboardPerfIT` (`@Tag("perf")`, warm-up + explain) |
| SC-009 zero PII in logs | `DashboardLogPiiScanTest` + CI `ci.yml` F50 sentinel scan |
| SC-010 survives restart | `DashboardRestartIT` |
| SC-011 structurally read-only | `DashboardReadOnlyStructuralTest` |
| SC-012 export audited, not persisted | `DashboardExportIT` |

## Deploy

Backend change + new Mongock changeset (`ChangeUnit021`) → `scripts\db-migrate.ps1` then `scripts\deploy-backend.ps1` (Mongock applies the indexes on startup). Frontend change → `scripts\deploy-frontend.ps1`. Full release → `scripts\deploy-all.ps1`.
