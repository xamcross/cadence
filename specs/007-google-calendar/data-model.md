# Phase 1 Data Model: Calendar Integration — Google Calendar (F10)

**Feature**: 007-google-calendar | **Date**: 2026-06-15 | Source: [spec.md](./spec.md) · [research.md](./research.md)

F10 adds **one** persisted collection (`managedCalendarEvents`) and several **transient** (non-persisted) model types for the availability read and the event-write outcome. It reuses F01.1's `CalendarConnection`/`CalendarProvider`/`ConnectionStatus` and the `authAuditLog`. No candidate collection is touched.

---

## 1. Persisted entity — `ManagedCalendarEvent` (`@Document("managedCalendarEvents")`)

One row per (workspace, booking, participant, provider): the durable handle for update/delete, the idempotency claim, and the rollback/reconciliation record (research D6/D10/D14).

| Field | Type | Notes |
|---|---|---|
| `id` | `String` (`@Id`) | Mongo ObjectId hex. |
| `workspaceId` | `String` | Tenant scope (FR-018). |
| `bookingRef` | `String` | Opaque interview/booking reference supplied by the caller (F13 owns its meaning). NOT a calendar id. |
| `memberId` | `String` | Internal member id of the participant whose calendar holds the event. |
| `provider` | `CalendarProvider` (enum) | `GOOGLE` (F11 adds `MICROSOFT`). |
| `providerEventId` | `String` | The provider's event id (Google: the deterministic client-supplied id, D6). **Opaque, not PII, not a secret** → stored plaintext, no converter. |
| `status` | `EventStatus` (enum) | `CREATED` \| `DELETED` \| `CLEANUP_INCOMPLETE` (D10). |
| `startAt` | `Instant` | Event start (absolute; D5). For reconciliation/enumeration only. |
| `endAt` | `Instant` | Event end (absolute). |
| `createdAt` | `Instant` | Set on first claim. |
| `updatedAt` | `Instant` | Touched on patch/delete/cleanup. |

**Explicitly NOT stored** (PII / content minimisation, FR-002/FR-017a/FR-018a): event title, location/dial-in text, description, attendee list, any provider-account email, any token. The doc carries only references + instants, so it needs **no** `PiiStringConverter` (research D14) — asserted by a raw-driver test (the doc holds no title/email/token sentinel).

**Indexes** (Mongock `ChangeUnit007`, native-driver `createIndex`, F00.1 pattern):
- **Unique** `{workspaceId:1, bookingRef:1, memberId:1, provider:1}` — the idempotency claim (D6); a racing duplicate create loses on this index → reuse the winner (mirrors F01.1 `upsertConnected`).
- Non-unique `{workspaceId:1, bookingRef:1}` — enumerate all participants' events for rollback (FR-012) and cancel/reschedule (F20).

All four unique-key fields are always-non-null, so **no** `@Field(write=NON_NULL)` partial-index foot-gun (CLAUDE.md F01 lesson).

**State transitions**:
```
(claim, unique upsert) --> CREATED --(events.patch)--> CREATED   (time/title updated; row time bounds refreshed)
                              |  \--(events.delete ok / 404 gone)--> DELETED
                              \--(compensating delete exhausts retry)--> CLEANUP_INCOMPLETE  (audited; reconcilable)
```

---

## 2. Transient model types (not persisted)

### `BusyInterval` (record)
`Instant start, Instant end` — one occupied range, absolute time (D5). No content.

### `AvailabilityStatus` (enum)
`DATA` (busy list is authoritative) · `NOT_CONNECTED` · `NEEDS_RECONNECTION` · `TEMPORARILY_UNAVAILABLE` (transient provider error after retry). A non-`DATA` member is **not schedulable**, never "free" (FR-004).

### `MemberAvailability` (record)
`String memberId, AvailabilityStatus status, List<BusyInterval> busy` — per-member result; `busy` empty + `DATA` == genuinely free.

### `EventDetails` (record) — caller-supplied at create/update time, never persisted by F10
`String title, String location, Instant startAt, Instant endAt, ZoneId timeZone` — title/location are recruiter free-text (treated as PII for logging, D13); F10 forwards them to the provider on the create/patch call and does **not** store them.

### `EventStatus` (enum) — see persisted entity above.

