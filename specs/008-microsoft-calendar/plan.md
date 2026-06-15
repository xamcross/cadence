# Implementation Plan: Calendar Integration — Microsoft 365 / Outlook (F11)

**Branch**: `008-microsoft-calendar` | **Date**: 2026-06-15 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/008-microsoft-calendar/spec.md`

## Summary

Deliver the **Microsoft 365 / Outlook calendar adapter** (backlog F11) — the same capabilities F10 ships for Google, spoken through **Microsoft Graph**, normalised into the **same internal model** so the scheduler treats both providers identically and **mixed Google + Microsoft panels become schedulable**. Two capabilities: (1) **read availability** via Graph `getSchedule` (parse `scheduleItems.{start,end,status}` only — no event content), normalised into F10's `MemberAvailability`/`BusyInterval`; (2) **create/update/delete** Cadence interview events on members' Outlook calendars, idempotently, reusing F10's compensating-delete saga and now its **cross-provider** rollback. Wrapped behind the existing `CalendarProviderClient` abstraction (no Graph SDK), resilient to Graph throttling (backoff + jitter + **`Retry-After`**), DST-correct, never reading/storing/logging content.

F11 is mostly **reuse**: the internal model, `AvailabilityService`, `CalendarEventService`, the bounded fan-out, `CalendarApiRetry`, the `managedCalendarEvents` collection (+ Mongock `007`), the audit/log discipline, and the self availability-preview (§II leg) are all F10's. The genuinely new code is one provider client (`MicrosoftCalendarClient`), a `StubGraphCalendar`, Graph-aware failure classification, `Retry-After` honouring, the §VIII scope change, and **one load-bearing refactor** that the F10 design demands for Graph.

Load-bearing engineering decisions (full detail in [research.md](./research.md)):
1. **Scope change `Calendars.Read` → `openid profile email offline_access Calendars.ReadWrite`** (D1) — write needs `Calendars.ReadWrite` (Graph has **no** owned-events-only scope, so it is broader than F10's Google grant — §VIII justification below); `openid profile email` make Graph issue an id_token so `providerAccountId` holds the member SMTP that getSchedule needs (D2a). **Config-only** to the existing `MicrosoftOAuthGateway`.
2. **Read = getSchedule, parse `{start,end,status}` only** (D2) — content control is **parse-discipline + the non-circular SC-004 test**, since getSchedule on a self-mailbox *can* carry subject/location (an honest divergence from F10's structural freeBusy guarantee; `availabilityView`-only is the rejected quantized-but-structural alternative).
3. **Status mapping fail-safe** (D3) — `tentative`/`oof`/`workingElsewhere`/`unknown` → busy; F10's `BusyInterval`/`MemberAvailability`/`AvailabilityStatus` **unchanged** (FR-013).
4. **DST: read in UTC, write IANA `dateTimeTimeZone`** (D4) — side-steps Windows-zone parsing; Graph accepts IANA on writes.
5. **Idempotency + the interface refactor** (D5) — unique-index claim (primary, reused) + Graph **`transactionId`** dedup; **Graph assigns the event id**, so `createEvent` returns the server id and the service persists/addresses it; `updateEvent`/`deleteEvent` are refactored to take `providerEventId` (resolved from the row) instead of re-deriving it. Behaviour-preserving for Google.
6. **Graph-aware classification** (D6) — `403`→reconnect (Graph throttling is `429`, not `403`); parse only `error.code`.
7. **Honour `Retry-After`** (D7) — `CalendarApiException` carries `retryAfter`; `CalendarApiRetry` waits `max(backoff, retryAfter)`; both header forms.
8. **`MicrosoftCalendarClient` = 2nd map entry** (D8) → **mixed panels + cross-provider rollback by construction** (D9, no orchestration change). **`StubGraphCalendar`** sibling stub (D10); §II leg = the reused provider-agnostic self-preview (D11); audit/log discipline + `graph.microsoft.com` base-URL guard (D12); **no new collection/changeset** — reuse `managedCalendarEvents`/Mongock `007` (D13); **no new dependency/service/topology** (D14).

## Technical Context

**Language/Version**: Java 21 (backend, toolchain pinned); TypeScript 5.4 / Angular 17.3 (frontend)
**Primary Dependencies**: Spring Boot 3.3.5 (web — incl. `RestClient`, data-mongodb, security w/ method security, actuator, aop, oauth2-client from F01); Mongock 5.4.4; logstash-logback-encoder 9.0. **No new backend or frontend runtime dependency** — Microsoft Graph HTTP via `RestClient` (on `JdkClientHttpRequestFactory` for `PATCH`, the F10 lesson); token store/crypto/audit reused from F01.1; the `MicrosoftOAuthGateway` already exists (F01.1); provider stubbed by the JDK `HttpServer` harness (`StubGraphCalendar`, sibling of `StubGoogleCalendar`). Test-only: `spring-security-test` (already present). **WireMock is NOT used** (F01.1 Jackson conflict).
**Storage**: MongoDB 7.x (Atlas prod, Testcontainers `mongo:7` tests). **No new collection and no new Mongock changeset** — reuses F10's `managedCalendarEvents` and `ChangeUnit007`'s indexes (the unique key already discriminates on `provider`, D13). Reuses `calendarConnections` (F01.1, read for provider+token+SMTP), `members`/`sessions` (actor), `authAuditLog` (the F10 `CALENDAR_EVENT_*` + F01.1 `CALENDAR_RECONNECT_REQUIRED` event types — no new value).
**Testing**: JUnit 5 + Testcontainers (integration: getSchedule free/busy `{start,end,status}`-only extraction with seeded subject/location → assert absent (SC-004, non-circular); status-mapping for `free`/`busy`/`tentative`/`oof`/`workingElsewhere`/`unknown`; exact non-grid boundaries; DST-crossing recorded-body wire assertion; idempotent create via `transactionId` + server-id read-back; in-place update/delete by stored id; `404`→ok; `429,429,201`→success; `429`+`Retry-After`→honoured; persistent `5xx`→transient-no-orphan; `401`/`403`→needs-reconnection (no retry); partial-create rollback + `CLEANUP_INCOMPLETE`; **mixed Google+Microsoft panel** read + booking + **both-direction** cross-provider rollback against both stubs; raw-driver no-content doc; cold-template read), MockMvc (preview 5-role self-scoped contract for a Microsoft connection + `no-store` + TRACE PII/content scan), Mockito (unit: Graph failure classifier truth table, `Retry-After` parse both forms, `max(backoff,retryAfter)` bound, status→busy mapping), Jasmine (preview render states for a Microsoft connection), Playwright (E2E connect Microsoft → preview against the stub).
**Target Platform**: Fly.io single Machine (backend JAR), Cloudflare Pages (Angular SPA), MongoDB Atlas.
**Project Type**: Web application (Angular frontend + Spring Boot backend).
**Performance Goals**: free/busy read = one indexed connection lookup + one getSchedule call per member, bounded-parallel for a panel, < 5 s for 5 members (SC-001); event create = one provider call + one claim upsert per participant, visible < 10 s (SC-002). No scheduled job; no hot-path scan.
**Constraints**: single instance + MongoDB only — no Redis/queue/cache/broker (§IV/C2; the panel fan-out is the existing bounded in-process executor); least-privilege within Graph's options — `Calendars.ReadWrite` (no narrower write scope exists) with §VIII justification (D1); never read/store/log event content, the getSchedule SMTP, attendee/account emails, tokens, or a Graph body verbatim (FR-021/022/023/025, SC-003/004); DST-correct (FR-003/003a, SC-005); idempotent + no-orphan incl. cross-provider (FR-010/011/012/014, SC-007/008/009); zero token/content in logs incl. TRACE (SC-003); zero tool downloads (§X); any new `.ps1` pure ASCII (§V — none expected).
**Scale/Scope**: MVP single workspace (tens–hundreds of members; panels of ≤ ~8). 5 user stories, 27 FRs (+ FR-002a/003a), 11 SCs.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Gate | Question | Result |
|---|---|---|
| **C1** | Within MVP scope (spec §11)? | ✅ PASS — Microsoft 365 / Outlook (bi-directional) is explicit §11 MVP "Calendar sync". No deferred capability pulled in (meeting-link = v1.5/excluded; booking orchestration = F13; Flow A2 excluded). |
| **C2** | New service, queue, or replica? | ✅ PASS — **no** new collection (reuses `managedCalendarEvents`), **no** new changeset; the panel read reuses the existing bounded in-process executor (threads, not a broker); no `@Scheduled`, no cache/replica/object-store. |
| **C3** | Exposes candidate PII to unauthorized roles? | ✅ PASS — availability is member calendar data; the preview is **self-scoped**. Recruiter-supplied event subject/location (may contain candidate PII) is forwarded to the participant's own calendar and **never stored or logged** (FR-022); the getSchedule SMTP/attendee emails are never logged/retained (FR-025); no role can read another member's calendar (FR-024). |
| **C4** | Dependency outside the fixed stack? | ✅ PASS — **zero new runtime dependencies**; Graph REST via `RestClient` (spring-web); the Microsoft Graph SDK is explicitly NOT added (wrapped behind `CalendarProviderClient`, Dependency Policy). Test stub is the JDK `HttpServer`. |
| **C5** | New/modified Windows scripts contain non-ASCII? | ✅ PLANNED — no new `.ps1`; the `ci.yml` change is YAML (LF). Any script change byte-scanned before done. |
| **C6** | Multi-role sub-agent review (≥3) scheduled? | ✅ DONE+SCHEDULED — spec reviewed by 4 roles; this plan reviewed by ≥3 roles (user-requested "review with appropriate sub-agents") before tasks; final implementation review at task close. |
| **C7** | Downloads any build tool/runtime/CLI? | ✅ PASS — cached Gradle 9.4.0, installed JDK 21, existing Angular CLI; no downloads. |

**Initial gate: PASS.** One item needs explicit §VIII sign-off (not a gate failure): the **OAuth scope expansion** beyond free/busy.

### §VIII OAuth scope-expansion justification (constitution-required)

Constitution §VIII: *"OAuth requests MUST default to free/busy-only scope. Requesting broader scopes requires explicit user consent and a spec-documented justification approved in the feature plan."* F11 invokes that clause (as F10 did):

- **Why broader scope is unavoidable**: the §11 MVP mandates **bi-directional** Outlook sync — Cadence must create/update/delete events. `Calendars.Read` is read-only; an event-write scope is required.
- **Least-privilege within Graph's options, honestly broader than F10**: Microsoft Graph has **no owned-events-only delegated write scope** (no analogue to Google's `calendar.events.owned`). `Calendars.ReadWrite` — read+write of the member's mailbox calendar — is the **narrowest delegated scope that can write** an event. This is broader than F10's Google grant: it technically also permits reading the member's full calendar. The plan **explicitly records** this rather than overstating parity (Security review M of F10's analogue).
- **Identity scopes (`openid profile email`)**: added so Graph issues an id_token → F01.1 populates `providerAccountId` with the member's email/UPN, which Graph `getSchedule` requires as the mailbox address (D2a). These are standard OIDC identity scopes (already used by F01 login), **not** calendar-content scopes.
- **Compensating controls**: the **read** path never lists/reads event content — getSchedule with `{start,end,status}` parse-discipline + the non-circular SC-004 test (D2); the **write** path only ever touches Cadence-created events addressed by their stored id (D5), never enumerates the calendar; event subject/location are forwarded but never stored/logged (request **and** response bodies — FR-022/023, redacting `EventDetails.toString()`, body-logging-free RestClient); the getSchedule SMTP and any attendee/account email are never logged/retained beyond F01.1's encrypted store (FR-025); explicit per-member consent via the F01.1 consent screen (now showing the write+identity scopes). **Approved in this plan per §VIII.**
- **Stale-scope migration is a tested path**: a pre-F11 connection (read-only, null `providerAccountId`) → write returns `403`→`NEEDS_RECONNECTION` (D1/D6), read returns `NEEDS_RECONNECTION` (no SMTP, D2a) — the member reconnects under the new scope. Tested, not assumed.

### Post-Design Re-Check (after Phase 1 + §VI plan review) — COMPLETED

Multi-role plan review completed by three roles (Backend/DevOps, Security/OAuth-GDPR, QA — full log + dispositions in `checklists/requirements.md`). The reviewers verified the plan's claims against the **actual F10 source**. **Result: PASS, unchanged gate status** — the one BLOCKER (a test-design gap, not a design flaw) and every SHOULD-FIX were folded into `research.md`/`spec.md`/`contracts/`/this plan; none added a dependency, service, or topology, and none moved a gate to FAIL.

Load-bearing corrections folded in:
1. **`Retry-After` testability (QA BLOCKER B1)** — the wait is extracted into a pure `nextWaitMillis(attempt, retryAfter)` unit-tested with **no sleep**; no test asserts wall-clock (D7, contract §F).
2. **`sub`-fallback → non-SMTP `providerAccountId` (Backend S4 / Security S1)** — `accountFromIdToken` can return an opaque `sub`; the adapter now treats a non-`@` account id as `NEEDS_RECONNECTION` (not a malformed getSchedule), with a dedicated test (D2a).
3. **`CalendarEventService` blast radius (Backend S1/S3)** — research D5 now names the exact changes: delete the line-145 `GoogleEventId` derivation, persist `createEvent`'s **returned** id, keep the fast-path's stored-id return, and add a per-participant row lookup in `updatePanelEvents`.
4. **Spec FR-002/SC-004 reconciled (Security S4)** — the spec no longer claims a *structural* guarantee for Graph; the control is parse-discipline (explicit path reads) verified by the non-circular SC-004 test.
5. **`transactionId` honest bound (Security S2)** — framed as a bounded-window retry guard; the unique-index claim is the durable one-row guarantee; an out-of-window concurrent double yields one row + a reconciled orphan, not "one event".
6. **Test-hardening (QA S1–S4, N1–N3, Security S5)** — per-status (six) assertions; all-day/recurring + empty-`scheduleItems` tests; "no retry" asserted via stub `count==1`; dedicated SC-011 audit assertion; mapper uses path-reads; both stubs `reset()` in the mixed test; gate fires on `POST …/events`.

Key gate confirmations (reviewers verified against code):
- **C2 holds** — no new collection, no new changeset (reuses `managedCalendarEvents`/`ChangeUnit007`; `provider` is the always-non-null 4th unique key — index-safe); bounded executor reused (no broker); no scheduler.
- **C4 / C7 unchanged** — zero new runtime deps (Graph via `RestClient`; `MicrosoftOAuthGateway` already exists), zero downloads.
- **§VIII** — `Calendars.ReadWrite` honestly justified as broader than F10 (no narrower Graph scope exists); read path content-controlled by parse-discipline + SC-004; identity scopes benign; stale-grant → tested reconnect.
- **Open verification carried into tasks (Security N1)**: confirm the F01.1 connect consent UI renders the **expanded** Microsoft scope to the member (genuine informed consent for `Calendars.ReadWrite`), not a stale "free/busy only" label.

## Project Structure

### Documentation (this feature)

```text
specs/008-microsoft-calendar/
├── plan.md              # This file
├── research.md          # Phase 0 — decisions D1..D14
├── data-model.md        # Phase 1 — reused entities + the interface refactor + Graph config
├── quickstart.md        # Phase 1 — local run + manual + test verification
├── contracts/
│   └── calendar-microsoft-api.md  # Phase 1 — preview REST (reused) + refactored adapter contract + Graph HTTP + RBAC
├── checklists/
│   └── requirements.md  # Spec quality + spec/plan review logs
└── tasks.md             # Phase 2 (/speckit.tasks — NOT created here)
```

### Source Code (repository root)

```text
backend/src/main/java/com/cadence/
├── integration/
│   ├── CalendarProviderClient.java           # MODIFIED — refactor update/delete to take providerEventId; createEvent returns the
│   │                                          #   provider-assigned id (D5). Javadoc: id is server-assigned for Microsoft.
│   ├── MicrosoftCalendarClient.java          # NEW — implements CalendarProviderClient (id()=MICROSOFT); token via CalendarTokenService;
│   │                                          #   SMTP via connection.providerAccountId, NON-SMTP/sub-only -> NEEDS_RECONNECTION (D2a); RestClient on JdkClientHttpRequestFactory;
│   │                                          #   getSchedule read (parse {start,end,status} only, D2/D3), events create(+transactionId,
│   │                                          #   read-back id)/patch/delete by stored id (D5); UTC read / IANA write (D4)
│   ├── GoogleCalendarClient.java             # MODIFIED — update/delete now accept providerEventId (= the deterministic id it still sets on
│   │                                          #   create + returns); behaviour-preserving (D5)
│   ├── CalendarApiClassifier.java            # MODIFIED — provider-aware: add Graph mapping (403->reconnect; 429/5xx->transient; parse
│   │                                          #   error.code only) alongside the existing Google reason-aware path (D6)
│   ├── CalendarApiException.java             # MODIFIED — add optional Duration retryAfter (D7)
│   ├── CalendarApiRetry.java                 # MODIFIED — extract pure nextWaitMillis(attempt, retryAfter)=max(backoff+jitter, retryAfter)
│   │                                          #   (testable without sleep, QA B1); execute() reads the exception's retryAfter (D7)
│   └── MicrosoftOAuthGateway.java            # MODIFIED (comment-only) — update the stale class Javadoc that still says scope is
│                                             #   "Calendars.Read offline_access ... field projection is F11's mitigation"; scope is config-driven (D1)
├── service/
│   ├── CalendarEventService.java             # MODIFIED — capture providerEventId = createEvent(...) and record the RETURNED id; resolve the
│   │                                          #   stored providerEventId for update/delete/rollback/cancel (D5). Orchestration/saga UNCHANGED
│   │                                          #   (mixed-provider rollback works by construction, D9)
│   └── AvailabilityService.java              # UNCHANGED — provider-map already selects the MS client once it is a @Component (D8/D9)
├── config/
│   └── CalendarApiProperties.java            # MODIFIED — add microsoft.base-url + graph-availability-view-interval (default 15); reuse the
│                                             #   shared timeouts/retries/backoff/parallelism/max-window/preview-window
backend/src/main/resources/application.yml    # MODIFIED — CHANGE calendar.oauth.microsoft.scope to
│                                             #   "openid profile email offline_access Calendars.ReadWrite" (D1); ADD calendar.api.microsoft.base-url
│                                             #   (${MS_GRAPH_API_BASE:https://graph.microsoft.com}) + graph-availability-view-interval
.github/workflows/ci.yml                       # MODIFIED — extend the base-URL guard: ban a graph.microsoft.com literal in MicrosoftCalendarClient
│                                             #   (mirrors the F10 googleapis.com guard); the 5 event-content sentinels already cover F11

