# Tasks: Authentication & Session Management

**Input**: Design documents from `C:\Users\xamcr\Cadence\specs\002-authentication\`
**Prerequisites**: plan.md, spec.md, research.md (v2), data-model.md, contracts/auth-api.md, quickstart.md

**Tests**: INCLUDED and written FIRST — the constitution (§VII Test-First & Acceptance-Driven) is non-negotiable for backend business logic and acceptance paths. Each story's tests are authored before its implementation and must fail first.

**Organization**: Tasks grouped by user story (US1–US5) for independent implementation/testing.

## Path Conventions (web app — see plan.md Structure)

- Backend main: `backend/src/main/java/com/cadence/`
- Backend test: `backend/src/test/java/com/cadence/`
- Frontend: `frontend/src/app/`
- All integration tests extend `BaseIntegrationTest` (shared `@ServiceConnection` singleton `mongo:7`), clean via `mongoTemplate.remove(...)` (never `dropCollection`), and use `@MockBean` (Boot 3.3) — per CLAUDE.md.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Dependencies and configuration scaffolding.

- [X] T001 Add `implementation 'org.springframework.boot:spring-boot-starter-oauth2-client'` to `backend/build.gradle` (BOM-managed; no version pin) — provides OIDC login + transitive Nimbus JwtEncoder/Decoder (research D2/C4).
- [X] T002 [P] Add test deps `testImplementation 'org.springframework.security:spring-security-test'` and **one pinned** WireMock coordinate (e.g. `org.wiremock:wiremock-standalone:3.x` — confirm it resolves from the existing Maven cache, no tool/CLI download) to `backend/build.gradle` (research D11; test scope only; §X — BE-8).
- [X] T003 [P] Add `auth.*` config block (session ttls, lockout, clock-skew, invitation/reset ttls) and `spring.security.oauth2.client.registration/provider.cadence-oidc` placeholders to `backend/src/main/resources/application.yml` (no secrets inline — quickstart config block).
- [X] T004 [P] Add `frontend/proxy.conf.json` routing `/api`, `/oauth2`, `/login/oauth2/code` to `http://localhost:8080`, reference it from `frontend/angular.json` `serve`, **and set dev `apiBaseUrl` to a relative `/api`** in `frontend/src/environments/environment.ts` so XHR goes through the proxy (same-origin) and Angular XSRF attaches the header — an absolute cross-origin `apiBaseUrl` breaks the cookie/XSRF locally (research D10/D8; FE-1/FE-2).

---

## Phase 2: Foundational (Blocking Prerequisites)

**⚠️ CRITICAL**: No user story can begin until this phase is complete. This builds the shared identity/session backbone every story uses.

### Backend — domain & enums

- [X] T005 [P] Create `Role` enum (ADMIN, RECRUITER, HIRING_MANAGER, INTERVIEWER, READ_ONLY) in `backend/src/main/java/com/cadence/domain/Role.java`.
- [X] T006 [P] Create `MemberStatus` enum (ACTIVE, DEACTIVATED) in `backend/src/main/java/com/cadence/domain/MemberStatus.java`.
- [X] T007 [P] Create `InvitationStatus` (PENDING, CONSUMED), `ResetStatus` (PENDING, CONSUMED), and `AuthEventType` enums in `backend/src/main/java/com/cadence/domain/` (data-model Enums; no stored EXPIRED state — BE-9).
- [X] T008 [P] Create `Member` `@Document("members")` with embedded `PasswordCredential` and `SsoIdentity`, fields per data-model (encrypted email/displayName + `emailHash`, `ssoProvider`/`ssoSubject`, `status`, `failedLoginCount`, `lockedUntil`) in `backend/src/main/java/com/cadence/domain/Member.java` (+ `PasswordCredential.java`, `SsoIdentity.java`). NOTE: the POJO is [P], but **persisting/at-rest-testing Member depends on T020** (PII converter + emailHash) — do not write Member-persistence code before T020 (BE-2).
- [X] T009 [P] Create `Session` `@Document("sessions")` (id=jti, memberId, role, createdAt, lastSeenAt, absoluteExpiresAt, idleExpiresAt, revoked) in `backend/src/main/java/com/cadence/domain/Session.java`.
- [X] T010 [P] Create `Invitation` `@Document("invitations")` (tokenHash, email[enc], role, status, expiresAt, invitedByMemberId) in `backend/src/main/java/com/cadence/domain/Invitation.java`.
- [X] T011 [P] Create `PasswordResetToken` `@Document("passwordResets")` (memberId, tokenHash, status, expiresAt) in `backend/src/main/java/com/cadence/domain/PasswordResetToken.java`.
- [X] T012 [P] Create `AuthAuditEvent` `@Document("authAuditLog")` (workspaceId, memberId?, eventType, occurredAt, sourceIpHash, outcome) in `backend/src/main/java/com/cadence/domain/AuthAuditEvent.java`.

