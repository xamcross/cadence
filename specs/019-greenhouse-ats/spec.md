# Feature Specification: ATS Integration — Greenhouse (F40)

**Feature Branch**: `019-greenhouse-ats`
**Created**: 2026-06-18
**Status**: Draft
**Input**: User description: "F40 ATS Integration: Greenhouse - bidirectional sync of candidate/stage data and scheduling event write-back via the AtsConnector interface"

## Overview

Cadence sits between a company's Applicant Tracking System (ATS) and its calendar/email systems. This feature connects a workspace to **Greenhouse** so that candidate and pipeline-stage data flow **into** Cadence automatically, and Cadence's scheduling activity flows **back out** to the candidate's Greenhouse timeline. The goal is that a recruiter who lives in Greenhouse never has to copy a candidate into Cadence by hand, and never loses visibility of a scheduling action because it happened in a separate tool.

This is the first of the MVP ATS connectors (F40 Greenhouse, F41 Lever) and establishes the reusable connector contract that F41 will reuse. Per the constitution Dependency Policy, all provider-specific access is wrapped behind a single domain interface (`AtsConnector`) so business logic never depends on a provider SDK.

Because live Greenhouse credentials are not yet provisioned, this feature ships and is verified end-to-end against a **locally-runnable Greenhouse API stub explicitly labelled "integration-pending"**. Promotion to live credentials is a later, separately-reviewed step (see Assumptions and the Out of Scope section).

## Clarifications

### Session 2026-06-18

- Q: Credential model — API key vs OAuth (the backlog says both)? → A: **Workspace-scoped Greenhouse API key (Harvest-style)** — the cheapest and simplest to build (a write-only encrypted secret; no OAuth app registration or token-refresh machinery). Note: pulling candidate/stage data from Greenhouse requires a Greenhouse plan with API access regardless of API-key vs OAuth (there is no free candidate-pull path); that subscription is a **customer-side cost incurred only at live-credential promotion**, not during this integration-pending build.
- Q: How does inbound candidate/stage data stay fresh within 5 minutes? → A: **Scheduled poll only** — a `@Scheduled` reconciliation poll at a ≤5-minute interval against the authenticated Greenhouse API. **No inbound webhook endpoint is exposed** (smaller attack surface; the stored credential is the only inbound auth).
- Q: Which Cadence events are written back to the Greenhouse timeline this increment? → A: **Scheduling lifecycle + no-show + interviewer-feedback-submitted** — link sent, interview confirmed/booked, rescheduled, cancelled, no-show (F23), and feedback submitted (F32).
- Q: How is an imported candidate's pipeline stage represented? → A: **Raw external stage label (free text)** plus the external job/requisition reference — no internal stage enum or mapping in this feature.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Connect a workspace to Greenhouse (Priority: P1)

As an Admin, I can connect my workspace to Greenhouse by entering a Greenhouse API credential in Cadence's integration settings, so that Cadence can read my candidates and write scheduling activity back without any further manual setup.

**Why this priority**: Nothing else in this feature can happen until a connection exists. It is the entry point and the smallest independently demonstrable slice — connecting, validating, and showing connection health is itself useful (it tells an Admin whether the integration is live).

**Independent Test**: From the integration settings screen, enter a valid credential against the integration-pending stub, observe the connection move to a "connected" state with a last-verified timestamp; enter an invalid credential and observe a clear, non-leaking error state. No candidate import or write-back is required for this slice to deliver value.

**Acceptance Scenarios**:

1. **Given** an Admin on the integration settings screen with no existing connection, **When** they submit a valid Greenhouse credential, **Then** Cadence verifies the credential against Greenhouse, stores it encrypted, and displays the connection as "connected" with a last-verified timestamp.
2. **Given** an Admin submitting an invalid or revoked credential, **When** verification fails, **Then** Cadence does not store a usable connection, displays a clear "could not connect" message, and the failure is recorded without exposing the credential or any candidate data.
3. **Given** an existing connection, **When** a non-Admin (Recruiter, Hiring Manager, Interviewer, Read-only) attempts to view or change the connection, **Then** view access follows the configured role policy and change/credential access is refused for non-Admins.
4. **Given** an existing connection, **When** an Admin disconnects it, **Then** the stored credential is removed, no further sync or write-back occurs, and previously imported candidate records remain intact (subject to retention/erasure rules).

