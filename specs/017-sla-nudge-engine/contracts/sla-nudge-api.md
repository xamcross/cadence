# Contract: SLA Nudge Engine API (F31)

All recruiter endpoints are internal: `@PreAuthorize("hasAnyRole('ADMIN','RECRUITER')")`, workspace-scoped via the authenticated principal, CSRF per the F02 internal chain (MockMvc `.with(csrf())`). Errors render through `SlaNudgeExceptionHandler` (`@RestControllerAdvice(assignableTypes=SlaNudgeController.class)`) as a value-free `{ "error": "..." }` envelope — byte-identical 404 across {unknown, malformed, cross-workspace, erased} (no existence oracle). **The handler MUST itself `@ExceptionHandler(ScopedNotFoundException)`** and render that same body: the global `RbacExceptionHandler` otherwise maps `ScopedNotFoundException` to `{"error":"not_found","message":"Not found."}` (WITH a message), which is byte-divergent and would leak existence (SC-016). HM / Interviewer / Read-only → 403 (`forbidden`, the RBAC envelope).

## A. Silence list (US2)

`GET /api/internal/sla/silence-list`

200 →
```json
{ "items": [
  { "candidateId": "65...", "slaState": "RED",   "lastActivityAt": "2026-06-08T09:00:00Z", "openDraftId": "66..." },
  { "candidateId": "65...", "slaState": "AMBER", "lastActivityAt": "2026-06-11T09:00:00Z", "openDraftId": null }
] }
```
- Workspace-scoped; lists AMBER + RED candidates (GREEN omitted). **Backed by a range read on `lastContactAt < amberCutoff`** (the wider window — AMBER rows are not past `breachCutoff`, so the drafting query would miss them), then classified RED/AMBER in Java under the injected `Clock` in the workspace zone (D5). `openDraftId` non-null iff an OPEN draft exists (joined from `slaNudgeDrafts {workspaceId,status:OPEN}`). No candidate PII.
- Cache-Control: `no-store`.

## B. Per-candidate SLA (US2)

`GET /api/internal/candidates/{candidateId}/sla`

200 → `{ "candidateId": "65...", "slaState": "RED", "lastActivityAt": "...", "openDraftId": "66..." }`
- Cross-workspace / unknown id → **404 `not_found`** (scoped, indistinguishable). Erased candidate → 404 (excluded from silence; FR-008).

## C. Draft preview (US3, FR-013)

`GET /api/internal/candidates/{candidateId}/sla/draft/preview`

200 →
```json
{ "messageType": "SLA_HOLDING",
  "subject": "We're still working on your application",
  "body": "Hi Dana, ... track your status here: https://app.example.com/status?token=...",
  "missingFields": [] }
```
- Renders the `SLA_HOLDING` template with the candidate's merge fields via the F21 `EmailTemplateService` preview path (decrypts name; resolves `{{status_link}}` via `CandidateStatusService.statusLinkFor`; surfaces `[[missing:...]]`/`missingFields` for any absent field — FR-012). **`Cache-Control: no-store`** (PII read). Never logged (FR-024). Requires an OPEN draft for the candidate (else 404). Cross-workspace → 404.

## D. Approve a draft (US3, FR-015/FR-016/FR-022/FR-023)

`POST /api/internal/sla/drafts/{draftId}/approve`

200 → `{ "draftId": "66...", "result": "SENT_ENQUEUED" }` (or `"ALREADY_ACTIONED"` for a concurrent/duplicate approve; an erased candidate → 404). `result` is **never** `REFUSED_AT_SEND` — the send-time gate refusal lands on the `emailDispatches` row asynchronously, not this response.
- CAS `{_id, status:OPEN} → APPROVED` (the **primary** single-winner guard; a concurrent loser is `matchedCount==0` → idempotent `{ "result": "ALREADY_ACTIONED" }`, no second send). The F22 dispatch idempotency key is a secondary backstop.
- Advances `lastContactAt` (clears breach), then — **in one try/catch** — resolves `status_link` via `CandidateStatusService.statusLinkFor` and `EmailDispatchService.enqueue(ws, candidateId, SLA_HOLDING, "BASE", now, {status_link, expected_date}, candidateId)` → `result: "SENT_ENQUEUED"`.
- **The send-time gate is authoritative but ASYNCHRONOUS** — `ContactPermissionGate` re-evaluates inside `EmailDispatchService.dispatch` *after* the dispatch claim (not at `enqueue`), so a candidate who became ineligible (withdrawn/over-retention/undeliverable) **after** the draft is **REFUSED on the `emailDispatches` row at dispatch time** (no message leaves; FR-023) — NOT a synchronous approve outcome. The approve response is `SENT_ENQUEUED` regardless; the suppression is observable on the dispatch row, not the approve body. (There is therefore **no `REFUSED_AT_SEND` result value** — it would be a false synchronous promise.)
- A candidate **erased** since the draft was created makes `statusLinkFor` throw `ScopedNotFoundException` *before* `enqueue` → the controller renders the indistinguishable **404** (treated identically to an unknown candidate, no oracle); **no message leaves**. Audited `SLA_DRAFT_APPROVED` on a successful approve.
- Cross-workspace draftId → 404. 5-role: ADMIN/RECRUITER allowed; others 403.

