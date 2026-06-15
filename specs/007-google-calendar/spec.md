# Feature Specification: Calendar Integration — Google Calendar

**Feature Branch**: `007-google-calendar`
**Created**: 2026-06-15
**Status**: Draft
**Backlog ID**: F10 (Tier 1 — Critical Path, P1)
**Input**: User description: "F10 Calendar Integration: Google Calendar - bidirectional free/busy read and event write/update/delete via OAuth per-user consent, wrapped behind CalendarProvider interface, tokens stored encrypted via F01.1"

## Overview

Cadence's core value is scheduling interviews against the real-time availability of internal participants. F01.1 (OAuth Token Store) already lets a member connect their Google account once and hands any caller a currently-valid, auto-refreshed access credential for that member on demand. This feature is the first thing that actually *uses* that credential: the **Google Calendar provider adapter**.

The adapter does two jobs against the Google Calendar service, both strictly minimised to what scheduling needs:

1. **Read availability** — given a set of connected members and a time window, return each member's busy intervals (free/busy only — never event titles, attendees, or body text), normalised into Cadence's internal availability model so that the scheduler (F12 rule engine, F13 booking flow) can compute genuinely conflict-free slots.
2. **Write calendar events** — create, update, and delete a Cadence-managed interview event on a member's calendar (title, time, location/dial-in text supplied by the recruiter — no auto-generated video link, deferred to v1.5), with the idempotent create and the compensating delete that an atomic booking (F13) needs to roll back cleanly on partial failure.

The adapter is wrapped behind the domain `CalendarProvider` abstraction so business logic never references the Google SDK/API directly, and it is built to tolerate Google's rate limits, transient outages, and daylight-saving-time boundaries without producing wrong wall-clock times or silently dropping work. It does **not** own the end-to-end booking orchestration (atomic slot reservation, the scheduling email, the pipeline status) — that is F13; this feature delivers the calendar operations F13 composes, plus the same capabilities for the Microsoft adapter (F11) to mirror.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Read a panel's availability for scheduling (Priority: P1)

As the scheduling system, given a set of internal participants who have connected their Google calendars and a target time window, I can retrieve each participant's busy intervals so that only genuinely free, rule-compliant slots are ever offered to a candidate.

**Why this priority**: Availability reading is the single capability that everything downstream (rule engine, slot computation, the candidate slot-picker) depends on. Without it there is no scheduling. It is also independently demonstrable: a connected member's busy times can be fetched and shown without any event ever being written.

**Independent Test**: Connect a test member's Google calendar (via the F01.1 flow against a stubbed Google service), seed that calendar with one busy interval, request availability for that member over a window covering it, and confirm Cadence returns exactly that busy interval — normalised to Cadence's internal time model — and nothing else (no title, attendee, or body content).

**Acceptance Scenarios**:

1. **Given** a connected member with one busy interval in the requested window, **When** the system queries that member's availability, **Then** it returns exactly that busy interval in the internal availability model, with correct start/end instants.
2. **Given** a 5-person panel of connected members, **When** the system queries availability for the whole panel over a window, **Then** it returns each member's busy intervals merged into the internal model within the panel-query performance target, in a single logical availability read.
3. **Given** any availability response from Google, **When** it is processed, **Then** no event title, attendee list, location, or body text is read into Cadence state or written to any log — only busy time ranges (and, at most, an opaque busy/tentative marker).
4. **Given** a panel member whose calendar is not connected (or needs reconnection), **When** availability is requested for the panel, **Then** the system reports that member as unavailable-for-scheduling with a clear, distinguishable reason rather than silently treating them as fully free.

---

### User Story 2 - Create a calendar event when an interview is booked (Priority: P1)

As the scheduling system, when an interview slot is confirmed, I can create a calendar event on each participant's Google calendar with the correct title, time zone, and recruiter-supplied location/dial-in details, so that every participant sees the interview on their own calendar.

**Why this priority**: Writing the confirmed event back is the other half of "bidirectional" and is what makes a booking real to participants. It is the capability F13's booking flow composes; demonstrable on its own by creating an event and observing it on the stubbed calendar.

**Independent Test**: For a connected member, create an interview event for a specific time window with a given title and location text; confirm the event is created on that member's calendar with the exact wall-clock time (correct time zone), the supplied title and location, and **no** auto-generated video link; the operation returns a stable provider reference Cadence can later update or delete.

**Acceptance Scenarios**:

1. **Given** a confirmed interview with a time, title, and recruiter-supplied location text, **When** the system creates the event for a connected participant, **Then** the event appears on that participant's calendar with the correct wall-clock time in their time zone, the given title, and the given location — and no automatically generated meeting link.
2. **Given** a successful event creation, **When** it completes, **Then** the system retains a stable provider reference (event identifier per participant) so the event can be updated or deleted later, and records the creation in the audit trail using internal identifiers only.
3. **Given** an interview booked for a time one hour before a daylight-saving-time transition, **When** events are created, **Then** every participant's event renders at the correct intended wall-clock time across the DST boundary (verified by a synthetic DST-crossing fixture).
4. **Given** the same booking is processed twice (a retried confirmation), **When** event creation runs again for the same participant and interview, **Then** the system does not create a duplicate event for that participant (idempotent create).

---

### User Story 3 - Update or cancel the calendar event on reschedule/cancellation (Priority: P1)

As the scheduling system, when a booked interview is rescheduled or cancelled, I can update or delete the corresponding Google Calendar event for every participant so that their calendars always reflect the current truth.

**Why this priority**: A calendar that shows a cancelled or moved interview at its old time is worse than no integration. Update/delete is also the compensating action an atomic booking needs to roll back a partially-created event set, so it is core, not polish.

**Independent Test**: Create an event, then (a) update it to a new time and confirm the participant's calendar shows the new time with the old slot gone, and (b) delete it and confirm it no longer appears; deleting an already-absent event is treated as success (idempotent delete).

**Acceptance Scenarios**:

1. **Given** an existing Cadence-created event, **When** the interview is rescheduled, **Then** the system updates that event in place to the new time for every participant and no stale event remains at the old time.
2. **Given** an existing Cadence-created event, **When** the interview is cancelled, **Then** the system deletes that event for every participant and it no longer appears on their calendars.
3. **Given** a delete (or update) targeting an event that the provider reports as already gone, **When** the operation runs, **Then** the system treats it as a successful no-op rather than failing (idempotent), so cleanup/rollback never gets stuck.
4. **Given** a booking in which the event was created for some participants but creation then failed for another, **When** the booking is rolled back, **Then** the system deletes the already-created events for the other participants so no orphaned interview event remains on any calendar.

---

### User Story 4 - Survive Google rate limits and transient outages (Priority: P2)

As the scheduling system, when Google temporarily rate-limits or errors, I retry transient failures with bounded backoff so that a brief provider hiccup does not fail a booking or an availability read, while a genuinely permanent failure is surfaced rather than retried forever.

**Why this priority**: Google Calendar enforces per-user and per-project quotas and has transient `429`/`5xx` responses; without backoff, normal load produces spurious scheduling failures. Important for reliability but secondary to the read/write capabilities themselves.

**Independent Test**: Stub the Google service to return `429` (or `503`) for the first one or two attempts and then succeed; confirm the operation ultimately succeeds via bounded exponential backoff with jitter (max retries enforced). Stub a persistent permanent error (e.g., revoked authorization surfaced by F01.1) and confirm the operation fails fast with a clear, distinguishable outcome and no infinite retry.

**Acceptance Scenarios**:

1. **Given** the provider returns `429` or `503` then recovers, **When** an availability read or event write is attempted, **Then** the system retries with exponential backoff and jitter (bounded to the configured maximum) and the operation ultimately succeeds.
2. **Given** the provider returns transient errors beyond the retry budget, **When** the budget is exhausted, **Then** the operation fails with a transient/retryable outcome distinct from a permanent failure, leaving no partial calendar state attributable to this adapter.
3. **Given** the member's authorization is permanently invalid (revoked), **When** any calendar operation is attempted, **Then** the system surfaces "needs reconnection" (via the F01.1 token store) without retrying indefinitely, and the participant is reported as unavailable-for-scheduling.
4. **Given** any retry sequence, **When** it runs, **Then** no token value, authorization code, or calendar event content appears in any log line at any level.

---

### Edge Cases

