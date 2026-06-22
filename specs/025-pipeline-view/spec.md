# Feature Specification: F51 Pipeline View

**Feature Branch**: `025-pipeline-view`
**Created**: 2026-06-18
**Status**: Draft (revised after multi-role review 2026-06-18)
**Input**: User description: "write spec for the next unimplemented task from the backlog. review with subagents"
**Backlog ref**: F51 — Pipeline View (`docs/backlog.md`, Tier 4 P3); spec §5.2 (FR-14 bulk actions), §4 Pillar C

## Overview

The Pipeline View is the recruiter's primary working surface: a single sortable, filterable list of every active candidate in the workspace, each colour-coded by their SLA health and scheduling progress, with one-click drill-down into a candidate's full activity timeline and bulk actions to move many candidates forward at once.

F51 is also the feature that **owns the requisition linkage** the rest of the product has deferred to it. Until now there has been no concept of a job opening (a "requisition") a candidate belongs to, and therefore no way to scope a Hiring Manager to "their own" candidates. F51 introduces the requisition as a first-class concept, links candidates to it, and uses it to enforce that a Hiring Manager sees only the candidates on requisitions they have been assigned to — server-side, never by hiding rows in the browser.

> **Review note (2026-06-18)**: This spec was reviewed by Business Analyst, Security/GDPR, QA, and Backend reviewers before planning. Two findings shaped scope materially: (1) there is **no general candidate "closed/offer/rejected" lifecycle field** in the system today, so "terminal" is defined here strictly in terms of fields that *do* exist (`statusOutcome`, scheduling cancellation, erasure) — see the Terminal-state definition in Assumptions; (2) the candidate→requisition link is an internal id (not personal data) and is **retained on erasure** as a non-PII anchor, with erased candidates excluded from every view by an active-state predicate, not by clearing the link.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Recruiter sees the whole pipeline at a glance (Priority: P1)

A Recruiter opens the Pipeline View and sees every active candidate in their workspace in one list. Each row shows the candidate's name, current stage, an at-a-glance SLA health colour (green / amber / red), and their scheduling progress (e.g. "no link sent", "link sent", "slot picked", "confirmed"). The Recruiter can sort and filter the list (by stage, by SLA colour, by scheduling progress, by requisition) to find the candidates that need attention right now.

**Why this priority**: This is the core value of the feature and a viable MVP on its own — it turns scattered per-candidate lookups into a single operational dashboard. Without it, a Recruiter has no way to answer "who needs my attention today?" Every other story builds on this list.

**Independent Test**: Seed a workspace with candidates in varied stages, SLA states, and scheduling states. Open the Pipeline View as a Recruiter and confirm all active candidates appear with correct stage, SLA colour, and scheduling status, and that sorting/filtering narrows the list correctly.

**Acceptance Scenarios**:

1. **Given** a workspace with 12 active candidates spanning several stages and SLA states, **When** a Recruiter opens the Pipeline View, **Then** all 12 candidates are listed, each showing name, current stage, SLA colour, and scheduling status.
2. **Given** the pipeline list is displayed, **When** the Recruiter filters to "SLA = red", **Then** only candidates currently breaching their SLA window are shown.
3. **Given** the pipeline list is displayed, **When** the Recruiter sorts by scheduling status, **Then** candidates are ordered by the defined scheduling-status order, with ties broken by most-recent-activity then candidate identifier (a stable, repeatable order).
4. **Given** a candidate's last meaningful activity crosses the SLA breach threshold, **When** the Recruiter's view refreshes on its next poll, **Then** that candidate's SLA colour updates to red without a manual page reload.
5. **Given** a candidate has been erased (right-to-erasure completed), **When** the Recruiter opens the pipeline, **Then** that candidate does not appear as an active pipeline row (no residual personal data is shown).
6. **Given** a brand-new workspace with no candidates, **When** a Recruiter opens the pipeline, **Then** an empty-state is shown (not an error).
7. **Given** a candidate imported with no stage yet recorded, **When** the pipeline is shown, **Then** that candidate appears with a defined "no stage / not started" label and a defined default SLA colour (never a blank or broken cell).

---

### User Story 2 - Hiring Manager sees only their own requisitions (Priority: P2)

A Hiring Manager opens the Pipeline View and sees only the candidates attached to requisitions they have been assigned to. They cannot see, sort, filter into, or otherwise discover candidates on requisitions they are not assigned to — even if they attempt a direct request for another requisition's candidates. To make this possible, an Admin can create requisitions and assign Hiring Managers to requisitions, and an Admin or Recruiter can attach a candidate to a requisition.