---

### User Story 2 - Candidates and stages flow into Cadence automatically (Priority: P1)

As a Recruiter using Greenhouse, I want candidates and their current pipeline stage to appear in Cadence automatically and stay up to date, so that I can schedule them in Cadence without re-entering anyone.

**Why this priority**: The core inbound value of the connector. Without imported candidates there is nothing to schedule from the ATS, and "new candidates appear within 5 minutes" is the headline outcome of the feature.

**Independent Test**: Seed the integration-pending stub with candidates on jobs/stages; trigger (or wait for) a sync; confirm the candidates, their external job/requisition, and current stage appear in Cadence and are visible on a sync/connection status surface within the target window. Update a candidate's stage in the stub; confirm Cadence reflects the new stage on the next sync.

**Acceptance Scenarios**:

1. **Given** a connected workspace and a candidate that exists on a job in Greenhouse, **When** a sync runs, **Then** the candidate appears in Cadence with their name/email (encrypted at rest), an external reference identifier, their associated job/requisition, and their current stage.
2. **Given** a candidate already imported into Cadence, **When** their stage changes in Greenhouse, **Then** a subsequent sync updates the stored stage on the existing Cadence record (no duplicate candidate is created).
3. **Given** a Greenhouse candidate whose email matches an existing Cadence candidate in the same workspace, **When** sync processes them, **Then** they are reconciled to the same Cadence record rather than duplicated.
4. **Given** a newly added candidate in Greenhouse, **When** the candidate is created or moved into a tracked stage, **Then** the candidate is reflected in Cadence within 5 minutes.
5. **Given** an imported candidate, **When** Cadence has not yet recorded email-contact consent/lawful basis for them, **Then** no outbound candidate email can be sent to them until consent is recorded through the existing consent flow (import does not by itself authorize contacting the candidate).

---

### User Story 3 - Scheduling activity is written back to Greenhouse (Priority: P2)

As a Recruiter, when a scheduling action happens in Cadence (e.g., a scheduling link is sent, an interview is booked, rescheduled, or cancelled), I want that action recorded on the candidate's Greenhouse timeline, so that anyone working in Greenhouse sees the up-to-date scheduling state without switching tools.

**Why this priority**: This is the outbound half of "bidirectional" and the differentiating value, but it depends on a connection (US1) and on candidates existing (US2) and on Cadence's existing scheduling flows producing events. It is independently demonstrable once a candidate is mapped.

**Independent Test**: For a mapped candidate, trigger a Cadence scheduling lifecycle event; confirm a corresponding activity/note is recorded against the candidate in the integration-pending stub, with no candidate free-text leaking into logs.

**Acceptance Scenarios**:

