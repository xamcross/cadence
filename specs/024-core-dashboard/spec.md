# Feature Specification: F50 Core Dashboard

**Feature Branch**: `024-core-dashboard`  
**Created**: 2026-06-18  
**Status**: Draft  
**Input**: User description: "find the next unimplemented task in the backlog and create a spec for it. review with subagents"

> **Backlog mapping**: This is **F50 — Core Dashboard** (Tier 4, P3 — Visibility), the next unimplemented MVP feature in `docs/backlog.md`. Specs 001–021 cover F00→F42; the only remaining MVP items are F50 (this spec) and F51 (Pipeline View, which depends on F50). Spec ref: §4 Pillar C, §5.4 (FR-21), §11 MVP.

## User Scenarios & Testing *(mandatory)*

The Core Dashboard gives a workspace's recruiting leads a single, at-a-glance view of operational health across three MVP metrics: **time-to-schedule**, **no-show rate**, and the **current silence list** (candidates exceeding their SLA window). It answers "is our scheduling working, and who is falling through the cracks right now?" without anyone running a report or exporting raw data. All numbers are computed from existing event records the system already captures (scheduling lifecycle, no-show signals, last-contact timestamps), so the dashboard is a read-only lens — it never originates candidate communications or changes pipeline state.

### User Story 1 - See scheduling velocity and no-show rate for a chosen window (Priority: P1)

As a Recruiter or Admin, I open the dashboard and immediately see the median time-to-schedule and the no-show rate for a window I can choose (default: the last 30 days), so I can tell whether our scheduling process is healthy and trending the right way.

**Why this priority**: These two metrics are the dashboard's core reason to exist — they quantify whether the scheduling product is delivering value. Without them the dashboard is empty. This story is independently demonstrable: a workspace with booked and past interviews shows real numbers.

**Independent Test**: Seed a workspace with several completed scheduling requests (link sent → slot booked) and some past interviews (some confirmed-attended, some stamped no-show). Open the dashboard as a Recruiter, confirm the median time-to-schedule and no-show rate display for the default 30-day window, change the window, and confirm both recompute against the new window.

**Acceptance Scenarios**:

1. **Given** a workspace with five interviews booked in the last 30 days, **When** a Recruiter opens the dashboard, **Then** the median time-to-schedule (time from scheduling link sent to slot confirmed) is displayed for the rolling-30-day window.
2. **Given** ten past interviews in the window of which two were stamped no-show, **When** a Recruiter views the no-show rate, **Then** it reads 20% (2 of 10) and is clearly labelled with the window and the count it is based on.
3. **Given** the dashboard is open with the default window, **When** the user selects a different window (e.g. last 7 days), **Then** both metrics recompute for that window and the selected window persists for the rest of the session.
4. **Given** a workspace with no booked interviews in the window, **When** a Recruiter views the metrics, **Then** each metric shows a clear "no data for this window" empty state rather than a zero, an error, or a blank.
5. **Given** an interview booked in the window whose start time is still in the future, **When** the no-show rate is computed, **Then** that interview is excluded from the denominator (it cannot be a no-show until its start has passed).
6. **Given** no interviews in the window have a start time that has passed, **When** the no-show rate is viewed, **Then** it shows the "not applicable / no interviews yet" state rather than 0%.
7. **Given** a single scheduling request that was rescheduled twice before being confirmed, **When** the metrics are computed, **Then** it contributes exactly one time-to-schedule measurement and at most one no-show-denominator entry (no double-counting of intermediate rounds).
8. **Given** the median is computed over an even number of booked requests, **When** the median time-to-schedule is displayed, **Then** it equals the arithmetic mean of the two central values.

---

### User Story 2 - See who is currently going silent (Priority: P1)

As a Recruiter or Admin, I see a live list of candidates who are currently exceeding (or approaching) their SLA silence window, so I know exactly who needs a touch today without scanning the whole pipeline.

**Why this priority**: The silence list is the dashboard's actionable surface — it turns the abstract no-show / SLA machinery into a concrete "contact these people" list. It is independently valuable even if the velocity metrics were absent, and it reuses the breach classification the SLA engine already computes.

