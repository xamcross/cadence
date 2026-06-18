# Feature Specification: ATS Integration — Lever (F41)

**Feature Branch**: `020-lever-ats`
**Created**: 2026-06-18
**Status**: Draft
**Input**: User description: "F41 ATS Integration: Lever - bidirectional candidate/stage sync and scheduling write-back via the shared AtsConnector interface, coexisting with Greenhouse"

## Overview

Cadence sits between a company's Applicant Tracking System (ATS) and its calendar/email systems. This feature connects a workspace to **Lever** so that candidate ("opportunity") and pipeline-stage data flow **into** Cadence automatically, and Cadence's scheduling activity flows **back out** to the candidate's Lever profile timeline. The goal is that a recruiter who lives in Lever never has to copy a candidate into Cadence by hand, and never loses visibility of a scheduling action because it happened in a separate tool.

This is the **second** of the MVP ATS connectors (F40 Greenhouse shipped first). F41 is overwhelmingly a **reuse** of the connector contract, sync orchestration, write-back outbox, retry/dead-letter, role model, and PII protections that F40 already established. The only genuinely new functional surface is (a) a Lever-specific provider adapter behind the existing `AtsConnector` contract, and (b) **multi-connector coexistence** — a single workspace, and the platform as a whole, must support Greenhouse and Lever side by side without interference. Per the constitution Dependency Policy, all provider-specific access stays wrapped behind the single domain interface so business logic never depends on a provider SDK.

Because live Lever credentials are not yet provisioned, this feature ships and is verified end-to-end against a **locally-runnable Lever API stub explicitly labelled "integration-pending"**, exactly as F40 did for Greenhouse. Promotion to live credentials is a later, separately-reviewed step (see Assumptions and the Out of Scope section).

## Clarifications

### Session 2026-06-18

- Q: Credential model for Lever — API key vs OAuth (the backlog says "Lever REST API")? → A: **Workspace-scoped Lever API key (Data API, HTTP Basic with the key as username)** — mirrors the F40 Greenhouse decision: a write-only encrypted secret, no OAuth app registration or token-refresh machinery, cheapest and simplest to build, and keeps the two connectors structurally identical. (Lever also offers OAuth; OAuth is deferred and not required for this increment.)
- Q: One connection per workspace, or can a workspace connect to both Greenhouse and Lever at once? → A: **One connection per (workspace, provider)** — a workspace may hold at most one Greenhouse connection and at most one Lever connection simultaneously, and they operate independently. This is the multi-connector coexistence the F41 backlog calls out. (Multi-account/agency multi-client per provider remains out of scope.)
- Q: When both connectors run, how is a candidate's provenance kept straight? → A: **Reconciliation key is (provider + external reference)** — the external reference is only authoritative *within its own provider*. A Greenhouse-sourced candidate and a Lever-sourced candidate are never merged on external reference alone; the email-hash secondary match still applies only when no external reference for *that provider* is recorded, and never merges across providers.
- Q: Which Cadence events are written back to the Lever timeline this increment? → A: **Identical write-back set to F40** — scheduling link sent, interview confirmed/booked, rescheduled, cancelled, candidate no-show (F23), and interviewer feedback submitted (F32). Each is a separate idempotent write-back item, routed to the candidate's provider of record.
- Q: How does inbound Lever data stay fresh within 5 minutes? → A: **Scheduled poll only** — the same `@Scheduled` reconciliation pattern as F40, at a ≤5-minute interval against the authenticated Lever API. **No inbound webhook endpoint is exposed** (smaller attack surface; the stored credential is the only inbound auth).
- Q: How is an imported Lever candidate's pipeline stage represented? → A: **Raw external stage label (free text)** plus the external posting/requisition reference — no internal stage enum or mapping, identical to F40.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Connect a workspace to Lever (Priority: P1)

As an Admin, I can connect my workspace to Lever by entering a Lever API credential in Cadence's integration settings, so that Cadence can read my candidates and write scheduling activity back without any further manual setup — and I can do this whether or not the workspace is also connected to Greenhouse.

**Why this priority**: Nothing else in this feature can happen until a Lever connection exists. It is the entry point and the smallest independently demonstrable slice — connecting, validating, and showing connection health is itself useful, and proving it can sit alongside a Greenhouse connection is the core new capability of F41.

