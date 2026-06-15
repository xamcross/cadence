# Phase 0 Research: Calendar Integration — Microsoft 365 / Outlook (F11)

**Feature**: 008-microsoft-calendar | **Date**: 2026-06-15
**Input**: [spec.md](./spec.md) · mirrors F10 (007-google-calendar) · builds on F01.1 (006-oauth-token-store)

All decisions resolve the Technical Context unknowns and are consumed by `plan.md`, `data-model.md`, and `contracts/`. Each: **Decision / Rationale / Alternatives rejected**. F11 reuses F10's internal model, services, retry, fan-out, and stub *pattern*; the decisions below cover only the genuine Microsoft-Graph divergences the spec flagged (idempotency, error shape, `Retry-After`, time zones, free/busy status mapping, the getSchedule mailbox address) plus the §VIII scope change.

---

## D1 — OAuth scope: add event write **and** the OIDC identity scopes needed for getSchedule (constitution §VIII)

**Decision**: Change `calendar.oauth.microsoft.scope` from F01.1's `Calendars.Read offline_access` to:
`openid profile email offline_access Calendars.ReadWrite`

- `Calendars.ReadWrite` — required to create/update/delete events (the §11 MVP bi-directional mandate). Graph has **no owned-events-only delegated scope** (unlike Google's `calendar.events.owned`), so `Calendars.ReadWrite` — read+write of the member's mailbox calendar — is the narrowest delegated scope that can write. It supersedes `Calendars.Read` (the read path keeps working).
- `openid profile email` — make Microsoft issue an **id_token** so F01.1's `AbstractOAuthGateway.accountFromIdToken` populates `providerAccountId` with the member's **email / UPN**. This is required because Graph `getSchedule` addresses calendars by SMTP/UPN (D2a); under the old `Calendars.Read offline_access` scope **no id_token is issued and `providerAccountId` is null**, so getSchedule cannot be built. These are standard OIDC identity scopes (already used by F01 login), **not** calendar-content scopes.

**§VIII documented justification (REQUIRED for any scope beyond free/busy)**: The MVP mandates bi-directional Outlook sync; the read-only `Calendars.Read` cannot write. Microsoft offers no narrower write scope than `Calendars.ReadWrite` (confirmed against the Graph delegated-permission reference) — there is no Graph analogue to Google's `calendar.events.owned`. The plan **explicitly acknowledges** `Calendars.ReadWrite` is therefore broader than F10's least-privilege Google write grant: it technically also permits reading the member's full calendar. Compensating controls: (a) the adapter **never** lists/reads event content — the read path is getSchedule with start/end/status parse-discipline (D2), the write path only ever touches Cadence-created events addressed by their stored id (D5); (b) explicit per-member consent (the F01.1 consent screen, now showing the write+identity scopes); (c) event subject/location are forwarded to the provider but never stored/logged (D12). The `openid profile email` additions are benign identity scopes. **Approved in this plan per §VIII.**

**Scope-migration safety (mirrors F10 B1)**: a connection made under the old `Calendars.Read offline_access` grant lacks write scope *and* has a null `providerAccountId`. On a **write**, Graph returns `403` (e.g. `ErrorAccessDenied`/insufficient scope) → classified RECONNECT → `markNeedsReconnection` (existing F01.1 seam) → the member reconnects under the new scope. On a **read**, a null `providerAccountId` (no SMTP for getSchedule) is treated as `NEEDS_RECONNECTION` (D2a) rather than an opaque failure — so a pre-F11 connection cleanly prompts reconnection. Re-consent is the explicit, tested path.

**Alternatives rejected**: keep `Calendars.Read` (cannot write — fails the MVP). `Calendars.ReadWrite.Shared` / application permissions / `Calendars.ReadBasic` (shared = broader; application = wrong trust model, no per-user consent; ReadBasic = read-only). Resolving the SMTP via `GET /me` instead of adding `openid` (needs `User.Read`, an *extra* scope and an extra call per query — adding `openid profile email` is cheaper and yields the address at connect with zero Graph calls).

---

## D2 — Availability read = Graph **`getSchedule`**, parsing `scheduleItems[].{start,end,status}` only (FR-002/FR-002a/FR-003)

**Decision**: Read availability via `POST {graphBase}/v1.0/me/calendar/getSchedule` with `Prefer: outlook.timezone="UTC"` and a body `{schedules:[<self SMTP>], startTime:{dateTime,timeZone:"UTC"}, endTime:{...}, availabilityViewInterval:15}`. Parse **only** `value[0].scheduleItems[]`, reading each item's `start.dateTime`, `end.dateTime` (as UTC instants, D4) and `status` — and **nothing else**. Map status → busy per D3. Ignore `availabilityView` (the quantized string) and **never** read `scheduleItems[].subject`/`location`/`isPrivate`.

**Content-minimisation control (honest nuance vs F10)**: Unlike Google's freeBusy endpoint — which *physically cannot* return event content (F10's structural guarantee) — Graph `getSchedule` on the caller's **own** mailbox **can** include `subject`/`location` in `scheduleItems` (the owner has full detail). There is no Graph parameter to suppress them. So for F11 the no-content guarantee is enforced by **parse-discipline** **and verified by SC-004** (the stub seeds subject/location into `scheduleItems`; the test asserts they never reach the model, the preview response, or the logs). This is exactly the backlog ISSUE-2 risk, so the test *is* the control. **Hardening (plan-review Security S5)**: the mapper MUST use **explicit path reads** — `node.path("scheduleItems")` → per item `path("start").path("dateTime")`, `path("end")…`, `path("status")` — exactly the `JsonNode.path(...)` style F10's `GoogleCalendarClient.parseBusy` uses, **never** full-object Jackson deserialization into a bean. This makes it structurally impossible for a later `@JsonProperty`/field addition to silently start binding `subject`/`location`, so the SC-004 control cannot regress unnoticed. Documented as a known divergence from F10's structural guarantee for the plan review to bless (see also the spec FR-002/SC-004 wording reconciliation, below).

