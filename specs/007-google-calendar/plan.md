# Implementation Plan: Calendar Integration — Google Calendar (F10)

**Branch**: `007-google-calendar` | **Date**: 2026-06-15 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/007-google-calendar/spec.md`

## Summary

Deliver the **Google Calendar provider adapter** (backlog F10): the first consumer of F01.1's encrypted per-member token store. Two capabilities, both minimised to scheduling needs: (1) **read availability** for one or more members via Google's free/busy endpoint, normalised into a provider-neutral model; (2) **create/update/delete** Cadence interview events on members' calendars, idempotently, with the compensating-delete an atomic booking (F13) needs to roll back cleanly. Wrapped behind the `CalendarProvider` abstraction (no Google SDK), resilient to rate limits/outages (backoff + jitter), DST-correct, and never reading/storing/logging event content. The atomic slot-reservation, scheduling email, and pipeline status are **F13**; F10 ships the operations F13 composes plus a minimal member-facing **availability-preview** as its constitution-§II end-to-end leg.

Load-bearing engineering decisions (full detail in [research.md](./research.md)):
1. **`calendar.events` scope added** (D1) — free/busy scope alone cannot write events; the MVP mandates bi-directional sync, so an event-write scope is unavoidable. `calendar.events` is the narrowest that works; full §VIII justification documented below and in D1. **Config-only change** to F01.1's gateway.
2. **Free/busy endpoint for reads** (D2) — the no-event-content guarantee (FR-002/SC-004) is **structural** (the wire response can't contain content), not filter-based.
3. **Provider-neutral model** `BusyInterval`/`MemberAvailability` + explicit `AvailabilityStatus` (D3) — a not-connected member is **not schedulable**, never "free" (FR-004); F11 reuses the same shape (FR-019).
4. **Bounded parallel per-member fan-out** (D4) — 5-person panel < 5 s (SC-001), no broker (C2).
5. **Idempotency = claimed `managedCalendarEvents` record + deterministic Google event id** (D6); update/delete idempotent (404→ok).
6. **Backoff + jitter, max 3** via `CalendarApiRetry` (D8); permanent auth (`401`) → needs-reconnection, no retry (D9).
7. **Saga compensating-delete + `CLEANUP_INCOMPLETE` outcome** (D10) — zero orphans, or an audited reconcilable record (FR-012/FR-016a/SC-007).
8. **§II demonstrable slice = availability-preview** (D11); **JDK `HttpServer` stub, not WireMock** (D12); audit/log discipline + the enum-to-`kv` foot-gun carried forward (D13); one collection + Mongock `007` (D14).
9. **Reuse, not new infra**: F01.1 `CalendarTokenService`/`CalendarProviderClient`/`AbstractOAuthGateway` pattern, `AuthAuditService` (+4 event types), `PiiCrypto` (none needed for the new collection — no PII in it), `RestClient`; the F01.1 frontend calendar feature extended by one preview action. **No new dependency, service, topology, or stack change.**

## Technical Context

**Language/Version**: Java 21 (backend, toolchain pinned); TypeScript 5.4 / Angular 17.3 (frontend)
**Primary Dependencies**: Spring Boot 3.3.5 (web — incl. `RestClient`, data-mongodb, security w/ method security, actuator, aop, oauth2-client from F01); Mongock 5.4.4; logstash-logback-encoder 9.0. **No new backend or frontend runtime dependency** — Google HTTP via `RestClient`; token store/crypto/audit reused from F01.1; provider stubbed by the existing JDK `HttpServer` harness (`StubProvider`). Test-only: `spring-security-test` (already present) for per-role post-processors. **WireMock is NOT used** (removed in F01.1 — Jackson conflict).
**Storage**: MongoDB 7.x (Atlas prod, Testcontainers `mongo:7` tests). Adds **one** collection — `managedCalendarEvents` (booking↔participant↔provider event references + instants; **no PII/secret**, so un-encrypted by design, D14). Reuses `calendarConnections` (F01.1, read for provider+token), `members`/`sessions` (actor), `authAuditLog` (extended with four event types).
**Testing**: JUnit 5 + Testcontainers (integration: free/busy-only extraction, bounded-parallel panel, DST-crossing wall-clock, idempotent create/update/delete, `429→retry→200`, persistent-`503`→transient-no-orphan, `401`→needs-reconnection, partial-create rollback + `CLEANUP_INCOMPLETE`, raw-driver no-content doc, cold-template), MockMvc (preview 5-role contract + two-member isolation + 401 + `no-store` + TRACE PII/content scan), Mockito (unit: failure classifier, backoff+jitter bound, availability-status mapping), Jasmine (preview render states), Playwright (E2E connect→preview against the stub).
**Target Platform**: Fly.io single Machine (backend JAR), Cloudflare Pages (Angular SPA), MongoDB Atlas.
**Project Type**: Web application (Angular frontend + Spring Boot backend).
**Performance Goals**: free/busy read = one indexed connection lookup + one provider call per member, bounded-parallel for a panel, < 5 s for 5 members (SC-001); event create = one claim upsert + one provider call per participant, event visible < 10 s (SC-002). No scheduled job; no hot-path scan.
**Constraints**: single instance + MongoDB only — no Redis/queue/cache/broker (§IV/C2; the panel fan-out is a bounded in-process executor, not a broker); free/busy-only **reads** + least-privilege `calendar.events` **write** scope with §VIII justification (D1); never read/store/log event content or attendee/account emails (FR-002/FR-017a/b/FR-018a, SC-003/SC-004); DST-correct (FR-003, SC-005); idempotent + no-orphan (FR-010/011/012, SC-007/008); zero token/content in logs incl. TRACE (SC-003); zero tool downloads (§X); any new `.ps1` pure ASCII (§V — none expected).
**Scale/Scope**: MVP single workspace (tens–hundreds of members; panels of ≤ ~8). 4 user stories, 24 FRs (incl. FR-016a/017a/017b/018a), 8 SCs.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Gate | Question | Result |
|---|---|---|
| **C1** | Within MVP scope (spec §11)? | ✅ PASS — Google Calendar (bi-directional) is explicit §11 MVP "Calendar sync". No deferred capability pulled in (Microsoft = F11; meeting-link = v1.5/excluded; booking orchestration = F13). |
| **C2** | New service, queue, or replica? | ✅ PASS — one new MongoDB collection on the existing instance; the panel read uses a **bounded in-process executor** (threads, not a broker); no `@Scheduled`, no cache/replica/object-store. |
| **C3** | Exposes candidate PII to unauthorized roles? | ✅ PASS — availability is member calendar data; the preview is **self-scoped** (a member sees only their own). Recruiter-supplied event title/location (may contain candidate PII) is forwarded to the participant's own calendar and **never stored or logged** (FR-017a/FR-018a); no role can read another member's calendar (FR-018). |
| **C4** | Dependency outside the fixed stack? | ✅ PASS — **zero new runtime dependencies**; Google REST via `RestClient` (spring-web); the SDK is explicitly NOT added (wrapped behind `CalendarProvider`, Dependency Policy). Test stub is the existing JDK `HttpServer`. |
| **C5** | New/modified Windows scripts contain non-ASCII? | ✅ PLANNED — no new `.ps1` expected; the `ci.yml` change is YAML (LF). Any script change byte-scanned before done. |
| **C6** | Multi-role sub-agent review (≥3) scheduled? | ✅ DONE+SCHEDULED — this plan reviewed by ≥3 roles (user-requested "review with sub-agents") before tasks; final implementation review at task close. |
| **C7** | Downloads any build tool/runtime/CLI? | ✅ PASS — cached Gradle 9.4.0, installed JDK 21, existing Angular CLI; no downloads. |

**Initial gate: PASS.** One item needs explicit §VIII sign-off (not a gate failure): the **OAuth scope expansion** beyond free/busy.

### §VIII OAuth scope-expansion justification (constitution-required)

Constitution §VIII: *"OAuth requests MUST default to free/busy-only scope. Requesting broader scopes requires explicit user consent and a spec-documented justification approved in the feature plan."* F10 invokes that exact clause:

- **Why broader scope is unavoidable**: the §11 MVP mandates **bi-directional** Google Calendar sync — Cadence must create/update/delete interview events. The free/busy scope is read-only; no amount of care lets it write an event. An event-write scope is therefore required to deliver the mandated feature, not a convenience.
- **Least-privilege scope chosen (plan-review M1 correction)**: default **`https://www.googleapis.com/auth/calendar.events.owned`** (manage only events the member organizes — i.e. the interviews Cadence creates) + `…/calendar.freebusy`. This does **not** grant read of the member's unrelated events, so it minimises both the consent ask and the **blast radius of a stored-token compromise** (M2). `calendar.events` (read/write of *all* events) is a **fallback only**, used solely if a verified `events.owned` limitation requires it, and only with a re-recorded §VIII trade-off here; full `…/calendar` is rejected. Exact string is config (`GOOGLE_CAL_SCOPE`), verified against Google's granular-scope list at build time.
- **Stale-scope migration is a tested path, not an assumption (plan-review B1)**: a connection created under F01.1's freebusy-only grant returns `403 insufficientPermissions` on a write; F10 detects this **distinctly from `invalid_grant`** and flips the connection to `NEEDS_RECONNECTION` (D1/D8/D9), so the member is prompted to reconnect rather than hitting silent repeated write failures.
- **Compensating controls**: the **read** path uses the free/busy endpoint *only* (D2) — Cadence never lists/reads the content of events it didn't create; event title/location are forwarded to the provider but never stored, never logged (request **and** response bodies — FR-017a/b/FR-018a), with a redacting `EventDetails.toString()` and a body-logging-free RestClient; explicit user consent is obtained per member (the F01.1 consent screen, now showing the events scope). **Approved in this plan per §VIII** (with the M1 least-privilege correction).

