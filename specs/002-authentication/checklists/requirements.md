# Specification Quality Checklist: Authentication & Session Management

**Purpose**: Validate specification completeness and quality before proceeding to planning
**Created**: 2026-06-13
**Feature**: [spec.md](../spec.md)

## Content Quality

- [x] No implementation details (languages, frameworks, APIs)
- [x] Focused on user value and business needs
- [x] Written for non-technical stakeholders
- [x] All mandatory sections completed

## Requirement Completeness

- [x] No [NEEDS CLARIFICATION] markers remain
- [x] Requirements are testable and unambiguous
- [x] Success criteria are measurable
- [x] Success criteria are technology-agnostic (no implementation details)
- [x] All acceptance scenarios are defined
- [x] Edge cases are identified
- [x] Scope is clearly bounded
- [x] Dependencies and assumptions identified

## Feature Readiness

- [x] All functional requirements have clear acceptance criteria
- [x] User scenarios cover primary flows
- [x] Feature meets measurable outcomes defined in Success Criteria
- [x] No implementation details leak into specification

## Notes

- Two scope decisions that lacked a clear default were resolved with the stakeholder before finalisation (recorded in Assumptions): **SSO = OIDC only** (SAML deferred to v1.5) and **provisioning = admin-invite only** (no public self-registration).
- "401" and "HTTP 200" appear in acceptance scenarios/requirements as observable HTTP outcomes (the contract a tester verifies), not as implementation prescriptions — the constitution and backlog already fix HTTP/REST as the interface, so these are treated as testable behaviour rather than leaked implementation detail.
- Calendar OAuth tokens (F01.1) and per-role permission enforcement (F02) are explicitly carved out so this spec stays bounded to identity + session.
- All items pass. Spec is ready for `/speckit.plan` (or `/speckit.clarify` if further refinement is desired).

## Multi-Role Sub-Agent Review (constitution §VI)

**Conducted**: 2026-06-13 | **Roles (4)**: Security/GDPR Lead, Business Analyst, QA Lead, Backend/DevOps Lead | **Outcome**: all four APPROVE-WITH-CHANGES, no blockers.

Findings applied to the spec (and backlog where noted):

- **Security**: OIDC integrity — state/nonce/PKCE + ID-token validation (FR-025); Member PII encrypted at rest (FR-026); invite/reset tokens 128-bit CSPRNG, hashed, single-use, no PII in URL (FR-030); reset rotates credential + revokes sessions (FR-031); rate-limit + enumeration-safe aux endpoints (FR-032); invite cannot take over an existing member (FR-033); expanded no-log set incl. OIDC code/state/nonce + tokens (FR-022); TLS 1.2+ (FR-024); audit survives erasure via non-PII ids (FR-036); session-credential theft posture + signing key as Fly secret (FR-037).
- **Revocation model (Sec/QA/Backend)**: per-request server-side revocation check, no external store (FR-028); sign-out = presenting session, next request (FR-015); deactivation = all sessions, next request (FR-021); SC-010; scenarios US2-5, US3-5/6.
- **Quantified thresholds (QA/BA)**: lockout 5/15min (FR-006); session 8h absolute + 30min sliding idle (FR-014); clock skew ±60s (FR-012).
- **Testability (QA)**: password-reset + concurrent-redeem scenarios (US3-5/6, FR-035, SC-008); deactivation-mid-session scenario (US2-5); IdP-unavailable (FR-034); candidate-path criterion narrowed to "not gated" (SC-003); adversarial tamper suite concretised (SC-012).
- **Backend/topology (Backend)**: index + TTL + SecurityConfig + dependency-policy notes added under "Notes for Planning"; "no external state" assumption; email unique per workspace.
- **Backlog reconciliation (BA)**: backlog F01 annotated (OIDC-only MVP, OAuthTokenStore AC = calendar tokens) and SAML added to the Deferred table; US-4 seeded-admin demo assumption added.

Minor/NIT items judged non-actionable: QA-13 (the FR group correctly cites §VIII Security & Privacy, not §VII); BA-9/FR-level "401" retained intentionally for backlog parity (phrased as observable outcome). No findings were silently discarded.

## Multi-Role Sub-Agent Review #2 — PLAN (constitution §VI)

**Conducted**: 2026-06-13 | **Roles (4)**: Backend/DevOps, Security/GDPR, QA, Frontend | **Outcome**: Backend & Security APPROVE-WITH-CHANGES; **QA & Frontend REJECT** (real design defects). All BLOCKER/MAJOR findings applied in `research.md` (v2), `data-model.md`, `contracts/auth-api.md`, `plan.md`. Re-check: no constitution gate moved to FAIL.

