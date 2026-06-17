# Feature Specification: SLA Nudge Engine

**Feature Branch**: `017-sla-nudge-engine`  
**Created**: 2026-06-17  
**Status**: Draft  
**Input**: User description: "checkout main, update from origin. take next unimplemented task from the backlog and write spec for it. review with multiple subagents" (resolves to **F31 — SLA Nudge Engine**, the next unimplemented feature in the backlog delivery sequence after F30)

## Overview

Cadence treats candidate communication as an SLA-managed pipeline, not an act of individual goodwill. The SLA Nudge Engine is the operational guardrail that makes candidate silence **visible, measurable, and fixable**: an Admin defines a maximum-silence rule (e.g. "no candidate goes more than 5 days without an update"), the system continuously detects candidates approaching or breaching that window, surfaces an amber/red indicator to recruiters, and — for a breaching candidate — drafts an honest holding/update message for the recruiter to send with one click. In the MVP, **nothing is sent automatically**: every drafted message waits for explicit recruiter approval (auto-send is deferred to v1.5).

This feature is within MVP scope (product spec §11 "basic SLA nudges (draft-for-approval only)"; FR-10; backlog F31). It depends only on already-delivered capabilities: the workspace SLA silence-window setting and time zone (F03), the candidate record with its consent/erasure/undeliverable state (F04), the email template library and merge renderer (F21), the consent/erasure-gated email delivery channel + in-app recruiter notification + dead-letter discipline (F22), the candidate status/outcome fields and publish timestamp (F30), and the shared `@Scheduled` + `SchedulerCheckpoint` idempotency/missed-fire pattern (F00.2).

> **Multi-role review applied (2026-06-17, two findings folded in)**: Business Analyst, Security/GDPR Lead, QA Lead, and Backend/DevOps Lead reviewed the draft. The Backend review found the BLOCKER that "last meaningful update" was *not* derivable from existing data — `lastContactAt` is written once at candidate creation and never advanced by any later event — so the original "reuses existing signals, no new tracking" assumption was false and breaches would never clear. This spec resolves it by mandating a denormalized last-meaningful-activity instant advanced at each qualifying write site (FR-005). Other folded-in findings: the send-time consent gate is the single authoritative suppression point and approval MUST route through the existing email-dispatch channel (FR-016/FR-023); erasure-invalidates-draft is an honest best-effort plus the authoritative send-time gate, not a false atomicity claim (FR-021); dead-letter/audit must receive a PII-free summary and the log-scan asserts the persisted artefacts (FR-025/SC-007); preview is a PII read that needs scoped-404 + no-store (FR-013); the draft is a new collection with a unique partial index for de-dup and an atomic CAS for approval (FR-015/FR-022, Key Entities); the amber margin and the null-/deactivated-recruiter fallback are pinned (FR-006/FR-012); drafting fires on breach not approach (FR-011); and per-signal breach-clearing is independently verified (SC-014).

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Admin defines the silence rule (Priority: P1)

An Admin opens workspace settings and sets the maximum silence window — the number of days a candidate may go without an update before the workspace considers them "in silence" and at risk of ghosting. This rule governs breach detection for the whole workspace.

**Why this priority**: The engine has no meaning without a configured threshold. This is the smallest independently shippable slice — it establishes the policy the rest of the feature enforces. (The workspace already carries a default silence window from F03; this story makes it the explicit, Admin-owned input to the nudge engine.)

**Independent Test**: As an Admin, set the silence window to N days and confirm it persists and survives restart; as a non-Admin, confirm the setting cannot be changed.

**Acceptance Scenarios**:

1. **Given** an Admin in workspace settings, **When** they set the maximum silence window to N days, **Then** the value is saved, audited, and used by subsequent breach detection.
2. **Given** a workspace with no explicitly configured silence window, **When** the engine runs, **Then** a documented sensible default applies (no candidate is left ungoverned).
3. **Given** a user without Admin rights, **When** they attempt to change the silence window, **Then** the request is refused server-side.
4. **Given** an Admin sets an out-of-range value (e.g. zero, negative, or absurdly large), **When** they save, **Then** the system rejects it with a clear validation message.

---