### Post-Design Re-Check (after Phase 1 + §VI plan review)

Completed after the multi-role plan review (Backend/DevOps, Security/OAuth-GDPR, QA — full log + dispositions in `checklists/requirements.md`). **All accepted findings folded** into `research.md`/`data-model.md`/`contracts/`/this plan. **Result: PASS, unchanged gate status** — every correction was a design/test-precision/security-hardening fix; none added a dependency, service, or topology, and none moved a gate to FAIL.

Load-bearing corrections folded in:
1. **Insufficient-scope `403` ≠ `invalid_grant`** (Security **BLOCKER B1**) — a stale freebusy-only connection would otherwise fail writes silently forever; D8/D9/D1 now detect `403 insufficientPermissions` explicitly and flip `NEEDS_RECONNECTION`. Tested.
2. **Least-privilege scope** (Security **M1/M2**) — default corrected from `calendar.events` to **`calendar.events.owned`** (+ freebusy); the §VIII claim no longer overstates least privilege; `calendar.events` is a fallback with a re-recorded trade-off.
3. **Reason-aware `403`** (Security **m3**) — Google returns `403` for rate-limiting too; the classifier inspects `errors[].reason` (`rateLimitExceeded`→transient vs `insufficientPermissions`/auth→needs-reconnection), not status alone.
4. **Interface/service token seam** (Backend **M1**) — the two-arg `validAccessToken` delegates to the three-arg `CalendarTokenService`; CRUD methods re-wrap the token-layer `CalendarProviderTransientException` as `CalendarApiException(transient)`.
5. **Fan-out executor hygiene** (Backend **MAJOR**) — `DelegatingSecurityContextExecutorService` + MDC copy + `@Bean(destroyMethod="shutdown")`; the single-member preview bypasses the pool; a `max-window` bound caps a panel call.
6. **Stub is a method-aware, sequenced sibling** (Backend/QA **MAJOR**) — `StubGoogleCalendar` adds method+path matching and per-operation/per-eventId status sequences (so `429,429,200` and create-ok-then-delete-fail are expressible) and holds seeded event content so SC-004 is non-circular.
7. **PII leak paths closed** (Security **M3**, QA) — redacting `EventDetails.toString()`, no RestClient body logging (request bodies too), five log-scan sentinel categories (title/location/dial-in/attendee-email/provider-account-email).
8. **Test verifiability** (QA) — named **gated concurrent create** race (one event + one row via the unique index); DST asserts the **recorded wire body** (offset + IANA zone, two straddling instants); zero-orphans asserted via the **stub's residual state**; FR-014 asserts the claim row is clean after an exhausted single create; oversized/empty-window tests; positive vacuity guard on the log scan; CI grep bans a `googleapis.com` literal in the client.
9. **Config completeness** (Backend MINOR) — `calendar.api` gains `connect-timeout`/`read-timeout`/`max-window`; deterministic event id uses an unambiguous length-prefixed join.