### Backend — repositories

- [X] T013 [P] Create `MemberRepository` (findByWorkspaceIdAndEmailHash, findBySsoProviderAndSsoSubject) in `backend/src/main/java/com/cadence/repository/MemberRepository.java`.
- [X] T014 [P] Create `SessionRepository` (findById, delete/markRevoked by memberId) in `backend/src/main/java/com/cadence/repository/SessionRepository.java`.
- [X] T015 [P] Create `InvitationRepository` in `backend/src/main/java/com/cadence/repository/InvitationRepository.java`.
- [X] T016 [P] Create `PasswordResetTokenRepository` in `backend/src/main/java/com/cadence/repository/PasswordResetTokenRepository.java`.
- [X] T017 [P] Create `AuthAuditEventRepository` in `backend/src/main/java/com/cadence/repository/AuthAuditEventRepository.java`.

### Backend — config, crypto, infra

- [X] T018 [P] Create `AuthProperties` `@ConfigurationProperties("auth")` binding ttls/lockout/skew in `backend/src/main/java/com/cadence/config/AuthProperties.java`.
- [X] T019 [P] Create `ClockConfig` exposing a `@Bean Clock` (systemUTC in prod; overridable in tests) in `backend/src/main/java/com/cadence/config/ClockConfig.java` (research D11).
- [X] T020 Create `PiiCryptoConverter` (AES-256-GCM read/write converters for PII fields, key from `PII_ENC_KEY`) and register Mongo custom converters; compute `emailHash` = HMAC-SHA-256(email, `PII_PEPPER`) in `backend/src/main/java/com/cadence/security/PiiCryptoConverter.java` (research D12).
- [X] T021 [P] Create `TokenHasher` = HMAC-SHA-256 with `TOKEN_PEPPER`/`IP_PEPPER` for invite/reset/IP hashing in `backend/src/main/java/com/cadence/security/TokenHasher.java` (research D4/SEC-2/SEC-6).
- [X] T022 Create Mongock `ChangeUnit002_AuthIndexes` (`order="002"`) creating all indexes + TTL + partial SSO index per data-model, with targeted `dropIndex` rollback, in `backend/src/main/java/com/cadence/config/migration/ChangeUnit002_AuthIndexes.java` (research D6; CLAUDE.md Mongock rules).
- [X] T023 [P] Create `AuthAuditService` (append-only, non-PII writes; hashes source IP via TokenHasher) in `backend/src/main/java/com/cadence/service/AuthAuditService.java`.
- [X] T024 Create `JwtSupport` — NimbusJwtEncoder/Decoder pinned to **HS256**, `kid` header, accepts current+previous `JWT_SECRET` keys, fail-fast if absent in prod — in `backend/src/main/java/com/cadence/security/JwtSupport.java` (research D1/SEC-3; BE-10). **Not [P]** — head of the crypto/session spine (T024→T026→T027→T028).
- [X] T025 [P] Create `SessionCookieFactory` building/clearing the `cad_session` cookie (`HttpOnly; Secure; SameSite=Lax; Path=/`) in `backend/src/main/java/com/cadence/security/SessionCookieFactory.java` (research D10/D1).
- [X] T026 Create `SessionService` — `issue()`, `validate()` (skew on JWT exp only; exact-now for absolute/idle/revoked/member-status), `revokeOne(jti)`, `revokeAllForMember(memberId)`, throttled sliding renewal (>1/3 idle window) using the injected `Clock` — in `backend/src/main/java/com/cadence/service/SessionService.java` (research D1; data-model Session rules; depends on T024, T014, T019).
- [X] T027 Create `SessionCookieAuthFilter` (per-request: read cookie → SessionService.validate → set SecurityContext principal+role; renew cookie when due) in `backend/src/main/java/com/cadence/security/SessionCookieAuthFilter.java` (**depends on T026**).
- [X] T028 Modify `SecurityConfig` to 3 ordered chains — actuator `@Order(1)` (unchanged), public `@Order(2)` `securityMatcher("/api/public/**","/api/candidate/**")` permitAll + CSRF-exempt, main `@Order(3)` `SessionCookieAuthFilter` + `anyRequest().authenticated()` + `CookieCsrfTokenRepository.withHttpOnlyFalse()` + **`oauth2Login()` wired with placeholder/default handlers** (real success/failure handlers swapped in at T041) so the foundational chain is complete; ensure `/oauth2/**` + `/login/oauth2/code/**` resolve on the main chain — in `backend/src/main/java/com/cadence/security/SecurityConfig.java` (**depends on T027**; research D7/BE-1/BE-2). `SecurityConfig.java` is a **serialized file** edited again at T041 and T048 — land in that order.
- [X] T029 Create `AuthController` skeleton with `GET /api/internal/auth/me` (returns current member identity+role; 401 when unauthenticated) plus a test-profile-only `/api/candidate/__probe` stub for SC-003, in `backend/src/main/java/com/cadence/api/AuthController.java` (contracts §Session).

