# Implementation Plan: Authentication & Session Management

**Branch**: `002-authentication` | **Date**: 2026-06-13 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/002-authentication/spec.md`

## Summary

Deliver workspace-member identity and session management for Cadence: **OIDC SSO** as the primary sign-in path, **email + password** as a rate-limited fallback, **invite-only** provisioning, and a **revocable session** that gates every internal endpoint while keeping candidate paths public. The technical approach uses Spring Security's built-in OIDC login (authorization-code + PKCE) to authenticate against the workspace IdP, then issues a **Cadence session JWT carried in an HttpOnly+Secure+SameSite cookie**, backed by a **MongoDB session registry** so sign-out and deactivation take effect on the member's next request (reconciling statelessness with the spec's revocation requirements). Passwords are BCrypt-hashed; invitation and reset links are 128-bit CSPRNG tokens stored hashed with MongoDB TTL expiry. No new infrastructure is introduced — single Spring Boot instance + MongoDB only.

## Technical Context

**Language/Version**: Java 21 (backend, toolchain pinned); TypeScript 5.4 / Angular 17.3 (frontend)
**Primary Dependencies**: Spring Boot 3.3.5 (web, data-mongodb, security, actuator, aop); **+ `spring-boot-starter-oauth2-client`** (new — OIDC login + transitive Nimbus `JwtEncoder`/`JwtDecoder` for the self-issued session JWT); Mongock 5.4.4; logstash-logback-encoder 9.0. Frontend: Angular standalone + Angular Material 17.3 (no new frontend deps)
**Storage**: MongoDB 7.x (Atlas in prod, Testcontainers `mongo:7` in tests) — collections: `members`, `sessions`, `invitations`, `passwordResets`, `authAuditLog`
**Testing**: JUnit 5 + Testcontainers (integration), MockMvc (API contract), Mockito (unit); Jasmine (frontend unit); Playwright/Cypress (E2E per constitution §VII)
**Target Platform**: Fly.io single Machine (backend JAR), Cloudflare Pages (Angular SPA), MongoDB Atlas
**Project Type**: Web application (Angular frontend + Spring Boot backend)
**Performance Goals**: OIDC-callback-to-session server-side processing < 2 s p95 (SC-001); per-request auth overhead (signature verify + one indexed session lookup) < 15 ms p95
**Constraints**: Single instance + MongoDB only (no Redis/queue/cache — constitution §IV); zero PII/credentials in logs (§VIII); TLS 1.2+; zero tool downloads (§X); all `.ps1` pure ASCII (§V)
**Scale/Scope**: MVP workspace scale (tens–hundreds of members per workspace); 5 user stories, 36 FRs, 12 SCs

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Gate | Question | Result |
|---|---|---|
| **C1** | Within MVP scope (spec §11)? | ✅ PASS — F01 is Tier-0 Foundation MVP. OIDC-only (SAML deferred to v1.5, recorded in backlog). |
| **C2** | New service, queue, or replica? | ✅ PASS — session registry + lockout state live in MongoDB; per-IP throttle is in-process on the single instance. No Redis/broker/cache tier. |
| **C3** | Exposes candidate PII to unauthorized roles? | ✅ PASS — feature *adds* the auth gate; candidate endpoints stay public by private link only; member PII encrypted at rest. |
| **C4** | Dependency outside the fixed stack? | ⚠️ JUSTIFIED — one new **Spring Boot starter** (`spring-boot-starter-oauth2-client`); it is a first-party Spring starter, not an infra SDK. Recorded in Complexity Tracking + Dependency Policy. No third-party JWT lib (Nimbus arrives transitively and is Spring-managed). |
| **C5** | New/modified Windows scripts contain non-ASCII? | ✅ PLANNED — no new `.ps1` expected; any change byte-scanned before done. |
| **C6** | Multi-role sub-agent review (≥3) scheduled? | ✅ SCHEDULED — runs at the end of this plan (user-requested) and again at task close. |
| **C7** | Downloads any build tool/runtime/CLI? | ✅ PASS — uses cached Gradle 9.4.0, installed JDK 21, existing Angular CLI; no downloads. |

**Initial gate: PASS** (one justified dependency; no topology/stack violation).

### Post-Design Re-Check (after Phase 1 + §VI plan review)

Re-evaluated after the multi-role review revised the design (research v2). **Result: PASS, unchanged gate status.**
- **C2 still holds**: the cross-origin fix is a Cloudflare routing rule (D10), not a new service; encryption-at-rest is app-level AES-256-GCM (D12), rate-limit cooldown is in MongoDB (D5) — no Redis/queue/cache introduced.
- **C3 strengthened**: PII now ciphertext-at-rest with keyed `emailHash` (D12); IP audit values HMAC-keyed (SEC-6).
- **C4 unchanged**: still exactly one new Spring starter; AES/HMAC use the JDK + Spring crypto already on the classpath (no new crypto dependency).
- **C7 unchanged**: zero downloads.
- The review raised **2 REJECT + 2 APPROVE-WITH-CHANGES**; all BLOCKER/MAJOR findings are applied in research v2 / data-model / contracts (disposition logged in `checklists/requirements.md`). No constitution gate moved to FAIL.

## Project Structure

### Documentation (this feature)

```text
specs/002-authentication/
├── plan.md              # This file
├── research.md          # Phase 0 — decisions & rationale
├── data-model.md        # Phase 1 — entities, indexes, state
├── quickstart.md        # Phase 1 — local run + manual verification
├── contracts/
│   └── auth-api.md      # Phase 1 — REST endpoint contracts
├── checklists/
│   └── requirements.md  # Spec quality + review log
└── tasks.md             # Phase 2 (/speckit.tasks — NOT created here)
```

### Source Code (repository root)

```text
backend/src/main/java/com/cadence/
├── api/
│   ├── AuthController.java            # login, logout, me, password-reset (public + internal)
│   └── InvitationController.java      # admin create invite; public validate/accept
├── domain/
│   ├── Member.java                   # @Document("members") — PII fields, role, status, sso, lockout
│   ├── Role.java                     # enum: ADMIN, RECRUITER, HIRING_MANAGER, INTERVIEWER, READ_ONLY
│   ├── MemberStatus.java             # enum: ACTIVE, DEACTIVATED
│   ├── PasswordCredential.java       # embedded: bcrypt hash (no plaintext)
│   ├── SsoIdentity.java              # embedded: provider + subject
│   ├── Session.java                  # @Document("sessions") — jti, memberId, absoluteExpiry, revoked
│   ├── Invitation.java               # @Document("invitations") — tokenHash, role, status, expiresAt
│   ├── PasswordResetToken.java       # @Document("passwordResets") — tokenHash, memberId, expiresAt
│   └── AuthAuditEvent.java           # @Document("authAuditLog") — non-PII event record
├── repository/
│   ├── MemberRepository.java
│   ├── SessionRepository.java
│   ├── InvitationRepository.java
│   ├── PasswordResetTokenRepository.java
│   └── AuthAuditEventRepository.java
├── service/
│   ├── AuthenticationService.java    # password sign-in, generic-failure, lockout orchestration
│   ├── SessionService.java           # issue/validate/renew/revoke session JWT + registry
│   ├── InvitationService.java        # create/validate/accept (single-use, concurrency-safe)
│   ├── PasswordResetService.java     # request/confirm (single-use, rotate, revoke sessions)
│   ├── LoginAttemptService.java      # per-account lockout (Mongo) + per-IP throttle (in-proc)
│   └── AuthAuditService.java         # append-only, non-PII audit writes
├── security/
│   ├── SecurityConfig.java           # MODIFIED — 3 ordered chains (actuator@1 / public@2 / main@3) + oauth2Login + CSRF(withHttpOnlyFalse)
│   ├── SessionCookieAuthFilter.java  # per-request: verify JWT (HS256, skew on exp only) + registry/revocation/status + throttled renewal
│   ├── OidcLoginSuccessHandler.java  # map OIDC subject→Member, invalidate IdP session (fixation), issue cookie, redirect
│   ├── OidcLoginFailureHandler.java  # IdP-unavailable/OAuth2 error → /login?error= (no stack trace) (FR-034)
│   ├── SessionCookieFactory.java     # HttpOnly+Secure+SameSite=Lax cookie build/clear
│   ├── JwtSupport.java               # NimbusJwtEncoder/Decoder pinned HS256 + `kid` rotation (current+previous key)
│   ├── PiiCryptoConverter.java       # AES-256-GCM Spring Data converter + HMAC emailHash (D12)
│   └── TokenHasher.java              # HMAC-SHA-256 (TOKEN_PEPPER) for invite/reset/IP hashing (D4/SEC-2/SEC-6)
├── config/
│   ├── ClockConfig.java              # @Bean Clock (systemUTC in prod; mutable in tests) — deterministic skew/lockout/renewal (D11)
│   ├── AuthProperties.java           # @ConfigurationProperties("auth") — ttls, lockout, skew
│   └── migration/
│       └── ChangeUnit002_AuthIndexes.java   # members/sessions/invitations/passwordResets/authAuditLog indexes + TTL + partial SSO index
└── resources/
    └── application.yml               # MODIFIED — oauth2 client registration placeholders, auth.* config block

