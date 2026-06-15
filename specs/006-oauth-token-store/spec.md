# Feature Specification: OAuth Token Store (Calendar Connections)

**Feature Branch**: `006-oauth-token-store`
**Created**: 2026-06-15
**Status**: Draft
**Backlog ID**: F01.1 (Tier 0 — Foundation, P0)
**Input**: User description: "F01.1 OAuth Token Store - encrypted per-user Google/Microsoft calendar OAuth refresh token storage behind CalendarProvider token-refresh interface"

## Overview

Cadence schedules interviews against the real-time availability of internal participants, which requires reading each member's Google or Microsoft 365 calendar. To do that without ever asking a member to log in again, Cadence must obtain a member's calendar authorization once (via the provider's standard consent screen, scoped to free/busy only) and then hold the resulting long-lived credential securely so it can be refreshed automatically whenever calendar access is needed.

This feature delivers that credential lifecycle: a member connecting their calendar account, the encrypted storage of the resulting tokens, automatic renewal of short-lived access when it expires, detection of an authorization that the member later revokes at the provider, and a member-initiated disconnect. It is a **foundation prerequisite** for the calendar integrations (F10 Google Calendar, F11 Microsoft 365), which consume the "give me a currently-valid calendar credential for this member" capability this feature provides. This feature does **not** read free/busy data or create calendar events — those are owned by F10/F11.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Connect a calendar account (Priority: P1)

As a workspace member (Recruiter, Hiring Manager, or Interviewer), I can connect my Google or Microsoft 365 calendar to Cadence by going through the provider's standard consent screen, so that Cadence can read my availability for scheduling. The consent requests free/busy access only. Once I approve, my connection is stored securely and shows as "Connected".

**Why this priority**: Nothing in the calendar tier (F10/F11) can function until a member can grant access and Cadence can hold that grant. This is the minimum viable, independently demonstrable slice — a member can connect and see confirmation, even before any calendar is actually read.

**Independent Test**: A member initiates "Connect Google" (or Microsoft), completes the provider consent against a test identity provider, is returned to Cadence, and sees their connection status as "Connected". Inspecting the stored connection record directly shows only encrypted credential material — no readable token.

**Acceptance Scenarios**:

1. **Given** an authenticated member with no calendar connected, **When** they start the Google connection flow and approve free/busy consent, **Then** Cadence records a "Connected" calendar connection for that member and provider, and the member sees a "Connected" status.
2. **Given** a member who just approved consent, **When** the stored connection record is read directly from storage, **Then** every credential field (refresh credential, access credential, provider account identifier) is ciphertext — no plaintext token, secret, or provider email appears.
3. **Given** a member who already has Google connected, **When** they complete the Google connection flow a second time, **Then** the existing connection is replaced/updated in place (exactly one connection per member per provider), not duplicated.
4. **Given** a member connecting an account, **When** the provider returns from consent, **Then** no token value, authorization code, or client secret is written to any log at any level.

---

### User Story 2 - Automatic renewal of expired calendar access (Priority: P1)

As the system, when a member's short-lived calendar access has expired but their underlying authorization is still valid, I automatically obtain fresh access using the stored long-lived credential, without prompting the member to reconnect, so that scheduling continues uninterrupted.

**Why this priority**: Short-lived access tokens expire within minutes to an hour; without transparent renewal, every scheduling action a few minutes after connecting would fail. Automatic refresh is what makes a one-time connection durable, and is the core "token store" value.

**Independent Test**: With a connected member whose access credential is set to expire imminently (simulated expiry), request a currently-valid credential for that member; the system renews it against a stubbed provider token endpoint and returns a fresh, valid credential — with zero member interaction. A renewal that returns a rotated long-lived credential persists the new one.

**Acceptance Scenarios**:

1. **Given** a connected member whose access credential has expired but whose authorization is still valid, **When** a calendar credential is requested for that member, **Then** the system renews the access credential transparently and returns a valid one without any member prompt.
2. **Given** a renewal in which the provider returns a new (rotated) long-lived credential, **When** the renewal completes, **Then** the new long-lived credential is stored encrypted and the previous one is no longer used.
3. **Given** two near-simultaneous requests for a credential whose access has just expired, **When** both are served, **Then** the stored connection is not corrupted and at most one renewal exchange is performed (the second reuses the renewed credential).
4. **Given** any renewal exchange, **When** it completes or fails, **Then** no token value or client secret appears in any log line.

---

### User Story 3 - Disconnect a calendar account (Priority: P2)

As a workspace member, I can disconnect my connected calendar from Cadence so that my stored credentials are deleted and Cadence can no longer access my calendar.

**Why this priority**: Required for member control and privacy (the member can withdraw the grant), but the connect + refresh slice is demonstrable without it. Also the path invoked automatically when a member is deactivated or erased.

**Independent Test**: A connected member chooses "Disconnect"; afterward their connection status shows "Not connected", the stored credential record is removed, and a subsequent credential request for that member reports "not connected" rather than returning a credential.

**Acceptance Scenarios**:

1. **Given** a member with a connected calendar, **When** they disconnect, **Then** the stored credentials for that member and provider are deleted and the status becomes "Not connected".
2. **Given** a member whose Cadence account is deactivated, **When** the deactivation completes, **Then** any stored calendar credentials for that member are deleted.
3. **Given** a member who disconnects, **When** the deletion occurs, **Then** Cadence makes a best-effort request to revoke the grant at the provider, and a failure of that provider call does not leave the local credentials un-deleted.

---

### User Story 4 - Detect and surface a revoked or broken connection (Priority: P2)

As a workspace member, when my authorization has been revoked at the provider (or otherwise stops working), I see my Cadence connection marked as needing reconnection and can reconnect, rather than scheduling silently failing.

**Why this priority**: A member can revoke Cadence's access in their Google/Microsoft account at any time; the system must degrade gracefully and tell the member, not fail opaquely. Important for reliability but secondary to the core connect + refresh flow.

**Independent Test**: With a connected member, simulate the provider rejecting a renewal as an invalid/expired grant; the system marks the connection "Needs reconnection", does not crash or retry forever, and the member sees a prompt to reconnect. Reconnecting restores "Connected".

**Acceptance Scenarios**:

1. **Given** a connected member whose grant has been revoked at the provider, **When** the system attempts to renew access, **Then** the renewal is recognized as a permanent authorization failure, the connection is marked "Needs reconnection", and the requesting caller is told no valid credential is available.
2. **Given** a member whose connection is "Needs reconnection", **When** they view their connection status, **Then** they see a clear prompt to reconnect (distinct from "Not connected" and from a transient error).
3. **Given** a transient provider outage (rate-limit or temporary server error) during renewal, **When** the system retries with backoff and the provider recovers, **Then** the connection remains "Connected" and is not marked "Needs reconnection".

---

### Edge Cases

- **Rotated long-lived credential**: Both Google and Microsoft may return a new refresh credential on renewal; the new value MUST replace the old, or the next renewal will fail.
- **Concurrent renewals**: Multiple scheduling reads for the same member at once must not trigger multiple conflicting renewals or corrupt the stored record.
- **Clock skew / imminent expiry**: Access treated as expired slightly before its nominal expiry (a safety buffer) so a credential is never handed out moments before it dies.
- **Permanent vs transient failure**: An invalid-grant (revoked/expired authorization) is permanent → mark "Needs reconnection"; a rate-limit or temporary server error is transient → retry with backoff, leave status "Connected".
- **Repeat connection**: Connecting the same provider again replaces the existing connection (one per member per provider); it never creates a second record.
- **Provider returns no long-lived credential**: If consent completes without yielding a storable long-lived credential (e.g., the provider only returns one on first consent), the system detects this and instructs the member how to re-grant so a durable credential is obtained, rather than storing an unusable connection.
- **Member deactivation / erasure**: Stored credentials are deleted when a member is deactivated or erased; no orphaned credentials remain.
- **Cross-member isolation**: A member can only view, create, or delete their own connection; no member or role can read another member's stored credentials.
- **Unsupported / mismatched provider**: A connection attempt for a provider Cadence does not support (anything other than Google or Microsoft for the MVP) is rejected cleanly.

## Requirements *(mandatory)*

### Functional Requirements

**Connection lifecycle**

- **FR-001**: The system MUST let an authenticated workspace member initiate a calendar connection for a supported provider (Google, Microsoft 365) via that provider's standard consent flow.
- **FR-002**: The system MUST request only free/busy-level calendar scope during consent; broader scopes MUST NOT be requested.
- **FR-003**: On successful consent, the system MUST obtain and persist the member's long-lived calendar authorization credential and record the connection as "Connected".
- **FR-004**: The system MUST maintain at most one connection per (member, provider); re-connecting the same provider MUST update the existing connection in place rather than creating a duplicate.
- **FR-005**: The system MUST allow a member to disconnect their calendar, which MUST delete their stored credentials for that provider and set the status to "Not connected".
- **FR-006**: On disconnect, the system MUST make a best-effort attempt to revoke the grant at the provider; a failure of that provider call MUST NOT prevent local credential deletion.
- **FR-007**: The system MUST delete a member's stored calendar credentials when that member is deactivated or erased.

**Credential storage & secrecy**

- **FR-008**: The system MUST store all calendar credential material (long-lived credential, any cached short-lived credential, and the provider account identifier) encrypted at rest, such that reading the raw stored record yields only ciphertext.
- **FR-009**: The system MUST NOT write any token value, authorization code, client secret, or provider account email/identifier to application logs at any level, during any operation (connect, renew, disconnect, failure).
- **FR-010**: The system MUST treat the provider account identifier (the email/subject associated with the connected account) as personal data and protect it with the same encryption and logging rules as other credential material.

**Renewal & retrieval**

- **FR-011**: The system MUST provide calendar integrations with a single, provider-agnostic way to obtain a currently-valid calendar credential for a given member and provider.
- **FR-012**: When a short-lived access credential is expired or within a safety buffer of expiring, the system MUST renew it automatically using the stored long-lived credential, with no member interaction.
- **FR-013**: When a renewal returns a rotated long-lived credential, the system MUST persist the new value and stop using the prior one.
- **FR-014**: The system MUST ensure concurrent credential requests for the same member do not corrupt the stored connection and do not perform redundant simultaneous renewals.
- **FR-015**: When a renewal fails because the underlying authorization is permanently invalid (revoked or expired grant), the system MUST mark the connection "Needs reconnection" and report to the caller that no valid credential is available — without retrying indefinitely.
- **FR-016**: When a renewal fails transiently (provider rate-limiting or temporary server error), the system MUST retry with bounded exponential backoff before giving up, and MUST NOT mark the connection "Needs reconnection" for a purely transient failure.

**Status & access control**

- **FR-017**: A member MUST be able to view the status of their own calendar connection(s), distinguishing at least: Not connected, Connected, and Needs reconnection.
- **FR-018**: The system MUST restrict viewing, creating, and deleting a calendar connection to the owning member only; no other member or role (including Admin) may read another member's stored credentials.
- **FR-019**: The system MUST reject a connection attempt for an unsupported provider with a clear error.
- **FR-020**: The system MUST record connection lifecycle events (connected, disconnected, marked needs-reconnection) in the audit trail using internal identifiers only, with no credential or PII content.

### Key Entities *(include if feature involves data)*

- **Calendar Connection**: Represents one member's authorization to one calendar provider. Key attributes: owning workspace, owning member (internal identifier), provider (Google or Microsoft), connection status (Not connected is represented by absence; Connected; Needs reconnection), the long-lived authorization credential (encrypted), an optional cached short-lived access credential with its expiry (encrypted), the granted scope, the provider account identifier (encrypted), and lifecycle timestamps (connected-at, last-renewed-at). Uniquely identified by (workspace, member, provider).
- **Valid Calendar Credential (transient result)**: The currently-usable access credential handed to a calendar integration on request. Not necessarily persisted in plaintext; derived by renewing if needed. Has an expiry and the provider it applies to.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A member can connect a calendar account end-to-end (initiate → consent → return to "Connected") in a single uninterrupted flow with no more than one consent screen.
- **SC-002**: 100% of stored connection records, when read directly from storage, expose no plaintext credential or provider account identifier (only ciphertext).
- **SC-003**: An automated scan of all application logs across the full connect, renew, and disconnect flows finds zero occurrences of token values, authorization codes, client secrets, or provider account emails.
- **SC-004**: When a member's authorization is still valid but their short-lived access has expired, calendar credential requests succeed via transparent renewal in 100% of cases with zero member re-prompts.
- **SC-005**: A credential request that requires a renewal performs **exactly one** outbound token-endpoint call plus one stored-credential write (no scan, poll, or N+1) — verified structurally; the bounded `RestClient` connect/read timeouts keep it within a 5-second wall-clock target under normal provider latency (the timeout, not a flaky CI latency assertion, is the enforced bound).
- **SC-006**: An authorization revoked at the provider is reflected as "Needs reconnection" on the member's next credential request, with no crash, no infinite retry, and a member-visible reconnect prompt.
- **SC-007**: A member can never retrieve, view, or delete another member's calendar connection (verified by access-control tests for every non-owner role, including Admin).

## Assumptions

- **Connect UX ownership**: This feature provides the connection lifecycle and a minimal status/connect/disconnect surface. The richer "connect your calendar" presentation within the calendar feature is owned by F10/F11; both consume the same underlying connection capability.
- **Calendar read/write is out of scope**: Reading free/busy availability and creating, updating, or deleting calendar events are owned by F10 (Google) and F11 (Microsoft 365). This feature stops at providing a valid credential on demand.
- **Provider-agnostic interface**: Per the constitution dependency policy, the "obtain a valid calendar credential" capability is exposed through the domain `CalendarProvider` abstraction; provider SDKs are wrapped behind it so business logic never references a provider SDK directly. The token-store internals are the first implementation behind that abstraction.
- **Reuse of existing security infrastructure**: Encryption-at-rest reuses the established application-level field encryption (the same AES-256-GCM PII encryption and Spring Data property-converter mechanism used for member email and other PII). A non-reversible peppered hash is used for any field that must be looked up. No new cryptographic dependency is introduced.
- **Supported providers (MVP)**: Google and Microsoft 365 only, consistent with the MVP calendar scope; other providers are out of scope.
- **Authentication & identity**: The member is already authenticated to Cadence (F01) before connecting a calendar; the calendar grant is separate from the member's Cadence login session.
- **No new infrastructure**: Credentials are stored in the existing MongoDB database; no broker, cache, or secrets service is introduced (constitution C2/C4). Provider client credentials (client id/secret) are supplied via the existing secrets mechanism, never committed to source.
- **Test identity provider**: Connection and renewal flows are verified against a stubbed/local provider token endpoint (no live Google/Microsoft credentials required in CI), consistent with the project's no-cloud-credentials test rule.

## Dependencies

- **F01 — Authentication & Session Management** (complete): provides the authenticated member context and the reusable PII encryption infrastructure.
- **F00.1 — MongoDB Index Bootstrapping**: this feature adds one collection and its index via the next Mongock changeset in sequence.
- **Consumed by F10 (Google Calendar) and F11 (Microsoft 365)**: these features depend on this one for encrypted token storage and on-demand valid-credential retrieval.

## Out of Scope

- Reading free/busy data or any calendar event content (F10/F11).
- Creating, updating, or deleting calendar events (F10/F11).
- The polished in-product "connect your calendar" experience and calendar settings UI beyond a minimal connection-status surface (F10/F11).
- Any calendar scope beyond free/busy (broader scopes are explicitly excluded by the constitution for the MVP).
- OAuth tokens for non-calendar purposes (e.g., ATS connectors handle their own credentials in F40/F41).
- Encryption key rotation tooling (reuses existing key management as-is).