**Independent Test**: Seed candidates whose last meaningful activity is older than the workspace silence window (and some just inside it), open the dashboard, and confirm the silence list shows the breached/at-risk candidates, ordered by how long they have been silent, excluding terminal-outcome and erased candidates.

**Acceptance Scenarios**:

1. **Given** a workspace silence window of 5 days and a candidate whose last contact was 6 days ago, **When** a Recruiter views the silence list, **Then** that candidate appears flagged as breached (red).
2. **Given** a candidate nearing but not yet past the silence window, **When** the silence list is viewed, **Then** the candidate appears flagged as at-risk (amber), distinct from breached.
3. **Given** a candidate whose process is complete (offer or rejection) or whose data has been erased, **When** the silence list is viewed, **Then** that candidate does **not** appear, regardless of last-contact age.
4. **Given** the SLA engine marks a new breach, **When** the dashboard silence list refreshes, **Then** the newly breached candidate appears in the list within one refresh cycle.

---

### User Story 3 - Export dashboard data to CSV (Priority: P2)

As an Admin, I can export the current dashboard data (the three metrics and the silence list) to a CSV file, so I can share it with leadership or analyse it offline.

**Why this priority**: Export is required MVP value (US-F50-4) but is secondary to seeing the metrics in-app. It is independently testable and adds the "take it with you" capability on top of stories 1 and 2.

**Independent Test**: With a populated dashboard, an Admin triggers export, downloads a CSV reflecting the same window and figures shown on screen, and confirms the file opens cleanly in a spreadsheet tool with no formula execution from any candidate-derived text.

**Acceptance Scenarios**:

1. **Given** a populated dashboard for a selected window, **When** an Admin exports to CSV, **Then** the file contains the three metric values and the silence-list rows matching what is shown on screen for that window.
2. **Given** a candidate display value that begins with a spreadsheet formula trigger (e.g. `=`, `+`, `-`, `@`), **When** the CSV is opened in a spreadsheet, **Then** the cell is shown as literal text and no formula executes.
3. **Given** a Read-only user, **When** they view the dashboard, **Then** they can see the metrics but the export action is unavailable to them.
4. **Given** a candidate erased after they appeared silent, **When** an Admin exports the dashboard, **Then** the erased candidate contributes no name or other personal data to the CSV.
5. **Given** an export is performed, **When** the audit log is inspected, **Then** an attributable export event is present (actor, workspace, window, row count) with no candidate names recorded.

---

### User Story 4 - Role-appropriate access (Priority: P2)

As the system, I enforce who can see and export the dashboard so that candidate data exposure stays scoped to the minimum necessary roles.

**Why this priority**: Access control is a constitution C3 gate (PII exposure scoped to minimum roles) and a security requirement, but it sits on top of the dashboard existing at all. It is independently testable via API contract tests per role.

**Independent Test**: Call the dashboard read and export endpoints as each role and assert the access matrix (Admin/Recruiter full; Read-only view-only no export; Interviewer denied).

**Acceptance Scenarios**:

1. **Given** an Interviewer, **When** they request dashboard data, **Then** the request is denied (forbidden) and no metric or silence data is returned.
2. **Given** a Read-only user, **When** they request the CSV export, **Then** the export is denied (forbidden) while the read-only view remains available.
3. **Given** a Read-only user, **When** they request dashboard read data, **Then** the metrics and silence list are returned (view access is permitted).
4. **Given** a Hiring Manager (deferred in MVP), **When** they request dashboard data, **Then** the request is denied (forbidden) — they are not shown unscoped cross-requisition data.
5. **Given** any role with a direct API call supplying another workspace's identifier, **When** they request dashboard data, **Then** only their own workspace's data is ever returned (the supplied identifier is ignored; no cross-workspace leakage and no existence oracle).

---

### Edge Cases

