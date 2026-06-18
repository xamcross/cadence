# API Contract: F50 Core Dashboard

Two internal endpoints under `/api/internal/dashboard`. Both are workspace-scoped from the authenticated session principal (`SessionService.Principal.workspaceId()`); a client-supplied workspace identifier is ignored. Both set `Cache-Control: no-store`. Errors use a value-free envelope from `DashboardExceptionHandler` (`@Order(HIGHEST_PRECEDENCE) @RestControllerAdvice(assignableTypes = DashboardController.class)`).

Roles: **Admin, Recruiter, Read-only** may read; **Admin, Recruiter** may export. **Interviewer denied. Hiring Manager denied** (deferred to F51 — FR-026). Denials return the standard authorization-denied envelope (403).

---

## 1. `GET /api/internal/dashboard`

Read the dashboard snapshot for a window.

**Auth**: `@PreAuthorize("hasAnyRole('ADMIN','RECRUITER','READ_ONLY')")` (class-level).

**Query params**:

| Param | Required | Values | Default |
|---|---|---|---|
| `window` | no | `LAST_7_DAYS` \| `LAST_30_DAYS` \| `LAST_90_DAYS` | `LAST_30_DAYS` |

**200 OK** — `application/json`:

```json
{
  "window": "LAST_30_DAYS",
  "generatedAt": "2026-06-18T10:15:00Z",
  "timeToSchedule": {
    "hasData": true,
    "medianHours": 18.5,
    "sampleCount": 12
  },
  "noShow": {
    "applicable": true,
    "rate": 0.2,
    "noShowCount": 2,
    "qualifyingCount": 10
  },
  "silenceList": [
    { "candidateId": "665...a1", "candidateName": "Jordan Lee", "severity": "RED", "daysSilent": 9 },
    { "candidateId": "665...b2", "candidateName": "Sam Okafor", "severity": "AMBER", "daysSilent": 4 }
  ]
}
```

**Empty / N-A states** (still 200):

```json
{
  "window": "LAST_7_DAYS",
  "generatedAt": "2026-06-18T10:15:00Z",
  "timeToSchedule": { "hasData": false, "medianHours": null, "sampleCount": 0 },
  "noShow": { "applicable": false, "rate": null, "noShowCount": 0, "qualifyingCount": 0 },
  "silenceList": []
}
```

**Responses**:

| Status | Body | When |
|---|---|---|
| 200 | snapshot | success (incl. empty/N-A states) |
| 400 | `{"error":"invalid_request"}` | `window` not one of the three values |
| 401 | (auth entry point) | no/invalid session |
| 403 | `{"error":"forbidden",...}` | Interviewer or Hiring Manager |

**Contract guarantees**:
- `silenceList` length ≤ `cadence.dashboard.silence-list-cap` (default 100), ordered most-overdue first (`lastActivityAt` ascending). `daysSilent = Duration.between(lastActivityAt, now).toDays()` (whole days, truncating). In the example above, with a 5-day SLA window and 1-day amber margin, `daysSilent: 9` is RED (past the 5-day window) and `daysSilent: 4` is AMBER (within the amber band) — self-consistent.
- `silenceList[*]` carries **no** email/phone field.
- `medianHours` is HALF_UP to one decimal place.
- `noShow.rate` is the raw `double` `noShowCount/qualifyingCount`; `noShow.applicable=false` whenever `qualifyingCount==0` (never `rate:0` from a zero denominator).
- `timeToSchedule.hasData=false` whenever `sampleCount==0`.
- Only the caller's workspace contributes; no field reveals another workspace's existence.

---

## 2. `GET /api/internal/dashboard/export`

Download the same snapshot as CSV.

**Auth**: `@PreAuthorize("hasAnyRole('ADMIN','RECRUITER')")` (method-level — overrides the class rule; Read-only denied).

**Query params**: same `window` as endpoint 1.

**200 OK** — `text/csv`, header `Content-Disposition: attachment; filename="dashboard-LAST_30_DAYS.csv"`. Body (illustrative):

```csv
Section,Metric,Value,Detail
Summary,Window,LAST_30_DAYS,
Summary,Generated,2026-06-18T10:15:00Z,
Time to schedule,Median hours,18.5,samples=12
No-show,Rate,20.0%,2 of 10

Silence list,Candidate,Jordan Lee,RED; 9 days silent
Silence list,Candidate,Sam Okafor,AMBER; 4 days silent
```

- The no-show percentage is `rate*100` HALF_UP to one decimal with `%` (e.g. `2/7 → "28.6%"`); median is HALF_UP to one decimal hours — pinned for the `DashboardExportIT` string assertion.
- Every candidate-derived cell (the name) is passed through `CsvInjectionEscaper.escapeForSpreadsheet(...)` — a leading `= + - @` / tab / CR is neutralised to literal text (FR-018/SC-006).
- Erased / terminal candidates are absent (read-time exclusion — FR-019).
- The file is built in-memory and never persisted server-side (FR-019a).
- On success, exactly one audit event `DASHBOARD_EXPORTED` is recorded (`window`, `rowCount`, actor, workspace — **no names**) (FR-019b/SC-012).

**Responses**:

| Status | Body | When |
|---|---|---|
| 200 | CSV attachment | success |
| 400 | `{"error":"invalid_request"}` | bad `window` |
| 401 | (auth entry point) | no/invalid session |
| 403 | `{"error":"forbidden",...}` | Read-only, Interviewer, or Hiring Manager |

---

## Role matrix (asserted by `DashboardContractTest`)

| Role | `GET /dashboard` | `GET /dashboard/export` |
|---|---|---|
| Admin | 200 | 200 |
| Recruiter | 200 | 200 |
| Read-only | 200 | **403** |
| Hiring Manager | **403** | **403** |
| Interviewer | **403** | **403** |

Plus: a request from any allowed role carrying a different workspace's id in any client-controllable position returns only the caller's workspace data (no cross-workspace leak / no oracle). Both endpoints are registered in `RbacEndpointInventoryTest` (deny-by-default build gate).

---

## Frontend contract (consumer)

`dashboard.service.ts` calls `GET {apiBaseUrl}/internal/dashboard?window=<W>` for the snapshot and navigates/downloads `GET {apiBaseUrl}/internal/dashboard/export?window=<W>` for CSV. The component:
- renders the three panels + empty/N-A states from the snapshot,
- shows the export control only for Admin/Recruiter (mirrors the backend 403),
- holds the selected `window` in session/component state (FR-014),
- marks all strings with `$localize`.
