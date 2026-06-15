# Contracts: Calendar Integration — Microsoft 365 / Outlook (F11)

**Feature**: 008-microsoft-calendar | **Date**: 2026-06-15 | Source: [spec.md](./spec.md) · [research.md](./research.md) · [data-model.md](./data-model.md)

F11 adds **no** new REST endpoint — it reuses F10's self availability-preview (now provider-agnostic for a Microsoft connection). It refactors the internal adapter contract (provider-neutral event-id addressing) and documents the **Microsoft Graph** outbound HTTP interaction (stubbed in tests, D10).

---

## A. REST contract — availability preview (reused, provider-agnostic)

### `GET /api/internal/calendar/availability/preview`
Unchanged from F10. `@PreAuthorize("isAuthenticated()")`, self-scoped to the principal (no `memberId`). The response `provider` field is now `MICROSOFT` when the signed-in member's connection is Microsoft:
```json
{ "provider": "MICROSOFT", "status": "DATA",
  "windowStart": "2026-06-15T00:00:00Z", "windowEnd": "2026-06-22T00:00:00Z",
  "busy": [ { "start": "2026-06-16T13:00:00Z", "end": "2026-06-16T14:00:00Z" } ] }
```
- `busy` contains **only** start/end instants — never subject, attendee, or location (FR-002/FR-022).
- `status` ∈ `DATA | NOT_CONNECTED | NEEDS_RECONNECTION | TEMPORARILY_UNAVAILABLE`. A pre-F11 Microsoft connection (read-only scope / null SMTP) → `NEEDS_RECONNECTION` (D2a).
- `401` unauthenticated; `Cache-Control: no-store`; never returns another member's data or event content.

> No REST endpoint for event create/update/delete (system-internal, invoked by F13). Mixed-provider behaviour is internal to `AvailabilityService`/`CalendarEventService`.

---

## B. Internal adapter contract — `CalendarProviderClient` (refactored; D5)

`MicrosoftCalendarClient` is the F11 impl (`id() == MICROSOFT`); `GoogleCalendarClient` adapts to the refactored signatures. Services select via `Map<CalendarProvider, CalendarProviderClient>` (built from the injected `List`). Every method obtains the token via `CalendarTokenService.validAccessToken(ws, member, provider)`.

```java
List<BusyInterval> queryFreeBusy(String ws, String memberId, Instant windowStart, Instant windowEnd);
String createEvent(String ws, String bookingRef, String memberId, EventDetails details); // returns provider-assigned id
void   updateEvent(String ws, String memberId, String providerEventId, EventDetails details); // addresses stored id
void   deleteEvent(String ws, String memberId, String providerEventId);                       // addresses stored id
```

- **`createEvent` returns the provider-assigned id** — Google: the deterministic id it sets + a `409`→success guard; Microsoft: the **server-assigned** id read back from the `201` response, with a `transactionId` dedup guard (D5). `CalendarEventService` persists the **returned** id as `ManagedCalendarEvent.providerEventId`.
- **`updateEvent`/`deleteEvent` take the stored `providerEventId`** (resolved by `CalendarEventService` from the row) — no id derivation.
- **Failure contract** (unchanged set): `CalendarReconnectRequiredException` (revoked / insufficient scope / null-SMTP pre-F11), `CalendarApiException(transient=true)` after the bounded retry, `CalendarNotConnectedException`. The token-layer transient is re-wrapped as `CalendarApiException(transient)`; never a token/content payload.

---

## C. Internal service contracts (unchanged orchestration)

- **`AvailabilityService.query(...)`** — **unchanged**. With the MS client present in the provider map, a mixed panel resolves each member to their provider's client; the bounded fan-out and status mapping are F10's. Privileged internal primitive (no caller-auth). SC-001 holds (one getSchedule per member, bounded-parallel).
- **`CalendarEventService.createPanelEvents / updatePanelEvents / cancelBooking`** — **orchestration unchanged**; the only change is recording the **returned** id and resolving the **stored** id for update/delete (D5). The compensating-delete saga now spans providers: rollback dispatches `clients.get(created.provider()).deleteEvent(ws, memberId, storedId)` per created entry, so a failure on one provider rolls back the other (FR-014/SC-009). `CLEANUP_INCOMPLETE` semantics unchanged (FR-019).

---