**Why this priority**: This restores the Hiring Manager scoped access that F50 (dashboard) and other features explicitly deferred to F51, and it satisfies the minimum-exposure privacy gate (a Hiring Manager must never see candidates outside their remit). It is P2 because the P1 Recruiter list delivers value first, but this story is a hard security/compliance requirement for the feature to be considered complete.

**Independent Test**: Create two requisitions, assign a Hiring Manager to only the first, attach candidates to each. Open the Pipeline View as that Hiring Manager and confirm only the first requisition's candidates appear; attempt a direct request scoped to the second requisition and confirm it is refused without revealing whether those candidates exist.

**Acceptance Scenarios**:

1. **Given** requisitions R1 and R2 exist and a Hiring Manager is assigned only to R1, **When** the Hiring Manager opens the Pipeline View, **Then** only candidates linked to R1 are listed and no R2 candidate is visible.
2. **Given** a Hiring Manager assigned only to R1, **When** they issue a direct request for R2's candidates (e.g. by guessing a requisition identifier), **Then** the request is refused indistinguishably from a not-found result and the response does not disclose whether R2 or its candidates exist.
3. **Given** an Admin, **When** they create a requisition, attach candidates to it, and assign a Hiring Manager, **Then** those candidates appear in that Hiring Manager's pipeline on next load, and each of those administrative changes is recorded in the audit log (actor + internal ids only).
4. **Given** a Hiring Manager with no assigned requisitions, **When** they open the Pipeline View, **Then** they see an empty pipeline (not an error and not other people's candidates).
5. **Given** a candidate not linked to any requisition, **When** any Hiring Manager opens the Pipeline View, **Then** that candidate is never shown to a Hiring Manager (unassigned candidates are visible to Recruiters/Admins/Read-only only).
6. **Given** a Recruiter moves a candidate from R1 to R2, **When** Hiring Managers on R1 and R2 next load their pipelines, **Then** the candidate disappears from R1's Hiring Managers' view and appears for R2's, and the link change is audited.
7. **Given** a requisition is closed or a Hiring Manager is unassigned from it, **When** that Hiring Manager next loads their pipeline, **Then** those candidates are no longer visible to them.

---

### User Story 3 - Bulk actions move many candidates forward at once (Priority: P2)

A Recruiter selects several candidates from the pipeline and applies a single bulk action — send a scheduling link, or send an update/holding email — to all of them in one step. Each send is individually personalised (merge-rendered) per candidate. The system applies the action to each selected candidate independently and reports the per-candidate outcome, so a failure for one candidate (e.g. a non-contactable candidate, or a candidate already booked) does not block the rest.

**Why this priority**: Bulk actions are the productivity payoff of having a list view (FR-14) and are what makes the pipeline a "working" view rather than a report. They are P2 because they depend on the P1 list existing, and on the existing single-candidate scheduling and email-send capabilities.

**Independent Test**: Select a mix of contactable and non-contactable candidates, apply "send scheduling link" in bulk, and confirm each contactable candidate receives a personalised action while each non-contactable candidate is reported as skipped with a single coarse reason — and that the overall action does not fail.

**Acceptance Scenarios**:

1. **Given** a Recruiter has selected 8 candidates, **When** they apply "send scheduling link" in bulk, **Then** a scheduling link is initiated for each eligible candidate (each email individually merge-rendered) and the result lists every candidate with its individual outcome.
2. **Given** 2 of the 8 selected candidates are not contactable for any reason (consent missing, withdrawn, over-retention, undeliverable, or erased), **When** the bulk action runs, **Then** those 2 are reported as skipped with a **single coarse "not contactable" outcome that is identical regardless of the underlying cause**, and the other 6 succeed.
3. **Given** a Hiring Manager, Interviewer, or Read-only user, **When** they attempt any bulk action, **Then** the action is refused (these roles cannot perform bulk actions).
4. **Given** two bulk-send requests for the same selection arrive concurrently (or a request is retried), **When** they execute, **Then** each candidate receives exactly one send (verified by a send-count assertion), with no duplicate.
5. **Given** a selection at exactly the configured maximum, **When** the Recruiter submits it, **Then** it is accepted; **Given** a selection one over the maximum, **Then** it is rejected with a clear limit message before any candidate is acted upon.
6. **Given** a candidate is erased after selection but before the bulk action reaches them, **When** the action executes, **Then** that candidate is reported as skipped ("not contactable"), no message is sent, no personal data appears in logs or the result, and the candidate is not resurrected.
7. **Given** a candidate already has a live booked interview, **When** a bulk "send scheduling link" includes them, **Then** the defined outcome for an already-in-flight candidate is applied consistently (a re-sent scheduling link supersedes the candidate's prior live scheduling request rather than creating a second concurrent booking path — see FR-019).

---

### User Story 4 - Drill into a candidate's full timeline (Priority: P3)

From any pipeline row, a Recruiter (or a Hiring Manager for their own candidates) opens a candidate's detail and sees a single chronological timeline of everything that has happened: emails sent, scheduling events (link sent, slot picked, booked, rescheduled, cancelled, no-show), status-page changes, and feedback request/submission status.

**Why this priority**: The timeline is the "understand this candidate" view that supports decisions, but it is P3 because the at-a-glance list and bulk actions deliver the primary operational value first; the timeline is depth-on-demand.

**Independent Test**: For a candidate with a known history of emails, scheduling events, and feedback activity, open their timeline and confirm every event appears in correct chronological order with accurate labels.

**Acceptance Scenarios**:

1. **Given** a candidate with several emails, a booking, a reschedule, and a submitted scorecard, **When** the Recruiter opens that candidate's timeline, **Then** all events appear in chronological order with human-readable labels.
2. **Given** a Hiring Manager viewing one of their own candidates, **When** they open the timeline, **Then** they see scheduling and feedback events (occurrence + status labels) but not protected content they are not entitled to see (e.g. interviewer scorecard free-text, which remains Recruiter/Admin-only).
3. **Given** a candidate with feedback requested but not yet submitted, **When** the timeline is viewed, **Then** the feedback item shows a "pending" status (which interviewers have/haven't responded).
4. **Given** a candidate with no events yet (never contacted), **When** their timeline is opened, **Then** an empty-timeline state is shown (not an error).
5. **Given** a Hiring Manager requests the timeline of a candidate not on any of their assigned requisitions, **When** the request is made, **Then** it is refused indistinguishably from a not-found result (the same no-oracle rule as the list).

---

### Edge Cases

- **Candidate with no requisition link**: visible to Recruiters/Admins/Read-only; never visible to any Hiring Manager. Bulk actions and timeline still work for Recruiters/Admins.
- **Candidate with no stage recorded yet** (e.g. freshly imported): shown with a defined "not started" stage label and a defined default SLA colour — never a blank/broken cell.
- **Terminal/closed candidate**: by default the pipeline shows active candidates only; terminal candidates (see Terminal-state definition in Assumptions) are excluded from the default view but can be revealed via an explicit "include closed" filter.
- **SLA colour staleness**: the colour is produced by calling the same SLA classification used by the SLA nudge engine, so the pipeline and the dashboard never disagree about whether a candidate is breaching.
- **Bulk action with partial failure**: each candidate's outcome is independent; the overall request succeeds and returns a per-candidate result list. No partial-state corruption.
- **Concurrent erasure during a bulk action**: a candidate erased between selection and execution is reported as skipped ("not contactable"), never resurrected, and no personal data leaks into logs.
- **Hiring Manager assigned to a requisition that is later closed/unassigned**: the candidates drop out of that Hiring Manager's pipeline on the next load.
- **Candidate moved between requisitions**: only the current link is shown; the timeline reflects activity regardless of the move; the visibility change applies on next load.
- **Requisition with no candidates / candidate attached to a closed requisition**: empty requisition shows no rows; a candidate on a closed requisition is treated as closed for default-view filtering.
- **Pagination boundary**: results are paginated with a defined page size and a stable cross-page sort, so no row is duplicated or skipped across pages.
- **Polling under concurrent mutation**: a poll landing mid-bulk-action or mid-booking returns a consistent per-row snapshot, never a half-applied row.
- **Bulk selection at and beyond the maximum**: at the maximum is accepted; one over is rejected before any candidate is acted upon.

## Requirements *(mandatory)*

### Functional Requirements

**Pipeline list**

- **FR-001**: The system MUST present a paginated list of active candidates in a workspace, each row showing at minimum: candidate name, current stage (or a defined "not started" label), SLA health (green/amber/red), scheduling status, and the requisition (if any).
- **FR-002**: The system MUST allow the list to be sorted by stage, SLA health, scheduling status, and most-recent-activity, with a documented stable tie-breaker (most-recent-activity, then candidate identifier) so ordering is repeatable and pagination-safe.
- **FR-003**: The system MUST allow the list to be filtered by stage, SLA health, scheduling status, and requisition, and to optionally include or exclude candidates in terminal/closed states (default: exclude). "Terminal/closed" is defined only in terms of existing candidate state (see Assumptions) — this feature does not invent a new candidate lifecycle field.
- **FR-004**: The SLA health colour shown in the pipeline MUST be produced by invoking the existing SLA nudge engine's per-candidate classifier (not a re-implementation), so the pipeline and the core dashboard always agree on a candidate's SLA state.
- **FR-005**: The scheduling status shown in the pipeline MUST be derived from the candidate's authoritative scheduling state via a single documented mapping, covering at minimum: no scheduling request → "no link sent"; link sent / awaiting selection; slot picked / booking; confirmed/booked; rescheduled; cancelled; no-show; and superseded/expired requests. This mapping is the single source of truth and MUST be unit-testable.
- **FR-006**: The system MUST refresh the displayed SLA and scheduling status on a polling cycle (default 60 seconds) without requiring a manual page reload; worst-case displayed staleness is bounded by the time to the next poll.
- **FR-007**: Erased candidates MUST be excluded from every role's pipeline and timeline by an active-state predicate at query time (not by mutating the requisition link), and MUST never expose residual personal data.

**Requisitions and candidate linkage**

- **FR-008**: The system MUST introduce a requisition concept (a named job opening) belonging to a workspace, with at least a title and an open/closed state.
- **FR-009**: The system MUST allow a candidate to be associated with at most one requisition, MUST allow that association to be set or changed by an Admin or Recruiter, and MUST record each set/change in the audit log (actor + internal ids only).
- **FR-010**: The system MUST allow Hiring Managers to be assigned to requisitions, reusing the existing member-to-resource assignment mechanism with the requisition resource type; creating requisitions and assigning Hiring Managers are Admin operations.
- **FR-011**: The system MUST surface any external job reference/label already carried by ATS-sourced or CSV-imported candidates so a Recruiter/Admin can **manually** create-or-link a workspace requisition from it. Automatic requisition creation/reconciliation from external labels is **out of scope for this feature** (assistive, manual-confirm only) — this keeps F51 a single coherent feature and avoids an unbounded reconciliation subsystem.

**Role-based visibility (server-side)**

- **FR-012**: The system MUST enforce candidate visibility server-side with these exact per-role outcomes: Admin and Recruiter see all candidates in the workspace; Read-only sees all candidates in the workspace in view-only mode (no actions, no export); a Hiring Manager sees only candidates whose requisition link is in their set of assigned requisitions; Interviewer has no pipeline access at all.
- **FR-013**: A Hiring Manager MUST NOT be able to retrieve candidates outside their assigned requisitions even via a direct, hand-crafted request; out-of-scope requisitions and candidates MUST be refused indistinguishably from non-existent ones (no existence oracle), consistent with the established scoped-not-found behaviour.
- **FR-014**: The Hiring Manager result set MUST be computed from the Hiring Manager's assigned-requisition set as the selection predicate (a candidate is included only if its requisition link is in that set); a candidate with no requisition link can never match and is therefore never visible to any Hiring Manager, and an empty assignment set yields an empty pipeline (never an unfiltered result).

**Bulk actions**

- **FR-015**: The system MUST allow a Recruiter or Admin to select multiple candidates and apply a single bulk action: (a) send a scheduling link, or (b) send an update/holding email; each send MUST be individually merge-rendered (personalised) per candidate — bulk is a fan-out of personalised single sends, never one shared message.
- **FR-016**: Bulk actions MUST be refused for Hiring Manager, Interviewer, and Read-only roles.
- **FR-017**: A bulk action MUST apply to each selected candidate independently and return a per-candidate outcome (succeeded / skipped), so a failure for one candidate does not abort the action for the others.
- **FR-018**: Every bulk send MUST pass through the same consent/erasure/deliverability gate as a single send, **re-evaluated per candidate at send time** (not against a cached selection snapshot). A non-contactable candidate MUST be reported with a single coarse "not contactable" outcome that is **byte-identical regardless of the underlying cause** (erased, withdrawn, over-retention, no-consent, undeliverable), so the result cannot be used as a consent/erasure oracle; the specific cause may be recorded only in internal value-free audit/diagnostics keyed by internal id.
- **FR-019**: Bulk actions MUST NOT cause a duplicate effect when submitted twice or concurrently. For the update-email verb this means no duplicate email (inherited from the existing per-candidate send-idempotency). For the scheduling-link verb this means no second concurrent booking path: a re-sent link supersedes the candidate's prior live scheduling request rather than creating two.
- **FR-020**: The system MUST enforce a configurable maximum selection size for a single bulk action (default at least 50) and reject an over-limit selection with a clear message before acting on any candidate.

**Candidate timeline**

- **FR-021**: The system MUST present, for a single candidate, a chronological timeline aggregating (read-only, no new stored record): emails sent, scheduling events (link sent, slot picked, booked, rescheduled, cancelled, no-show), status-page changes, and feedback request/submission status.
- **FR-022**: Timeline access MUST honour the same role scoping as the list: a Hiring Manager can view the timeline only for candidates on their assigned requisitions (an out-of-scope or unknown candidate is refused indistinguishably from not-found), and protected content (e.g. interviewer scorecard free-text) remains restricted to the roles already entitled to it.
- **FR-023**: The timeline MUST present each event with a human-readable label and timestamp, in a consistent chronological order.

**Cross-cutting**

- **FR-024**: All logging related to the pipeline, bulk actions, and timeline MUST use only internal/anonymised identifiers — never candidate name, email, phone, message content, status free-text, or scorecard content at any log level. This explicitly covers bulk-action result/skip-reason logging (ids only) and timeline-aggregation error paths (any failure reduces to a value-free cause class, never a raw message).
- **FR-025**: The pipeline, bulk-action, and timeline surfaces are internal (authenticated staff) screens; candidate-facing accessibility and performance gates (WCAG, mobile Lighthouse) do not apply, but the internal responsiveness targets in Success Criteria do.

### Key Entities

- **Requisition** *(new)*: A named job opening within a workspace. Attributes: workspace, title, open/closed state, created timestamp, creating member, and an optional external linkage label (ATS job reference or imported requisition label) used to assist manual linking. Hiring Managers are scoped through assignments to requisitions.
- **Candidate–Requisition link** *(new field on the candidate)*: A nullable internal reference associating a candidate to at most one requisition. It is an internal identifier, **not personal data**: it is retained on erasure (a non-PII anchor, consistent with the ATS-reference precedent), and erased candidates are excluded from views by the active-state predicate, not by clearing this link. Drives Hiring Manager visibility and pipeline grouping/filtering.
- **Requisition Assignment** *(reuses existing assignment mechanism)*: A member-to-requisition assignment (requisition resource type) that grants a Hiring Manager visibility to that requisition's candidates.
- **Pipeline Entry** *(view projection, not stored)*: The per-candidate row composed for the list — candidate identity, current stage, SLA health, scheduling status, requisition, and last-activity time. Composed from existing candidate, SLA, and scheduling state.
- **Bulk Action Result** *(transient)*: The per-candidate outcome of a bulk action — candidate reference plus succeeded or skipped (with the single coarse non-disclosing reason).
- **Candidate Timeline** *(view projection, not stored)*: A chronologically merged list of a candidate's email, scheduling, status, and feedback events.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A Recruiter can isolate every candidate currently breaching their SLA in the workspace via the red-SLA filter, with the complete breaching set returned in the result (no breaching candidate omitted).
- **SC-002**: For a workspace with 200 active candidates, the first page of the pipeline — with SLA and scheduling status computed server-side — is returned within a defined server-side latency budget, and the page becomes interactive in under 3 seconds end-to-end on a desktop connection. (The measurement target — first page vs full set — and the page size are fixed in `plan.md`; the metric is unambiguous about which is measured.)
- **SC-003**: When a candidate's SLA classification or scheduling state changes, the pipeline reflects the new value on the next poll after the change (verified deterministically with an injected clock), with zero manual reloads required.
- **SC-004**: Role enforcement is verified by a contract test across all five roles with these exact outcomes: Admin/Recruiter → full workspace list; Read-only → full workspace list, view-only (no bulk action, no export); Hiring Manager → only assigned-requisition candidates, with out-of-scope requests refused indistinguishably from not-found; Interviewer → no access. 100% of out-of-scope Hiring Manager attempts are refused without an existence oracle.
- **SC-005**: A Recruiter can apply a single action to at least 50 selected candidates in one step, every candidate receives an individual succeeded/skipped outcome, and no candidate receives a duplicate effect even under concurrent submission.
- **SC-006**: A bulk send to a set that includes non-contactable candidates results in 0 messages sent to non-contactable candidates and a single coarse "not contactable" outcome for each (byte-identical across all underlying causes), while all contactable candidates are actioned and individually personalised.
- **SC-007**: A candidate's timeline shows 100% of their email, scheduling, status, and feedback events in correct chronological order.
- **SC-008**: Zero occurrences of candidate personal data (name, email, phone, message/status/scorecard content) appear in application logs across pipeline, bulk-action, and timeline operations (verified by a CI log/sentinel scan).
- **SC-009**: An erased candidate appears in no role's pipeline and in no role's timeline, and the introduction of the requisition link does not alter existing candidate reads (dashboard, SLA scan, scheduling) — verified by regression tests.

## Assumptions

- **Terminal-state definition (no new lifecycle field)**: there is no general candidate "offer made / rejected / closed-out" lifecycle field in the system today. For this feature, a candidate is treated as **terminal/closed** when their published status outcome is a completion (offer or rejection) or they are erased; a cancelled-out scheduling state alone does not make a candidate terminal. "Active" means not erased and not terminal. If richer lifecycle states are wanted later, that is a separate feature — F51 will not invent one.
- **Requisition scope is minimal-but-real**: F51 introduces requisitions only to the extent needed to (a) group/filter the pipeline and (b) scope Hiring Managers. A full requisition-management product surface (descriptions, hiring teams, approval workflows, headcount, stages-per-requisition) is **out of scope** for the MVP. A requisition here is essentially a title + open/closed state + assignable owner(s).
- **Candidate→requisition linkage is one-to-(zero-or-one)** and the link is an internal id, not personal data: it is retained on erasure (non-PII anchor) and views exclude erased candidates via the active-state predicate. Multi-requisition candidates are out of scope.
- **Linkage is set manually by Recruiters/Admins**, with the external job id/label of ATS/CSV-sourced candidates merely *surfaced* to assist that manual linking (FR-011). No automatic reconciliation.
- **Bulk "close out" is deferred**: the backlog FR-14 wording ("schedule, update, or close out") is satisfied for the MVP by the two bulk verbs "send scheduling link" and "send update/holding email." A bulk reject/close-out action overlaps candidate-status and erasure flows and is deferred to a later feature; this is a documented, intentional narrowing.
- **Bulk actions reuse existing single-candidate capabilities** (scheduling-link initiation and consent-gated templated email send) as a per-candidate fan-out; the email verb's repeat-safety is inherited from the existing send-idempotency, and the scheduling-link verb's repeat-safety is the existing supersede semantics (no second concurrent booking).
- **The pipeline is an internal staff screen**: no candidate-facing accessibility or mobile-performance gates apply (consistent with the dashboard precedent), but the internal responsiveness targets above do.
- **Polling, not push**: live status freshness is achieved by periodic refresh on a default 60-second cycle, not a realtime push channel.
- **Hiring Manager assignment is performed by an Admin** through the existing member/assignment administration, not by Hiring Managers self-selecting requisitions.
- **Five roles already exist** (Admin, Recruiter, Hiring Manager, Interviewer, Read-only) and their authentication/authorization mechanisms are reused unchanged.

## Dependencies

- **Candidate records, SLA classification, scheduling state, email delivery, feedback state, and the role/assignment model** all already exist and are consumed (not rebuilt) by this feature. The SLA colour calls the existing classifier; bulk sends fan out over the existing consent-gated send paths; the timeline is a read-only merge of existing candidate-keyed records.
- **The requisition concept and the candidate→requisition link are introduced by this feature** (the requisition assignment resource type already exists in the assignment model, reserved for exactly this) and unblock previously-deferred Hiring Manager scoping in the dashboard (F50) and elsewhere.
- This feature is the last in the recommended delivery sequence and assumes the candidate-data-producing features (scheduling, status, SLA, feedback, ATS/CSV ingestion) are complete.
- **Regression scope**: because this feature adds a field to the candidate record and a new candidate-list read path, the plan MUST include regression tests that existing candidate reads (dashboard, SLA scan, scheduling) and erasure semantics are unaffected (SC-009).