**One getSchedule per member — the 20-mailbox cap never applies (plan-review QA S2)**: F11 issues **one getSchedule call per member** with `schedules:[<single self SMTP>]`, reusing F10's bounded per-member fan-out (`AvailabilityService` is unchanged). Graph's ~20-schedules-per-request batch cap therefore never applies and batch chunking is **not implemented** (explicitly deferred — a future optimisation could batch ≤20 mailboxes into one call, but that would change `queryFreeBusy`'s one-member contract). This is recorded so the spec's "panel larger than the cap" edge case reads as *designed-away*, not forgotten. `freebusy-parallelism` is kept conservative for Graph's stricter per-app throttle.

**Rationale**: getSchedule is still the best choice — it returns free/busy for *any* mailbox the token can see (enabling F13 panels) and `scheduleItems` carry exact event boundaries (satisfying the QA exact-boundary requirement). The parse-discipline + non-circular SC-004 test closes the content gap.

**Alternatives rejected**:
- **`availabilityView`-only (digit string `0/1/2/3/4`)** — *truly* structural (no content field exists in the response), but **quantized** to `availabilityViewInterval`, so a busy block that straddles a bucket boundary is mis-represented (QA BLOCKER: a 09:10–09:25 busy would round to the bucket grid → mis-offered slots). Kept only as a documented degraded fallback. Rejected as primary.
- **`/me/calendarView?$select=start,end,showAs`** — operates on `/me` (no SMTP needed) and projects fields, but a dropped/incorrect `$select` regresses to full content (the F10 `events.list` foot-gun the backlog explicitly warns about), and it needs client-side recurrence handling. getSchedule expands recurrence server-side. Rejected (regression-prone projection; kept as the documented contingency only).

---

## D2a — getSchedule `schedules[]` address = the member's own SMTP/UPN from `providerAccountId`

**Decision**: For the self read/preview (the only F11-direct caller; panels are F13), the `schedules` array is `[connection.providerAccountId]` — the member's email/UPN populated from the id_token at connect (D1). The `MicrosoftCalendarClient` reads it from the F01.1 `CalendarConnection` (decrypted transparently by the `PiiStringConverter`), uses it **only** to build the getSchedule request, and **never logs or persists it** (FR-025).

**`sub`-fallback guard (plan-review Backend S4 / Security S1)**: F01.1's `AbstractOAuthGateway.accountFromIdToken` returns the **first non-blank** of `email`, `preferred_username`, `upn`, **`sub`** — and a Microsoft work/school id_token can omit `email` while always carrying `sub`, an **opaque GUID that is not a routable mailbox address**. A `sub` value would be non-blank, so a naive "null/blank → reconnect" check would **miss** it and send a GUID to getSchedule (→ a malformed/empty result, silently wrong availability). Therefore `MicrosoftCalendarClient` MUST treat a `providerAccountId` that is **not SMTP/UPN-shaped (no `@`)** the same as null → `CalendarReconnectRequiredException` → `NEEDS_RECONNECTION`. (A pre-F11 connection — read-only scope, often a null or `sub`-only account id — is thus cleanly routed to reconnection, which yields a real address under the new `openid profile email` scope.) Tested: seed a `sub`-only connection and assert `NEEDS_RECONNECTION`, not a malformed getSchedule.

**Rationale**: getSchedule requires SMTP/UPN addresses; the address is available post-D1 with zero extra Graph calls. Treating a missing **or non-addressable** account id as needs-reconnection is correct (such a connection also lacks the write scope and must reconnect anyway) and closes the silent-wrong-availability hole the `sub` fallback would otherwise open.

**Alternatives rejected**: a `GET /me?$select=mail,userPrincipalName` lookup per query (extra latency + needs `User.Read`); storing the SMTP in a new field (D1 already lands it in `providerAccountId`); trusting `providerAccountId` blindly (the `sub` foot-gun).

---

## D3 — Free/busy status mapping; reuse F10's `BusyInterval`/`MemberAvailability` model **unchanged** (FR-002a/FR-013)

**Decision**: Map every Graph `scheduleItems[].status` value: `free` → no interval (schedulable); **`busy`, `tentative`, `oof`, `workingElsewhere`, `unknown`** → emit a `BusyInterval(start,end)` (not schedulable). No status is ever silently treated as free (FR-002a — fail safe). The internal `BusyInterval` record stays `(Instant start, Instant end)` with **no** added marker, so F10's model and the shared contract (FR-013) are unchanged; `MemberAvailability`/`AvailabilityStatus` are reused verbatim. (Google's freeBusy already collapses tentative into busy, so emitting an interval for any non-free Graph status keeps both providers' output identical.)

**Rationale**: Keeps the F11/F10 model byte-identical (mixed panels consume one shape) while honouring the QA BLOCKER that `tentative`/`oof`/`workingElsewhere`/`unknown` must block, never read as free.

**Alternatives rejected**: adding a `busy/tentative` enum to `BusyInterval` — would diverge from F10's shape (breaks FR-013) for no scheduling benefit (tentative blocks anyway). Treating `tentative` as free — the classic double-book bug. Rejected.

---

## D4 — DST / time zones: read in UTC, write IANA in `dateTimeTimeZone` (FR-003/FR-003a/SC-005)

**Decision**: (a) **Reads** request `timeZone:"UTC"` + `Prefer: outlook.timezone="UTC"`, so `scheduleItems[].start/end.dateTime` come back as UTC and parse to `Instant` unambiguously — **side-stepping Windows-zone identifiers entirely** on the read path. (b) **Writes** send `start`/`end` as `{ dateTime:<local wall-clock ISO, no offset>, timeZone:<IANA, e.g. "America/New_York"> }`; Graph accepts IANA zone identifiers in `dateTimeTimeZone` (Graph's documented IANA support), so it renders the correct wall-clock across a DST boundary. (c) A synthetic DST-crossing fixture asserts the **recorded request body** carries the IANA zone and a local `dateTime` for two instants straddling a spring-forward boundary (SC-005). A Windows↔IANA mapping table is a **documented fallback** only, used if a non-IANA zone is ever encountered on a non-UTC path (not exercised by the UTC read path).

**Rationale**: Requesting UTC removes the Windows-vs-IANA parse foot-gun (QA SHOULD-FIX) from the hot path; Graph's native IANA acceptance on writes mirrors F10's `{dateTime,timeZone}` approach (D5 in F10) so the DST test shape is identical.

**Alternatives rejected**: parsing Windows zone names from non-UTC responses (needs a brittle mapping table on the hot path); sending only a UTC instant on writes without the IANA zone (a later edit/display could mis-zone — Graph's documented DST-safe form is dateTime+IANA). Rejected.

---

## D5 — Idempotency: provider-neutral unique-index claim (PRIMARY) + Graph `transactionId`; **interface refactor** to a server-assigned id (FR-010/FR-011)

**Decision**: The **primary, durable** idempotency substrate stays F10's provider-neutral **unique-index claim** on `managedCalendarEvents {workspaceId,bookingRef,memberId,provider}` (D13) — unchanged. The Graph-specific guard (the analogue of Google's deterministic-id 409) is the **`transactionId`** property on create: the adapter sets `transactionId = base32hex(SHA-256(lenPrefixed(bookingRef|memberId)))` (the same deterministic value F10 derives), and Graph deduplicates a retried/concurrent create carrying the same `transactionId`. Because **Graph assigns the event `id` server-side** (a client cannot set it), the adapter **reads back the `id` from the create response and returns it**, and the service **persists the returned id** as `providerEventId`.

**Honest bound on `transactionId` (plan-review Security S2)**: Graph's `transactionId` dedup is a **bounded-window** retry guard (it dedups replays within a limited per-mailbox retention), **not** a durable idempotency key. So `transactionId` is explicitly *belt-and-suspenders*; the **durable** exactly-one guarantee is the unique-index claim in `recordCreated` (upsert + `DuplicateKeyException` → non-inserter, F10's path, unchanged). Consequence to state plainly: two creates that race **outside** Graph's dedup window could produce **two** Graph events; the unique-index claim still yields exactly **one** `managedCalendarEvents` row (CREATED), and the second, unreferenced Graph event is an orphan reconciled by the same path as a `CLEANUP_INCOMPLETE` residual (not silently ignored). The contract therefore promises "exactly one *recorded* event" (one row), and "one Graph event" only within the dedup window — the gated concurrent test asserts one row + (within-window) one stub insert, with the stub modelling the window so the test is not asserting an unrealistically strong Graph guarantee.

**Interface refactor (the load-bearing F11 change)** — the current `CalendarProviderClient` re-derives the Google id inside `updateEvent`/`deleteEvent`, which is impossible for Graph. Make addressing provider-neutral:
```
String createEvent(ws, bookingRef, memberId, EventDetails)            // returns the provider-assigned id
void   updateEvent(ws, memberId, String providerEventId, EventDetails)// addresses the STORED id
void   deleteEvent(ws, memberId, String providerEventId)              // addresses the STORED id
```
`CalendarEventService` is refactored — **explicit blast radius** (plan-review Backend S1/S3), because the F10 service body derives and discards ids in three places that all change:
- `createForParticipant` (≈ line 145) currently computes `String eventId = GoogleEventId.of(bookingRef, memberId)`, calls `client.createEvent(...)` as a **void statement (discarding its return, line 160)**, and records that *derived* id (line 163) and returns it (line 169). The refactor **deletes the line-145 derivation**, captures `String providerEventId = client.createEvent(...)`, and records/returns the **returned** id. (For Microsoft the derived id is meaningless; leaving line 145 in place would compile and pass the Google tests while silently storing a wrong id for MS.)
- The sequential fast-path (lines 149–154) already returns `existing.getProviderEventId()` from the row — post-refactor this is the **only** correct source of the MS id on a retry (it cannot be re-derived); keep it.
- `updatePanelEvents` (≈ line 101) and `rollback`/`cancelBooking` (≈ lines 125/178) must pass the **stored** `providerEventId` into the client. `cancelBooking`/`rollback` already hold the row (`row.getProviderEventId()` / the `Created` record's id) — extend `Created` to carry the id; **`updatePanelEvents` does NOT currently load the row** (it has only `bookingRef`), so it needs an **added per-participant repo read** to resolve the stored id (the contract's "orchestration unchanged" means the saga shape, not zero new reads).

`GoogleCalendarClient` keeps setting its deterministic id + 409-success on create and **returns that id** (so Google's stored id is unchanged and addressing still works); only its update/delete signatures change to accept the passed id (which, for Google, equals the deterministic id). The DB-keyed helpers (`keyQuery`/`markStatus`/`touchTimes`) keep keying by `(ws,bookingRef,memberId,provider)` — that is the *row* key, not the provider event id. This refactor touches `GoogleCalendarClient`, `CalendarProviderClient`, `CalendarEventService`, and the F10 tests that call update/delete — a mechanical, behaviour-preserving change for Google.

**Rationale**: Graph forbids client-supplied ids and offers `transactionId` precisely for retry-dedup; the unique-index claim remains the cross-provider guarantee (FR-010 holds for both). Recording the returned id is mandatory for Graph and harmless for Google. The provider-neutral signatures end the F10 design's reliance on a derivable id (the Backend reviewer's BLOCKER).

**Alternatives rejected**: keep `(bookingRef,memberId)` signatures and have the MS client look up the row itself (couples every client to the repo; the service already holds the row). Use `iCalUId`/natural-key dedupe by listing events (a content read — violates D2). Skip `transactionId` and rely only on the claim (loses the provider-side dedup guard for an in-flight concurrent create where the claim is written *after* the provider call — F10's provider-first ordering; `transactionId` closes that window exactly as Google's 409 does). Rejected.

---

## D6 — Graph failure classification (provider-aware; simpler than Google's 403)

**Decision**: Add Graph-aware classification (parameterise `CalendarApiClassifier` by provider, or a `classifyGraph`): 

| Graph response | Class | Action |
|---|---|---|
| `429`; `5xx`; network error | TRANSIENT | retry w/ backoff+jitter, **honour `Retry-After`** (D7), max 3 |
| `401`; `403` (e.g. `ErrorAccessDenied`, insufficient scope) | RECONNECT | flip `NEEDS_RECONNECTION`, audit `CALENDAR_RECONNECT_REQUIRED`, **no** retry |
| other `4xx` (`400`, `409`, `404`) | FATAL | no retry (`404`/`410` handled as idempotent success by the caller) |

Graph's error body is `{"error":{"code":"<string>","message":"..."}}` — parse only `error.code` (a non-PII token), never the `message` (may echo identifiers). Unlike Google, **Graph throttling is `429`, not `403`** — so a `403` is unambiguously auth/permission → RECONNECT (no rate-limit reason disambiguation needed). 

**Rationale**: Graph's status semantics differ from Google's (no `403`-as-throttle), so a status-only RECONNECT-on-403 is correct here; reusing Google's reason-aware 403 logic verbatim would be wrong. `Retry-After` is the one resilience signal Graph adds (D7).

**Alternatives rejected**: reuse Google's classifier unchanged (mis-handles `403`); inspect `error.message` for richer classification (PII-leak risk, FR-023). Rejected.

---

## D7 — Honour `Retry-After` on `429`/`503` (FR-016)

**Decision**: Extend `CalendarApiException` with an optional `Duration retryAfter`, populated when Graph returns a `Retry-After` header (on `429` or `503`). The header is parsed in **both** documented forms — delta-seconds integer and HTTP-date — with a malformed/absent header falling back to the default jittered backoff. The change is shared (Google rarely sends `Retry-After`; honouring it there is harmless), keeping one retry policy across adapters (FR-015).

**Testable-by-construction wait (plan-review QA B1 — the one BLOCKER)**: `CalendarApiRetry.execute` currently computes backoff internally and `Thread.sleep`s with no observable seam, and the loop discards the exception's `retryAfter`. Asserting "the retry waited ≥ the interval" by wall-clock is **flaky** and incompatible with the suite's `retry-base-backoff: PT0S` fast tests. Therefore extract the wait into a **pure, returnable function** `long nextWaitMillis(int attempt, Duration retryAfter)` = `max(backoffMillis(attempt), retryAfter == null ? 0 : retryAfter.toMillis())`, and have `execute` read the caught `CalendarApiException.retryAfter` and call it. Tests assert `nextWaitMillis` **directly with no sleep** (both header forms parsed to a `Duration`, then the bound), plus one test that the loop actually passes the exception's parsed `retryAfter` in. **No test asserts elapsed wall-clock time.**

**Rationale**: Graph throttling supplies `Retry-After`; ignoring it retries too early and compounds throttling. Both header formats occur in the wild, so both are parsed. Making the wait a pure function keeps the assertion deterministic and the fast-test convention intact.

**Alternatives rejected**: ignore `Retry-After` and use pure jittered backoff (re-throttles); honour only delta-seconds (HTTP-date form silently mis-parses to 0 or a huge wait). Rejected.

---

## D8 — `MicrosoftCalendarClient` is the second `CalendarProviderClient` impl

**Decision**: `MicrosoftCalendarClient implements CalendarProviderClient`, `id()==MICROSOFT`, registered as a Spring `@Component` so it auto-joins the `Map<CalendarProvider,CalendarProviderClient>` in `AvailabilityService`/`CalendarEventService` (the F01.1 gateway-map pattern). It obtains the token via `CalendarTokenService.validAccessToken(ws,member,MICROSOFT)` (never its own), reads the member's SMTP from the connection (D2a), and performs HTTP via a private `RestClient` built on `JdkClientHttpRequestFactory` (required for `PATCH` — the F10 lesson) with the bounded `calendar.api.*` timeouts and **no** body-logging interceptor (FR-021/FR-023). No Microsoft Graph SDK is added (Dependency Policy / C4) — raw REST, stub-testable.

**Rationale**: Adding one map entry makes mixed Google+Microsoft panels and cross-provider rollback work with **no change** to `AvailabilityService`/`CalendarEventService`'s orchestration (they already select per-connection and the rollback already iterates `created[]` with each entry's own client) — this is FR-013/FR-014/US4 delivered by construction (D9). The `RestClient`-on-`JdkClientHttpRequestFactory` and token-seam patterns are copied from `GoogleCalendarClient`.