## D. Outbound contract — Microsoft Graph HTTP (adapter ↔ Graph; stubbed, D10)

`{base}` = `calendar.api.microsoft.base-url`. All calls: `Authorization: Bearer <member token>`; no body logging.

| Op | Method + path | Request essentials | Response handling |
|---|---|---|---|
| Free/busy | `POST {base}/v1.0/me/calendar/getSchedule` | `Prefer: outlook.timezone="UTC"`; body `{schedules:[<self SMTP>], startTime:{dateTime,timeZone:"UTC"}, endTime:{...}, availabilityViewInterval:15}` | parse `value[0].scheduleItems[]` `{start.dateTime,end.dateTime,status}` **only** (UTC → `Instant`); `status!=free` → `BusyInterval`; ignore `availabilityView`; **never** read `subject`/`location`/`isPrivate` (D2/D3) |
| Create | `POST {base}/v1.0/me/events` | body `{ subject:<title>, location:{displayName:<loc>}, start:{dateTime:<local>,timeZone:<IANA>}, end:{...}, transactionId:<deterministic> }` | `201` → read back `id`; same `transactionId` retried → Graph returns the existing event → idempotent success (D5) |
| Update | `PATCH {base}/v1.0/me/events/{id}` | changed fields only (`{id}` = stored `providerEventId`) | `200` ok; `404/410` → success (idempotent) |
| Delete | `DELETE {base}/v1.0/me/events/{id}` | — | `204` ok; `404/410` → success |

- **Retry / classification (D6 — Graph-aware; throttling is `429`, not `403`)**:
  | Graph response | Class | Action |
  |---|---|---|
  | `429`, `5xx`, network | transient | backoff + jitter, **honour `Retry-After`** (`max(backoff,retryAfter)`, both header forms — D7), max `calendar.api.max-retries` (3) |
  | `401`, `403` (e.g. `ErrorAccessDenied` / insufficient scope) | permanent-auth | `markNeedsReconnection`, audit `CALENDAR_RECONNECT_REQUIRED`, **no** retry |
  | other `4xx` (`400`, `409`, `404`) | fatal | no retry (`404/410` → idempotent success by the caller) |
- Parse only Graph `error.code` (never `error.message` — FR-023). No request/response body logged verbatim. `PATCH` requires `JdkClientHttpRequestFactory` (the F10 lesson).

---

## E. RBAC / access matrix (unchanged from F10)

| Endpoint / capability | Admin | Recruiter | Hiring Mgr | Interviewer | Read-only | Unauth |
|---|---|---|---|---|---|---|
| `GET …/availability/preview` (self only) | ✅ own | ✅ own | ✅ own | ✅ own | ✅ own | 401 |
| `AvailabilityService.query` (panel) | internal (F12/F13, role-gated there) | | | | | n/a |
| `CalendarEventService.*` (write) | internal (F13 booking flow) | | | | | n/a |

- Preview self-scoped → no member reads another's calendar (FR-024). Cross-workspace impossible (every call `workspaceId`-keyed).

---

## F. Contract test obligations (Phase = tasks)

