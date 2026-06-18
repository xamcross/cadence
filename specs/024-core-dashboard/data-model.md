# Phase 1 Data Model: F50 Core Dashboard

**No new persisted collection.** The dashboard computes everything on read. This document describes (a) the existing documents it reads, (b) the one additive enum value, (c) the two new indexes, and (d) the transient computed shapes (DTOs / records — not stored).

---

## A. Existing collections READ (no schema change)

### `schedulingRequests` (F13/F20/F23) — the metrics source (PII-free)

Fields consumed (all already present):

| Field | Type | Use |
|---|---|---|
| `workspaceId` | String | scope filter |
| `status` | `SchedulingStatus` enum | `BOOKED` = live booking; the metric filter |
| `sentAt` | Instant | time-to-schedule start |
| `bookedAt` | Instant | time-to-schedule end + velocity window anchor |
| `bookedStartAt` | Instant | no-show window anchor (interview start) |
| `noShowAt` | Instant (nullable) | no-show numerator signal (present ⇒ no-show) |

`SchedulingStatus` values: `PENDING_SELECTION, BOOKING, BOOKED, EXPIRED, SUPERSEDED, CLEANUP_INCOMPLETE, CANCELLING, CANCELLED, RESCHEDULED`. The dashboard filters `status == BOOKED` for both metrics (final live state — D2).

> No PII is read from this collection. `candidateId`/`locationText` are **not** read by the metrics path.

### `candidates` (F04) — the silence-list identity (PII path)

| Field | Type | Use |
|---|---|---|
| `workspaceId` | String | scope filter |
| `id` | String | silence-list row id + batch-load key |
| `name` | String (encrypted at rest) | silence-list display name (decrypted under cap; never logged) |
| `lastContactAt` | Instant (nullable) | SLA basis (read via `SlaNudgeService`) |
| `createdAt` | Instant | SLA fallback basis |
| `statusOutcome` | `CandidateStatusOutcome` (`IN_PROGRESS, COMPLETE_OFFER, COMPLETE_REJECTED`) | terminal exclusion |
| `erasureState` | `ErasureState` (`ACTIVE, ERASED`) | erased exclusion |

The classification (basis, breach/amber/red, terminal & erased exclusion) is performed by the **existing** `SlaNudgeService` — the dashboard does not re-implement it. The dashboard only joins the **name** for the capped, classified ids.

### `workspaceConfig` (F03) — read transitively

`slaSilenceWindowDays` is read by `SlaNudgeService` (not directly by the dashboard) to define the breach threshold.

---

## B. Additive enum value (append-only)

### `AuthEventType` — add one value

```
DASHBOARD_EXPORTED
```

Recorded via `AuthAuditService.record(DASHBOARD_EXPORTED, workspaceId, memberId, "window=<W>;rows=<N>", sourceIp)` on every CSV export. Append-only; no migration. Payload carries window + row count only — **no candidate names** (FR-019b/SC-012).

---

## C. New indexes — `ChangeUnit021_DashboardIndexes` (order `"021"`)

On the existing `schedulingRequests` collection:

| Index | Purpose |
|---|---|
| `{ workspaceId: 1, status: 1, bookedAt: 1 }` | time-to-schedule window scan (workspace + `BOOKED` + `bookedAt` range) |
| `{ workspaceId: 1, status: 1, bookedStartAt: 1 }` | no-show window scan (workspace + `BOOKED` + `bookedStartAt` range) |

Created with native `createIndex`; rollback drops each with targeted `dropIndex(new Document(...))` (never `dropIndexes()`). No index added to `candidates` — the silence scan reuses the existing `{workspaceId, lastContactAt}` (`ChangeUnit001`).

---

## D. Transient computed shapes (DTOs / records — NOT persisted)

### `DashboardWindow` (enum)

```
LAST_7_DAYS, LAST_30_DAYS, LAST_90_DAYS
```

`parse(String)` → enum; unknown ⇒ `IllegalArgumentException` (→ 400). `null` ⇒ default `LAST_30_DAYS`. Resolves to a `windowStart = now − {7|30|90} days` (absolute `Duration`).

### `DashboardSnapshot` (response record)

```
DashboardSnapshot(
  DashboardWindow window,
  Instant generatedAt,
  TimeToScheduleMetric timeToSchedule,
  NoShowMetric noShow,
  List<SilenceRow> silenceList
)
```

### `TimeToScheduleMetric`

```
TimeToScheduleMetric(
  boolean hasData,            // false ⇒ "no data for this window" empty state (FR-002/edge)
  Double medianHours,         // one-decimal; null when !hasData
  int sampleCount             // number of booked requests contributing
)
```

### `NoShowMetric`

```
NoShowMetric(
  boolean applicable,         // false ⇒ "not applicable / no interviews yet" (FR-007)
  Double rate,                // 0.0–1.0; null when !applicable
  int noShowCount,            // numerator
  int qualifyingCount         // denominator (past, in-window, BOOKED)
)
```