Key gate confirmations:
- **C2 holds** — one collection on the existing instance; bounded executor (no broker); no scheduler.
- **C3 holds** — self-scoped preview; `AvailabilityService.query` flagged a privileged internal primitive (no endpoint without an F13 gate, M4); recruiter event content never stored/logged; cross-member calendar read impossible.
- **C4 / C7 unchanged** — zero new runtime deps, zero downloads (`RestClient` + the JDK `HttpServer` stub).
- **§VIII** — least-privilege `calendar.events.owned` with the corrected justification; free/busy-only *read* path (D2); stale-scope → tested reconnect (B1); no content/email/token in logs incl. TRACE with five sentinel categories (D13).

## Project Structure

### Documentation (this feature)

```text
specs/007-google-calendar/
├── plan.md              # This file
├── research.md          # Phase 0 — decisions D1..D14
├── data-model.md        # Phase 1 — ManagedCalendarEvent + transient models, indexes, config
├── quickstart.md        # Phase 1 — local run + manual + test verification
├── contracts/
│   └── calendar-google-api.md  # Phase 1 — preview REST + internal adapter/service + Google HTTP + RBAC
├── checklists/
│   └── requirements.md  # Spec quality + spec/plan review logs
└── tasks.md             # Phase 2 (/speckit.tasks — NOT created here)
```

