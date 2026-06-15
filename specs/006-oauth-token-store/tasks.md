---
description: "Task list for OAuth Token Store (Calendar Connections) — F01.1"
---

# Tasks: OAuth Token Store (Calendar Connections)

**Input**: Design documents from `C:\Users\xamcr\Cadence\specs\006-oauth-token-store\`
**Prerequisites**: plan.md, spec.md, research.md (D1–D14), data-model.md, contracts/calendar-oauth-api.md

**Tests**: INCLUDED and TDD-ordered — constitution §VII mandates test-first for all non-trivial backend logic and acceptance-criteria paths. Within each story, write the listed tests FIRST and confirm they FAIL before implementing.

**Organization**: Tasks are grouped by user story (US1–US4) so each is an independently testable increment.

## Path Conventions (web app — plan.md structure)

- Backend main: `backend/src/main/java/com/cadence/`
- Backend test: `backend/src/test/java/com/cadence/calendar/`
- Frontend: `frontend/src/app/`

## Build/run flags (constitution §X — zero downloads)

Run backend tests with `JAVA_HOME=C:/jdk-24.0.1`, the cached `gradle-9.4.0` binary, `-Dapi.version=1.41`, `DOCKER_HOST=npipe:////./pipe/docker_engine`. The first multi-class Testcontainers run after a recompile may throw a one-time `GenericContainer` class-init error — re-run. Never trigger a Gradle wrapper download.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Enums, config, and test scaffolding all later phases build on.

- [x] T001 [P] Create `CalendarProvider` enum (`GOOGLE`, `MICROSOFT`) in `backend/src/main/java/com/cadence/domain/CalendarProvider.java` (data-model §3). Add a case-insensitive `fromPath(String)` that throws `UnsupportedProviderException` for anything else (FR-019).
- [x] T002 [P] Create `ConnectionStatus` enum (`CONNECTED`, `NEEDS_RECONNECTION`) in `backend/src/main/java/com/cadence/domain/ConnectionStatus.java` (data-model §3; "Not connected" = doc absence, never a value).
- [x] T003 [P] Add `CALENDAR_CONNECTED`, `CALENDAR_DISCONNECTED`, `CALENDAR_RECONNECT_REQUIRED` to `backend/src/main/java/com/cadence/domain/AuthEventType.java` (append only — never reorder existing values; research D13).
- [x] T004 [P] Create `CalendarOAuthProperties` (`@ConfigurationProperties("calendar.oauth")`) in `backend/src/main/java/com/cadence/config/CalendarOAuthProperties.java` with per-provider `clientId/clientSecret/authorizationUri/tokenUri/revocationUri/scope` plus shared `stateTtl`, `accessTokenSkew`, `connectTimeout`, `readTimeout` (research D9 — auto-registered by the existing `@ConfigurationPropertiesScan` on `CadenceApplication`, no enable task).
- [x] T005 Add the `calendar.oauth.*` block to `backend/src/main/resources/application.yml` — explicit endpoint URIs (NOT issuer-uri; F01 eager-discovery footgun), Google `calendar.freebusy` + Microsoft `Calendars.Read offline_access` scopes, dev defaults pointing at a local stub host, secret env refs `GOOGLE_CAL_CLIENT_ID/SECRET` + `MS_CAL_CLIENT_ID/SECRET`, sensible timeouts (depends on T004).
- [x] T006 [P] Create the calendar test support fixture `backend/src/test/java/com/cadence/calendar/CalendarTestSupport.java` — extends/reuses `BaseIntegrationTest` (singleton `@ServiceConnection` container — NOT `@Container`); a WireMock server for the provider authorize/token/revocation endpoints with per-test `reset()`; a helper that seeds a `CONNECTED` connection via the production `start`+`completeCallback` path against the stub; and a `@BeforeEach` that cleans `calendarConnections`, `calendarOAuthState`, `authAuditLog` with `mongoTemplate.remove(new Query(), …)` (NEVER `dropCollection` — drops the `006` indexes).

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Domain docs, persistence, migration, provider gateways, and the shared security/exception scaffolding every user story needs.