**Independent Test**: From the integration settings screen, enter a valid Lever credential against the integration-pending stub, observe the Lever connection move to a "connected" state with a last-verified timestamp; enter an invalid credential and observe a clear, non-leaking error state. With a Greenhouse connection already present in the same workspace, confirm both connections are shown and managed independently. No candidate import or write-back is required for this slice to deliver value.

**Acceptance Scenarios**:

1. **Given** an Admin on the integration settings screen with no existing Lever connection, **When** they submit a valid Lever credential, **Then** Cadence verifies the credential against Lever, stores it encrypted, and displays the Lever connection as "connected" with a last-verified timestamp.
2. **Given** an Admin submitting an invalid or revoked Lever credential, **When** verification fails, **Then** Cadence does not store a usable connection, displays a clear "could not connect" message, and the failure is recorded without exposing the credential or any candidate data.
3. **Given** an existing Lever connection, **When** a non-Admin attempts to view or change it, **Then** a Recruiter sees connection health only (state + timestamps, never the secret), a Hiring Manager / Interviewer / Read-only user is refused, and every non-Admin is refused any credential or change/disconnect access.
4. **Given** an existing Lever connection, **When** an Admin disconnects it, **Then** the stored credential is removed, no further Lever sync or write-back occurs, and previously imported Lever candidate records remain intact (subject to retention/erasure rules).
5. **Given** a workspace already connected to Greenhouse, **When** an Admin also connects Lever, **Then** both connections exist independently with their own state and timestamps, and disconnecting one does not affect the other.

---

### User Story 2 - Lever candidates and stages flow into Cadence automatically (Priority: P1)

As a Recruiter using Lever, I want candidates ("opportunities") and their current pipeline stage to appear in Cadence automatically and stay up to date, so that I can schedule them in Cadence without re-entering anyone — and candidates from Lever and Greenhouse coexist cleanly in the same workspace.

**Why this priority**: The core inbound value of the connector. Without imported candidates there is nothing to schedule from the ATS, and "new candidates appear within 5 minutes" is the headline outcome of the feature.

**Independent Test**: Seed the integration-pending Lever stub with candidates on postings/stages; trigger (or wait for) a sync; confirm the candidates, their external posting/requisition, and current stage appear in Cadence and are visible on a sync/connection status surface within the target window. Update a candidate's stage in the stub; confirm Cadence reflects the new stage on the next sync. In a workspace also connected to Greenhouse, confirm Lever and Greenhouse candidates are imported by their respective syncs without cross-contamination.

**Acceptance Scenarios**:

1. **Given** a connected workspace and a candidate that exists on a posting in Lever, **When** a sync runs, **Then** the candidate appears in Cadence with their name/email/phone where available (encrypted at rest), an external reference identifier, their associated posting/requisition, their current stage, and a recorded source provider of "Lever".
2. **Given** a candidate already imported from Lever, **When** their stage changes in Lever, **Then** a subsequent sync updates the stored stage on the existing Cadence record (no duplicate candidate is created).
3. **Given** a Lever candidate whose email matches an existing Cadence candidate in the same workspace that has no Lever external reference yet, **When** sync processes them, **Then** they are reconciled to the same Cadence record rather than duplicated; a candidate already keyed to a *different* provider's external reference is NOT merged with the Lever candidate.
4. **Given** a newly added candidate in Lever, **When** the candidate is created or moved into a tracked stage, **Then** the candidate is reflected in Cadence within 5 minutes.
5. **Given** an imported Lever candidate, **When** Cadence has not yet recorded email-contact consent/lawful basis for them, **Then** no outbound candidate email can be sent to them until consent is recorded through the existing consent flow (import does not by itself authorize contacting the candidate).
6. **Given** a workspace connected to both Greenhouse and Lever, **When** both syncs run, **Then** each candidate is imported under its own provider of record and the two provider syncs do not double-import, overwrite, or merge each other's candidates.

---

### User Story 3 - Scheduling activity is written back to Lever (Priority: P2)

As a Recruiter, when a scheduling action happens in Cadence (e.g., a scheduling link is sent, an interview is booked, rescheduled, or cancelled), I want that action recorded on the candidate's Lever timeline, so that anyone working in Lever sees the up-to-date scheduling state without switching tools — and a Greenhouse-sourced candidate's activity still goes to Greenhouse, never to Lever.

**Why this priority**: This is the outbound half of "bidirectional" and the differentiating value, but it depends on a connection (US1) and on candidates existing (US2) and on Cadence's existing scheduling flows producing events. It is independently demonstrable once a Lever candidate is mapped.