**Alternatives rejected**: the `microsoft-graph` SDK (heavyweight dep outside the stack, transitive conflicts, hard to point at the stub — C4); a switch-on-provider monolith (violates the swap-without-touching-service rule). Rejected.

---

## D9 — Mixed Google + Microsoft panel + cross-provider rollback (FR-013/FR-014/US4) — by construction

**Decision**: No new orchestration. `AvailabilityService.query` already resolves each member's connection to its provider's client and fans out; with the MS client present, a mixed panel returns one normalised `List<MemberAvailability>`. `CalendarEventService.createPanelEvents` already records each `Created(memberId, provider, eventId)` and, on a mid-panel failure, rolls back by calling `clients.get(c.provider()).deleteEvent(...)` per created entry — so a failure on the 2nd provider deletes the already-created event on the **1st** provider regardless of which providers they are. F11 only needs the refactor of D5 (record/address the server id) for this to work for Graph. Tests assert both directions (Google-fails-rollback-MS and MS-fails-rollback-Google) against both stubs (SC-007/SC-009).

**Rationale**: The whole point of the F10 provider-map design was to make F11 a drop-in; the cross-provider rollback is the same `created[]` loop. Verifying both failure directions is the QA NICE-TO-HAVE folded in.

**Alternatives rejected**: a provider-specific rollback path (unnecessary — the loop is already provider-keyed). Rejected.

