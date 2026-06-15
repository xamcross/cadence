# Feature Specification: Calendar Integration — Microsoft 365 / Outlook

**Feature Branch**: `008-microsoft-calendar`
**Created**: 2026-06-15
**Status**: Draft
**Backlog ID**: F11 (Tier 1 — Critical Path, P1)
**Input**: User description: "F11 Microsoft 365 / Outlook Calendar Integration — same functional scope as F10 via Microsoft Graph, implementing the CalendarProvider interface, tokens stored encrypted via F01.1, free/busy field-projected so no event content is read."

## Overview

F10 delivered the Google Calendar provider adapter behind the domain `CalendarProvider` abstraction: free/busy availability reads, idempotent event create/update/delete, a compensating-delete rollback primitive, bounded retry, and DST-correct times. This feature delivers the **Microsoft 365 / Outlook adapter** — the exact same set of capabilities and the same internal model, but spoken through **Microsoft Graph** instead of the Google Calendar API.

The adapter does the same two jobs, both strictly minimised to what scheduling needs:

1. **Read availability** — given a set of connected members and a time window, return each member's busy intervals (free/busy only — never event subjects, attendees, or body text), normalised into the **same internal availability model F10 produces** so the scheduler (F12 rule engine, F13 booking flow) treats Google and Microsoft participants identically.
2. **Write calendar events** — create, update, and delete a Cadence-managed interview event on a member's Outlook calendar (subject/title, time, location/dial-in text supplied by the recruiter — no auto-generated video link, deferred to v1.5), with the idempotent create and compensating delete an atomic booking (F13) needs to roll back cleanly on partial failure.

Microsoft Graph has a materially different surface from Google: free/busy is read via the **`getSchedule`** action and a calendar read, unless explicitly field-projected, can return full event content (subject, body, attendees). The constitution §VIII least-content requirement therefore becomes a **hard, explicit field-projection contract** for this adapter (Security ISSUE-2). The adapter is wrapped behind the same `CalendarProvider` abstraction so business logic never references Graph types directly, tolerates Graph's throttling (`429` with `Retry-After`), transient outages, and DST boundaries, and — because it normalises into the same internal model as F10 — makes **mixed Google + Microsoft panels schedulable in a single flow** the first time both adapters are present.

This feature does **not** own the end-to-end booking orchestration (atomic slot reservation, the scheduling email, the pipeline status) — that is F13; this feature delivers the Microsoft calendar operations F13 composes, mirroring F10.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Read a Microsoft 365 panel's availability for scheduling (Priority: P1)

As the scheduling system, given internal participants who have connected their Microsoft 365 / Outlook calendars and a target time window, I can retrieve each participant's busy intervals — using a field-projected Graph query that returns no event content — so that only genuinely free, rule-compliant slots are ever offered to a candidate.

**Why this priority**: Availability reading is the capability everything downstream (rule engine, slot computation, slot-picker) depends on for Microsoft users, exactly as for Google. It is independently demonstrable: a connected member's busy times can be fetched and shown without any event ever being written. The Graph field-projection is the load-bearing privacy control and must be proven here.

**Independent Test**: Connect a test member's Microsoft calendar (via the F01.1 flow against a stubbed Graph service), seed that calendar with one busy event that includes a subject, body, and attendees, request availability for that member over a window covering it, and confirm Cadence returns exactly that busy interval — normalised into the **same internal model F10 uses** — and that the subject, body, and attendee fields are physically absent from what Cadence reads (field-projected query), not merely dropped after the fact.

**Acceptance Scenarios**:

1. **Given** a connected Microsoft 365 member with one busy interval in the requested window, **When** the system queries that member's availability, **Then** it returns exactly that busy interval in the internal availability model, with correct start/end instants, identical in shape to a Google availability result.
2. **Given** a 5-person Microsoft panel, **When** the system queries availability for the whole panel over a window, **Then** it returns each member's busy intervals merged into the internal model within the panel-query performance target.
3. **Given** any Graph availability response, **When** it is processed, **Then** no event subject, attendee list, location, or body text is read into Cadence state or written to any log — only the busy/free time ranges and their schedule status (the read uses `getSchedule`, which structurally cannot return event content).
4. **Given** schedule items with statuses `busy`, `tentative`, `oof`, `workingElsewhere`, and `unknown`, **When** availability is computed, **Then** each is treated as not-schedulable (only `free` is schedulable) — no status is silently treated as free.
5. **Given** a busy event whose boundaries do not align to a fixed time grid (e.g. 09:10–09:25), **When** availability is computed, **Then** the returned interval reflects the exact boundaries, not a coarse quantized window.
6. **Given** a panel member whose Microsoft calendar is not connected (or needs reconnection), **When** availability is requested for the panel, **Then** the system reports that member as unavailable-for-scheduling with a clear, distinguishable reason rather than silently treating them as fully free.

