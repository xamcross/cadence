# Phase 0 Research: Authentication & Session Management

**Feature**: 002-authentication | **Date**: 2026-06-13
**Revision**: v2 — incorporates the §VI multi-role plan review (cross-origin cookie reality, encryption-at-rest mechanism, test strategy, security hardening).

This document resolves the technical unknowns implied by the spec and records the decisions the design (Phase 1) builds on. Every decision is checked against the constitution (single-instance + MongoDB only, fixed stack, security-by-default, zero downloads).

---

## D10 — Deployment origin (DECIDED FIRST — drives D1/D8) *(review FE-1..FE-4, BE-1, SEC-5)*

**Decision**: Serve the SPA and the API on the **same registrable origin** via a **Cloudflare reverse-proxy**: Cloudflare Pages serves the SPA at `app.cadence.example`, and a Cloudflare route proxies `app.cadence.example/api/*` (and `/oauth2/*`, `/login/oauth2/code/*`) to the Fly.io backend. The browser therefore sees one origin; the session cookie is **first-party**.

**Rationale**:
- A Cloudflare-Pages SPA and a `*.fly.dev` backend are **different origins**. A `SameSite=Strict/Lax` cookie is then a third-party cookie that the browser will not attach to `withCredentials` XHR, and `Set-Cookie` on the cross-site OIDC redirect would be dropped — the SSO and session flows would silently never authenticate (FE-1, FE-2). Same-origin proxying eliminates this entire class of bug, plus the credentialed-CORS and cross-origin-XSRF problems (FE-3, FE-4).
- It keeps the constitution topology intact (still one Fly Machine, one SPA, one Atlas) — Cloudflare proxying is a CDN routing rule, not a new service.

**Fallback (documented, not chosen)**: If same-origin proxying is infeasible, use `SameSite=None; Secure` cookies **plus** an explicit `CorsConfigurationSource` (exact SPA origin, `allowCredentials=true`, allow `X-XSRF-TOKEN`/`Content-Type`, permit `OPTIONS` preflight) **plus** a custom cross-origin XSRF emission. This is strictly more fragile and is only a fallback.

**Consequence**: With same-origin, `cad_session` uses `SameSite=Lax` (sent on top-level OIDC return navigation and on same-origin XHR; still blocks cross-site CSRF, which is additionally token-protected). `apiBaseUrl` in prod is the same origin (`/api`), set via the existing Cloudflare build-time injection (FE-10).

---

## D1 — Session credential mechanism (statelessness vs. revocation)

**Decision**: A **Cadence-issued JWT** (HS256, key from a Fly.io secret) in an **HttpOnly + Secure + SameSite=Lax cookie** (`cad_session`), backed by a **MongoDB `sessions` registry** keyed by the JWT `jti`. Per request the `SessionCookieAuthFilter`: (1) verifies signature + `exp`/`nbf` **with ±60 s skew applied only to the cryptographic check**; (2) loads the session by `jti`; (3) rejects at **exact `now`** (no skew) if `revoked`, past `absoluteExpiresAt`/`idleExpiresAt`, or the member is `DEACTIVATED`; (4) **throttled sliding-renewal** — re-issues the cookie and writes `lastSeenAt`/`idleExpiresAt` **only when more than 1/3 of the idle window has elapsed** since `lastSeenAt`, so renewal is not a per-request write.

**Key management** *(SEC-3)*: JWTs carry a `kid` header. The decoder accepts the **current and previous** signing keys (`JWT_SECRET`, `JWT_SECRET_PREVIOUS`) so a key can be rotated with an overlap window; emergency rotation forces re-login bounded by the 8 h absolute TTL. Decoder is **pinned to HS256** and rejects `alg:none`/`alg`-confusion (SC-012).

**Rationale**:
- Honours the backlog's "JWT issued by backend, verified on every request".
- The signature catches tampering cheaply (SC-012); the registry lookup is what makes FR-015/FR-021/FR-028 ("effective on next request") achievable — a pure stateless JWT cannot be revoked early.
- Applying skew only to the JWT `exp`/`nbf` (not to the Mongo absolute/revoked/status checks) means the hard 8 h lifetime and revocation are **not** extended by the tolerance (SEC-11).
- HttpOnly defeats XSS token theft (FR-037); same-origin (D10) + `SameSite=Lax` + CSRF token defeats CSRF.

