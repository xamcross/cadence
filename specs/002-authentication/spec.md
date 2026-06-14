# Feature Specification: Authentication & Session Management

**Feature Branch**: `002-authentication`
**Created**: 2026-06-13
**Status**: Draft
**Input**: User description: "take the first non-implemented feature from the backlog. honour the constitution" → resolved to **F01 — Authentication & Session Management** (first non-implemented item after the F00 scaffold in the delivery sequence).

## Overview

Cadence needs a way to know **who** is using the workspace-facing application and to keep that identity attached to every request, while keeping the candidate-facing surface completely open (no login, no account). This feature establishes the workspace member sign-in experience and the per-request session that protects internal data.

Two sign-in paths exist for workspace members:

1. **Single sign-on (SSO)** via the organisation's identity provider — the **primary** and default path.
2. **Email + password** — a fallback for workspaces that have not configured SSO.

Candidates never authenticate: every candidate-facing action (slot selection, rescheduling, status page, feedback) continues to work through private per-candidate links, which are out of scope here and covered by their own features.

This feature delivers **identity and session only**. Fine-grained per-role permission rules (what each role may do) are delivered separately by F02 (RBAC); F01 establishes the identity and the role attached to it so F02 can enforce against it.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Sign in with company SSO (Priority: P1)

A workspace member opens Cadence and signs in using their organisation's identity provider (e.g. Google Workspace, Microsoft Entra ID, Okta) so that their corporate identity is the source of truth and they manage one fewer password.

**Why this priority**: SSO is the constitution-mandated primary authentication path and the expected norm for the target B2B buyer. Without it, the product cannot be sold into security-conscious organisations. It is the most-used sign-in path in production.

**Independent Test**: Configure a test OIDC identity provider, click "Sign in with SSO", complete the identity-provider login, and confirm the member lands on the authenticated dashboard with an active session. Fully demonstrable end to end on its own.

**Acceptance Scenarios**:

1. **Given** a workspace with SSO configured and a member who exists in the identity provider, **When** the member chooses "Sign in with SSO" and completes the identity-provider login, **Then** they are returned to Cadence with an active authenticated session and can load the dashboard.
2. **Given** the SSO option is presented on the sign-in screen, **When** the member views the screen, **Then** SSO is the visually primary/default action and email+password is a secondary option (never the default).
3. **Given** a person authenticates successfully at the identity provider but has no matching workspace member record, **When** they are returned to Cadence, **Then** access is denied with a clear "you don't have access to this workspace — contact your administrator" message and no session is created.
4. **Given** a member completes SSO sign-in, **When** their identity is established, **Then** the role recorded for that member (Admin, Recruiter, Hiring Manager, Interviewer, or Read-only) is attached to the session so downstream permission checks can use it.

---

### User Story 2 - Protected access to internal data (Priority: P1)

Every internal/workspace request carries the member's identity, and any request to an internal endpoint without a valid session is rejected, so that candidate and pipeline data is never exposed to unauthenticated callers.

**Why this priority**: Authentication has no value unless it actually gates access. This is the core security guarantee of the feature and a prerequisite for every other workspace feature. It is P1 alongside Story 1 because sign-in and gate enforcement are only meaningful together.

**Independent Test**: Call an internal endpoint with no session and observe a rejection; call the same endpoint with a valid session and observe success. Verifiable with automated API tests without any UI.

**Acceptance Scenarios**:

1. **Given** no valid session, **When** a request is made to any internal/workspace endpoint, **Then** the system responds with "unauthenticated" (HTTP 401) and returns no data.
2. **Given** a valid session, **When** a request is made to an internal endpoint the member is allowed to reach, **Then** the request is processed normally.
3. **Given** a candidate-facing endpoint, **When** it is called with no member session at all, **Then** it is **not** subject to the member-authentication gate (the request reaches its own handler rather than being rejected with 401), because candidate paths are an explicit public allow-list (verified without invoking the candidate private-link logic, which is out of scope here).
4. **Given** an expired or tampered session token, **When** it is presented to an internal endpoint, **Then** the request is rejected as unauthenticated and no data is returned.
5. **Given** a member who was deactivated by an Administrator while holding an active session, **When** their next request reaches an internal endpoint, **Then** it is rejected as unauthenticated (deactivation takes effect on the next request per FR-021/FR-028).

---