**Independent Test**: For a mapped Lever candidate, trigger a Cadence scheduling lifecycle event; confirm a corresponding activity/note is recorded against the candidate in the integration-pending Lever stub, with no candidate free-text leaking into logs. For a mapped Greenhouse candidate in the same workspace, confirm the same event routes to Greenhouse and not to Lever.

**Acceptance Scenarios**:

1. **Given** a mapped Lever candidate, **When** a Cadence event in the write-back set occurs (link sent, interview confirmed, rescheduled, cancelled, no-show, or feedback submitted), **Then** a corresponding activity is written to that candidate's Lever timeline.
2. **Given** a write-back is attempted, **When** it succeeds, **Then** Cadence records that the event was delivered so the same event is not written twice (idempotent write-back).
3. **Given** a candidate who has been erased in Cadence, **When** a write-back would otherwise occur for them, **Then** no personal data is sent and the action follows the erasure rules (the candidate's data is not re-exposed to the ATS).
4. **Given** a workspace connected to both providers, **When** a write-back occurs for a candidate, **Then** it is routed only to that candidate's source provider (a Lever candidate's activity never lands in Greenhouse and vice versa).

---

### User Story 4 - Resilience when Lever is unavailable (Priority: P2)

As a Recruiter, when Lever is temporarily unreachable, I want Cadence to keep my scheduling actions safe and tell me the integration is degraded, so that no activity is silently lost and I know when sync is behind — and a Lever outage must not stall my Greenhouse integration.

**Why this priority**: Email is the sole candidate channel and the ATS timeline is a system-of-record expectation; a dropped write-back is a trust failure. Degraded-mode handling is required for the connector to be production-credible, but it builds on US1–US3.

**Independent Test**: Make the integration-pending Lever stub return errors/timeouts; perform a scheduling action for a Lever candidate; confirm the write-back is queued (not dropped), a degraded-state indicator is shown for the Lever connection on the integration status surface, and the queued write-back is delivered automatically once the stub recovers. Confirm a healthy Greenhouse connection in the same workspace continues syncing normally during the Lever outage.

**Acceptance Scenarios**:

1. **Given** Lever is unreachable, **When** a write-back is attempted, **Then** the write-back is persisted to a retry queue (not dropped) and is retried automatically.
2. **Given** a queued write-back, **When** Lever recovers, **Then** the write-back is delivered within 15 minutes of provider recovery and removed from the queue.
3. **Given** Lever is unreachable during an inbound sync, **When** the sync cannot complete, **Then** the failure is recorded (referencing internal identifiers only), the integration status surface shows a degraded indicator for the Lever connection, and the next successful sync reconciles any missed changes.
4. **Given** repeated failures that exceed the retry policy, **When** a write-back cannot be delivered, **Then** it is moved to a dead-letter state visible to an Admin rather than being discarded, and an operator is notified.
5. **Given** a workspace connected to both providers, **When** Lever is degraded, **Then** the Greenhouse connection's sync and write-back are unaffected (provider failures are isolated per connection).

---

### Edge Cases

- **Invalid or revoked credential mid-life**: a previously working Lever connection starts returning authorization errors → that connection is flagged as needing re-authorization; Lever sync/write-back pause; Admin is prompted to re-enter the credential. The Greenhouse connection (if any) is unaffected. No candidate data is exposed in the error.
- **Duplicate identity within a provider**: the same external Lever candidate is seen twice (overlapping or re-run sync) → exactly one Cadence record results; processing is idempotent.
- **Same email across providers**: a Greenhouse candidate and a Lever candidate share an email → they are kept as two distinct records; email never merges across providers (the (provider + external reference) key is authoritative — see FR-008). A scheduling event for each record produces two independent, provider-correct write-backs (the Greenhouse record's to Greenhouse, the Lever record's to Lever).
- **Unusual stage label**: any Lever stage label is stored as-is (free text); the candidate is imported regardless of label and is never dropped for an unrecognized stage.
- **Candidate erased in Cadence, then a Lever change arrives**: the erased candidate's personal fields are not re-populated from the ATS; the inbound change is handled without resurrecting erased PII.
- **Candidate archived/rejected in Lever**: Cadence reflects the terminal state on its record without deleting audit history.
- **No inbound endpoint**: there is no inbound webhook; all inbound data is pulled by the authenticated scheduled poll, so a forged/unauthenticated push cannot inject candidate data. Freshness is bounded by the poll interval (≤5 min).
- **Burst load**: 50 Lever candidates appearing between polls are all imported on the next poll within the target window without loss.
- **Provider rate limiting**: Lever throttles requests → Cadence backs off and retries within policy rather than failing the sync outright.
- **Concurrent / overlapping sync runs**: two Lever sync cycles overlapping — or a Lever sync overlapping a Greenhouse sync — must not double-import, cross-merge, or corrupt records.
- **Retention conflict**: an imported Lever candidate older than the workspace retention period is subject to the existing retention flagging/blocking rules.