1. **Given** a mapped candidate, **When** a Cadence event in the write-back set occurs (link sent, interview confirmed, rescheduled, cancelled, no-show, or feedback submitted), **Then** a corresponding activity is written to that candidate's Greenhouse timeline.
2. **Given** a write-back is attempted, **When** it succeeds, **Then** Cadence records that the event was delivered so the same event is not written twice (idempotent write-back).
3. **Given** a candidate who has been erased in Cadence, **When** a write-back would otherwise occur for them, **Then** no personal data is sent and the action follows the erasure rules (the candidate's data is not re-exposed to the ATS).

---

### User Story 4 - Resilience when Greenhouse is unavailable (Priority: P2)

As a Recruiter, when Greenhouse is temporarily unreachable, I want Cadence to keep my scheduling actions safe and tell me the integration is degraded, so that no activity is silently lost and I know when sync is behind.

**Why this priority**: Email is the sole candidate channel and the ATS timeline is a system-of-record expectation; a dropped write-back is a trust failure. Degraded-mode handling is required for the connector to be production-credible, but it builds on US1–US3.

**Independent Test**: Make the integration-pending stub return errors/timeouts; perform a scheduling action; confirm the write-back is queued (not dropped), a degraded-state indicator is shown on the integration status surface, and the queued write-back is delivered automatically once the stub recovers.

**Acceptance Scenarios**:

1. **Given** Greenhouse is unreachable, **When** a write-back is attempted, **Then** the write-back is persisted to a retry queue (not dropped) and is retried automatically.
2. **Given** a queued write-back, **When** Greenhouse recovers, **Then** the write-back is delivered within 15 minutes of the next retry cycle and removed from the queue.
3. **Given** Greenhouse is unreachable during an inbound sync, **When** the sync cannot complete, **Then** the failure is recorded (referencing internal identifiers only), the integration status surface shows a degraded indicator, and the next successful sync reconciles any missed changes.
4. **Given** repeated failures that exceed the retry policy, **When** a write-back cannot be delivered, **Then** it is moved to a dead-letter state visible to an Admin rather than being discarded, and an operator is notified.

---

### Edge Cases

- **Invalid or revoked credential mid-life**: a previously working connection starts returning authorization errors → connection is flagged as needing re-authorization; sync/write-back pause; Admin is prompted to re-enter the credential. No candidate data is exposed in the error.
- **Duplicate identity**: the same external candidate is seen twice (overlapping or re-run sync) → exactly one Cadence record results; processing is idempotent. Two *distinct* external candidates that share an email are kept as two records (external reference is authoritative; email never merges across distinct external references — see FR-008).
- **Unusual stage label**: any Greenhouse stage label is stored as-is (free text); the candidate is imported regardless of label and is never dropped for an unrecognized stage.
- **Candidate erased in Cadence, then a Greenhouse change arrives**: the erased candidate's personal fields are not re-populated from the ATS; the inbound change is handled without resurrecting erased PII.
- **Candidate withdrawn/rejected/deleted in Greenhouse**: Cadence reflects the terminal state on its record without deleting audit history.
- **No inbound endpoint**: there is no inbound webhook; all inbound data is pulled by the authenticated scheduled poll, so a forged/unauthenticated push cannot inject candidate data. Freshness is bounded by the poll interval (≤5 min).
- **Burst load**: 50 candidates appearing between polls are all imported on the next poll within the target window without loss.
- **Provider rate limiting**: Greenhouse throttles requests → Cadence backs off and retries within policy rather than failing the sync outright.
- **Concurrent / overlapping sync runs**: two sync cycles overlapping must not double-import or corrupt records.
- **Retention conflict**: an imported candidate older than the workspace retention period is subject to the existing retention flagging/blocking rules.

## Requirements *(mandatory)*

### Functional Requirements

**Connection & configuration**

- **FR-001**: The system MUST allow an Admin to connect a workspace to Greenhouse by providing a Greenhouse API key (Harvest-style, workspace-scoped), stored as a write-only encrypted secret, scoped to one connection per workspace. (No OAuth authorization flow or token refresh is required in this feature.)
- **FR-002**: The system MUST verify a submitted credential against Greenhouse before treating the connection as active, and MUST surface a clear connection state (connected / needs re-authorization / error / integration-pending) with a last-verified timestamp.
- **FR-003**: The system MUST store the Greenhouse credential encrypted at rest and MUST never return it in any response, log, audit entry, sync-failure record, or dead-letter record. The credential MUST be managed as a write-only secret (a connection-status boolean may be exposed, never the secret). On a verification or sync failure, any provider error response MUST be reduced to a status/category code before being persisted or surfaced; the raw provider response body and any echoed credential or authorization header MUST NOT be stored or shown verbatim.
- **FR-004**: The system MUST restrict creating, changing, and removing the connection (and any access to the credential) to the Admin role; non-Admin access to the credential MUST be refused. Non-Admin roles permitted to use the integration (Recruiter) MAY view connection *health* (state + timestamps, never the secret); Hiring Manager, Interviewer, and Read-only roles have no connection-management access.
- **FR-005**: The system MUST allow an Admin to disconnect the integration, after which no further sync or write-back occurs, the stored credential is destroyed, and any pending (not-yet-delivered) write-back items for that workspace are cancelled; previously imported candidate records are retained subject to retention/erasure rules.

**Inbound sync (candidate & stage data)**

- **FR-006**: The system MUST import candidates from the connected Greenhouse account into the workspace, including each candidate's identity (name, email), an external reference identifier, the associated job/requisition, and the current pipeline stage.
- **FR-007**: The system MUST keep imported candidates' stage up to date as it changes in Greenhouse, updating the existing Cadence record without creating duplicates.
- **FR-008**: The system MUST reconcile inbound candidates to existing Cadence records using the external reference identifier as the authoritative key. A secondary match by email (via the keyed email hash, never the encrypted email value) within the same workspace applies ONLY when no external reference is yet recorded on an existing record; two distinct external references that happen to share an email MUST NOT be merged into one record. Reconciliation MUST be idempotent so re-imports and overlapping syncs do not create duplicates.
- **FR-009**: The system MUST reflect a newly added or newly stage-changed Greenhouse candidate in Cadence within 5 minutes of the change, achieved by a scheduled reconciliation poll running at a configurable interval of at most 5 minutes.
- **FR-010**: The system MUST store and retain each candidate's stage as the raw external stage label (free text) exactly as Greenhouse reports it, without requiring a mapping to any internal Cadence stage; a candidate MUST NOT be dropped because their stage is unrecognized.
- **FR-011**: Inbound sync MUST pull candidate/stage data from the authenticated Greenhouse API (the stored API key is the inbound auth). The system MUST NOT expose any unauthenticated inbound endpoint for ATS data ingestion (no inbound webhook in this feature); inbound data is never accepted from an unauthenticated caller.
- **FR-012**: The system MUST run inbound sync without a queue broker or additional infrastructure service, using the workspace's scheduled-task / checkpoint pattern with idempotent, missed-fire-safe behavior.

**Outbound write-back (scheduling activity)**

- **FR-013**: The system MUST write a corresponding activity to the candidate's Greenhouse timeline when any of these Cadence events occurs for a mapped candidate: scheduling link sent, interview confirmed/booked, rescheduled, cancelled, candidate no-show (F23), and interviewer feedback submitted (F32).
- **FR-014**: The system MUST make write-back idempotent so that the same Cadence event is not recorded more than once on the Greenhouse timeline, even across retries or restarts.
- **FR-015**: The system MUST NOT write back any personal data for a candidate who is in an erased state, MUST cancel/sweep any pending write-back items for an erased candidate, and MUST follow the erasure rules so erased data is not re-exposed to the ATS. Every inbound update/reconcile write MUST be guarded so it applies only while the candidate record is in the active (non-erased) state — an inbound change MUST NOT re-populate name/email/phone on an erased record (a non-PII stage-label update is permitted); a sync read followed by a write that races an erasure MUST resolve in favor of erasure (atomic active-state-guarded write).

**Resilience & degraded mode**

- **FR-016**: The system MUST persist any write-back that cannot be delivered (because Greenhouse is unreachable, throttled, or erroring) to a durable retry queue rather than dropping it.
- **FR-017**: The system MUST automatically retry queued write-backs with backoff, delivering a recoverable write-back within 15 minutes of provider recovery.
- **FR-018**: The system MUST move a write-back that exhausts the retry policy to a dead-letter state that is visible to an Admin and notifies an operator, rather than discarding it.
- **FR-019**: The system MUST surface a degraded-state indicator on the ATS integration settings/status screen whenever sync or write-back is failing or behind, referencing internal identifiers only (never PII or provider error bodies).
- **FR-020**: The system MUST back off and retry (within policy) when Greenhouse rate-limits requests, rather than failing the entire sync.

**Security, privacy & auditing**

- **FR-021**: The system MUST store imported candidate personal data (name, email, phone) encrypted at rest, consistent with the workspace's existing candidate data protection.
- **FR-022**: The system MUST NOT write candidate personal data or free-text field values (name, email, phone, stage label) to application logs at any level; logs MUST reference only internal candidate identifiers and the opaque external reference identifier.
- **FR-023**: Importing a candidate MUST NOT by itself establish lawful basis to contact the candidate; outbound candidate email remains gated by the existing consent/lawful-basis checks until basis is recorded.
- **FR-024**: The system MUST record, for each connection/sync/write-back event, at minimum: the event outcome (success/failure/category), a timestamp, the actor or trigger, counts processed/created/updated (for sync), and the internal candidate id plus opaque external reference (for per-candidate events) — and MUST NOT record candidate PII or the credential in these records.
- **FR-025**: An imported candidate MUST be subject to the same right-to-erasure and retention enforcement as any other Cadence candidate.
- **FR-028**: Imported candidates and their associated requisitions MUST inherit the same role-based access control and Hiring-Manager requisition scoping as natively-created candidates. Importing a candidate via the ATS MUST NOT widen any role's candidate visibility (no role gains blanket access to the imported pipeline merely because it arrived from the ATS).
- **FR-029**: The connector MUST request and retain ONLY the enumerated fields (candidate name, email, phone, external reference, associated job/requisition, current stage). It MUST NOT import or store resumes/attachments, recruiter notes, free-text custom fields, social profiles, or demographic / equal-opportunity (EEO/EEOC) data (data minimization; avoid ingesting special-category data with no lawful basis).

**Provider abstraction & integration-pending delivery**

- **FR-026**: All Greenhouse-specific access MUST be encapsulated behind a single provider-agnostic connector contract such that workspace/business logic depends only on the contract, not on any Greenhouse-specific client — enabling F41 (Lever) and future connectors to reuse it and a provider to be swapped without changing business logic.
- **FR-027**: The feature MUST be deliverable and verifiable end-to-end against a locally-runnable Greenhouse API stub that is explicitly labelled "integration-pending"; promotion to live Greenhouse credentials is a separate, explicitly-reviewed step and MUST NOT be implied as complete by this feature.

### Key Entities *(include if feature involves data)*

- **ATS Connection**: One per workspace. Represents the link to Greenhouse: provider identity, encrypted API key (write-only secret), connection state (connected / needs re-authorization / error / integration-pending), last-verified and last-sync timestamps, and the poll interval/cursor. Owned/managed by Admin.
- **Imported Candidate (extension of the existing Candidate)**: Adds the link to the ATS — an opaque external reference identifier (the authoritative reconciliation key), the source provider, the associated external job/requisition, and the current stage as a raw free-text label. Personal fields and the stage label remain encrypted/no-log and subject to consent/erasure/retention.
- **Requisition / Job (external)**: The Greenhouse job a candidate is associated with — external identifier plus a human-readable title — used to group candidates and (later) to scope role-based visibility.
- **Stage**: The candidate's current pipeline position as known to Greenhouse, stored as a raw free-text label (no internal stage taxonomy).
- **Write-Back Item**: A pending or completed outbound activity tied to a Cadence scheduling event and a mapped candidate, carrying a delivery state (pending / delivered / dead-letter), attempt count, and an idempotency marker so the same event is delivered at most once.
- **Sync Run record**: A record of an inbound sync cycle — start/finish timestamps, candidates processed/created/updated, and failure summary (no PII) — used to drive the status surface and audit.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A candidate newly created or moved into a tracked stage in Greenhouse appears (or updates) in Cadence within **5 minutes** of the change.
- **SC-002**: A burst of **50 candidates** arriving at once is fully processed into Cadence within **5 minutes**, with every candidate imported exactly once (no duplicates, no losses).
- **SC-003**: A Cadence scheduling lifecycle event for a mapped candidate results in exactly one corresponding Greenhouse timeline activity under normal operation and across retries/restarts. (Honest bound: because Greenhouse provides no client-supplied dedup key for timeline activities, at-most-once is enforced by a local claim-before-send transition; a crash in the narrow window between provider-accept and recording delivery is reconciled on restart rather than blindly re-sent — see Assumptions.)
- **SC-004**: When Greenhouse is unreachable, **100%** of attempted write-backs are queued (none dropped), and a recoverable write-back is delivered within **15 minutes** of provider recovery.
- **SC-005**: **Zero** occurrences of candidate name, email, phone, stage label, or the Greenhouse credential appear in application logs across the full connect → sync → write-back → failure flow (verified by log scan).
- **SC-006**: Reading the stored connection record directly shows the credential only as ciphertext; the credential is never present in any API response.
- **SC-007**: A simulated mid-cycle restart during sync or write-back does not produce duplicate imports or duplicate timeline activities.
- **SC-008**: An imported candidate cannot be sent an outbound email until email-contact consent/lawful basis is recorded — verified by attempting a send and observing it blocked.
- **SC-009**: Swapping the provider implementation requires changing only the connector adapter binding — no change to workspace/business logic — demonstrating the abstraction holds for the future Lever connector.
- **SC-010**: An invalid or revoked credential never yields an active connection and never exposes candidate data or the credential value in the resulting error.
- **SC-011**: An Admin can see, at any time, the current integration state (connected / needs re-authorization / error / degraded / integration-pending), the last successful sync time, and any write-backs stuck in dead-letter.
- **SC-012**: Reading an imported candidate document directly shows name, email, and phone only as ciphertext (encrypted at rest), symmetric to SC-006 for the credential.

## Assumptions

- **Authentication mechanism** *(resolved — Clarifications 2026-06-18)*: A workspace-scoped Greenhouse API key (Harvest-style, write-only secret); no per-user OAuth flow or token refresh. This is the cheapest and simplest model to build. Any live Greenhouse API access requires a Greenhouse plan with API access (a customer-side subscription, no free candidate-pull path exists); that cost is incurred only at the live-credential promotion step, not during this integration-pending build.
- **Storage lawful basis (data-protection posture)**: The customer workspace is assumed to be the controller for the imported candidate data and to warrant it has a lawful basis to process that data and a data-processing agreement covering Cadence as processor; Cadence's responsibility is to store the minimized field set (FR-029) encrypted at rest (FR-021), enforce the workspace retention clock on imported records (FR-025), and not establish contact basis on import (FR-023). The legal basis itself is a customer responsibility, not a Cadence feature.
- **Direction of truth**: Greenhouse is the source of truth for candidate identity, job/requisition, and pipeline stage (read into Cadence). Cadence is the source of truth for scheduling activity (written back to Greenhouse). Cadence does NOT push stage changes back to Greenhouse in this feature.
- **Near-real-time mechanism** *(resolved — Clarifications 2026-06-18)*: The 5-minute freshness target is met by a scheduled reconciliation poll only (configurable interval ≤5 min), using the existing scheduled-task/checkpoint pattern; no external queue/broker (constitution §IV) and no inbound webhook endpoint are introduced. The poll interval must be pinned in the plan against SC-001/SC-002 (e.g., a ≤5-min interval with per-poll processing comfortably inside the window for a 50-candidate batch).
- **Write-back triggers** *(resolved — Clarifications 2026-06-18)*: The write-back set is scheduling-link-sent, interview-confirmed/booked, rescheduled, cancelled, candidate-no-show (consuming the existing F23 no-show event), and interviewer-feedback-submitted (consuming the existing F32 event). Each is a separate idempotent write-back item.
- **Write-back idempotency anchor**: Greenhouse provides no client-supplied dedup key for timeline activities (unlike the calendar providers' deterministic event ids). At-most-once is therefore anchored in a local claim-before-send state transition; the documented honest bound is that a crash between provider-accept and recording delivery leaves an item in an in-flight state that is reconciled on restart rather than blindly re-sent (the existing email-dispatch "sent-unconfirmed" reaper precedent).
- **Scope of "pipeline view"**: The F40 backlog mentions surfacing the degraded banner "in the pipeline view"; the pipeline view (F51) and core dashboard (F50) are not yet built. For this feature the connection health, sync status, and dead-letter visibility are surfaced on a dedicated **ATS integration settings/status screen**. Stage/requisition data imported here will later feed F50/F51; this feature does not build those views.
- **Stage representation** *(resolved — Clarifications 2026-06-18)*: The candidate stage is stored as the raw external label (free text) plus the external job/requisition reference; no internal Cadence stage enum and no stage-mapping rules are introduced in this feature. Stage normalization is left to F50/F51.
- **Live credentials**: Live Greenhouse credentials are not available during this feature; delivery and acceptance are against the integration-pending stub. The live-credential promotion (and its mandatory security re-review per the F40 acceptance criteria) is tracked as a separate step.
- **Single connector**: Only Greenhouse is in scope here; Lever (F41) reuses the connector contract this feature establishes; CSV import (F42) is independent.
- **Existing platform reuse**: This feature reuses the existing candidate record, candidate PII encryption, consent/erasure/retention, scheduled-task/checkpoint pattern, dead-letter/operator-notification mechanism, and role-based access control rather than introducing new infrastructure.

## Out of Scope

- Lever and any ATS connector other than Greenhouse (F41 and later).
- CSV / standalone import (F42).
- The core dashboard (F50) and pipeline view (F51), including bulk actions and SLA colour-coding of imported candidates.
- Pushing pipeline-stage changes from Cadence back into Greenhouse (only scheduling activity is written back).
- A configurable stage-mapping administration UI.
- Live production Greenhouse credentials and the go-live cutover (separate, reviewed promotion step).
- Multi-account or agency multi-client Greenhouse connections per workspace (one connection per workspace).