- **Empty / new workspace**: A workspace with no scheduling history shows explicit empty states for every metric and an empty silence list — never an error, a misleading 0%, or a blank panel.
- **Division by zero for no-show rate**: When no interviews have occurred in the window, the no-show rate is presented as "not applicable / no interviews yet", not 0% and not an error.
- **In-flight vs completed interviews**: Future-dated booked interviews are excluded from the no-show rate (an interview that has not happened cannot be a no-show); only interviews whose start time has passed count toward the denominator.
- **Reschedules and cancellations**: A rescheduled or cancelled booking is not double-counted; time-to-schedule and no-show counts reflect the final state of each scheduling request, not every intermediate round.
- **Candidate erased mid-window**: An erased candidate is excluded from the silence list and contributes no personal data to any export, even if their (now-anonymised) scheduling events fall in the window.
- **Window with partial data**: Selecting a window that predates the workspace's first activity returns valid empty/low-count results, not an error.
- **Large workspace**: A workspace with thousands of candidates and interviews still returns the dashboard within the performance target (see Success Criteria) and the silence list remains bounded/paginated rather than returning an unbounded result set.
- **Concurrent SLA refresh**: The silence list reflects breaches consistently even while the background SLA scanner is updating breach state.
- **Terminal-outcome candidate with old activity**: A candidate marked offer/rejected with stale last-contact does not appear in the silence list (terminal outcomes are never "silent").
- **Multiple requests per candidate**: A candidate with several scheduling requests in the window contributes one time-to-schedule measurement and one no-show-denominator entry **per request** (counting unit is the request, not the candidate — FR-003).
- **Reschedule across the window boundary**: A request booked inside the window but rescheduled so its final start lands outside the window (or vice-versa) is attributed to its **final** confirmed start for the no-show window and its **final** confirmed time for the velocity window.
- **Candidate exactly at the silence threshold**: A candidate whose last activity is exactly at the threshold instant is classified deterministically by the SLA engine's absolute-duration boundary (no off-by-one flap), matching the SLA nudge engine's verdict.

## Requirements *(mandatory)*

### Functional Requirements

**Metrics — Time-to-Schedule**

- **FR-001**: The system MUST compute and display the **median time-to-schedule** for a selected time window, defined as the elapsed time from when a candidate's scheduling link was sent to when the candidate confirmed a slot. The median MUST be computed as: for an odd count of measurements, the middle value; for an **even count**, the arithmetic mean of the two central values. The figure MUST be reported in a fixed unit (hours, to one decimal place); the underlying durations are stored as absolute elapsed time (start-to-confirm), independent of any time-zone or DST boundary.
- **FR-002**: Time-to-schedule MUST be computed only from scheduling requests whose **slot-confirmed time falls within the selected window** and that reached a confirmed/booked final state; requests still awaiting selection, expired, superseded, or cancelled MUST NOT contribute a measurement.
- **FR-003**: The unit of counting is the **scheduling request**, not the candidate: each scheduling request contributes at most one time-to-schedule measurement (its final booked outcome), and a candidate with multiple scheduling requests in the window contributes one measurement per request. Reschedule rounds of a single request MUST NOT add extra measurements.
- **FR-003a**: The time-to-schedule window is anchored on **slot-confirmed time** (FR-002), whereas the no-show window is anchored on **scheduled interview start** (FR-005). These two anchors are intentionally different (velocity is "how fast did we book", no-show is "of interviews that should have happened"); the dashboard MUST label each metric so the anchor is unambiguous.

**Metrics — No-Show Rate**

- **FR-004**: The system MUST compute and display the **no-show rate** for a selected window as `no-shows ÷ qualifying interviews`. The authoritative no-show signal for an interview is the system's **no-show stamp** (the marker set when a confirmed interview's scheduled start is reached without attendance confirmation); an interview is a no-show if and only if it carries that stamp. The numerator and denominator MUST count by **scheduling request** (final state), consistent with FR-003.
- **FR-005**: An interview qualifies for the no-show denominator only if its **scheduled start falls within the selected window AND has already passed** (relative to the current time). Future-dated interviews, and interviews whose start is outside the window, MUST be excluded. A request rescheduled so that its final scheduled start moves into/out of the window is counted by that final start.
- **FR-006**: The system MUST present the no-show rate together with the underlying counts (no-shows and total qualifying interviews) so the figure is interpretable.
- **FR-007**: When the denominator is zero (no past interviews in the window), the system MUST present a clear "not applicable / no interviews yet" state rather than 0% or an error.

**Metrics — Silence List**