### User Story 2 - Recruiter sees who is in silence (Priority: P1)

A recruiter looks at their working view and immediately sees, per candidate, a communication-health indicator: **green** (within SLA), **amber** (approaching the silence threshold), or **red** (breached — overdue for an update). The recruiter can tell at a glance which candidates are at risk of being ghosted, without computing anything by hand.

**Why this priority**: Making silence visible is the core differentiator of the product ("candidates currently in silence" as a red-flag operational signal). This is the value even before any drafting happens — a recruiter who can *see* the breach can act on it. P1 and ships with Story 3.

**Independent Test**: Seed candidates with last-meaningful-activity timestamps straddling the threshold (well within, within the amber margin, and past the window) and confirm each surfaces the correct green/amber/red indicator, computed server-side, persisting across restart.

**Acceptance Scenarios**:

1. **Given** a candidate whose last meaningful activity is well within the silence window, **When** the recruiter views the candidate, **Then** the indicator is green (within SLA).
2. **Given** a candidate whose last meaningful activity falls within the amber margin of the window, **When** the recruiter views the candidate, **Then** the indicator is amber.
3. **Given** a candidate whose last meaningful activity is older than the silence window, **When** the recruiter views the candidate, **Then** the indicator is red (breached).
4. **Given** a candidate whose data has been erased or who is in a terminal outcome (hired/rejected), **When** the engine evaluates SLA, **Then** that candidate is not flagged as an active silence breach.
5. **Given** an SLA indicator was red, **When** a qualifying activity occurs (an outbound candidate communication is sent, a candidate status is published/updated, or an interview is booked/rescheduled), **Then** the last-meaningful-activity instant advances, the indicator returns to green, and the candidate leaves the silence list.

---

### User Story 3 - System drafts a holding message for one-click recruiter approval (Priority: P1)

When a candidate breaches the silence window, the system prepares an honest, human-toned holding/update message (drawn from the template library) addressed to that candidate and queues it for the recruiter. The recruiter reviews the draft and either approves it — which sends it through the normal email channel — or dismisses it. Nothing is sent without that explicit recruiter action.

**Why this priority**: This is the "fixable" half of "visible, measurable, and fixable" — it turns a red indicator into a one-click remediation. It is the feature's headline action. P1.

**Independent Test**: Drive a candidate into breach via the scheduled scan (with an injected clock), confirm exactly one draft is created (not a duplicate per scan), the recruiter is notified in-app, approving the draft dispatches exactly one email through the consent-gated channel, and dismissing it sends nothing.

**Acceptance Scenarios**:

1. **Given** a candidate breaches the silence window, **When** the scheduled scan runs, **Then** a holding/update message draft is created for that candidate and the responsible recruiter is notified in-app.
2. **Given** a queued draft, **When** the recruiter approves it, **Then** exactly one message is dispatched through the email channel and the candidate's last-meaningful-activity instant advances (clearing the breach).
3. **Given** a queued draft, **When** the recruiter dismisses it, **Then** no message is sent and the draft is removed from the queue (the candidate may be re-drafted on a future breach if still silent).
4. **Given** a candidate already has an open (un-actioned) draft, **When** the scan runs again before the recruiter acts, **Then** no second draft is created for the same ongoing breach (no queue flooding).
5. **Given** a candidate in an erased, no-consent, or undeliverable state, **When** the scan runs, **Then** no draft is created for that candidate (drafting is suppressed, consistent with the email-channel consent/erasure gate).
6. **Given** a recruiter previews a queued draft, **When** it renders, **Then** the candidate's merge fields are filled and any missing field is shown as a visible warning, never a raw unrendered placeholder.
7. **Given** a candidate is in a sensitive terminal stage (e.g. offer or rejection), **When** the scan runs, **Then** the engine does not auto-draft a generic "still deciding" holding message for that stage (stage-aware guardrail).

---

### Edge Cases