## Requirements *(mandatory)*

### Functional Requirements

**Connection & configuration**

- **FR-001**: The system MUST allow an Admin to connect a workspace to Lever by providing a Lever API key (Data API, HTTP Basic with the key as username), stored as a write-only encrypted secret, scoped to **one connection per (workspace, provider)**. A workspace MAY simultaneously hold a Greenhouse connection and a Lever connection. (No OAuth authorization flow or token refresh is required in this feature.)
- **FR-002**: The system MUST verify a submitted Lever credential against Lever before treating the connection as active, and MUST surface a clear connection state (connected / needs re-authorization / error / integration-pending) with a last-verified timestamp, independently per provider.
- **FR-003**: The system MUST store the Lever credential encrypted at rest and MUST never return it in any response, log, audit entry, sync-failure record, or dead-letter record. The credential MUST be managed as a write-only secret (a connection-status boolean may be exposed, never the secret). On a verification or sync failure, any provider error response MUST be reduced to a status/category code before being persisted or surfaced; the raw provider response body and any echoed credential or authorization header MUST NOT be stored or shown verbatim.
- **FR-004**: The system MUST restrict creating, changing, and removing the Lever connection (and any access to the credential) to the Admin role; non-Admin access to the credential MUST be refused. Non-Admin roles permitted to use the integration (Recruiter) MAY view connection *health* (state + timestamps, never the secret); Hiring Manager, Interviewer, and Read-only roles have no connection-management access. The role policy is identical to F40 and applies per provider.
- **FR-005**: The system MUST allow an Admin to disconnect the Lever integration, after which no further Lever sync or write-back occurs, the stored Lever credential is destroyed, and any pending (not-yet-delivered) Lever write-back items for that workspace are cancelled; previously imported Lever candidate records are retained subject to retention/erasure rules. Disconnecting Lever MUST NOT affect a coexisting Greenhouse connection or its pending write-backs.

**Inbound sync (candidate & stage data)**

- **FR-006**: The system MUST import candidates from the connected Lever account into the workspace, including each candidate's identity (name, email, phone where available), an external reference identifier, the associated posting/requisition, and the current pipeline stage, recording "Lever" as the source provider.
- **FR-007**: The system MUST keep imported Lever candidates' stage up to date as it changes in Lever, updating the existing Cadence record without creating duplicates.
- **FR-008**: The system MUST reconcile inbound Lever candidates to existing Cadence records using **(source provider + external reference identifier)** as the authoritative key. A secondary match by email (via the keyed email hash, never the encrypted email value) within the same workspace applies ONLY to a record that is **not yet keyed to any provider's external reference** — once a record carries an external reference for *any* provider it MUST NOT be adopted by email for a different provider (a Cadence candidate holds exactly one `(source provider, external reference)` pair). Two distinct external references MUST NOT be merged into one record. Reconciliation MUST be idempotent so re-imports and overlapping syncs do not create duplicates.
- **FR-009**: The system MUST reflect a newly added or newly stage-changed Lever candidate in Cadence within 5 minutes of the change, achieved by a scheduled reconciliation poll whose configurable interval is set so that worst-case freshness (poll interval + per-poll processing time, including a 50-candidate batch) stays within the 5-minute bound. (The plan MUST pin the concrete interval against SC-001/SC-002.)
- **FR-010**: The system MUST store and retain each candidate's stage as the raw external stage label (free text) exactly as Lever reports it, without requiring a mapping to any internal Cadence stage; a candidate MUST NOT be dropped because their stage is unrecognized.
- **FR-011**: Inbound Lever sync MUST pull candidate/stage data from the authenticated Lever API (the stored API key is the inbound auth). The system MUST NOT expose any unauthenticated inbound endpoint for ATS data ingestion (no inbound webhook in this feature); inbound data is never accepted from an unauthenticated caller.
- **FR-012**: The system MUST run inbound Lever sync without a queue broker or additional infrastructure service, using the workspace's scheduled-task / checkpoint pattern with idempotent, missed-fire-safe behavior, on a checkpoint independent of the Greenhouse sync so the two providers' schedules do not block each other.