### Backend — foundational test

- [X] T030 [P] Add `ChangeUnit002` index-bootstrap integration test asserting `listIndexes` output for all 5 collections (incl. unique/TTL/partial) in `backend/src/test/java/com/cadence/auth/AuthIndexBootstrapTest.java` (mirrors F00.1 IndexBootstrapTest pattern). MUST clean via `mongoTemplate.remove(...)`, **never `dropCollection`** (would destroy Mongock indexes — CLAUDE.md/BE-5).

### Frontend — foundational

- [X] T031 [P] Create `auth.models.ts` (Member, Role, AuthState) in `frontend/src/app/core/auth/auth.models.ts`.
- [X] T032 [P] Create `auth.service.ts` (login, logout, me$, reset/invite calls) in `frontend/src/app/core/auth/auth.service.ts`.
- [X] T033 [P] Create `apiInterceptor` (sets `withCredentials:true` for apiBaseUrl) and `authErrorInterceptor` (**only 401** → redirect `/login`; do NOT redirect on legitimate 410 link-invalid responses; skip redirect when already on a public auth route whose paths match T035 exactly) in `frontend/src/app/core/auth/auth.interceptor.ts` (FE-3/FE-5/FE-6).
- [X] T034 [P] Create `auth.guard.ts` (CanActivate via auth.service.me$) in `frontend/src/app/core/auth/auth.guard.ts`.
- [X] T035 Modify `frontend/src/app/app.config.ts` to add `provideHttpClient(withInterceptors([...]), withXsrfConfiguration({cookieName:'XSRF-TOKEN',headerName:'X-XSRF-TOKEN'}))`, and `frontend/src/app/app.routes.ts` with public auth routes (`/login`, `/accept-invite`, `/reset`, `/reset/confirm`) as **top-level siblings (NOT children) of the guarded shell** so the guard never fires on them (avoids redirect loop) (FE-4/FE-6/FE-8; depends on T033, T034).

