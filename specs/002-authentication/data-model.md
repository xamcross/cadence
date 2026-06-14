# Phase 1 Data Model: Authentication & Session Management

**Feature**: 002-authentication | **Date**: 2026-06-13

MongoDB collections introduced/modified by F01. All POJOs are `@Document` classes under `com.cadence.domain` (constitution §Code Style). PII fields are marked **[PII]** and are subject to encryption-at-rest (FR-026) and the no-log rule (FR-022).

---

## Collection: `members`

The workspace user who can sign in. Distinct from a Candidate.

| Field | Type | Notes |
|---|---|---|
| `id` | String (ObjectId) | `@Id` |
| `workspaceId` | String | tenant scope; part of unique key |
| `email` | String **[PII]** | stored as **AES-256-GCM ciphertext** (D12); lowercased before encryption |
| `emailHash` | String | **HMAC-SHA-256(email, PII_PEPPER)** — drives the unique index + all equality lookups (D12); not reversible |
| `displayName` | String **[PII]** | stored as **AES-256-GCM ciphertext** (D12) |
| `role` | `Role` enum | ADMIN / RECRUITER / HIRING_MANAGER / INTERVIEWER / READ_ONLY |
| `status` | `MemberStatus` enum | ACTIVE / DEACTIVATED (FR-021) |
| `passwordCredential` | `PasswordCredential` (embedded, nullable) | present only for fallback members |
| `ssoIdentity` | `SsoIdentity` (embedded, nullable) | present only for SSO-linked members |
| `ssoProvider` | String (nullable, **absent** when password-only) | denormalised for the partial unique index |
| `ssoSubject` | String (nullable, **absent** when password-only) | denormalised for the partial unique index |
| `failedLoginCount` | int | lockout counter (FR-006) |
| `lockedUntil` | Instant (nullable) | lockout release time |
| `createdAt` / `updatedAt` | Instant | |

**Embedded `PasswordCredential`**: `{ bcryptHash: String }` — BCrypt strength 12, never plaintext, never logged (FR-004/FR-022).
**Embedded `SsoIdentity`**: `{ provider: String, subject: String, linkedAt: Instant }` — `provider`/`subject` come from a **validated ID token** at link time (SEC-10), mirrored to the top-level `ssoProvider`/`ssoSubject` fields for indexing.

**Indexes**: `{ workspaceId:1, emailHash:1 }` unique; `{ ssoProvider:1, ssoSubject:1 }` unique **partial** (`{ ssoProvider: { $exists: true } }` — password-only members omit both fields entirely, BE-4).

**Erasure (F04 interplay)**: erasure overwrites `email`/`displayName` ciphertext and `emailHash` with `[ERASED]` markers; the member-keyed `authAuditLog` (non-PII) survives (FR-036).

**Validation / rules**:
- Email unique within a workspace (DuplicateKey → handled as "already a member", FR-033).
- A member has at least one sign-in means (password and/or SSO) once provisioned.
- `DEACTIVATED` members cannot sign in (FR-007 edge) and all their sessions are revoked (FR-021).

**State transitions (status)**: `ACTIVE → DEACTIVATED` (admin action, owned by F02/F03; revokes all sessions). Reactivation is out of F01 scope.

---

## Collection: `sessions`

The revocable session registry backing the cookie JWT (research D1).

| Field | Type | Notes |
|---|---|---|
| `id` | String | = JWT `jti` (UUID); primary-key lookup |
| `memberId` | String | owner; indexed for revoke-all |
| `workspaceId` | String | |
| `role` | `Role` | snapshot at issue time |
| `createdAt` | Instant | absolute-lifetime anchor |
| `lastSeenAt` | Instant | sliding-idle anchor (updated on renew) |
| `absoluteExpiresAt` | Instant | `createdAt + absolute-ttl` (default 8 h); **TTL index** |
| `idleExpiresAt` | Instant | `lastSeenAt + idle-ttl` (default 30 min) |
| `revoked` | boolean | set true on sign-out / deactivation / password reset |

**Indexes**: `_id` (jti, implicit); `{ memberId:1 }`; `{ absoluteExpiresAt:1 }` TTL `expireAfterSeconds:0`.

**Validation / rules**:
- A request is authenticated iff: JWT signature + `exp`/`nbf` valid (**±60 s skew applied only here**) → session exists → `revoked == false` → `now <= absoluteExpiresAt` (**exact now, no skew**) → `now <= idleExpiresAt` (**exact now**) → owning member is `ACTIVE` (SEC-11).
- **Renewal is throttled**: `lastSeenAt`/`idleExpiresAt` are rewritten only when >1/3 of the idle window has elapsed since `lastSeenAt`, so most authenticated requests perform a read-only registry check, not a write (BE-3/BE-5). Renewal runs in the per-request `SessionCookieAuthFilter` (all internal endpoints), not only on `/me` (SEC-9).
- **Sign-out** (FR-015): set `revoked=true` for the presenting `jti` only.
- **Deactivation** (FR-021): set `revoked=true` for all sessions where `memberId == X`.
- **Password reset** (FR-031): revoke all of the member's sessions.
- TTL index purges expired rows with no scheduler (constitution §IV).