---

## D10 — Test stub: `StubGraphCalendar` (JDK `HttpServer` sibling of `StubGoogleCalendar`)

**Decision**: Add `StubGraphCalendar` — a JDK `com.sun.net.httpserver.HttpServer`, **sibling** of `StubGoogleCalendar` (not a subclass; the Graph paths/shapes/`transactionId`/`Retry-After` differ). It serves: `POST /v1.0/me/calendar/getSchedule` → `200` `{value:[{scheduleItems:[{status,start:{dateTime,timeZone},end:{...},subject,location}]}]}` (seeded subject/location present → SC-004 non-circular); `POST /v1.0/me/events` → `201` with a **server-generated `id`**, deduping by `transactionId` (same `transactionId` → return the existing id, FR-010); `PATCH /v1.0/me/events/{id}` → `200`; `DELETE /v1.0/me/events/{id}` → `204`. It supports per-(method,path) **status sequences** (`429,429,201`), an injectable **`Retry-After`** header (delta-seconds and HTTP-date forms), a live-event store (residual-orphan assertions, SC-007), request recording (assert the recorded body for DST/idempotency), and a `gate(n)` latch (non-vacuous concurrency). `calendar.api.microsoft.base-url` points the adapter at the stub via `@DynamicPropertySource`. **WireMock is NOT used** (the F01.1 Jackson conflict).