**Config**: `auth.session.absolute-ttl=PT8H`, `idle-ttl=PT30M`, `clock-skew=PT60S`. Signing keys: `JWT_SECRET` (+ optional `JWT_SECRET_PREVIOUS`) Fly secrets; **fail fast at startup if absent in prod**, documented local-dev default (BE-10).

**Alternatives considered**: opaque server-side token (simpler, but contradicts backlog "JWT"); stateless-only JWT (cannot meet next-request revocation); `spring-session-data-mongodb` (extra dep for what a tiny registry does).

---

## D2 — OIDC SSO integration

**Decision**: `spring-boot-starter-oauth2-client` with Spring Security `oauth2Login` (authorization-code + **PKCE**). Spring validates the ID token (signature via JWKS, `iss`, `aud`, `exp`, `nonce`) and manages `state`. A custom `OidcLoginSuccessHandler`:
1. maps the OIDC `(issuer, subject)` → `Member.ssoIdentity`;
2. refuses sign-in (no cookie, redirect `/login?error=no_access`) if no **ACTIVE** member matches (FR-007) or the member is DEACTIVATED;
3. **invalidates the IdP-login `HttpSession` and issues a fresh CSRF token** to close session fixation (SEC-4);
4. issues the `cad_session` cookie (D1) and redirects to the SPA.

A custom **`AuthenticationFailureHandler`** maps all OIDC/OAuth2 errors (IdP unreachable, token error) to a generic `/login?error=idp_unavailable` redirect with **no exception detail/stack trace** (FR-034, SEC-12, QA-14).

**SSO-invitee linking** *(SEC-10)*: when an invitation is accepted with `needsPassword:false`, the invitee completes a real OIDC flow; the linked `(issuer, subject)` is taken from the **validated ID token**, never self-asserted, and binding fails cleanly (surfaced, not 500) if that subject is already linked to another member.

**Rationale**: first-party Spring starter → standards-correct, low-risk; PKCE required by FR-025. Per-workspace IdP registration resolution is an F03 concern; MVP seeds one registration.

**New dependency justification (gate C4)**: `spring-boot-starter-oauth2-client` — OIDC login + transitive Nimbus `JwtEncoder`/`JwtDecoder` for the self-issued session JWT. First-party Spring starter; no infra SDK; no separate JWT library.

---

## D3 — Password hashing & fallback sign-in

**Decision**: **BCrypt** (`BCryptPasswordEncoder`, strength 12) from `spring-boot-starter-security` (no new dep). On sign-in, **always run a hash comparison** (against a fixed dummy hash when the account is unknown) so unknown-account, wrong-password, and locked paths are timing-uniform and return the **same** generic `401 invalid_credentials` body (FR-005).

**Rationale**: Spring default, adaptive, salted; strength 12 fits the < 2 s budget. Dummy-hash closes the enumeration timing side-channel.

**Alternatives**: Argon2 pulls BouncyCastle — deferred; BCrypt suffices for MVP, dependency-free.

---

## D4 — Invitation & password-reset links

**Decision**: Token = **256-bit `SecureRandom`**, URL-safe Base64, returned only in the emailed link; stored as **HMAC-SHA-256 with a server-side `TOKEN_PEPPER` Fly secret** (not bare SHA-256) in `invitations.tokenHash` / `passwordResets.tokenHash` (unique-indexed) so a DB-only leak yields nothing matchable (SEC-2). Single-use enforced by an **atomic `findOneAndUpdate({tokenHash, status:PENDING} → CONSUMED)`** — first concurrent redemption wins, others refused (FR-035). MongoDB TTL index on `expiresAt` (invites 72 h, resets 1 h). No PII in the URL (FR-030). **Token validation precedes any password-policy check** so a forged token never leaks validity via a 400 (BE-8).

**Rationale**: mirrors backlog ISSUE-9 + F00.1 unique-token pattern; 256-bit ≫ 128-bit floor; HMAC-pepper adds defense-in-depth; atomic CAS is the same race-safe primitive F13 uses.

