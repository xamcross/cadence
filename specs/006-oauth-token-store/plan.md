# Implementation Plan: OAuth Token Store (Calendar Connections)

**Branch**: `006-oauth-token-store` | **Date**: 2026-06-15 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/006-oauth-token-store/spec.md`

## Summary

Deliver the **calendar-credential foundation** (backlog F01.1) that every calendar feature (F10 Google, F11 Microsoft) depends on: a member connects their Google/Microsoft calendar once via the provider's free/busy consent screen, and Cadence holds the resulting credential **encrypted at rest** and **auto-refreshes** it so later free/busy reads never re-prompt. F01.1 owns the credential lifecycle only — **no free/busy read, no event write** (those are F10/F11).

Load-bearing engineering decisions:
1. **Explicit authorization-code + refresh behind the `CalendarProvider` interface** (research D1) — NOT Spring `oauth2Login`/`oauth2Client` (those are for *login* and bring storage-model + filter-collision friction). Provider calls use `RestClient` (spring-web). **Zero new dependency.**
2. **Encryption-at-rest reuses `PiiStringConverter`/`MongoPiiConfig`** (D2) for the refresh token, cached access token, provider account id, and the PKCE verifier — raw-driver reads are ciphertext (SC-002).
3. **Single-use `OAuthFlowState` + PKCE + `state`↔session double-binding** (D4) closes login-CSRF and flow-hijack; a TTL index auto-reaps abandoned flows.
4. **Optimistic `tokenVersion` CAS** (D5) makes concurrent refresh exactly-one-writer (a 5-person free/busy storm cannot clobber a rotated refresh token) — lock-free, broker-free (C2).
5. **Permanent (`invalid_grant`) vs transient (429/5xx)** failure split (D6): the former flips status to `NEEDS_RECONNECTION` (member-visible reconnect, no retry); the latter does bounded backoff and stays `CONNECTED`.
6. **Free/busy-only scope** with the §VIII-required justification (D7); broader write scopes deferred to F10/F11.
7. **Structural cross-member isolation** — every HTTP endpoint acts on the authenticated principal only (no `memberId` in any path), so FR-018 needs no scoping query.
8. **Reuse, not new infra**: `AuthAuditService`/`AuthEventType` (+3 values) for the audit; `RoleService.guardedDeactivate` for FR-007 cleanup; the existing `@Order(3)` chain extended only by one **scoped callback `AuthenticationEntryPoint`** (so an expired session during consent redirects instead of bare-401-ing — the `/api/**` 401 + actuator contracts are otherwise untouched); Mongock `ChangeUnit006`; WireMock (already a test dep) to stub the provider. **No topology, stack, or dependency change.**

## Technical Context

**Language/Version**: Java 21 (backend, toolchain pinned); TypeScript 5.4 / Angular 17.3 (frontend)
**Primary Dependencies**: Spring Boot 3.3.5 (web — incl. `RestClient`, data-mongodb, security w/ method security, actuator, aop, **oauth2-client already present from F01**); Mongock 5.4.4; logstash-logback-encoder 9.0. **No new backend or frontend runtime dependency** — OAuth HTTP via `RestClient`; crypto via `PiiCrypto`/`PiiStringConverter`/`MongoPiiConfig`; audit via `AuthAuditService`. Test-only: WireMock `3.9.1` (already present, F01 OIDC stubbing) for the provider authorize/token/revocation stubs; `spring-security-test` (already present) for per-role post-processors.
**Storage**: MongoDB 7.x (Atlas prod, Testcontainers `mongo:7` tests). Adds two collections — **`calendarConnections`** (one per member+provider; tokens + account id encrypted) and **`calendarOAuthState`** (single-use, TTL-reaped in-flight flow state; PKCE verifier encrypted). Reuses `members`/`sessions` (actor) and `authAuditLog` (extended with three event types).
**Testing**: JUnit 5 + Testcontainers (integration: raw-driver ciphertext, Clock-driven transparent refresh, rotated-refresh persistence, concurrent-refresh CAS latch, invalid_grant→NEEDS_RECONNECTION, transient→bounded-retry-stays-CONNECTED, single-use state replay, deactivation cleanup), MockMvc (5-role contract + two-member isolation + 401 + TRACE secret-scan), Mockito (unit: failure classifier, expiry/skew decision), Jasmine (frontend status rendering + any-role route), Playwright (E2E connect→status→disconnect against the stub).
**Target Platform**: Fly.io single Machine (backend JAR), Cloudflare Pages (Angular SPA), MongoDB Atlas.
**Project Type**: Web application (Angular frontend + Spring Boot backend).
**Performance Goals**: `validAccessToken` is a single indexed read when the token is fresh; a refresh is one outbound token-endpoint POST + one CAS write, returning within 5 s under normal provider latency (SC-005). No hot-path scan, no scheduled job.
**Constraints**: Single instance + MongoDB only — no Redis/queue/cache/object-store (§IV / C2); all credential material encrypted at rest and never returned/logged (§VIII, FR-008/FR-009/FR-010); free/busy-only scope (§VIII, FR-002); concurrent refresh must not corrupt or double-rotate (FR-014); zero token/secret in logs incl. TRACE (SC-003); zero tool downloads (§X); any new `.ps1` pure ASCII (§V — none expected).
**Scale/Scope**: MVP single workspace (tens–hundreds of members, ≤2 connections each). 4 user stories, 20 FRs, 7 SCs.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Gate | Question | Result |
|---|---|---|
| **C1** | Within MVP scope (spec §11)? | ✅ PASS — F01.1 is the Tier-0 foundation that the §11 MVP "Google + Microsoft calendar sync" depends on; it is the explicitly-deferred-from-F01 token store (`specs/002-authentication/spec.md:183,207`). No deferred capability is pulled in (write scopes / event CRUD stay in F10/F11). |
| **C2** | New service, queue, or replica? | ✅ PASS — two new MongoDB collections on the existing instance; refresh is synchronous (no broker, no `@Scheduled`); abandoned-flow cleanup is a **TTL index**, not a task; no cache/replica/object-store. |
| **C3** | Exposes candidate PII to unauthorized roles? | ✅ PASS — no candidate data at all. Member calendar credentials are encrypted at rest and reachable **only by the owning member** (structural — no cross-member path); even Admin cannot read another member's tokens (FR-018). |
| **C4** | Dependency outside the fixed stack? | ✅ PASS — **zero new runtime dependencies**; `RestClient` is in spring-web; WireMock is an existing test dep. The provider SDKs are explicitly NOT added (wrapped behind `CalendarProvider`, Dependency Policy). |
| **C5** | New/modified Windows scripts contain non-ASCII? | ✅ PLANNED — no new `.ps1` expected; the `ci.yml` change is YAML (LF). Any script change byte-scanned before done. |
| **C6** | Multi-role sub-agent review (≥3) scheduled? | ✅ DONE+SCHEDULED — this plan reviewed by ≥3 roles (user-requested "review with sub-agents") before tasks; final implementation review at task close. |
| **C7** | Downloads any build tool/runtime/CLI? | ✅ PASS — cached Gradle 9.4.0, installed JDK 21, existing Angular CLI; no downloads. |

**Initial gate: PASS** (no new dependency, no topology change, no stack change).

### Post-Design Re-Check (after Phase 1 + §VI plan review)

Completed after the multi-role plan review (Backend/DevOps, Security/OAuth, QA, Front-End — **all four APPROVE-WITH-CHANGES, no BLOCKERs**; full log + dispositions in `checklists/requirements.md`). All accepted findings were folded into `research.md`/`data-model.md`/`contracts/calendar-oauth-api.md`/`spec.md`/this plan. **Result: PASS, unchanged gate status** — every correction was a design/test-precision fix; none added a dependency, service, or topology, and none moved a gate to FAIL.

Load-bearing corrections folded in:
1. **TTL index API** (Backend-MAJOR) — `ChangeUnit006` uses `new IndexOptions().expireAfter(0L, TimeUnit.SECONDS)`, NOT an index-key `Document("expireAfterSeconds",0)` (which would silently build a plain field index and never expire).
2. **Single-use state consume** (Backend/Security-MAJOR) — `mongoTemplate.findAndRemove` only; `OAuthFlowStateRepository` exposes no plain finder (no TOCTOU replay window).
3. **Callback on expired session** (Backend/Security-MAJOR) — scoped callback `AuthenticationEntryPoint` 302-redirects to `?error=session_expired` instead of a bare 401 (the one `SecurityConfig` change).
4. **Redirect hardening + mix-up** (Security-MAJOR) — all callback redirects built from the configured `spaBaseUrl` + allowlisted enum (no open redirect); token exchange driven off the consumed `state.provider` (provider-confusion structurally closed).
5. **Member-erasure cleanup** (Security-MAJOR) — `disconnectAll` is the canonical primitive wired into deactivation now and verified directly; documented that any future member-erasure path MUST call it (no member-erasure path exists yet — F04 erases candidates).
6. **Refresh-rotation footgun** (Security) — persist a new refresh token only when the response carries one; null the worthless access token on `invalid_grant`; transient failure leaves the row byte-identical.
7. **Coverage gaps** (QA-MAJOR) — added asserting tests for FR-002 (exact free/busy scope on the authorize URL), FR-020 (audit rows per transition), the four callback negative redirects incl. the no-refresh-token edge, and a **gated** concurrency test (WireMock token response released only after all N threads pass the expiry check, so it cannot vacuously pass); SC-005 re-scoped to a structural one-call assertion bounded by RestClient timeouts.
8. **`$unset` ban + RestClient timeouts + no-store + account sentinel** (Backend/Security/QA-MINOR) — clear converter fields with `.set(field,null)`; configure connect/read timeouts; `Cache-Control: no-store` on `GET /connections`; add a provider-account-email sentinel to the log scan + drive the failing-revoke path.
9. **Frontend flow** (Frontend-MAJOR) — Connect uses `window.location.href` (not `Router`) after the `start` JSON response; the component reads `?connected`/`?error` and renders a localized `role="alert"` banner; nav link outside any role `@if`.

Key gate confirmations:
- **C2 holds** — both collections on the existing instance; no broker (synchronous refresh + CAS); abandoned-flow reaping is a TTL index, not a scheduler task.
- **C3 holds** — no candidate data; member tokens encrypted + owner-only by construction.
- **C4 / C7 unchanged** — zero new runtime deps, zero downloads (`RestClient` + existing WireMock).
- **§VIII** — free/busy-only scope with documented justification (D7), all secrets encrypted at rest (D2), single-use+bound `state`+PKCE (D4), zero-secret logs with the sentinel-extended CI scan (D11).

## Project Structure

### Documentation (this feature)

```text
specs/006-oauth-token-store/
├── plan.md              # This file
├── research.md          # Phase 0 — decisions D1..D14
├── data-model.md        # Phase 1 — CalendarConnection, OAuthFlowState, enums, indexes, CalendarProvider contract
├── quickstart.md        # Phase 1 — local run + manual + test verification
├── contracts/
│   └── calendar-oauth-api.md   # Phase 1 — REST + internal service (forward) contracts; RBAC matrix
├── checklists/
│   └── requirements.md  # Spec quality + spec/plan review logs
└── tasks.md             # Phase 2 (/speckit.tasks — NOT created here)
```

### Source Code (repository root)

```text
backend/src/main/java/com/cadence/
├── api/
│   ├── CalendarConnectionController.java   # NEW — /api/internal/calendar/connections (list/start/callback/disconnect)
│   │                                       #   ALL @PreAuthorize("isAuthenticated()"); acts on principal only (no memberId in path)
│   ├── CalendarDtos.java                   # NEW — ConnectionRow / ConnectionList / StartResponse records; NO token on any response
│   └── CalendarExceptionHandler.java       # NEW — @RestControllerAdvice → {error,message}; unsupported_provider (400). Reuses F01/F02 envelope
├── domain/
│   ├── CalendarConnection.java             # NEW — @Document("calendarConnections"); refreshToken/accessToken/providerAccountId
│   │                                       #   (encrypted, @Field write=NON_NULL), status, tokenVersion; hand toString() omits secrets
│   ├── OAuthFlowState.java                  # NEW — @Document("calendarOAuthState"); id=state, memberId, provider, pkceVerifier(encrypted), expiresAt(TTL)
│   ├── CalendarProvider.java               # NEW (enum) — GOOGLE | MICROSOFT  (provider identity)
│   └── ConnectionStatus.java               # NEW (enum) — CONNECTED | NEEDS_RECONNECTION
├── integration/
│   ├── CalendarProviderClient.java         # NEW (interface) — the forward CalendarProvider contract: validAccessToken(ws,member);
│   │                                       #   GoogleCalendarClient/MicrosoftCalendarClient impls land in F10/F11
│   ├── GoogleOAuthGateway.java             # NEW — authorize-URL builder + code/refresh/revoke HTTP via RestClient (Google endpoints)
│   ├── MicrosoftOAuthGateway.java          # NEW — same, Microsoft endpoints
│   └── OAuthGateway.java                    # NEW (interface) — provider-agnostic authorize/exchange/refresh/revoke seam used by the service
├── repository/
│   ├── CalendarConnectionRepository.java   # NEW — findByWorkspaceIdAndMemberId(...); findBy...AndProvider; deleteByWorkspaceIdAndMemberId
│   └── OAuthFlowStateRepository.java        # NEW — findAndDelete by id is done via MongoTemplate (single-use CAS), repo for save
├── service/
│   ├── CalendarConnectionService.java      # NEW — start() (create OAuthFlowState + authorize URL), completeCallback() (single-use state
│   │                                       #   consume + bind + exchange + upsert CONNECTED + audit), list(), disconnect(), disconnectAll() (D12 seam)
│   ├── CalendarTokenService.java            # NEW — validAccessToken(ws,member,provider): expiry+skew decision, refresh via gateway,
│   │                                       #   tokenVersion CAS (D5), failure classify (D6); injected Clock
│   └── OAuthFailureClassifier.java          # NEW — maps token-endpoint error → PERMANENT(invalid_grant)|TRANSIENT(429/5xx)|FATAL (unit-tested)
├── config/
│   ├── CalendarOAuthProperties.java        # NEW — @ConfigurationProperties("calendar.oauth") per-provider client/endpoints, stateTtl,
│   │                                       #   accessTokenSkew, RestClient connect/read timeouts (Backend #6 — bound the SC-005 budget)
│   ├── MongoPiiConfig.java                 # MODIFIED — register PiiStringConverter for CalendarConnection.{refreshToken,accessToken,
│   │                                       #   providerAccountId} + OAuthFlowState.pkceVerifier (D2; same converter instance, one bean)
│   └── migration/
│       └── ChangeUnit006_CalendarOAuthIndexes.java # NEW — unique calendarConnections{workspaceId,memberId,provider};
│                                            #   TTL calendarOAuthState{expiresAt} expireAfterSeconds:0
├── domain/AuthEventType.java               # MODIFIED — append CALENDAR_CONNECTED, CALENDAR_DISCONNECTED, CALENDAR_RECONNECT_REQUIRED
├── service/RoleService.java                # MODIFIED — guardedDeactivate(...) also calls calendarConnectionService.disconnectAll(ws,member),
│                                           #   best-effort wrapped so a provider-revoke failure cannot abort deactivation (D12 / Backend #4)
└── security/SecurityConfig.java            # MODIFIED (scoped) — @Order(3) exceptionHandling adds a callback-matcher
                                            #   AuthenticationEntryPoint → 302 {spaBaseUrl}/calendar/connections?error=session_expired
                                            #   (Security #1); /api/** 401 + actuator contracts otherwise untouched

backend/src/test/java/com/cadence/
└── calendar/
    ├── CalendarConnectIntegrationTest.java     # US1: start→callback(stubbed code exchange)→CONNECTED; raw-driver ciphertext for
    │                                            #   refreshToken/accessToken/providerAccountId (SC-002); re-connect upserts (one row, version++);
    │                                            #   start authorizeUrl asserts EXACT free/busy scope + offline params, NO write scope (FR-002, QA#1/#2);
    │                                            #   callback negatives (QA#8/#9): unknown/expired state→?error=invalid_state; state.memberId!=session→reject
    │                                            #   (FR-018 cross-member-attach, label as SC-007); provider error param→?error=consent_denied;
    │                                            #   exchange returns NO refresh_token→?error=no_offline_grant, NO usable row (spec edge); state.provider!=path→reject;
    │                                            #   each path stores NO row; assert one CALENDAR_CONNECTED audit row, no token/account in it (FR-020, QA#13)
    ├── CalendarRefreshIntegrationTest.java      # US2: expired access + valid grant → validAccessToken refreshes (stub hit once), fresh token (SC-004);
    │                                            #   rotated refresh token persisted AND next refresh POSTs the NEW token (WireMock body match), old gone (FR-013, QA#5);
    │                                            #   refresh response OMITTING a refresh_token PRESERVES the existing one (Security#8 rotation footgun);
    │                                            #   CONCURRENT validAccessToken latch N>=20 with the WireMock token response GATED until all threads pass the
    │                                            #   expiry check → assert EXACTLY ONE token POST + tokenVersion+1 (D5; gate avoids vacuous pass, QA#6);
    │                                            #   Clock advanced past skew triggers refresh, within skew does not
    ├── CalendarReconnectIntegrationTest.java    # US4: stub invalid_grant → status NEEDS_RECONNECTION + accessToken nulled + CalendarReconnectRequiredException
    │                                            #   + NO retry + one CALENDAR_RECONNECT_REQUIRED audit; transient 503 → bounded retry (<=3) then
    │                                            #   CalendarProviderTransientException, status STAYS CONNECTED and row BYTE-IDENTICAL (tokenVersion/creds
    │                                            #   unchanged — no partial write, FR-016/QA#7, SC-006); GET /connections distinguishes CONNECTED vs NEEDS_RECONNECTION
    ├── CalendarDisconnectIntegrationTest.java   # US3: disconnect deletes row + best-effort revoke (happy path: WireMock VERIFY revoke called, QA#10;
    │                                            #   failure path: revoke 500 ignored, row still gone, FR-006); idempotent 204 for absent; one CALENDAR_DISCONNECTED
    │                                            #   audit; FR-007 deactivation seam (RoleService.guardedDeactivate) deletes connections — seed BOTH providers,
    │                                            #   assert zero rows (QA#11); direct disconnectAll test = member-erasure forward seam proof (Security#6)
    ├── CalendarRbacContractTest.java            # SC-007: 4 endpoints x 5 roles all isAuthenticated() ok; two members each see only own GET /connections
    │                                            #   (member A never sees B's connectedAccount, FR-018); unauthenticated → 401; mutating calls .with(csrf());
    │                                            #   unsupported provider → 400 on POST start AND DELETE, and callback→error-redirect not 500 (FR-019, QA#12);
    │                                            #   GET /connections response carries Cache-Control: no-store (Security#10)
    ├── OAuthFailureClassifierTest.java          # PURE Mockito unit: invalid_grant→PERMANENT; 429/500/503/network→TRANSIENT; other 4xx→FATAL (truth table)
    ├── CalendarTokenExpiryTest.java             # PURE Mockito unit: expiry+skew decision (fresh vs near-expiry vs expired) via MutableClock; null access → refresh
    ├── CalendarRestartPersistenceTest.java      # cold MongoTemplate (fresh MongoPiiConfig converter) decrypts refreshToken to original (F03 cold-template pattern)
    └── CalendarLogPiiScanTest.java              # SC-003: root TRACE; drive start→callback→refresh→reconnect→disconnect AND a FAILING revoke (500/network,
                                                 #   the path most likely to echo a token via an RestClient error — Security#11); assert seeded access-token/
                                                 #   refresh-token/client-secret/auth-code AND provider-account-email (sentinel-acct-*@example.invalid, FR-010/QA#3)
                                                 #   SENTINELS absent at any level + a positive vacuity guard (the sentinels really traversed the path)

frontend/src/app/
├── features/calendar/
│   ├── calendar-connections.component.ts    # NEW — any authenticated role; per-provider status (Not connected / Connected as … / Needs reconnection);
│   │                                         #   Connect = POST start (button disabled in-flight) then window.location.href = authorizationUrl (NOT Router, Frontend#2);
│   │                                         #   reads ?connected/?error query param on return → localized role="alert" banner + refetch (Frontend#3);
│   │                                         #   Disconnect uses confirming=signal(false) two-state confirm; "Connected as {acct}" via $localize named placeholder
│   └── calendar.service.ts                   # NEW — typed HTTP via ${environment.apiBaseUrl}/internal/calendar/** (XSRF+creds auto-attached); $localize error text
├── core/auth/...                             # REUSED UNCHANGED — authGuard only (no role gate)
├── features/shell/shell.component.ts         # MODIFIED — add a "Calendar connections" nav link for ALL authenticated roles (no @if role)
└── app.routes.ts                             # MODIFIED — path 'calendar/connections' canActivate:[authGuard]  (any role)

frontend/src/app/features/calendar/calendar-connections.component.spec.ts   # Jasmine: renders all three statuses; reachable by every role (authGuard only);
                                                                            #   each ?error= code renders its localized alert (Frontend#7); Connect disabled during in-flight start
frontend/e2e/calendar-connections.spec.ts                                   # Playwright: connect (stub) → Connected → disconnect → Not connected

backend/src/main/resources/application.yml   # MODIFIED — calendar.oauth.* block (explicit endpoint URIs, dev stub defaults, secret env refs)
.github/workflows/ci.yml                      # MODIFIED — extend the PII/secret log-scan with token sentinels (access_token/refresh_token/client_secret/code=)
```

**Test isolation note (shared singleton container)**: every new test class MUST clean `calendarConnections`, `calendarOAuthState`, and `authAuditLog` in `@BeforeEach` with `mongoTemplate.remove(new Query(), Type.class)` — never `dropCollection` (drops the Mongock `006` unique + TTL indexes; CLAUDE.md F00.1 lesson). The TTL index does NOT make documents disappear mid-test (the background reaper runs ~every 60 s), so expiry tests assert behaviour via the handler's explicit `expiresAt > now(clock)` check, not by waiting for the reaper. **Seeding rule**: connections are created via the production `completeCallback`/`start` path against the WireMock stub, except the two pure-unit tests (classifier, expiry) which hand-build inputs over mocks (no Mongo).

**Structure Decision**: Web-application layout (constitution Reference Source Layout). F01.1 *extends* the F01 scaffold with one controller (all `/api/internal/calendar/**`, self-scoped, `isAuthenticated()`), two collections + repositories, three services + a provider-OAuth gateway pair behind an interface (Dependency Policy), one Mongock changeset (`006`), and a small member-self frontend feature. It reuses the F02 `@Order(3)` chain + `RestAccessDeniedHandler` (extended only by a scoped callback entry point), the F01 PII crypto, the F01 audit, and the F02 `guardedDeactivate` seam. It modifies exactly: `MongoPiiConfig` (+4 fields), `AuthEventType` (+3 values), `RoleService.guardedDeactivate` (+cleanup call), `SecurityConfig` (+scoped callback entry point), `application.yml` (+`calendar.oauth.*`), and `ci.yml` (token + account sentinels). No new top-level structure, no new dependency.

## Complexity Tracking

| Decision | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| Explicit authorization-code + refresh (not Spring `oauth2Login`/`oauth2Client`) | Calendar connection is a *second authorization* by an already-logged-in member; it must not re-authenticate them, and the encrypted-Mongo + status + CAS model does not fit Spring's `OAuth2AuthorizedClient` storage (research D1) | `oauth2Login` re-issues the session (conflates identity). `oauth2Client` + a custom `OAuth2AuthorizedClientRepository` is *more* code (conversion glue) and collides with the existing login registration on `/oauth2/authorization/**`. The explicit flow is smaller and fully Clock-driven/stub-testable. |
| Two collections (`calendarConnections` + `calendarOAuthState`) rather than one | The in-flight flow state (PKCE verifier, single-use `state`, TTL auto-reap) has a different lifecycle (seconds, single-use, auto-expiring) than the durable connection (months) | Folding flow state onto the connection doc would mean a half-built connection row before consent completes (an unusable "connection" the member sees), and no clean TTL reap. Separation keeps the durable row meaning exactly "connected". |
| `OAuthGateway`/`CalendarProviderClient` interfaces with per-provider impls now (Google + Microsoft) | The constitution Dependency Policy mandates wrapping each provider behind a domain interface; both providers differ in scope strings, offline params, and error bodies | A single switch-on-provider class is simpler but bakes provider specifics into the service and violates the "swap provider without touching service code" rule; F10/F11 widen these same interfaces, so the seam is real, not speculative. |
| Optimistic `tokenVersion` CAS for refresh | A 5-person free/busy panel (F10) fires concurrent `validAccessToken` for the same member; a naive read-modify-write clobbers a rotated refresh token and breaks the next refresh (FR-014) | A JVM lock is correct only on one instance and still races a future second instance; a refresh lease adds latency + a lease-expiry edge case. The version CAS is lock-free, broker-free (C2), and matches the proven F02/F04 CAS pattern. |
