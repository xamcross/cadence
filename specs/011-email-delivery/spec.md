# Feature Specification: Email Delivery Channel (F22)

**Feature Branch**: `011-email-delivery`  
**Created**: 2026-06-16  
**Status**: Draft  
**Input**: Backlog F22 — "Transactional email via the `EmailSender` interface. Provider wrapped behind the interface. Scheduled reminders use `@Scheduled` + `SchedulerCheckpoint` (F00.2). The `EmailSender` checks consent and erasure state before every dispatch."

## Overview

Cadence's outbound communications all flow through a single email delivery channel. Today the channel is a no-op placeholder; this feature replaces it with a real, reliable transactional-email path that:

- renders an approved template (F21) for a specific candidate and sends it through the workspace-configured email provider (F03),
- refuses to dispatch to any candidate who has not consented or whose data is erased/over-retention (F04),
- never duplicates a send, even across process restarts (the F00.2 idempotency contract),
- records delivery failures and hard bounces against the candidate so a recruiter can act on them,
- and exposes a reusable, idempotent, missed-fire-safe scheduled-dispatch mechanism that later features (no-show defense, SLA nudges, feedback reminders) build their reminders on.

Email is the **sole** outbound channel for the MVP (no SMS/WhatsApp), so reliability, consent-safety, and bounce visibility on this one channel are critical.

## Scope Boundaries

**In scope (F22):**
- The real `EmailSender` implementation behind the existing interface (provider wrapped; swappable by bean replacement).
- A pre-dispatch consent/erasure gate on every candidate-addressed send.
- Per-dispatch idempotency (no duplicate sends; restart-safe).
- A persisted dispatch record (outbox) capturing every attempt, its result, and delivery/bounce status.
- Intake of provider-reported delivery outcomes (hard/soft bounce, delivery confirmation) and surfacing hard bounces to the recruiter.
- A dead-letter record + recruiter/in-app notification when a dispatch cannot be completed.
- A reusable scheduled-dispatch mechanism (using the F00.2 `SchedulerCheckpoint` pattern) that other features schedule reminders through.

> **Note on the existing seam**: the current `EmailSender` interface (a no-op) does not carry the workspace, event type, or scheduled time that the consent gate and idempotency key require. F22 therefore widens the dispatch request (interface/value-object change) and enforces the consent gate in the dispatch service — "reuse the interface" means the same abstraction, not the same method signature. This is a deliberate cutover, detailed in `plan.md`.
>
> **Supersedes** the F22 backlog acceptance-criterion field shape (`{ consentRecorded: true, erasureStatus: false }`): consent/erasure is evaluated through the F04 `ContactPermissionGate` decision (FR-005), which broadens refusal reasons (over-retention, no-basis, unavailable).

**Out of scope (owned elsewhere):**
- Concrete reminder business rules and their trigger times — 24 h / 1 h interview reminders (F13/F23), SLA nudge drafts (F31), feedback-form reminder escalation (F32). F22 provides the channel and the scheduled-dispatch pattern; those features decide *what* and *when*.
- Template authoring, merge-field rendering, and locking (F21, already shipped — F22 consumes the rendered output).
- Recording of consent and erasure state (F04 — F22 only *reads* the decision via the existing gate).
- Email-provider/domain/API-key configuration UI and storage (F03 — F22 *reads* it).
- One-click recruiter approval UX for SLA drafts (F31) and the pipeline view that displays bounce flags (F51 surfaces them; F22 records them).
- SMS/WhatsApp channels (deferred to v1.5).

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Reliable transactional send with consent enforcement (Priority: P1)

When the system has a reason to email a candidate (e.g., a calendar confirmation, an invitation, a recruiter-approved message), it renders the appropriate template for that candidate and delivers it through the configured provider — but only if the candidate is currently contactable.

**Why this priority**: This is the core capability. Every other Cadence communication flow (scheduling confirmations, reminders, status nudges) is blocked until a real, consent-safe send exists. It is also the primary GDPR control surface for outbound comms — a send to an erased or non-consenting candidate is a compliance breach.

**Independent Test**: Trigger a send for a consenting candidate and confirm the message reaches a test inbox/mock with the correct rendered content and recipient; trigger a send for a non-consenting/erased candidate and confirm no message leaves the system and the refusal is recorded.

**Acceptance Scenarios**:

1. **Given** a candidate with recorded consent, an active (non-erased) record, and a configured workspace email provider, **When** the system dispatches a template to that candidate, **Then** the rendered email is delivered to the candidate's address and a dispatch record marks it sent.
2. **Given** a candidate whose data has been erased, **When** the system attempts to dispatch any email to that candidate, **Then** no message is sent, the attempt is refused and recorded with the reason, and a recruiter-visible failure is raised.
3. **Given** a candidate with no recorded lawful basis / withdrawn consent / flagged as over-retention, **When** the system attempts a dispatch, **Then** the send is refused for that reason and no message leaves the system.
4. **Given** the workspace has not configured an email provider, **When** a dispatch is attempted, **Then** the send fails cleanly with a recorded, recruiter-visible reason rather than silently dropping the message.

---

### User Story 2 - No duplicate sends, even across restarts (Priority: P1)

A candidate must never receive two copies of the same message because of a retry, a concurrent trigger, or a process crash mid-send.

**Why this priority**: Duplicate confirmations or reminders directly erode candidate trust and are a known failure mode of restart-prone scheduled jobs. The backlog and constitution (F00.2) mandate idempotent, missed-fire-safe dispatch as a hard requirement.

**Independent Test**: Issue the same logical dispatch twice (and simulate a crash between "about to send" and "marked sent"), then confirm exactly one message was delivered.

**Acceptance Scenarios**:

1. **Given** a logical message identified by its candidate, event type, and scheduled time, **When** the same logical message is dispatched more than once, **Then** exactly one email is delivered and subsequent attempts are no-ops.
2. **Given** a dispatch that is interrupted after the provider accepted the message but before the system recorded success, **When** the system restarts and replays in-flight work, **Then** the candidate does not receive a second copy.
3. **Given** two concurrent triggers for the same logical message, **When** both run, **Then** exactly one wins the send and the other observes the message as already handled.

---

### User Story 3 - Failure and bounce visibility for recruiters (Priority: P2)

When a message cannot be delivered — the provider rejects it, the address hard-bounces, or the dispatch is refused on consent grounds — a recruiter can see that it happened and act, because email is the only channel and a silent failure means a candidate is silently lost.

**Why this priority**: Without bounce/failure visibility, a candidate whose email is wrong or who never received an invite simply disappears from the process with no signal. The backlog flags bounce handling as critical because email is the sole channel.

**Independent Test**: Force a hard bounce / provider rejection / consent refusal for a candidate and confirm the candidate record reflects the failure and the recruiter receives an in-app notification — with no PII written to logs.

**Acceptance Scenarios**:

1. **Given** a dispatch whose recipient address hard-bounces (reported by the provider), **When** the bounce is received, **Then** the hard bounce is recorded against the candidate, the candidate is flagged so further automatic sends are suppressed pending recruiter action, and the recruiter receives an in-app notification.
2. **Given** a dispatch that the provider rejects synchronously (e.g., malformed recipient, provider 5xx after retries), **When** the failure is final, **Then** a dead-letter record is written (candidate internal ID only, no PII) and a recruiter-visible alert is raised.
3. **Given** a transient provider error (rate limit / temporary 5xx), **When** the system retries with backoff, **Then** a later success is recorded as a single delivered message and no dead-letter or duplicate results.
4. **Given** any failure path, **When** the failure is logged, **Then** no candidate email address, name, phone, or message body appears at any log level.

---

### User Story 4 - Reusable scheduled-dispatch mechanism (Priority: P2)

Later features need to send reminders at a future time (e.g., "24 hours before the interview"). F22 provides a single, idempotent, missed-fire-safe way to schedule and run those sends so each feature does not reinvent a scheduler.

**Why this priority**: This unblocks F23 (no-show defense), F31 (SLA nudges), and F32 (feedback reminders) and ensures they all inherit the same idempotency and missed-fire recovery guarantees rather than each implementing them inconsistently.

**Independent Test**: Schedule a dispatch for a future time, advance the clock past it, and confirm it sends exactly once; simulate a missed firing window (downtime spanning the scheduled time) and confirm it still sends exactly once on recovery.

**Acceptance Scenarios**:

1. **Given** a dispatch scheduled for a future time, **When** that time passes and the scheduled task runs, **Then** the message is dispatched exactly once through the same consent-checked, idempotent path as immediate sends.
2. **Given** the system was down across a scheduled dispatch time, **When** it restarts, **Then** the missed dispatch is detected and sent once (not skipped, not duplicated).
3. **Given** a scheduled dispatch whose candidate became non-contactable (erased/withdrawn) between scheduling and firing, **When** the task runs, **Then** the send is refused at fire time and recorded, not delivered on stale state.

---

