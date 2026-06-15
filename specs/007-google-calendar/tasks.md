---
description: "Task list for F10 — Calendar Integration: Google Calendar"
---

# Tasks: Calendar Integration — Google Calendar (F10)

**Input**: Design documents from `/specs/007-google-calendar/`
**Prerequisites**: plan.md, spec.md, research.md (D1–D14), data-model.md, contracts/calendar-google-api.md

**Tests**: INCLUDED and TDD-ordered — constitution §VII (Test-First & Acceptance-Driven) is mandatory for this codebase, and plan.md enumerates the test files. Each story's tests are written FIRST and MUST fail before its implementation.

**Organization**: By user story (US1–US4). US1/US2/US3 are P1; US4 is P2. US1 (availability read) is the MVP slice and the constitution-§II end-to-end demonstrable leg.

## Format: `[ID] [P?] [Story?] Description with file path`

- **[P]**: parallelizable (different file, no dependency on an incomplete task)
- **[Story]**: US1/US2/US3/US4 (story phases only)
- Backend root: `backend/src/main/java/com/cadence/`; tests: `backend/src/test/java/com/cadence/calendar/`; frontend: `frontend/src/app/features/calendar/`

## Run flags (CLAUDE.md — every backend test/build invocation)

`JAVA_HOME=C:/jdk-24.0.1`, cached `gradle-9.4.0` binary, `-Dapi.version=1.41`, `DOCKER_HOST=npipe:////./pipe/docker_engine`, `-Dorg.gradle.java.installations.auto-download=false` (Principle X — zero downloads). First multi-class Testcontainers run after a recompile may throw the one-time `GenericContainer` class-init error — re-run.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: configuration + CI plumbing the whole feature depends on.

- [x] T001 Add the `calendar.api.*` block (google.base-url, connect-timeout PT5S, read-timeout PT10S, max-retries 3, retry-base-backoff PT0.1S, freebusy-parallelism 8, max-window P60D, preview-window P7D) and CHANGE `calendar.oauth.google.scope` default to `https://www.googleapis.com/auth/calendar.events.owned https://www.googleapis.com/auth/calendar.freebusy` (D1 least-privilege; document `calendar.events` as the fallback) in `backend/src/main/resources/application.yml`
- [x] T002 [P] Create `CalendarApiProperties` (`@ConfigurationProperties("calendar.api")`) with the fields from T001 in `backend/src/main/java/com/cadence/config/CalendarApiProperties.java`; register via `@EnableConfigurationProperties` alongside the F01.1 `CalendarOAuthProperties`
- [x] T003 [P] Extend the CI PII/secret scan in `.github/workflows/ci.yml` with FIVE calendar event-content sentinels (event-title, location, dial-in/phone-number, attendee-email, provider-account-email) on top of the F01.1 token sentinels, AND a grep that fails if a `googleapis.com` literal appears in `GoogleCalendarClient.java` (URIs must come from `calendar.api.google.base-url`) — research D13/D14

**Checkpoint**: config binds; CI gates declared.

---

## Phase 2: Foundational (Blocking Prerequisites)

**⚠️ CRITICAL**: every user story depends on this phase. These are the shared adapter substrate, models, storage, and test harness.

### Domain models (provider-neutral; shared with F11)