backend/src/test/java/com/cadence/
├── auth/
│   ├── PasswordSignInIntegrationTest.java     # US3: success, generic fail, lockout/recovery
│   ├── SessionGateIntegrationTest.java        # US2: 401 no-session, 200 valid, candidate not-gated, deactivation-next-request
│   ├── SessionRevocationIntegrationTest.java  # US5: signout single session; deactivation all sessions
│   ├── OidcLoginIntegrationTest.java          # US1: subject→member map, no-match denied, role attached
│   ├── InvitationIntegrationTest.java         # US4: issue/accept single-use, concurrent-redeem-once, no-self-register, no-takeover
│   ├── PasswordResetIntegrationTest.java      # reset rotate + session revoke + enumeration-safe
│   ├── AuthContractTest.java                  # MockMvc: status codes + error envelope per endpoint
│   ├── TokenTamperTest.java                   # SC-012 adversarial suite
│   └── AuthLogPiiScanTest.java                # SC-005: no PII/credentials in captured logs

frontend/src/app/
├── core/auth/
│   ├── auth.service.ts               # login, logout, me(), reset/invite calls (withCredentials)
│   ├── auth.guard.ts                 # CanActivate → redirect to /login when 401
│   ├── auth.interceptor.ts           # 401 → clear state + redirect; ensure withCredentials
│   └── auth.models.ts                # Member, Role, AuthState
├── features/auth/
│   ├── login/login.component.ts          # SSO primary button + email/password fallback form
│   ├── accept-invite/accept-invite.component.ts
│   ├── reset-request/reset-request.component.ts
│   └── reset-confirm/reset-confirm.component.ts
├── app.config.ts                     # MODIFIED — provideHttpClient(withXsrfConfiguration, withInterceptors)
└── app.routes.ts                     # MODIFIED — /login, /accept-invite, /reset, /reset/confirm, guarded shell