**Alternatives**: signed JWT invite tokens — rejected (still need a server record for single-use; leaked key forges invites).

---

## D5 — Rate limiting & account lockout *(SEC-7, SEC-8)*

**Decision**: Two layers, no external store:
- **Per-account lockout** on the `Member` doc (`failedLoginCount`, `lockedUntil`): lock after 5 failures / 15 min, auto-release after the window (FR-006). **Locked accounts return the identical generic `401 invalid_credentials`** (not a distinct 429) so lockout is **not** an enumeration oracle (SEC-7) — the password check is silently skipped while the response/timing stays uniform.
- **Per-IP throttle** (the user-visible `429`) for `login`, `password-reset/request`, `invitation accept/validate` via an **in-process** sliding window; this fires regardless of account existence (enumeration-safe). Plus a **coarse Mongo-backed per-target cooldown** for `password-reset/request` and invite-token validation so abuse resistance survives a restart/redeploy (SEC-8). The in-proc limiter exposes a test reset hook and is `Clock`-driven (QA-8).

**Rationale**: lockout must survive restarts (Mongo); IP throttle is a cheap broker-free dampener on the single Fly Machine; reset/invite cooldown in Mongo closes the restart-resets-budget gap without Redis (§IV).

**Alternatives**: Bucket4j/Resilience4j/Redis — new dep / prohibited tier; rejected.

---

## D6 — MongoDB schema, indexes & TTL (F00.1 pattern)

**Decision**: New Mongock changeset **`ChangeUnit002_AuthIndexes`** (`order = "002"`, never renamed; native `createIndex`; targeted `dropIndex` rollback per CLAUDE.md):

| Collection | Index | Options |
|---|---|---|
| `members` | `{ workspaceId: 1, emailHash: 1 }` | **unique** (lookup/uniqueness on the HMAC, not on ciphertext — see D12) |
| `members` | `{ ssoProvider: 1, ssoSubject: 1 }` | **unique, partial** `{ ssoProvider: { $exists: true } }` (BE-4) |
| `sessions` | `{ memberId: 1 }` | revoke-all on deactivation/reset |
| `sessions` | `{ absoluteExpiresAt: 1 }` | **TTL** `expireAfterSeconds: 0` |
| `invitations` | `{ tokenHash: 1 }` | **unique** |
| `invitations` | `{ expiresAt: 1 }` | **TTL** `expireAfterSeconds: 0` |
| `passwordResets` | `{ tokenHash: 1 }` | **unique** |
| `passwordResets` | `{ expiresAt: 1 }` | **TTL** `expireAfterSeconds: 0` |
| `authAuditLog` | `{ memberId: 1, occurredAt: -1 }` | member-keyed audit |

Session `_id` = JWT `jti` (UUID) → registry lookup is a primary-key read. Indexes are created at startup before any member write path is live (greenfield, BE-6).

**Rationale**: follows F00.1; TTL indexes self-clean expired sessions/invites/resets with **no `@Scheduled` task** (§IV); auth audit gets its **own** collection (member-keyed), not the candidate-keyed `auditLog` (BE-8).

---

## D7 — Spring Security filter-chain topology *(BE-2)*

**Decision**: Three ordered `SecurityFilterChain` beans — the existing main chain is **renumbered** so there is no `@Order` collision:
1. `@Order(1)` `securityMatcher("/actuator/**")` → permitAll (existing, unchanged — preserves the CLAUDE.md management-port 200/404 contract).
2. `@Order(2)` `securityMatcher("/api/public/**", "/api/candidate/**")` → permitAll + CSRF-exempt (no session yet); rate-limited (FR-011/FR-029).
3. `@Order(3)` **main** chain (was `@Order(2)`): matches everything else **including `/oauth2/**` and `/login/oauth2/code/**`**; `oauth2Login(...)` + `SessionCookieAuthFilter` before `UsernamePasswordAuthenticationFilter`; `authorizeHttpRequests(anyRequest().authenticated())` (deny-by-default, FR-010); CSRF via `CookieCsrfTokenRepository.withHttpOnlyFalse()` (so the SPA can echo `X-XSRF-TOKEN`). The OIDC endpoints are explicitly permitted within this chain by `oauth2Login`.