**Checkpoint**: Identity/session backbone, security chains, indexes, crypto, and frontend auth core exist. User stories can now proceed.

---

## Phase 3: User Story 1 - Sign in with company SSO (Priority: P1) 🎯 MVP

**Goal**: A member signs in via the workspace OIDC IdP and lands authenticated.

**Independent Test**: Drive an OIDC login against a mock IdP → `cad_session` issued → `GET /api/internal/auth/me` returns 200 with the mapped member+role; unknown/deactivated subject → `/login?error=no_access`, no cookie.

### Tests for User Story 1 (write first, must fail) ⚠️

- [ ] T036 [P] [US1] `OidcLoginIntegrationTest` — subject→member mapping, no-match → no_access, DEACTIVATED member → no_access, role attached to session — using `spring-security-test` `oidcLogin()` in `backend/src/test/java/com/cadence/auth/OidcLoginIntegrationTest.java` (QA-2/QA-12).
- [ ] T037 [P] [US1] `OidcValidationTest` — full ID-token validation (sig/iss/aud/exp/nonce) via WireMock JWKS/token stub, and IdP-unavailable → `/login?error=idp_unavailable` (no stack trace) — in `backend/src/test/java/com/cadence/auth/OidcValidationTest.java` (FR-025/FR-034; QA-14).

### Implementation for User Story 1

- [X] T038 [US1] Implement SSO member-matching in `MemberService` (resolve `(issuer,subject)` → ACTIVE member; reject otherwise) in `backend/src/main/java/com/cadence/service/MemberService.java`.
- [X] T039 [US1] Implement `OidcLoginSuccessHandler` (match member, invalidate IdP `HttpSession` + fresh CSRF for fixation, issue `cad_session`, redirect to SPA) in `backend/src/main/java/com/cadence/security/OidcLoginSuccessHandler.java` (SEC-4; depends on T026, T038).
- [X] T040 [US1] Implement `OidcLoginFailureHandler` (map all OAuth2/OIDC errors → `/login?error=...`, no exception detail) in `backend/src/main/java/com/cadence/security/OidcLoginFailureHandler.java` (FR-034/SEC-12).
- [X] T041 [US1] Swap the real `oauth2Login(successHandler, failureHandler)` handlers into the main chain (replacing the T028 placeholders) in `backend/src/main/java/com/cadence/security/SecurityConfig.java` (depends on T039, T040; serialized SecurityConfig edit after T028).
- [X] T042 [P] [US1] Create `login` component with SSO as the primary CTA (links to `/oauth2/authorization/cadence-oidc`) and `?error=` handling (incl. an accessible `no_access` / `idp_unavailable` rendered message, not a blank state) in `frontend/src/app/features/auth/login/login.component.ts` (FR-002; QA-2).
- [ ] T043 [US1] Add Playwright `sso-login.spec` covering the happy path → dashboard **and the `?error=no_access` no-cookie path** (mock/Keycloak IdP fixture) in `frontend/e2e/sso-login.spec.ts` (QA-1/QA-2; log any CI IdP fallback, never silently skip).

**Checkpoint**: SSO sign-in works end to end and is independently testable.

---

## Phase 4: User Story 2 - Protected access to internal data (Priority: P1)

**Goal**: Internal endpoints are gated; candidate paths stay public; revocation is effective on next request.

**Independent Test**: Call `/api/internal/**` with no/expired/tampered/revoked cookie → 401; with a valid session → 200; `/api/candidate/**` → not 401; deactivate a member → their next request → 401.

### Tests for User Story 2 (write first, must fail) ⚠️