### User Story 3 - Email + password fallback sign-in (Priority: P2)

A member of a workspace that has not configured SSO signs in with an email address and password so that early-adopter workspaces are not blocked on identity-provider setup.

**Why this priority**: SSO is primary, but the MVP must be usable by workspaces before SSO is wired up. It is P2 because Story 1 already unblocks the SSO-ready majority; this path serves the long tail and is the explicit "fallback only" path in the constitution.

**Independent Test**: Provision a member account with a password, sign in with the correct credentials (success), then with a wrong password (rejected), then exceed the attempt limit (temporarily locked). All verifiable independently of SSO.

**Acceptance Scenarios**:

1. **Given** a member account with a set password, **When** they submit the correct email and password, **Then** they receive an active authenticated session.
2. **Given** a member account, **When** an incorrect password is submitted, **Then** sign-in fails with a generic "invalid email or password" message that does not reveal whether the email exists.
3. **Given** repeated failed sign-in attempts for the same account or source beyond a configured threshold, **When** another attempt is made within the lockout window, **Then** the attempt is rejected as rate-limited and the legitimate user can still recover after the window.
4. **Given** the sign-in screen, **When** it renders, **Then** the email+password form is presented as the secondary/fallback option, not the default.
5. **Given** a fallback member who has forgotten their password, **When** they request a reset and follow the single-use, time-limited link to set a new password, **Then** the new password works, the old password no longer works, and their existing sessions are revoked; the reset-request response is identical whether or not the email is a known account.
6. **Given** a reset (or invitation) link that has already been used, **When** a second redemption is attempted concurrently or afterward, **Then** exactly one redemption succeeds and the other is refused.

---

### User Story 4 - Account provisioning by invitation (Priority: P2)

An Administrator invites a new workspace member by email; the invitee follows a one-time link to set a password (for the fallback path) or to be linked to their SSO identity, so that member accounts exist without any public self-registration.

**Why this priority**: Accounts must come from somewhere. Invitation is the only provisioning path for the MVP (no open self-registration), keeping the attack surface small. It is P2 because the first Administrator is bootstrapped during workspace setup (F03) and the email+password and SSO paths can be demonstrated with seeded accounts first.

**Independent Test**: As an Administrator, send an invite to a new email; follow the invite link; complete account setup; sign in as the new member. The invite link cannot be reused or used after expiry.

**Acceptance Scenarios**:

1. **Given** an Administrator, **When** they invite a person by email, **Then** an invitation is recorded and an invitation email is sent containing a single-use, time-limited link.
2. **Given** a valid, unexpired invitation link, **When** the invitee completes account setup, **Then** a member account is created with the role the Administrator assigned and the invitation is consumed.
3. **Given** an invitation link that has already been used or has expired, **When** it is opened, **Then** the system shows a clear "this invitation is no longer valid — ask your administrator to resend" message and does not create an account.
4. **Given** no valid invitation, **When** a person attempts to register directly, **Then** no account is created (there is no public self-registration path).

---

### User Story 5 - Sign out and session expiry (Priority: P3)

A signed-in member can sign out, and an idle or long-lived session expires on its own, so that an unattended or shared device does not leave the workspace exposed.

**Why this priority**: Session termination is necessary for a complete, trustworthy authentication story but is not on the critical path to the first demo. P3 polish that the Definition of Done still requires.

**Independent Test**: Sign in, sign out, then confirm the prior session can no longer reach internal endpoints. Separately, let a session pass its lifetime and confirm it is rejected thereafter.

**Acceptance Scenarios**:

1. **Given** an active session, **When** the member signs out, **Then** the session is ended and subsequent requests with the old session are rejected as unauthenticated.
2. **Given** a session that has reached its maximum lifetime, **When** the member makes a request afterward, **Then** the request is rejected and the member is prompted to sign in again.
3. **Given** an active session that is still valid but near expiry, **When** the member continues working, **Then** the session is renewed/continued without forcing an interruptive re-login (within the maximum lifetime bound).

---

### Edge Cases

