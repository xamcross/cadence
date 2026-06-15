# Phase 0 Research: Calendar Integration — Google Calendar (F10)

**Feature**: 007-google-calendar | **Date**: 2026-06-15
**Input**: [spec.md](./spec.md) · builds on F01.1 (006-oauth-token-store)

All decisions below resolve the Technical Context unknowns and are consumed by `plan.md`, `data-model.md`, and `contracts/`. Each: **Decision / Rationale / Alternatives rejected**.

---

## D1 — OAuth scope: free/busy read **and** event write requires an event-management scope (constitution §VIII justification)

**Decision**: F10 requests, in addition to F01.1's `calendar.freebusy`, the **narrowest event-management scope that grants manage-own-events without granting read of unrelated events** — default **`https://www.googleapis.com/auth/calendar.events.owned`** (see-create-change-delete events the member organizes/owns, which is exactly what Cadence creates) — so the consented Google scope string becomes:
`https://www.googleapis.com/auth/calendar.events.owned https://www.googleapis.com/auth/calendar.freebusy`
`calendar.events` (read/write of **all** the member's events) is the documented **fallback** only if a verified limitation of `events.owned` blocks a required operation (e.g. updating an event a user manually moved off the owned set). Either is **config** (`GOOGLE_CAL_SCOPE`), so the exact string is confirmed against Google's current granular-scope list at implementation; tests use the stub, so the string is just configuration. No code change to F01.1's gateway — only the `application.yml` default + the Fly config. (Plan-review correction: the earlier draft named `calendar.events` as "narrowest" — it is **not**; `events.owned` is the least-privilege answer for "manage only events Cadence authors", and the §VIII text below is corrected accordingly.)

**§VIII documented justification (REQUIRED by the constitution for any scope beyond free/busy)**: The §11 MVP mandates **bi-directional** Google Calendar sync — Cadence must *create, update, and delete* interview events on participants' calendars (F10 US-F10-2/3). The free/busy scope is read-only and structurally cannot write an event; an event-write scope is therefore unavoidable to deliver the mandated capability. The chosen `calendar.events.owned` restricts writes to events the member organizes (the interviews Cadence creates) and does **not** authorize reading the titles/attendees/bodies of unrelated meetings — minimising both the consent ask and the **blast radius of a stored-token compromise** (a leaked refresh token can touch only Cadence-authored events, not the member's whole calendar). The *read* path additionally never lists/reads any event content (FR-002, structural via D2 — free/busy endpoint only). If the `calendar.events` fallback is ever required, the §VIII trade-off it introduces (the granted scope then permits — though Cadence never exercises — read of all events) MUST be re-recorded and re-approved here. The full `…/calendar` scope is rejected outright as over-privileged.

**Scope-migration safety (plan-review B1)**: connections created under F01.1's freebusy-only grant lack the event scope. An event write against such a token returns Google **`403 insufficientPermissions`/`insufficientScope`** — which is **not** `invalid_grant`, so a refresh *succeeds* and returns a still-scope-deficient token (the create would loop-fail). D8/D9 are corrected so an **insufficient-scope `403` is detected explicitly and flips the connection to `NEEDS_RECONNECTION`** (distinct from `invalid_grant`), surfacing a reconnect prompt instead of an opaque repeated failure. The read/preview path keeps working on the freebusy scope, so the member is told to reconnect rather than seeing "connected but writes mysteriously fail". This makes re-consent the explicit, tested path, not an operational assumption.

**Alternatives rejected**:
- **Keep `calendar.freebusy` only** — cannot write events; fails the MVP's bi-directional requirement. Rejected.
- **`calendar` (full access)** — grants read/write of *all* the member's events; violates least-privilege and §VIII's free/busy default intent. Rejected.
- **`calendar.events` as the default** — grants read/write of *all* events (broader read than Cadence ever uses); kept only as a fallback with a re-recorded §VIII trade-off. Rejected as the default (plan-review M1/M2).
- **`calendar.app.created`** — secondary/app-calendar semantics, awkward on the member's *primary* calendar; `events.owned` is the better primary-calendar least-privilege answer. Rejected.

---

## D2 — Availability read uses Google's **FreeBusy query**, never `events.list` (structural FR-002/SC-004 guarantee)