- [X] T044 [P] [US2] `SessionGateIntegrationTest` — 401 no-session, 200 valid, candidate-path not-gated (via `__probe`), expired/tampered cookie 401, deactivation-on-next-request 401 — in `backend/src/test/java/com/cadence/auth/SessionGateIntegrationTest.java` (SC-002/SC-003/SC-010; uses Clock for expiry).
- [ ] T045 [P] [US2] `TokenTamperTest` — `alg:none`, altered alg, stripped/forged signature, mutated sub/role claim, another member's token → all rejected; **plus a key-rotation case**: a token signed with `JWT_SECRET_PREVIOUS` still validates while a retired key is rejected — in `backend/src/test/java/com/cadence/auth/TokenTamperTest.java` (SC-012/SEC-3/QA-3).
- [ ] T046 [P] [US2] `AuthContractTest` (MockMvc) — gate-behaviour matrix + error-envelope shape for `/me`, login, password-reset, invitation, and logout endpoints; internal POST without CSRF → 403; public login without CSRF → not 403 — in `backend/src/test/java/com/cadence/auth/AuthContractTest.java` (contracts §VII API-contract type; QA-1/QA-16).

### Implementation for User Story 2

- [X] T047 [US2] Add `revokeAllForMember` enforcement on DEACTIVATED status in `SessionService.validate()` (reject when owning member not ACTIVE) in `backend/src/main/java/com/cadence/service/SessionService.java` (FR-021/FR-028; refines T026).
- [X] T048 [US2] Verify/finish deny-by-default + candidate allow-list wiring and CSRF in `SecurityConfig` so all T044/T046 cases pass in `backend/src/main/java/com/cadence/security/SecurityConfig.java` (FR-010/FR-029).
- [X] T049 [P] [US2] Apply `auth.guard` to the protected app-shell route and confirm 401→/login redirect with no loop in `frontend/src/app/app.routes.ts` + `frontend/src/app/core/auth/auth.guard.ts` (FE-6).

**Checkpoint**: The gate is provably enforced; US1+US2 together form the deployable MVP.

---

## Phase 5: User Story 3 - Email + password fallback sign-in (Priority: P2)

**Goal**: Password sign-in with uniform failures, lockout/recovery, and forgotten-password reset.

**Independent Test**: Correct creds → session; wrong/unknown/locked → identical 401; 5 failures → locked, recovers after window; reset link rotates password + revokes sessions; reset request is enumeration-safe; single-use under concurrency.

### Tests for User Story 3 (write first, must fail) ⚠️

- [X] T050 [P] [US3] `PasswordSignInIntegrationTest` — success; unknown/wrong/locked all return identical 401 (dummy-hash path); lockout after 5 within window then recovery after window (Clock-advanced); DEACTIVATED member → 401 — in `backend/src/test/java/com/cadence/auth/PasswordSignInIntegrationTest.java` (FR-005/FR-006/SEC-7; QA-6/QA-12/QA-13).
- [X] T051 [P] [US3] `PasswordResetIntegrationTest` — request always 202 (enumeration-safe); confirm rotates credential + revokes all sessions; old password fails; concurrent confirm → exactly one success (CountDownLatch); **a forged/unknown reset token returns the uniform invalid response before any weak-password 400** (validate-before-policy); **burst of reset-requests/token-validations trips a 429 and responses are uniform** — in `backend/src/test/java/com/cadence/auth/PasswordResetIntegrationTest.java` (FR-020/FR-031/FR-032/FR-035/SC-008; BE-8/FR-032 aux-endpoint).

### Implementation for User Story 3