- **Unconnected / needs-reconnection participant in a panel**: reported as a distinct unavailable-for-scheduling reason, never treated as fully free (which would let the scheduler double-book them).
- **Empty window / no busy intervals**: a member with nothing in the window returns an empty busy set (fully available), not an error.
- **DST boundary bookings**: events created across a spring-forward/fall-back transition must render at the intended wall-clock time; stored/compared instants must be unambiguous (absolute time + the relevant zone), never a naive local time.
- **Rotated/expired access credential mid-operation**: the adapter always obtains the token through the F01.1 store, so a just-expired access credential is transparently refreshed; the adapter never caches a raw token across operations.
- **Idempotent create on retry**: a retried booking must not create a second event for the same participant+interview; the adapter recognises an already-created event.
- **Idempotent update/delete on a missing event**: updating or deleting an event the provider says is already gone is a success, so rollback and cancellation never wedge.
- **Partial-create rollback**: when an event is created for some participants but a later participant fails, the already-created events are deleted (compensating delete) so no orphan remains.
- **Rate-limit vs permanent failure**: `429`/`5xx`/network = transient (retry with backoff+jitter); revoked grant / `invalid` authorization = permanent (surface needs-reconnection, no retry).
- **Unrelated meeting content**: at no point are titles, attendees, descriptions, or locations of *non-Cadence* events read, stored, or logged — only busy/free intervals.
- **Time window bounds**: an availability query with an excessively large window is bounded/rejected so a single request cannot become an unbounded scan.

## Requirements *(mandatory)*

### Functional Requirements

**Availability (free/busy) reads**

- **FR-001**: The system MUST provide a provider-agnostic capability to read, for one or more connected members and a bounded time window, each member's busy intervals, normalised into Cadence's internal availability model.
- **FR-002**: The system MUST request and process **free/busy data only**; it MUST NOT request, read, store, or log event titles, attendees, descriptions, or locations of any calendar entry (Cadence-created or otherwise) when reading availability.
- **FR-003**: The system MUST normalise all returned busy intervals to unambiguous absolute time (instant plus the applicable time zone) so downstream rule evaluation and rendering are DST-correct.
- **FR-004**: When a requested member is not connected or needs reconnection, the system MUST represent that member as unavailable-for-scheduling with a reason distinct from "free", rather than omitting them silently or treating them as available.
- **FR-005**: The system MUST support reading availability for a multi-member panel in a single logical operation (not an unbounded per-member fan-out that breaks the panel performance target).

**Event writes (create / update / delete)**

- **FR-006**: The system MUST create a calendar event on a connected member's calendar with a caller-supplied title, start/end time (with explicit time zone), and recruiter-supplied location/dial-in text, and MUST NOT auto-generate any video-conference link (deferred to v1.5).
- **FR-007**: On successful creation the system MUST return/persist a stable per-participant provider reference sufficient to later update or delete that exact event.
- **FR-008**: The system MUST update an existing Cadence-created event in place (e.g., new time) without leaving a stale event at the prior time.
- **FR-009**: The system MUST delete a Cadence-created event such that it no longer appears on the participant's calendar.
- **FR-010**: Event creation MUST be idempotent for the same participant + interview: a retried create MUST NOT produce a duplicate event.
- **FR-011**: Update and delete MUST be idempotent: targeting an event the provider reports as already absent MUST be treated as success, not failure.
- **FR-012**: The system MUST provide a compensating delete so that a partially-created set of events (created for some participants, failed for another) can be fully rolled back, leaving no orphaned events. *(The atomic booking orchestration that invokes this rollback is F13; this feature provides the operation.)*

**Resilience & failure handling**

- **FR-013**: On a transient provider failure (rate-limit `429`, server error `5xx`, or network error), the system MUST retry with bounded exponential backoff and jitter, up to a configured maximum number of attempts (default: 3, per the F10 backlog acceptance criterion; the policy and its default are shared with F11).
- **FR-014**: When the retry budget is exhausted, the system MUST surface a transient/retryable outcome that is distinguishable from a permanent failure and MUST NOT leave partial calendar state attributable to the failed operation.
- **FR-015**: When an operation fails because the member's authorization is permanently invalid — a revoked/expired grant, **or a grant that lacks the event-management scope** (e.g. a connection made before event-write was enabled) — the system MUST surface a "needs reconnection" outcome (via the F01.1 token store) without retrying indefinitely, and report the member as unavailable-for-scheduling. The insufficient-scope case MUST be handled distinctly from a transient failure (a token refresh does not fix a missing scope).
- **FR-016a**: When a **compensating delete** (rollback) cannot complete after its retry budget is exhausted, the system MUST surface a distinct "cleanup-incomplete" outcome for that participant and record it (audit, internal IDs only) so the orphaned event can be reconciled later, rather than silently reporting a clean rollback. A clean rollback (the normal case) leaves zero orphaned events.

**Credentials, isolation & secrecy**