- **Member exists but is deactivated**: A previously valid member whose access was revoked attempts SSO or password sign-in → sign-in is refused with an access-denied message; no session is created.
- **SSO succeeds, no workspace match**: Authentication at the identity provider succeeds but the email maps to no member → access denied, no auto-provisioning (provisioning is invite-only).
- **Identity provider unavailable**: The configured identity provider is unreachable or returns an error during the redirect → the member sees a clear "sign-in is temporarily unavailable, try again" message rather than a stack trace or blank page; the email+password fallback remains available if configured.
- **Clock skew / token timing**: A session token presented slightly before/after a boundary is handled by a small tolerance so legitimate users are not spuriously rejected, while genuinely expired tokens are still refused.
- **Concurrent sessions**: The same member signed in on two devices — both sessions remain valid until each expires or is signed out independently.
- **Password reset for fallback users**: A fallback user who has forgotten their password can request a reset via a single-use, time-limited link (no account enumeration in the response).
- **Replayed/forged token**: A tampered or copied session token from another member is rejected; the token cannot be altered to impersonate a different identity or role.
- **Invite to an email that is already a member**: Re-inviting an existing member does not create a duplicate account.
- **Candidate link never grants member access**: A candidate's private link can never be escalated into a workspace member session.

## Requirements *(mandatory)*

### Functional Requirements

#### Sign-in & identity

- **FR-001**: System MUST allow a workspace member to sign in via the workspace's configured SSO identity provider using the OpenID Connect (OIDC) protocol.
- **FR-002**: System MUST present SSO as the primary, default sign-in action and email+password as a clearly secondary fallback.
- **FR-003**: System MUST allow a workspace member to sign in with email and password when that workspace permits the fallback path.
- **FR-004**: System MUST verify passwords against a securely hashed, salted stored value and MUST NOT store or be able to recover plaintext passwords.
- **FR-005**: System MUST return a single generic failure message for failed email+password sign-in that does not disclose whether the email address exists, with timing/response behaviour uniform across "unknown account", "wrong password", and "locked" so none reveals account existence.
- **FR-006**: System MUST rate-limit / lock repeated failed sign-in attempts (per account and per source). Default: lock after **5** failed attempts within a **15-minute** window; lock auto-releases after the window (configurable per workspace). A legitimate user MUST be able to recover after the window without administrator intervention.
- **FR-007**: System MUST deny sign-in (no session created) when an authenticated identity-provider user has no matching active member record, with a clear access-denied message and no auto-provisioning.
- **FR-008**: System MUST attach the member's identity and assigned role (Admin, Recruiter, Hiring Manager, Interviewer, Read-only) to the established session so downstream permission enforcement (F02) can use it.
- **FR-025**: System MUST protect the OIDC sign-in exchange against replay/CSRF using a `state` value and a `nonce` (and PKCE), and MUST validate the returned identity token's signature, issuer, audience, and expiry before establishing any session.
- **FR-026**: System MUST store Member personal-data fields (email address, display name) using the workspace encryption-at-rest configuration (CSFLE or server-side encryption), consistent with the project's PII-at-rest standard (backlog ISSUE-8).

#### Session & request protection

- **FR-009**: System MUST issue a session credential on successful sign-in that is presented on subsequent requests to identify the member.
- **FR-010**: System MUST reject any request to an internal/workspace endpoint that lacks a valid session, responding as unauthenticated (HTTP 401) and returning no protected data.
- **FR-011**: System MUST treat candidate-facing endpoints as explicitly public (no member session required); these endpoints rely on their own private per-candidate link, not a member session.
- **FR-012**: System MUST reject expired, malformed, or tampered session credentials as unauthenticated, applying a small fixed clock-skew tolerance (default **±60 seconds**) on expiry/not-before boundaries so legitimate users are not spuriously rejected while genuinely expired credentials are still refused.
- **FR-013**: System MUST validate the integrity of the session credential so that it cannot be altered to change the member identity or role it represents.
- **FR-014**: System MUST enforce an **absolute** maximum session lifetime (default **8 hours**) after which re-authentication is required, and MUST renew an active session on continued use within an **idle window** (default **30 minutes**, sliding) up to that absolute bound without an interruptive re-login. Both values are configurable per workspace.
- **FR-015**: System MUST allow a signed-in member to sign out; the **presenting** session MUST be revoked and rejected as unauthenticated on its **next request** (other concurrent sessions of the same member are unaffected).
- **FR-028**: System MUST be able to revoke an active session credential before its natural expiry. Revocation MUST be enforced by a per-request server-side check (member active-status plus a session/token version or identifier persisted in MongoDB) so that sign-out (FR-015) and deactivation (FR-021) both take effect on the member's next request — without introducing any external session store, cache, or broker (single-instance + MongoDB only).
- **FR-029**: System MUST treat the public candidate surface as an explicit, positively-declared allow-list of candidate-facing paths that bypass the member-authentication gate; internal endpoints are protected by default (deny-by-default), so adding a new internal endpoint cannot accidentally become public.