### User Story 5 - Provider portability (Priority: P3)

An operator can switch the underlying email provider without changing business logic.

**Why this priority**: Required by the constitution's interface-abstraction rule and the backlog ("swapping provider requires only a bean change"). It is P3 because it is a structural property verified by test rather than an end-user-visible flow.

**Independent Test**: Replace the provider implementation behind the interface and confirm all sending behaviour is unchanged with no edits to calling services.

**Acceptance Scenarios**:

1. **Given** business services that trigger sends only through the delivery interface, **When** the provider implementation is swapped, **Then** no calling service code changes and all dispatch behaviour (consent gate, idempotency, dead-letter, bounce intake) is preserved.

---

### Edge Cases

- **Send refused mid-flight by consent change**: a message scheduled while a candidate was contactable but fired after erasure/withdrawal must be refused at fire time (state is re-evaluated at dispatch, never trusted from scheduling time).
- **Provider accepts then async-bounces**: a message the provider initially accepts but later reports as a hard bounce must update the dispatch record and flag the candidate (delivery acceptance ≠ delivery success).
- **Soft bounce vs hard bounce**: a soft (transient) bounce must not permanently flag the candidate or suppress future sends the way a hard bounce does.
- **Duplicate provider bounce/delivery notifications**: repeated or out-of-order provider callbacks for the same message must be idempotent (no double-flagging, no double-notification).
- **Unverified/forged provider callback**: an inbound delivery/bounce notification that cannot be authenticated as coming from the configured provider must be rejected, not allowed to flag arbitrary candidates.
- **Missing/changed merge data at send time**: a template that fails to render for a candidate must not send a broken message; it fails the dispatch with a recorded reason.
- **Concurrent immediate + scheduled trigger for the same logical message**: must collapse to exactly one delivered message.
- **Provider total outage**: repeated transient failures must exhaust bounded retries, dead-letter, and alert — never loop indefinitely or block the triggering request.
- **Restart during the dead-letter/notification step**: failure recording itself must be safe to replay.

## Requirements *(mandatory)*

### Functional Requirements

**Dispatch & provider abstraction**
- **FR-001**: The system MUST deliver transactional emails to candidates through a single delivery abstraction; no business/service code may talk to an email provider directly.
- **FR-002**: The system MUST render the approved template (F21) for the target candidate and send the rendered subject/body, using the workspace-configured email provider and sending domain (F03).
- **FR-003**: The provider integration MUST be replaceable without changing any calling service code (provider-swap = bean replacement only).
- **FR-004**: When no email provider is configured for the workspace, the system MUST fail the dispatch with a recorded, recruiter-visible reason and MUST NOT silently drop the message.

**Consent & erasure gate (GDPR)**
- **FR-005**: Before every candidate-addressed dispatch, the system MUST consult the contact-permission decision (F04) and MUST send only when the decision permits.
- **FR-006**: A dispatch refused by the permission gate MUST NOT transmit any message and MUST record the refusal with its reason (no lawful basis, withdrawn, erased, over-retention, unavailable). An unavailable/unreadable candidate state (missing record or read error) MUST be treated as a refusal (fail-closed) — never a transmission and never an unhandled error that crashes the dispatcher.
- **FR-007**: The permission decision MUST be evaluated at the moment of dispatch (including for scheduled sends), never cached from an earlier point in the flow.
- **FR-008**: A refused dispatch MUST surface to the recruiter (dead-letter + in-app notification) so the candidate is not silently dropped.

