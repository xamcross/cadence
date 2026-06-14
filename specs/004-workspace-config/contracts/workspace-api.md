# API Contract: Workspace Setup & Configuration (F03)

**Date**: 2026-06-14 | **Feature**: `004-workspace-config`

Conventions inherited from F01/F02: JSON bodies; session via the `cad_session` HttpOnly cookie; CSRF via the readable cookie for mutating requests; error envelope `{ "error": "<code>", "message": "<human text>" }`. `401` = unauthenticated (F01), `403` = authenticated-but-non-Admin (F02 `RestAccessDeniedHandler`, JSON envelope). All `/api/internal/workspace/**` handlers are gated by a class-level `@PreAuthorize("hasRole('ADMIN')")`.

The credential value is **never** present in any response. Reads expose `credentialSet: boolean` only.

---

## Admin configuration surface — `/api/internal/workspace/**` (ADMIN only)

### `GET /api/internal/workspace/config`
Returns the full settings payload. **200**:
```json
{
  "configured": true,
  "name": "Acme Talent",
  "timeZone": "Europe/London",
  "workingHours": { "start": "09:00", "end": "17:30" },
  "slaSilenceWindowDays": 5,
  "retentionPeriodDays": 365,
  "retentionAcknowledgedAt": "2026-06-14T10:00:00Z",
  "brandColor": "#1F2937",
  "hasLogo": true,
  "emailSendingDomain": "careers.acme.com",
  "credentialSet": true,
  "templateLocks": { "interview_invite": true }
}
```
- Non-Admin → **403** `{ "error": "forbidden", ... }`. Unauthenticated → **401**.
- On an unconfigured workspace → **200** with `"configured": false` and null/default operational fields.

### `POST /api/internal/workspace/setup`
First-run wizard completion. Atomic conditional upsert (D4).
```json
{ "name": "Acme Talent", "timeZone": "Europe/London",
  "workingHours": { "start": "09:00", "end": "17:30" },
  "slaSilenceWindowDays": 5, "retentionPeriodDays": 365,
  "retentionAcknowledged": true }
```
- **200** → `WorkspaceConfigResponse` (`configured: true`); audits `WORKSPACE_CONFIGURED` (actor, ts, acknowledged days).
- `retentionAcknowledged != true` → **400** `{ "error": "retention_not_acknowledged" }`, stays unconfigured (FR-004/SC-003).
- Any invalid field → **400** `{ "error": "validation_failed", "fields": { "timeZone": "...", ... } }`, nothing persisted (FR-005/SC-008).
- Already configured (or concurrent loser) → **409** `{ "error": "already_configured" }` (FR-006/SC-009).

### `PATCH /api/internal/workspace/config`
Update operational settings (partial; targeted `$set`).
```json
{ "slaSilenceWindowDays": 7 }
```
- **200** → updated `WorkspaceConfigResponse`; audits `WORKSPACE_CONFIG_CHANGED` (`outcome` per field; retention also records `oldValue`/`newValue`).
- Invalid field → **400**, no partial write. Unconfigured workspace → **409** `{ "error": "not_configured" }` (settings edits require setup first).

### `PUT /api/internal/workspace/branding`
Set brand colour. **200**; non-`#RRGGBB` → **400**. Audits `branding`.

### `POST /api/internal/workspace/logo`  (multipart `file`)
Upload/replace logo. Validated size → magic-byte → `ImageIO` (D6).
- **200** `{ "hasLogo": true }`; audits `logo`.
- > 1 MB / non-PNG-JPEG / SVG / magic-mismatch / undecodable / oversize dims → **400** `{ "error": "invalid_logo", "message": "..." }` (FR-012/SC-008).

### `DELETE /api/internal/workspace/logo`
Clear logo → `hasLogo=false`; subsequent branding read returns the default placeholder. **204**; audits `logo`.