**Decision**: Read availability via `POST {googleApiBase}/calendar/v3/freeBusy` (`freebusy.query`) with a body of `{timeMin, timeMax, items:[{id:"primary"}]}`, parsing **only** the returned `calendars.primary.busy[]` array of `{start,end}` RFC-3339 instants into the internal `BusyInterval` model. The adapter never calls `events.list` / `events.get` for availability, so event titles, attendees, descriptions, and locations are **never returned to Cadence at all** — the no-content guarantee (FR-002) is enforced by the *choice of endpoint*, not by post-hoc filtering.

**Rationale**: This is the same structural control the backlog flags as ISSUE-2 for Microsoft. Using the free/busy endpoint means the wire response physically cannot contain event content, so SC-004 ("seed rich event content, assert only intervals survive") passes by construction. Each member's free/busy is queried with **that member's** access token against their `primary` calendar (Cadence holds per-member tokens; it cannot and must not query another member's calendar with a different member's token).

**Alternatives rejected**: `events.list` with `?fields=items(start,end)` field projection — would *request* full-content scope and rely on projection discipline to avoid leakage; one mistaken `fields` change leaks content. The free/busy endpoint removes the foot-gun entirely. Rejected.

---

## D3 — Provider-neutral internal availability model (shared with F11)

**Decision**: Define a provider-agnostic model the Google adapter normalises into and F11 reuses verbatim:
- `BusyInterval(Instant start, Instant end)` — absolute instants only (D6).
- `MemberAvailability(String memberId, AvailabilityStatus status, List<BusyInterval> busy)`.
- `AvailabilityStatus` ∈ `{ DATA, NOT_CONNECTED, NEEDS_RECONNECTION, TEMPORARILY_UNAVAILABLE }` — so the scheduler can distinguish "free" (DATA + empty busy) from "unknown because the member isn't connected / needs reconnection / a transient provider error" (FR-004): a non-`DATA` member is treated by F12/F13 as **not schedulable**, never as fully free.

**Rationale**: FR-019 requires F10 and F11 to produce the *same* internal shape so mixed-provider panels (F11) and the rule engine (F12) consume one model. Carrying an explicit status (not just an empty list) prevents the classic "no data == wide open" double-booking bug (FR-004).

**Alternatives rejected**: returning a bare `List<BusyInterval>` per member (loses the not-connected vs free distinction → silent double-book). Rejected. Returning provider-specific DTOs (breaks the F11 shared-model contract). Rejected.

---

## D4 — Bounded **parallel per-member** free/busy fan-out (SC-001: 5-person panel < 5 s)

**Decision**: A panel availability read issues one `freebusy.query` per member **concurrently**, bounded by a small fixed-size executor (default parallelism 8, configurable `calendar.api.freebusy-parallelism`), and joins with a per-panel deadline derived from the RestClient read-timeout. This is a bounded in-process fan-out — **no broker, no queue, no extra service** (C2 holds). The **single-member availability-preview (D11) does NOT use the pool** — it is one call on the request thread (a 1-element fast path), so the pool's only F10 consumer is the panel `query`, delivered+tested here and consumed by F13.

**Executor hygiene (plan-review Backend MAJOR)**: the bean is wrapped in `DelegatingSecurityContextExecutorService` and decorated to **copy the MDC correlation id** onto worker threads (else panel-thread logs lose the correlation id and any `SecurityContext`/MDC read returns null — the standard Spring thread-pool foot-gun). It is declared `@Bean(destroyMethod = "shutdown")` (a plain `@Bean ExecutorService` is **not** torn down by `server.shutdown=graceful`, which only drains the web container) so the pool closes on context shutdown and its threads don't delay JVM exit; in-request fan-out tasks are joined before the response, so graceful drain already covers an in-flight request. A bounded **max window** (D-window below) caps any single panel call.

**Window bound (plan-review QA)**: `AvailabilityService.query` rejects/clamps a window wider than `calendar.api.max-window` (default 60 days) so a caller-supplied `windowStart/windowEnd` cannot turn one request into an unbounded multi-month scan (spec edge "Time window bounds"); an empty/zero window returns `DATA` with an empty busy list, not an error.