- [x] T004 [P] Create `BusyInterval` record (`Instant start, Instant end`) in `backend/src/main/java/com/cadence/domain/BusyInterval.java`
- [x] T005 [P] Create `AvailabilityStatus` enum (`DATA, NOT_CONNECTED, NEEDS_RECONNECTION, TEMPORARILY_UNAVAILABLE`) in `backend/src/main/java/com/cadence/domain/AvailabilityStatus.java`
- [x] T006 [P] Create `MemberAvailability` record (`String memberId, AvailabilityStatus status, List<BusyInterval> busy`) in `backend/src/main/java/com/cadence/domain/MemberAvailability.java`
- [x] T007 [P] Create `EventDetails` record (`String title, String location, Instant startAt, Instant endAt, ZoneId timeZone`) WITH a **redacting `toString()`** that omits title/location (F03 secret-toString discipline, plan-review M3) in `backend/src/main/java/com/cadence/domain/EventDetails.java`
- [x] T008 [P] Create `EventStatus` enum (`CREATED, DELETED, CLEANUP_INCOMPLETE`) in `backend/src/main/java/com/cadence/domain/EventStatus.java`
- [x] T009 [P] Create `PanelBookingResult` + `MemberEventResult` records, `PanelOutcome`/`MemberOutcome` enums (data-model §2), AND the `Participant(String memberId, ZoneId timeZone)` input record (used by `CalendarEventService.createPanelEvents`/`updatePanelEvents`) in `backend/src/main/java/com/cadence/domain/PanelBookingResult.java` (Participant may be its own file `domain/Participant.java`)
- [x] T010 Append `CALENDAR_EVENT_CREATED, CALENDAR_EVENT_UPDATED, CALENDAR_EVENT_DELETED, CALENDAR_EVENT_CLEANUP_INCOMPLETE` to `backend/src/main/java/com/cadence/domain/AuthEventType.java` (append only — never renumber; FR-020 reconnection-occurrence reuses the existing `CALENDAR_RECONNECT_REQUIRED`)

### Storage + migration

- [x] T011 [P] Create `ManagedCalendarEvent` `@Document("managedCalendarEvents")` (workspaceId, bookingRef, memberId, provider, providerEventId, status, startAt, endAt, createdAt, updatedAt — refs+instants only, NO content/secret, NO converter; hand `toString()` content-free) in `backend/src/main/java/com/cadence/domain/ManagedCalendarEvent.java`
- [x] T012 Create `ManagedCalendarEventRepository` (findByWorkspaceIdAndBookingRef; findByWorkspaceIdAndBookingRefAndMemberIdAndProvider) in `backend/src/main/java/com/cadence/repository/ManagedCalendarEventRepository.java`
- [x] T013 Create `ChangeUnit007_ManagedCalendarEventIndexes` (order `"007"`): unique `{workspaceId,bookingRef,memberId,provider}` + non-unique `{workspaceId,bookingRef}` via native `createIndex`+`IndexOptions().unique(true)`; rollback drops the two by key (never `dropIndexes`) in `backend/src/main/java/com/cadence/config/migration/ChangeUnit007_ManagedCalendarEventIndexes.java`

### Adapter substrate (shared by all stories)

- [x] T014 [P] Create `CalendarApiException extends RuntimeException` (`boolean transient`, `Integer httpStatus`, `String providerReason`) in `backend/src/main/java/com/cadence/integration/CalendarApiException.java`
- [x] T015 Create `CalendarApiRetry` — bounded exponential backoff **+ jitter** (max from `calendar.api.max-retries`, base `retry-base-backoff`) and the **reason-aware** classifier (D8 table: `429`/`5xx`/network/`403 rateLimitExceeded`→transient; `401`/`403` revoked/`insufficientPermissions`→permanent-auth; other `4xx`→fatal) in `backend/src/main/java/com/cadence/integration/CalendarApiRetry.java`
- [x] T016 Add `markNeedsReconnection(String workspaceId, String memberId, CalendarProvider provider)` to `backend/src/main/java/com/cadence/service/CalendarTokenService.java` — reuse the F01.1 guarded `findAndModify({_id, status==CONNECTED} → NEEDS_RECONNECTION, accessToken=null, version++)` + `CALENDAR_RECONNECT_REQUIRED` audit; callable by the adapter on revoked/insufficient-scope `403` (D9/B1)
- [x] T017 Widen the `CalendarProviderClient` interface with `queryFreeBusy(ws, member, windowStart, windowEnd)`, `createEvent(ws, bookingRef, member, EventDetails)`, `updateEvent(ws, bookingRef, member, EventDetails)`, `deleteEvent(ws, bookingRef, member)` in `backend/src/main/java/com/cadence/integration/CalendarProviderClient.java`
- [x] T018 Create `GoogleCalendarClient implements CalendarProviderClient` SKELETON (`id()==GOOGLE`; `validAccessToken(ws,member)` delegates to `tokenService.validAccessToken(ws,member,GOOGLE)` — plan-review Backend M1; private `RestClient` built with `calendar.api` connect/read timeouts and **no body-logging interceptor**; CRUD methods stubbed `throw UnsupportedOperationException`, filled per story) in `backend/src/main/java/com/cadence/integration/GoogleCalendarClient.java`. NOTE: `CalendarReconnectRequiredException`, `CalendarNotConnectedException`, and `CalendarProviderTransientException` already exist from F01.1 — REUSE them, do not recreate
- [x] T019 Create `CalendarFanoutConfig` — a bounded fixed `ExecutorService` (`freebusy-parallelism`) wrapped in `DelegatingSecurityContextExecutorService` + an MDC-copy task decorator, declared `@Bean(destroyMethod = "shutdown")` (plan-review Backend MAJOR) in `backend/src/main/java/com/cadence/config/CalendarFanoutConfig.java`