**Rationale**: deny-by-default means a new internal endpoint cannot accidentally be public (FR-029); candidate/public paths are a positively-declared allow-list; renumbering avoids the `@Order(2)` clash; `/oauth2/**` coverage confirmed on the main chain (BE-2).

---

## D8 — Frontend auth wiring *(FE-4..FE-9)*

**Decision**: Angular standalone, **same-origin** (D10). `provideHttpClient(withInterceptors([apiInterceptor, authErrorInterceptor]), withXsrfConfiguration({ cookieName: 'XSRF-TOKEN', headerName: 'X-XSRF-TOKEN' }))` — matching Spring defaults (FE-8). A single `apiInterceptor` sets `withCredentials: true` on every `apiBaseUrl` request (not per-call, FE-5). The SPA learns identity via `GET /api/internal/auth/me` (cookie is HttpOnly). `authErrorInterceptor` redirects to `/login` on 401 **but skips redirect when already on a public auth route** (`/login`, `/accept-invite`, `/reset`, `/reset/confirm`) to avoid loops (FE-6). The `authGuard` wraps only the shell child routes; public auth pages sit at the top level, unguarded (FE-6).

- **Login**: SSO button is the primary CTA (FR-002); email/password is a secondary form.
- **accept-invite / reset-confirm**: pre-validate the token via the GET endpoint, branch on `needsPassword`, and render an accessible "link invalid/expired" state with a recovery action (FE-9, constitution §II end-to-end).
- **Accessibility (FE-7)**: login, accept-invite, reset-request, reset-confirm are **public-facing** → held to **WCAG 2.2 AA (axe-core 0 violations) + mobile-first (375 px, ≥44 px targets)**, same gate as candidate pages (§IX). All strings via `$localize`. No new frontend dependency.

**Rationale**: HttpOnly cookie + `/me` is the XSS-safe SPA pattern; same-origin makes Angular's built-in XSRF + Spring's `CookieCsrfTokenRepository` line up with zero custom CORS/XSRF code.

**Alternatives**: JWT in `localStorage` — rejected (XSS-exposed, violates FR-037).

---

## D9 — Tooling & versions (zero-download, gate C7)

**Decision**: cached **Gradle 9.4.0**, installed **JDK 21**, existing **Angular CLI 17.3**. The one new backend dependency resolves from Maven Central at build time (a *library* artifact — permitted; §X prohibits downloading build *tools/runtimes/CLIs*, not Maven deps). No frontend deps added.

---

## D11 — Test strategy *(QA-1..QA-10, QA-12..QA-18)*

Covers every §VII required test type and maps each SC to a concrete test. **All integration tests extend `BaseIntegrationTest`** (shared `@ServiceConnection` singleton `mongo:7`) and clean the five new collections via `mongoTemplate.remove(new Query(), Type.class)` in `@BeforeEach` — **never `dropCollection`** (CLAUDE.md; QA-9). Spring Boot 3.3 mocks use `@MockBean` (not `@MockitoBean`).