### `PUT /api/internal/workspace/email`
Set sending domain + provider credential.
```json
{ "sendingDomain": "careers.acme.com", "credential": "SG.xxxxx" }
```
- **200** `{ "emailSendingDomain": "...", "credentialSet": true }` — **never** echoes the credential (FR-017/SC-006). Stored encrypted at rest (FR-016/SC-007).
- Malformed domain → **400**. Re-PUT with a new credential **rotates** (overwrites ciphertext; old unrecoverable). Audits `email_config`.

### `DELETE /api/internal/workspace/email/credential`
Unset the credential → `credentialSet=false`. **204**; audits `email_config`.

### `PUT /api/internal/workspace/templates/{key}/lock`
```json
{ "locked": true }
```
- **200** → updated `templateLocks`; audits `template_lock`. Empty/oversize `{key}` → **400**.
- Forward contract: the "locked ⇒ Recruiter cannot edit" rule is enforced by F21 against this state (not exercised by F03).

---

## Public candidate-facing branding — `/api/public/workspace/**` (no session)

Exposes **only** non-PII brand assets (logo + colour). Reachable without auth (F02 `@Order(2)` permitAll chain) because candidate pages have no session.

### `GET /api/public/workspace/branding`
**200** (always, set/unset/partial — SC-011):
```json
{ "brandColor": "#1F2937", "logoUrl": "/api/public/workspace/logo" }
```
Resolved per attribute (defaults when unset, D5). Never includes any setting or the credential.

### `GET /api/public/workspace/logo`
- **200** image bytes with the verified `Content-Type` (`image/png`/`image/jpeg`) or the static default placeholder when `hasLogo=false`. Response headers (review SEC-BLOCKER-2): `X-Content-Type-Options: nosniff` (load-bearing anti-MIME-sniff), `Content-Disposition: inline`, `Content-Security-Policy: default-src 'none'; sandbox` (defense-in-depth), and `Cache-Control: public, max-age=300` + `ETag` so the candidate CDN serves the single ≤ 1 MB asset from the edge (anonymous, bounded — no per-request Mongo fetch). Raster-only, so it never executes script. Headers are set by `PublicBrandingController` (the `@Order(2)` chain adds no CSP here).

> **Public-oracle note (SEC-MAJOR-1)**: distinguishing a custom logo/colour from the default is *intentional* — branding is public-by-design on candidate pages. These two brand attributes are the **only** state the public reads expose; the configured/unconfigured state, any setting, `credentialSet`, and the credential are never observable here.

---

## Cross-feature: `GET /api/internal/auth/me` (MODIFIED)

Both the backend `AuthDtos.MemberSummary` **and** the frontend `core/auth/auth.models.ts` `MemberSummary` interface gain `workspaceConfigured: boolean` (review FE-1) so the SPA shell — reading off the existing `auth.member$` stream — routes a first-run Admin to the wizard and a non-Admin to a neutral "setup pending" state (D3/US6). Authenticated-any-role (unchanged `@PreAuthorize("isAuthenticated()")`). Only the boolean is exposed below Admin — no setting values. (The F02 `role.guard.spec.ts` `member()` test factory must add the new required field.)

---

## Authorization matrix (all internal endpoints)

| Endpoint | Admin | Recruiter / HM / Interviewer / Read-only | Unauth |
|---|---|---|---|
| `GET/POST/PATCH/PUT/DELETE /api/internal/workspace/**` | ✅ 2xx | ❌ 403 | 401 |
| `GET /api/public/workspace/branding`, `/logo` | ✅ | ✅ (public) | ✅ (public) |
| `GET /api/internal/auth/me` | ✅ | ✅ (authenticated-any-role) | 401 |

The 5×4 (surface × non-admin role) refusal matrix on the internal surface is asserted by `WorkspaceRbacContractTest` (SC-001/SC-002). The internal prefix ensures `RbacEndpointInventoryTest` enforces the declared role.