**State transitions**: `ACTIVE (revoked=false) → REVOKED (revoked=true)` or → auto-deleted by TTL.

---

## Collection: `invitations`

Admin-created, single-use, time-limited provisioning grant (FR-016–FR-019, FR-033, FR-035).

| Field | Type | Notes |
|---|---|---|
| `id` | String | `@Id` |
| `workspaceId` | String | |
| `email` | String **[PII]** | invited address (lowercased) |
| `role` | `Role` | role to assign on acceptance |
| `tokenHash` | String | **HMAC-SHA-256(token, TOKEN_PEPPER)** of the 256-bit link token; unique (SEC-2) |
| `status` | `InvitationStatus` enum | PENDING / CONSUMED |
| `invitedByMemberId` | String | admin who issued it |
| `createdAt` | Instant | |
| `expiresAt` | Instant | default +72 h; **TTL index** |
| `consumedAt` | Instant (nullable) | |

**Indexes**: `{ tokenHash:1 }` unique; `{ expiresAt:1 }` TTL `expireAfterSeconds:0`.

**Validation / rules**:
- Accept is an atomic `findOneAndUpdate({tokenHash, status:PENDING} → status:CONSUMED)` — exactly one concurrent winner (FR-035).
- Cannot target an existing ACTIVE member (FR-033): pre-check + unique member index guards takeover; a re-invite of an active member leaves that member's role/credential/SSO link byte-for-byte unchanged.
- Token validity is checked **before** any password-policy validation (BE-8).
- Expired/used/unknown → uniform friendly "link invalid" message, no account (FR-019). There is **no stored `EXPIRED` state** — the TTL index deletes expired rows, and a missing/consumed row is treated uniformly as invalid (BE-9).

**State transitions**: `PENDING → CONSUMED` (accepted); expiry is handled by TTL deletion, not a stored status.

---

## Collection: `passwordResets`

Single-use forgotten-password link for fallback members (FR-020, FR-031, FR-035).

| Field | Type | Notes |
|---|---|---|
| `id` | String | `@Id` |
| `memberId` | String | target member |
| `tokenHash` | String | **HMAC-SHA-256(token, TOKEN_PEPPER)** of 256-bit token; unique (SEC-2) |
| `status` | `ResetStatus` enum | PENDING / CONSUMED |
| `createdAt` | Instant | |
| `expiresAt` | Instant | default +1 h; **TTL index** |
| `consumedAt` | Instant (nullable) | |

**Indexes**: `{ tokenHash:1 }` unique; `{ expiresAt:1 }` TTL `expireAfterSeconds:0`.

**Validation / rules**:
- Request endpoint always responds 202 regardless of whether the email exists (enumeration-safe, FR-032); a row is created only for a real fallback member.
- Confirm is atomic single-use (FR-035); on success rotate `passwordCredential` and revoke all sessions (FR-031).

**State transitions**: `PENDING → CONSUMED`.

---

## Collection: `authAuditLog`

Append-only, member-keyed, **non-PII** security event record (FR-023, FR-036). Separate from candidate-keyed `auditLog`.

| Field | Type | Notes |
|---|---|---|
| `id` | String | `@Id` |
| `workspaceId` | String | |
| `memberId` | String (nullable) | non-PII internal id; null when no member resolved (e.g. failed login for unknown email) |
| `eventType` | `AuthEventType` enum | SIGN_IN_SUCCESS / SIGN_IN_FAILURE / SIGN_OUT / INVITATION_ISSUED / INVITATION_CONSUMED / MEMBER_DEACTIVATED / PASSWORD_RESET_REQUESTED / PASSWORD_RESET_COMPLETED |
| `occurredAt` | Instant | |
| `sourceIpHash` | String (nullable) | **HMAC-SHA-256(ip, IP_PEPPER)** — keyed so the small IPv4 space is not brute-force reversible (SEC-6); never raw IP |
| `outcome` | String | short non-PII code |

**Indexes**: `{ memberId:1, occurredAt:-1 }`.

**Validation / rules**:
- **Append-only** — no update/delete path (mirrors candidate `auditLog`).
- References members by id only, so it survives a member's PII erasure (FR-036).
- No email/name/token/IP-in-clear ever written (FR-022).

---

## Enums

- `Role`: ADMIN, RECRUITER, HIRING_MANAGER, INTERVIEWER, READ_ONLY
- `MemberStatus`: ACTIVE, DEACTIVATED
- `InvitationStatus`: PENDING, CONSUMED (expiry via TTL, not a stored state — BE-9)
- `ResetStatus`: PENDING, CONSUMED
- `AuthEventType`: (see `authAuditLog` above)

---

## Entity relationships

```text
Workspace (F03) 1───* Member
Member 1───0..1 PasswordCredential (embedded)
Member 1───0..1 SsoIdentity (embedded)
Member 1───* Session
Member 1───* PasswordReset
Member 1───* AuthAuditEvent
Workspace 1───* Invitation ──(on accept)──> creates Member
```

All collections are workspace-scoped; cross-workspace access is impossible by construction (queries always include `workspaceId`, and the member unique key is `{workspaceId, email}`).