### Source Code (repository root)

```text
backend/src/main/java/com/cadence/
├── api/
│   ├── CalendarAvailabilityController.java   # NEW — GET /api/internal/calendar/availability/preview
│   │                                         #   @PreAuthorize("isAuthenticated()"); self-scoped (no memberId); no-store
│   └── CalendarDtos.java                     # MODIFIED — add AvailabilityPreviewResponse (provider,status,window,busy[]); NO content fields
├── domain/
│   ├── ManagedCalendarEvent.java             # NEW — @Document("managedCalendarEvents"); refs + instants only, NO PII/secret
│   ├── EventStatus.java                      # NEW (enum) — CREATED | DELETED | CLEANUP_INCOMPLETE
│   ├── BusyInterval.java                     # NEW (record) — start/end Instant
│   ├── AvailabilityStatus.java               # NEW (enum) — DATA | NOT_CONNECTED | NEEDS_RECONNECTION | TEMPORARILY_UNAVAILABLE
│   ├── MemberAvailability.java               # NEW (record) — memberId, status, List<BusyInterval>
│   ├── EventDetails.java                     # NEW (record) — title, location, startAt, endAt, ZoneId (caller-supplied; NOT persisted)
│   └── AuthEventType.java                    # MODIFIED — append CALENDAR_EVENT_CREATED/UPDATED/DELETED/CLEANUP_INCOMPLETE
├── integration/
│   ├── CalendarProviderClient.java           # MODIFIED — widen with queryFreeBusy/createEvent/updateEvent/deleteEvent (D7)
│   ├── GoogleCalendarClient.java             # NEW — implements CalendarProviderClient (id()=GOOGLE); token via CalendarTokenService;
│   │                                         #   RestClient (bounded timeouts) → Google freeBusy/events; normalises to the internal model
│   ├── CalendarApiException.java             # NEW — transient flag + httpStatus + providerError (D8)
│   └── CalendarApiRetry.java                 # NEW — exponential backoff + jitter, max-N (D8); shared with F11
├── repository/
│   └── ManagedCalendarEventRepository.java   # NEW — findBy workspaceId+bookingRef(+memberId+provider); for enumeration & claim
├── service/
│   ├── AvailabilityService.java              # NEW — query(ws,window,memberIds) bounded-parallel; status mapping (FR-004); SC-001
│   └── CalendarEventService.java             # NEW — createPanelEvents (saga + compensating delete + CLEANUP_INCOMPLETE), updatePanelEvents,
│   │                                         #   cancelBooking; writes ManagedCalendarEvent; audits CALENDAR_EVENT_* (internal ids only)
├── config/
│   ├── CalendarApiProperties.java            # NEW — @ConfigurationProperties("calendar.api") google.base-url, connect/read-timeout, max-retries,
│   │                                         #   retry-base-backoff, freebusy-parallelism, max-window, preview-window (D4/D8/D11)
│   ├── CalendarFanoutConfig.java             # NEW — bounded fixed ExecutorService for the panel fan-out (D4), WRAPPED in
│   │                                         #   DelegatingSecurityContextExecutorService + MDC-copy decorator; @Bean(destroyMethod="shutdown")
│   └── migration/
│       └── ChangeUnit007_ManagedCalendarEventIndexes.java # NEW — unique {workspaceId,bookingRef,memberId,provider}; {workspaceId,bookingRef}

backend/src/main/resources/application.yml    # MODIFIED — add calendar.api.* (incl. connect/read-timeout, max-window); CHANGE calendar.oauth.google.scope
                                              #   to calendar.events.owned + calendar.freebusy (D1 least-privilege; calendar.events fallback only)
.github/workflows/ci.yml                       # MODIFIED — extend PII scan: 5 event-content sentinels (title/location/dial-in/attendee-email/provider-account-email)
                                              #   + grep banning a googleapis.com literal in GoogleCalendarClient

backend/src/test/java/com/cadence/calendar/
├── CalendarAvailabilityIntegrationTest.java  # US1: free/busy returns ONLY intervals though the stub event holds a SENTINEL title/attendee-email → assert sentinels
│                                             #   absent from model+response+logs (SC-004, non-circular); not-connected/needs-reconnection/transient → DISTINCT
│                                             #   AvailabilityStatus (FR-004); empty-window → DATA+[]; oversized window (> max-window) → rejected/clamped; single-member happy path
├── CalendarPanelAvailabilityTest.java        # US1: 5-member bounded-parallel panel (SC-001); a MICROSOFT-connected member → NOT_CONNECTED pre-F11
├── CalendarEventCreateIntegrationTest.java   # US2: create → event id + ManagedCalendarEvent CREATED + one CALENDAR_EVENT_CREATED audit (NO title/loc in it);
│                                             #   idempotent sequential (double create → one event, 409→ok, SC-008); raw-driver doc has refs+instants only, NO content (D14)
├── CalendarConcurrentCreateTest.java         # SC-008: GATED two-thread createPanelEvents(same ws,bookingRef,member) released by gate(2) → exactly ONE Google
│                                             #   insert (stub-recorded) + ONE managedCalendarEvents row via the unique-index DuplicateKeyException claim (F01.1 gated-CAS pattern)
├── CalendarEventDstTest.java                 # US2/SC-005: assert the RECORDED request body's dateTime offset + IANA timeZone for TWO instants straddling a
│                                             #   spring-forward boundary (offset must change) — not an Instant round-trip
├── CalendarEventUpdateDeleteTest.java        # US3: update = stub records a PATCH on the SAME providerEventId (in place, no new insert); delete; delete/patch of a
│                                             #   gone (404) event → success (FR-011/SC-008)
├── CalendarRollbackIntegrationTest.java      # US3: partial-create → compensating delete, zero orphans asserted via the STUB's residual event store (SC-007);
│                                             #   per-eventId persistent delete-503 → that member CLEANUP_INCOMPLETE + CALENDAR_EVENT_CLEANUP_INCOMPLETE audit + orphan STILL present (FR-016a)
├── CalendarApiRetryTest.java                 # US4: 429,429,200→success; persistent 503→CalendarApiException(transient) after max-3 + claim row absent/non-CREATED (FR-014);
│                                             #   403 rateLimitExceeded→retried; 403 insufficientPermissions & 401→NEEDS_RECONNECTION+CALENDAR_RECONNECT_REQUIRED, NO retry (D9/B1);
│                                             #   pure-unit reason-aware classifier truth table + backoff+jitter bound (delay <= base*2^n + jitterMax)
├── CalendarAvailabilityContractTest.java     # preview: 5 roles self-scoped 200; 401 unauth; Cache-Control no-store; busy[] has no content field;
│                                             #   two members never see each other's busy data (FR-018); RbacEndpointInventoryTest stays green
├── CalendarEventLogPiiScanTest.java          # SC-003: root TRACE scoped to com.cadence; drive preview+create+update+delete+a RETRY-path failing call; assert
│                                             #   FIVE sentinel categories ABSENT (token/secret, title, location, dial-in/phone, attendee-email, provider-account-email)
│                                             #   + positive vacuity guard (a known internal id IS detected) (FR-017a/b/018a)
├── CalendarEventRestartPersistenceTest.java  # cold MongoTemplate reads managedCalendarEvents back (no converter needed; refs survive)
└── StubGoogleCalendar.java                   # NEW (sibling, NOT a StubProvider subclass — D12) — JDK HttpServer: METHOD+path matching (DELETE/PATCH have no body),
                                              #   PER-operation/PER-eventId status SEQUENCES (429,429,200; create-ok-then-delete-503), an in-memory event store holding
                                              #   seeded title/attendees (freeBusy projects only start/end → SC-004 non-circular), request recording, gate(n) latch

frontend/src/app/features/calendar/
├── calendar-connections.component.ts         # MODIFIED — add "Preview my availability" action → calendar.service.previewAvailability();
│                                             #   render busy blocks / "you appear free" / reconnect-prompt (reuse existing status surface); localized strings
├── calendar.service.ts                       # MODIFIED — add previewAvailability(): GET internal/calendar/availability/preview (typed)
└── calendar-connections.component.spec.ts    # MODIFIED — Jasmine: render DATA(busy)/DATA(empty=free)/NEEDS_RECONNECTION states; preview button calls service
frontend/e2e/calendar-connections.spec.ts     # MODIFIED — Playwright: connect (stub) → Preview → busy blocks shown
```

