# API Contract — OAuth Token Store (Calendar Connections)

**Feature**: F01.1 | **Branch**: `006-oauth-token-store` | **Date**: 2026-06-15

All HTTP endpoints are under `/api/internal/calendar/**` on the existing `@Order(3)` authenticated chain. **Every endpoint operates on the authenticated principal's OWN connection — no `memberId` ever appears in a path or body.** Cross-member access (FR-018) is therefore structurally impossible. Each handler carries a method-security annotation (so `RbacEndpointInventoryTest` passes); the required authority is `isAuthenticated()` (any of the five roles may connect their own calendar).

No response body ever contains a token, authorization code, client secret, or any credential material. The only account-derived value returned is the decrypted `providerAccountId` for display ("Connected as …").

---

## 1. `GET /api/internal/calendar/connections`

List the caller's own calendar connections and their status.

- **Auth**: `isAuthenticated()`
- **200**:
  ```json
  {
    "connections": [
      { "provider": "GOOGLE",    "status": "CONNECTED",          "connectedAccount": "alex@example.com", "connectedAt": "2026-06-15T10:00:00Z" },
      { "provider": "MICROSOFT", "status": "NEEDS_RECONNECTION", "connectedAccount": "alex@contoso.com", "connectedAt": "2026-06-10T09:00:00Z" }
    ]
  }
  ```
  Providers with no connection are omitted (caller infers "Not connected"). `connectedAccount` is the decrypted `providerAccountId`; never a token.