Each **non-vacuous** (assert against the stub's recorded state / the DB, not a self-reported result):

- **REST (preview, MS connection)**: 5 roles self-scoped `200`; unauth `401`; `Cache-Control: no-store`; `busy` has no content field; two members never cross; `RbacEndpointInventoryTest` green.
- **Free/busy no-content (SC-004, non-circular)**: the stub's `scheduleItems` carry a **sentinel subject + location + attendee email**; assert the parsed model / preview / logs contain **none** (parse-discipline control, D2). The mapper uses explicit `path("start"/"end"/"status")` reads (no full-object deserialization) so a later field addition cannot silently bind content (Security S5).
- **Status mapping (FR-002a / SC-010)**: seed **each** of `free`/`busy`/`tentative`/`oof`/`workingElsewhere`/`unknown` as a **separate** item and assert **per-status** (six distinct assertions) — only `free` is schedulable; each of the other five yields a busy interval (a bulk "the rest are busy" assertion can hide a single-status bug, QA S1). Non-grid boundary (09:10–09:25) → exact interval (FR-003).
- **All-day / recurring (spec edge cases)**: seed one all-day/multi-day item and two distinct in-window occurrences; assert both come back with exact spans (no drop, no mis-span) — F11's obligation is not to mangle the server-expanded items (QA S3).
- **Availability status (FR-004)**: not-connected / pre-F11-null-SMTP→`NEEDS_RECONNECTION` / **`sub`-only (non-`@`) account id→`NEEDS_RECONNECTION`** (Backend S4/Security S1) / needs-reconnection / transient-after-retry → **distinct** statuses; empty window → `DATA`+[]; **empty `scheduleItems` / empty `value` array → `DATA`+[] (no NPE on `value[0]`)** (QA N3); oversized window → clamped.
- **Idempotent create — sequential AND gated-concurrent**: double create → one Graph event (`transactionId` dedup) + one `managedCalendarEvents` row; **gated** two-thread create — the `gate(n)` latch MUST fire on the **`POST /v1.0/me/events`** request (not the Mongo claim) so it proves both threads reached Graph before either claimed — asserts exactly one Graph insert (within the dedup window the stub models) + one row (unique-index claim) (SC-008, QA N2). Assert the recorded row's `providerEventId` == the **server** id from the create response (D5). (Per D5's honest bound: the *durable* one-row guarantee is the unique-index claim; the stub models the bounded `transactionId` window so the test does not over-assert Graph's dedup.)
- **Update in place (US3-1)**: assert the stub recorded a `PATCH` on the **stored** `providerEventId` (not a new POST). Update/delete of a `404`-gone event → success (FR-011/SC-008).
- **Resilience (SC-006)**: `429,429,201` → success; persistent `5xx` → `CalendarApiException(transient)` after max-3 **and** the claim row absent/non-`CREATED` (FR-017); `401` and `403` → `NEEDS_RECONNECTION` + `CALENDAR_RECONNECT_REQUIRED`, **no** retry — assert "no retry" **non-vacuously** via `StubGraphCalendar.count(method,path) == 1` for the `401`/`403` path (QA S4), not a self-reported outcome (D6).
- **Retry-After (FR-016, BLOCKER QA B1 — no wall-clock assertion)**: a **pure unit test** of `nextWaitMillis(attempt, retryAfter)` = `max(backoff+jitter, retryAfter)` with **no sleep**, for both header forms parsed to a `Duration` (delta-seconds AND HTTP-date) + a malformed/absent header → falls back to jittered backoff; plus one test asserting the loop reads the stub-injected `Retry-After` into `CalendarApiException.retryAfter` and feeds it to `nextWaitMillis`. **Never** assert elapsed wall-clock time. Plus the pure-unit Graph classifier truth table.
- **Reconnection audit (SC-011)**: a dedicated assertion (own test or an explicit block) that a calendar-op-triggered `403`/`401` writes **exactly one** `CALENDAR_RECONNECT_REQUIRED` audit row with internal ids only and no payload.
- **DST (SC-005, pin the wire body)**: the **recorded create body** has the local `dateTime` + IANA `timeZone` for **two instants straddling** a spring-forward boundary.
- **Mixed panel (US4/SC-009)**: one `GOOGLE` + one `MICROSOFT` member → one normalised availability set (both stubs); panel booking creates on both; force provider-2 create-fail → rollback deletes provider-1 event — **both directions** (assert zero residual events in each stub's store).
- **Rollback (SC-007/FR-019)**: MS partial-create → compensating delete, zero orphans via the stub's residual store; per-eventId persistent delete-`5xx` → `CLEANUP_INCOMPLETE` + one `CALENDAR_EVENT_CLEANUP_INCOMPLETE` audit (internal ids only) + orphan still present (reconcilable).
- **Audit content**: create/update/delete audit rows assert **no** subject/location (internal ids only).
- **PII/log scan (SC-003)**: TRACE run of preview + create + update + delete + a retry-path failing call → zero sentinels across all categories (token/secret, subject, location, dial-in, attendee-email, **account email / getSchedule SMTP**) scoped to `com.cadence`; positive vacuity guard.
- **Persistence**: raw-driver read of a Microsoft `managedCalendarEvents` row shows refs + instants only (no subject/email/token); cold-`MongoTemplate` read returns the row.
- **CI**: a grep asserts no `graph.microsoft.com` literal in `MicrosoftCalendarClient` (URIs come from `calendar.api.microsoft.base-url`).
- **F10 regression**: existing Google update/delete tests adapted to the `providerEventId` signature stay green (the refactor is behaviour-preserving).
