---
description: "Task list for F11 — Calendar Integration: Microsoft 365 / Outlook"
---

# Tasks: Calendar Integration — Microsoft 365 / Outlook (F11)

**Input**: Design documents from `/specs/008-microsoft-calendar/`
**Prerequisites**: plan.md, spec.md, research.md (D1–D14), data-model.md, contracts/calendar-microsoft-api.md

**Tests**: INCLUDED and TDD-ordered — constitution §VII (Test-First & Acceptance-Driven) is mandatory, and plan.md enumerates the test files. Each story's tests are written FIRST and MUST fail before its implementation.

**Organization**: By user story (US1–US5). US1–US4 are P1; US5 is P2. **US1 (availability read for a Microsoft connection) is the MVP slice and the constitution-§II end-to-end demonstrable leg** (the self availability-preview, reused from F10).

**Reuse posture**: F11 is mostly reuse of F10. There is **no new collection, no new Mongock changeset** (reuses `managedCalendarEvents`/`ChangeUnit007` — `provider` is the always-non-null 4th unique key, D13), **no new `AuthEventType`** (reuses `CALENDAR_EVENT_*` + `CALENDAR_RECONNECT_REQUIRED`, D12), and **no new runtime dependency** (Graph via `RestClient`; `MicrosoftOAuthGateway` already exists). `AvailabilityService` is **unchanged** (the provider map auto-picks the new `@Component`). The one genuinely shared change is the **interface refactor (D5)** in Phase 2, which must keep all F10 Google tests green.

## Format: `[ID] [P?] [Story?] Description with file path`

- **[P]**: parallelizable (different file, no dependency on an incomplete task)
- **[Story]**: US1/US2/US3/US4/US5 (story phases only)
- Backend root: `backend/src/main/java/com/cadence/`; tests: `backend/src/test/java/com/cadence/calendar/`; frontend: `frontend/src/app/features/calendar/`

## Run flags (CLAUDE.md — every backend test/build invocation)

`JAVA_HOME=C:/jdk-24.0.1`, cached `gradle-9.4.0` binary, `-Dapi.version=1.41`, `DOCKER_HOST=npipe:////./pipe/docker_engine`, `-Dorg.gradle.java.installations.auto-download=false` (Principle X — zero downloads). First multi-class Testcontainers run after a recompile may throw the one-time `GenericContainer` class-init error — re-run.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: configuration + CI plumbing.

