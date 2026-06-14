# API Contract: Authentication & Session Management

**Feature**: 002-authentication | **Date**: 2026-06-13

Conventions:
- Base: backend on `:8080` (public `:8080`, management `:8081`).
- **Public (no session)**: `/api/public/auth/**` and Spring OIDC paths — permitAll chain.
- **Internal (session required)**: `/api/internal/**` — authenticated chain, deny-by-default.
- **Candidate**: `/api/candidate/**` — permitAll (declared here only to assert the allow-list; no endpoints added by F01).
- **Origin**: SPA and API are served **same-origin** via a Cloudflare reverse-proxy of `/api`, `/oauth2`, `/login/oauth2/code` to the Fly backend (research D10). This makes the cookie first-party; no cross-origin CORS/XSRF handling is required. (Fallback if same-origin is infeasible: `SameSite=None; Secure` + explicit credentialed CORS — documented in D10, not the default.)
- Session credential: `cad_session` cookie — `HttpOnly; Secure; SameSite=Lax; Path=/`.
- CSRF: state-changing internal requests require the `X-XSRF-TOKEN` header; cookie via `CookieCsrfTokenRepository.withHttpOnlyFalse()` (name `XSRF-TOKEN`, header `X-XSRF-TOKEN` — matches Angular defaults). Public auth endpoints are CSRF-exempt (no session yet) but rate-limited.
- Error envelope (all 4xx/5xx): `{ "error": "<code>", "message": "<user-safe text>" }` — never contains PII, tokens, or stack traces (FR-022).

---

## SSO (OIDC) — Spring-managed

### `GET /oauth2/authorization/{registrationId}`
Initiates OIDC authorization-code + PKCE redirect to the IdP. (Spring Security endpoint.)
- **302** → IdP authorize URL (sets `state`, `nonce`, PKCE verifier).

### `GET /login/oauth2/code/{registrationId}`
OIDC callback. Spring validates ID token (signature/`iss`/`aud`/`exp`/`nonce`); `OidcLoginSuccessHandler` runs.
- **302** → SPA dashboard with `Set-Cookie: cad_session=...` when `(issuer,subject)` maps to an **ACTIVE** member (FR-001, FR-008, FR-025).
- **302** → SPA `/login?error=no_access` (no cookie) when no active member matches (FR-007) or member is DEACTIVATED.
- **302** → SPA `/login?error=idp_unavailable` (no cookie, no stack trace) when the IdP is unreachable or returns an OAuth2/OIDC error — via a custom `AuthenticationFailureHandler` (FR-034, SEC-12). The email+password fallback remains reachable.
- On success: IdP `HttpSession` invalidated + fresh CSRF token (session-fixation, SEC-4).
- Audit: `SIGN_IN_SUCCESS` / `SIGN_IN_FAILURE` (non-PII).

---

## Email + password (public, rate-limited)

### `POST /api/public/auth/login`
Body: `{ "workspaceId": "...", "email": "...", "password": "..." }`
- **200** + `Set-Cookie: cad_session` → `{ "memberId", "role", "displayName" }` on success (FR-003). On success the prior IdP/login `HttpSession` is invalidated and a fresh CSRF token issued (session-fixation, SEC-4).
- **401** `{ "error":"invalid_credentials", "message":"Invalid email or password." }` — **identical** for unknown account, wrong password, **and locked account** (the password check is silently skipped when locked so lockout is not an enumeration oracle, SEC-7); constant-time path (always runs a hash compare, FR-005).
- **429** `{ "error":"rate_limited", ... }` — **per-IP throttle only** (fires regardless of account existence, enumeration-safe, FR-032). Per-account lockout does NOT surface as a distinct status.
- Audit: success/failure (non-PII). Never logs email/password.

### `POST /api/public/auth/password-reset/request`
Body: `{ "workspaceId": "...", "email": "..." }`
- **202** always (enumeration-safe, FR-032). Sends a reset link only if a fallback member exists (FR-020).
- **429** on per-IP throttle.

### `POST /api/public/auth/password-reset/confirm`
Body: `{ "token": "...", "newPassword": "..." }`
- **200** → password rotated, all sessions revoked (FR-031). Old password no longer works.
- **410** `{ "error":"link_invalid" }` — used/expired/unknown token (uniform; FR-019/FR-032).
- **400** — weak password (policy message, no enumeration).
- Concurrency: atomic single-use; exactly one of N concurrent confirms succeeds (FR-035).

---

## Invitations

### `POST /api/internal/invitations`  *(session + ADMIN)*
Body: `{ "email": "...", "role": "RECRUITER" }`
- **201** → `{ "invitationId", "expiresAt" }`; emails a single-use link (FR-016/FR-017). Link token never in the response body or logs.
- **409** `{ "error":"already_member" }` — email already an ACTIVE member (FR-033).
- **403** — caller is not ADMIN (coarse check here; full RBAC matrix is F02).
- **401** — no session.
- Audit: `INVITATION_ISSUED`.

### `GET /api/public/auth/invitations/{token}`
Validate a link before showing the accept form.
- **200** → `{ "email", "role", "workspaceName", "needsPassword": true|false }` (email shown to the invitee themselves only, via the secret token).
- **410** `{ "error":"link_invalid" }` — used/expired/unknown (FR-019). Rate-limited (FR-032).

### `POST /api/public/auth/invitations/{token}/accept`
Body: `{ "password": "..." }` (omitted when the invitee will use SSO — see SSO-invitee note).
- **201** + `Set-Cookie: cad_session` → member created with the invited role, invitation consumed (FR-018). Auto-signs-in.
- **410** — used/expired/unknown link, or concurrent loser (token validity checked **before** password policy, BE-8; FR-035).
- **400** — weak password (only reachable after token validity passes).
- **SSO invitee** (`needsPassword:false`): acceptance completes a real OIDC flow; the linked `(issuer, subject)` is taken from the **validated ID token** (never self-asserted) and is rejected cleanly if already linked to another member (SEC-10).
- Audit: `INVITATION_CONSUMED`.

---

## Session

### `GET /api/internal/auth/me`  *(session)*
- **200** → `{ "memberId", "workspaceId", "role", "displayName", "email" }` (to the member themselves).
- **401** — no/invalid/expired/revoked session, or member deactivated (FR-010/FR-012/FR-028).
- Side effect: sliding renewal — may refresh `cad_session` cookie when inside the idle window (FR-014).

### `POST /api/internal/auth/logout`  *(session + CSRF)*
- **204** + `Set-Cookie: cad_session=; Max-Age=0`. Presenting session revoked; effective next request (FR-015). Other sessions unaffected.
- Audit: `SIGN_OUT`.

---

## Gate behaviour (cross-cutting, asserted by contract tests)

| Request | Expected |
|---|---|
| Any `/api/internal/**` with no session | **401**, no body data (SC-002) |
| Any `/api/internal/**` with valid session | normal processing (FR-010) |
| Any `/api/candidate/**` with no session | reaches handler / **not 401** (SC-003) |
| `/api/internal/**` with expired/tampered/revoked cookie | **401** (FR-012/FR-013) |
| Deactivated member's next `/api/internal/**` request | **401** (FR-021/FR-028) |
| `/actuator/health` on management port | **200** (unchanged) |

---

## Contract test matrix (MockMvc — `AuthContractTest`)

Each endpoint asserts: success status + shape, auth-failure status, validation/error envelope shape, and that no PII/token appears in the response. Plus the gate table above. Per-story integration tests (Testcontainers) cover the stateful flows (issue/consume, revoke, lockout/recovery, concurrency) listed in the spec's per-story acceptance scenarios and the §VII minimum test set.
