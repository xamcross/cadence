# API Contracts: Join / Express-Interest Request Form (F70)

**Feature**: 029-join-interest-form | **Date**: 2026-06-23

All public endpoints ride the `@Order(2)` `securityMatcher("/api/public/**", ...)` permitAll/STATELESS/CSRF-exempt chain. All internal endpoints ride the main authenticated chain (`@Order(4)`), carry class-level `@PreAuthorize("hasRole('ADMIN')")`, and are workspace-scoped from the session principal. A scoped `@Order(HIGHEST_PRECEDENCE) @RestControllerAdvice` (the `FeedbackExceptionHandler` precedent) renders byte-identical error envelopes for these controllers and re-throws `AccessDeniedException`/`AuthenticationException` from any catch-all.

---

## Public — submit interest

### `POST /api/public/interest`

Unauthenticated. Rate-limited per hashed IP + per-workspace ceiling (R6).

Request body:
```json
{
  "name": "Dana Lee",
  "email": "dana@example.com",
  "organization": "Acme Talent",        // optional, ≤ 200
  "message": "We hire ~20 eng/quarter.", // optional, ≤ 2000
  "website": ""                           // honeypot — MUST be empty
}
```

Validation (FR-003): `name` required ≤ 200; `email` required, valid format, ≤ 254; `organization` ≤ 200; `message` ≤ 2000.

Responses:
| Status | Body | When |
|---|---|---|
| `202 Accepted` | `{"status":"received"}` | Any valid submission — **byte-identical body, status, and headers** whether the email is an active member, has a pending invitation, matches an existing open request (coalesced), or is unknown (FR-005/SC-005). The handler performs **no member/invitation existence check** (those cases are indistinguishable by construction); the only branch is dedup insert-vs-coalesce, both returning this identical response, with the `notify` side effect deferred off the response path — so the response is **structurally constant-time** (a structural guarantee, NOT a wall-clock-asserted one — R8). Honeypot-tripped / bot-heuristic-failed submissions ALSO return this exact 202 with no row written (no oracle). |
| `400 Bad Request` | `{"error":"invalid_request"}` | Field validation failure (format/length/required). Same envelope regardless of which field; never echoes other stored data. |
| `429 Too Many Requests` | `{"error":"rate_limited"}` | Per-source (best-effort) cap or the durable per-workspace DB-count ceiling exceeded (R6). |

No response ever reveals account/request existence. The owning `workspaceId` is resolved server-side from `cadence.interest.default-workspace-id` (FR-019); it is NOT in the request. The per-source key is the **real client IP** (`CF-Connecting-IP` / validated `X-Forwarded-For`), hashed — `getRemoteAddr()` alone collapses to the proxy edge IP (R6).

---

## Internal — admin review queue (Admin only)

Base: `/api/internal/interest-requests`, `@PreAuthorize("hasRole('ADMIN')")`, scoped to the caller's workspace.

### `GET /api/internal/interest-requests?status=open|reviewed|invited|dismissed|all`

`200`:
```json
{
  "requests": [
    {
      "id": "66f...",
      "name": "Dana Lee",
      "email": "dana@example.com",
      "emailUnverified": true,
      "organization": "Acme Talent",
      "organizationUnverified": true,
      "message": "We hire ~20 eng/quarter.",
      "status": "NEW",
      "submittedAt": "2026-06-23T09:12:00Z"
    }
  ]
}
```
`emailUnverified`/`organizationUnverified` are constant `true` flags so the UI labels submitter-claimed data (Assumptions / US2 Sc.1). `Cache-Control: no-store`.

### `POST /api/internal/interest-requests/{id}/review`
Guarded CAS `{status: NEW} -> REVIEWED`. `200 {"status":"REVIEWED"}`; `409 {"error":"conflict"}` if not in `NEW`; `404 {"error":"not_found"}` if absent/other-workspace (scoped, byte-identical).

### `POST /api/internal/interest-requests/{id}/dismiss`
Guarded CAS `{status in NEW,REVIEWED} -> DISMISSED` (+`$unset openEmailHash`). `200 {"status":"DISMISSED"}`; `409`; `404`.

### `POST /api/internal/interest-requests/{id}/invite`
Body `{"role":"RECRUITER"}` (any assignable role). Flow:
1. Guarded CAS claim `{status in NEW,REVIEWED} -> INVITED` (single-winner; loser → `409`).
2. Call `InvitationService.create(sessionWorkspaceId, sessionMemberId, request.email, role, ip)`.
   - Success → set `invitationId`, `$unset openEmailHash`. `200 {"status":"INVITED","invitationId":"..."}`.
   - `AlreadyMemberException` → request stays terminal (treat as resolved), `200 {"status":"INVITED","alreadyMember":true}` (FR-015 — clear admin outcome, no second access path, no 500, no public leak). The CAS already consumed the open state so no duplicate path.
3. `404` if absent/other-workspace; `409` if already terminal.

Role-from-session for workspace + actor (FR-014); never from submitter input.

### `POST /api/internal/interest-requests/{id}/erase`
Admin erasure (FR-022): `$set` PII fields `"[ERASED]"`, `$unset emailHash`+`openEmailHash`. `200 {"status":"erased"}`; `404` scoped. Idempotent.

### `GET /api/internal/interest-requests/export?status=open|reviewed|invited|dismissed|all`

CSV export of the review queue (the F50 `DashboardController` export precedent). Admin-only (class-level `@PreAuthorize`), workspace-scoped from the session principal, same status-filter semantics as the list (default `open` EXCLUDES `REVIEWED`).

`200`:
- `Content-Type: text/csv;charset=UTF-8`
- `Content-Disposition: attachment; filename="interest-requests.csv"`
- `Cache-Control: no-store`
- Body — header row `name,email,organization,message,status,submittedAt` then one row per request, recent-first.

**SC-012 / FR-010 (fully closed).** Every free-text cell (`name`, `email`, `organization`, `message`) is routed through `CsvInjectionEscaper` at this export boundary, so a `= + - @ |`/tab/CR formula payload is prefixed with a single quote (and RFC-4180-quoted as needed) and cannot execute when the CSV is opened in a spreadsheet; cells are stored verbatim and only neutralized on egress. `status`/`submittedAt` are safe enums/instants. The export is a deliberate PII egress and records one attributable audit event `INTEREST_REQUESTS_EXPORTED` (status filter + row count only — no submitter names; the `DASHBOARD_EXPORTED` precedent); no cell value is ever logged.

---

## Notification (internal effect, no endpoint)

After a new open request insert (deferred off the response path), best-effort `RecruiterNotificationService.notify(workspaceId, null, RecruiterNotificationType.INTEREST_REQUEST)` (3-arg, null candidateId — the `ATS_SYNC_FAILED` precedent) — persists a value-free in-app `RecruiterNotification` row (no submitter PII; sends no email). Coalesced via R1 (a resubmit does not insert → no second alert).

## Frontend routes

- Public: `GET /request-access` (lazy standalone component) — form + privacy notice + confirmation state. **`noindex`** (`seo: PRIVATE`, R4). Linked from `/login` and the public home `/`.
- Internal: admin queue screen under the existing admin area; `interest-requests.service.ts` typed client (incl. `exportCsv(status)` which triggers a same-origin CSV download via the session cookie).