---

### User Story 2 - Create an Outlook event when an interview is booked (Priority: P1)

As the scheduling system, when an interview slot is confirmed, I can create a calendar event on each Microsoft 365 participant's Outlook calendar with the correct subject, time zone, and recruiter-supplied location/dial-in details, so that every participant sees the interview on their own calendar.

**Why this priority**: Writing the confirmed event back is the other half of "bidirectional" and is what makes a booking real to Microsoft participants. It is the capability F13's booking flow composes for Microsoft users; demonstrable on its own by creating an event and observing it on the stubbed Graph calendar.

**Independent Test**: For a connected Microsoft member, create an interview event for a specific time window with a given subject and location text; confirm the event is created on that member's Outlook calendar with the exact wall-clock time (correct time zone), the supplied subject and location, and **no** auto-generated video link; the operation returns a stable Graph event reference Cadence can later update or delete.

**Acceptance Scenarios**:

1. **Given** a confirmed interview with a time, subject, and recruiter-supplied location text, **When** the system creates the event for a connected Microsoft participant, **Then** the event appears on that participant's Outlook calendar with the correct wall-clock time in their time zone, the given subject, and the given location — and no automatically generated meeting link.
2. **Given** a successful event creation, **When** it completes, **Then** the system retains a stable provider reference (Graph event identifier per participant) so the event can be updated or deleted later, and records the creation in the audit trail using internal identifiers only.
3. **Given** an interview booked for a time one hour before a daylight-saving-time transition, **When** events are created, **Then** every participant's event renders at the correct intended wall-clock time across the DST boundary (verified by a synthetic DST-crossing fixture, validating Microsoft's time-zone handling specifically).
4. **Given** the same booking is processed twice (a retried confirmation), **When** event creation runs again for the same participant and interview, **Then** the system does not create a duplicate event for that participant (idempotent create).

---

### User Story 3 - Update or cancel the Outlook event on reschedule/cancellation (Priority: P1)

As the scheduling system, when a booked interview is rescheduled or cancelled, I can update or delete the corresponding Outlook event for every Microsoft participant so that their calendars always reflect the current truth.

**Why this priority**: A calendar that shows a cancelled or moved interview at its old time is worse than no integration. Update/delete is also the compensating action an atomic booking needs to roll back a partially-created event set, so it is core, not polish.

**Independent Test**: Create an Outlook event, then (a) update it to a new time and confirm the participant's calendar shows the new time with the old slot gone, and (b) delete it and confirm it no longer appears; deleting an already-absent event is treated as success (idempotent delete).

**Acceptance Scenarios**:

1. **Given** an existing Cadence-created Outlook event, **When** the interview is rescheduled, **Then** the system updates that event in place to the new time for every participant and no stale event remains at the old time.
2. **Given** an existing Cadence-created Outlook event, **When** the interview is cancelled, **Then** the system deletes that event for every participant and it no longer appears on their calendars.
3. **Given** a delete (or update) targeting an event that Graph reports as already gone, **When** the operation runs, **Then** the system treats it as a successful no-op rather than failing (idempotent), so cleanup/rollback never gets stuck.
4. **Given** a booking in which the event was created for some participants but creation then failed for another, **When** the booking is rolled back, **Then** the system deletes the already-created events for the other participants so no orphaned interview event remains on any calendar.

---

### User Story 4 - Schedule a mixed Google + Microsoft panel in one flow (Priority: P1)

As the scheduling system, when a panel contains both Google and Microsoft 365 participants, I can read availability and create/update/delete events for the whole panel in a single scheduling flow, with both providers behaving identically against the same internal model, so that mixed-provider teams are schedulable without special-casing.

**Why this priority**: Real interview panels routinely mix providers; the whole point of the `CalendarProvider` abstraction and a shared internal model is that the scheduler need not know which provider any member uses. This becomes possible for the first time the moment both adapters exist, so it is a P1 capability of F11 (not polish), and it carries the cross-provider rollback guarantee.

