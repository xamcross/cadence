# Phase 0 Research — OAuth Token Store (Calendar Connections)

**Feature**: F01.1 | **Branch**: `006-oauth-token-store` | **Date**: 2026-06-15

All decisions below resolve the Technical Context unknowns and the design choices the spec leaves open. Each is grounded in the existing F00–F04 codebase so the feature reuses proven infrastructure and introduces **zero new runtime dependencies, services, or topology**.

---

## D1 — OAuth flow implementation: explicit authorization-code + refresh behind `CalendarProvider`, NOT Spring `oauth2Login` / `oauth2Client`

**Decision**: Implement the per-user calendar authorization-code grant and refresh **explicitly** — a server-side `start` endpoint that builds the provider authorize URL, a `callback` endpoint that exchanges the code for tokens via Spring's `RestClient`, and a `CalendarTokenService` that refreshes by POSTing `grant_type=refresh_token`. All of it lives behind the domain `CalendarProvider` interface (constitution Dependency Policy). No provider SDK is added.

**Rationale**:
- **`oauth2Login` is authentication, not linking.** The existing `@Order(3)` chain already uses `oauth2Login` with the `cadence-oidc` registration to *log a member in*. Calendar connection is a *second authorization* performed by an **already-authenticated** member against a **different** provider, and it must NOT change who is logged in. Routing calendar consent through `oauth2Login` would re-issue the session and conflate identities.
- **Spring `oauth2Client` (`OAuth2AuthorizedClientManager`) was considered and rejected.** Its default `OAuth2AuthorizedClientService` persists to memory or JDBC, keyed by `(registrationId, principalName)`. Bending it onto our encrypted-MongoDB document, the `CONNECTED`/`NEEDS_RECONNECTION` status, the provider-account identifier, and the `tokenVersion` CAS (D5) requires a custom `OAuth2AuthorizedClientRepository` whose conversion glue is larger and harder to test than the explicit flow — and its authorization-request filter collides on `/oauth2/authorization/**` with the existing `oauth2Login` registration. The explicit flow is fully deterministic, Clock-driven, and stubbed end-to-end with the WireMock dependency already present.
- **Risk surface is small.** This is a confidential-client, server-side authorization-code exchange over TLS with an exact-match registered redirect URI. We do **not** validate an ID token (we already know the member from their session), so the classic hand-rolled-OAuth pitfall (JWT/signature validation) does not apply. Anti-forgery `state` + PKCE (D4) close the remaining gaps.

**Alternatives considered**: (a) `oauth2Login` extra registration — rejected (re-authenticates the member). (b) `oauth2Client` + custom `OAuth2AuthorizedClientRepository` — rejected (storage-model friction, `/oauth2/authorization/**` filter collision, harder to unit-test the refresh/CAS path). (c) A third-party OAuth client library — rejected (constitution C4: no dependency outside the fixed stack; `RestClient` suffices).

---

## D2 — Encryption at rest: reuse `PiiStringConverter` via `MongoPiiConfig` for every secret field

**Decision**: Register the existing `PiiStringConverter` (AES-256-GCM, `PiiCrypto`) for `CalendarConnection.refreshToken`, `CalendarConnection.accessToken`, `CalendarConnection.providerAccountId`, and `OAuthFlowState.pkceVerifier`. A raw-driver read of any of these yields only ciphertext (SC-002). This is the exact pattern already used for `Member.email` and `Candidate.email`.

**Rationale**: The converter is a per-`(class, field)` registration on the single existing `MongoCustomConversions` bean — no new bean, no service-layer crypto calls. `PiiCrypto.encrypt/decrypt` is null-safe, so an absent access token (before first refresh) stores null cleanly. Reusing the same converter instance keeps the "one bean only" invariant noted in `MongoPiiConfig`.

**Alternatives considered**: MongoDB CSFLE — rejected (constitution permits app-level encryption, already chosen project-wide; CSFLE needs Atlas-enterprise key vault config — added topology). Encrypting in the service before `$set` — rejected (the F03 lesson: the converter already encrypts `$set` values; pre-encrypting double-encrypts).