- **Injectable `java.time.Clock`** in `SessionService`, `LoginAttemptService`, and the limiter, so clock-skew (±60 s in/out boundaries), sliding-renewal (renews in idle window; never exceeds absolute TTL), and lockout-recovery (advance past 15 min window) are deterministic — no real sleeps (QA-4/5/6).
- **OIDC tests**: unit/slice via Spring Security test `oidcLogin()` post-processor for subject→member mapping (incl. DEACTIVATED → no_access, QA-12); full ID-token validation (sig/iss/aud/exp/nonce) and IdP-error path (FR-034) via **WireMock** stubbing the JWKS + token endpoints (no live IdP, CI-safe) (QA-2/14).
- **`TokenTamperTest`** (SC-012): `NimbusJwtDecoder` pinned to HS256; asserts rejection of `alg:none`, altered `alg`, stripped/forged signature, mutated `sub`/`role` claim, and another member's valid token (QA-3).
- **Concurrency** (FR-035/SC-008): `CountDownLatch` harness fires N parallel invitation/reset confirms against the atomic `findOneAndUpdate`; asserts exactly one success, rest 410 (QA-7).
- **PII log scan** (SC-005): Logback `ListAppender` capture across sign-in/invite/reset; asserts zero matches for the full FR-022 set (email, JWT, hex token, bcrypt `$2` prefix, OIDC code/state/nonce) (QA-10); complements CI grep.
- **At-rest** (SC-011, QA-11): persistence test asserts stored `passwordCredential.bcryptHash` has the `$2` prefix (not plaintext), `tokenHash` ≠ raw token, and the stored `email` field is AES-GCM ciphertext (not plaintext) with a matching `emailHash` (D12).
- **CSRF** (QA-16): MockMvc — internal POST without `X-XSRF-TOKEN` → 403; with token → success; public login without CSRF → not 403.
- **Frontend (Jasmine)**: guard (each role/redirect), both interceptors (withCredentials set; 401 redirect + no-loop on public routes), login/accept-invite/reset-confirm validation incl. weak-password + 410 rendering (QA-18).
- **E2E (Playwright)** (QA-1): `sso-login.spec`, `password-fallback.spec`, `invite-accept.spec` — SSO uses a **dockerised Keycloak realm fixture** (image pulled once / cached; if unavailable in CI, the WireMock OIDC harness backs a reduced E2E and the gap is logged, never silently skipped).
- **SC-003** (QA-17): a test-only `/api/candidate/__probe` handler asserts candidate paths reach a handler (non-401); full enumeration re-verified as candidate features land.

---

## D12 — Encryption-at-rest for member PII *(SEC-1, QA-11)*

**Decision**: **Application-level AES-256-GCM** (constitution §VIII explicitly permits "application-level AES-256") via a Spring Data `Converter`, key from a `PII_ENC_KEY` Fly secret. Per member:
- `email` and `displayName` stored as **randomized AES-256-GCM ciphertext** (a DB reader sees ciphertext → SC-011 holds).
- `emailHash` = **HMAC-SHA-256(email, PII_PEPPER)** stored alongside, used for the **unique index** `{workspaceId, emailHash}` and all equality lookups (sign-in, invite dedupe) — this resolves the "deterministic-encryption vs unique-index" tension SEC-1 raised without needing CSFLE/KMS infrastructure.

**Rationale**: self-contained (no CSFLE shared-library/KMS, avoiding §X tension and operational overhead), satisfies §VIII + SC-011 + C2. Erasure (F04) overwrites the ciphertext + hash with `[ERASED]` markers; the member-keyed audit (non-PII) survives (FR-036).

**Alternatives**: MongoDB CSFLE deterministic-email — heavier (key vault, KMS/master key, mongocrypt shared lib); deferred. Atlas server-side at-rest only — rejected: it does **not** show ciphertext to a DB reader, so it would fail SC-011 as written.

---

## Resolved unknowns summary

| Unknown | Resolution |
|---|---|
| Deployment origin / cookie viability | Same-origin Cloudflare proxy; SameSite=Lax (D10) |
| Session credential & revocation | JWT-in-HttpOnly-cookie + Mongo registry, kid rotation, skew on crypto-only (D1) |
| SSO protocol/library + failure path | OIDC via oauth2-client, PKCE, success+failure handlers, session-fixation fix (D2) |
| Password hashing & timing safety | BCrypt 12 + dummy-hash uniform response (D3) |
| Invite/reset tokens | 256-bit CSPRNG, HMAC-pepper hash, atomic CAS, TTL, validate-before-policy (D4) |
| Rate limit / lockout / enumeration | Mongo lockout (uniform 401) + in-proc IP 429 + Mongo cooldown (D5) |
| Indexes & expiry | ChangeUnit002, TTL, partial SSO index, own audit collection (D6) |
| Filter-chain shape | 3 ordered chains, renumbered, OIDC paths on main (D7) |
| Frontend session/CORS/XSRF | Same-origin, withCredentials interceptor, no-loop guard, axe AA (D8) |
| Tooling | Cached Gradle 9.4.0 / JDK 21 / Angular 17.3, zero downloads (D9) |
| Test strategy | Injectable clock, mock OIDC, pinned decoder, concurrency, ListAppender, E2E (D11) |
| PII encryption-at-rest | App-level AES-256-GCM + emailHash HMAC (D12) |

All NEEDS CLARIFICATION resolved. Proceed to Phase 1 design artifacts (updated below).