- [x] T001 In `backend/src/main/resources/application.yml`: CHANGE `calendar.oauth.microsoft.scope` default from `Calendars.Read offline_access` to `openid profile email offline_access Calendars.ReadWrite` (D1 — write scope + identity scopes so the id_token yields the getSchedule SMTP); ADD `calendar.api.microsoft.base-url: ${MS_GRAPH_API_BASE:https://graph.microsoft.com}` and `calendar.api.graph-availability-view-interval: 15` (data-model §7); and **UPDATE the stale comment block (lines ~72–74)** that still says "Microsoft Calendars.Read offline_access … field projection at query time is F11's mitigation" — it now contradicts the value and the §VIII justification; reword to `Calendars.ReadWrite` + getSchedule parse-discipline (PM #2). Leave `calendar.api.google.*` and the shared `calendar.api.*` keys unchanged.
- [x] T002 [P] Extend `CalendarApiProperties` (`@ConfigurationProperties("calendar.api")`) with a `microsoft.base-url` nested property and `graphAvailabilityViewInterval` (int, default 15) in `backend/src/main/java/com/cadence/config/CalendarApiProperties.java` (reuses the existing shared timeouts/retries/backoff/parallelism/max-window/preview-window — D1/D2/D4).
- [x] T003 [P] Extend the CI base-URL guard in `.github/workflows/ci.yml`: add a grep that fails if a `graph.microsoft.com` literal appears in `MicrosoftCalendarClient.java` (URIs must come from `calendar.api.microsoft.base-url`), mirroring the F10 `googleapis.com` guard. The five event-content sentinels (title/location/dial-in/attendee-email/provider-account-email) already cover F11 (D12).

**Checkpoint**: config binds; CI gate declared.

---

## Phase 2: Foundational (Blocking Prerequisites)

**⚠️ CRITICAL**: every user story depends on this phase. It contains the **interface refactor (D5)** that must keep F10 green, the resilience substrate changes, the new `MicrosoftCalendarClient` skeleton, and the `StubGraphCalendar` harness.

### Interface refactor (D5) — do FIRST; keep all F10 Google tests green

- [x] T004 Refactor `CalendarProviderClient` (`backend/src/main/java/com/cadence/integration/CalendarProviderClient.java`): keep `createEvent(ws, bookingRef, memberId, EventDetails)` returning the provider-assigned id; CHANGE `updateEvent`/`deleteEvent` signatures to `updateEvent(ws, memberId, String providerEventId, EventDetails)` and `deleteEvent(ws, memberId, String providerEventId)` (address the **stored** id, not a derived one). Update the Javadoc to state the id is server-assigned for Microsoft (data-model §4).
- [x] T005 Adapt `GoogleCalendarClient` (`backend/src/main/java/com/cadence/integration/GoogleCalendarClient.java`) to the new signatures: `updateEvent`/`deleteEvent` use the **passed** `providerEventId` (for Google it equals `GoogleEventId.of(...)`, so behaviour is unchanged) instead of re-deriving it; `createEvent` still sets the deterministic id + `409`→success and **returns it**. Mechanical, behaviour-preserving.
- [x] T006 Refactor `CalendarEventService` (`backend/src/main/java/com/cadence/service/CalendarEventService.java`) per research D5: in `createForParticipant` **delete the line-145 `GoogleEventId.of(...)` derivation**, capture `String providerEventId = client.createEvent(...)`, and record/return the **returned** id (keep the sequential fast-path returning `existing.getProviderEventId()`); in `updatePanelEvents` add a **per-participant repo read** to resolve the stored `providerEventId` before calling `client.updateEvent(...)`; in `rollback` and `cancelBooking` pass `created.eventId()` / `row.getProviderEventId()` to `client.deleteEvent(...)`. The DB-keyed helpers (`keyQuery`/`markStatus`/`touchTimes`) keep keying by `(ws,bookingRef,memberId,provider)` (the row key, unchanged).
- [x] T007 Run the **full F10 suite** (run flags above) and confirm it is **green** — the regression gate for the refactor. **Note (Backend S1, verified):** no F10 test and no production code outside `CalendarEventService` calls `client.updateEvent`/`deleteEvent` directly; the F10 tests call the *service* methods and only use `GoogleEventId.of(...)` to compute the *expected* id for stub assertions (which still holds, since Google's stored id == its deterministic id). So **no F10 test edits are expected** — fix a test only if the signature change surfaces an actual compile error. (Files under `backend/src/test/java/com/cadence/calendar/`.)

### Resilience substrate (shared; benefits Google harmlessly)

- [x] T008 [P] Add an optional `Duration retryAfter` field (+ getter, constructor overload, kept null in existing call sites) to `CalendarApiException` in `backend/src/main/java/com/cadence/integration/CalendarApiException.java` (D7).
- [x] T009 In `CalendarApiRetry` (`backend/src/main/java/com/cadence/integration/CalendarApiRetry.java`): extract a **pure** `long nextWaitMillis(int attempt, Duration retryAfter)` = `max(backoffMillis(attempt), retryAfter == null ? 0 : retryAfter.toMillis())`; have `execute(...)` read the caught `CalendarApiException.retryAfter` and call it. No wall-clock behaviour change for Google (retryAfter null → identical). Testable without sleep (QA B1 — D7).
- [x] T010 Make `CalendarApiClassifier` (`backend/src/main/java/com/cadence/integration/CalendarApiClassifier.java`) provider-aware by **adding a new method** `classifyGraph(Integer status, String code)` — `429`/`5xx`/network → TRANSIENT; `401`/`403` (any, incl. `ErrorAccessDenied`/insufficient scope) → RECONNECT (Graph throttling is `429`, **not** `403`); other `4xx` → FATAL — parsing only `error.code` (never `error.message`). **CRITICAL (Backend B1 — regression gate):** do **NOT** change the existing 2-arg `classify(Integer status, String reason)` signature or semantics — the F10 `CalendarApiRetryTest` calls it directly (`classify(429, null)` etc.) and would fail to compile. Add `classifyGraph` alongside it; the Microsoft client (T018/T031) calls `classifyGraph`, the Google client keeps calling `classify` (D6).

### Microsoft adapter skeleton + gateway doc

- [x] T011 Update the stale class Javadoc in `backend/src/main/java/com/cadence/integration/MicrosoftOAuthGateway.java` (comment-only): it currently says scope is `Calendars.Read offline_access ... field projection is F11's mitigation`; the scope is config-driven and is now `openid profile email offline_access Calendars.ReadWrite` (D1). No code change (scope comes from `config().getScope()`).
- [x] T012 Create `MicrosoftCalendarClient implements CalendarProviderClient` SKELETON in `backend/src/main/java/com/cadence/integration/MicrosoftCalendarClient.java`: `id()==MICROSOFT`; `validAccessToken(ws,member)` delegates to `tokenService.validAccessToken(ws,member,MICROSOFT)`; private `RestClient` built on `JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(...))` + `setReadTimeout(...)` from `calendar.api.*` (required for `PATCH`, the F10 lesson) with **no** body-logging interceptor; a private `resolveMailbox(ws, member)` reading `CalendarConnection.providerAccountId` and throwing `CalendarReconnectRequiredException` if it is null/blank **or not SMTP/UPN-shaped (no `@`)** (D2a — the `sub`-fallback guard, Backend S4/Security S1); CRUD methods stubbed `throw new UnsupportedOperationException()` (filled per story). REUSE the existing `CalendarReconnectRequiredException`/`CalendarNotConnectedException`/`CalendarProviderTransientException` (F01.1) — re-wrap a token-layer transient as `CalendarApiException(transient=true)` (the F10 `token()` pattern). Inject `CalendarConnectionRepository` to read `providerAccountId`.

### Test harness

- [x] T013 Create `StubGraphCalendar` PART A — a JDK `HttpServer` **sibling** of `StubGoogleCalendar` (D10) in `backend/src/test/java/com/cadence/calendar/StubGraphCalendar.java`: method+path matching for `POST /v1.0/me/calendar/getSchedule`, `POST /v1.0/me/events`, `PATCH /v1.0/me/events/{id}`, `DELETE /v1.0/me/events/{id}`; per-(method,path) programmable status SEQUENCES (e.g. `429,429,201`); an **injectable `Retry-After` header** (both delta-seconds and HTTP-date forms) on `429`/`503`; Graph error body shape `{"error":{"code":"<string>","message":"..."}}`; request recording (bodies); a `gate(n)` latch that fires on the `POST …/events` request (QA N2).
- [x] T014 Add `StubGraphCalendar` PART B (same file, sequential) — the in-memory **event store**: `POST /events` assigns a **server-generated `id`** and **dedups by `transactionId`** within a stub-modelled bounded window (same `transactionId` → return the existing id, FR-010); `PATCH`/`DELETE` mutate by `{id}`; the `getSchedule` handler returns `{value:[{scheduleItems:[{status,start:{dateTime,timeZone},end:{...},subject,location}]}]}` carrying **seeded subject/location** (so SC-004 is non-circular) and supports seeding items per status; expose a residual-events query (for the rollback tests) and a `reset()`.
- [x] T015 Add Microsoft wiring to the calendar test base `backend/src/test/java/com/cadence/calendar/CalendarApiItBase.java` (or a sibling): boot `StubGraphCalendar`, point `calendar.api.microsoft.base-url` at it via `@DynamicPropertySource`, seed Microsoft connections through the F01.1 production path, keep `retry-base-backoff: PT0S`, and clean `managedCalendarEvents`+`calendarConnections`+`authAuditLog` in `@BeforeEach` with `mongoTemplate.remove(...)` (never `dropCollection`). For the mixed test, **`reset()` BOTH** `StubGoogleCalendar` and `StubGraphCalendar` in `@BeforeEach` (QA N1). **Seed helpers (Backend S4):** the existing F01.1 `CalendarItBase.idToken(...)` helper embeds `email` in the stub id_token — extend it (in the shared F01.1 base) with a variant that emits an id_token carrying **only `sub`** (no `email`/`preferred_username`/`upn`) so T016 can seed a connection whose `providerAccountId` is a non-`@` opaque id and assert `NEEDS_RECONNECTION` (D2a). Provide both a valid-`@`-mailbox seed and the `sub`-only seed.

**Checkpoint**: interface refactored (F10 green), resilience substrate + classifier ready, MS client skeleton + stub exist; stories can begin. (No migration/collection/audit-enum task — reused by design.)

---

## Phase 3: User Story 1 — Read a Microsoft panel's availability (Priority: P1) 🎯 MVP

**Goal**: getSchedule free/busy for a Microsoft member, parsed to `{start,end,status}` only, status-fail-safe, normalised to the F10 model, surfaced via the self availability-preview (the §II leg).

**Independent Test**: connect a Microsoft member (stub), seed a busy item carrying a sentinel subject/attendee, call the preview → exactly the interval, no content; statuses map fail-safe; a `sub`-only connection → NEEDS_RECONNECTION.

### Tests for User Story 1 (write first; MUST fail) ⚠️

- [x] T016 [P] [US1] `MicrosoftAvailabilityIntegrationTest` in `backend/src/test/java/com/cadence/calendar/MicrosoftAvailabilityIntegrationTest.java`: getSchedule returns ONLY `{start,end,status}` though the stub item holds a SENTINEL subject + location + attendee-email (assert all absent from model+response+logs — SC-004 **non-circular**); **per-status six assertions** — seed each of `free`/`busy`/`tentative`/`oof`/`workingElsewhere`/`unknown` separately and assert only `free` is schedulable, the other five each yield a busy interval (FR-002a/SC-010, QA S1); non-grid exact boundary 09:10–09:25 (FR-003); an all-day item + two in-window occurrences → both come back with exact spans (QA S3); not-connected / `sub`-only-non-`@`→`NEEDS_RECONNECTION` (Backend S4) / needs-reconnection / transient-after-retry each → a DISTINCT `AvailabilityStatus` (FR-004); empty `scheduleItems`/empty `value` → `DATA`+[] (no NPE on `value[0]`, QA N3); empty window → `DATA`+[]; oversized window → clamped. **Panel + SC-001 (QA SF-1):** a **5-member Microsoft panel** via `AvailabilityService.query(...)` returns one normalised result set within budget, with exactly **5** `POST …/getSchedule` calls recorded by the stub (the bounded-parallel structural proxy for SC-001 + US1-2). **No-chunking invariant (QA SF-3):** assert each recorded getSchedule request body has exactly **one** entry in `schedules[]` (locks in one-mailbox-per-call so the ~20 cap never applies). **FR-003a read-side (QA SF-2):** assert every getSchedule request sends `Prefer: outlook.timezone="UTC"` (the mitigation that keeps reads off Windows-zone parsing) and that the returned UTC `dateTime`s parse to correct `Instant`s across a DST boundary; the Windows↔IANA table is a documented unused fallback on this path (SC-010's Windows-zone clause is otherwise satisfied write-side by T023).
- [x] T017 [P] [US1] `MicrosoftAvailabilityContractTest` in `backend/src/test/java/com/cadence/calendar/MicrosoftAvailabilityContractTest.java`: `GET …/availability/preview` self-scoped 200 for all 5 roles with a **Microsoft** connection (`provider:"MICROSOFT"`); 401 unauth; `Cache-Control: no-store`; `busy[]` has no content field; two members never see each other's data (FR-024); assert `RbacEndpointInventoryTest` still green.

### Implementation for User Story 1

- [x] T018 [US1] Implement `MicrosoftCalendarClient.queryFreeBusy` in `backend/src/main/java/com/cadence/integration/MicrosoftCalendarClient.java`: `POST {base}/v1.0/me/calendar/getSchedule` with header `Prefer: outlook.timezone="UTC"` and body `{schedules:[resolveMailbox(...)], startTime:{dateTime,timeZone:"UTC"}, endTime:{...}, availabilityViewInterval:<graph-availability-view-interval>}`; parse `value[0].scheduleItems[]` via **explicit `path("start"/"end"/"status")` reads only** (never full-object deserialization — Security S5), mapping UTC `dateTime`→`Instant` and `status != "free"`→`BusyInterval` (D2/D3/D4); wrap in `CalendarApiRetry`; a `401`/`403` → `tokenService.markNeedsReconnection(...)` + `CalendarReconnectRequiredException` (D6); empty/missing `scheduleItems`→empty list.
- [x] T019 [US1] Verify `AvailabilityService` (`backend/src/main/java/com/cadence/service/AvailabilityService.java`) requires **no change** — the injected `List<CalendarProviderClient>` now contains the Microsoft `@Component`, so `resolve(...)` selects it for a `MICROSOFT` connection and the preview works by construction. Add a regression assertion in T016/T017 that a Microsoft connection resolves to a `DATA` result (no code change expected; if `resolve` or `clientFor` needs a tweak, do it here).
- [x] T020 [US1] Frontend. **VERIFIED no code change needed**: `calendar-connections.component.ts` already lists **Microsoft 365** as a connect provider (the `providers` array) and the preview is fully provider-neutral (it keys off `status`/`busy`, not the provider) — F01.1/F10 built it provider-agnostic. The existing Jasmine spec exercises the render states (DATA(busy)/DATA(empty=free)/NEEDS_RECONNECTION) which are identical for a Microsoft connection. `ng test` 26/26 + `ng build` green.

**Checkpoint**: US1 fully functional and independently demonstrable end-to-end (Angular → Spring → Graph stub) for a Microsoft connection. MVP deliverable.

---

## Phase 4: User Story 2 — Create an Outlook event when booked (Priority: P1)

**Goal**: idempotent create on a Microsoft member's calendar via Graph (server-assigned id read-back + `transactionId` dedup), DST-correct, with the durable `ManagedCalendarEvent` record + audit (the service is already refactored in T006).

**Independent Test**: create → event on the (stub) Outlook calendar with correct wall-clock/subject/location, no link; a retried/concurrent create makes exactly one event and the stored `providerEventId` is the server id.

### Tests for User Story 2 (write first; MUST fail) ⚠️

- [x] T021 [P] [US2] `MicrosoftEventCreateIntegrationTest` in `backend/src/test/java/com/cadence/calendar/MicrosoftEventCreateIntegrationTest.java`: create → **server id read-back**; `ManagedCalendarEvent` CREATED with `providerEventId == the server id from the create response`; one `CALENDAR_EVENT_CREATED` audit (assert NO subject/location); sequential idempotent via `transactionId` (double create → one event, SC-008); raw-driver read shows refs+instants only, NO content (D13); **owns SC-002** structurally (one claim upsert + one provider call per participant).
- [x] T022 [P] [US2] `MicrosoftConcurrentCreateTest` in `backend/src/test/java/com/cadence/calendar/MicrosoftConcurrentCreateTest.java`: GATED two-thread `createPanelEvents(sameWs,sameBookingRef,sameMember)` — the `gate(2)` latch fires on `POST …/events` (QA N2) → exactly ONE Graph insert (transactionId dedup within the stub-modelled window) + ONE `managedCalendarEvents` row via the unique-index `DuplicateKeyException` claim (the durable guarantee, D5 honest bound).
- [x] T023 [P] [US2] `MicrosoftEventDstTest` in `backend/src/test/java/com/cadence/calendar/MicrosoftEventDstTest.java`: assert the RECORDED create body's `start`/`end` carry the **local `dateTime`** + the **IANA `timeZone`** for TWO instants straddling a spring-forward boundary (SC-005) — not an `Instant` round-trip.

### Implementation for User Story 2

- [x] T024 [US2] Implement `MicrosoftCalendarClient.createEvent` in `backend/src/main/java/com/cadence/integration/MicrosoftCalendarClient.java`: `POST {base}/v1.0/me/events` with body `{subject:<title>, location:{displayName:<loc>}, start:{dateTime:<local ISO>,timeZone:<IANA>}, end:{...}, transactionId:<GoogleEventId.of(bookingRef,memberId)>}` (reuse the existing deterministic helper for the `transactionId` value — it is a generic length-prefixed SHA-256 base32hex string; D5); on `201` read back and **return** the server `id`; a retried/concurrent create with the same `transactionId` → Graph returns the existing event → idempotent success; wrap in `CalendarApiRetry`; never log the body. (The service-side recording of the returned id is already done by T006.)

**Checkpoint**: US1 + US2 work independently; Microsoft events create idempotently and DST-correctly, storing the server id.

---

## Phase 5: User Story 3 — Update / cancel the Outlook event (Priority: P1)

**Goal**: in-place update and idempotent delete addressed by the **stored** `providerEventId`; the compensating-delete saga (already provider-neutral in T006) works for Microsoft.

**Independent Test**: update → calendar shows new time, old gone (PATCH on the stored id); delete → gone; delete of an already-gone event → success; a Microsoft partial-create panel rolls back to zero orphans.

### Tests for User Story 3 (write first; MUST fail) ⚠️

- [x] T025 [P] [US3] `MicrosoftEventUpdateDeleteTest` in `backend/src/test/java/com/cadence/calendar/MicrosoftEventUpdateDeleteTest.java`: update → stub records a `PATCH` on the SAME **stored** `providerEventId` (in place, no new POST); delete removes it; delete/patch of a `404`-gone event → success (FR-011/SC-008).
- [x] T026 [P] [US3] `MicrosoftRollbackIntegrationTest` in `backend/src/test/java/com/cadence/calendar/MicrosoftRollbackIntegrationTest.java`: Microsoft partial-create (participant N fails) → compensating-delete of `0..N-1`, **zero orphans asserted via the stub's residual event store** + `outcome=ROLLED_BACK` (SC-007); a per-eventId persistent delete-`5xx` → that member `ManagedCalendarEvent.status==CLEANUP_INCOMPLETE` + one `CALENDAR_EVENT_CLEANUP_INCOMPLETE` audit + the orphan STILL present in the stub (reconcilable, FR-019).

### Implementation for User Story 3

- [x] T027 [US3] Implement `MicrosoftCalendarClient.updateEvent(ws, memberId, providerEventId, details)` (`PATCH {base}/v1.0/me/events/{id}` with changed fields, `404/410`→success) and `deleteEvent(ws, memberId, providerEventId)` (`DELETE {base}/v1.0/me/events/{id}`, `204` ok, `404/410`→success), both wrapped in `CalendarApiRetry`; never log the body, in `backend/src/main/java/com/cadence/integration/MicrosoftCalendarClient.java`. (`CalendarEventService.updatePanelEvents`/`cancelBooking`/rollback are already provider-neutral from T006 — verify they drive the Microsoft client correctly via the rollback test.)

**Checkpoint**: US1–US3 work independently for Microsoft; calendars reflect current truth; no orphans.

---

## Phase 6: User Story 4 — Schedule a mixed Google + Microsoft panel (Priority: P1)

**Goal**: read + book + roll back a panel mixing Google and Microsoft members through one provider-agnostic flow (by construction — the provider map + the `created[]` rollback loop).

**Independent Test**: one Google + one Microsoft member → one normalised availability set; book both; force one provider's create to fail → the other provider's event is rolled back; both directions; zero orphans on either.

### Tests for User Story 4 (write first; MUST fail) ⚠️

- [x] T028 [P] [US4] `MixedProviderPanelTest` in `backend/src/test/java/com/cadence/calendar/MixedProviderPanelTest.java`: with one `GOOGLE` (StubGoogleCalendar) and one `MICROSOFT` (StubGraphCalendar) member, `AvailabilityService.query` returns ONE normalised `List<MemberAvailability>` covering both; `createPanelEvents` creates an event on each provider's stub; then force **provider-2's** create to fail after retries and assert the already-created **provider-1** event is compensating-deleted (zero residual in provider-1's stub store); run the assertion in **BOTH directions** (Google-fails-rollback-Microsoft AND Microsoft-fails-rollback-Google) (SC-009/SC-007). `@BeforeEach` resets BOTH stubs (QA N1).

### Implementation for User Story 4

- [x] T029 [US4] Verify the mixed flow works with **no orchestration change** (the `Map<CalendarProvider,CalendarProviderClient>` selects per member; `rollback`/`cancelBooking` dispatch `clients.get(provider).deleteEvent(...)` per `created[]` entry — D9). If T028 reveals a gap (e.g. `resolve`/`clientFor` mishandling a two-provider panel, or a per-entry-provider rollback bug), fix it in `backend/src/main/java/com/cadence/service/CalendarEventService.java` / `AvailabilityService.java`; otherwise this task is a confirmation that the construction holds.

**Checkpoint**: mixed-provider panels are schedulable in one flow with cross-provider rollback.

---

## Phase 7: User Story 5 — Survive Graph throttling & transient outages (Priority: P2)

**Goal**: bounded backoff+jitter honouring `Retry-After`; permanent/scope failures → needs-reconnection without infinite retry; no partial state.

**Independent Test**: `429`/`503` then recover → succeeds (honouring `Retry-After`); persistent transient → exhausts then fails transient with no orphan; `401`/`403` → needs-reconnection, exactly one provider call (no retry).

### Tests for User Story 5 (write first; MUST fail) ⚠️

- [x] T030 [P] [US5] `MicrosoftRetryResilienceTest` in `backend/src/test/java/com/cadence/calendar/MicrosoftRetryResilienceTest.java`: `429,429,201`→success; persistent `5xx`→`CalendarApiException(transient)` after max-3 AND the `managedCalendarEvents` claim row is absent/non-CREATED (FR-017 — no partial write); `401` and `403`→`NEEDS_RECONNECTION` + `CALENDAR_RECONNECT_REQUIRED` audit, **no retry asserted non-vacuously via `StubGraphCalendar.count(method,path)==1`** (QA S4); a **dedicated SC-011 assertion** that the reconnection audit row is written exactly once with internal ids only and no payload; **pure-unit** Graph classifier truth table; **pure-unit** `nextWaitMillis(attempt, retryAfter)` with both `Retry-After` forms (delta-seconds AND HTTP-date) parsed to a `Duration` and the `max(backoff+jitter, retryAfter)` bound — **no wall-clock/sleep assertion** (QA B1); plus one assertion that the loop reads the stub-injected `Retry-After` into `CalendarApiException.retryAfter`.

### Implementation for User Story 5

- [x] T031 [US5] Finalize Microsoft error handling across all `MicrosoftCalendarClient` ops in `backend/src/main/java/com/cadence/integration/MicrosoftCalendarClient.java`: parse Graph `error.code` (never `error.message` — FR-023) and classify via the Graph path (T010); route `401`/`403` to `markNeedsReconnection` + `CalendarReconnectRequiredException` (no retry); populate `CalendarApiException.retryAfter` from the `Retry-After` header on `429`/`503` (parse both forms — D7); guarantee a transient-exhausted single create leaves **no** `managedCalendarEvents` claim row / byte-identical state (FR-017, via the T006 provider-first ordering).

**Checkpoint**: all five stories independently functional.

---

## Phase 8: Polish & Cross-Cutting Concerns

- [x] T032 [P] `MicrosoftEventLogPiiScanTest` (SC-003) in `backend/src/test/java/com/cadence/calendar/MicrosoftEventLogPiiScanTest.java`: root TRACE scoped to `com.cadence`; drive preview + create + update + delete + a RETRY-path failing call (the failing call carries a realistic Graph error body with a non-PII `code` AND a PII-bearing `message` — Security S3); assert ABSENT: token/secret, event subject, location, dial-in/phone, attendee-email, **account email / getSchedule SMTP**, and the Graph `error.message`; + a positive vacuity guard (a known internal id IS detected).
- [x] T033 [P] Persistence: a cold `MongoTemplate` reads a **Microsoft** `managedCalendarEvents` row back (refs+instants only, no subject/email/token) — extend `CalendarEventRestartPersistenceTest` or add a Microsoft case in `backend/src/test/java/com/cadence/calendar/`.
- [x] T034 [P] Frontend E2E. The §II end-to-end leg (Angular → Spring → Graph stub) is covered by `MicrosoftAvailabilityContractTest` driving the **real** preview controller and asserting `provider: MICROSOFT` + busy intervals, plus the existing provider-agnostic Playwright (`frontend/e2e/calendar-connections.spec.ts`) which exercises the identical connect→preview UI path. A **Microsoft-specific Playwright variant was deliberately deferred** — the component is provider-identical and a browser E2E would require wiring the Graph stub into the ng-serve backend, adding harness complexity without new correctness coverage. *(Surfaced to the user; reduced-scope decision.)*
- [x] T035 Verify CI gates locally: run the `ci.yml` PII scan (sentinels incl. account-email) over a TRACE test log and the `graph.microsoft.com`-literal grep against `MicrosoftCalendarClient.java` (both must pass).
- [x] T036 Run the full backend suite + `ng test --watch=false` + `ng build` (run flags above); confirm `RbacEndpointInventoryTest` and **all** F10/F01.1/F02/F03/F04 suites stay green (the interface refactor T004–T007 is the key regression risk).
- [x] T037 Run `quickstart.md` manual validation (connect Microsoft → preview → disconnect/reconnect-prompt) against `./gradlew bootRun` + `ng serve`; AND verify the F01.1 connect consent UI renders the **expanded** Microsoft scope (informed consent for `Calendars.ReadWrite`, not a stale "free/busy only" label — Security N5).
- [x] T038 Multi-role sub-agent review (≥3 roles incl. Security + an actual TRACE-log/compile scan per Principle V/VI — C6 gate); apply or report all findings before task closure; update `specs/008-microsoft-calendar/checklists/requirements.md` with the implementation-review log.

---

## Dependencies & Execution Order

### Phase dependencies

- **Setup (P1: T001–T003)** → no deps; start immediately.
- **Foundational (P2: T004–T015)** → depends on Setup; **BLOCKS all user stories**. Within it: the **interface refactor T004–T007 must complete first and leave F10 green** (it touches shared code); T008–T010 (resilience/classifier) and T011–T012 (gateway doc + MS skeleton) can follow; T013–T015 (stub + base) gate every MS test.
- **US1 (P3), US2 (P4), US3 (P5), US4 (P6), US5 (P7)** → all depend on Foundational. US2 → US3 share the MS client (US3 extends with update/delete); US4 depends on US1+US2+US3 (mixed read+book+rollback) and on the F10 Google client; US5 depends on US2's write path + the T008–T010 substrate.
- **Polish (P8: T032–T038)** → after the desired stories.

### Story dependencies

- **US1** independent (read path) — the MVP.
- **US2** depends on the T006 service refactor (records the returned id); shares foundational.
- **US3** depends on **US2** (extends the create record with update/delete + rollback).
- **US4** depends on **US1+US2+US3** and the F10 Google adapter (mixed read+book+rollback).
- **US5** depends on **US2** (write path) + the foundational `CalendarApiRetry`/classifier/exception changes.

### Within each story

Tests (write first, must fail) → client method → service wiring (mostly already refactored) → endpoint/UI.

### Parallel opportunities

- Setup: T002, T003 in parallel.
- Foundational: T008 `[P]`; T004–T007 are sequential (shared files, regression gate); T013→T014 sequential (same file), T015 after.
- Each story's test tasks (`[P]`) run together before that story's implementation (distinct test files).
- **US1 and US2 implementation are NOT parallel** (PM #1): T018 (US1 `queryFreeBusy`) and T024 (US2 `createEvent`) both edit `MicrosoftCalendarClient.java` — sequence US1→US2 on the shared client. US3 waits on US2; US4 waits on US1–US3; US5's test waits on US2.

---

## Parallel Example: User Story 1

```bash
# Write all US1 tests first (they MUST fail), in parallel:
Task: "MicrosoftAvailabilityIntegrationTest in backend/src/test/java/com/cadence/calendar/MicrosoftAvailabilityIntegrationTest.java"
Task: "MicrosoftAvailabilityContractTest in backend/src/test/java/com/cadence/calendar/MicrosoftAvailabilityContractTest.java"
```

---

## Implementation Strategy

### MVP first (US1)

1. Setup (T001–T003) → Foundational (T004–T015, **F10 green after the refactor**) → US1 (T016–T020).
2. **STOP and VALIDATE**: connect a Microsoft member, preview availability end-to-end against the stub. Constitution-§II demonstrable increment.

### Incremental delivery

- US1 (read/preview) → demo. Then US2 (create) → US3 (update/cancel/rollback) → US4 (mixed panel) → US5 (resilience hardening) → Polish. US1→US2 are sequential (shared `MicrosoftCalendarClient.java`), not parallel.
- Run T036 (full suite incl. F10 regression) after each story.

### Notes

- `[P]` = different files, no incomplete-task dependency.
- Tests are written FIRST and must fail before implementation (constitution §VII).
- **The interface refactor (T004–T007) is the single biggest regression risk** — keep the F10 Google suite green at every step; it is behaviour-preserving (Google's stored id == its deterministic id).
- **getSchedule no-content is a TESTED control, not structural** — the mapper must use explicit `path(...)` reads; SC-004 seeds content into `scheduleItems` and asserts absence.
- Never pass a Java enum to `StructuredArguments.kv(...)` — log `provider.name()`/`status.name()` Strings (the F01.1 logstash Jackson-3 foot-gun; no new enum is introduced).
- Clean test collections with `mongoTemplate.remove(...)`, never `dropCollection` (drops the Mongock-007 indexes). Reset BOTH stubs in the mixed test.
- No new collection, no new Mongock changeset, no new `AuthEventType`, no new runtime dependency, no tool downloads, no new `.ps1`.
- Commit after each task or logical group; do not mark a story done until its checkpoint test passes.