---

## D3 — No queryable hash on credentials; lookup is by `(workspaceId, memberId, provider)`

**Decision**: Connections are always addressed by the natural key `(workspaceId, memberId, provider)` — both from HTTP (the authenticated principal's own memberId) and from the F10/F11 service call. No credential or account-identifier value is ever a query key, so **no deterministic `emailHash`-style field is needed** for this feature. `providerAccountId` is stored encrypted purely for display ("Connected as …") and audit-free diagnostics, never queried.

**Rationale**: Unlike `Member.email` (looked up at login) the calendar tokens are never the lookup key. Avoiding a keyed hash removes an attack surface and a field. The unique index on `(workspaceId, memberId, provider)` (D8) enforces one-connection-per-pair (FR-004).

---

## D4 — CSRF/anti-forgery `state` + PKCE via a single-use `OAuthFlowState` document

**Decision**: `start` generates a high-entropy `state` nonce (reuse `SecureTokens`) and a PKCE `code_verifier`; it persists a single-use `OAuthFlowState{ id=state, workspaceId, memberId, provider, pkceVerifier(encrypted), expiresAt }` (TTL-indexed, ~10 min) and redirects to the provider authorize URL with `state`, `code_challenge` (S256), the free/busy scope (D7), and provider offline params. `callback` does an atomic `findAndDelete` on `state` (single-use), verifies it has not expired, confirms the deleted record's `memberId` equals the **current session principal's** memberId, then exchanges the code with the stored `code_verifier`.

**Rationale**:
- **`state` defeats CSRF/login-CSRF** on the callback and binds the flow to the initiating member. The consume is an **atomic `mongoTemplate.findAndRemove`** (the *only* read path — no plain `findById` finder exists on the repository, which would reintroduce a TOCTOU replay window); a replayed callback finds nothing → rejected (Backend #3 / Security #5).
- **Double binding**: the callback requires BOTH a valid `state` row AND that the row's memberId equals the authenticated session principal — so an attacker cannot mint a `state` bound to the victim (their `start` is authenticated as themselves → row carries the attacker's memberId → fails the victim's session match), and a victim's `state` replayed in the attacker's session is rejected. This is the FR-018/SC-007 cross-member-attach defense, not merely a CSRF check.
- **Mix-up / provider-confusion**: the token exchange is driven entirely off the **consumed `state.provider`** (its endpoint + client credentials), with the path provider used only to locate the row and as a redundant equality check — so a provider-confusion attack is structurally impossible, not check-dependent (Security #3).
- **PKCE (S256)** protects the code-in-transit even though the client is confidential, and is required by Microsoft for some app types; harmless for Google. The verifier is encrypted at rest and single-use (consumed with the row).
- TTL index auto-expires abandoned flows (no scheduled cleanup needed) — using the native `IndexOptions().expireAfter(...)` form (D8), not an index-key document.

**Alternatives considered**: Stateless signed `state` (HMAC over memberId|provider|nonce|exp) with the verifier in an HttpOnly cookie — rejected (a third-party-initiated top-level redirect plus our SameSite=Lax cookie makes cookie-carried verifiers fragile; a single-use server row is simpler to reason about and auto-expires). Storing the verifier unencrypted — rejected (it is a one-time secret; encrypt at rest like every other secret, §VIII).

---

## D5 — Refresh concurrency: optimistic `tokenVersion` CAS via `findAndModify`

**Decision**: `CalendarConnection` carries a monotonic `tokenVersion` (long). `CalendarTokenService.getValidAccessToken` reads the connection; if the access token is missing or within the skew buffer (D6) of expiry, it performs the refresh HTTP call, then **persists with an atomic `findAndModify(filter: {_id, tokenVersion: v}, update: {set new tokens, providerRotatedRefresh?, tokenVersion: v+1, lastRefreshAt})`**. If the filter matches zero documents, another request already refreshed: re-read and use the now-current token (do not call the provider again).

**Rationale**: Two simultaneous calendar reads for the same member (a 5-person-panel free/busy storm in F10) must not double-refresh and clobber a rotated refresh token. The version CAS makes "exactly one writer wins" deterministic without a distributed lock or broker (constitution C2). Injected `Clock` makes the expiry decision testable. This mirrors the F02 `guardedFlipAdmin` / F04 CAS-winner pattern already proven in the codebase.

**Alternatives considered**: A `refreshInProgress` lease with wait/poll — rejected (adds latency + a lease-expiry edge case; the CAS re-read is simpler and lock-free). `synchronized`/JVM lock — rejected (correct only on a single instance and still races with a future second instance; the DB CAS is correct regardless).

---

## D6 — Failure classification: permanent (`invalid_grant`) → `NEEDS_RECONNECTION`; transient (429/5xx) → bounded backoff retry

**Decision**: Classify token-endpoint failures:
- **Permanent** — the OAuth error body is `invalid_grant` (revoked/expired refresh token) → set status `NEEDS_RECONNECTION`, **null the now-worthless `accessToken`** but retain `refreshToken` (so the member sees a reconnect prompt; data-minimisation §VIII — Security #7), and signal callers that no valid credential is available (a typed `CalendarReconnectRequiredException`). **No retry.**
- **Transient** — HTTP 429 or 5xx, or a network error → retry with bounded exponential backoff + jitter (max 3 attempts, the same policy F10/F11 will use for the calendar APIs); on exhaustion, surface a transient error and **leave the connection byte-identical** (`status` stays `CONNECTED`, `tokenVersion`/`refreshToken`/`accessToken` unchanged — no partial write, FR-016 / QA #7).
- **Rotation (FR-013)**: on a successful refresh, persist a new `refreshToken` **only if the response carries one** (providers re-issue only sometimes); never overwrite a good refresh token with a null/absent response value (Security #8). The CAS write conditionally includes `refreshToken` in the `$set`.

**Rationale**: Distinguishing the two prevents a transient outage from forcing every member to reconnect (a self-inflicted outage) while still surfacing a genuinely revoked grant (FR-015/FR-016, SC-006). The classifier keys on the standard OAuth `error` field, which both Google and Microsoft return.

**Alternatives considered**: Treat all failures as needing reconnection — rejected (a 30-second Google blip would disconnect the whole workspace). Infinite retry on transient — rejected (unbounded latency; max-3 with backoff is the project-wide policy).

---

## D7 — Scopes: free/busy-only, provider-specific, with the §VIII justification

**Decision** (the spec-documented justification the constitution requires for any calendar scope):

| Provider | Requested scopes | Offline/refresh params |
|---|---|---|
| Google | `https://www.googleapis.com/auth/calendar.freebusy` | `access_type=offline`, `prompt=consent` (guarantees a refresh token is returned) |
| Microsoft 365 | `Calendars.Read offline_access` | `offline_access` (Graph refresh token); free/busy read via `getSchedule` with `$select` field projection (owned by F11) |

**Rationale**: Google exposes a dedicated free/busy scope (`calendar.freebusy`) — the minimum that satisfies §VIII. Microsoft Graph has **no** delegated scope narrower than `Calendars.Read` for the `getSchedule` free/busy endpoint; the constitution's free/busy intent is met by **field projection at query time** (F11's documented mitigation, ISSUE-2), so F01.1 requests `Calendars.Read` and records this as the approved justification. `offline_access` / `access_type=offline` are what yield a refresh token at all. Broader scopes (`calendar`, `Calendars.ReadWrite`) needed for event creation are deferred to F10/F11 and are explicitly **out of scope** here.

**Alternatives considered**: Request write scopes now (anticipating F10/F11) — rejected (§VIII least-scope + §I YAGNI; F10/F11 will re-consent with the write scope when they land). `Calendars.Read.Shared` — rejected (broader than needed).

---

## D8 — Redirect URI, security chain, and indexes

**Decision**:
- **Endpoints** live under `/api/internal/calendar/**` on the existing `@Order(3)` authenticated chain. The provider redirect URI is `{baseUrl}/api/internal/calendar/connections/{provider}/callback`. The callback is a top-level browser GET, so the `SameSite=Lax` `cad_session` cookie is sent (the exact reason F01 chose Lax) and the member is identified from their session. GET is not CSRF-protected; the mutating `start` (POST) and `disconnect` (DELETE) carry the standard CSRF cookie/header the SPA already sends.
- **Callback on an expired/absent session** (consent can take minutes — Backend #1 / Security #1): the `/api/**` chain's default entry point returns a **bare 401** with no body, stranding the member on a blank page and silently dropping the code. Instead, register a **callback-path-specific `AuthenticationEntryPoint`** (a matcher for `…/calendar/**/callback` placed before the `/api/**` 401 rule, in the `@Order(3)` `exceptionHandling`) that 302-redirects to `{spaBaseUrl}/calendar/connections?error=session_expired`. This is the one **`SecurityConfig` change** the feature makes (a scoped entry-point addition; the `/api/**` 401 and actuator contracts are otherwise untouched).
- **Redirect-target hardening** (Security #2): every callback redirect (success and all `?error=…`) is built from the **configured `spaBaseUrl` constant + a fixed path + an allowlisted enum value** — never from a request parameter or a forwarded-host header (no open redirect). Reuse the `OidcLoginSuccessHandler` `props.getSpaBaseUrl()` pattern. No token/code ever appears in a redirect URL.
- **Indexes** (`ChangeUnit006_CalendarOAuthIndexes`, order `"006"`): **unique** `calendarConnections {workspaceId, memberId, provider}` (enforces FR-004 one-per-pair); TTL `calendarOAuthState {expiresAt}` via `new IndexOptions().expireAfter(0L, TimeUnit.SECONDS)` (auto-reaps abandoned flows). Native `createIndex` + targeted `dropIndex` rollback (CLAUDE.md Mongock rules; never `dropIndexes`, never reuse/rename an applied order). **Not** an index-key `Document("expireAfterSeconds",0)` (that builds a plain field index — Backend #2).

**Rationale**: Reusing the authenticated chain means the F00 actuator-404 and F01 `/api/**`-401 contracts are untouched (the new entry point is scoped to the callback matcher only). The internal prefix means `RbacEndpointInventoryTest` requires a method-security annotation on every new handler (so none can ship un-guarded). All index fields are non-null, so no `@Field(write=NON_NULL)` partial-index footgun.

**Alternatives considered**: A dedicated public callback path on the `@Order(2)` chain — rejected (the callback must be authenticated to bind to the member; a public callback would need to re-derive identity from `state` alone, weakening D4's double-binding). Leaving the bare 401 on the callback — rejected (a silent dead-end after consent; the scoped redirect entry point is a one-line addition).

---

## D9 — Configuration & secrets: new `calendar.oauth.*` block, explicit endpoint URIs, Fly secrets

**Decision**: Add a `CalendarOAuthProperties` (`@ConfigurationProperties("calendar.oauth")`) with per-provider `clientId`, `clientSecret`, `authorizationUri`, `tokenUri`, `revocationUri`, `scope`, and shared `stateTtl`, `accessTokenSkew`, and **`connectTimeout` + `readTimeout`** for the `RestClient` used against the token/revocation endpoints. A default `RestClient` has no socket timeout, so a hung provider socket would stall a free/busy request (and blow the SC-005 budget) indefinitely — configure the timeouts via `ClientHttpRequestFactorySettings` (Backend #6). Production secrets (`GOOGLE_CAL_CLIENT_ID/SECRET`, `MS_CAL_CLIENT_ID/SECRET`) come from Fly secrets; dev defaults point at the local stub. Like F01's OIDC config, use **explicit endpoint URIs**, never an `issuer-uri`/discovery document (the eager-fetch-at-startup footgun that crashes the context offline — CLAUDE.md F01 lesson).

**Rationale**: Mirrors the proven `auth.*` / `AuthProperties` pattern. Explicit URIs keep startup network-free for local dev / CI / tests. Secrets never touch source or `fly.toml` (§IV).

**Alternatives considered**: Reuse `spring.security.oauth2.client.registration.*` for google/microsoft — rejected (that wiring is consumed by `oauth2Login`/`oauth2Client`, which D1 deliberately avoids; a clean independent property block prevents accidental coupling to the login chain).

---

## D10 — No new dependency; tests stub the provider with WireMock (already present)

**Decision**: Token-endpoint and authorize calls use `RestClient` (in `spring-boot-starter-web`, already a dependency). Integration tests stub the provider authorize/token/revocation endpoints with **WireMock** (`org.wiremock:wiremock-standalone:3.9.1`, already a test dependency added for F01's OIDC JWKS stubbing). No live Google/Microsoft credentials in CI (constitution test rule).

**Rationale**: Confirmed against `build.gradle` — both `RestClient` and WireMock are available. C4/C7 hold: zero new runtime dependency, zero downloads.

---

## D11 — Zero-token logging + CI scan extension

**Decision**: Every log statement in the connect/refresh/disconnect path uses `StructuredArguments` with only `memberId` (internal ObjectId) + `provider` + outcome code — never a token, authorization code, client secret, or `providerAccountId`. The CI PII/secret scan (`ci.yml`) is extended with high-entropy **sentinels** seeded by the log-scan test for the access token, refresh token, client secret, authorization code, **and the provider account email** (`sentinel-acct-<hex>@example.invalid`, so FR-010's PII-in-logs half is actually exercised — QA #3), plus literal patterns (`refresh_token`, `access_token`, `client_secret`, `code=`), so the scan cannot pass vacuously (the F04 sentinel lesson). The scan **also drives a failing-revoke path** (revoke endpoint 500 / network error) and asserts the token sentinel is absent there — that exception path is the most likely place an `RestClient` error would echo the request body (the token) into a log (Security #11).

**Rationale**: §VIII no-secrets-in-logs (FR-009/FR-010, SC-003). The sentinels drive the real encrypt/decrypt/refresh/failed-revoke path and assert absence at every level incl. TRACE.

---

## D12 — Deactivation / erasure seam: `disconnectAll` hooked into `RoleService.guardedDeactivate`

**Decision**: `CalendarConnectionService` exposes `disconnectAll(workspaceId, memberId)` (best-effort provider revocation + delete all connection rows for the member, across BOTH providers). `RoleService.guardedDeactivate` calls it alongside the existing `sessions.revokeAllForMember(memberId)` (line 99) so a deactivated member's calendar credentials are destroyed (FR-007). The `disconnectAll` call is wrapped so a provider-revoke failure cannot abort the deactivation (best-effort, FR-006 semantics — Backend #4). `disconnectAll` must NOT inject `SessionService`/`RoleService` (keeping it free of the deactivation orchestrator avoids any cycle; the graph `RoleService → CalendarConnectionService → {repo, OAuthGateway, AuthAuditService, Clock}` has no path back).

**Member-erasure (Security #6)**: FR-007 also covers *erasure*, and `providerAccountId` is the member's real email/subject (personal data, FR-010). **No member-record erasure path exists yet** (F04 erases *candidate* data, not members). So F01.1 ships `disconnectAll` as the canonical primitive, wires it into deactivation now, and **documents that any future member-erasure path MUST call `disconnectAll`**. The primitive is verified production-path today (a direct `disconnectAll` test asserts all rows for a member — both providers — are removed + best-effort revoked), so the seam is proven, not stubbed.

**Rationale**: Reuses the one existing place that already tears down a member's footprint, so cleanup cannot be forgotten. Testable at the service level today even though no HTTP deactivation/erasure endpoint exists yet (the F04 service-seam approach).

**Alternatives considered**: A `@Scheduled` orphan-sweep — rejected (the deactivation path is synchronous and already exists; a sweep adds a scheduler task for a case the seam handles immediately).

---

## D13 — Audit: reuse `AuthAuditService` + new `AuthEventType` values (no new collection)

**Decision**: Record connection lifecycle via the existing `AuthAuditService.record(...)` into `authAuditLog`, adding `AuthEventType` values `CALENDAR_CONNECTED`, `CALENDAR_DISCONNECTED`, `CALENDAR_RECONNECT_REQUIRED`. Actor = the member; payload = workspaceId + memberId + provider + outcome only (no PII/token).

**Rationale**: FR-020 wants lifecycle events with internal ids; the auth audit log already does exactly this for member-actor events, so no new collection/topology (C2). Closed-enum event types keep it non-PII by construction (the F04 audit lesson).

**Alternatives considered**: A new `calendarAuditLog` collection — rejected (unnecessary; auth audit already covers member-actor security events).

---

## D14 — Frontend: a member-self "Calendar connections" surface (any role), `authGuard` only

**Decision**: New standalone `features/calendar/calendar-connections.component.ts` + `calendar.service.ts`, route `path: 'calendar/connections'` guarded by `authGuard` **only** (every member manages their own calendar — not Admin-gated), plus a shell nav link visible to all authenticated roles (placed **outside every `@if(m.role…)` block** but inside `@if(member(); as m)` — Frontend #1). The component shows per-provider status (Not connected / Connected as … / Needs reconnection) and a Connect/Disconnect control. Key flow details corrected in review:

- **Connect = XHR then full-page navigation** (Frontend #2): `start` returns `200 {authorizationUrl}` (JSON, **not** a 302 — the contract is authoritative; D-prose "302s" was wrong). The handler does `this.calendar.start(provider).subscribe(r => window.location.href = r.authorizationUrl)` — **`window.location.href`, never the Angular `Router`** — so the eventual provider callback is a top-level GET carrying the `SameSite=Lax` cookie. Precedent: `auth.service.ts` `startSso()`. The Connect button is disabled while the `start` request is in flight (no double-submit / double `OAuthFlowState`).
- **Read the return query param** (Frontend #3): on the provider's redirect back to `/calendar/connections?connected={provider}` or `?error={invalid_state|consent_denied|no_offline_grant|session_expired}`, the component injects `ActivatedRoute`, reads the param (precedent: `accept-invite.component.ts`), maps each `error` code to a localized `role="alert"` banner, and re-fetches `GET /connections`. Each `?error=` code has its own `$localize` message.
- **i18n** (Frontend #5): static strings ("Not connected", "Needs reconnection") via `i18n="@@calendar.*"` attributes; the interpolated "Connected as {account}" via a `$localize` **named placeholder** (not string concatenation).
- **a11y** (Frontend #6): internal screen (WCAG/Lighthouse candidate gates N/A), but reuse the GDPR pattern — a `confirming = signal(false)` two-state confirm before destructive Disconnect, errors in `role="alert"`, ≥44px controls, labelled provider controls.
- **CSRF** (Frontend #4): `calendar.service.ts` calls go through `HttpClient` against `${environment.apiBaseUrl}/internal/calendar/**` (same base as `gdpr.service.ts`), so the existing `withXsrfConfiguration` + `apiInterceptor` attach the `X-XSRF-TOKEN` header + credentials automatically — no new code.

**Rationale**: FR-017/FR-001/FR-005 are member-self actions, unlike the Admin-only GDPR surfaces. `authGuard`-only matches "any authenticated member". The status tri-state (D5/D6) drives the UI.

---

## Summary of reuse (no new runtime dependency, no topology change)

| Concern | Reused asset |
|---|---|
| Encryption at rest | `PiiCrypto` + `PiiStringConverter` + `MongoPiiConfig` |
| HTTP to providers | `RestClient` (spring-web) |
| Provider stubbing in tests | WireMock (already a test dep) |
| Anti-forgery nonce | `SecureTokens` |
| Audit | `AuthAuditService` + `authAuditLog` + `AuthEventType` (+3 values) |
| Deactivation cleanup | `RoleService.guardedDeactivate` seam |
| Security chain | existing `@Order(3)` authenticated chain (unchanged) |
| Migration | Mongock `ChangeUnit006` (next order) |
| Config/secrets | `@ConfigurationProperties` + Fly secrets (the `AuthProperties` pattern) |
| Clock-driven expiry/refresh | injected `java.time.Clock` (F01 pattern) |