**⚠️ CRITICAL**: No user story work can begin until this phase is complete.

- [x] T007 [P] Create `CalendarConnection` `@Document("calendarConnections")` in `backend/src/main/java/com/cadence/domain/CalendarConnection.java` (data-model §1): fields per table; `refreshToken`/`accessToken`/`providerAccountId` as `@Field(write = NON_NULL)`; `tokenVersion` (long); hand-written `toString()` that OMITS `refreshToken`/`accessToken`/`providerAccountId`.
- [x] T008 [P] Create `OAuthFlowState` `@Document("calendarOAuthState")` in `backend/src/main/java/com/cadence/domain/OAuthFlowState.java` (data-model §2): `id` = state nonce, `workspaceId`, `memberId`, `provider`, `pkceVerifier`, `createdAt`, `expiresAt`.
- [x] T009 Register the `PiiStringConverter` for `CalendarConnection.{refreshToken,accessToken,providerAccountId}` and `OAuthFlowState.pkceVerifier` in `backend/src/main/java/com/cadence/config/MongoPiiConfig.java` (same converter instance, one bean; research D2) and update the class Javadoc (Backend #8) (depends on T007, T008).
- [x] T010 Create `backend/src/main/java/com/cadence/config/migration/ChangeUnit006_CalendarOAuthIndexes.java` (order `"006"`, never reuse/rename): **unique** `calendarConnections {workspaceId:1,memberId:1,provider:1}`; **TTL** `calendarOAuthState {expiresAt:1}` via `new IndexOptions().expireAfter(0L, TimeUnit.SECONDS)` — NOT an index-key `Document("expireAfterSeconds",0)` (Backend #2). Native `createIndex` + targeted `dropIndex` rollback; never `dropIndexes` (depends on T007, T008).
- [x] T011 [P] Create `CalendarConnectionRepository` in `backend/src/main/java/com/cadence/repository/CalendarConnectionRepository.java`: `findByWorkspaceIdAndMemberId`, `findByWorkspaceIdAndMemberIdAndProvider`, `deleteByWorkspaceIdAndMemberId`.
- [x] T012 [P] Create `OAuthFlowStateRepository` in `backend/src/main/java/com/cadence/repository/OAuthFlowStateRepository.java` — expose `save` ONLY, **no plain finder** (single-use consume is `mongoTemplate.findAndRemove`; a `findById` would reintroduce a TOCTOU replay window — Backend #3 / Security #5).
- [x] T013 [P] Create the typed calendar exceptions in `backend/src/main/java/com/cadence/integration/`: `CalendarReconnectRequiredException`, `CalendarProviderTransientException`, `CalendarNotConnectedException`, and `UnsupportedProviderException` (contracts §7).
- [x] T014 [P] Create the forward `CalendarProviderClient` interface in `backend/src/main/java/com/cadence/integration/CalendarProviderClient.java` — `CalendarProvider id()` + `String validAccessToken(String workspaceId, String memberId)` (data-model §6; concrete Google/Microsoft impls land in F10/F11). NOTE: the interface named `CalendarProvider` in data-model §6 is implemented here as `CalendarProviderClient` and its `CalendarProviderId` is the `CalendarProvider` enum (T001) — a deliberate rename to avoid a domain/interface name clash; both plan.md and these tasks use the renamed forms.
- [x] T015 [P] Create the `OAuthGateway` interface + a `TokenResponse` record in `backend/src/main/java/com/cadence/integration/OAuthGateway.java` — `authorizationUrl(state, codeChallenge, redirectUri)`, `exchangeCode(code, codeVerifier, redirectUri)`, `refresh(refreshToken)`, `revoke(token)`; `TokenResponse(accessToken, refreshTokenOrNull, expiresAt, scope, providerAccountId)`.
- [x] T016 [P] Write `backend/src/test/java/com/cadence/calendar/OAuthFailureClassifierTest.java` FIRST (must FAIL — no classifier yet) — pure Mockito unit truth table: `invalid_grant` → `PERMANENT`; `429`/`500`/`503`/network → `TRANSIENT`; other 4xx → `FATAL`.
- [x] T017 Implement `OAuthFailureClassifier` in `backend/src/main/java/com/cadence/service/OAuthFailureClassifier.java` mapping a token-endpoint error to `PERMANENT|TRANSIENT|FATAL` (research D6) — make T016 pass.
- [x] T018 Implement `GoogleOAuthGateway` in `backend/src/main/java/com/cadence/integration/GoogleOAuthGateway.java` — `RestClient` (spring-web; configure connect/read timeouts from `CalendarOAuthProperties`, Backend #6) against Google endpoints; authorize URL with `calendar.freebusy` scope + `access_type=offline&prompt=consent` + PKCE `code_challenge` (S256); code/refresh/revoke calls; never log tokens/codes/secrets (depends on T015, T004).
- [x] T019 Implement `MicrosoftOAuthGateway` in `backend/src/main/java/com/cadence/integration/MicrosoftOAuthGateway.java` — same shape against Microsoft endpoints; `Calendars.Read offline_access` scope + `offline_access`; PKCE S256 (depends on T015, T004).
- [x] T020 [P] Create `CalendarDtos` (records: `ConnectionRow(provider,status,connectedAccount,connectedAt)`, `ConnectionList`, `StartResponse(authorizationUrl)`) in `backend/src/main/java/com/cadence/api/CalendarDtos.java` — NO token field on any record (contracts §1/§2).
- [x] T021 [P] Create `CalendarExceptionHandler` (`@RestControllerAdvice`) in `backend/src/main/java/com/cadence/api/CalendarExceptionHandler.java` → `{error,message}` with `unsupported_provider` (400), reusing the F01/F02 envelope (contracts §5).
- [x] T022 Add a scoped callback `AuthenticationEntryPoint` to the `@Order(3)` chain in `backend/src/main/java/com/cadence/security/SecurityConfig.java`: a `defaultAuthenticationEntryPointFor` matching `…/calendar/**/callback` that 302-redirects to `{spaBaseUrl}/calendar/connections?error=session_expired` (Security #1). **Register it BEFORE the existing `/api/**` 401 mapping** (entry points fire in registration order, and the callback path matches `/api/**`, so a later registration would never fire). Keep the existing `/api/**`→401 and `/**`→403 mappings after it so the F01 `/api/**` 401 and F00 actuator contracts stay intact; T024 asserts a non-callback `/api/internal/calendar/**` path still 401s when unauthenticated.

**Checkpoint**: Domain, persistence, gateways, and security scaffolding ready — user stories can begin.

---

## Phase 3: User Story 1 — Connect a calendar account (Priority: P1) 🎯 MVP

**Goal**: A member completes free/busy consent and Cadence stores the connection encrypted, showing "Connected".

**Independent Test**: `start` → stubbed provider consent → `callback` → `GET /connections` shows `CONNECTED`; raw-driver read of the row is ciphertext only.

### Tests for User Story 1 (write first, confirm FAIL)

- [x] T023 [P] [US1] Write `backend/src/test/java/com/cadence/calendar/CalendarConnectIntegrationTest.java`: start→callback→`CONNECTED`; raw-driver ciphertext for `refreshToken`/`accessToken`/`providerAccountId` (SC-002); re-connect upserts one row with `tokenVersion++` (FR-004); `start` authorize URL asserts EXACT free/busy scope + offline param, NO write scope (FR-002); callback negatives → no row: unknown/expired state→`?error=invalid_state`, `state.memberId != session`→reject (label as FR-018/SC-007 cross-member-attach), provider `error` param→`?error=consent_denied`, exchange returns no refresh token→`?error=no_offline_grant`, `state.provider != path`→reject, unsupported `{provider}` in the callback path→error redirect (NOT 500); assert exactly one `CALENDAR_CONNECTED` audit row carrying no token/account (FR-020); assert the callback redirect host is always `spaBaseUrl` regardless of an injected `Host`/`X-Forwarded-Host` header or redirect param (open-redirect negative, Security #2).
- [x] T024 [P] [US1] Write `backend/src/test/java/com/cadence/calendar/CalendarRbacContractTest.java` (list/start/callback portion): 5 roles all `isAuthenticated()` ok on `GET /connections`, `POST /{p}/start`, callback; two members each see only their own `GET /connections` (member A never sees B's `connectedAccount`, FR-018); unauthenticated → 401; mutating calls `.with(csrf())`; `unsupported_provider` → 400 on `start`; `GET /connections` response carries `Cache-Control: no-store` (Security #10). (DELETE cases added in US3.)

### Implementation for User Story 1

- [x] T025 [US1] Create `CalendarConnectionService` in `backend/src/main/java/com/cadence/service/CalendarConnectionService.java` with `start(workspaceId, memberId, provider)`: generate a high-entropy `state` (`SecureTokens`) + PKCE verifier/challenge, persist a single-use `OAuthFlowState` (encrypted verifier, `expiresAt = now(clock) + stateTtl`), build the authorize URL via the provider gateway, return it (injected `Clock`). Inject a provider→gateway resolver — constructor-inject `List<OAuthGateway>` and index it into a `Map<CalendarProvider, OAuthGateway>` by each gateway's `id()` — so both `CalendarConnectionService` and `CalendarTokenService` select Google vs Microsoft by `provider` (backend-review #4).
- [x] T026 [US1] Add `completeCallback(provider, code, state, error, principal)` to `CalendarConnectionService`: atomic `mongoTemplate.findAndRemove` the state by `_id`; bind-check `expiresAt>now` + `memberId==principal` + `provider==state.provider`; exchange the code at the **`state.provider`** gateway (mix-up defense, Security #3); reject (no row) when no refresh token returned; upsert `CONNECTED` (encrypted tokens via converter — `$set`, never pre-encrypt, never `$unset`); audit `CALENDAR_CONNECTED` via the existing generic `AuthAuditService.record(type, workspaceId, memberId, outcome, sourceIp)` with `sourceIp = null` (null-safe; there is no calendar-specific audit method — backend-review #1); return an allowlisted SPA redirect built from `spaBaseUrl` (Security #2) (depends on T025).
- [x] T027 [US1] Add `list(workspaceId, memberId)` to `CalendarConnectionService` returning per-provider rows (decrypted `connectedAccount`, status) (depends on T025).
- [x] T028 [US1] Create `CalendarConnectionController` in `backend/src/main/java/com/cadence/api/CalendarConnectionController.java`: `GET /api/internal/calendar/connections` (sets `Cache-Control: no-store`), `POST …/{provider}/start`, `GET …/{provider}/callback` (302 redirect, never JSON) — all `@PreAuthorize("isAuthenticated()")`, all acting on the principal only (no `memberId` in any path); make T023/T024 pass.
- [x] T029 [P] [US1] Create `frontend/src/app/features/calendar/calendar.service.ts` — typed `HttpClient` calls against `${environment.apiBaseUrl}/internal/calendar/**` (XSRF + credentials auto-attached); `$localize` error text.
- [x] T030 [US1] Create `frontend/src/app/features/calendar/calendar-connections.component.ts` (standalone): per-provider status (Not connected / "Connected as {acct}" via `$localize` named placeholder / Needs reconnection); Connect = `start(provider).subscribe(r => window.location.href = r.authorizationUrl)` (NOT `Router`; button disabled in-flight); on load read `?connected`/`?error` via `ActivatedRoute` → localized `role="alert"` banner + refetch `GET /connections` (Frontend #2/#3/#5).
- [x] T031 [US1] Register route `path: 'calendar/connections'`, `canActivate: [authGuard]` (any role, no roleGuard) in `frontend/src/app/app.routes.ts`.
- [x] T032 [US1] Add a "Calendar connections" nav link for ALL authenticated roles in `frontend/src/app/features/shell/shell.component.ts` — placed outside every `@if (m.role …)` block but inside `@if (member(); as m)` (Frontend #1).
- [x] T033 [US1] Write `frontend/src/app/features/calendar/calendar-connections.component.spec.ts` (Jasmine, after T030): renders all three statuses; each `?error=` code renders its localized alert; Connect disabled during in-flight start; reachable by every role (authGuard only).

**Checkpoint**: US1 fully functional — a member can connect and see "Connected"; tokens are encrypted at rest.

---

## Phase 4: User Story 2 — Automatic renewal of expired access (Priority: P1)

**Goal**: An expired access token is transparently refreshed using the stored refresh token, no member prompt.

**Independent Test**: with a connected member whose access is (simulated) expired but grant valid, `validAccessToken` refreshes against the stub and returns a fresh token; concurrent calls refresh exactly once.

### Tests for User Story 2 (write first, confirm FAIL)

- [x] T034 [P] [US2] Write `backend/src/test/java/com/cadence/calendar/CalendarTokenExpiryTest.java` — pure Mockito unit over a `MutableClock`: fresh token (no refresh), within-skew (refresh), expired (refresh), null access (refresh) (FR-012).
- [x] T035 [P] [US2] Write `backend/src/test/java/com/cadence/calendar/CalendarRefreshIntegrationTest.java`: expired access + valid grant → `validAccessToken` refreshes (WireMock token endpoint hit once), returns fresh token (SC-004); rotated refresh token persisted AND the NEXT refresh POSTs the NEW token (WireMock body match), old gone (FR-013); a refresh response OMITTING a refresh token PRESERVES the existing one (Security #8); CONCURRENT `validAccessToken` via `CountDownLatch` N≥20 with the WireMock token response GATED until all threads pass the expiry check → assert EXACTLY ONE token POST + `tokenVersion` +1 (FR-014, D5; the gate prevents a vacuous pass, QA #6).

### Implementation for User Story 2

- [x] T036 [US2] Create `CalendarTokenService` in `backend/src/main/java/com/cadence/service/CalendarTokenService.java` with `validAccessToken(workspaceId, memberId, provider)`: load the connection (else `CalendarNotConnectedException`); if access is null/expired/within `accessTokenSkew` of expiry (injected `Clock`) refresh via the provider gateway, then persist with an atomic `findAndModify({_id, tokenVersion:v} → new tokens [refreshToken only if response carries one], tokenVersion:v+1, lastRefreshAt)`; on zero-match re-read and use the winner's token (no second exchange) (research D5). Make T034/T035 pass. **US2 safety**: the detailed permanent/transient handling lands in US4 (T044), but T036 MUST already guarantee that a refresh failure (any exception from the gateway) propagates WITHOUT a partial write — the CAS `$set` runs only on a successful token response — so US2 in isolation can never corrupt a connection row on a provider error (completeness-review #5).

**Checkpoint**: US2 works — connections survive access-token expiry transparently; concurrency-safe.

---

## Phase 5: User Story 3 — Disconnect a calendar account (Priority: P2)

**Goal**: A member disconnects; stored credentials are deleted and the grant is best-effort revoked.

**Independent Test**: disconnect a connected member → row gone, status "Not connected"; a failing provider revoke does not block deletion; deactivation deletes all of a member's connections.

### Tests for User Story 3 (write first, confirm FAIL)

- [x] T037 [P] [US3] Write `backend/src/test/java/com/cadence/calendar/CalendarDisconnectIntegrationTest.java`: disconnect deletes the row + best-effort revoke (happy path: WireMock VERIFY revoke called, QA #10; failure path: revoke 500 ignored, row still gone — FR-006); idempotent 204 for an absent connection; one `CALENDAR_DISCONNECTED` audit; FR-007 deactivation seam — seed BOTH providers for a member, call `RoleService.guardedDeactivate`, assert zero connection rows (QA #11); a direct `disconnectAll` test as the member-erasure forward-seam proof (Security #6).
- [x] T038 [US3] Extend `CalendarRbacContractTest` with `DELETE …/{provider}`: 5 roles `isAuthenticated()` ok (self only), `.with(csrf())`, `unsupported_provider` → 400 on DELETE.

### Implementation for User Story 3

- [x] T039 [US3] Add `disconnect(workspaceId, memberId, provider)` and `disconnectAll(workspaceId, memberId)` to `CalendarConnectionService`: best-effort provider revoke (failure swallowed, FR-006), delete the row(s) across both providers, audit `CALENDAR_DISCONNECTED` via `AuthAuditService.record(..., sourceIp=null)`; idempotent (depends on T025).
- [x] T040 [US3] Add `DELETE /api/internal/calendar/connections/{provider}` (`@PreAuthorize("isAuthenticated()")`, 204, self only) to `CalendarConnectionController`.
- [x] T041 [US3] In `backend/src/main/java/com/cadence/service/RoleService.java` `guardedDeactivate(...)`, call `calendarConnectionService.disconnectAll(workspaceId, memberId)` alongside `sessions.revokeAllForMember(memberId)` — best-effort wrapped so a provider-revoke failure cannot abort deactivation; do NOT inject `SessionService`/`RoleService` into `CalendarConnectionService` (no cycle — Backend #4 / D12).
- [x] T042 [US3] Add the Disconnect action to `frontend/src/app/features/calendar/calendar-connections.component.ts` using a `confirming = signal(false)` two-state confirm before the destructive `DELETE`; errors in `role="alert"` (Frontend #6).

**Checkpoint**: US1+US2+US3 work — connect, refresh, and disconnect (incl. deactivation cleanup).

---

## Phase 6: User Story 4 — Detect & surface a revoked/broken connection (Priority: P2)

**Goal**: A revoked grant flips to "Needs reconnection" (no infinite retry); a transient outage retries and stays "Connected".

**Independent Test**: stub `invalid_grant` → status `NEEDS_RECONNECTION` + exception, no retry; stub transient `503` → bounded retry then transient exception, status stays `CONNECTED`.

### Tests for User Story 4 (write first, confirm FAIL)

- [x] T043 [P] [US4] Write `backend/src/test/java/com/cadence/calendar/CalendarReconnectIntegrationTest.java`: stub `invalid_grant` → status `NEEDS_RECONNECTION`, `accessToken` nulled, `CalendarReconnectRequiredException`, NO retry, one `CALENDAR_RECONNECT_REQUIRED` audit; stub transient `503` → bounded retry (≤3) then `CalendarProviderTransientException`, status STAYS `CONNECTED` and the row is BYTE-IDENTICAL (tokenVersion/creds unchanged — no partial write, FR-016/QA #7, SC-006); `GET /connections` distinguishes `CONNECTED` vs `NEEDS_RECONNECTION`.

### Implementation for User Story 4

- [x] T044 [US4] Extend `CalendarTokenService` refresh handling: classify the failure via `OAuthFailureClassifier` — PERMANENT (`invalid_grant`) → CAS-flip `status=NEEDS_RECONNECTION` + null `accessToken` (retain `refreshToken`, Security #7) + audit `CALENDAR_RECONNECT_REQUIRED` via `AuthAuditService.record(..., sourceIp=null)` + throw `CalendarReconnectRequiredException` (no retry); TRANSIENT (429/5xx/network) → bounded exponential backoff + jitter (≤3) then throw `CalendarProviderTransientException`, leaving the row unchanged (research D6) (modifies T036).
- [x] T045 [US4] In `frontend/src/app/features/calendar/calendar-connections.component.ts`, render the `NEEDS_RECONNECTION` state with a Reconnect action (reuses the Connect flow) and the `session_expired` error banner; confirm all four `?error=` codes are localized.

**Checkpoint**: All four user stories independently functional.

---

## Phase 7: Polish & Cross-Cutting Concerns

- [x] T046 [P] Write `backend/src/test/java/com/cadence/calendar/CalendarRestartPersistenceTest.java` — a cold `MongoTemplate` (fresh `MongoPiiConfig` converter) decrypts `refreshToken` to the original (F03 cold-template pattern; SC-002 cold path).
- [x] T047 [P] Write `backend/src/test/java/com/cadence/calendar/CalendarLogPiiScanTest.java` — root logger at TRACE; drive start→callback→refresh→reconnect→disconnect AND a FAILING revoke (the likeliest token-in-log path, Security #11); assert seeded high-entropy SENTINELS for access token, refresh token, client secret, auth code, AND provider account email (`sentinel-acct-<hex>@example.invalid`, FR-010/QA #3) are absent at any level, with a positive vacuity guard that the sentinels really traversed the path (SC-003).
- [x] T048 Extend the CI PII/secret log-scan in `.github/workflows/ci.yml` with the token + account-email sentinel patterns (`access_token`, `refresh_token`, `client_secret`, `code=`, `sentinel-acct-*`) (research D11).
- [x] T049 [P] Write `frontend/e2e/calendar-connections.spec.ts` (Playwright) — connect (against the stub) → status Connected → disconnect → Not connected.
- [x] T050 Verify zero PII/secret in `StructuredArguments` across the calendar code (only `memberId` + `provider` + outcome code logged); confirm no new `.ps1` was added (C5 N/A) and no tool download occurred (C7).
- [x] T051 Run `quickstart.md` validation (manual connect against a real/stub provider + the raw-driver ciphertext check).
- [x] T052 Full green-build verification: `gradlew test` (entire backend suite incl. the existing `RbacEndpointInventoryTest`, which must still pass for the new `/api/internal/calendar/**` handlers) + `ng test --watch=false` + `ng build` (with the §X run flags).
- [x] T053 Constitution §VI / C6 — multi-role sub-agent review (≥3 roles: Backend, Security, QA) of the IMPLEMENTED diff; apply or report every finding before task closure (per CLAUDE.md, this is mandatory and automatic at task close).

---

## Dependencies & Execution Order

### Phase dependencies

- **Setup (Phase 1)**: no dependencies.
- **Foundational (Phase 2)**: depends on Setup; BLOCKS all user stories. (T009/T010 depend on T007/T008.)
- **User Stories (Phase 3–6)**: all depend on Foundational. US1→US2→US3→US4 share `CalendarConnectionService`/`CalendarTokenService`/the controller, so they are **sequential by priority** (same-file edits), not parallel across stories.
- **Polish (Phase 7)**: depends on the stories it exercises (T046/T047 after US4; T048 anytime after T047; T052/T053 last).

### Within each user story

- Tests (T023/T024, T034/T035, T037, T043) are written FIRST and must FAIL before the implementation tasks in the same phase.
- Service before controller before frontend.

### Parallel opportunities

- Setup: T001, T002, T003, T004, T006 in parallel (T005 after T004).
- Foundational: T007, T008, T011, T012, T013, T014, T015, T016, T020, T021 in parallel; then T009/T010 (after T007/T008), T017 (after T016), T018/T019 (after T015), T022.
- US1 tests T023 + T024 in parallel; frontend T029 + T033 in parallel with backend impl.
- Cross-story parallelism is limited (shared service/controller files) — run stories in priority order.

---

## Implementation Strategy

### MVP (User Story 1 only)

1. Phase 1 Setup → 2. Phase 2 Foundational → 3. Phase 3 US1 → **STOP & VALIDATE**: a member can connect Google/Microsoft and see "Connected", tokens encrypted at rest. Demoable.

### Incremental delivery

US1 (connect) → US2 (auto-refresh, makes the connection durable) → US3 (disconnect + deactivation cleanup) → US4 (revoked/transient handling) → Polish (log-scan, restart-persistence, CI, E2E, §VI review). Each adds value without breaking the prior increment.

---

## Notes

- `[P]` = different files, no dependency on an incomplete task.
- Every `/api/internal/calendar/**` handler MUST carry `@PreAuthorize("isAuthenticated()")` or `RbacEndpointInventoryTest` (T052) reds the build.
- Converter-managed fields: clear with `.set(field, null)`, NEVER `.unset(...)` (ClassCastException — F03 lesson).
- Seed connections via the production `start`+`completeCallback` path (T006 helper); the only non-Mongo exceptions are the pure-unit tests (T016, T034).
- Commit after each task or logical group; never push to `main` directly (DoD).