**Test isolation note (shared singleton container)**: every new test class MUST clean `managedCalendarEvents`, `calendarConnections`, and `authAuditLog` in `@BeforeEach` with `mongoTemplate.remove(new Query(), Type.class)` — never `dropCollection` (drops the Mongock `007` indexes; CLAUDE.md F00.1 lesson). Connections are seeded via the F01.1 production path against the stub; calendar API responses come from `StubGoogleCalendar`. Concurrency/idempotency tests use the stub's **gate latch** so the assertion can't pass vacuously.

**Structure Decision**: Web-application layout (constitution Reference Source Layout). F10 *extends* the F01.1 calendar package: one self-scoped controller, one collection + repository, two services + the widened `CalendarProviderClient` with a `GoogleCalendarClient` impl behind it (Dependency Policy), a retry helper + a bounded fan-out executor bean, one Mongock changeset (`007`), and a one-action extension of the existing frontend calendar feature. It reuses the F01.1 token store/crypto/audit and the F02 security chains unchanged (the preview endpoint is `isAuthenticated()`, so no `SecurityConfig` change — unlike F01.1, no callback entry point is needed). It modifies exactly: `CalendarProviderClient` (+4 methods), `CalendarDtos` (+1 response), `AuthEventType` (+4 values), `application.yml` (+`calendar.api.*`, scope change), and `ci.yml` (content sentinels). No new top-level structure, no new dependency.