backend/src/test/java/com/cadence/calendar/
├── MicrosoftAvailabilityIntegrationTest.java # US1: getSchedule returns ONLY {start,end,status} though the stub item holds a SENTINEL
│                                             #   subject/attendee → assert sentinels absent from model+response+logs (SC-004 non-circular);
│                                             #   status free/busy/tentative/oof/workingElsewhere/unknown mapping (FR-002a); non-grid exact
│                                             #   boundary (FR-003); not-connected/needs-reconnection (incl. pre-F11 null-SMTP)/transient → DISTINCT status
├── MicrosoftEventCreateIntegrationTest.java  # US2: create → server id read-back + ManagedCalendarEvent CREATED (providerEventId = server id) +
│                                             #   one CALENDAR_EVENT_CREATED audit (no subject/loc); idempotent via transactionId (double create →
│                                             #   one event, SC-008); raw-driver doc has refs+instants only (D13)
├── MicrosoftConcurrentCreateTest.java        # SC-008: GATED two-thread create(same ws,bookingRef,member) → exactly ONE Graph insert (transactionId
│                                             #   dedup) + ONE managedCalendarEvents row (unique-index claim)
├── MicrosoftEventDstTest.java                # US2/SC-005: assert the RECORDED create body's start/end dateTime (local) + IANA timeZone for TWO
│                                             #   instants straddling a spring-forward boundary
├── MicrosoftEventUpdateDeleteTest.java       # US3: update = stub records a PATCH on the SAME stored providerEventId (in place); delete; delete/patch
│                                             #   of a gone (404) event → success (FR-011/SC-008)
├── MicrosoftRetryResilienceTest.java         # US5: 429,429,201→success; 429 + Retry-After (delta-seconds AND HTTP-date) → waited; persistent 503 →
│                                             #   CalendarApiException(transient) after max-3 + claim row absent/non-CREATED (FR-017); 401 & 403 →
│                                             #   NEEDS_RECONNECTION + CALENDAR_RECONNECT_REQUIRED, NO retry (D6); pure-unit Graph classifier truth
│                                             #   table + Retry-After parse + max(backoff,retryAfter) bound
├── MixedProviderPanelTest.java               # US4/SC-009: one GOOGLE + one MICROSOFT member → one normalised availability set (both stubs); panel
│                                             #   booking creates on both; force provider-2 create-fail → rollback deletes provider-1 event (BOTH
│                                             #   directions: Google-fail-rollback-MS AND MS-fail-rollback-Google); zero orphans via both stubs' residual state (SC-007)
├── MicrosoftRollbackIntegrationTest.java     # US3/SC-007: MS partial-create → compensating delete, zero orphans via stub residual store; per-eventId
│                                             #   persistent delete-5xx → CLEANUP_INCOMPLETE + audit + orphan still present (FR-019)
├── MicrosoftAvailabilityContractTest.java    # preview: 5 roles self-scoped 200 for a MICROSOFT connection; no-store; busy[] has no content field;
│                                             #   two members never see each other's data (FR-024); RbacEndpointInventoryTest stays green
├── MicrosoftEventLogPiiScanTest.java         # SC-003: TRACE scoped to com.cadence; drive preview+create+update+delete+a retry-path failing call;
│                                             #   assert sentinels ABSENT (token/secret, subject, location, dial-in, attendee-email, account-email/SMTP)
│                                             #   + positive vacuity guard
└── StubGraphCalendar.java                    # NEW (sibling of StubGoogleCalendar, D10) — JDK HttpServer: getSchedule (seeded subject/location +
                                              #   status), events POST (server id + transactionId dedup) / PATCH / DELETE; per-(method,path) status
                                              #   SEQUENCES; injectable Retry-After (both forms); live-event store; request recording; gate(n) latch