**Outbound write-back (scheduling activity)**

- **FR-013**: The system MUST write a corresponding activity to the candidate's Lever timeline when any of these Cadence events occurs for a mapped Lever candidate: scheduling link sent, interview confirmed/booked, rescheduled, cancelled, candidate no-show (F23), and interviewer feedback submitted (F32).
- **FR-014**: The system MUST make Lever write-back idempotent so that the same Cadence event is not recorded more than once on the Lever timeline, even across retries or restarts.
- **FR-015**: The system MUST NOT write back any personal data for a candidate who is in an erased state, MUST cancel/sweep any pending write-back items for an erased candidate, and MUST follow the erasure rules so erased data is not re-exposed to the ATS. Every inbound update/reconcile write MUST be guarded so it applies only while the candidate record is in the active (non-erased) state — an inbound change MUST NOT re-populate name/email/phone on an erased record (a non-PII stage-label update is permitted); a sync read followed by a write that races an erasure MUST resolve in favor of erasure (atomic active-state-guarded write). The erasure sweep MUST cancel pending write-back items for the erased candidate across **all** connected providers (provider-agnostic invalidation).
- **FR-016**: The system MUST route each write-back to the candidate's source provider only, keyed on the candidate's recorded source provider — a Lever candidate's activity is written to Lever and a Greenhouse candidate's activity to Greenhouse; an event MUST NOT be written to the wrong provider, and a candidate with no ATS link generates no write-back. Each persisted write-back item MUST carry the target provider so a mis-registered adapter cannot mis-route a recorded item.

**Resilience & degraded mode**

- **FR-017**: The system MUST persist any Lever write-back that cannot be delivered (because Lever is unreachable, throttled, or erroring) to a durable retry queue rather than dropping it.
- **FR-018**: The system MUST automatically retry queued Lever write-backs with backoff, delivering a recoverable write-back within 15 minutes of provider recovery.
- **FR-019**: The system MUST move a Lever write-back that exhausts the retry policy to a dead-letter state that is visible to an Admin and notifies an operator, rather than discarding it.
- **FR-020**: The system MUST surface a degraded-state indicator on the ATS integration settings/status screen whenever Lever sync or write-back is failing or behind, referencing internal identifiers only (never PII or provider error bodies), and MUST present each provider's health independently.
- **FR-021**: The system MUST back off and retry (within policy) when Lever rate-limits requests, rather than failing the entire sync.
- **FR-022**: A failure, degradation, or rate-limit of one provider MUST NOT pause, fail, or delay the other provider's sync or write-back for the same workspace (provider isolation).

**Security, privacy & auditing**

- **FR-023**: The system MUST store imported candidate personal data (name, email, phone) encrypted at rest, consistent with the workspace's existing candidate data protection.
- **FR-024**: The system MUST NOT write candidate personal data or free-text field values (name, email, phone, stage label) to application logs at any level; logs MUST reference only internal candidate identifiers, the source provider, and the opaque external reference identifier.
- **FR-025**: Importing a candidate MUST NOT by itself establish lawful basis to contact the candidate; outbound candidate email remains gated by the existing consent/lawful-basis checks until basis is recorded.
- **FR-026**: The system MUST record, for each connection/sync/write-back event, at minimum: the source provider, the event outcome (success/failure/category), a timestamp, the actor or trigger, counts processed/created/updated (for sync), and the internal candidate id plus opaque external reference (for per-candidate events) — and MUST NOT record candidate PII or the credential in these records. The recorded source provider on each per-candidate sync/write-back record MUST match the candidate's provider of record (so SC-013 provider-correctness is auditable, not only behavioural).
- **FR-027**: An imported Lever candidate MUST be subject to the same right-to-erasure and retention enforcement as any other Cadence candidate.
- **FR-028**: Imported Lever candidates and their associated requisitions MUST inherit the same role-based access control and Hiring-Manager requisition scoping as natively-created candidates. Importing a candidate via the ATS MUST NOT widen any role's candidate visibility (no role gains blanket access to the imported pipeline merely because it arrived from the ATS). (Per the F40 precedent, the candidate→requisition→assignment link does not yet exist; Hiring-Manager *requisition-level* scoping is inherited-as-deferred to F51 — F41 MUST NOT widen visibility, and MUST NOT claim to deliver new HM scoping.)
- **FR-029**: The connector MUST request and retain ONLY the enumerated fields (candidate name, email, phone, external reference, associated posting/requisition, current stage). It MUST NOT import or store resumes/attachments, recruiter notes, free-text custom fields, social/links profiles, tags, sources, archive-reason text, or demographic / equal-opportunity (EEO/EEOC) data (data minimization; avoid ingesting special-category data with no lawful basis). Lever's EEO data resides on a separate endpoint that MUST never be called.