## Complexity Tracking

| Decision | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| New `managedCalendarEvents` collection (vs storing the eventId on a future F13 booking doc) | F10 must support idempotent create, update, delete, and **rollback enumeration** now; F13 (the booking doc's owner) does not exist yet, so F10 needs a durable home for per-participant provider event references and the `CLEANUP_INCOMPLETE` reconciliation record (FR-012/FR-016a) | Deriving event ids on the fly (deterministic id, D6) covers create idempotency but leaves nothing to **enumerate** for rollback/cancel and no place to record an un-deletable orphan; F13 can later reference `bookingRef` without owning F10's references. |
| Bounded fan-out `ExecutorService` for the panel read | A 5-person panel is inherently N token-scoped free/busy calls (per-member tokens, D4); sequential risks the 5 s SC-001 budget | An unbounded `parallelStream`/thread-per-member violates "bounded" (FR-005) and risks resource exhaustion; a broker/queue is forbidden (C2) and absurd for in-request fan-out. A single small fixed pool is the minimum that meets the budget. |
| `CalendarApiRetry` (new) rather than reusing F01.1 `refreshWithRetry` | The calendar-API path needs **jitter** (backlog AC) to avoid synchronized panel-wide retry storms; F01.1's token-endpoint retry is linear, no jitter, and coupled to the OAuth gateway | Reusing the linear retry would miss the required jitter and entangle calendar-API retries with token-refresh retries. ~30 lines of backoff math; a Resilience4j/Spring-Retry dependency for that is rejected (C4). |
| Member **availability-preview** UI (a small surface F10 adds) | Constitution §II forbids shipping backend-only work as done; the panel/booking paths are system-triggered (F13), so the preview is F10's genuine Angular→Spring→provider end-to-end leg, and it usefully proves a connection can actually read availability (not just hold a token) | Shipping F10 as a pure backend adapter risks the §II "backend-only as done" prohibition; building the full booking UI steals F13/F14 scope (§I). The preview is the minimum honest §II leg. Flagged for the review to confirm it is not YAGNI. |