backend/src/test/java/com/cadence/calendar/   # MODIFIED — F10 tests that call updateEvent/deleteEvent adapt to the providerEventId signature (D5);
                                              #   CalendarApiItBase may gain a Microsoft connection seed + StubGraphCalendar wiring for the mixed test

frontend/src/app/features/calendar/
├── calendar-connections.component.ts         # MODIFIED (light) — ensure Microsoft connect is offered (F01.1) and provider label/preview are
│                                             #   provider-neutral (not Google-hardcoded); reuse the existing preview action
├── calendar-connections.component.spec.ts    # MODIFIED — Jasmine: preview render for a MICROSOFT connection (DATA/empty/NEEDS_RECONNECTION)
frontend/e2e/calendar-connections.spec.ts     # MODIFIED — Playwright: connect Microsoft (stub) → Preview → busy blocks shown
```

**Test isolation note (shared singleton container)**: every new test class MUST clean `managedCalendarEvents`, `calendarConnections`, and `authAuditLog` in `@BeforeEach` with `mongoTemplate.remove(new Query(), Type.class)` — never `dropCollection` (drops the Mongock `007` indexes; CLAUDE.md F00.1 lesson). Connections are seeded via the F01.1 production path against the OAuth stub; calendar API responses come from `StubGraphCalendar` (and `StubGoogleCalendar` for the mixed test). The mixed test runs **both** stubs simultaneously (separate `@DynamicPropertySource` base-URLs) and MUST `reset()` **both** in `@BeforeEach` so seeded busy/live events don't bleed across tests (QA N1). Concurrency/idempotency tests use the stub's **gate latch** (fired on the `POST …/events` request, QA N2) so the assertion can't pass vacuously.

**Structure Decision**: Web-application layout. F11 *adds one provider client* behind the existing `CalendarProviderClient` and is otherwise a set of **targeted modifications** to F10 code: the interface (refactor update/delete to a `providerEventId`, create returns it), `GoogleCalendarClient` (signature-only), `CalendarEventService` (record/address the returned/stored id), the classifier (Graph mapping), the retry + exception (`Retry-After`), config (Graph base-url + scope), and `ci.yml` (base-URL guard). `AvailabilityService` is **unchanged** (the provider map picks up the new `@Component`). No new collection, no new Mongock changeset, no new dependency, no new top-level structure. The frontend change is light (the preview is already provider-agnostic).

## Complexity Tracking

| Decision | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| **Refactor `updateEvent`/`deleteEvent` to take `providerEventId`** (and `createEvent` to return the server id) | Graph assigns the event id server-side and forbids a client-supplied id, so the id is **not derivable** from `(bookingRef,memberId)` as F10's Google path assumes; update/delete must address the stored id | Keeping the `(bookingRef,memberId)` signature and having each client re-look-up the row couples every provider client to the repository and duplicates the lookup the service already does; deriving the id (Google's trick) is impossible on Graph. The refactor is mechanical and behaviour-preserving for Google. |
| **Graph `transactionId` on create** (in addition to the unique-index claim) | F10's provider-first ordering writes the claim row *after* the provider call, leaving a small in-flight window for a concurrent create; Google closes it with a deterministic-id 409, Graph closes it with `transactionId` dedup | Relying on the claim alone reopens the F10 concurrent-create window for Graph (two overlapping creates could both insert at the provider before either claims); `transactionId` is Graph's documented retry-dedup mechanism, ~0 extra code. |
| **Provider-aware classification path** (Graph rules differ from Google) | Graph throttling is `429` (Google overloads `403`), so a `403` on Graph is unambiguously auth→reconnect; reusing Google's reason-aware 403 logic would mis-handle Graph | A single shared classifier mis-classifies one provider's `403`; the Graph branch is a few lines and keeps each provider correct. No new dependency. |
| **`StubGraphCalendar` (sibling, not a subclass of `StubGoogleCalendar`)** | Graph's URL scheme, body shapes, `transactionId` dedup, server-assigned ids, and `Retry-After` header differ from Google's; the mixed-panel test runs **both** stubs simultaneously | Subclassing forces Google's path/body semantics onto Graph; a fresh sibling (~same size) is cleaner and lets both stubs coexist for the mixed test. WireMock is barred (F01.1 Jackson conflict). |