**Rationale**: Same in-repo precedent (JDK HttpServer, no cloud creds) with the Graph-specific shapes the resilience/idempotency/`Retry-After`/content tests need. A sibling keeps `StubGoogleCalendar` intact for the mixed-panel test (both stubs run together).

**Alternatives rejected**: subclass `StubGoogleCalendar` (different URL scheme, body shapes, `transactionId` semantics — a sibling is cleaner); WireMock (Jackson/Jetty conflict); live Graph in CI (needs credentials). Rejected.

---

## D11 — Demonstrable end-to-end slice (constitution §II)

**Decision**: F11's §II leg reuses F10's self **availability-preview** — `GET /api/internal/calendar/availability/preview` is already provider-agnostic (`AvailabilityService.providerFor` picks the member's connection), so once a member connects **Microsoft** (the F01.1 connect flow, already supporting MS) the same preview renders their Outlook busy blocks through the full Angular → Spring → Graph(stub) round-trip. The event-write path stays system-only (F13-triggered), delivered as the capability + verified end-to-end against `StubGraphCalendar`; the **mixed-panel** capability (US4) is verified by an integration test driving both stubs. Frontend change is minimal: ensure the connections page offers Microsoft connect (F01.1) and the preview/labels are provider-neutral (not Google-hardcoded); adjust the Jasmine/Playwright specs to cover a Microsoft connection.

**Rationale**: Honours §II without rebuilding a booking UI (F13/F14). The preview already exercises the new adapter; no new endpoint is needed.

**Alternatives rejected**: a new MS-specific endpoint (the preview is already provider-agnostic); shipping F11 as a backend-only adapter (risks the §II "backend-only as done" prohibition). Rejected.

---

## D12 — Audit, logging & PII discipline (reuse F10's controls)

**Decision**: Reuse the existing `AuthEventType` values `CALENDAR_EVENT_CREATED/UPDATED/DELETED/CLEANUP_INCOMPLETE` and `CALENDAR_RECONNECT_REQUIRED` — **no new enum** (so the logstash-encoder enum→`kv` Jackson-3 crash foot-gun adds no new surface; the MS client logs only `provider.name()`/ids as Strings, never an enum to `kv`). Never log: the token/auth code/secret (FR-021), the member SMTP/UPN used in `schedules` or any attendee email (FR-025), the event subject/location (FR-022), or the Graph response/error body verbatim — only `status` + `error.code` + classified outcome (FR-023). `EventDetails.toString()` already redacts title/location (reused). `ci.yml`'s PII scan already covers the five event-content sentinel categories from F10; **add a base-URL literal guard** banning a `graph.microsoft.com` literal in `MicrosoftCalendarClient.java` (mirrors the F10 `googleapis.com` guard, so tests can't hit real Graph). The SC-003 TRACE scan is extended to drive the Microsoft preview/create/update/delete/retry paths.

