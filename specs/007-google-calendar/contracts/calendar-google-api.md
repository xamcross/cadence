# Contracts: Calendar Integration — Google Calendar (F10)

**Feature**: 007-google-calendar | **Date**: 2026-06-15 | Source: [spec.md](./spec.md) · [research.md](./research.md) · [data-model.md](./data-model.md)

F10 exposes **one** new REST endpoint (the §II demonstrable availability-preview, D11) plus the **internal service/adapter contracts** that the scheduler (F12/F13/F20) composes. The Google Calendar HTTP interaction is documented as the adapter's outbound contract (stubbed in tests, D12).

---

## A. REST contract — availability preview (the user-facing §II leg)

### `GET /api/internal/calendar/availability/preview`
- **Auth**: `@PreAuthorize("isAuthenticated()")` — **any** authenticated role; **self-scoped** to the principal (no `memberId` parameter; a member can only preview their own calendar).
- **Query params**: none for MVP (window defaults to `calendar.api.preview-window`, D11).
- **200** body (`AvailabilityPreviewResponse`):
  ```json
  {
    "provider": "GOOGLE",
    "status": "DATA",
    "windowStart": "2026-06-15T00:00:00Z",
    "windowEnd":   "2026-06-22T00:00:00Z",
    "busy": [ { "start": "2026-06-16T13:00:00Z", "end": "2026-06-16T14:00:00Z" } ]
  }
  ```
  - `busy` contains **only** start/end instants — never a title, attendee, or location (FR-002).
  - `status` ∈ `DATA | NOT_CONNECTED | NEEDS_RECONNECTION | TEMPORARILY_UNAVAILABLE`. For `NOT_CONNECTED`/`NEEDS_RECONNECTION`, `busy` is `[]` and the SPA shows a connect/reconnect prompt (reuses the F01.1 status surface).
- **401** when unauthenticated (existing F01 `/api/**` entry point).
- **Headers**: `Cache-Control: no-store` (availability is live; mirrors F01.1 `GET /connections`).
- **Never returns** another member's data; never returns event content.