- **Response sets `Cache-Control: no-store`** — `connectedAccount` is personal data (FR-010); it must not be cached by intermediaries or the browser (Security #10). The query is `findByWorkspaceIdAndMemberId(principal)`, so member B's account never appears in member A's response (the two-member isolation test asserts this).

---

## 2. `POST /api/internal/calendar/connections/{provider}/start`

Begin a connection. Creates the single-use `OAuthFlowState` and returns the provider authorize URL for the SPA to navigate to. CSRF-protected (standard SPA cookie/header).

- **Auth**: `isAuthenticated()`
- **Path**: `provider` ∈ `{google, microsoft}` (case-insensitive) → else **400 `unsupported_provider`**.
- **200**:
  ```json
  { "authorizationUrl": "https://accounts.google.com/o/oauth2/v2/auth?client_id=…&scope=…&state=…&code_challenge=…&access_type=offline&prompt=consent" }
  ```
- **Notes**: The `authorizationUrl` requests **only the free/busy scope** (Google `calendar.freebusy`; Microsoft `Calendars.Read offline_access`) and the offline params (`access_type=offline&prompt=consent` for Google; `offline_access` for Microsoft) — research D7. A contract test parses the URL and asserts the exact free/busy scope is present, no write scope (`calendar`/`Calendars.ReadWrite`) is present, and the offline param is present (FR-002, QA #1/#2). The SPA performs a **full-page `window.location.href` navigation** to it (so the eventual callback is a top-level GET carrying the `cad_session` cookie). Returning the URL (a `200` JSON body, **not** a 302) lets the SPA control navigation and keeps the endpoint XHR-friendly.

---

## 3. `GET /api/internal/calendar/connections/{provider}/callback`

The provider redirect target. Top-level browser GET (carries the `SameSite=Lax` session cookie). Not CSRF-protected (GET); anti-forgery is the `state` single-use + member binding (research D4).

- **Auth**: `isAuthenticated()` (the session cookie identifies the member). If the session expired during consent, a callback-path-specific `AuthenticationEntryPoint` 302-redirects to `{spaBaseUrl}/calendar/connections?error=session_expired` (NOT a bare 401 — research D8 / Security #1), so the member is never stranded on a blank page.
- **Query**: `code`, `state` (success) OR `error`, `state` (user denied / provider error)
- **Behaviour**:
  1. Atomic `mongoTemplate.findAndRemove` the `OAuthFlowState` by `_id == state` (single-use; no plain finder exists). Missing/expired (`expiresAt <= now`) → redirect `…/calendar/connections?error=invalid_state` (no token stored).
  2. Assert `state.memberId == principal.memberId()` and `state.provider == {provider}` → mismatch → same `invalid_state` redirect, no record (this is the FR-018/SC-007 cross-member-attach defense).
  3. If `error` present (user denied) → redirect `…?error=consent_denied` (no record).
  4. Exchange `code` (+ PKCE verifier) at the token endpoint **of the consumed `state.provider`** (its endpoint + client credentials — mix-up defense, Security #3). No refresh token returned → redirect `…?error=no_offline_grant` (no usable record stored).
  5. On success: upsert the `CalendarConnection` (status `CONNECTED`, encrypted tokens, `providerAccountId`), audit `CALENDAR_CONNECTED`, redirect `…/calendar/connections?connected={provider}`.
  - An unsupported `{provider}` path → error redirect (not a 500).
- **Always a 302 redirect to the SPA, built from the configured `spaBaseUrl` constant + a fixed path + an allowlisted `error`/`connected` enum value only** — never from a request parameter or forwarded-host header (no open redirect — Security #2). No token/code is ever placed in the redirect URL. Never a JSON body (this is a browser navigation).

---

## 4. `DELETE /api/internal/calendar/connections/{provider}`

Disconnect the caller's connection for one provider. CSRF-protected.

- **Auth**: `isAuthenticated()`
- **204** on success (idempotent — deleting an absent connection is also 204).
- **Behaviour**: best-effort provider revocation (failure ignored, FR-006), delete the row, audit `CALENDAR_DISCONNECTED`. Byte-identical 204 whether or not a connection existed (no existence oracle).
- **400 `unsupported_provider`** for an unknown provider path.

---

## 4b. Audit (FR-020)

Each lifecycle transition writes one `authAuditLog` row via `AuthAuditService` (research D13), actor = the member, payload = `workspaceId` + `memberId` + `provider` + outcome only (no token, no `providerAccountId`, no PII):

| Transition | `AuthEventType` |
|---|---|
| callback success → `CONNECTED` | `CALENDAR_CONNECTED` |
| disconnect / deactivation cleanup | `CALENDAR_DISCONNECTED` |
| refresh `invalid_grant` → `NEEDS_RECONNECTION` | `CALENDAR_RECONNECT_REQUIRED` |

Integration tests assert exactly one row of the expected type per transition and that the row carries no token/account value.

## 5. Error envelope

Reuses the F01/F02 `@RestControllerAdvice` shape:
```json
{ "error": "unsupported_provider", "message": "…" }
```
Error codes: `unsupported_provider` (400). The callback path never returns a JSON error (it always redirects); its failure modes are the `?error=…` query params above.

---

## 6. RBAC matrix (contract test surface — SC-007)

| Endpoint | ADMIN | RECRUITER | HIRING_MANAGER | INTERVIEWER | READ_ONLY | Unauthenticated |
|---|---|---|---|---|---|---|
| `GET /connections` | ✅ own | ✅ own | ✅ own | ✅ own | ✅ own | 401 |
| `POST /{p}/start` | ✅ own | ✅ own | ✅ own | ✅ own | ✅ own | 401 |
| `GET /{p}/callback` | ✅ own | ✅ own | ✅ own | ✅ own | ✅ own | 401 |
| `DELETE /{p}` | ✅ own | ✅ own | ✅ own | ✅ own | ✅ own | 401 |

"own" = the authenticated principal only. There is no addressable path to another member's connection, so the classic 403/404 cross-tenant matrix collapses to "self-only by construction". The contract test asserts: (a) every role can manage its own; (b) two different members each see only their own `GET /connections` (member A never sees member B's connection); (c) unauthenticated → 401 (the F01 `/api/**` entry point).

---

## 7. Internal service contract (forward — consumed by F10/F11, not HTTP)

```
CalendarProvider.validAccessToken(workspaceId, memberId) -> String
   - CONNECTED + token fresh           -> returns cached access token
   - CONNECTED + token expired/near    -> refreshes (CAS, D5), returns fresh token
   - refresh invalid_grant             -> sets NEEDS_RECONNECTION, throws CalendarReconnectRequiredException
   - refresh transient (429/5xx)       -> bounded backoff retry; on exhaustion throws CalendarProviderTransientException (status stays CONNECTED)
   - no connection (absent)            -> throws CalendarNotConnectedException

CalendarConnectionService.disconnectAll(workspaceId, memberId) -> void   (deactivation/erasure seam, D12)
```

These are service-layer seams exercised production-path by integration tests (no HTTP create/refresh endpoint for them in F01.1 — F10/F11 are the HTTP consumers of free/busy that drive `validAccessToken`).
