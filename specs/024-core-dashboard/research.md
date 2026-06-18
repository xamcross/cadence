# Phase 0 Research: F50 Core Dashboard

All Technical Context items are resolved (no NEEDS CLARIFICATION). Each decision below is grounded in the existing codebase.

## D1 — Where the three metrics come from (no new write path)

**Decision**: Compute all figures **on read** from existing collections; introduce **no new collection** and **no precomputed metric store**.

- **Time-to-schedule**: `SchedulingRequest.sentAt` (set in `SchedulingService.initiate`) → `SchedulingRequest.bookedAt` (set in the `BOOKING→BOOKED` CAS in `SlotReservationService.book`). Sample = `bookedAt − sentAt` for `status == BOOKED` rows whose `bookedAt` falls in the window.
- **No-show rate**: denominator = `status == BOOKED` rows whose `bookedStartAt` is in the window **and** `≤ now` (elapsed); numerator = that subset with `noShowAt != null` (the F23 no-show stamp, already documented as "the MVP no-show signal for F50").
- **Silence list**: reuse `SlaNudgeService.silenceList(workspaceId)` → `List<CandidateSla>(candidateId, slaState, lastActivityAt, openDraftId)`.

**Rationale**: `schedulingRequests` already carries the exact instants needed and is PII-free (ids/instants/enums; `locationText` is the only encrypted field and is not read here), so the velocity metrics are PII-free by construction. Reusing `SlaNudgeService` keeps the SLA threshold single-sourced (FR-008/FR-011) so the dashboard and the nudge engine never disagree.

**Read-bounding (review SHOULD-FIX — SC-008)**: the metric reads are NOT unbounded full-document scans:
- **No-show numerator + denominator are Mongo `count` queries** (no documents loaded into memory).
- **Time-to-schedule median** materialises only a **projected two-field** read (`fields: {sentAt:1, bookedAt:1}` — never the full doc, which carries `offeredSlots`/encrypted `locationText`), capped by `DashboardProperties.medianSampleCap` (default 5000) as a DoS backstop (the F12 unbounded-read lesson). At MVP volumes the cap is never hit; if it ever is, `sampleCount` reflects the capped read and the median is flagged an honest bounded estimate.

**Alternatives considered**:
- *A precomputed `dashboardMetrics` collection refreshed by a `@Scheduled` job* — rejected: adds a collection + a scheduler + a staleness/erasure-lag window (an erased candidate could linger in a cached snapshot). On-read is simpler, always-fresh, and C2-cleaner. The MVP volume (single workspace, hundreds–low-thousands of requests) makes on-read aggregation well within the 3 s budget.
- *Mongo aggregation pipeline for the median* — rejected for the median specifically: `$group` has no portable exact-median operator pre-Mongo-7 `$median` with the tie-break we need; an in-memory sort of the (projected, capped) duration list is trivial and lets us pin the even-N rule (FR-001) deterministically.

## D2 — Counting unit and lineage (no double-count)

**Decision**: Count by **scheduling request in its final live state**, not by candidate.