- **FR-008**: The system MUST display a **silence list** of candidates currently exceeding or approaching their workspace SLA silence window, classified as breached (red) or at-risk (amber). The breached/at-risk classification MUST reuse the existing SLA engine's classification (its silence-window threshold and its amber margin), so the dashboard and the SLA nudge engine never disagree on who is breached; the dashboard MUST NOT introduce its own independent threshold.
- **FR-009**: The silence list MUST be computed at read time and MUST exclude candidates whose process has reached a terminal outcome (offer or rejection) and candidates who are not in an active (non-erased) state. Exclusion of terminal-outcome and non-active candidates MUST be applied at the source of the query (so such candidates never enter the snapshot that feeds either the screen or the export), not only at the formatting layer.
- **FR-010**: The silence list MUST order candidates by severity / duration of silence (most overdue first) and MUST be bounded by an explicit cap / pagination (default cap MUST be a fixed finite number, e.g. the top 100 most-overdue) so it never returns an unbounded result set; this cap also bounds the number of candidate names decrypted per request.
- **FR-011**: The silence list MUST stay consistent with the SLA engine's breach classification, reflecting newly marked breaches within one dashboard refresh cycle (an upper bound the refresh test can assert against, even though the refresh mechanism — load, on-demand, or short poll — is a planning detail).
- **FR-012**: Each silence-list entry MUST surface only the **candidate's name, an internal candidate identifier, and the silence duration / severity** — the minimum needed for a recruiter to identify and act. It MUST NOT surface candidate email, phone, or any other contact detail in the list view. The decrypted name on this authenticated internal screen is treated as the minimum-necessary identifier (the existing internal-preview/pipeline precedent); the response carries no field that would let the surface later widen to email/phone.

**Time Window**

- **FR-013**: All three metrics MUST be scoped to a user-selectable time window, defaulting to a rolling 30 days.
- **FR-014**: The selected window MUST persist for the duration of the user's session (retained client-side in session state) so navigating away and back within the same session retains the choice; a new session may reset to the default window.
- **FR-015**: The system MUST offer a fixed, predefined set of windows — **last 7, last 30, and last 90 days** — and MUST accept only one of these values. The window MUST NOT be an arbitrary client-supplied range; this both satisfies the stories and bounds aggregation cost (a crafted unbounded window cannot be a resource-exhaustion vector).

**Export**

- **FR-016**: Authorised users MUST be able to export the current dashboard data (the three metrics and the silence list) for the selected window to a CSV file.
- **FR-017**: The exported CSV MUST reflect the same window and figures shown on screen at export time.
- **FR-018**: Any candidate-derived text written to the CSV MUST be neutralised against spreadsheet formula injection (cells beginning with formula triggers `=`, `+`, `-`, `@`, tab, or carriage-return — including after a leading space/newline/BOM — are stored as literal text, never executed) — reusing the established export-boundary sanitiser rather than introducing new escaping logic.
- **FR-019**: Erased candidates MUST NOT contribute personal data to any export; this follows from the read-time exclusion at the query source (FR-009), so an erased candidate is absent from the snapshot that feeds the export.
- **FR-019a**: The export artefact MUST NOT be persisted server-side beyond the request (it is a transient download), leaving no residual file containing candidate names at rest.
- **FR-019b**: Because the CSV is a deliberate PII egress path, each export MUST be recorded in the audit log as an attributable event (actor, workspace, window, row count — internal identifiers only, no candidate names in the audit), so a bulk extraction is accountable.

**Access Control & Scope**

- **FR-020**: Dashboard read access MUST be restricted to **Admin, Recruiter, and Read-only** roles; Interviewer MUST be denied (forbidden) and receive no metric or silence data. Hiring Manager is **deferred** in the MVP and treated as denied — see FR-026. A denied role MUST receive a forbidden response (the standard authorization-denied envelope), not a partial result.
- **FR-021**: Export MUST be restricted to **Admin and Recruiter**; Read-only and Interviewer MUST be denied export (forbidden) while Read-only retains view access. The new dashboard read and export endpoints MUST be covered by the project's deny-by-default endpoint inventory (the build-time check that fails on any internal endpoint lacking declared role security).
- **FR-022**: All dashboard data MUST be workspace-scoped server-side, derived from the authenticated principal's workspace; the server MUST NOT trust any client-supplied workspace identifier. A user MUST never receive metrics or silence-list entries from another workspace, even via a direct API call. Errors MUST use the scoped error envelope consistent with prior internal endpoints (no response that acts as a cross-workspace existence oracle).