- **No silence window configured**: A workspace that never set a window must still be governed by a documented default; no candidate is silently ungoverned (US-F31-1 / FR-002).
- **Missed scheduler fire / restart mid-scan**: If the scheduled scan host restarts or a fire is missed, the next run (or replay) must detect the same breaches without sending duplicate drafts or duplicate emails — idempotent and missed-fire-safe (FR-014/FR-015).
- **Candidate updated between scan and approval**: A candidate breaches, a draft is queued, then a qualifying activity advances the candidate's last-meaningful-activity instant before the recruiter approves. Approval is still permitted (recruiter judgement governs — see Assumptions), but the eligibility gate (consent/erasure/undeliverable/terminal) is re-checked at send time and refuses an ineligible send (FR-023).
- **Erased candidate with an open draft**: A candidate is erased while a draft sits in the queue. The erasure flow best-effort invalidates the open draft; the authoritative guarantee that an erased candidate is never messaged is the send-time consent gate (FR-021/FR-023).
- **No-consent / undeliverable candidate**: Drafting and any send must be suppressed for candidates without email-channel consent or flagged undeliverable — even if they technically breach the window (FR-019).
- **Concurrent scans / concurrent approval**: Two scan executions overlapping, or two recruiters approving the same draft, must not produce two drafts or two emails for one breach (FR-015/FR-022).
- **Clock / time-zone correctness at boundary**: A candidate exactly at the window boundary, and a window crossing a DST change in the workspace time zone, must classify deterministically (no off-by-one-day flap) (FR-007).
- **No assignable recruiter at breach time**: A breaching candidate has no assigned recruiter, or the assigned recruiter is deactivated. The draft is still created and the notification falls back to the workspace's active Admins/Recruiters so the breach is never silently dropped (FR-012).
- **Auto-send attempted**: There is no configuration, endpoint, or code path that sends an SLA message without recruiter approval in the MVP build (FR-010 / SC-008).

## Requirements *(mandatory)*

### Functional Requirements

**SLA policy configuration**

- **FR-001**: An Admin MUST be able to define the maximum candidate-silence window for the workspace (the number of days a candidate may go without a qualifying activity before being considered in breach). This reuses the existing workspace silence-window setting; no new policy collection is introduced.
- **FR-002**: When no silence window has been explicitly configured, the system MUST apply a documented sensible default so that every active candidate is governed by some SLA threshold.
- **FR-003**: Changing the silence window MUST be restricted to Admin and enforced server-side; the change MUST be audited (actor, old value, new value, timestamp).
- **FR-004**: The system MUST reject an invalid silence-window value (non-positive, or beyond a documented maximum) with a clear validation message and leave the prior value unchanged.

**Breach detection & visibility**

- **FR-005**: The system MUST maintain, per candidate, a single canonical **last-meaningful-activity instant**, advanced at each qualifying write site — when an outbound candidate communication is sent (F22), when a candidate status is published/updated (F30), and when an interview is booked or rescheduled (F13/F20). This instant MUST be the consistent basis for breach detection (FR-006) and breach-clearing (FR-009). Qualifying activities are strictly system/recruiter-originated; **no candidate-originated action (page view, erasure-request submission, inbound bounce) may advance the instant**, so a candidate can neither self-suppress nor be made to appear contacted. *(The pre-existing `lastContactAt` is set only at candidate creation and is insufficient; this denormalized instant is the one new write-path wiring this feature adds — no separate event-tracking infrastructure.)*
- **FR-006**: The system MUST classify each active candidate's communication health as **green** (within SLA), **amber** (within a documented "nearing breach" margin before the window — a fixed default margin for the MVP, not yet separately configurable), or **red** (breached — last meaningful activity older than the window), computed server-side.
- **FR-007**: Breach classification MUST be computed against the workspace time zone with an injectable clock, so a candidate at the exact boundary and a window crossing a DST change classify deterministically (no off-by-one flap), verifiable under a controlled clock.
- **FR-008**: A candidate whose data has been erased, or who is in a terminal outcome (hired/rejected), MUST NOT be reported as an active silence breach.
- **FR-009**: When a qualifying activity advances a candidate's last-meaningful-activity instant (FR-005), the system MUST recompute and clear any prior amber/red classification (return the candidate to green / remove from the silence list) on the next evaluation. Clearing is a scan-side recompute against the advanced instant, not a synchronous event hook.

**Draft-for-approval (no auto-send)**