**Rationale**: Carries every F10/F01.1 security guarantee forward; the only new PII surface is the getSchedule `schedules` SMTP and the Graph error body, both explicitly closed.

**Alternatives rejected**: logging the Graph error body for debuggability (leaks ids/emails — FR-023); a new audit enum (unnecessary; reuse keeps the foot-gun surface flat). Rejected.

---

## D13 — Storage, indexes, migration: **reuse** `managedCalendarEvents` / Mongock `007` (no new changeset)

**Decision**: F11 adds **no** collection and **no** Mongock changeset. It reuses `managedCalendarEvents` and `ChangeUnit007`'s unique index `{workspaceId,bookingRef,memberId,provider}` + non-unique `{workspaceId,bookingRef}`. Verified index-safe: `provider` is the 4th key of the unique index and `ManagedCalendarEvent.provider` is an always-populated `CalendarProvider` enum, so a `MICROSOFT` row and a `GOOGLE` row for the same `(workspace,booking,member)` cannot collide (no `@Field(write=NON_NULL)` partial-index foot-gun — the field is never null). The `providerEventId` for Microsoft is the **server-assigned** Graph id (opaque, not PII/secret) → still no `PiiStringConverter` needed.

**Rationale**: The F10 schema was deliberately built provider-discriminated for exactly this reuse; adding a changeset would be redundant.