**Idempotency & reliability**
- **FR-009**: Each logical message MUST be identified by a stable idempotency key (candidate + event type + scheduled time) such that repeated or concurrent dispatch attempts of the same logical message result in exactly one delivered email. For an immediate (non-scheduled) send the "scheduled time" component is the caller-supplied logical send instant for that trigger, so two distinct triggers never collide and one trigger never bypasses dedup.
- **FR-010**: The system MUST be safe against process restart mid-dispatch: an interrupted send MUST NOT cause a duplicate on replay, and a send the provider already accepted MUST NOT be re-sent. Where the chosen transport exposes no client-set idempotency token, the exactly-once guarantee degrades to at-least-once with key-based de-duplication (a claimed-but-unconfirmed send is reconciled, never blindly re-sent); the exact mechanism and any residual duplicate-window is documented in `plan.md`.
- **FR-011**: On transient provider errors (rate limiting / temporary failures), the system MUST retry with bounded exponential backoff and jitter before giving up. The maximum attempts/elapsed MUST be a configurable value with a stated default (consistent with the F10 calendar adapter's bounded-retry precedent) so the cap is test-assertable.
- **FR-012**: When dispatch ultimately fails after retries, the system MUST write a dead-letter record and raise a recruiter-visible alert through the single dead-letter+notification mechanism (shared with FR-008), and MUST NOT block, fail, or roll back the triggering request — candidate-addressed dispatch is asynchronous relative to the trigger (or, if run synchronously, its failure is caught and does not undo the trigger).

**Dispatch record / outbox**
- **FR-013**: The system MUST persist a dispatch record for every attempt capturing at minimum: candidate (internal ID), message/event type, idempotency key, current status (e.g., pending, in-flight/claimed, sent, failed, bounced, refused), and timestamps. The record MUST store the candidate **internal ID only** — never the recipient email address, name, rendered subject, or message body; the recipient is resolved from the candidate record at send time and never persisted on the outbox row. The record MUST never be logged with PII.
- **FR-014**: Dispatch-record status transitions — including consent/erasure refusals (FR-006) and hard-bounce/dead-letter events — MUST be appended to the candidate-keyed, append-only audit trail of communications (supporting FR-18 in the product spec).

**Delivery outcome & bounce handling**
- **FR-015**: The system MUST accept provider-reported delivery outcomes (delivery confirmation, hard bounce, soft bounce) and update the corresponding dispatch record accordingly.
- **FR-016**: Inbound provider delivery/bounce notifications MUST be authenticated (provider signature/secret) AND resolve to the owning workspace and a known dispatch record before any state change; an unauthenticated, cross-workspace, or unmatched notification MUST be rejected and MUST NOT alter any candidate state. Any verification secret/signing key MUST be stored encrypted-at-rest and never logged (the F03 write-only-credential discipline).
- **FR-017**: A hard bounce MUST be recorded against the candidate, MUST flag the candidate so that further automatic sends are suppressed (a subsequent send to a flagged candidate becomes a recorded refusal per FR-006) pending recruiter action, and MUST raise an in-app recruiter notification. The bounce flag and any bounce metadata stored on the candidate are PII-adjacent fields subject to F04 erasure (purged on erasure). Clearing the flag is a recruiter action recorded in the candidate audit trail; whether F22 exposes the clear operation or only the flag state is resolved in `plan.md`.
- **FR-018**: A soft (transient) bounce MUST NOT permanently flag or suppress the candidate.
- **FR-019**: Provider notifications MUST be processed idempotently — duplicate or out-of-order callbacks for the same message MUST NOT double-flag the candidate or double-notify the recruiter.

**Scheduled dispatch**
- **FR-020**: The system MUST provide a scheduled-dispatch mechanism that runs future-dated sends through the same consent-checked, idempotent dispatch path as immediate sends.
- **FR-021**: The scheduled mechanism MUST use the shared scheduler-checkpoint pattern (F00.2): each run records its window, and missed firing windows (e.g., spanning downtime) MUST be detected and replayed exactly once on recovery.
- **FR-022**: The scheduled mechanism MUST be reusable by later features (no-show, SLA, feedback) without each feature re-implementing idempotency or missed-fire recovery.

**Logging & privacy**
- **FR-023**: No candidate email address, name, phone number, or message body may appear in application logs at any level; only internal opaque identifiers may be logged.
- **FR-024**: All dispatch, refusal, bounce, and dead-letter events MUST be observable for operators (metrics/structured logs) without exposing PII.

### Key Entities *(include if feature involves data)*

- **Email Dispatch Record (Outbox entry)**: One row per logical outbound message. Attributes: workspace, candidate internal ID, message/event type, idempotency key, status (pending → sent / failed / bounced / refused), attempt count, scheduled-for time, sent time, last-outcome reason, provider message reference (opaque). No message body, no recipient address beyond what dispatch requires, never logged with PII.
- **Delivery Outcome / Bounce event**: A provider-reported result linked to a dispatch record: type (delivered, hard bounce, soft bounce, complaint), received time, opaque provider reference. Drives candidate flagging on hard bounce.
- **Candidate (existing, F04)**: Read for the contact-permission decision; updated with a hard-bounce/undeliverable flag (and bounce metadata) that suppresses further automatic sends until a recruiter clears it.
- **Dead-letter record (existing, F00.2)**: Written on terminal dispatch failure; candidate internal ID only, PII-sanitized.
- **Scheduler checkpoint (existing, F00.2)**: Tracks the scheduled-dispatch task's last-run window for idempotency and missed-fire recovery.
- **Workspace email configuration (existing, F03)**: Read for provider/sending-domain/credentials; never returned or logged.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A triggered transactional email for a consenting candidate is delivered within 60 seconds of the trigger (measured against the test provider/SMTP sink for CI determinism).
- **SC-002**: 100% of dispatches to candidates in a non-contactable state (erased, withdrawn, over-retention, no basis, unknown) are blocked before transmission — zero such messages leave the system.
- **SC-003**: Under duplicate and concurrent triggers, and across a simulated mid-send crash, each logical message is delivered exactly once — verified by counting messages at the provider/sink, not by reading the outbox status (zero duplicates across the test matrix).
- **SC-004**: A simulated downtime spanning a scheduled dispatch time results in exactly one send on recovery (no missed send, no duplicate).
- **SC-005**: 100% of hard bounces *detectable by the chosen transport* and 100% of terminal dispatch failures are recorded against the candidate and surfaced to a recruiter via in-app notification (no silent loss). The transport's bounce-detection capability (async webhook vs synchronous-rejection-only) is fixed in `plan.md`.
- **SC-006**: Zero candidate PII (email, name, phone, body) appears in application logs across the full dispatch, retry, bounce, and dead-letter flow (verified by CI log scan).
- **SC-007**: Swapping the email-provider implementation behind the delivery interface requires zero edits to any triggering/business service (verified by an interface-level test).
- **SC-008**: An unauthenticated, cross-workspace, or unmatched inbound delivery/bounce notification alters no candidate state (rejected before any record update).
- **SC-009**: A duplicated or out-of-order authenticated hard-bounce/delivery callback for the same message produces exactly one candidate flag and one recruiter notification (idempotent intake).
- **SC-010**: A soft (transient) bounce does not flag or suppress the candidate, and a transient send error that later succeeds results in exactly one delivered message (no flag, no duplicate, no dead-letter).

## Assumptions

- **Transport choice is a plan-phase decision, kept out of this spec.** The backlog cites "Spring Mail + provider SDK (e.g., SendGrid/SES)"; the actual provider/transport (SMTP via the framework's mail support vs. a provider HTTP API) will be chosen in `plan.md` under the constitution's dependency policy, consistent with F03 already modelling sending-domain + provider-API-key configuration. The spec stays provider-agnostic.
- **F22 ships the channel + the reusable scheduled-dispatch pattern, not concrete reminder business rules.** Specific reminder triggers and timings are owned by F13/F23/F31/F32; F22 is demonstrated end-to-end by at least one real trigger (e.g., a recruiter-approved message or a calendar confirmation send) plus the scheduled mechanism exercised with a test clock — no stub-only delivery (§II).
- **The existing seams are reused, not rebuilt**: the `EmailSender` interface (today a no-op), `ContactPermissionGate` (F04 decision), `SchedulerCheckpointService` and `DeadLetterService` (F00.2), and the F21 rendered-template output. F22 replaces the no-op with the real implementation.
- **Bounce/delivery feedback arrives via an authenticated inbound provider notification** (callback/webhook) in addition to synchronous send-time rejections; if the chosen transport offers no async notification channel, hard-bounce detection degrades to synchronous rejections only and that limitation is documented in `plan.md`.
- **The recruiter notification surface is in-app** (consistent with F22's dead-letter + in-app-notification acceptance criteria and the F51 pipeline view that displays bounce flags); no new notification channel is introduced.
- **Consent/erasure state is authoritative in F04** and read-only here; F22 adds no new consent semantics, only enforces the existing gate at dispatch time.
- **Standard MVP volume**: transactional/reminder email volume for a single workspace fits comfortably within a single backend instance using the `@Scheduled` + checkpoint pattern with no external queue broker (constitution: no new infrastructure service). A single active scheduler is assumed; even if two instances briefly overlap (rolling deploy), the per-message unique idempotency key (FR-009) is the real safety net that still prevents duplicate sends — leader election is out of scope.

## Dependencies

- **F00.2** — `SchedulerCheckpoint` idempotency pattern, `DeadLetterService`, structured PII-free logging.
- **F03** — workspace email-provider/sending-domain/credential configuration.
- **F04** — consent + erasure state and the `ContactPermissionGate` decision; candidate audit log.
- **F21** — template library + merge rendering (the rendered message F22 transmits).
- Consumers (later): **F13** (confirmation dispatch), **F23** (no-show reminders), **F31** (SLA nudges), **F32** (feedback reminders), **F51** (pipeline view surfaces bounce flags).