**Headline fixes applied**:
- **Cross-origin cookie reality (FE-1/2/3/4, BE-1, SEC-5)** — Cloudflare-Pages SPA + Fly backend is cross-origin, which broke the `SameSite=Strict` design end-to-end. Resolved by **same-origin Cloudflare reverse-proxy** of `/api`,`/oauth2`,`/login/oauth2/code` + `SameSite=Lax` (research D10; contracts; quickstart). Fallback (`SameSite=None`+CORS) documented.
- **Encryption-at-rest undefined (SEC-1, QA-11)** — SC-011 needs a DB reader to see ciphertext, which Atlas at-rest does not provide. Resolved with **app-level AES-256-GCM + keyed `emailHash` HMAC** for the unique index/lookup (research D12; data-model). No CSFLE/KMS infra; §VIII-compliant.
- **Test strategy (QA-1..10, 12..18)** — added research D11: injectable `Clock`; mock OIDC via `spring-security-test` `oidcLogin()` + WireMock JWKS/token; HS256-pinned decoder + 5-vector `TokenTamperTest`; `CountDownLatch` concurrency harness on atomic `findOneAndUpdate`; `BaseIntegrationTest` singleton + remove-not-drop; `ListAppender` PII scan over full FR-022 set; concrete Playwright E2E; CSRF tests.
- **SecurityConfig @Order collision + OIDC path coverage (BE-2)** — renumbered to actuator@1 / public@2 / main@3; `/oauth2/**` permitted on main (research D7; plan structure).
- **Security hardening** — session fixation invalidation (SEC-4); lockout returns uniform 401 not a 429 oracle (SEC-7); skew applied to JWT `exp` only, not Mongo absolute/revoked checks (SEC-11); throttled renewal write (BE-3/5/SEC-9); HMAC-pepper token hashes (SEC-2); `kid` + current/previous key rotation (SEC-3); HMAC-keyed audit IP (SEC-6); OIDC failure handler (SEC-12/QA-14/FR-034); SSO-invitee link from validated ID token (SEC-10); validate-token-before-password-policy (BE-8).
- **Schema (BE-9, BE-4)** — dropped dead stored `EXPIRED` invitation state (TTL handles expiry); SSO unique index made **partial** (`ssoProvider $exists`).
- **Frontend (FE-5/6/7/8/9/10)** — single `withCredentials` interceptor; public auth routes unguarded + no-redirect-loop; login/invite/reset held to WCAG AA + axe; XSRF cookie/header names pinned to Angular defaults; invite/reset pre-validation + accessible invalid-link state; prod same-origin `apiBaseUrl`.