#### Provisioning (invite-only)

- **FR-016**: System MUST allow an Administrator to invite a new member by email address with an assigned role.
- **FR-017**: System MUST deliver invitations as single-use, time-limited links and MUST NOT provide any public self-registration path.
- **FR-018**: System MUST allow an invitee with a valid, unexpired invitation to complete account setup (set a password and/or link their SSO identity) which creates the member account and consumes the invitation.
- **FR-019**: System MUST reject used or expired invitation links with a clear, non-technical message and create no account.
- **FR-020**: System MUST allow a fallback (email+password) member to reset a forgotten password via a single-use, time-limited link without disclosing whether an account exists.
- **FR-021**: System MUST allow an Administrator to deactivate a member, after which that member can no longer sign in and **all** of that member's existing sessions are revoked, effective on each session's **next request** (per FR-028). *(The deactivation action/UI surface is owned by F02/F03; F01 owns only the sign-in refusal and session-revocation effect.)*
- **FR-030**: System MUST generate invitation and password-reset link tokens as cryptographically random values of at least 128 bits, store them **hashed** at rest, place no email or other PII in the link URL, and make each link single-use and time-limited (carrying backlog ISSUE-9 forward to these private links).
- **FR-031**: On successful password reset, System MUST rotate the credential (the previous password no longer works) and revoke the member's existing sessions.
- **FR-032**: System MUST rate-limit the invitation-acceptance, password-reset-request, and token-validation endpoints (per source IP and, where applicable, per account), and these responses MUST be enumeration-safe (uniform regardless of whether the target account/invitation exists).
- **FR-033**: System MUST ensure an invitation can only provision a non-existent / non-active member; it MUST NOT mutate an existing active member's credential, role, or SSO identity link. Re-inviting an existing active member MUST NOT create a duplicate account.
- **FR-034**: When the configured identity provider is unreachable or returns an error during sign-in, System MUST present a clear non-technical error (no stack trace or blank page) and MUST keep the email+password fallback reachable where the workspace permits it.
- **FR-035**: Concurrent redemption of a single-use invitation or password-reset link MUST result in exactly one success; all other concurrent or subsequent redemptions MUST be refused.

#### Privacy, logging & compliance (constitution §VIII)

- **FR-022**: System MUST NOT write any plaintext personal data (email address, member name) or any credential/secret material to application logs at any level; only internal anonymised identifiers may be logged. The prohibited set explicitly includes: passwords, password hashes, session tokens, identity-provider tokens, OIDC authorization codes, OIDC `state`/`nonce` values, invitation tokens, password-reset tokens, and client secrets.
- **FR-023**: System MUST record an audit entry for security-relevant authentication events (successful sign-in, failed sign-in, sign-out, invitation issued/consumed, member deactivation, password reset) using non-PII identifiers.
- **FR-024**: System MUST transmit all authentication exchanges (sign-in, token presentation, invitation, password reset) over encrypted transport only (**TLS 1.2 or higher**, per constitution §VIII).
- **FR-036**: The authentication audit log (FR-023) MUST reference members only by non-PII internal identifier so that it can be retained for security purposes without conflicting with a member's right-to-erasure of their PII fields.
- **FR-037**: System MUST deliver the session credential in a manner safe against theft: if cookie-based, the cookie MUST be `HttpOnly`, `Secure`, and `SameSite`-restricted with CSRF protection on state-changing requests; if a bearer token, the spec/plan MUST state the XSS-exposure mitigation. The session-signing key MUST be supplied as a Fly.io secret, never committed.

### Key Entities *(include if feature involves data)*