- **FR-010**: In the MVP, the system MUST NOT send any SLA/holding message automatically. Every SLA message MUST require explicit recruiter approval before dispatch; **no auto-send configuration, endpoint, or code path may exist** in the MVP build (structural absence, not a disabled flag).
- **FR-011**: When a candidate **breaches** the silence window (red — not merely amber/approaching) and is not suppressed per FR-019/FR-020, the system MUST create a holding/update message draft for that candidate, populated from the email template library with the candidate's merge fields. *(Backlog US-F31-2 wording "when a threshold is approaching" is realised as: the amber indicator is the early-warning signal (FR-006); the draft is created on breach to avoid premature/duplicate holding messages.)*
- **FR-012**: On draft creation, the system MUST notify the responsible recruiter in-app (reusing the existing recruiter-notification channel). If the candidate has no assigned recruiter or the assignee is deactivated, the notification MUST fall back to the workspace's active Admin/Recruiter members so a breach is never silently dropped.
- **FR-013**: A recruiter MUST be able to preview a queued draft with the candidate's merge fields rendered; any missing merge field MUST surface as a visible warning, never a raw unrendered placeholder. Because preview decrypts candidate merge fields, it MUST be permission- and workspace-scoped (FR-018), served with `Cache-Control: no-store`, and MUST NOT write the rendered content to logs.
- **FR-014**: The breach scan MUST run on the shared `@Scheduled` + `SchedulerCheckpoint` pattern (F00.2) under its own checkpoint name: it MUST be idempotent across runs and recover missed fires without duplicating drafts or sends. The scan MUST be bounded and index-backed — it keys its range query on the indexed last-meaningful-activity instant per workspace (index declared in `plan.md` per F00.1), never a full-collection scan.
- **FR-015**: Draft creation MUST be de-duplicated per breach by an atomic primitive (a unique constraint over the candidate's open-draft state, not a read-then-write): a candidate with an existing open draft MUST NOT receive a second draft from a subsequent or overlapping scan; a duplicate-insert attempt MUST be an idempotent no-op.
- **FR-016**: A recruiter MUST be able to **approve** a queued draft. Approval MUST route the send through the existing consent/erasure-gated email-dispatch channel (it MUST NOT construct and send the message directly, so the send-time gate cannot be bypassed); it dispatches exactly one message and advances the candidate's last-meaningful-activity instant (clearing the breach).
- **FR-017**: A recruiter MUST be able to **dismiss** a queued draft, which sends nothing and removes it from the queue; a dismissed candidate may be re-drafted on a future breach if still silent.
- **FR-018**: Approve, dismiss, and preview MUST be permission-enforced (Recruiter or Admin for the candidate's workspace) and workspace-scoped via the candidate, so a draft identifier from another workspace returns an indistinguishable not-found response (no existence oracle), not an authorization error that leaks existence. Approve and dismiss MUST be audited (actor, candidate, action, timestamp).

**Suppression, consent & erasure**

- **FR-019**: SLA drafting and any send MUST be suppressed for candidates who lack email-channel consent, are in an erased state, or are flagged undeliverable — the same gate enforced by the email delivery channel — even if the candidate technically breaches the window.
- **FR-020**: The engine MUST apply a stage-aware guardrail using the candidate's existing status/outcome state: it MUST NOT auto-draft a generic holding/"still deciding" message for sensitive terminal stages (e.g. offer, rejection) where such a message would be inappropriate.
- **FR-021**: When a candidate is erased, the erasure flow MUST best-effort invalidate any open SLA draft for that candidate. The authoritative guarantee that an erased candidate is never messaged is the send-time consent gate (FR-023) — so even a draft that briefly survives the erasure write can never transmit.

**Idempotency & consistency**

- **FR-022**: Concurrent approval of the same draft (two recruiters, or a double-submit) MUST result in at most one dispatched message, via an atomic claim (compare-and-set on the draft's open state); the loser is a no-op. Even a double-claim cannot double-send, because the underlying email dispatch is itself idempotent on its existing unique key.
- **FR-023**: The send-time consent/erasure/undeliverable/terminal gate is the single authoritative suppression point. Approving a draft whose candidate has become ineligible since the draft was created MUST be re-checked at send time and refuse to dispatch rather than message an ineligible candidate.

**Privacy / logging**

- **FR-024**: The system MUST NOT write candidate personal data (name, email, phone, or any recruiter-/system-authored free text that may contain personal data) to application logs at any level; only internal identifiers and value-free outcome reasons may be logged. The draft entity MUST store only internal identifiers (candidate id, template id, message type) and lifecycle state at rest — never the rendered recipient address or body (which remain transient).
- **FR-025**: Drafted message content and recipient address MUST NOT be persisted to durable diagnostic artefacts (dead-letter, audit). A render/dispatch failure MUST hand the dead-letter/audit path a PII-free summary (cause class / outcome enum only), never a raw exception message that could carry template or merge content.

### Key Entities *(include if feature involves data)*

- **SLA policy (silence window)**: The Admin-configured maximum-silence threshold for the workspace (in days), with a documented default. Reuses the existing workspace configuration record (F03's silence-window setting) — no new collection.
- **Last-meaningful-activity instant**: A denormalized per-candidate timestamp (FR-005) advanced at each qualifying write site and index-backed for the breach scan. The single source of truth for green/amber/red classification.
- **SLA draft (holding/update message)**: A new persisted record per breaching candidate — references the candidate, the chosen template, and a message-type; carries an open → approved/dismissed lifecycle state. De-duplicated by a unique constraint over the open state (FR-015) and claimed by an atomic compare-and-set on approval (FR-022). Stores internal identifiers + lifecycle only — no candidate PII at rest (FR-024); the rendered recipient/body stay transient.
- **Candidate (existing, F04/F30)**: The data-subject record. The engine reads its consent/erasure/undeliverable state, status/outcome (F30), assigned recruiter, and the last-meaningful-activity instant; it never relaxes the existing consent/erasure gate.
- **Recruiter notification (existing, F22)**: The in-app channel used to alert the responsible recruiter (or the Admin/Recruiter fallback) that a candidate needs attention.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A candidate who has had no qualifying activity for more than the configured silence window shows a red indicator and appears on the silence list; one within the amber margin shows amber; one updated well within the window shows green — verified by integration test with seeded last-meaningful-activity timestamps for all three bands.
- **SC-002**: Setting the silence window persists to durable storage and survives a restart; a non-Admin cannot change it (server-side refusal), verified by test.
- **SC-003**: When a candidate breaches the window, exactly one draft is created and the responsible recruiter receives exactly one in-app notification, regardless of how many times the scan runs (sequentially or overlapping) while the draft is open — verified by an integration test that runs the scan repeatedly.
- **SC-004**: Approving a queued draft dispatches exactly one email through the consent-gated channel and the candidate's breach clears; dismissing a draft sends zero emails — verified by test (dispatch count asserted).
- **SC-005**: No SLA draft is created or sent for a candidate in an erased, no-consent, or undeliverable state, even when the candidate is past the silence window — verified by test for each suppressed state.
- **SC-006**: A simulated mid-scan restart followed by a checkpoint replay produces no duplicate drafts and no duplicate emails — verified by integration test.
- **SC-007**: No candidate personal data and no recipient email address appears in any application log, **nor in the persisted dead-letter or audit records**, across the scan, draft-create, preview, approve, and dismiss flows — verified by an automated scan with PII sentinels seeded into each free-text/merge field, asserting absence in logs and in the persisted artefacts.
- **SC-008**: No code path, configuration, or endpoint in the MVP build sends an SLA message without recruiter approval — verified by a structural test that asserts every SLA-message send is reachable only from the recruiter approve action (no `@Scheduled`/system caller invokes the SLA send), i.e. the capability is absent, not merely turned off.
- **SC-009**: Breach classification is deterministic at the window boundary and across a DST change in the workspace time zone — verified under a controlled clock (no off-by-one-day flap).
- **SC-010**: Concurrent approval of the same draft yields at most one dispatched email — verified by a concurrent integration test.
- **SC-011**: Every silence-window change and every approve/dismiss action produces an audit record (actor, candidate where applicable, action, timestamp) — verified by audit-record assertions.
- **SC-012**: A candidate in a sensitive terminal stage (offer/rejection) is not auto-drafted a generic holding message — verified by test.
- **SC-013**: The scheduled breach scan completes within its scheduler interval (and within a documented bound, e.g. a few seconds) for a workspace with at least 1,000 active candidates, using an index-backed range query on the last-meaningful-activity instant — verified by an integration test plus a query-plan assertion that the index bounds the scan (no full-collection scan).
- **SC-014**: Each qualifying activity independently advances the last-meaningful-activity instant and clears an existing breach — verified by a parameterized test with one case per signal (outbound send, status publish/update, interview book, interview reschedule), including the case where the only prior value was the creation-time fallback.
- **SC-015**: Erasing a candidate who has an open SLA draft invalidates that draft (best-effort) and, regardless of the draft's state, a subsequent approve of any draft for that candidate does not dispatch (the send-time gate refuses the erased candidate) — verified by integration test.
- **SC-016**: A draft identifier addressed from outside its workspace returns an indistinguishable not-found response (no existence oracle), and the preview response carries `Cache-Control: no-store` — verified by test.

## Assumptions

- **MVP scope is draft-for-approval only**: Auto-send SLA policies are explicitly deferred to v1.5 (backlog Deferred table; product spec §11). The engine drafts and queues; a recruiter sends. The structural absence of an auto-send path (FR-010 / SC-008) is a stronger guarantee than a disabled flag.
- **Silence window is workspace-level for the MVP**: Product spec FR-10 mentions "per stage" windows, but the existing workspace configuration carries a single workspace-level silence-window setting (F03), and backlog US-F31-1's example ("max 5 days without a candidate update") is workspace-level. The MVP uses the workspace-level window; per-stage silence windows are a reasonable later extension and are out of scope. (The stage-aware guardrail FR-020 introduces *stage-aware suppression* for sensitive stages — distinct from per-stage windows.)
- **Last-meaningful-activity is one new denormalized signal, not new infrastructure**: The canonical instant (FR-005) is a single denormalized timestamp advanced at qualifying write sites that already exist (F22 send, F30 status publish, F13/F20 booking). This is the one corrected-from-review item: the pre-existing `lastContactAt` is written only at candidate creation (confirmed against the code) and cannot serve as the signal on its own; the creation-time value is the conservative initial fallback until the first qualifying activity. No queue, broker, or event-tracking service is introduced (constitution C2/C4).
- **Approval sends on recruiter judgement**: A recruiter may approve a queued draft even if a later activity has already cleared the breach (the recruiter chose to send the holding message); the system does not silently cancel an approved send for a no-longer-breaching candidate. What is *never* permitted is sending to an ineligible candidate — eligibility (consent/erasure/undeliverable/terminal) is re-checked at send time (FR-023).
- **Reuse, no new infrastructure service**: The feature reuses the workspace SLA setting and time zone (F03), the candidate record + consent/erasure/undeliverable gate + audit log (F04), the template library + merge renderer with missing-field warnings (F21), the consent/erasure-gated email channel + recruiter in-app notification + dead-letter discipline (F22), and the candidate status/outcome fields (F30). The only net-new persistent object is the SLA-draft record (storage, not infrastructure — permitted). Approval routes through the existing email-dispatch channel so the send-time consent gate cannot be bypassed.
- **Indicator surfaces here; full pipeline view is later**: This feature computes and exposes the server-side green/amber/red SLA status and the silence list, plus the recruiter approve/dismiss/preview surface and a per-candidate badge. The rich, sortable Pipeline Health Board (F51) and the dashboard silence-list metric (F50) consume these but are out of scope here.
- **Sensitive-stage guardrail**: FR-020 suppresses generic holding drafts for terminal/sensitive stages (offer, rejection) using the candidate's existing status/outcome state (F30) — a conservative default consistent with the product's "auto-send off by default for sensitive stages" risk control, applied here to drafting.
- **Single language**: SLA draft copy is English for the MVP, authored from the existing template library with localization markers, consistent with prior candidate-facing features.
- **Scheduler topology**: Single-instance scheduling (constitution §IV); correctness rests on per-record atomic claims (the unique open-draft constraint + the approval compare-and-set) and the SchedulerCheckpoint pattern, not on single-threading, so a rolling deploy or overlapping fire is safe.