**Provider abstraction, schema & integration-pending delivery**

- **FR-030**: All Lever-specific access MUST be encapsulated behind the **existing** provider-agnostic connector contract (`AtsConnector`) established by F40, such that workspace/business logic depends only on the contract, not on any Lever-specific client. Adding Lever MUST require no change to the shared sync/write-back/retry **business** orchestration beyond registering the new provider adapter and provider identity; if a contract change is unavoidable, it MUST remain backward-compatible with the Greenhouse adapter.
- **FR-031**: The system MUST migrate the ATS-connection uniqueness constraint from one-per-workspace to **one-per-(workspace, provider)** so a workspace can hold a Greenhouse connection and a Lever connection simultaneously. (The shipped F40 schema enforces a unique `{workspaceId}` connection index; F41 MUST change this — via a new, idempotent migration that back-fills the provider on existing rows — or coexistence is impossible. This is the one unavoidable schema change; FR-030's "no business-logic change" claim does NOT extend to the data schema.)
- **FR-032**: The feature MUST be deliverable and verifiable end-to-end against a locally-runnable Lever API stub that is explicitly labelled "integration-pending"; promotion to live Lever credentials is a separate, explicitly-reviewed step that MUST include a Security re-review (credential never logged, credential in environment/secret config not source, and confirmation that the Lever opportunity identifier used as the external reference is the correct addressing key for the write-back endpoint) and MUST NOT be implied as complete by this feature.
- **FR-033**: Greenhouse and Lever connectors MUST coexist in a single workspace and across the platform without interference: independent connection records, independent sync schedules/checkpoints, independent write-back queues, and provider-scoped reconciliation — verified by an integration test exercising both connectors in one workspace.

### Key Entities *(include if feature involves data)*

- **ATS Connection**: One per (workspace, provider). Represents the link to Lever (or Greenhouse): provider identity, encrypted API key (write-only secret), connection state (connected / needs re-authorization / error / integration-pending), last-verified and last-sync timestamps, and the poll interval/cursor. A workspace may have one of each provider. Owned/managed by Admin.
- **Imported Candidate (extension of the existing Candidate)**: Adds the link to the ATS — an opaque external reference identifier (authoritative *within its provider*), the **source provider** (Greenhouse or Lever), the associated external posting/requisition, and the current stage as a raw free-text label. Personal fields and the stage label remain encrypted/no-log and subject to consent/erasure/retention.
- **Requisition / Posting (external)**: The Lever posting a candidate is associated with — external identifier plus a human-readable title — used to group candidates and (later) to scope role-based visibility.
- **Stage**: The candidate's current pipeline position as known to Lever, stored as a raw free-text label (no internal stage taxonomy).
- **Write-Back Item**: A pending or completed outbound activity tied to a Cadence scheduling event, a mapped candidate, and the candidate's source provider, carrying a delivery state (pending / delivered / dead-letter), attempt count, and an idempotency marker so the same event is delivered at most once to the correct provider.
- **Sync Run record**: A record of an inbound sync cycle for a given provider — provider, start/finish timestamps, candidates processed/created/updated, and failure summary (no PII) — used to drive the per-provider status surface and audit.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A candidate newly created or moved into a tracked stage in Lever appears (or updates) in Cadence within **5 minutes** of the change.
- **SC-002**: A burst of **50 Lever candidates** arriving at once is fully processed into Cadence within **5 minutes**, with every candidate imported exactly once (no duplicates, no losses).
- **SC-003**: A Cadence scheduling lifecycle event for a mapped Lever candidate results in exactly one corresponding Lever timeline activity under normal operation and across retries/restarts. (Honest bound: at-most-once is enforced by a local claim-before-send transition; a crash in the narrow window between provider-accept and recording delivery is reconciled on restart rather than blindly re-sent — see Assumptions.)
- **SC-004**: When Lever is unreachable, **100%** of attempted write-backs are queued (none dropped), and a recoverable write-back is delivered within **15 minutes** of provider recovery.
- **SC-005**: **Zero** occurrences of candidate name, email, phone, stage label, or the Lever credential appear in application logs across the full connect → sync → write-back → failure flow (verified by log scan).
- **SC-006**: Reading the stored Lever connection record directly shows the credential only as ciphertext; the credential is never present in any API response.
- **SC-007**: A simulated mid-cycle restart during Lever sync or write-back — including a restart while **both** Greenhouse and Lever have in-flight cycles in the same workspace — does not produce duplicate imports, duplicate timeline activities, or any cross-provider corruption.
- **SC-008**: An imported Lever candidate cannot be sent an outbound email until email-contact consent/lawful basis is recorded — verified by attempting a send and observing it blocked.
- **SC-009**: The shared sync/write-back/retry **business orchestration** code is unchanged by adding Lever (verified by a structural test that business-layer classes reference no Lever-specific type, the F40 constant-pool/`MailTransportSwapTest` precedent), demonstrating the F40 abstraction holds for a second provider. (The only permitted non-business change is the provider-adapter registration and the FR-031 connection-uniqueness schema migration.)
- **SC-010**: An invalid or revoked Lever credential never yields an active connection and never exposes candidate data or the credential value in the resulting error.
- **SC-011**: An Admin can see, at any time and per provider, the current integration state (connected / needs re-authorization / error / degraded / integration-pending), the last successful sync time, and any write-backs stuck in dead-letter.
- **SC-012**: Reading an imported Lever candidate document directly shows name, email, and phone only as ciphertext (encrypted at rest).
- **SC-013**: In a single workspace connected to both Greenhouse and Lever, candidates and write-backs are kept provider-correct, verified by an integration test running both connectors at once, with each guarantee independently asserted:
  - **SC-013a**: No candidate is double-imported across providers (a candidate is imported under exactly one provider of record).
  - **SC-013b**: No candidate is merged across providers — a record already keyed to one provider's external reference is never adopted by the other provider, even on a shared email.
  - **SC-013c**: No write-back is delivered to the wrong provider (a Lever candidate's activity never appears on a Greenhouse timeline and vice versa), and a candidate with no ATS link produces no write-back.
- **SC-014**: A Lever outage (errors/timeouts) does not degrade or delay a coexisting healthy Greenhouse connection's sync or write-back in the same workspace.
- **SC-015**: An inbound sync write that races a candidate erasure never re-populates name/email/phone on the erased record — verified by a concurrent erase-vs-sync integration test (the F40 resolve-then-active-state-guarded-write invariant), and disconnecting Lever cancels only Lever's pending write-backs while a coexisting Greenhouse queue is untouched.

## Assumptions

- **Authentication mechanism** *(resolved — Clarifications 2026-06-18)*: A workspace-scoped Lever API key (Data API, HTTP Basic with the key as username, empty password); no per-user OAuth flow or token refresh. This mirrors the F40 Greenhouse decision so the two connectors stay structurally identical. Live Lever API access requires a Lever plan with Data API access (a customer-side subscription); that cost is incurred only at the live-credential promotion step, not during this integration-pending build.
- **"Candidate" terminology**: Lever models a candidate-on-a-job as an "opportunity"; Cadence treats the opportunity (and its associated contact identity) as the imported candidate. The external reference is the Lever opportunity identifier so that a person appearing on multiple postings is kept as distinct pipeline entries, consistent with how F40 treats Greenhouse applications.
- **Storage lawful basis (data-protection posture)**: The customer workspace is assumed to be the controller for the imported candidate data and to warrant a lawful basis and a data-processing agreement covering Cadence as processor; Cadence stores the minimized field set (FR-029) encrypted at rest (FR-023), enforces the workspace retention clock (FR-027), and does not establish contact basis on import (FR-025). The legal basis is a customer responsibility, not a Cadence feature.
- **Direction of truth**: Lever is the source of truth for candidate identity, posting/requisition, and pipeline stage (read into Cadence). Cadence is the source of truth for scheduling activity (written back to Lever). Cadence does NOT push stage changes back to Lever in this feature.
- **Near-real-time mechanism** *(resolved — Clarifications 2026-06-18)*: The 5-minute freshness target is met by a scheduled reconciliation poll only (configurable interval ≤5 min), using the existing scheduled-task/checkpoint pattern on a Lever-specific checkpoint; no external queue/broker (constitution §IV) and no inbound webhook endpoint are introduced. The poll interval must be pinned in the plan against SC-001/SC-002.
- **Write-back triggers** *(resolved — Clarifications 2026-06-18)*: Identical to F40 — scheduling-link-sent, interview-confirmed/booked, rescheduled, cancelled, candidate-no-show (consuming the existing F23 no-show event), and interviewer-feedback-submitted (consuming the existing F32 event). Each is a separate idempotent write-back item routed to the candidate's source provider; the existing F40 write-back enqueue seams emit items for whichever provider a candidate belongs to.
- **Write-back idempotency anchor**: Lever provides no client-supplied dedup key for timeline activities. At-most-once is anchored in a local claim-before-send state transition (the F40/email-dispatch precedent); the honest bound is that a crash between provider-accept and recording delivery leaves an item in an in-flight state reconciled on restart rather than blindly re-sent.
- **Scope of "pipeline view"**: The pipeline view (F51) and core dashboard (F50) are not yet built. As in F40, connection health, sync status, and dead-letter visibility are surfaced on the dedicated **ATS integration settings/status screen**, now showing both providers. Imported stage/requisition data will later feed F50/F51; this feature does not build those views.
- **Stage representation** *(resolved — Clarifications 2026-06-18)*: The candidate stage is stored as the raw external label (free text) plus the external posting/requisition reference; no internal Cadence stage enum and no stage-mapping rules are introduced. Stage normalization is left to F50/F51.
- **Live credentials**: Live Lever credentials are not available during this feature; delivery and acceptance are against the integration-pending stub. The live-credential promotion (and its mandatory security re-review per the F40/F41 acceptance criteria) is tracked as a separate step.
- **Burst-50 via poll, not webhook**: The F40 backlog phrases the burst acceptance as "50 candidates via webhook." F41 deliberately carries this forward as a *poll-based* outcome (SC-002) consistent with the no-inbound-webhook decision (FR-011) — the substitution of "poll" for "webhook" is intentional, inherited from F40, not an omission.
- **Connection-uniqueness schema change is required (not avoidable)**: The shipped F40 schema enforces a unique `{workspaceId}` ATS-connection index (one connection per workspace). Multi-connector coexistence (FR-031, FR-033) requires migrating that to a unique `{workspaceId, provider}` index with a back-fill of `provider` on existing rows. This is the single unavoidable data-schema change; the plan MUST include the migration. FR-030/SC-009's "no business-logic change" claim is about business orchestration code, not the schema.
- **"Independent checkpoint/schedule" (FR-012/FR-033) is satisfied by per-connection isolation, ratified**: the implementation keeps the existing single `ats-sync-scan` scheduled scan that iterates every CONNECTED connection (both providers) inside a per-connection try/catch, so a Lever failure flips only the Lever connection and never blocks the Greenhouse iteration. This delivers the provider isolation FR-012/FR-022/FR-033 require *more simply* than two separate checkpoint documents/schedules; introducing separate per-provider checkpoints would be unjustified complexity (constitution §I). FR-012/FR-033's "independent" wording is met by isolation, not by literal schedule separation — a reviewed, intentional decision.
- **Hiring-Manager scoping inherited-as-deferred**: FR-028's HM requisition scoping mirrors F40, where it was explicitly deferred to F51 because no candidate→requisition→assignment link exists yet. F41 does not build new HM scoping; it only guarantees ATS import widens no role's visibility.
- **Maximal reuse of F40**: This feature reuses the existing `AtsConnector` contract, the candidate record and PII encryption, consent/erasure/retention, the scheduled-task/checkpoint pattern, the write-back outbox/retry/dead-letter and operator-notification mechanism, role-based access control, and the JDK-HTTP-server stub harness pattern, rather than introducing new infrastructure. F41's net-new code is the Lever provider adapter, the `LEVER` provider identity, the connection-uniqueness migration (FR-031), and the multi-connector coexistence guarantees (FR-033).

## Out of Scope

- Any ATS connector other than Lever and the already-shipped Greenhouse (Workable, SmartRecruiters, etc. are deferred to v2).
- CSV / standalone import (F42).
- The core dashboard (F50) and pipeline view (F51), including bulk actions and SLA colour-coding of imported candidates.
- Pushing pipeline-stage changes from Cadence back into Lever (only scheduling activity is written back).
- A configurable stage-mapping administration UI.
- Lever OAuth authentication (API-key Data API only this increment).
- Live production Lever credentials and the go-live cutover (separate, reviewed promotion step).
- Multi-account or agency multi-client connections for a single provider within one workspace (at most one connection per provider per workspace).