- **Member (workspace user)**: A person who can sign into a workspace. Key attributes: internal identifier, the workspace they belong to, email address (PII), display name (PII), assigned role, account status (active/deactivated), and the means of sign-in available to them (SSO-linked identity and/or password credential). Distinct from a Candidate, who never has a member account.
- **Password credential**: The securely hashed+salted secret for a fallback member. Never stored or recoverable in plaintext. Associated with exactly one Member.
- **SSO identity link**: The association between a Member and their stable identifier at the identity provider, used to match a returning SSO user to their member record.
- **Session**: The authenticated context for a signed-in Member, bounded by a maximum lifetime, carrying the member identity and role, and revocable by sign-out or deactivation.
- **Invitation**: A pending, single-use, time-limited grant created by an Administrator to provision a new Member with a specified role; transitions to consumed or expired.
- **Authentication audit event**: An append-only record of a security-relevant authentication action, referencing only non-PII identifiers.

> Note: Per-user OAuth tokens for **calendar** providers (Google/Microsoft free-busy) are a separate concern owned by F01.1 (OAuth Token Store) and are NOT part of this feature. F01's "session" is the workspace member's application session, not a calendar grant.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: System-side processing of the OIDC callback through to an authenticated session (the portion Cadence controls, excluding the identity provider's own screens and network round-trips) completes in under **2 seconds at p95**.
- **SC-002**: 100% of requests to internal endpoints without a valid session are refused with no protected data returned (verified across every internal endpoint).
- **SC-003**: 100% of candidate-facing allow-list paths are not subject to the member-authentication gate (reach their own handler rather than a 401), verified across every declared candidate path without invoking candidate private-link logic.
- **SC-004**: After the configured number of failed sign-in attempts, further attempts within the lockout window are blocked 100% of the time, and a legitimate user regains access after the window without administrator intervention.
- **SC-005**: Zero occurrences of plaintext personal data or credential material (passwords, hashes, session tokens, identity-provider tokens) in application logs across the full sign-in, invitation, and password-reset flows (verified by automated log scan).
- **SC-006**: A signed-out or expired session is rejected on its next internal request 100% of the time.
- **SC-007**: A tampered or another member's session credential never grants access or alters the represented identity/role (0 successful impersonations across adversarial tests).
- **SC-008**: An invitation or password-reset link works exactly once and never after expiry (0 successful reuses or post-expiry uses), including under concurrent redemption (exactly one of N simultaneous redemptions succeeds).
- **SC-009**: 100% of authentication exchanges occur over encrypted transport, TLS 1.2+ (no plaintext-transport path exists).
- **SC-010**: A signed-out session and a deactivated member's sessions are rejected on their next internal request 100% of the time.
- **SC-011**: Reading the stored Member document and credential/token records directly shows encrypted PII and hashed (never plaintext) passwords and invite/reset tokens, in 100% of records.
- **SC-012**: The adversarial token-tamper test suite (modified role/identity claim, stripped or forged signature, "none" algorithm, another member's token replayed) yields 0 successful accesses or privilege changes (concretises SC-007's claim).

## Assumptions

- **SSO protocol scope**: MVP implements **OIDC only**; SAML 2.0 is deferred to v1.5. OIDC satisfies the constitution's "SSO is the primary authentication path" requirement and matches the only SSO acceptance test in the backlog (Keycloak/OIDC). Most target identity providers (Google Workspace, Microsoft Entra ID, Okta) support OIDC. *(Confirmed with stakeholder, 2026-06-13.)*
- **Account provisioning model**: **Admin-invite only** — there is no public self-registration. The first Administrator of a workspace is bootstrapped as part of workspace setup (F03). *(Confirmed with stakeholder, 2026-06-13.)*
- **Authentication != authorization**: F01 establishes identity, session, and the role attached to the session. Endpoint-level permission rules per role (who may do what) are owned by F02 (RBAC). F01 only enforces the coarse gate: authenticated vs unauthenticated, and public candidate endpoints vs protected internal endpoints.
- **Calendar OAuth is out of scope**: Per-user Google/Microsoft calendar tokens are owned by F01.1, not this feature.
- **MFA**: Multi-factor authentication for the email+password fallback is out of scope for MVP; SSO members inherit whatever MFA their identity provider enforces. (Deferring MFA on the fallback path is acceptable because the fallback is explicitly secondary and rate-limited.)
- **Single-instance topology**: Session handling must work on a single application instance with MongoDB as the only datastore (constitution §IV) — no separate session cache/broker is introduced.
- **Reasonable session lifetime defaults**: Session maximum lifetime and lockout thresholds use industry-standard defaults (and are configurable per workspace where the workspace-config feature F03 exposes them); exact numeric values are an implementation/plan decision, not a scope question.
- **Candidate links unchanged**: The private per-candidate link mechanism (token generation, TTL, rate-limiting) is defined by the candidate-facing features (F13/F14/F30/F32) and is only referenced here as the reason candidate endpoints are public.
- **US-4 standalone demo**: For F01's standalone end-to-end demo (constitution §II, no stubs), invitation provisioning is exercised against a **seeded Administrator**; the production first-Admin bootstrap is delivered by F03. This keeps US-4 at P2 while remaining demonstrable.
- **Backlog "OAuthTokenStore" AC**: The F01 backlog acceptance criterion "Token refresh is handled by the OAuthTokenStore (see F01.1)" refers to **calendar** OAuth tokens (F01.1), not the member application session. It is therefore deliberately out of scope for F01, not a dropped requirement.
- **No external state for sessions or rate-limiting**: Session revocation state and lockout/rate-limit counters live in MongoDB (durable, per-account) and/or in-process on the single instance (per-IP); no Redis/cache/broker is introduced (constitution §IV / gate C2).

## Notes for Planning (backend / topology — to be confirmed in plan.md)

These are flagged by the multi-role review so the plan's Constitution Check passes cleanly; exact mechanisms belong in `plan.md`, not this spec.

- **MongoDB indexes (F00.1 pattern)** the plan MUST declare: `members { workspaceId: 1, email: 1 }` (**unique** — email is unique *per workspace*); the SSO identity link by `{ provider, subject }` (**unique**); `invitations { tokenHash: 1 }` (**unique**); `passwordResets { tokenHash: 1 }` (**unique**); and a member-keyed index for auth audit events.
- **TTL indexes**: invitations, password-reset tokens, and lockout counters SHOULD use MongoDB TTL indexes so expiry/cleanup needs no scheduler or broker.
- **SecurityConfig interaction**: the existing two-chain `SecurityConfig` (actuator `@Order(1)` permitAll + main `authenticated()`) must gain an ordered `securityMatcher("/api/candidate/**").permitAll()` chain ahead of the authenticated chain (FR-029/FR-011), and the main chain becomes an OIDC client + self-issued-JWT resource server.
- **Auth mechanism / dependency policy (C4)**: prefer Spring Security's built-in OAuth2/OIDC client and resource-server support; any third-party JWT/crypto library MUST be recorded with a one-line justification in `plan.md` (Dependency Policy).
- **Audit collection**: decide whether auth audit reuses the scaffold's candidate-keyed `auditLog` collection (needs a new member-keyed index) or a separate collection; declare the index either way.

## Dependencies

- **F00 / F00.1 / F00.2 (scaffold)**: complete — provides the Spring Security filter-chain skeleton (currently `authenticated()` placeholder), MongoDB index bootstrap, structured no-PII logging, and the audit/observability baseline this feature extends.
- **F03 (Workspace Setup)**: provides per-workspace SSO configuration and the bootstrap of the first Administrator. F01 can be developed against seeded configuration first; the SSO-config UI and first-admin bootstrap close the loop.
- **F02 (RBAC)**: consumes the identity+role this feature attaches to the session. F01 must land first.

## Constitution Alignment (informational)

- **C1 — MVP scope**: In scope. F01 is an MVP backlog item (Tier 0 Foundation).
- **C2 — no new service/queue/replica**: Satisfied. Stateless session on the single instance + MongoDB only; no broker, no cache tier.
- **C3 — candidate PII exposure**: Strengthened. Internal data is gated behind authentication; candidate endpoints stay public by private link only.
- **C4 — fixed stack**: Satisfied. Uses the existing Spring Security stack; any auth/JWT library addition is recorded with justification in `plan.md`.
- **C5 — script encoding**: Any new/changed `.ps1`/`.cmd`/`.bat` will be byte-scanned for non-ASCII before done.
- **C6 — multi-role sub-agent review**: At least three role perspectives (Security/GDPR, Backend, QA) will review before task close.
- **C7 — zero tool downloads**: No build tool/runtime/CLI will be downloaded; highest already-installed versions used.
- **§VIII Security & Privacy**: SSO primary (FR-002), no PII/credentials in logs (FR-022), erasure-friendly identifiers in audit (FR-023), TLS-only (FR-024).