- **FR-016**: The system MUST obtain the access credential for every calendar operation through the existing F01.1 token store (transparent refresh), and MUST NOT independently persist, cache across operations, or duplicate the raw credential.
- **FR-017**: The system MUST NOT write any token value, authorization code, or client secret to application logs at any level, during any operation (read, create, update, delete, failure, retry).
- **FR-017a**: The system MUST NOT write **any** calendar event content to logs at any level — this includes both (a) the content of unrelated meetings encountered while reading availability and (b) the **Cadence-created interview event's own** title and location/dial-in text (which is recruiter-supplied free text that routinely contains candidate name, email, or dial-in numbers, i.e. candidate PII per constitution §VIII).
- **FR-017b**: The system MUST sanitise provider (Google) response and error payloads before logging: raw response/error bodies — which may echo calendar identifiers, attendee/account emails, or event snippets — MUST NOT be logged verbatim; only a non-PII summary (e.g., status code, classified outcome) may be logged.
- **FR-018**: The system MUST only access calendars of members within the actor's own workspace and MUST NOT read or write across workspace boundaries.
- **FR-018a**: The provider account identifier (the member's provider email/subject) and attendee email addresses received from Google in free/busy or event responses are personal data; the system MUST NOT log them and MUST NOT retain them in Cadence state beyond what F01.1 already holds encrypted — upholding the F01.1 baseline that the provider account identifier is encrypted-at-rest PII.

**Abstraction & audit**

- **FR-019**: The Google integration MUST be exposed through the domain `CalendarProvider` abstraction; business/scheduling logic MUST NOT reference the Google SDK/API types directly, and the response model MUST be the same internal availability/event model the Microsoft adapter (F11) will produce.
- **FR-020**: The system MUST record calendar event lifecycle actions (created, updated, deleted) and a calendar-operation-triggered "needs reconnection" occurrence in the audit trail using internal identifiers only, with no credential or event-content payload. (The reconnection-state flip itself is owned and audited by F01.1; F10 records the scheduling-side occurrence so a member reported unavailable-due-to-revocation is traceable.)

### Key Entities *(include if feature involves data)*

- **Availability Window Query (transient)**: A request for busy/free over a bounded time window for a set of members within a workspace. Attributes: workspace, member set, window start/end, applicable time zone. Produces a set of per-member busy intervals.
- **Busy Interval (transient, internal model)**: One occupied time range for one member: absolute start, absolute end, applicable zone, and an opaque busy/tentative marker — never any event content. This is the same `TimeSlot`/free-busy model F11 normalises into.
- **Managed Calendar Event**: A Cadence-created interview event projected onto each participant's calendar. Attributes: owning workspace, the internal interview/booking reference, per-participant provider event reference (the stable handle for update/delete), start/end with explicit zone, title, recruiter-supplied location text. Holds **no** content of unrelated calendar entries. (Persistence of these references is shared with / owned by the booking flow F13; this feature defines and populates the provider-reference handles.)
- **Provider Operation Outcome (transient)**: The result of a calendar operation — success (with provider reference), transient/retryable failure, or permanent "needs reconnection" failure — distinguishable by the caller so the booking flow can retry, roll back, or surface a reconnect prompt.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A free/busy query for a 5-person panel returns normalised availability within 5 seconds under normal provider latency.
- **SC-002**: A confirmed interview's calendar event appears on each connected participant's calendar within 10 seconds of the create request.
- **SC-003**: Across the full read, create, update, and delete flows, an automated scan of all application logs finds **zero** occurrences of token values, authorization codes, client secrets, provider/attendee account emails, or any calendar event content — including the Cadence-created event's own recruiter-supplied title and location text (titles, attendees, descriptions, locations of any event).
- **SC-004**: 100% of availability responses expose only busy/free time ranges in Cadence state — no event title, attendee, or body of any meeting (Cadence-created or unrelated) is ever read into state or logged (verified by an adapter test that seeds rich event content and asserts only intervals survive).
- **SC-005**: An interview booked one hour before a DST boundary renders at the correct wall-clock time on every participant's calendar (verified by a synthetic DST-crossing integration fixture) in 100% of runs.
- **SC-006**: When the provider returns `429`/`503` and then recovers within the retry budget, the operation succeeds with no manual intervention; when the budget is exhausted or the grant is revoked, the failure is surfaced as transient vs permanent respectively, with no infinite retry and no orphaned calendar events (verified by stubbed-provider tests).
- **SC-007**: A partial-failure booking (event created for N−1 of N participants, then a failure) leaves **zero** orphaned events after a successful rollback (verified by an integration test asserting the compensating delete); if a compensating delete itself exhausts its retry budget, the affected participant is surfaced and audited as "cleanup-incomplete" (never silently reported as a clean rollback), so any residual event is reconcilable (FR-016a).
- **SC-008**: An idempotent retry of a create produces exactly one event per participant+interview; an update/delete against an already-absent event reports success (no duplicate, no spurious error).

## Assumptions

- **Builds on F01.1, not a new token store**: All credential acquisition and refresh, encrypted storage, and "needs reconnection" detection are provided by F01.1's token store and its `validAccessToken`/`CalendarProviderClient` capability. This feature consumes that and does not re-implement it.
- **Demonstrable slice, F13 owns the orchestration**: This feature delivers the Google adapter's read + event-CRUD capabilities and the compensating-delete primitive, exercised end-to-end against a stubbed Google service. The atomic slot reservation, the candidate self-scheduling email, and the pipeline status transitions are owned by F13 (Flow A1); F13 composes the operations defined here. "Within 10 s of slot confirmation" is measured from the adapter's create request, since slot confirmation is an F13 trigger.
- **No auto-generated meeting link (MVP)**: Per backlog OD-1 and the deferred table, no Google Meet/Teams/Zoom link is generated; the event carries only recruiter-supplied location/dial-in text. Meeting-link generation is v1.5.
- **Least-privilege scope (constitution §VIII)**: The **read** path is free/busy-only. Writing events is impossible under a free/busy scope, so a least-privilege **event-management** scope (manage only events Cadence creates — `calendar.events.owned`, paired with free/busy) is additionally consented, with the §VIII-required justification documented and approved in `plan.md` (D1). No *read* scope beyond free/busy is requested, and no unrelated event content is ever accessed. A connection still holding only the old free/busy grant is detected on first write (insufficient-scope) and surfaced as "Needs reconnection" (see FR-015), not failed opaquely.
- **Provider-agnostic internal model**: The availability and event models are provider-neutral so F11 (Microsoft 365) normalises into the same `TimeSlot`/free-busy and managed-event shapes; mixed Google+Microsoft panels (a single scheduling flow) become possible once F11 lands.
- **No new infrastructure or dependency**: HTTP access to Google reuses the existing in-stack HTTP client approach (the same `RestClient`/gateway pattern F01.1 introduced) behind the `CalendarProvider` abstraction; no broker, cache, or SDK that violates the dependency policy is added. Provider client credentials come from the existing secrets mechanism.
- **No-cloud-credentials testing**: Read/write/retry/DST/rollback behaviours are verified against a stubbed/local Google service (the same JDK-`HttpServer` stub approach F01.1 used), so CI needs no live Google credentials.
- **DST correctness via absolute time**: All intervals and event times are handled as absolute instants plus an explicit zone; naive local-time arithmetic is never used for storage or comparison.
- **Rate-limit policy shared with F11**: The backoff/jitter/max-retry policy is defined here and reused by F11 so both adapters behave identically under provider throttling.

## Dependencies

- **F01.1 — OAuth Token Store** (complete): provides per-member encrypted Google credentials, transparent refresh, and the `CalendarProvider`/`CalendarProviderClient` abstraction this feature widens with free/busy reads and event CRUD.
- **F01 — Authentication & Session Management** (complete): authenticated member + workspace context for every operation.
- **F00.1 — MongoDB Index Bootstrapping**: any new persisted provider-event references are indexed via the next Mongock changeset in sequence (or are owned by F13's booking records).
- **Consumed by F12 (Rule Engine), F13 (Flow A1 scheduling), and F20 (Reschedule/Cancel)**: these features call this adapter's availability read and event create/update/delete operations.
- **Mirrored by F11 (Microsoft 365)**: F11 implements the same internal model and resilience policy for Microsoft Graph.

## Out of Scope

- Atomic slot reservation, the candidate scheduling link/email, and pipeline status transitions (F13).
- Reschedule/cancellation *flow* and its tokens/notifications (F20) — this feature provides the update/delete operations that flow uses.
- Microsoft 365 / Outlook calendars (F11).
- Auto-generated video-conference links (Google Meet/Teams/Zoom) — deferred to v1.5.
- Reading or storing any calendar content beyond free/busy intervals and Cadence-created events.
- The OAuth consent/connect/disconnect UX and token encryption (owned by F01.1).
- Multi-stage interview loop scheduling (Flow A2, deferred to v1.5).