### `PanelBookingResult` (record) — the event-write outcome contract (D10)
`PanelOutcome outcome, List<MemberEventResult> perMember`
- `PanelOutcome` ∈ `{ CREATED, ROLLED_BACK, CLEANUP_INCOMPLETE }`.
- `MemberEventResult(String memberId, MemberOutcome outcome, String providerEventId)` where `MemberOutcome` ∈ `{ CREATED, FAILED, ROLLED_BACK, CLEANUP_INCOMPLETE, NEEDS_RECONNECTION }`.
This is what F13 reads to decide commit / retry / roll back / surface a reconnect prompt.

### `CalendarApiException` (RuntimeException) — failure classification (D8)
Carries `boolean transient`, `Integer httpStatus`, `String providerReason` (the Google `errors[].reason`). Classification is **reason-aware** (D8 table): transient (`429`/`5xx`/network/`403 rateLimitExceeded` → retry w/ backoff+jitter), permanent-auth (`401`/`403` revoked/**`insufficientPermissions`** → needs-reconnection, D9), fatal (other `4xx` → no retry).

---

## 3. Reused (unchanged) entities

- **`CalendarConnection`** (F01.1) — read to find each participant's provider + status and to obtain the access token (via `CalendarTokenService`). F10 does not write it (except the existing F01.1 NEEDS_RECONNECTION flip on `invalid_grant`).
- **`CalendarProvider`**, **`ConnectionStatus`** (F01.1 enums) — reused as-is.
- **`authAuditLog`** via `AuthAuditService` — extended with four new `AuthEventType` values (below), internal ids only.

### `AuthEventType` — appended values (D13)
`CALENDAR_EVENT_CREATED`, `CALENDAR_EVENT_UPDATED`, `CALENDAR_EVENT_DELETED`, `CALENDAR_EVENT_CLEANUP_INCOMPLETE`. (Append only — never renumber existing values.)

---

## 4. Validation & invariants

- **Tenant isolation (FR-018)**: every query/upsert is keyed by `workspaceId`; a member's availability/preview is self-scoped to the authenticated principal (no `memberId` in the preview path).
- **`AvailabilityService.query` is a privileged internal primitive (plan-review Security M4)**: it accepts an arbitrary `memberId` list and is `workspaceId`-keyed only — it performs **no** caller-authorization itself. It MUST NOT be exposed on any HTTP endpoint without an F13-level authorization gate (the scheduling actor's permission to schedule those members). F10 ships it consumed only by the self-scoped preview (1-element, principal-derived) and by F13/F12 internally. A guard note is added to `RbacEndpointInventoryTest`'s rationale so a future feature cannot wire this method to a controller without a role gate.
- **Deterministic Google event id (D6)**: derived from a **length-prefixed/unambiguous** join of `bookingRef` + `memberId` before `SHA-256` → base32hex (charset `[a-v0-9]`, RFC-4648 extended-hex, padding stripped) so `("a","bc")` and `("ab","c")` cannot collide. Inputs are internal opaque ids (no PII oracle).
- **Content minimisation (FR-002/FR-017a/FR-018a)**: `managedCalendarEvents` and all logs/audits hold references + instants only — never title/location/attendee/account-email/token.
- **Idempotency (FR-010/FR-011)**: unique claim index + idempotent provider create (409→ok) / delete (404→ok).
- **No-orphan guarantee (FR-012/SC-007)**: a partial-create rollback deletes all already-created events; an un-deletable one becomes `CLEANUP_INCOMPLETE` (audited), never a silent clean report (FR-016a).
- **Absolute time (FR-003/D5)**: all stored/compared times are `Instant`; event writes also send the IANA `timeZone`.

---

## 5. Config surface (`application.yml`, bound by `CalendarApiProperties` — research D8/D4)

```
calendar:
  api:
    google:
      base-url: ${GOOGLE_CAL_API_BASE:https://www.googleapis.com}   # tests point at the JDK HttpServer stub
    connect-timeout: PT5S          # the calendar RestClient's own timeouts (plan-review Backend MINOR)
    read-timeout: PT10S            # also the per-panel fan-out join deadline (D4)
    max-retries: 3                 # FR-013 backlog AC (shared w/ F11)
    retry-base-backoff: PT0.1S     # x 2^attempt + jitter; tests override to PT0S
    freebusy-parallelism: 8        # bounded panel fan-out (D4)
    max-window: P60D               # reject/clamp an oversized availability window (D4 / spec edge)
    preview-window: P7D            # availability-preview horizon (D11)
```
And the F01.1 `calendar.oauth.google.scope` default changes to add the **least-privilege event-management** scope (D1):
`https://www.googleapis.com/auth/calendar.events.owned https://www.googleapis.com/auth/calendar.freebusy`
(`calendar.events` is the documented fallback only, with a re-recorded §VIII trade-off — D1).