> No REST endpoint for event create/update/delete in F10 — those are **system-internal** (invoked by F13's booking flow via the service contract below). Adding a public booking endpoint is F13's job.

---

## B. Internal adapter contract — `CalendarProviderClient` (widened; research D7)

Provider-agnostic; `GoogleCalendarClient` is the F10 impl (`id() == GOOGLE`). Services select via `Map<CalendarProvider, CalendarProviderClient>`. Every method obtains the access token through `CalendarTokenService.validAccessToken(ws, member, provider)` (F01.1) — the client never holds its own credential.

```java
public interface CalendarProviderClient {
    CalendarProvider id();

    // F01.1 (existing) — currently-valid access token, transparent refresh.
    String validAccessToken(String workspaceId, String memberId);

    // F10 (new):
    /** Busy intervals for ONE member over [window.start, window.end). Free/busy endpoint only (D2). */
    MemberAvailability queryFreeBusy(String workspaceId, String memberId, Instant windowStart, Instant windowEnd);

    /** Idempotent create of a Cadence interview event on the member's calendar. Returns the provider event id. */
    String createEvent(String workspaceId, String bookingRef, String memberId, EventDetails details);

    /** In-place update (time/title/location) of a previously-created event. Idempotent. */
    void updateEvent(String workspaceId, String bookingRef, String memberId, EventDetails details);

    /** Idempotent delete; a provider "already gone" (404/410) is treated as success (FR-011). */
    void deleteEvent(String workspaceId, String bookingRef, String memberId);
}
```

**Failure contract** (every method): `CalendarReconnectRequiredException` (permanent auth / revoked grant / **insufficient scope** — D9), `CalendarApiException(transient=true)` after the bounded retry budget is exhausted (D8), `CalendarNotConnectedException` (no connection). The interface's two-arg `validAccessToken(ws,member)` delegates to `CalendarTokenService.validAccessToken(ws,member,id())`; the CRUD methods **catch the token-layer `CalendarProviderTransientException` and re-wrap as `CalendarApiException(transient=true)`** so only `CalendarApiException` surfaces a transient from the calendar API (plan-review Backend M1). Never returns/throws with a token or event-content payload.

---

## C. Internal service contracts (composed by F12/F13/F20)

### `AvailabilityService.query(workspaceId, windowStart, windowEnd, List<String> memberIds) -> List<MemberAvailability>`
- One bounded-parallel `queryFreeBusy` per member (D4), each via the member's connection-selected client.
- A member with no connection → `MemberAvailability(memberId, NOT_CONNECTED, [])`; needs-reconnection → `NEEDS_RECONNECTION`; transient after retry → `TEMPORARILY_UNAVAILABLE` (FR-004). Never silently "free".
- A member whose connection is a provider with no F10 client (e.g. `MICROSOFT` pre-F11) → `NOT_CONNECTED` for F10 purposes (F11 fills it in).
- A window wider than `calendar.api.max-window` is rejected/clamped; an empty window → `DATA` + empty busy (not an error).
- **Privileged internal primitive (Security M4)**: performs no caller-authorization; MUST NOT be exposed on an endpoint without an F13-level role gate.
- **SC-001**: 5-member panel returns within 5 s under normal latency (bounded parallel).

### `CalendarEventService` (the booking-side write primitives; D10)
```
PanelBookingResult createPanelEvents(String workspaceId, String bookingRef,
                                     List<Participant> participants, EventDetails details)
void updatePanelEvents(String workspaceId, String bookingRef,
                       List<Participant> participants, EventDetails newDetails)
void cancelBooking(String workspaceId, String bookingRef)   // delete all events for the booking (idempotent)
```
- `createPanelEvents`: creates per participant; on a mid-panel failure, compensating-deletes the already-created ones and returns `outcome=ROLLED_BACK`; if a compensating delete itself exhausts retries, that member → `CLEANUP_INCOMPLETE`, audited, `outcome=CLEANUP_INCOMPLETE` (FR-012/FR-016a/SC-007). Success → `outcome=CREATED`.
- Each create/update/delete writes/updates a `ManagedCalendarEvent` (claim → CREATED → DELETED/CLEANUP_INCOMPLETE) and emits the matching audit (`CALENDAR_EVENT_*`, internal ids only — D13).
- `Participant(String memberId, ZoneId timeZone)` — the per-attendee zone for DST-correct rendering (D5).

---

## D. Outbound contract — Google Calendar HTTP (adapter ↔ Google; stubbed in tests, D12)

| Op | Method + path (`{base}` = `calendar.api.google.base-url`) | Request essentials | Response handling |
|---|---|---|---|
| Free/busy | `POST {base}/calendar/v3/freeBusy` | `{timeMin, timeMax, items:[{id:"primary"}]}`, `Authorization: Bearer <member token>` | parse `calendars.primary.busy[]` `{start,end}` → `BusyInterval`s **only**; ignore everything else |
| Create | `POST {base}/calendar/v3/calendars/primary/events` | body `{ id:<deterministic>, summary:<title>, location:<loc>, start:{dateTime,timeZone}, end:{dateTime,timeZone} }` | `200/201` → event id; `409` (id exists) → treat as success (D6) |
| Update | `PATCH {base}/calendar/v3/calendars/primary/events/{eventId}` | changed fields only | `200` ok; `404/410` → treat as success (idempotent) |
| Delete | `DELETE {base}/calendar/v3/calendars/primary/events/{eventId}` | — | `204` ok; `404/410` → treat as success |

- **Retry / classification (D8, reason-aware — Google overloads `403`)**:
  | Provider response | Class | Action |
  |---|---|---|
  | `429`, `5xx`, network, `403 rateLimitExceeded`/`userRateLimitExceeded` | transient | backoff **+ jitter**, max `calendar.api.max-retries` (3) |
  | `401`, `403` revoked, **`403 insufficientPermissions`/`insufficientScope`** (stale freebusy-only grant, B1) | permanent-auth | flip `NEEDS_RECONNECTION` (D9), audit `CALENDAR_RECONNECT_REQUIRED`, **no** retry |
  | other `4xx` | fatal | no retry |
- **No event content read** on the free/busy path (the endpoint cannot return it). **No provider error/request body logged verbatim** (FR-017b — request bodies too: the RestClient has no body-logging interceptor) — only status + classified outcome.

---

## E. RBAC / access matrix

| Endpoint / capability | Admin | Recruiter | Hiring Mgr | Interviewer | Read-only | Unauth |
|---|---|---|---|---|---|---|
| `GET …/availability/preview` (self only) | ✅ own | ✅ own | ✅ own | ✅ own | ✅ own | 401 |
| `AvailabilityService.query` (panel) | internal — called by F12/F13 on behalf of the scheduling actor; not directly role-gated here | | | | | n/a |
| `CalendarEventService.*` (write) | internal — invoked by F13's booking flow (role-gated at the F13 endpoint), never a direct client call | | | | | n/a |

- The preview is self-scoped → no member can read another's calendar (FR-018, mirrors F01.1 SC-007).
- Cross-workspace access is impossible: every service call is `workspaceId`-keyed.

---

## F. Contract test obligations (Phase = tasks)

Each must be **non-vacuous** (assert against the stub's recorded state / the DB, not a self-reported result object):

- **REST**: `GET …/preview` for all 5 roles = 200 self-scoped; unauthenticated = 401; `Cache-Control: no-store`; `busy` has no content field; two members never see each other's busy data (FR-018); preview is single-member (no fan-out pool).
- **Free/busy no-content (SC-004 — must be non-circular)**: the stub holds a seeded event carrying a **sentinel title + attendee email**; assert the adapter's parsed `MemberAvailability`/the preview response/the logs contain **none** of those sentinels (tie to the SC-003 sentinel set) — proving content that *exists server-side* never reaches Cadence.
- **Availability status (FR-004)**: not-connected / needs-reconnection / transient-after-retry each assert a **distinct** `AvailabilityStatus` (not merely "not DATA"); empty-window → `DATA` + empty list; **oversized window** (> max-window) → rejected/clamped.
- **Idempotent create — sequential AND gated-concurrent**: sequential double-create → one event, `409`→ok; **a named gated test** (`gate(2)`) releases two concurrent `createPanelEvents(sameWs,sameBookingRef,sameMember)` simultaneously and asserts exactly **one** Google insert recorded by the stub **and** exactly **one** `managedCalendarEvents` row (the unique-index `DuplicateKeyException` claim path exercised, mirroring F01.1's gated CAS test) — the gate proves the threads truly overlapped (SC-008).
- **Update in place (US3-1)**: assert the stub recorded a **PATCH on the same `providerEventId`** (not a new insert) — "in place" is the load-bearing assertion. Update/delete of a `404`-gone event → success (FR-011/SC-008).
- **Resilience (SC-006)**: `429,429,200` → succeeds after 2 retries; persistent `503` → `CalendarApiException(transient)` after max-3, **and the `managedCalendarEvents` claim row is absent/non-`CREATED`** (FR-014 — no half-written state); `403 rateLimitExceeded` → retried (transient); `403 insufficientPermissions` and `401` → `NEEDS_RECONNECTION` + `CALENDAR_RECONNECT_REQUIRED` audit, **no** retry (D9/B1); pure-unit backoff+jitter bound (`delay ≤ base·2^n + jitterMax`).
- **DST (SC-005 — pin the wire body)**: assert the **recorded request body** has the correct UTC offset in `dateTime` for the pre-transition instant **and** the IANA `timeZone` field; use **two instants straddling** the boundary to prove the offset actually changes (not an `Instant` round-trip).
- **Rollback (SC-007/FR-016a)**: panel where participant N's insert fails → assert the **stub's residual event store is empty** for the booking (zero orphans) and `outcome=ROLLED_BACK`; then a path where a created event's **delete persistently fails** (per-eventId `503` sequence) → that member's `ManagedCalendarEvent.status==CLEANUP_INCOMPLETE`, one `CALENDAR_EVENT_CLEANUP_INCOMPLETE` audit (internal ids only), `outcome=CLEANUP_INCOMPLETE`, and the orphan **still exists** in the stub (proving reconcilable, not silently dropped).
- **Audit content**: create/update/delete audit rows assert **no** title/location present (internal ids only).
- **PII/log scan (SC-003)**: TRACE-level run of preview + create + update + delete + a **retry-path failing call** asserts zero sentinels at any level across **five** categories — token/secret, **event-title, location, dial-in/phone-number, attendee-email, provider-account-email** — scoped to `com.cadence` loggers; **positive vacuity guard** asserts a known-present internal id IS detected (so a no-op scan can't pass green).
- **Persistence**: raw-driver read of `managedCalendarEvents` shows refs + instants only (no title/email/token); cold-`MongoTemplate` read returns the row (no converter needed).
- **CI**: a grep asserts no `googleapis.com` literal in `GoogleCalendarClient` (URIs come from `calendar.api.google.base-url`, so tests can't accidentally hit real Google).