**Computation & Persistence**

- **FR-023**: Dashboard figures MUST be computed from persisted event records (scheduling lifecycle, no-show signals, last-contact timestamps), not from in-memory state, so the dashboard survives a restart and reflects durable history.
- **FR-024**: Metric computation MUST NOT mutate any candidate, scheduling, or communication state — the dashboard is strictly read-only and MUST NOT trigger any outbound candidate communication. This guarantee MUST be **structural and verifiable** (the dashboard's read/aggregation code holds no reference to the email-dispatch, calendar-write, or state-mutating persistence paths), following the established no-auto-send structural-test precedent.
- **FR-025**: Dashboard reads and exports MUST NOT write plaintext PII (candidate name, email, phone) to application logs; only anonymised internal identifiers may be logged. The CI log-scan gate MUST be extended with this feature's candidate-name/email sentinels so the guarantee is enforced, not vacuous.

**Hiring-Manager Scope (deferred — documented constraint)**

- **FR-026**: The MVP dashboard MUST scope all metrics and the silence list at the **workspace** level (FR-020/FR-022 enforce this). Per-requisition breakdown and Hiring-Manager "own requisitions only" filtering are **deferred to F51 (Pipeline View)**, which owns the candidate→requisition→assignment linkage that does not yet exist in the system. The dashboard MUST NOT present a per-requisition view it cannot correctly scope, and Hiring Managers MUST be treated under the deferred-scope decision (see Assumptions) rather than shown unscoped cross-requisition data. *(Follows the F32/F40 precedent: HM→requisition scoping deferred because no assignment link exists; building it in F50 would be a stub (§II) or scope-creep (§I).)*

**Presentation / Accessibility**

- **FR-027**: The dashboard is an **internal** (authenticated, staff-facing) screen; the candidate-facing Lighthouse-≥85 and WCAG-2.2-AA acceptance gates explicitly **do not apply** and there is **no automated presentation gate** for this screen (documented to prevent ambiguous CI gate failures, per the backlog F50 note and the F50/F51 internal-screen precedent).

### Key Entities *(include if feature involves data)*

- **Dashboard Snapshot (computed, not stored)**: The set of figures for a given workspace and window — median time-to-schedule, no-show rate (with counts), and the silence list. Derived on read from existing records; not a new persisted collection.
- **Scheduling Request (existing)**: Source of time-to-schedule (link-sent and booked timestamps) and no-show counting (booked start time, no-show signal, attendance confirmation). Final-state per request.
- **Candidate (existing)**: Source of silence-list membership via last-meaningful-activity timestamp, terminal-outcome state, and erasure state; identity for display.
- **Workspace Configuration (existing)**: Source of the SLA silence window length that defines breach for the silence list.
- **CSV Export (transient artefact)**: The downloadable file produced from the Dashboard Snapshot, with candidate-derived cells injection-neutralised.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A Recruiter or Admin can open the dashboard and see all three metrics for the default 30-day window in a single view without running a report or exporting anything.
- **SC-002**: Changing the time window recomputes all three metrics for the new window, and the chosen window is retained for the rest of the session.
- **SC-003**: For a seeded dataset, the no-show rate exactly matches an independent manual count of (no-show-stamped requests ÷ past qualifying interviews) for the window, and the median time-to-schedule matches a manual median (mean-of-two-central for even N, in hours to one decimal) of the same booked requests — including a seeded request exactly at the silence threshold and an even-N median case, both yielding deterministic expected values.
- **SC-004**: The silence list contains exactly the candidates whose last meaningful activity is older than the workspace silence threshold and who are active and non-terminal — no terminal-outcome or erased candidate ever appears.
- **SC-005**: A candidate marked breached by the SLA engine appears on the dashboard silence list within one refresh cycle.
- **SC-006**: An exported CSV opened in a common spreadsheet tool executes no formulas from candidate-derived text and contains no personal data for erased candidates.
- **SC-007**: Role enforcement holds (verified by per-role contract tests): Interviewer → forbidden, no data; Hiring Manager (deferred) → forbidden; Read-only → can view, export forbidden; Admin/Recruiter → view and export; and a request supplying another workspace's identifier returns only the caller's workspace data with no oracle.
- **SC-008**: The dashboard returns within 3 seconds (single-run, warm) for a seeded workspace of at least 200 active candidates and at least 1,000 booked scheduling requests spread across the window, with all aggregations index-backed.
- **SC-009**: Dashboard reads and exports produce zero plaintext-PII matches in application logs, verified by the CI log-scan gate extended with this feature's name/email sentinels.
- **SC-010**: Every displayed figure survives an application restart unchanged (computed from durable records, not memory).
- **SC-011**: The dashboard's computation path is structurally read-only — a structural test confirms it holds no reference to the email-dispatch, calendar-write, or state-mutating persistence paths — so no dashboard operation can originate a candidate communication or mutate pipeline state.
- **SC-012**: Every CSV export emits exactly one attributable audit event (actor, workspace, window, row count; no candidate names), and no export artefact remains persisted server-side after the request.

## Assumptions

- **Backlog selection**: "The next unimplemented task in the backlog" is **F50 — Core Dashboard**. Specs 001–021 implement F00 through F42; per the backlog delivery sequence F50 precedes F51 (Pipeline View depends on the dashboard). F51 is intentionally left as the subsequent feature.
- **Three metrics only**: Per §11 MVP and the backlog, the dashboard ships exactly the three core metrics (time-to-schedule, no-show rate, silence list). Recruiter-hours-saved, feedback-turnaround analytics, and candidate-pulse/NPS are explicitly deferred to v1.5/v2 and are out of scope.
- **Hiring-Manager / per-requisition scoping deferred to F51**: The system has **no** candidate→requisition→assignment link today (confirmed against current code; consistent with the F32/F40/F51 notes). Building one now would require a stub (§II) or pull forward F51 work (§I YAGNI). Therefore the MVP dashboard is **workspace-scoped**, with no per-requisition breakdown. The Hiring-Manager "own requisitions only" row of the backlog access matrix is satisfied only once F51 introduces the requisition linkage; until then a Hiring Manager is **not** granted the dashboard (treated as not-permitted rather than shown unscoped cross-requisition data), preserving the C3 minimum-exposure gate. This decision is called out for confirmation in the planning phase.
- **No-show signal**: The MVP no-show signal is the existing no-show stamp set when a confirmed interview's start time is reached without attendance confirmation. "Interview happened" for the denominator means a booked request whose start time has passed within the window.
- **Time-to-schedule definition**: Measured from scheduling-link-sent to slot-confirmed, using the request's final booked outcome. Reschedules use the final confirmed booking, not each intermediate round.
- **Computation strategy**: Figures are computed on read via bounded, index-backed aggregation over existing collections; no new precomputed metric collection is introduced (no new infrastructure — C2). The plan MUST declare the specific indexes the aggregations rely on (the project's index-bootstrapping convention); the candidate silence scan reuses the existing `{workspaceId, lastContactAt}` index, and the scheduling aggregations rely on a booked-state + start/confirm-time index. Because there is no precompute, an erasure or terminal-outcome change is reflected on the next read with no lag window.
- **Silence-list identity**: The list surfaces candidate name + internal id + silence duration only; the recruiter must know who to contact, so the decrypted name is the minimum-necessary identifier on this internal screen. There is no non-PII human label available (a candidate has no reference number), which is why name is used; email/phone are never included.
- **Export sanitisation reuse**: CSV export reuses the existing export-boundary injection sanitiser (the F42 escaper) rather than introducing new escaping logic.
- **Refresh model**: The silence list reflects SLA breaches within one dashboard refresh; whether refresh is on page load, on demand, or on a short poll is a UX/planning detail, provided SC-005 holds.
- **Internal screen**: The dashboard is staff-facing and internal; candidate-facing performance/accessibility CI gates do not apply (documented to avoid ambiguous gate failures), though the screen must remain usable.
- **No new outbound communications**: The dashboard never sends candidate email or changes pipeline state; it is a read-only lens over data other features produce.