## E. Dismiss a draft (US3, FR-016/FR-017)

`POST /api/internal/sla/drafts/{draftId}/dismiss`

200 → `{ "draftId": "66...", "result": "DISMISSED" }`
- CAS `{_id, status:OPEN} → DISMISSED`; sends nothing; audited `SLA_DRAFT_DISMISSED`. Not-OPEN → idempotent `ALREADY_ACTIONED`. Cross-workspace → 404. A dismissed candidate may be re-drafted on a future breach.

## F. SLA window setting (US1 — reuse, no new endpoint)

Set via the **existing** workspace settings endpoint (F03): `PATCH /api/internal/workspace/settings` with `{ "slaSilenceWindowDays": 5 }`. Admin-only, validated 1–30 (`validateSla`), audited. F31 adds no endpoint here; the scan/classifier consume the value, defaulting to the global default when unset (FR-002).

## G. Internal contract: `advanceLastContact` (FR-005, SC-014)

`CandidateActivityService.advanceLastContact(workspaceId, candidateId, Instant now)` — `updateFirst({_id, workspaceId, erasureState:ACTIVE}, $set lastContactAt=now)`. Called at the five qualifying sites (data-model §3). Value-free; guarded on ACTIVE; idempotent. **No candidate-originated path calls it** (FR-005).

## H. Internal contract: scan (no-auto-send, FR-010/SC-008)

`SlaNudgeScheduler.sweep()` (checkpoint `"sla-nudge-scan"`) → iterate `workspaceConfigRepository.findAll()` (skip unconfigured) → per workspace a **paginated** index-backed range read `findByWorkspaceIdAndErasureStateAndLastContactAtBefore(ws, ACTIVE, breachCutoff, PageRequest.of(0, scanBatchLimit))` (new overload; the 3-arg `RetentionService` method is untouched) → gate + terminal filter → `repo.insert(OPEN draft)` (DuplicateKey = no-op) → `RecruiterNotificationService.notify(SLA_DRAFT_PENDING)`. **The scheduler/scan has no reference to `EmailDispatchService`** — it never sends. SC-008 asserts (call-graph) the only `enqueue(...SLA_HOLDING...)` caller is `SlaNudgeService.approve`.

## Status-code matrix

| Case | Status | Body |
|---|---|---|
| Recruiter/Admin list/read/preview/approve/dismiss | 200 | as above (preview/list `no-store`) |
| Unknown / malformed / cross-workspace / erased candidate or draft (view paths) | 404 | `{"error":"not_found"}` (indistinguishable) |
| HM / Interviewer / Read-only | 403 | `{"error":"forbidden"}` (RBAC envelope) |
| Rate-limited (if applied to a candidate-facing path — N/A here; all internal/authenticated) | 429 | `{"error":"rate_limited"}` |
| Concurrent/duplicate approve or dismiss | 200 | `{"result":"ALREADY_ACTIONED"}` (idempotent) |

## Test surface (maps to SC)

- **SC-001** seeded green/amber/red via `lastContactAt` bands → silence-list + per-candidate states.
- **SC-002** window persists + non-Admin refused (existing settings endpoint).
- **SC-003** repeated/overlapping `sweep()` → exactly one OPEN draft + one `SLA_DRAFT_PENDING` (unique partial index).
- **SC-004** approve → exactly one `emailDispatches` row enqueued + breach clears; dismiss → zero.
- **SC-005** erased / no-basis / withdrawn / over-retention / undeliverable → no draft (each state).
- **SC-006** mid-scan restart + replay → no duplicate draft/dispatch.
- **SC-007** `SENTINELF31*` in name/status-link → absent from logs, dead-letter, audit, draft doc.
- **SC-008** call-graph: no scheduler/scan path enqueues `SLA_HOLDING`.
- **SC-009** boundary + DST determinism under `MutableClock` in the workspace zone.
- **SC-010** gated concurrent approve → ≤1 dispatch.
- **SC-011** approve/dismiss + window change audited.
- **SC-012** terminal-outcome candidate not drafted.
- **SC-013** index-backed **paginated** range scan (query-plan assertion on the new `(…, Pageable)` overload) + 1,000-candidate sweep within bound.
- **SC-014** each of the five sites advances `lastContactAt` (parameterized) — incl. site 5 (`approve`-synchronous advance) asserted **independently** (advance happens even when the subsequent dispatch is REFUSED/no-send), so the re-draft-window closure is non-circular.
- **SC-015** erasure invalidates open draft + send-time gate refuses.
- **SC-016** cross-workspace draftId → indistinguishable 404; preview `no-store`.