frontend/src/app/core/auth/*.spec.ts  # Jasmine: guard (each role/redirect), interceptor, login form validation
frontend/e2e/                          # Playwright: SSO happy path, password fallback, invite accept
```

**Structure Decision**: Web-application layout (constitution Reference Source Layout). Backend follows the established `api/domain/repository/service/security/config` packages; frontend follows `core/` (auth, interceptors, guards) + `features/` standalone components. This feature *extends* the existing scaffold (notably `security/SecurityConfig.java` and a new Mongock changeset `002`), adding no new top-level structure.

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| New dependency `spring-boot-starter-oauth2-client` | OIDC SSO is the constitution-mandated primary auth path (FR-001/FR-025); the starter provides standards-correct authorization-code+PKCE login, ID-token validation (sig/iss/aud/exp/nonce), and the Nimbus `JwtEncoder`/`JwtDecoder` used to sign/verify the self-issued session JWT. | Hand-rolling the OIDC handshake and JWT crypto would be error-prone security code (the exact class of bug §V/§VIII warn against) and would still need a JWT library; a first-party Spring starter is the lowest-risk choice and is permitted by the Dependency Policy with this justification. |
| Per-request MongoDB session-registry lookup (JWT is not purely stateless) | FR-028 requires sign-out (FR-015) and deactivation (FR-021) to take effect on the member's **next request**; a purely stateless JWT cannot be revoked before expiry. | A stateless-only JWT with short TTL was rejected: it cannot meet "next request" revocation and would force an unacceptably short re-login cadence. The registry lookup is a single indexed read on the existing MongoDB instance — no new service (C2 holds). Write amplification avoided via throttled renewal (D1). |
| Test-only dependencies: `spring-security-test`, WireMock | `spring-security-test` provides `oidcLogin()` post-processor; WireMock stubs the OIDC JWKS/token endpoints so full ID-token validation + IdP-failure paths are tested deterministically in CI with no live IdP (D11). | A live/Testcontainers Keycloak in unit/integration tests is slow, network-bound, and risks a tool pull in CI; mock OIDC is the standard, hermetic approach. Test-scope only — no runtime/production dependency added. Recorded per Dependency Policy. |