- [X] T052 [P] [US3] Create `LoginAttemptService` — per-account lockout in Mongo (Clock-driven, 5/15 min) + **in-process-only** per-IP throttle (do NOT introduce Redis/shared store — §IV/C2; BE-6) with a test reset hook + Mongo cooldown for reset/invite — in `backend/src/main/java/com/cadence/service/LoginAttemptService.java` (research D5; SEC-7/SEC-8/QA-8).
- [X] T053 [US3] Create `AuthenticationService` — BCrypt verify with always-run dummy-hash, uniform generic outcome, lockout integration, audit — in `backend/src/main/java/com/cadence/service/AuthenticationService.java` (FR-004/FR-005; depends on T052, T026, T023).
- [X] T054 [P] [US3] Create `PasswordResetService` — request (create row only for real member, 202 always) + confirm (atomic `findOneAndUpdate` CAS, rotate credential, revoke sessions, validate-before-policy) — in `backend/src/main/java/com/cadence/service/PasswordResetService.java` (FR-020/FR-031/FR-035; SEC-8/BE-8; depends on T021, T026).
- [X] T055 [US3] Add endpoints `POST /api/public/auth/login`, `POST /api/public/auth/password-reset/request`, `POST /api/public/auth/password-reset/confirm` to `AuthController` (status codes/envelope per contract; 429 from IP throttle only) in `backend/src/main/java/com/cadence/api/AuthController.java` (depends on T053, T054).
- [X] T056 [P] [US3] Add the email+password fallback form (secondary CTA, depends on T042's login component) to `login.component.ts` and create a **standalone** `reset-request.component.ts` page with its own success/error accessible state in `frontend/src/app/features/auth/reset-request/reset-request.component.ts` (FR-002; FE-5).
- [X] T057 [P] [US3] Create `reset-confirm.component.ts` (pre-validate token, accessible invalid/expired-link state, weak-password message) in `frontend/src/app/features/auth/reset-confirm/reset-confirm.component.ts` (FE-9).
- [ ] T058 [P] [US3] Jasmine specs for login form validation (incl. generic "invalid email or password" render, not leaky) and reset-confirm (weak password, 410 rendering) in `frontend/src/app/features/auth/**/*.spec.ts` (QA-5/QA-18).
- [ ] T059 [US3] Playwright `password-fallback.spec` (login + wrong password + lockout copy) in `frontend/e2e/password-fallback.spec.ts`.

**Checkpoint**: Password + reset paths work independently.

---

## Phase 6: User Story 4 - Account provisioning by invitation (Priority: P2)

**Goal**: Admin invites a member; invitee accepts via single-use link; no self-registration; no takeover.

**Independent Test**: Admin creates invite → email link → validate → accept (creates member, auto sign-in) → link reuse 410; re-invite of active member 409 with member unchanged; concurrent accept → one success.

### Tests for User Story 4 (write first, must fail) ⚠️

- [X] T060 [P] [US4] `InvitationIntegrationTest` — issue; validate; accept single-use; concurrent-redeem → exactly one (CountDownLatch); re-invite active member → 409 with role/credential/SSO byte-for-byte unchanged; no direct self-register; validate-before-password-policy; **SSO-invitee accept derives `(issuer,subject)` from a validated ID token (not self-asserted) and a duplicate-subject link is refused with a surfaced error (not 500)** — in `backend/src/test/java/com/cadence/auth/InvitationIntegrationTest.java` (FR-016..019/FR-033/FR-035/SEC-10; QA-7/QA-15/BE-8).

### Implementation for User Story 4

- [X] T061 [US4] Create `InvitationService` — create (reject existing active member), validate (uniform invalid), accept (atomic CAS create member with role; SSO-invitee links `(issuer,subject)` from a validated ID token, reject if already linked) — in `backend/src/main/java/com/cadence/service/InvitationService.java` (FR-033/SEC-10; depends on T021, T020, T026).
- [X] T062 [US4] Create `InvitationController` — `POST /api/internal/invitations` (ADMIN), `GET /api/public/auth/invitations/{token}`, `POST /api/public/auth/invitations/{token}/accept` (auto sign-in) — in `backend/src/main/java/com/cadence/api/InvitationController.java` (contracts §Invitations; depends on T061).
- [X] T063 [P] [US4] Create `accept-invite.component.ts` (pre-validate token, branch on `needsPassword`, accessible invalid-link state) + a minimal Admin "invite member" trigger in `frontend/src/app/features/auth/accept-invite/accept-invite.component.ts` (FE-9).
- [ ] T064 [P] [US4] Jasmine spec for accept-invite (needsPassword branch, invalid-link) in `frontend/src/app/features/auth/accept-invite/accept-invite.component.spec.ts`.
- [ ] T065 [US4] Playwright `invite-accept.spec` (admin invite → accept → signed in) in `frontend/e2e/invite-accept.spec.ts`.

**Checkpoint**: Invite-only provisioning works independently.

---

## Phase 7: User Story 5 - Sign out and session expiry (Priority: P3)

**Goal**: Sign-out revokes the presenting session only; sessions slide-renew within idle window and hard-expire at the absolute bound.

**Independent Test**: Sign out → old cookie 401; second device still works; activity within idle window renews; past absolute TTL → 401 despite activity.

### Tests for User Story 5 (write first, must fail) ⚠️

- [ ] T066 [P] [US5] `SessionRevocationIntegrationTest` — logout revokes only the presenting jti (other session unaffected); sliding renewal extends within idle window; absolute TTL rejects despite continued activity — Clock-driven — in `backend/src/test/java/com/cadence/auth/SessionRevocationIntegrationTest.java` (FR-014/FR-015; SC-006/SC-010; QA-4/QA-5).

### Implementation for User Story 5

- [X] T067 [US5] Add `POST /api/internal/auth/logout` (CSRF-protected; `SessionService.revokeOne` + clear cookie; audit SIGN_OUT) to `AuthController` in `backend/src/main/java/com/cadence/api/AuthController.java` (FR-015).
- [X] T068 [P] [US5] Add a logout action to `frontend/src/app/core/auth/auth.service.ts` that issues `POST /api/internal/auth/logout` **with the `X-XSRF-TOKEN` header**, clears `me$` auth state, and redirects to `/login`; add a sign-out control in the shell component and a Jasmine spec — in `frontend/src/app/core/auth/auth.service.ts` + shell component (FR-015; FE-7).

**Checkpoint**: All five stories independently functional.

---

## Phase 8: Polish & Cross-Cutting Concerns

**Purpose**: Constitution Definition-of-Done items spanning all stories.

- [ ] T069 [P] `AuthLogPiiScanTest` — Logback `ListAppender` over sign-in/invite/reset flows asserts zero matches for the full FR-022 set (email, JWT, hex token, bcrypt `$2`, OIDC code/state/nonce, secrets) in `backend/src/test/java/com/cadence/auth/AuthLogPiiScanTest.java` (SC-005/QA-10).
- [X] T070 [P] `AuthAtRestTest` — stored `bcryptHash` has `$2` prefix (not plaintext), `tokenHash` ≠ raw token, stored `email` is AES-GCM ciphertext with matching `emailHash` in `backend/src/test/java/com/cadence/auth/AuthAtRestTest.java` (SC-011/QA-11).
- [ ] T071 [P] Add axe-core (WCAG 2.2 AA, 0 violations) + mobile-first (375 px, ≥44 px targets) checks for `login`, `accept-invite`, `reset-request`, `reset-confirm` pages in `frontend/e2e/` or component specs (FE-7/§IX).
- [X] T072 [P] Audit all auth UI strings use `$localize` across `frontend/src/app/features/auth/**/*.html|*.ts` (login/invite/reset components) (§IX).
- [ ] T073 [P] Extend the CI PII log-grep step in `.github/workflows/ci.yml` to scan the **already-produced** test log/report output (cached Gradle binary — do NOT re-invoke a fresh `gradlew` that could trigger a download, §X) for auth token/email patterns (DoD; complements T069; BE-10).
- [ ] T074 Document required Fly secrets (`JWT_SECRET`[+`_PREVIOUS`], `PII_ENC_KEY`, `PII_PEPPER`, `TOKEN_PEPPER`, `IP_PEPPER`, `OIDC_CLIENT_ID`/`OIDC_CLIENT_SECRET`/`OIDC_ISSUER_URI`) in `specs/002-authentication/quickstart.md` + deploy notes. **Add a note that SC-009 (TLS 1.2+) and SC-001 (OIDC-callback p95 < 2 s) are enforced/observed at the Cloudflare/Fly edge and via the BCrypt-strength/Clock budget respectively — consciously infra-deferred, not dropped** (QA-3/QA-4).
- [ ] T075 Define and verify the **production** Cloudflare reverse-proxy route mapping `app-origin/api/*`, `/oauth2/*`, `/login/oauth2/code/*` → the Fly backend (the load-bearing same-origin guarantee from research D10 — not just the local `proxy.conf.json`); record in deploy notes (BE-9).
- [ ] T076 Add `AuthSecretsStartupTest` asserting the application context **fails to start** under the prod profile when a required secret (`JWT_SECRET`, `PII_ENC_KEY`, peppers) is absent, in `backend/src/test/java/com/cadence/auth/AuthSecretsStartupTest.java` (BE-10 fail-fast verification).
- [X] T077 If any `scripts/*.ps1` are changed for this feature, byte-level non-ASCII scan (zero matches) + parse; otherwise record "no scripts changed — static-only" in task notes (§V/C5).
- [ ] T078 Run `quickstart.md` manual verification (steps 1–7) against a local run; record results.

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: no dependencies.
- **Foundational (Phase 2)**: depends on Setup; **blocks all stories**. Within it: enums/domain (T005–T012) → repositories (T013–T017) → crypto/config/infra (T018–T029); T020→T008, T024→T026→T027→T028→T029; frontend T031–T035.
- **US1 (Phase 3)** and **US2 (Phase 4)**: both P1, depend only on Foundational; together = MVP.
- **US3 (Phase 5)**, **US4 (Phase 6)**, **US5 (Phase 7)**: depend only on Foundational; independent of each other.
- **Polish (Phase 8)**: after the stories it verifies are complete.

### Story independence

- US1 issues sessions via the foundational `SessionService`; US2 validates via the same; each is testable alone using the `/me` probe.
- US3, US4, US5 each touch only their own services/endpoints/components — no cross-story file conflicts.

### Within each user story

- Tests authored first and failing (§VII) → services → endpoints → frontend → E2E.

---

## Parallel Execution Examples

**Foundational domain layer (after Setup):**
```
T005 Role · T006 MemberStatus · T007 enums · T008 Member · T009 Session · T010 Invitation · T011 PasswordResetToken · T012 AuthAuditEvent   # all [P]
then T013–T017 repositories   # all [P]
```

**User Story 1 tests (write first, in parallel):**
```
T036 OidcLoginIntegrationTest · T037 OidcValidationTest
```

**User Story 3 services (after its tests fail):**
```
T052 LoginAttemptService · T054 PasswordResetService   # [P]; then T053 AuthenticationService (depends on T052)
```

---

## Implementation Strategy

### MVP (Stories US1 + US2)

1. Phase 1 Setup → 2. Phase 2 Foundational → 3. Phase 3 US1 (SSO) → 4. Phase 4 US2 (gate) → **STOP & VALIDATE**: a member can SSO-sign-in and reach gated internal data; candidate paths stay open. Deploy/demo.

### Incremental delivery

- Add US3 (password + reset) → demo → US4 (invitations) → demo → US5 (sign-out/expiry) → demo. Each is an independently testable increment (constitution §II — no half-wired increments to `main`).

### Definition of Done (per constitution, gated in Phase 8)

- Unit + integration + contract tests green (Testcontainers, no Atlas creds).
- PII log-scan zero matches (T069/T073); at-rest encryption verified (T070).
- Public auth pages: axe-core AA + mobile-first (T071); `$localize` (T072).
- No-script-change recorded or `.ps1` non-ASCII scan = 0 (T077).
- **Multi-role sub-agent review (≥3) at task close** (constitution §VI) — runs after this task list per the user request.
- Deploy: `db-migrate.ps1` (ChangeUnit002) → `deploy-backend.ps1` → `deploy-frontend.ps1`, secrets set first (T074).