- Time-to-schedule and the no-show denominator both filter `status == BOOKED`. A reschedule (F20) flips the old round to `RESCHEDULED`/`SUPERSEDED` and creates a new `BOOKED` round, so filtering on `BOOKED` yields exactly **one** live row per lineage — no double-count (FR-003). A cancelled interview is `CANCELLED` (excluded — it did not "happen", FR-002/FR-005).
- A request rescheduled so its `bookedStartAt`/`bookedAt` moves across the window boundary is attributed by its **final** instant (the live `BOOKED` row's value), satisfying the edge case.

**Rationale**: `BOOKED` is the single live-booking state in `SchedulingStatus` (`PENDING_SELECTION, BOOKING, BOOKED, EXPIRED, SUPERSEDED, CLEANUP_INCOMPLETE, CANCELLING, CANCELLED, RESCHEDULED`); selecting it gives final-state semantics for free without lineage walking.

## D3 — Indexes (the only schema change)

**Decision**: Add two compound indexes on the existing `schedulingRequests` via `ChangeUnit021_DashboardIndexes` (order `"021"`, off the highest applied `"020"`):

1. `{workspaceId: 1, status: 1, bookedAt: 1}` — backs the time-to-schedule window scan.
2. `{workspaceId: 1, status: 1, bookedStartAt: 1}` — backs the no-show window scan, workspace-leading (the existing F23 `{status, bookedStartAt}` index is global, not workspace-scoped, so a per-workspace dashboard scan needs the workspace-leading variant).

The silence scan reuses the **existing** `candidates {workspaceId, lastContactAt}` index (`ChangeUnit001`) via `SlaNudgeService` — no new candidate index.

**Rationale**: Satisfies the F00.1 rule "no scan without a covering index" and bounds SC-008. Native `createIndex` + targeted `dropIndex` rollback (never `dropIndexes()` — the F00 lesson). Order `"021"` is the next zero-padded order off the highest *applied* changeset, not the branch number `024` (the documented Mongock convention).

**Alternatives considered**: Reusing the global `{status, bookedStartAt}` and filtering workspace in-query — rejected: a workspace-leading index is materially better for the per-workspace range scan and keeps the no-show and velocity scans symmetric.

## D4 — Median definition (determinism for SC-003)

**Decision**: Median over the sorted list of `Duration` samples — odd N → middle element; even N → arithmetic mean of the two central elements; report in **hours to one decimal place**. Durations are absolute elapsed time (`Duration.between(sentAt, bookedAt)`), DST-immune.

**Rationale**: Pins SC-003 so a seeded even-N dataset has a single correct expected value. In-memory computation over the bounded booked-sample list.

## D5 — Silence-list identity and the name join

**Decision**: After `SlaNudgeService.silenceList(workspaceId)` returns the capped, classified candidate ids, batch-load their `Candidate` docs (one `findByWorkspaceIdAndIdIn(workspaceId, ids)` query — a new finder) and project **name + internal id + slaState + silence duration** only. Cap the list at `DashboardProperties.silenceListCap` (default 100), which is also the decrypt bound.

**Rationale**: `CandidateSla` returns ids only; the recruiter needs the name to act, and the decrypted name on this authenticated internal screen is the minimum-necessary identifier (the F21 preview / pipeline precedent). One batch read avoids an N+1. There is no non-PII human label on `Candidate` (no reference number), so name is the identifier; email/phone are never included and no field on the response DTO would let the surface widen.

**Confirmed against source (review)**: `SlaNudgeService.silenceList(workspaceId)` returns up to its `scanBatchLimit` (500) classified rows that are **unordered and ids-only** (`CandidateSla(candidateId, slaState, lastActivityAt, openDraftId)`); it already excludes GREEN/terminal/erased at the query source. Therefore the dashboard MUST itself, in order: (1) **sort** by `lastActivityAt` ascending (most-overdue first, FR-010), (2) **truncate** to `silenceListCap` (100), (3) **batch-decrypt names** via `findByWorkspaceIdAndIdIn` on the truncated ids **only** (≤ 100 decrypts — the bound `DashboardSilenceListIT` asserts, not just the response length). This sort+cap+decrypt-bound is dashboard-owned code with hard test coverage, because the SLA service guarantees none of it.

## D6 — Export: format, injection-safety, audit, no-persistence

**Decision**:
- `GET /api/internal/dashboard/export?window=...` returns `text/csv` with `Content-Disposition: attachment; filename="dashboard-<window>.csv"`, built in-memory and streamed in the response body (never written to a server-side file — FR-019a).
- Every candidate-derived cell (the silence-list name) passes through `CsvInjectionEscaper.escapeForSpreadsheet(...)` (F42's export-boundary sanitiser; F50 is its **first real caller**, so the export path gets its own unit test — FR-018).
- The same snapshot that feeds the screen feeds the export (FR-017), so the read-time exclusion of erased/terminal candidates (FR-009/FR-019) applies automatically.
- After producing the file, record one audit event: `AuthAuditService.record(AuthEventType.DASHBOARD_EXPORTED, workspaceId, memberId, "window=<W>;rows=<N>", null)` — ids/window/count only, no names (FR-019b/SC-012). The `sourceIp` arg is passed `null` (the dashboard has no security need for it; `AuthAuditService` HMAC-hashes the IP internally and is null-safe — the F23 system-event precedent passes `null`). The export uses the **same capped snapshot** as the screen, so its row count equals the screen's silence-list length for the same window (`DashboardExportIT` asserts this snapshot identity — it does NOT egress all 500 breached, only the top ≤ 100).

**Rationale**: Reuses the established file-download header pattern (`PublicBrandingController`) switched to `attachment`. `AuthAuditService.record` (workspace+member scoped) fits a workspace-level export better than the per-candidate `CandidateAuditService.append`. `DASHBOARD_EXPORTED` is a new append-only `AuthEventType` value (additive, no migration).

**Alternatives considered**: Per-candidate audit rows for each exported name — rejected: noisy, and the accountability requirement is "who exported, when, how much", which one workspace-level event captures.

## D7 — Endpoints, roles, window binding, no-oracle

**Decision**:
- Two endpoints under `/api/internal/dashboard` (so `RbacEndpointInventoryTest` covers them):
  - `GET /api/internal/dashboard?window=LAST_30_DAYS` → full snapshot (metrics + silence list). Class-level `@PreAuthorize("hasAnyRole('ADMIN','RECRUITER','READ_ONLY')")`.
  - `GET /api/internal/dashboard/export?window=...` → CSV. Method-level `@PreAuthorize("hasAnyRole('ADMIN','RECRUITER')")` (overrides the class rule for that method — Read-only denied export, FR-021).
- **Window** is bound as a `String` query param and resolved via `DashboardWindow.parse(...)` which throws a **dedicated `InvalidRequestException`** (mirroring the F41 ATS controller, which wraps the `valueOf` failure rather than letting a raw `IllegalArgumentException` propagate) for anything not in `{LAST_7_DAYS, LAST_30_DAYS, LAST_90_DAYS}` → the handler maps it to 400 `invalid_request`. (Binding directly as an enum path/param raises `MethodArgumentTypeMismatchException` → the catch-all 500 — the F41 lesson; parse-as-String avoids it.) Absent window defaults to `LAST_30_DAYS`. The `DashboardExceptionHandler` maps the dedicated exception to 400, not a broad `IllegalArgumentException` catch.
- Workspace is taken from `principal.workspaceId()` only; any client-supplied workspace id is ignored (FR-022). `DashboardExceptionHandler` is `@Order(HIGHEST_PRECEDENCE) @RestControllerAdvice(assignableTypes = DashboardController.class)` returning the value-free envelope, and its catch-all re-throws `AccessDeniedException`/`AuthenticationException` (the F31 lesson — else `@PreAuthorize` 403s become 500s). `Cache-Control: no-store` on both endpoints.

**Rationale**: Mirrors the F31 `SlaNudgeController`/`SlaNudgeExceptionHandler` exactly. Hiring Manager is absent from both role lists → denied by deny-by-default (FR-026); the contract test asserts HM-denied explicitly so the deferral is enforced, not just documented.

## D8 — Read-only guarantee (structural, SC-011)

**Decision**: `DashboardService` holds **no** reference to `EmailDispatchService`, `EmailSender`, any `CalendarProviderClient`/`CalendarEventService`, or any repository `save`/`insert`/`updateFirst`/`findAndModify` mutation. A `DashboardReadOnlyStructuralTest` does a constant-pool scan of `DashboardService.class` (the F31 `NoAutoSendStructuralTest` precedent) asserting absence of those types/method refs, plus a reflection check that the service exposes no write method.

**Rationale**: FR-024 must be verifiable, not merely asserted. The only writes in the whole feature are (a) the two index creations (Mongock, startup) and (b) the append-only audit event on export (via `AuthAuditService`, which is an append, not a candidate/scheduling mutation). The structural test scopes the no-mutation guarantee to the metric/silence computation path; the export-audit write lives on the controller calling `AuthAuditService` (allowed — it mutates no domain state).

**Test scoping (review NIT)**: `DashboardReadOnlyStructuralTest` scans **`DashboardService.class` specifically** (NOT `DashboardController` — the controller legitimately references `AuthAuditService` for the export audit, so scanning it would be vacuous). The scan asserts `DashboardService` has no field/constant-pool reference to `EmailDispatchService`, `EmailSender`, `CalendarEventService`/`CalendarProviderClient`, or `AuthAuditService` (the audit is the controller's job, not the service's), and no repository mutation method (`save`/`insert`/`updateFirst`/`findAndModify`).

## D9 — Frontend (internal screen, no §IX gate)

**Decision**: One lazy Angular standalone feature `features/admin/dashboard` (component + `dashboard.service.ts` + scss + spec), routed behind `roleGuard('ADMIN','RECRUITER','READ_ONLY')` chained after `authGuard` (the F42 `admin/csv-import` precedent). A window selector (3 fixed options) drives one `GET /api/internal/dashboard` call; three panels render the metrics + silence list; the export button is shown only when the user's role is Admin/Recruiter and triggers a download of `/export`. All strings `$localize`-marked. The window choice is held in component/session state (FR-014).

**Rationale**: Internal staff screen → the candidate-facing Lighthouse/WCAG CI gates do not apply (FR-027; the F50/F51 documented precedent). Jasmine covers window-switch recompute, the empty/N-A states, and export-hidden-for-Read-only.

## D10 — PII discipline & CI

**Decision**: Velocity metrics never touch PII. The silence-list name is never logged (the service logs only `kv("workspaceId", …)`/counts; enums logged as `.name()` — the F01.1 logstash enum footgun). `ci.yml` gains an `F50` SENTINEL block (candidate-name/email sentinels seeded into a dashboard test) so SC-009 is enforced, not vacuous. The `DashboardLogPiiScanTest` drives a TRACE-level render with a name sentinel and asserts absence across logs/response-where-not-expected.

**Rationale**: Every prior feature added a CI sentinel block; F50's only PII path (the name join + CSV) is the thing to scan.
