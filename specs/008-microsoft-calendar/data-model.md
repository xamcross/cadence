# Phase 1 Data Model: Calendar Integration — Microsoft 365 / Outlook (F11)

**Feature**: 008-microsoft-calendar | **Date**: 2026-06-15 | Source: [spec.md](./spec.md) · [research.md](./research.md)

F11 adds **no** persisted collection and **no** Mongock changeset. It **reuses** F10's `managedCalendarEvents` (and its `ChangeUnit007` indexes), F10's transient model types, and F01.1's `CalendarConnection`/`CalendarProvider`/`ConnectionStatus`/`authAuditLog`. The only model-level changes are a **provider-neutral refactor of the adapter interface** (so a server-assigned event id works) and the addition of a `retryAfter` field on the failure type. No candidate collection is touched.

---

## 1. Reused persisted entity — `ManagedCalendarEvent` (`@Document("managedCalendarEvents")`)

Unchanged from F10. One row per (workspace, booking, participant, provider). For Microsoft rows:
- `provider` = `MICROSOFT`.
- `providerEventId` = the **Graph server-assigned** event id (opaque, **not PII/secret** → stored plaintext, no converter — same as the Google id). It is read back from the create response (D5), not derived.
- All other fields (`workspaceId`, `bookingRef`, `memberId`, `status`, `startAt`, `endAt`, `createdAt`, `updatedAt`) identical to F10.

**Index reuse is safe (D13)**: `ChangeUnit007`'s unique `{workspaceId,bookingRef,memberId,provider}` already discriminates on `provider`, and `provider` is an always-populated enum, so a `MICROSOFT` row and a `GOOGLE` row for the same `(workspace,booking,member)` coexist without collision and **no** `@Field(write=NON_NULL)` partial-index foot-gun. No new index, no changeset.

**Explicitly NOT stored** (unchanged): event subject, location/dial-in text, description, attendees, any account/attendee email, any token. The doc holds references + instants only.

---

## 2. Reused transient model types (unchanged from F10)

- **`BusyInterval(Instant start, Instant end)`** — **unchanged** (D3). Graph statuses other than `free` all map to an interval; no marker field is added (keeps the F10/F11 shape identical, FR-013).
- **`AvailabilityStatus`** ∈ `{ DATA, NOT_CONNECTED, NEEDS_RECONNECTION, TEMPORARILY_UNAVAILABLE }` — unchanged. A pre-F11 Microsoft connection with a null SMTP (D2a) maps to `NEEDS_RECONNECTION`.
- **`MemberAvailability(String memberId, AvailabilityStatus status, List<BusyInterval> busy)`** — unchanged.
- **`EventDetails(String title, String location, Instant startAt, Instant endAt, ZoneId timeZone)`** — unchanged; for Microsoft writes, `timeZone` is sent as the IANA id in `dateTimeTimeZone` (D4). Redacting `toString()` reused (omits title/location).
- **`EventStatus`** ∈ `{ CREATED, DELETED, CLEANUP_INCOMPLETE }` — unchanged.
- **`PanelBookingResult` / `MemberEventResult` / `PanelOutcome` / `MemberOutcome`** — unchanged; the mixed-provider rollback produces the same outcome shape (D9).
- **`Participant(String memberId, ZoneId timeZone)`** — unchanged.

---

## 3. Changed type — `CalendarApiException`

Add an **optional** field for `Retry-After` (D7):

| Field | Type | Notes |
|---|---|---|
| `transient` | `boolean` | unchanged |
| `httpStatus` | `Integer` | unchanged |
| `providerReason` | `String` | Google: `errors[].reason`; Microsoft: `error.code` (D6). Non-PII token only. |
| `retryAfter` | `Duration` (nullable) | **NEW** — set when the provider returned a `Retry-After` header (Graph `429`/`503`). `CalendarApiRetry` waits `max(backoffWithJitter, retryAfter)`. Null for the common case. |

---

## 4. Refactored interface — `CalendarProviderClient` (D5, the load-bearing change)

Provider-neutral addressing so a **server-assigned** id (Graph) works; behaviour-preserving for Google.