**Alternatives rejected**: a new `microsoftCalendarEvents` collection (fragments the cross-provider rollback enumeration); a new index (the existing one already discriminates on provider). Rejected.

---

## D14 — No new dependency, service, topology, or stack change

**Decision**: Graph HTTP via `RestClient` (spring-web, on `JdkClientHttpRequestFactory` for PATCH); token/crypto/audit reused from F01.1; provider stubbed by the JDK `HttpServer`. No Microsoft Graph SDK, no broker/cache/replica, no new collection. The MS gateway (`MicrosoftOAuthGateway`) already exists (F01.1). **Zero new runtime dependency** (C4); **no new service/queue/replica** (C2); **no tool downloads** (C7).

---

## Resolved unknowns summary

| Unknown (Technical Context) | Resolved by |
|---|---|
| Write events on Graph without over-broad scope? | D1 — `Calendars.ReadWrite` (+ `openid profile email`), §VIII justified (broader than F10, no narrower Graph scope exists) |
| Read free/busy without ingesting content? | D2 — getSchedule, parse `scheduleItems.{start,end,status}` only; control = parse-discipline + SC-004 (getSchedule self-mailbox can carry content) |
| How does getSchedule know which mailbox? | D2a — member SMTP/UPN from `providerAccountId` (now populated via `openid`); null → needs-reconnection |
| `tentative`/`oof`/`unknown` mapping? | D3 — any non-`free` → busy (fail safe); F10 model unchanged |
| Windows-vs-IANA time zones / DST? | D4 — read in UTC; write IANA dateTimeTimeZone (Graph accepts IANA) |
| Idempotent create when Graph assigns the id? | D5 — unique-claim (primary) + `transactionId` (Graph dedup); record/address the **server** id; interface refactor |
| Graph error shape / classification? | D6 — provider-aware; `403`→reconnect (Graph throttling is `429`, not `403`) |
| `Retry-After`? | D7 — honour on `429`/`503`, both header forms; `max(backoff, retryAfter)` |
| Provider abstraction without an SDK? | D8 — `MicrosoftCalendarClient` impl, `RestClient` |
| Mixed-provider panel + cross-provider rollback? | D9 — by construction (provider-keyed map + `created[]` loop) |
| Test stub without cloud creds / WireMock? | D10 — `StubGraphCalendar` JDK `HttpServer` sibling |
| Constitution §II leg? | D11 — reuse the provider-agnostic self preview |
| Audit/log/PII discipline? | D12 — reuse F10 controls + `graph.microsoft.com` base-URL guard |
| Storage/indexes/migration? | D13 — reuse `managedCalendarEvents` / Mongock `007` (no new changeset) |
| New dependency/service/topology? | D14 — none |