### Test harness

- [x] T020 Create `StubGoogleCalendar` PART A — the JDK `HttpServer` **sibling** (not a `StubProvider` subclass — D12): METHOD+path matching (DELETE/PATCH have no body, key on method+path with the event id as a path param), PER-operation/PER-eventId programmable status SEQUENCES (e.g. `429,429,200`; create-ok-then-delete-`503` — stateful per-key counters), request recording, and a `gate(n)` latch in `backend/src/test/java/com/cadence/calendar/StubGoogleCalendar.java`
- [x] T020b Add `StubGoogleCalendar` PART B — the in-memory **event store**: `events.insert/patch/delete` mutate it, and the `freeBusy` handler PROJECTS ONLY `{start,end}` from stored events that also carry seeded title/attendees (so SC-004's "content exists server-side but never reaches Cadence" is non-vacuous); expose a residual-events query for the rollback test (T038) in `backend/src/test/java/com/cadence/calendar/StubGoogleCalendar.java` (same file as T020 — sequential)
- [x] T021 Create a calendar-API test base (extend the F01.1 `CalendarItBase` or a sibling) that boots `StubGoogleCalendar` and points `calendar.api.google.base-url` at it via `@DynamicPropertySource`, seeds connections through the F01.1 production path, sets `calendar.api.retry-base-backoff: PT0S`, and cleans `managedCalendarEvents`+`calendarConnections`+`authAuditLog` in `@BeforeEach` with `mongoTemplate.remove(...)` (never `dropCollection`) in `backend/src/test/java/com/cadence/calendar/CalendarApiItBase.java`

**Checkpoint**: adapter skeleton, storage, models, and the stub exist; stories can begin.

---

## Phase 3: User Story 1 — Read a panel's availability (Priority: P1) 🎯 MVP

**Goal**: free/busy reads for one member and a bounded-parallel panel, normalised to the internal model, surfaced via a self-scoped availability-preview (the §II end-to-end leg).

**Independent Test**: connect a member (stub), seed a busy interval, call the preview → exactly that interval returned, no event content; a 5-member panel returns within budget; a not-connected member is reported NOT_CONNECTED (never "free").

### Tests for User Story 1 (write first; MUST fail) ⚠️

- [x] T022 [P] [US1] `CalendarAvailabilityIntegrationTest`: free/busy returns ONLY intervals though the stub event holds a SENTINEL title+attendee-email (assert sentinels absent from model+response+logs — SC-004 non-circular); not-connected / needs-reconnection / transient-after-retry each → a DISTINCT `AvailabilityStatus` (FR-004); empty window → `DATA`+[]; oversized window (> max-window) → rejected/clamped — in `backend/src/test/java/com/cadence/calendar/CalendarAvailabilityIntegrationTest.java`
- [x] T023 [P] [US1] `CalendarPanelAvailabilityTest`: 5-member bounded-parallel panel returns within budget (SC-001); a MICROSOFT-connected member → `NOT_CONNECTED` pre-F11 — in `backend/src/test/java/com/cadence/calendar/CalendarPanelAvailabilityTest.java`
- [x] T024 [P] [US1] `CalendarAvailabilityContractTest`: `GET …/availability/preview` self-scoped 200 for all 5 roles; 401 unauth; `Cache-Control: no-store`; `busy[]` has no content field; two members never see each other's data (FR-018); assert `RbacEndpointInventoryTest` still green — in `backend/src/test/java/com/cadence/calendar/CalendarAvailabilityContractTest.java`

### Implementation for User Story 1

- [x] T025 [US1] Implement `GoogleCalendarClient.queryFreeBusy` — `POST {base}/calendar/v3/freeBusy` with `{timeMin,timeMax,items:[{id:"primary"}]}` + member bearer token (via the T018 delegate), parse `calendars.primary.busy[]` `{start,end}` into `BusyInterval`s ONLY (D2), wrap calls in `CalendarApiRetry`; `403 insufficientPermissions`/`401` → `markNeedsReconnection` + `CalendarReconnectRequiredException` in `backend/src/main/java/com/cadence/integration/GoogleCalendarClient.java`
- [x] T026 [US1] Implement `AvailabilityService.query(ws, windowStart, windowEnd, memberIds)` — per-member client selection via `Map<CalendarProvider,CalendarProviderClient>`, bounded-parallel fan-out on the `CalendarFanoutConfig` executor, status mapping (NOT_CONNECTED / NEEDS_RECONNECTION / TEMPORARILY_UNAVAILABLE / DATA — FR-004), `max-window` clamp, and a single-member fast path that bypasses the pool; document it as a privileged internal primitive (Security M4) in `backend/src/main/java/com/cadence/service/AvailabilityService.java`
- [x] T027 [US1] Add `AvailabilityPreviewResponse(provider, status, windowStart, windowEnd, List<BusyInterval> busy)` — NO content fields — to `backend/src/main/java/com/cadence/api/CalendarDtos.java`
- [x] T028 [US1] Create `CalendarAvailabilityController` — `GET /api/internal/calendar/availability/preview` `@PreAuthorize("isAuthenticated()")`, self-scoped to the principal (no memberId param), default window = `preview-window`, `Cache-Control: no-store` in `backend/src/main/java/com/cadence/api/CalendarAvailabilityController.java`
- [x] T029 [US1] Frontend: add `previewAvailability()` to `frontend/src/app/features/calendar/calendar.service.ts` and a "Preview my availability" action to `frontend/src/app/features/calendar/calendar-connections.component.ts` rendering busy blocks / "you appear free" / reconnect-prompt (localized `$localize`, reuse the F01.1 status surface)
- [x] T030 [US1] Frontend unit spec: render DATA(busy)/DATA(empty=free)/NEEDS_RECONNECTION states; the preview button calls the service — in `frontend/src/app/features/calendar/calendar-connections.component.spec.ts`

**Checkpoint**: US1 fully functional and independently demonstrable end-to-end (Angular → Spring → Google stub). MVP deliverable.

---

## Phase 4: User Story 2 — Create a calendar event when booked (Priority: P1)

**Goal**: idempotent create of a Cadence interview event on each participant's calendar, DST-correct, with a durable `ManagedCalendarEvent` record + audit.

**Independent Test**: create an event → appears on the (stub) calendar with correct wall-clock/title/location, no video link; a retried/concurrent create makes exactly one event.

### Tests for User Story 2 (write first; MUST fail) ⚠️

- [x] T031 [P] [US2] `CalendarEventCreateIntegrationTest`: create → provider event id + `ManagedCalendarEvent` CREATED + one `CALENDAR_EVENT_CREATED` audit (assert NO title/location in the audit); sequential idempotent (double create → one event, `409`→ok, SC-008); raw-driver read of the doc shows refs+instants only, NO content (D14); **owns SC-002** — assert structurally (one claim upsert + one provider call per participant; the ≤10 s wall-clock target is the RestClient-bounded consequence, not a flaky latency assertion) — in `backend/src/test/java/com/cadence/calendar/CalendarEventCreateIntegrationTest.java`
- [x] T032 [P] [US2] `CalendarConcurrentCreateTest`: GATED two-thread `createPanelEvents(sameWs,sameBookingRef,sameMember)` released by `gate(2)` → exactly ONE Google insert (stub-recorded) + ONE `managedCalendarEvents` row via the unique-index `DuplicateKeyException` claim (F01.1 gated-CAS pattern; non-vacuous) — in `backend/src/test/java/com/cadence/calendar/CalendarConcurrentCreateTest.java`
- [x] T033 [P] [US2] `CalendarEventDstTest`: assert the RECORDED request body's `dateTime` UTC offset + IANA `timeZone` for TWO instants straddling a spring-forward boundary (offset must change) — not an `Instant` round-trip (SC-005) — in `backend/src/test/java/com/cadence/calendar/CalendarEventDstTest.java`

### Implementation for User Story 2

- [x] T034 [P] [US2] Implement deterministic Google event-id derivation — `base32hex(SHA-256(lengthPrefixed(bookingRef, memberId)))` (charset `[a-v0-9]`, padding stripped; unambiguous join so no collision — data-model §4) as a small util in `backend/src/main/java/com/cadence/integration/GoogleEventId.java`
- [x] T035 [US2] Implement `GoogleCalendarClient.createEvent` — `POST {base}/calendar/v3/calendars/primary/events` with `{id:<deterministic>, summary:<title>, location:<loc>, start:{dateTime,timeZone}, end:{dateTime,timeZone}}` (no video link), `201/200`→id, `409`→treat as success (D6), wrapped in `CalendarApiRetry`; never log the body in `backend/src/main/java/com/cadence/integration/GoogleCalendarClient.java`
- [x] T036 [US2] Implement `CalendarEventService.createPanelEvents(ws, bookingRef, participants, details)` — claim each `ManagedCalendarEvent` via the unique-index upsert BEFORE the provider insert (idempotency), call `createEvent`, persist CREATED + `CALENDAR_EVENT_CREATED` audit (internal ids only); return `PanelBookingResult` (rollback path added in US3) in `backend/src/main/java/com/cadence/service/CalendarEventService.java`

**Checkpoint**: US1 + US2 both work independently; events create idempotently and DST-correctly.

---

## Phase 5: User Story 3 — Update / cancel + rollback (Priority: P1)

**Goal**: in-place update, idempotent delete, and the compensating-delete saga (zero orphans, or an audited `CLEANUP_INCOMPLETE`).

**Independent Test**: update → calendar shows new time, old gone; delete → gone; delete of an already-gone event → success; a partial-create panel rolls back to zero orphans.

### Tests for User Story 3 (write first; MUST fail) ⚠️

- [x] T037 [P] [US3] `CalendarEventUpdateDeleteTest`: update → stub records a `PATCH` on the SAME `providerEventId` (in place, no new insert); delete removes it; delete/patch of a `404`-gone event → success (FR-011/SC-008) — in `backend/src/test/java/com/cadence/calendar/CalendarEventUpdateDeleteTest.java`
- [x] T038 [P] [US3] `CalendarRollbackIntegrationTest`: partial-create (participant N fails) → compensating-delete of `0..N-1`, **zero orphans asserted via the stub's residual event store** (not the self-reported outcome) + `outcome=ROLLED_BACK` (SC-007); a per-eventId persistent delete-`503` → that member `ManagedCalendarEvent.status==CLEANUP_INCOMPLETE` + one `CALENDAR_EVENT_CLEANUP_INCOMPLETE` audit + the orphan STILL present in the stub (reconcilable, FR-016a) — in `backend/src/test/java/com/cadence/calendar/CalendarRollbackIntegrationTest.java`

### Implementation for User Story 3

- [x] T039 [US3] Implement `GoogleCalendarClient.updateEvent` (`PATCH …/events/{id}` changed fields, `404/410`→success) and `deleteEvent` (`DELETE …/events/{id}`, `204` ok, `404/410`→success), both wrapped in `CalendarApiRetry` in `backend/src/main/java/com/cadence/integration/GoogleCalendarClient.java`
- [x] T040 [US3] Implement `CalendarEventService.updatePanelEvents` (patch each, refresh row time bounds, `CALENDAR_EVENT_UPDATED` audit) and `cancelBooking` (enumerate `{workspaceId,bookingRef}`, idempotent delete all, `CALENDAR_EVENT_DELETED` audit) in `backend/src/main/java/com/cadence/service/CalendarEventService.java`
- [x] T041 [US3] Add the compensating-delete saga to `createPanelEvents`: on a mid-panel failure, delete the already-created events; if a compensating delete exhausts its retry budget → mark that row `CLEANUP_INCOMPLETE`, audit `CALENDAR_EVENT_CLEANUP_INCOMPLETE`, set `outcome=CLEANUP_INCOMPLETE` (never a silent clean report — FR-016a) in `backend/src/main/java/com/cadence/service/CalendarEventService.java`

**Checkpoint**: US1–US3 work independently; calendars always reflect current truth; no orphans.

---

## Phase 6: User Story 4 — Survive rate limits & transient outages (Priority: P2)

**Goal**: bounded backoff+jitter retry on transient failures; permanent/scope failures surfaced as needs-reconnection without infinite retry; no partial state.

**Independent Test**: `429`/`503` then recover → succeeds; persistent transient → exhausts then fails transient with no orphan; revoked/insufficient-scope → needs-reconnection, no retry.

### Tests for User Story 4 (write first; MUST fail) ⚠️

- [x] T042 [P] [US4] `CalendarApiRetryTest`: `429,429,200`→success; persistent `503`→`CalendarApiException(transient)` after max-3 AND the `managedCalendarEvents` claim row is absent/non-CREATED (FR-014 — no partial write); `403 rateLimitExceeded`→retried; `403 insufficientPermissions` & `401`→`NEEDS_RECONNECTION` + `CALENDAR_RECONNECT_REQUIRED` audit, NO retry (D9/B1); pure-unit reason-aware classifier truth table + backoff bound `delay ≤ base·2^n + jitterMax` — in `backend/src/test/java/com/cadence/calendar/CalendarApiRetryTest.java`

### Implementation for User Story 4

- [x] T043 [US4] Finalize reason-aware `403` handling across all `GoogleCalendarClient` ops (inspect Google `errors[].reason`), wire the insufficient-scope/revoked path to `CalendarTokenService.markNeedsReconnection` (T016), and guarantee a transient-exhausted single create leaves no `managedCalendarEvents` claim row / byte-identical state (FR-014) in `backend/src/main/java/com/cadence/integration/GoogleCalendarClient.java` and `backend/src/main/java/com/cadence/service/CalendarEventService.java`

**Checkpoint**: all four stories independently functional.

---

## Phase 7: Polish & Cross-Cutting Concerns

- [x] T044 [P] `CalendarEventLogPiiScanTest` (SC-003): root TRACE scoped to `com.cadence`; drive preview + create + update + delete + a RETRY-path failing call; assert FIVE sentinel categories ABSENT (token/secret, event-title, location, dial-in/phone-number, attendee-email, provider-account-email) + a positive vacuity guard (a known internal id IS detected) — in `backend/src/test/java/com/cadence/calendar/CalendarEventLogPiiScanTest.java`
- [x] T045 [P] `CalendarEventRestartPersistenceTest`: a cold `MongoTemplate` reads a `managedCalendarEvents` row back (no converter needed; refs survive) — in `backend/src/test/java/com/cadence/calendar/CalendarEventRestartPersistenceTest.java`
- [x] T046 [P] Frontend E2E: Playwright connect (stub) → "Preview my availability" → busy blocks shown — in `frontend/e2e/calendar-connections.spec.ts`
- [x] T047 Verify CI gates locally: run the `ci.yml` PII scan (5 sentinels) over a TRACE test log and the `googleapis.com`-literal grep against `GoogleCalendarClient.java` (both must pass)
- [x] T048 Run the full backend suite + `ng test --watch=false` + `ng build` (run flags above); confirm `RbacEndpointInventoryTest` and the F01.1/F02/F03/F04 suites stay green
- [x] T049 Run `quickstart.md` manual validation (connect → preview → disconnect/reconnect-prompt) against `./gradlew bootRun` + `ng serve`
- [x] T050 Multi-role sub-agent review (≥3 roles incl. Security + an actual TRACE-log/compile scan per Principle V/VI — C6 gate); apply or report all findings before task closure; update `specs/007-google-calendar/checklists/requirements.md` with the implementation-review log

---

## Dependencies & Execution Order

### Phase dependencies

- **Setup (P1: T001–T003)** → no deps; start immediately.
- **Foundational (P2: T004–T021)** → depends on Setup; **BLOCKS all user stories**.
- **US1 (P3), US2 (P4), US3 (P5), US4 (P6)** → all depend on Foundational. US1 is the MVP; US2 → US3 share `GoogleCalendarClient`/`CalendarEventService` (US3 extends US2's create with the rollback saga, so US3 depends on US2). US4 hardens classification used by US1–US3 (its dedicated tests depend on US2's write path existing).
- **Polish (P7: T044–T050)** → after the desired stories.

### Story dependencies

- **US1** independent (read path only) — the MVP.
- **US2** independent of US1 (write path), shares foundational.
- **US3** depends on **US2** (extends `createPanelEvents` with rollback; reuses the create record).
- **US4** depends on **US2** (resilience tests exercise the write path) and the foundational `CalendarApiRetry`.

### Within each story

Tests (write first, must fail) → models/util → client method → service → endpoint/UI.

### Parallel opportunities

- Setup: T002, T003 in parallel.
- Foundational: T004–T009, T011, T014 are all `[P]` (distinct files); T012/T013 after T011; T015–T021 mostly sequential where they touch shared files.
- Each story's test tasks (`[P]`) run together before that story's implementation.
- After Foundational, US1 and US2 can proceed in parallel (different files); US3 waits on US2; US4's test waits on US2.

---

## Parallel Example: User Story 1

```bash
# Write all US1 tests first (they MUST fail), in parallel:
Task: "CalendarAvailabilityIntegrationTest in backend/src/test/java/com/cadence/calendar/CalendarAvailabilityIntegrationTest.java"
Task: "CalendarPanelAvailabilityTest in backend/src/test/java/com/cadence/calendar/CalendarPanelAvailabilityTest.java"
Task: "CalendarAvailabilityContractTest in backend/src/test/java/com/cadence/calendar/CalendarAvailabilityContractTest.java"
```

```bash
# Foundational model files in parallel:
Task: "BusyInterval in domain/BusyInterval.java"
Task: "AvailabilityStatus in domain/AvailabilityStatus.java"
Task: "MemberAvailability in domain/MemberAvailability.java"
Task: "EventDetails in domain/EventDetails.java"
Task: "EventStatus in domain/EventStatus.java"
Task: "PanelBookingResult in domain/PanelBookingResult.java"
```

---

## Implementation Strategy

### MVP first (US1)

1. Setup (T001–T003) → Foundational (T004–T021) → US1 (T022–T030).
2. **STOP and VALIDATE**: connect a member, preview availability end-to-end against the stub. This is the constitution-§II demonstrable increment.

### Incremental delivery

- US1 (read/preview) → demo. Then US2 (create) → US3 (update/cancel/rollback) → US4 (resilience hardening) → Polish.
- Each story keeps the prior ones green; run T048 after each story.

### Notes

- `[P]` = different files, no incomplete-task dependency.
- Tests are written FIRST and must fail before implementation (constitution §VII).
- Never pass a Java enum to `StructuredArguments.kv(...)` — log `provider.name()` / `status.name()` Strings (F01.1 logstash Jackson-3 foot-gun; now applies to the new `EventStatus`/`AvailabilityStatus`/`PanelOutcome`/`MemberOutcome` enums too).
- Clean test collections with `mongoTemplate.remove(...)`, never `dropCollection` (drops the Mongock-007 indexes).
- No new runtime dependency; no tool downloads; no new `.ps1`.
- Commit after each task or logical group; do not mark a story done until its checkpoint test passes.