### `SilenceRow`

```
SilenceRow(
  String candidateId,         // internal id only
  String candidateName,       // decrypted; minimum-necessary identifier (never email/phone)
  String severity,            // "RED" | "AMBER" (SlaState.name())
  long daysSilent             // = Duration.between(lastActivityAt, now(clock)).toDays()
)
```

- `daysSilent` is **`Duration.between(lastActivityAt, Instant.now(clock)).toDays()`** — absolute elapsed whole days (truncating floor), DST-immune (the F31 `Duration.ofDays` precedent). Not rounded.
- **Ordering**: the dashboard sorts the classified candidates **`lastActivityAt` ascending (most-overdue first)** and then truncates to `silenceListCap` (FR-010). `SlaNudgeService.silenceList(...)` returns up to its `scanBatchLimit` (500) **unordered, ids-only** rows — so the sort, the cap, and the name-decrypt are all the dashboard's responsibility (see §F).
- Bounded by `DashboardProperties.silenceListCap` (default 100). Email/phone are absent from the record by construction (FR-012).

### `DashboardProperties` (`@ConfigurationProperties("cadence.dashboard")`)

| Property | Default | Meaning |
|---|---|---|
| `silenceListCap` | 100 | max silence rows returned + name-decrypt bound (FR-010) |

### Rounding (pinned for SC-003 / SC-006 determinism)

- `medianHours`: `Duration.between(sentAt, bookedAt)` in hours, **HALF_UP to one decimal place**.
- `noShow.rate` (JSON): raw `double` in `[0,1]` = `noShowCount / qualifyingCount` (no rounding in JSON — the consumer formats).
- No-show **percentage in the CSV**: `rate * 100` formatted **HALF_UP to one decimal place** with a `%` suffix (e.g. `2/7 → "28.6%"`, `2/10 → "20.0%"`). A non-terminating ratio (2/7) is seeded in `DashboardExportIT`.

### `Clock` injection (determinism — REQUIRED)

`DashboardService` MUST inject `java.time.Clock` and derive `now = Instant.now(clock)` for the window start, the `bookedStartAt ≤ now` past check, and `daysSilent`. It MUST NOT call `Instant.now()` directly (else every windowed IT is non-deterministic). Tests override with `MutableClock` (the F01 `@Primary MutableClock` pattern).

---

## E. Validation & state rules (no state machine — read-only)

- **Window**: must be one of the three enum values; else 400 (D7).
- **No-show denominator zero** ⇒ `applicable=false` (never 0% / never divide-by-zero) (FR-007).
- **Time-to-schedule zero samples** ⇒ `hasData=false` (FR-002 empty state).
- **Silence exclusions** applied at the query source by `SlaNudgeService` (erased + terminal never enter the snapshot) (FR-009/FR-019).
- **No mutation**: the feature performs no write to any domain document; the only writes are the two index creations (startup) and the append-only export audit event (SC-011).

---

## F. Required repository additions & read-bounding

### New finders

- **`CandidateRepository.findByWorkspaceIdAndIdIn(String workspaceId, Collection<String> ids)`** → `List<Candidate>` — the single batch name-load for the **capped** (≤ `silenceListCap`) silence ids. Backed by the `_id` index — no new index. MUST be called only on the truncated id set (≤ 100), never the full 500 (the decrypt bound, asserted by `DashboardSilenceListIT`).
- **`SchedulingRequestRepository`** velocity + no-show reads (explicit `@Query`, projected, bounded):
  - **No-show counts use Mongo `count`, not document loads**:
    - `countQualifying`: `count({workspaceId, status:'BOOKED', bookedStartAt:{$gt: windowStart, $lte: now}})` → denominator.
    - `countNoShows`: `count({workspaceId, status:'BOOKED', bookedStartAt:{$gt: windowStart, $lte: now}, noShowAt:{$ne:null}})` → numerator.
  - **Time-to-schedule median uses a projected, capped read**: a `@Query(value="{...}", fields="{ 'sentAt':1, 'bookedAt':1 }")` returning only the two instants (never the full document — `offeredSlots`/encrypted `locationText` are not loaded), `{workspaceId, status:'BOOKED', bookedAt:{$gt: windowStart, $lte: now}}`, with an explicit `Pageable` cap (`medianSampleCap`, default 5000). If the cap is hit, `sampleCount` reflects the capped count and the result is flagged so the median is an honest bounded estimate (logged, value-free). For MVP volumes the cap is never hit; it is a DoS backstop (the F12 unbounded-read lesson).

### `DashboardProperties` (updated)

| Property | Default | Meaning |
|---|---|---|
| `silenceListCap` | 100 | max silence rows + name-decrypt bound (FR-010) |
| `medianSampleCap` | 5000 | max booked rows materialised for the median (DoS backstop) |