**Rationale**: Google's free/busy endpoint can take multiple calendars in one request, but only for calendars the *calling token* can see; Cadence holds a **separate token per member**, so a panel is inherently N token-scoped calls. Running them sequentially risks the 5 s budget under normal latency; a bounded parallel fan-out keeps wall-clock ≈ the slowest single call. FR-005's "single logical operation, not an unbounded per-member fan-out" is satisfied: the fan-out is bounded and capped, exposed as one `AvailabilityService.query(...)` call.

**Alternatives rejected**: one combined `freebusy.query` listing all members' emails under a single service token — Cadence is not a Workspace-domain-delegated app and must not assume domain-wide delegation; it only has per-member user consent. Rejected (wrong trust model, and would need a far broader admin scope). Unbounded `parallelStream`/thread-per-member — violates "bounded" and risks resource exhaustion on a large panel. Rejected.

---

## D5 — DST correctness: absolute instants on the wire, IANA zone on event writes

**Decision**: (a) All free/busy and stored times are `java.time.Instant` (absolute UTC); parsing uses `Instant`/`OffsetDateTime`, never `LocalDateTime`. (b) Event **writes** send `start`/`end` as `{ dateTime: <RFC-3339 with offset>, timeZone: <IANA zone, e.g. "America/New_York"> }` so Google renders the correct wall-clock across a DST boundary. (c) A synthetic DST-crossing integration fixture (an interview one hour before a spring-forward transition in a zone that observes DST) asserts the event's effective wall-clock is the intended one (SC-005).

**Rationale**: The classic silent failure (QA edge-case) is naive local-time arithmetic that drifts by an hour across a transition. Storing/operating on absolute instants and handing Google both the offset *and* the IANA zone removes the ambiguity.

**Alternatives rejected**: sending only a UTC instant without the IANA `timeZone` field — Google would store it correctly but a later all-day/recurrence edit or the attendee's display could mis-zone; sending the IANA zone is Google's documented DST-safe form. Rejected. Naive `LocalDateTime` storage — the bug itself. Rejected.

---

## D6 — Event write: idempotency via a claimed `managedCalendarEvents` record + deterministic Google event id

**Decision**: Per (workspace, bookingRef, member, provider) Cadence persists a `ManagedCalendarEvent` record holding the provider event id and status. **Primary, provider-neutral idempotency** = a unique index `{workspaceId, bookingRef, memberId, provider}` claimed by an upsert *before* the provider insert (mirrors F01.1 `upsertConnected` racing the unique index); a duplicate claim → the existing record is reused, so a retried create makes **at most one** event per participant (FR-010). **Google-specific belt-and-suspenders**: the insert supplies a client-generated event id `base32hex(SHA-256(bookingRef|memberId))` (Google id charset `[a-v0-9]{5,1024}`); re-inserting the same id returns HTTP 409, which the adapter treats as success and reconciles the stored id (covers a record-written-but-insert-retried race). Update = `events.patch`; delete = `events.delete`; a delete/patch of an id the provider reports **404/410 (gone)** is treated as success (FR-011) so rollback/cancel never wedges.

