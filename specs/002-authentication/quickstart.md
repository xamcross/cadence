# Quickstart: Authentication & Session Management

**Feature**: 002-authentication | **Date**: 2026-06-13

How to run and manually verify F01 locally. Assumes the F00 scaffold runs (`./gradlew bootRun` + `ng serve`).

## Prerequisites (zero-download — constitution §X)

- JDK 21 (installed), cached Gradle 9.4.0, Angular CLI 17.3 (installed). Do **not** download tools.
- Local MongoDB: `docker run -d -p 27017:27017 mongo:7`.
- A test OIDC IdP for SSO (e.g. local Keycloak Docker) — optional for the password-only paths.

## Configuration

Backend reads (all have local-dev defaults; secrets via `fly secrets set` in prod):

```yaml
# application.yml (added by this feature)
auth:
  session:
    cookie-name: cad_session
    absolute-ttl: PT8H
    idle-ttl: PT30M
    clock-skew: PT60S
  lockout:
    max-attempts: 5
    window: PT15M
  invitation:
    ttl: PT72H
  password-reset:
    ttl: PT1H
spring:
  security:
    oauth2:
      client:
        registration:
          cadence-oidc:
            client-id: ${OIDC_CLIENT_ID:dev-client}
            client-secret: ${OIDC_CLIENT_SECRET:dev-secret}
            scope: openid,email,profile
        provider:
          cadence-oidc:
            issuer-uri: ${OIDC_ISSUER_URI:http://localhost:8088/realms/cadence}
```

Local secrets (dev only, env vars; in prod all are **Fly secrets** — FR-037, set before first deploy):
- `JWT_SECRET` (HS256 signing key; `JWT_SECRET_PREVIOUS` optional during key rotation)
- `PII_ENC_KEY` (AES-256-GCM key for member email/displayName at rest — D12)
- `PII_PEPPER` (HMAC key for `emailHash`), `TOKEN_PEPPER` (HMAC for invite/reset hashes), `IP_PEPPER` (HMAC for audit IP)
- `OIDC_CLIENT_ID` / `OIDC_CLIENT_SECRET` / `OIDC_ISSUER_URI`

The backend **fails fast at startup** if any required secret is absent in prod (documented local-dev defaults only outside prod).

**Origin**: in production the SPA and API are **same-origin** via a Cloudflare reverse-proxy of `/api`, `/oauth2`, `/login/oauth2/code` to the Fly backend (research D10), so `cad_session` is a first-party `SameSite=Lax` cookie. Locally, `ng serve` proxies `/api` to `:8080` via `proxy.conf.json` to reproduce same-origin.

## Run

```bash
docker run -d -p 27017:27017 mongo:7
cd backend && ./gradlew bootRun        # applies ChangeUnit002 indexes on startup
cd frontend && ng serve                # SPA on http://localhost:4200
```

## Seed a first admin (US-4 standalone demo)

Until F03's workspace-setup bootstrap exists, seed one ACTIVE admin (a small dev-only seeder or `mongosh` insert with a BCrypt hash). This unblocks the invite + password flows end-to-end (spec Assumption "US-4 standalone demo").

## Manual verification (maps to acceptance scenarios)

1. **Password sign-in (US3)**: `POST /api/public/auth/login` with the seeded admin → 200 + `cad_session` cookie. Wrong password → 401 generic. 5 wrong tries → 429; wait 15 min (or shrink window) → recovers.
2. **Gate (US2)**: `GET /api/internal/auth/me` with cookie → 200; without cookie → 401. `GET /api/candidate/ping` (any candidate path) → not 401.
3. **SSO (US1)**: visit `/oauth2/authorization/cadence-oidc` → IdP login → returns with cookie when the OIDC subject maps to an active member; unknown subject → `/login?error=no_access`, no cookie.
4. **Invite (US4)**: as admin `POST /api/internal/invitations {email, role}` → 201 + email link. Open link → `GET /api/public/auth/invitations/{token}` → 200; accept → 201 + auto sign-in. Re-open link → 410.
5. **Reset**: `POST /api/public/auth/password-reset/request` → 202 (same whether or not email exists). Follow link → confirm → old password fails, new works, prior sessions 401.
6. **Sign-out (US5)**: `POST /api/internal/auth/logout` → 204; reuse old cookie → 401. Second device's session still works.
7. **Deactivation**: flip a member to DEACTIVATED (admin/dev) → their next `/api/internal/**` request → 401.

## Automated test verification

```bash
cd backend && ./gradlew test     # Testcontainers spins mongo:7; runs auth integration + contract + tamper + PII-scan suites
cd frontend && ng test --watch=false
```

Key suites: `SessionGateIntegrationTest`, `SessionRevocationIntegrationTest`, `OidcLoginIntegrationTest`, `PasswordSignInIntegrationTest`, `InvitationIntegrationTest`, `PasswordResetIntegrationTest`, `AuthContractTest`, `TokenTamperTest` (SC-012), `AuthLogPiiScanTest` (SC-005).

## Definition-of-Done checks (constitution)

- [ ] `./gradlew test` + `ng test` green (Testcontainers, no Atlas creds).
- [ ] PII log-scan (`AuthLogPiiScanTest` + CI grep) shows zero email/token/password matches (SC-005).
- [ ] Login/invite/reset public pages: axe-core 0 violations; `$localize` on all strings (§IX).
- [ ] No `.ps1` changed; if any, byte-level non-ASCII scan = 0 (§V).
- [ ] Multi-role sub-agent review (≥3) applied/reported (§VI).
- [ ] Deploy: `db-migrate.ps1` (ChangeUnit002) → `deploy-backend.ps1` → `deploy-frontend.ps1`; `JWT_SECRET`, `OIDC_*` set as Fly secrets first.