**Reported as accepted/deferred (not silently dropped)**: SEC-2 peppering applied (upgraded from the reviewer's optional); SEC-8 restart-resets-IP-budget mitigated with a Mongo-backed cooldown for reset/invite (in-proc per-IP retained for login); per-workspace OIDC registration resolution remains an F03 dependency (noted in D2). The two REJECT verdicts were driven by the cross-origin and test-strategy blockers above, all now addressed in the revised design.

## Multi-Role Sub-Agent Review #3 — TASKS (constitution §VI)

**Conducted**: 2026-06-13 | **Roles (4)**: QA, Backend/DevOps, Security/GDPR, Frontend | **Outcome**: **all four APPROVE-WITH-CHANGES — no REJECT, no BLOCKER** (the plan-stage revisions held). 78 tasks across 8 phases. All MAJOR/actionable findings applied to `tasks.md`:

- **Dev same-origin (FE-1/FE-2)** — T004 now sets dev `apiBaseUrl=/api` (relative) so the proxy is used and cookie/XSRF work locally.
- **Sequencing (BE-1/2/3)** — T028 installs `oauth2Login` with placeholder handlers (foundational chain complete; T041 swaps real ones); explicit `depends-on` added to T024/T027/T028; SecurityConfig.java flagged as a serialized file (T028→T041→T048); T008 notes Member-persistence depends on T020.
- **Prod proxy (BE-9)** — new **T075** to define/verify the production Cloudflare reverse-proxy route (D10 is load-bearing, not just local).
- **Verifying-test gaps** — key-rotation case added to T045 (SEC-3); SSO-invite link-from-validated-token added to T060 (SEC-10); reset validate-before-policy + aux-endpoint rate-limit/enumeration/429 added to T051 (BE-8/FR-032); new **T076** `AuthSecretsStartupTest` for fail-fast on missing secrets (BE-10); contract coverage broadened beyond `/me` in T046 (QA-1).
- **Frontend clarifications (FE-3/4/5/7)** — interceptor redirects on 401 only (not 410); public auth routes are top-level siblings of the guarded shell (no loop); reset-request is a standalone page; logout sends CSRF header + clears state + redirects.
- **Hygiene (BE-5/6/8/10)** — T030 remove-not-drop reminder; T052 in-process-only/no-Redis guard; T002 pins one WireMock coordinate; T073 scans produced output (no fresh `gradlew`).

**Consciously deferred (reported, T074)**: SC-009 (TLS 1.2+) and SC-001 (OIDC p95 < 2 s) are enforced/observed at the Cloudflare/Fly edge and via BCrypt-strength/Clock budget — infra-deferred, not dropped. No findings silently discarded; no constitution gate at risk.

**Spec-Kit pipeline status**: spec → plan → tasks complete, each gated by a §VI multi-role review with findings applied. Ready for `/speckit.implement` (or manual execution), which will close with a final §VI review per the constitution DoD.

## Multi-Role Sub-Agent Review #4 — IMPLEMENTATION (constitution §VI), 2 loops

**Conducted**: 2026-06-14 | **Roles (4)**: Security/GDPR, Backend/Spring, QA, Frontend, reviewing the actual code (integration tests could not run here — Docker/Testcontainers launch fails on this machine, confirmed pre-existing as the scaffold's own `IndexBootstrapTest` fails identically; both main + test sources compile via cached Gradle 9.4.0).

**Loop 1 — 2 REJECT + 2 APPROVE-WITH-CHANGES.** Real defects found (the review substituted for the un-runnable tests):
- **BE-1 (BLOCKER)** — password-only members persisted `ssoProvider:null`; the partial unique index `{$exists:true}` matches null → 2nd password member collides, breaking invitation acceptance. FIX: `@Field(write=NON_NULL)` omits the fields for password members.
- **SEC-1 (BLOCKER)** — JWT `kid` key-selection inconsistency. FIX: verify current-then-previous (2-key rotation set), kid informational, HS256 still pinned.
- **SEC-7 (MAJOR)** — reset-confirm 500/stack-trace if member erased. FIX: `findByIdOptional → InvalidLink`.
- **SEC-6 (MAJOR)** — weak-password-before-consume = token-validity oracle. FIX: password-policy checked FIRST in reset + invite (more enumeration-safe; supersedes the earlier BE-8 ordering note).
- **SEC-8 (MINOR)** — expiry race in single-use CAS. FIX: `expiresAt > now` added to the atomic `findAndModify` query.
- **SEC-10 (MAJOR)** — lockout lost-update under concurrency. FIX: atomic `$inc` + conditional `lockedUntil`.
- **FE-1** — redundant `me()` in shell. FIX: removed (guard already populates `member$`).

**Loop 2 — focused re-review: all 6 BLOCKER/MAJOR findings RESOLVED, no new blocking defects.** Both REJECTs cleared.

**Accepted/deferred (reported, not silently dropped)**:
- SEC-4 (wrong-password vs unknown path differ by one Mongo write) — accepted MVP risk; the timing delta is small beside BCrypt cost-12. Tracked for hardening.
- `recordSuccess` non-atomic save — pre-existing, benign (success resets the counter anyway).
- FE-2 (interceptor/guard double-redirect on `/me` 401) — minor quality; left.
- **QA coverage gaps = the still-open tasks** (honestly unchecked in tasks.md): US1 SSO tests (T036/T037/T043), token-tamper suite (T045), contract test (T046), sign-out/renewal tests (T066), PII-log-scan (T069), frontend Jasmine/E2E specs (T058/T059/T064/T065), axe/i18n polish (T071), CI/docs (T073/T074/T075), startup fail-fast test (T076), quickstart run (T078). The implementing code for these paths exists and compiles; the **verifying tests** remain to be written and run in a Docker-capable environment.

**Verification status**: backend `./gradlew test` **GREEN — 63 tests, 0 failures, 0 errors** (run 2026-06-14 against Testcontainers `mongo:7`; cached Gradle 9.4.0, §X honoured). Covers all 6 new auth integration classes (SessionGate, PasswordSignIn, PasswordReset, Invitation, AtRest, IndexBootstrap) + the 5 pre-existing scaffold tests.

Three additional defects were found **only by running the suite** (the earlier reviews + compile could not catch them) and fixed:
- **OIDC `issuer-uri` eager discovery** — `spring-boot-starter-oauth2-client` with `issuer-uri` fetched the IdP discovery doc at startup; with no Keycloak reachable the whole ApplicationContext failed → every `@SpringBootTest` (and `bootRun`) broke. FIX: explicit provider endpoints (authorization/token/jwk-set/user-info URIs) — no startup network call.
- **Double `@Primary` Clock** (QA-1, confirmed live) — `AuthTestConfig` declared both a `MutableClock` and a `Clock` `@Primary` bean (both assignable to Clock) → `NoUniqueBeanDefinitionException`. FIX: a single `@Primary MutableClock` bean (prod `ClockConfig` backs off via `@ConditionalOnMissingBean`).
- **Actuator 401-vs-403 regression** — the global `HttpStatusEntryPoint(401)` on the main chain changed the F00 actuator-on-public-port contract (`ActuatorPortTest` expected 403). FIX: scoped the 401 entry point to `/api/**` (FR-010) and kept `Http403ForbiddenEntryPoint` as the default, preserving the scaffold contract.

`ng test` (frontend) still to be run by the user; note the auth component `*.spec.ts` files (T058/T064) are not yet written, so it currently only confirms the new components compile under the Angular build.