**Rationale**: F11 (Microsoft Graph) cannot supply client event ids, so the *persisted-record* claim is the cross-provider idempotency substrate (keeps FR-019's shared model honest); Google's deterministic id is a free extra guard. The record is also what makes rollback enumeration (FR-012) and reconciliation (FR-016a) possible.

**Alternatives rejected**: deterministic id **only** (no record) — works for Google but F11 can't mirror it, and there's no durable list to enumerate for rollback/reconciliation. Rejected. Record **only**, server-generated id — fine, but loses Google's cheap 409 race guard. Kept both; record is primary.

---

## D7 — `CalendarProviderClient` widened; `GoogleCalendarClient` is the first impl behind it

**Decision**: Widen the existing forward interface `CalendarProviderClient` (F01.1 declared it with `validAccessToken` only and a comment that "Google/Microsoft impls land in F10/F11") with the read/write capability:
`queryFreeBusy(ws, memberId, window)`, `createEvent(ws, bookingRef, memberId, EventDetails)`, `updateEvent(...)`, `deleteEvent(ws, bookingRef, memberId)`. `GoogleCalendarClient implements CalendarProviderClient`, `id()==GOOGLE`, obtains the access token via `CalendarTokenService.validAccessToken(ws, member, GOOGLE)` (F01.1 — never a token of its own), and performs HTTP via a private `RestClient` built with bounded connect/read timeouts from `calendar.api.*` (the F01.1 `AbstractOAuthGateway` pattern). Services select the client from a `Map<CalendarProvider, CalendarProviderClient>` built from the injected `List<CalendarProviderClient>` (the F01.1 gateway-map pattern). The Google Calendar SDK is **not** added — raw REST via `RestClient` keeps the Dependency Policy (C4) and stays stub-testable.

**Token plumbing (plan-review Backend M1 — the interface/service seam made explicit)**: the interface's existing **two-arg** `validAccessToken(ws, member)` is implemented by each provider client as a thin delegate to the F01.1 **three-arg** service: `=> tokenService.validAccessToken(ws, member, id())`. The new CRUD methods call that same delegate for their bearer token. The token layer throws `CalendarProviderTransientException`/`CalendarReconnectRequiredException`/`CalendarNotConnectedException`; the CRUD methods **catch a token-layer `CalendarProviderTransientException` and re-wrap it as `CalendarApiException(transient=true)`** so callers see exactly one transient type from the calendar-API surface (the §B failure contract lists only `CalendarApiException`/`CalendarReconnectRequiredException`/`CalendarNotConnectedException`). `CalendarReconnectRequiredException`/`CalendarNotConnectedException` propagate unchanged.

**Rationale**: Satisfies the constitution Dependency Policy (business logic depends on the `CalendarProvider` abstraction, never the SDK) and FR-019; reuses the exact, proven F01.1 seam so F11 slots in as a second map entry.

**Alternatives rejected**: pulling the official `google-api-services-calendar` SDK — a new heavyweight dependency outside the fixed stack, brings transitive Guava/HTTP-client conflicts, and is hard to point at the JDK stub. Rejected (C4). A single switch-on-provider class — bakes provider specifics into the service, violates the swap-without-touching-service rule. Rejected.

---

## D8 — Calendar-API failure classification + retry with **exponential backoff and jitter** (max 3)

**Decision**: Introduce `CalendarApiException(transient, httpStatus, providerReason)` and a tiny `CalendarApiRetry` executor: on a **transient** failure — `429`, `5xx`, a network `ResourceAccessException`, **or a `403` whose Google `errors[].reason` is `rateLimitExceeded`/`userRateLimitExceeded`** — retry with exponential backoff **plus jitter**, capped at `calendar.api.max-retries` (default **3**, the F10 backlog AC) using `calendar.api.retry-base-backoff` (× 2^attempt) and a bounded random jitter. Classification is **reason-aware, not status-only** (plan-review m3 — Google overloads `403`):

| Provider response | Class | Action |
|---|---|---|
| `429`; `5xx`; network error; `403 rateLimitExceeded`/`userRateLimitExceeded` | TRANSIENT | retry w/ backoff+jitter, max 3 |
| `401`; `403` revoked/auth; **`403 insufficientPermissions`/`insufficientScope`** (B1 stale-scope) | PERMANENT-AUTH | flip `NEEDS_RECONNECTION` (D9), **no** retry |
| other `4xx` (e.g. `400`, `404` non-idempotent context) | FATAL | no retry |

The insufficient-scope `403` is **explicitly** routed to needs-reconnection (not lumped with generic auth, and definitely not retried) — it is the stale-scope-migration signal (D1/B1). Backoff/jitter and the max are shared config reused by F11 (one policy, identical behaviour).

**Rationale**: The backlog requires "exponential backoff and jitter (max 3 retries)" for `429`/`503`. F01.1's refresh retry was *linear without jitter* (token endpoint), so F10 adds the jitter the calendar-API path needs to avoid synchronized retry storms across a panel. Jitter is the behavioural delta from F01.1's retry; the reason-aware `403` split is the other (Google returns `403` for *both* rate-limiting and auth/scope, so a status-only classifier would mis-retry or mis-fail).

**Alternatives rejected**: reuse F01.1's linear `refreshWithRetry` verbatim — no jitter, and it's coupled to the token endpoint. Rejected. Resilience4j / Spring Retry dependency — new dependency for ~30 lines of backoff math. Rejected (C4). Tests set `retry-base-backoff: PT0S` so retry assertions don't add wall-clock (the F01.1 `refresh-retry-backoff: PT0S` test pattern).

---

## D9 — Mid-call token revocation (`401` from the calendar API) → needs-reconnection, bounded

**Decision**: The access token always comes from `CalendarTokenService.validAccessToken` (fresh by F01.1's skew logic). Two permanent-auth cases:
- **Revoked grant** (`401`, or `403` revoked): the adapter does **one** forced re-validation through the token store; if that fails as `invalid_grant` the store flips `NEEDS_RECONNECTION` (existing F01.1 behaviour, which already audits `CALENDAR_RECONNECT_REQUIRED`).
- **Insufficient scope** (`403 insufficientPermissions`/`insufficientScope`, the stale-freebusy-only grant — B1): a refresh would *succeed* yet still lack the scope, so re-validation does **not** help. The adapter directly flips the connection to `NEEDS_RECONNECTION` via a guarded `findAndModify({_id, status==CONNECTED} → NEEDS_RECONNECTION)` (the F01.1 `markNeedsReconnection` shape) and audits **`CALENDAR_RECONNECT_REQUIRED`** (reuse the existing F01.1 event type — this is the FR-020 "calendar-op-triggered needs-reconnection" occurrence; no new audit enum needed).

Either way the adapter surfaces `CalendarReconnectRequiredException` with **no** transient-style retry; the member is reported `NEEDS_RECONNECTION` in availability, or the booking op fails fast so F13 can roll back. On a panel where M members simultaneously hit a revoked/stale grant, each does at most one re-validation (bounded N extra token calls, acceptable).

**Rationale**: Reuses F01.1's permanent-vs-transient machinery; a revoked or scope-deficient grant must not be retried forever (FR-015, SC-006), and the insufficient-scope case must be handled *separately* from `invalid_grant` or it loops silently (plan-review B1).

**Alternatives rejected**: treat `401`/`403` as transient and back off — retries a permanent failure pointlessly and delays the reconnect prompt. Rejected. Rely on `invalid_grant` alone to catch stale scope — it never fires for insufficient scope (the refresh succeeds), so writes fail opaquely forever. Rejected (the B1 bug).

---

## D10 — Partial-create rollback + the "cleanup-incomplete" outcome (FR-012 / FR-016a / SC-007)

**Decision**: `CalendarEventService.createPanelEvents(ws, bookingRef, participants, details)` creates events participant-by-participant; on a failure for participant *k* it **compensating-deletes** the events already created for participants `0..k-1` (each delete idempotent, D6) and returns a `PanelBookingResult` = `{ outcome: CREATED | ROLLED_BACK | CLEANUP_INCOMPLETE, perMember:[...] }`. If a compensating delete itself exhausts its retry budget, that member is recorded `status=CLEANUP_INCOMPLETE` on its `ManagedCalendarEvent`, audited (`CALENDAR_EVENT_CLEANUP_INCOMPLETE`, internal ids only), and the overall outcome is `CLEANUP_INCOMPLETE` — never silently reported as a clean rollback (so an orphan is reconcilable, FR-016a). A normal rollback leaves zero orphans (SC-007). **The atomic slot-reservation that *invokes* this is F13**; F10 ships the primitive + its outcome contract, exercised end-to-end against the stub in tests.

**Rationale**: SC-007's "zero orphans" is only achievable if the compensating delete can itself fail and that failure is surfaced rather than swallowed (the Security/QA finding folded into the spec). The result object gives F13 a clean retry/rollback/escalate signal.

**Alternatives rejected**: best-effort delete that swallows failures and always reports clean — makes SC-007 a lie under a delete failure. Rejected. A MongoDB multi-doc transaction across calendar writes — calendar side effects are external (not transactional with Mongo); a saga/compensation is the only correct shape. Rejected.

---

## D11 — Demonstrable end-to-end slice (constitution §II): member **availability-preview** through the full stack

**Decision**: Because the panel-read and event-write paths are *system*-triggered (their UI trigger is F13's booking flow), F10's constitution-§II end-to-end leg is a **minimal real user-facing slice**: a "Verify / preview my availability" action on the existing F01.1 `calendar-connections` page that calls `GET /api/internal/calendar/availability/preview` (`isAuthenticated()`, self-scoped — the signed-in member only) and renders the member's own busy blocks for the next 7 days (or "you appear free"). This is a genuine Angular → Spring → Google(stub) → back round-trip exercising the free/busy adapter, and is honestly useful (it proves the connection can actually *read availability*, not merely that a token is stored — which is all "Connected" status means today). The **event-write** path (system-only) is delivered as the F13-consumed capability + the compensating-delete primitive, verified end-to-end against the stub by integration/contract tests; its UI trigger lands in F13.

**Rationale**: Honours §II ("no backend-only work presented as a shipped feature") without gold-plating a booking UI that belongs to F13. Self-scoped preview exposes no other member's data (C3). Flagged in `plan.md` for the multi-role review to confirm it is the right §II leg and not YAGNI.

**Alternatives rejected**: ship F10 as a pure backend adapter, tests only, no UI — risks the §II "backend-only as done" prohibition. Rejected. Build the full panel/booking UI in F10 — that orchestration and its candidate-facing surface are F13/F14; building them here is scope theft and premature (§I). Rejected.

---

## D12 — Test provider stub: extend the F01.1 JDK `HttpServer` (NOT WireMock)

**Decision**: Add a **sibling** `StubGoogleCalendar` (NOT a subclass of `StubProvider`) — a JDK `com.sun.net.httpserver.HttpServer` — because the calendar API needs matching dimensions the OAuth `StubProvider` does not have (plan-review Backend/QA): (1) **method-aware** matching (`DELETE`/`PATCH` carry no body, so match on **method + path**, with the event id as a path param — `StubProvider` keys on path+body only and ignores the method); (2) **per-operation, per-eventId programmable status sequences** consumed per call (e.g. `freeBusy→200`, but `events.insert→200` then the *same* event's `events.delete→503,503,503` to drive the create-succeeds-then-delete-fails `CLEANUP_INCOMPLETE` path); a single global "newest wins" stub (StubProvider's model) cannot express `429,429,200` or op-specific failures. It also keeps an in-memory **event store** that holds the seeded event's title/attendees so the `freeBusy` handler can *project only* `{start,end}` (making the SC-004 no-content test non-vacuous — the content really exists server-side and must not appear client-side), request recording, and a `gate(n)` latch (reused idea from `StubProvider`) for non-vacuous concurrency. `calendar.api.google.base-url` points the adapter at the stub. **WireMock is NOT used** — the `wiremock-standalone` fat jar's bundled Jackson breaks Spring Boot's Jackson (removed in F01.1); the JDK `HttpServer` is conflict-free.

**Rationale**: Matches the in-repo precedent (JDK HttpServer, no cloud creds) while giving the calendar tests the method-awareness and per-call status sequencing the resilience/rollback/idempotency assertions genuinely need; a sibling keeps the OAuth stub's simpler semantics intact. Budget real work — this is more than a subclass.

**Alternatives rejected**: WireMock (Jackson/Jetty classpath conflict — the documented F01.1 reason it was removed). Rejected. Hitting real Google in CI — needs cloud credentials; prohibited. Rejected.

---

## D13 — Audit, logging & PII discipline (reuse `AuthAuditService`; extend the log scan)

**Decision**: Add `AuthEventType` values `CALENDAR_EVENT_CREATED`, `CALENDAR_EVENT_UPDATED`, `CALENDAR_EVENT_DELETED`, `CALENDAR_EVENT_CLEANUP_INCOMPLETE` (event lifecycle). The **FR-020 "calendar-op-triggered needs-reconnection" occurrence reuses the existing F01.1 `CALENDAR_RECONNECT_REQUIRED`** (no new enum — D9 emits it when F10 flips a connection on insufficient-scope/revoked `403`). Audit each via `AuthAuditService` with **internal ids only** (workspaceId, memberId, bookingRef, providerEventId — never title/location/attendee/token). Logging rules: log only `provider.name()` **Strings** and internal ids via `StructuredArguments.kv` — **never** a Java enum to `kv(...)` (the F01.1 logstash-encoder Jackson-3 `NoSuchFieldError` foot-gun; this now applies to the **new** enums too — `EventStatus`, `AvailabilityStatus`, `PanelOutcome`, `MemberOutcome` MUST be logged as `.name()` if logged at all); never the event title/location (recruiter free-text PII incl. **dial-in numbers**, FR-017a), never an attendee or **provider-account** email (FR-018a — two distinct categories), never a Google response/error body verbatim (FR-017b — log only status + classified outcome). `EventDetails` gets a **redacting `toString()`** (omits title/location — the F03 `emailProviderCredential` discipline) so an exception that captures it can't leak; the calendar `RestClient` is built with **no body-logging interceptor** and no wire-level DEBUG/TRACE logger for its package (FR-017b covers request bodies too, not only responses). `ci.yml`'s PII/secret scan gains calendar sentinels for **event-title, location, dial-in/phone-number, attendee-email, and provider-account-email** (five categories) on top of the F01.1 token sentinels.

**Rationale**: Carries every F01.1 security guarantee forward unbroken and closes the review-found gaps (FR-017a/b, FR-018a) including the request-body and exception-`toString` leak paths the plan review surfaced. The enum-to-`kv` foot-gun — now with four *new* enums — is the single most likely way F10 re-introduces a runtime crash, so it's called out explicitly for the tasks phase.

**Alternatives rejected**: logging the Google error body for debuggability — leaks calendar ids/emails/event snippets (FR-017b). Rejected. Auditing event titles for richer history — stores candidate PII in the audit log. Rejected.

---

## D14 — Storage, indexes, migration (Mongock `007`)

**Decision**: One new collection `managedCalendarEvents`; `ChangeUnit007_ManagedCalendarEventIndexes` (order `"007"`) creates a **unique** index `{workspaceId, bookingRef, memberId, provider}` (idempotency claim, D6) and a non-unique `{workspaceId, bookingRef}` (rollback/cancel enumeration). Native driver `createIndex` + `IndexOptions().unique(true)` (the F00.1 pattern); rollback drops the two indexes by key (never `dropIndexes()`). The provider event id and time bounds are **not secrets** (an opaque calendar id + instants, no PII), so the collection needs **no** `PiiStringConverter` registration — confirmed by the §VIII no-PII review and the raw-driver test asserting the doc holds no token/title/email.

**Rationale**: Matches F00.1 index conventions and the established Mongock ordering. Keeping content out of the doc (D13) is what lets it stay un-encrypted safely.

**Alternatives rejected**: storing the eventId on the (future) F13 booking doc — F13 doesn't exist; F10 needs a durable home now for update/delete/rollback. Rejected. Encrypting the whole doc defensively — unnecessary (no PII) and breaks the unique-index query keys. Rejected.

---

## Resolved unknowns summary

| Unknown (Technical Context) | Resolved by |
|---|---|
| How to write events without violating free/busy-only? | D1 — `calendar.events` scope + §VIII justification |
| How to guarantee no unrelated event content is read? | D2 — free/busy endpoint only (structural) |
| Shared model with F11? | D3 — provider-neutral `BusyInterval`/`MemberAvailability` |
| 5-person panel < 5 s? | D4 — bounded parallel per-member fan-out |
| DST correctness? | D5 — absolute instants + IANA zone on writes |
| Idempotent create/update/delete? | D6 — claimed record + deterministic Google id |
| Provider abstraction without an SDK dep? | D7 — widen `CalendarProviderClient`, `RestClient` |
| 429/503 retry with jitter, max 3? | D8 — `CalendarApiRetry` (backoff + jitter) |
| Revoked-mid-call handling? | D9 — one re-validate then needs-reconnection |
| Partial-create rollback + orphan safety? | D10 — saga compensating delete + cleanup-incomplete outcome |
| Constitution §II end-to-end leg? | D11 — member availability-preview slice |
| Test stub without cloud creds / WireMock? | D12 — JDK `HttpServer` stub |
| Audit/log/PII discipline? | D13 — `AuthAuditService` + scan sentinels + enum-kv foot-gun |
| Storage/indexes/migration? | D14 — `managedCalendarEvents` + Mongock `007` |