**Independent Test**: With one member connected to a stubbed Google service and another to a stubbed Graph service, query availability for the two-member panel and confirm both members' busy intervals come back in one normalised result set; then book an event for both and confirm both calendars receive it; then force the second provider's create to fail after retries and confirm the already-created event on the first provider is rolled back (no orphan on either provider).

**Acceptance Scenarios**:

1. **Given** a panel with both a Google and a Microsoft member, **When** availability is queried for the panel, **Then** both members' busy intervals are returned in the same internal availability model with no provider-specific shape leaking to the caller.
2. **Given** a mixed-provider booking, **When** events are created for the whole panel, **Then** each participant's event is created on their own provider's calendar with consistent wall-clock time and content.
3. **Given** a mixed-provider booking where one provider's event creation fails after retries, **When** the booking is rolled back, **Then** the already-created events on the *other* provider are deleted (compensating delete), leaving zero orphaned events on either provider.

---

### User Story 5 - Survive Microsoft Graph throttling and transient outages (Priority: P2)

As the scheduling system, when Microsoft Graph temporarily throttles or errors, I retry transient failures with bounded backoff (honouring Graph's `Retry-After` where present) so that a brief provider hiccup does not fail a booking or an availability read, while a genuinely permanent failure is surfaced rather than retried forever.

**Why this priority**: Microsoft Graph enforces per-app and per-mailbox throttling and returns `429` (often with a `Retry-After` header) plus transient `5xx`; without backoff, normal load produces spurious scheduling failures. Important for reliability but secondary to the read/write capabilities themselves. The policy is the same one F10 defined and is shared between adapters.

**Independent Test**: Stub the Graph service to return `429` (with and without a `Retry-After` header) or `503` for the first one or two attempts and then succeed; confirm the operation ultimately succeeds via bounded exponential backoff with jitter, honouring `Retry-After` when present (max retries enforced). Stub a persistent permanent error (e.g., revoked authorization or insufficient scope surfaced via F01.1) and confirm the operation fails fast with a clear, distinguishable outcome and no infinite retry.

**Acceptance Scenarios**:

1. **Given** Graph returns `429` or `503` then recovers, **When** an availability read or event write is attempted, **Then** the system retries with exponential backoff and jitter (bounded to the configured maximum, shared with F10) and the operation ultimately succeeds.
2. **Given** Graph returns `429` with a `Retry-After` header, **When** the system retries, **Then** it waits at least the indicated interval before retrying rather than retrying immediately.
3. **Given** Graph returns transient errors beyond the retry budget, **When** the budget is exhausted, **Then** the operation fails with a transient/retryable outcome distinct from a permanent failure, leaving no partial calendar state attributable to this adapter.
4. **Given** the member's authorization is permanently invalid (revoked grant, or a grant lacking the event-write scope), **When** any calendar operation is attempted, **Then** the system surfaces "needs reconnection" (via the F01.1 token store) without retrying indefinitely, and the participant is reported as unavailable-for-scheduling.
5. **Given** any retry sequence, **When** it runs, **Then** no token value, authorization code, or calendar event content appears in any log line at any level.

---

### Edge Cases

- **Graph returns content unless projected**: a `calendarView`/event read can carry subject, body, and attendees; the read MUST use `getSchedule` (which structurally returns only busy/free schedule items) so content never crosses the wire. A regression that swaps in an unprojected read must be caught (the privacy guarantee is structural, not post-hoc filtering).
- **Graph free/busy status values**: `tentative`, `oof` (out-of-office), `workingElsewhere`, and `unknown` are explicitly mapped — `tentative`/`oof` block the slot, and `workingElsewhere`/`unknown` fail safe (not-schedulable), never silently treated as free (which would double-book).
- **All-day / multi-day events in the window**: an all-day busy/out-of-office block is interpreted at its true span (midnight-to-midnight in the event's own zone) and contributes to unavailability per its status — not dropped or mis-spanned.
- **Recurring events**: free/busy reflects the **expanded** recurrence instances within the window (`getSchedule` expands them server-side); each occurrence in the window yields its own busy interval.
- **Coarse `availabilityView` vs exact boundaries**: busy intervals come from the schedule items' real start/end, so a busy block that does not align to a fixed grid is not rounded to a quantized window.
- **Windows vs IANA time-zone identifiers**: Graph may express times in Windows zone names; these are mapped to the IANA-based internal model so wall-clock times (and DST behaviour) are correct.
- **Panel larger than Graph's per-request schedule cap**: `getSchedule` accepts a bounded number of mailboxes per request (~20); a larger panel is chunked into multiple requests and merged, so a big panel neither errors nor silently drops members.
- **`Retry-After` header on `429`/`503`**: when Graph supplies a `Retry-After` (delta-seconds or HTTP-date), the backoff MUST honour it (wait at least that long) instead of the default jittered interval.
- **Mixed-provider panel**: Google and Microsoft members in one panel return one normalised result set; a failure on one provider during booking triggers compensating deletes on the other (no cross-provider orphan).
- **Unconnected / needs-reconnection participant in a panel**: reported as a distinct unavailable-for-scheduling reason, never treated as fully free.
- **Empty window / no busy intervals**: a member with nothing in the window returns an empty busy set (fully available), not an error.
- **DST boundary bookings**: events created across a spring-forward/fall-back transition must render at the intended wall-clock time using Microsoft's time-zone representation; stored/compared instants must be unambiguous (absolute time + the relevant zone), never naive local time.
- **Rotated/expired access credential mid-operation**: the adapter always obtains the token through the F01.1 store, so a just-expired access credential is transparently refreshed; the adapter never caches a raw token across operations.
- **Idempotent create on retry**: a retried booking must not create a second event for the same participant+interview.
- **Idempotent update/delete on a missing event**: updating or deleting an event Graph says is already gone is a success, so rollback and cancellation never wedge.
- **Partial-create rollback**: when an event is created for some participants but a later participant fails, the already-created events are deleted (compensating delete) so no orphan remains; a compensating delete that itself exhausts its retry budget is surfaced as "cleanup-incomplete", not silently reported clean.
- **Throttle vs permanent failure**: `429`/`5xx`/network = transient (retry with backoff+jitter); revoked grant / insufficient scope = permanent (surface needs-reconnection, no retry).
- **Unrelated meeting content**: at no point are subjects, attendees, descriptions, or locations of *non-Cadence* events read, stored, or logged — only busy/free intervals.
- **Time window bounds**: an availability query with an excessively large window is bounded/rejected so a single request cannot become an unbounded scan (same clamp as F10).

## Requirements *(mandatory)*

### Functional Requirements

**Availability (free/busy) reads**

- **FR-001**: The system MUST read, for one or more connected Microsoft 365 members and a bounded time window, each member's busy intervals via Microsoft Graph, normalised into the **same internal availability model F10 produces** (provider-neutral to the caller).
- **FR-002**: The system MUST read availability via Microsoft Graph's **`getSchedule`** action (per-mailbox busy/free schedule items) as the primary mechanism — analogous to Google's freeBusy-only endpoint, and chosen over `calendarView` because the latter's content-suppression depends on a `$select` that can silently regress. It MUST request and process **free/busy data only** and MUST NOT request, read, store, or log event subjects, attendees, descriptions, or locations of any calendar entry. **Important honest nuance** (see plan research D2): `getSchedule` on the caller's **own** mailbox *can* include `subject`/`location` on its schedule items, so — unlike Google's freeBusy, which is structurally content-free — the no-content guarantee for Graph is enforced by **parse-discipline** (the mapper reads **only** `start`/`end`/`status` via explicit JSON path reads; subject/location are never bound to any field) and **verified by the non-circular SC-004 test**, NOT by a structural endpoint property. A `calendarView` + field-projected `$select` is a documented degraded fallback only.
- **FR-002a**: The system MUST map every Graph free/busy status value onto the internal busy/free marker explicitly and safely: `busy` and `oof` (out-of-office) and `tentative` MUST be treated as **not schedulable** (block the slot); `free` is schedulable; `workingElsewhere` and `unknown` MUST be treated as not-schedulable (fail safe — never silently treated as free). The internal model MUST carry whatever marker the downstream rule engine needs to distinguish these, and the mapping MUST be covered by an acceptance test asserting each status value.
- **FR-003**: The system MUST normalise all returned busy intervals to unambiguous absolute time (instant plus the applicable time zone) so downstream rule evaluation and rendering are DST-correct, regardless of how Graph expresses the times. Exact interval boundaries MUST be derived from the schedule items' actual `start`/`end` instants, **not** from a coarse quantized `availabilityView` string, so a busy block that does not align to a fixed grid (e.g. 09:10–09:25) is represented at its true boundaries.
- **FR-003a**: The system MUST correctly interpret Graph's time-zone representation, which may use **Windows time-zone identifiers** (e.g. `"Eastern Standard Time"`) rather than IANA identifiers (e.g. `America/New_York`). The adapter MUST map between Windows and IANA zones (or force IANA via the appropriate Graph request preference) so that stored/compared instants and rendered wall-clock times are correct, especially across a DST boundary; a naive pass-through of a Windows zone name into the IANA-based internal model is prohibited.
- **FR-004**: When a requested member is not connected or needs reconnection, the system MUST represent that member as unavailable-for-scheduling with a reason distinct from "free", rather than omitting them silently or treating them as available.
- **FR-005**: The system MUST support reading availability for a multi-member panel in a single logical operation (not an unbounded per-member fan-out that breaks the panel performance target), consistent with F10's panel read.

**Event writes (create / update / delete)**

- **FR-006**: The system MUST create an Outlook calendar event on a connected member's calendar with a caller-supplied subject/title, start/end time (with explicit time zone), and recruiter-supplied location/dial-in text, and MUST NOT auto-generate any video-conference link (deferred to v1.5).
- **FR-007**: On successful creation the system MUST return/persist a stable per-participant Graph event reference sufficient to later update or delete that exact event.
- **FR-008**: The system MUST update an existing Cadence-created Outlook event in place (e.g., new time) without leaving a stale event at the prior time.
- **FR-009**: The system MUST delete a Cadence-created Outlook event such that it no longer appears on the participant's calendar.
- **FR-010**: Event creation MUST be idempotent for the same participant + interview: a retried create MUST NOT produce a duplicate event. *(Note: unlike F10's Google adapter — which derives a deterministic client-supplied event id and relies on the provider returning a duplicate-conflict — Microsoft Graph assigns its own server-side event id on create and does **not** accept a client-supplied id. F11's idempotency MUST therefore use a Graph-supported mechanism — e.g. a per-create natural-key/transaction identifier de-duplicated by the provider, or a check of the persisted server id before create — and update/delete MUST address the **persisted server-assigned id**, not a re-derived one. The concrete mechanism is decided in `plan.md`; this is a genuine divergence from F10, not a shared path.)*
- **FR-011**: Update and delete MUST be idempotent: targeting an event Graph reports as already absent MUST be treated as success, not failure.
- **FR-012**: The system MUST provide a compensating delete so that a partially-created set of events can be fully rolled back, leaving no orphaned events — including the **cross-provider** case where the panel mixes Google and Microsoft and a failure on one provider must roll back already-created events on the other. *(The atomic booking orchestration that invokes this rollback is F13; this feature provides the operation and the same-shaped outcome F10 produces.)*

**Mixed-provider behaviour**

- **FR-013**: The Microsoft adapter MUST normalise availability and event results into the exact same internal model as the Google adapter so that a mixed Google + Microsoft panel is read and booked through one provider-agnostic flow, with no provider-specific shape exposed to the caller.
- **FR-014**: A mixed-provider booking that fails on one provider after retries MUST roll back the already-created events on the other provider (no orphaned calendar events on either provider).

**Resilience & failure handling**

- **FR-015**: On a transient Graph failure (throttling `429`, server error `5xx`, or network error), the system MUST retry with bounded exponential backoff and jitter, up to the configured maximum number of attempts (default: 3 — the same policy and default shared with F10).
- **FR-016**: When Graph returns a `Retry-After` header (on `429` **or** `503`), the system MUST wait at least the indicated interval before the next retry rather than retrying on the default jittered interval. The header MUST be honoured in **both** its forms — a delta-seconds integer and an HTTP-date — and a malformed/absent header MUST fall back to the default bounded backoff.
- **FR-017**: When the retry budget is exhausted, the system MUST surface a transient/retryable outcome distinguishable from a permanent failure and MUST NOT leave partial calendar state attributable to the failed operation.
- **FR-018**: When an operation fails because the member's authorization is permanently invalid — a revoked/expired grant, **or a grant that lacks the event-write scope** — the system MUST surface a "needs reconnection" outcome (via the F01.1 token store) without retrying indefinitely, and report the member as unavailable-for-scheduling. The insufficient-scope case MUST be handled distinctly from a transient failure (a token refresh does not fix a missing scope).
- **FR-019**: When a **compensating delete** (rollback) cannot complete after its retry budget is exhausted, the system MUST surface a distinct "cleanup-incomplete" outcome for that participant and record it (audit, internal IDs only) so the orphaned event can be reconciled later, rather than silently reporting a clean rollback. A clean rollback (the normal case) leaves zero orphaned events.

**Credentials, isolation & secrecy**

- **FR-020**: The system MUST obtain the access credential for every calendar operation through the existing F01.1 token store (transparent refresh) for the Microsoft provider, and MUST NOT independently persist, cache across operations, or duplicate the raw credential.
- **FR-021**: The system MUST NOT write any token value, authorization code, or client secret to application logs at any level, during any operation (read, create, update, delete, failure, retry).
- **FR-022**: The system MUST NOT write **any** calendar event content to logs at any level — this includes both (a) the content of unrelated meetings encountered while reading availability and (b) the **Cadence-created interview event's own** subject and location/dial-in text (recruiter-supplied free text that routinely contains candidate name, email, or dial-in numbers, i.e. candidate PII per constitution §VIII).
- **FR-023**: The system MUST sanitise Graph response and error payloads before logging: raw response/error bodies — which may echo mailbox identifiers, attendee/account emails, or event snippets — MUST NOT be logged verbatim; only a non-PII summary (e.g., status code, classified outcome) may be logged.
- **FR-024**: The system MUST only access calendars of members within the actor's own workspace and MUST NOT read or write across workspace boundaries.
- **FR-025**: The provider account identifier (the member's Microsoft account email/subject) and attendee email addresses received from Graph in free/busy or event responses are personal data; the system MUST NOT log them and MUST NOT retain them in Cadence state beyond what F01.1 already holds encrypted.

**Abstraction & audit**

- **FR-026**: The Microsoft integration MUST be exposed through the domain `CalendarProvider` abstraction; business/scheduling logic MUST NOT reference Microsoft Graph SDK/API types directly, and the response model MUST be the same internal availability/event model the Google adapter (F10) produces.
- **FR-027**: The system MUST record calendar event lifecycle actions (created, updated, deleted) and a calendar-operation-triggered "needs reconnection" occurrence in the audit trail using internal identifiers only, with no credential or event-content payload. (The reconnection-state flip itself is owned and audited by F01.1; F11 records the scheduling-side occurrence so a member reported unavailable-due-to-revocation is traceable.)

### Key Entities *(include if feature involves data)*

- **Availability Window Query (transient)**: A request for busy/free over a bounded time window for a set of members within a workspace. Same shape as F10. Produces a set of per-member busy intervals.
- **Busy Interval (transient, internal model)**: One occupied time range for one member: absolute start, absolute end, applicable zone, and an opaque schedulable/not-schedulable marker (derived from the Graph status — `busy`/`tentative`/`oof`/`workingElsewhere`/`unknown` all not-schedulable, `free` schedulable) — never any event content. The **same** internal `TimeSlot`/free-busy model F10 produces; F11 normalises Graph results into it (if F10's marker has no slot for the extra Graph statuses, the internal model is widened in a provider-neutral way without changing F10's behaviour).
- **Managed Calendar Event**: A Cadence-created interview event projected onto each participant's calendar. Attributes: owning workspace, internal interview/booking reference, per-participant provider event reference (the stable Graph handle for update/delete), start/end with explicit zone, subject/title, recruiter-supplied location text, and the provider (Google vs Microsoft) so mixed-provider rollback can target the right adapter. Holds **no** content of unrelated calendar entries. (Persistence shared with / owned by the booking flow F13; F11 reuses the same managed-event store F10 introduced — the per-participant reference already carries a provider discriminator.)
- **Provider Operation Outcome (transient)**: The result of a calendar operation — success (with provider reference), transient/retryable failure, or permanent "needs reconnection" failure — distinguishable by the caller, identical in shape to F10's outcome so the booking flow handles both providers uniformly.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A free/busy query for a 5-person Microsoft panel returns normalised availability within 5 seconds under normal Graph latency.
- **SC-002**: A confirmed interview's Outlook event appears on each connected participant's calendar within 10 seconds of the create request.
- **SC-003**: Across the full read, create, update, and delete flows, an automated scan of all application logs finds **zero** occurrences of token values, authorization codes, client secrets, Microsoft account/attendee emails, or any calendar event content — including the Cadence-created event's own recruiter-supplied subject and location text.
- **SC-004**: 100% of availability responses expose only busy/free time ranges (start/end/status) in Cadence state — no event subject, attendee, or body of any meeting is ever read into state or logged. Verified by a **non-circular** adapter test that seeds `subject`/`location`/attendee content into the stub's `getSchedule` `scheduleItems` (content that *exists* server-side, as a real self-mailbox getSchedule would carry) and asserts none of it reaches the model, the preview response, or the logs — i.e. the parse-discipline (explicit start/end/status path reads) is the control, since for Graph the guarantee is enforced by mapping discipline rather than a structural endpoint property (FR-002).
- **SC-005**: An interview booked one hour before a DST boundary renders at the correct wall-clock time on every Microsoft participant's calendar (verified by a synthetic DST-crossing integration fixture exercising Microsoft's time-zone handling) in 100% of runs.
- **SC-006**: When Graph returns `429`/`503` and then recovers within the retry budget the operation succeeds with no manual intervention (and honours `Retry-After` when present); when the budget is exhausted or the grant is revoked/insufficiently-scoped, the failure is surfaced as transient vs permanent respectively, with no infinite retry and no orphaned calendar events (verified by stubbed-Graph tests).
- **SC-007**: A partial-failure booking leaves **zero** orphaned events after a successful rollback — including a mixed Google + Microsoft panel where a failure on one provider rolls back the other (verified by an integration test asserting the compensating delete against both stubs); if a compensating delete itself exhausts its retry budget, the affected participant is surfaced and audited as "cleanup-incomplete" (never silently reported clean).
- **SC-008**: An idempotent retry of a create produces exactly one Outlook event per participant+interview; an update/delete against an already-absent event reports success (no duplicate, no spurious error).
- **SC-009**: A mixed Google + Microsoft panel is read and booked through a single scheduling flow with no provider-specific branching exposed to the caller (verified by an integration test querying and booking a two-provider panel through the `CalendarProvider` abstraction, exercising rollback in **both** failure directions — Google-fails-roll-back-Microsoft and vice versa).
- **SC-010**: Every Graph free/busy status value (`free`/`busy`/`tentative`/`oof`/`workingElsewhere`/`unknown`) maps to the correct schedulable/not-schedulable outcome with no value silently treated as free (verified by an adapter test seeding each status), and busy intervals reflect exact (non-quantized) boundaries including across a Windows-time-zone-named DST fixture.
- **SC-011**: A calendar-operation-triggered "needs reconnection" occurrence (e.g. on an insufficient-scope or revoked-grant Graph response) is recorded in the audit trail with internal identifiers only and no credential or event-content payload (verified by asserting the audit record against a stubbed insufficient-scope response).

## Assumptions

- **Builds on F01.1, not a new token store**: All Microsoft credential acquisition and refresh, encrypted storage, and "needs reconnection" detection are provided by F01.1's token store, which already supports the Microsoft provider. This feature consumes that and does not re-implement it.
- **Mirrors F10, shares its internal model and resilience policy — but with real Graph-specific divergences**: F11 reuses the internal availability/event model, the managed-event store, the retry/backoff policy, the availability fan-out, and the compensating-delete primitive F10 introduced. Where F10 and F11 produce the same caller-visible behaviour, F10's behaviour is the contract. However, several areas are **not** drop-in reuse and need real new design in `plan.md`/`research.md`: (a) **idempotent create** — Graph forbids client-supplied event ids, so F10's deterministic-id + duplicate-conflict trick does not port (FR-010); (b) the **error classifier** — Graph's error JSON shape (`error.code` strings such as `ErrorAccessDenied`/`MailboxNotEnabledForRESTAPI`) differs from Google's `error.errors[].reason`, so the transient/permanent/insufficient-scope classification needs a Graph-specific mapping; (c) **free/busy status mapping**, **Windows↔IANA time zones**, and **`Retry-After`** handling (FR-002a/FR-003a/FR-016) are genuinely new. The spec flags these; the concrete mechanisms are owned by `plan.md`.
- **Planning carry-forwards (for `plan.md`/`research.md`, not new spec requirements)**: (1) verify F10's `ChangeUnit007` unique index `{workspaceId,bookingRef,memberId,provider}` keeps `provider` non-null so a Microsoft row and a Google row for the same member+booking cannot collide (reuse is index-safe only if the discriminator is always populated); (2) the logstash-encoder-9.0 enum→`StructuredArguments.kv()` Jackson-3 crash footgun applies to the new Microsoft provider enum value and the reused outcome enums — log `.name()` Strings only; (3) F11 inherits F10's accepted, F20-owned deferral whereby a re-create-after-delete (reschedule) does not emit a fresh `CALENDAR_EVENT_CREATED` audit (the upsert is an update) — this is not a new F11 regression.
- **Demonstrable slice, F13 owns the orchestration**: This feature delivers the Microsoft adapter's read + event-CRUD capabilities and the (cross-provider) compensating-delete behaviour, exercised end-to-end against a stubbed Graph service. The atomic slot reservation, the candidate self-scheduling email, and the pipeline status transitions are owned by F13; F13 composes the operations defined here. "Within 10 s of slot confirmation" is measured from the adapter's create request.
- **No auto-generated meeting link (MVP)**: Per backlog OD-1 and the deferred table, no Teams/Meet/Zoom link is generated; the event carries only recruiter-supplied location/dial-in text. Meeting-link generation is v1.5.
- **Least-privilege scope (constitution §VIII, Security ISSUE-1/ISSUE-2)**: The **read** path uses Graph's `getSchedule` busy/free action under `Calendars.Read` so no subject/body/attendee is ever requested or returned (structural, per FR-002). Writing events requires `Calendars.ReadWrite`. Unlike F10's Google `calendar.events.owned` (write access to **only** app-created events), Microsoft Graph offers **no owned-events-only delegated write scope**, so `Calendars.ReadWrite` is necessarily broader than F10's write grant — it technically also permits reading the member's full calendar. The §VIII justification in `plan.md` MUST explicitly acknowledge this, justify that no narrower Graph scope exists, and affirm that the adapter never exercises the read capability of the write grant (it only ever touches Cadence-created events on the write path, and the availability read still goes through the structural `getSchedule` control). A connection still holding only a read grant is detected on first write (insufficient-scope) and surfaced as "Needs reconnection" (FR-018), not failed opaquely.
- **Provider-agnostic internal model**: Because F11 normalises into the same model as F10, mixed Google + Microsoft panels (a single scheduling flow) work as soon as F11 lands; the caller never branches on provider.
- **No new infrastructure or dependency**: HTTP access to Microsoft Graph reuses the existing in-stack HTTP client approach (the same `RestClient`/gateway pattern F01.1 and F10 use, with the `JdkClientHttpRequestFactory` needed for `PATCH` already established by F10) behind the `CalendarProvider` abstraction; no broker, cache, or Microsoft Graph SDK that violates the dependency policy is added. Provider client credentials come from the existing secrets mechanism.
- **No-cloud-credentials testing**: Read/write/retry/`Retry-After`/DST/rollback and mixed-provider behaviours are verified against a stubbed/local Graph service (the same JDK-`HttpServer` stub approach F01.1/F10 used — a `StubGraphCalendar` sibling of `StubGoogleCalendar`), so CI needs no live Microsoft credentials.
- **DST correctness via absolute time**: All intervals and event times are handled as absolute instants plus an explicit zone (Graph's `dateTime`+`timeZone` shape mapped to the internal model); naive local-time arithmetic is never used for storage or comparison.

## Dependencies

- **F01.1 — OAuth Token Store** (complete): provides per-member encrypted Microsoft credentials, transparent refresh, and the `CalendarProvider`/`CalendarProviderClient` abstraction this feature implements for Microsoft.
- **F10 — Calendar Integration: Google Calendar** (complete): defines the internal availability/event model, the `managedCalendarEvents` store and its Mongock collection, the retry/backoff policy, the compensating-delete saga, the availability fan-out, the self availability-preview surface, and the stub-harness pattern that F11 reuses and mirrors.
- **F01 — Authentication & Session Management** (complete): authenticated member + workspace context for every operation.
- **F00.1 — MongoDB Index Bootstrapping**: F11 reuses F10's `managedCalendarEvents` collection and its indexes (the per-participant reference already carries a provider discriminator); no new collection is anticipated.
- **Consumed by F12 (Rule Engine), F13 (Flow A1 scheduling), and F20 (Reschedule/Cancel)**: these features call this adapter's availability read and event create/update/delete operations through the provider-agnostic abstraction, enabling mixed-provider panels.

## Out of Scope

- Atomic slot reservation, the candidate scheduling link/email, and pipeline status transitions (F13).
- Reschedule/cancellation *flow* and its tokens/notifications (F20) — this feature provides the update/delete operations that flow uses.
- Google Calendar (F10, complete) — except insofar as F11 must interoperate with it in a mixed panel.
- Auto-generated video-conference links (Teams/Meet/Zoom) — deferred to v1.5.
- Reading or storing any calendar content beyond free/busy intervals and Cadence-created events.
- The OAuth consent/connect/disconnect UX and token encryption (owned by F01.1; the Microsoft connect flow already exists there).
- Multi-stage interview loop scheduling (Flow A2, deferred to v1.5).