```java
public interface CalendarProviderClient {
    CalendarProvider id();
    String validAccessToken(String workspaceId, String memberId);

    List<BusyInterval> queryFreeBusy(String workspaceId, String memberId, Instant windowStart, Instant windowEnd);

    /** Idempotent create; RETURNS the provider-assigned event id (Google: deterministic; Microsoft: server id). */
    String createEvent(String workspaceId, String bookingRef, String memberId, EventDetails details);

    /** In-place update of the event addressed by its stored providerEventId. Idempotent (404/410 -> ok). */
    void updateEvent(String workspaceId, String memberId, String providerEventId, EventDetails details);

    /** Idempotent delete of the event addressed by its stored providerEventId (404/410 -> ok). */
    void deleteEvent(String workspaceId, String memberId, String providerEventId);
}
```

**Changes from F10**: `updateEvent`/`deleteEvent` previously took `(ws, bookingRef, memberId)` and re-derived the Google id; they now take the **stored `providerEventId`** (resolved by `CalendarEventService` from the `ManagedCalendarEvent` row). `createEvent` already returned the id; F11 makes the service **persist the returned value** (for Google it equals the deterministic id, so the stored id is unchanged). `GoogleCalendarClient` still sets its deterministic id on create (and its `409`-success guard) and returns it; its update/delete now use the passed id instead of deriving it — a mechanical change.

**Failure contract** (unchanged set): `CalendarReconnectRequiredException` (revoked / insufficient scope / null-SMTP pre-F11), `CalendarApiException(transient=true)` after the bounded retry budget, `CalendarNotConnectedException`. Never returns/throws with a token or event-content payload.

---

## 5. Reused (unchanged) entities

- **`CalendarConnection`** (F01.1) — read for the member's provider + status, the access token (via `CalendarTokenService`), and now the **SMTP/UPN** (`providerAccountId`, decrypted, used only to build getSchedule, never logged/persisted by F11 — D2a). F11 does not write it (except the existing `markNeedsReconnection` flip on `403`/insufficient scope).
- **`CalendarProvider`**, **`ConnectionStatus`** — reused as-is (`MICROSOFT` already exists).
- **`AuthEventType`** — **no new value**; reuses `CALENDAR_EVENT_CREATED/UPDATED/DELETED/CLEANUP_INCOMPLETE` and `CALENDAR_RECONNECT_REQUIRED` (D12).

---

## 6. Validation & invariants

- **Tenant isolation (FR-024)**: every query/upsert keyed by `workspaceId`; preview self-scoped to the principal.
- **`AvailabilityService.query` remains a privileged internal primitive** (no caller-auth; not to be wired to an endpoint without an F13 gate) — unchanged from F10.
- **Content minimisation (FR-002/FR-022/FR-025)**: `managedCalendarEvents`, logs, and audits hold references + instants only. The getSchedule SMTP and any attendee/account email are used transiently and never logged/persisted. The Graph free/busy mapper deserializes only `start`/`end`/`status` (subject/location never bound).
- **Status fail-safe (FR-002a)**: only `free` is schedulable; `busy`/`tentative`/`oof`/`workingElsewhere`/`unknown` → busy interval.
- **Idempotency (FR-010/FR-011)**: unique-index claim (primary) + Graph `transactionId` dedup; create reads back & persists the server id; update/delete by stored id; `404`/`410` → success.
- **No-orphan incl. cross-provider (FR-012/FR-014/SC-007/SC-009)**: the F10 compensating-delete saga, now exercised across providers (rollback dispatches per-entry `clients.get(provider).deleteEvent(storedId)`).
- **Absolute time (FR-003/FR-003a/D4)**: reads requested/parsed in UTC; writes send local `dateTime` + IANA `timeZone`.

---

## 7. Config surface (`application.yml`, bound by `CalendarApiProperties` — D1/D2/D4/D7)

```
calendar:
  oauth:
    microsoft:
      # F11 (research D1): event WRITE needs Calendars.ReadWrite (Graph has no owned-events-only scope —
      # broader than F10's Google grant, §VIII justified). openid/profile/email make Graph issue an id_token
      # so providerAccountId holds the member SMTP that getSchedule needs (D2a). Space-separated.
      scope: ${MS_CAL_SCOPE:openid profile email offline_access Calendars.ReadWrite}
  api:
    microsoft:
      base-url: ${MS_GRAPH_API_BASE:https://graph.microsoft.com}   # tests point at the JDK HttpServer stub
    graph-availability-view-interval: 15   # getSchedule availabilityViewInterval (minutes); exact boundaries
                                           # come from scheduleItems, this only bounds the ignored availabilityView
    # reused from F10 (shared, identical behaviour): connect-timeout, read-timeout, max-retries,
    # retry-base-backoff, freebusy-parallelism, max-window, preview-window
```
The F10 `calendar.api.google.*` and the shared `calendar.api.*` keys are unchanged.
