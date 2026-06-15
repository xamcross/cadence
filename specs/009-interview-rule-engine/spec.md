# Feature Specification: Interview Template & Rule Engine

**Feature Branch**: `009-interview-rule-engine`
**Created**: 2026-06-15
**Status**: Draft
**Backlog ID**: F12 (Tier 1 — Critical Path, P1)
**Input**: User description: "F12 Interview Template and Rule Engine - recruiter-created interview stage templates (duration, required/optional participants, panel composition 'any N of pool', buffers, daily caps, blackout periods, time-zone handling) plus the rule engine that applies them at booking time to compute compliant slots."

## Overview

Cadence schedules interviews against the real availability of internal participants. Two foundations already exist: F03 gave each workspace a time zone, working hours, and SLA defaults; F10/F11 give each connected member's busy/free intervals through the provider-agnostic availability layer. What is still missing is the definition of *what a valid interview actually looks like* and the logic that turns "raw free time" into "slots we are allowed to offer this candidate". That is F12.

This feature delivers two things:

1. **Interview stage templates** — a reusable, recruiter-owned definition of an interview type: how long it runs, who must attend (required participants), who may attend (optional participants and "any N of a pool" panels), the spacing rules (buffer before/after, daily interview cap per interviewer), the periods when it must never be scheduled (blackout periods), and the working-hours/time-zone basis it operates in (inheriting the workspace defaults from F03 unless overridden). A template is created once and reused for every candidate at that stage, so repeated interview types never need reconfiguring.

2. **The rule engine** — given a template, a target date range, and the participants' real availability (read through F10/F11), it computes the set of genuinely compliant time slots: every offered slot honours the duration, every required participant is free across the slot and its buffers, every pool reaches its "any N" quorum, no interviewer exceeds their daily cap, and nothing falls inside a blackout, outside working hours, or on the wrong side of a daylight-saving-time boundary. The engine produces *only* compliant slots; an availability gap it cannot resolve makes a slot ineligible rather than silently bookable.

The engine is the source of the slot list that the candidate self-scheduling page (F14) renders and that the end-to-end booking flow (F13) reserves against. F12 does **not** reserve a slot, send the scheduling email, finalise the concrete panel for a booking, or render any candidate UI — those are F13/F14. It delivers the template definition + management and the compliant-slot computation those features compose. Scope is **single-stage** interviews only; the multi-stage loop solver (Flow A2) is deferred to v1.5.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Create and manage an interview stage template (Priority: P1)

As a Recruiter (or Admin), I can create, edit, and retire an interview stage template — its duration, required and optional participants, "any N of pool" panel rules, buffers, daily interviewer cap, blackout periods, and working-hours basis — so that every candidate at that stage is scheduled against the same agreed rules without reconfiguring them each time.

**Why this priority**: Without a stored, reusable template there is nothing for the rule engine to apply, and recruiters would have to re-specify every rule per candidate. It is the foundational data this whole feature operates on, and it is independently demonstrable: a recruiter can create a template, read it back, edit it, and retire it with no scheduling having happened yet.

**Independent Test**: As a Recruiter, create a template ("Phone Screen", 45 minutes, one required interviewer, 15-minute buffers, max 2/day, a one-week blackout), read it back and confirm every field persisted; edit the duration and confirm the change persists; retire it and confirm it is no longer offered for new scheduling but existing references remain valid. As a non-permitted role (Interviewer, Read-only), attempt to create or edit a template and confirm it is refused (HTTP 403).

**Acceptance Scenarios**:

1. **Given** a Recruiter and a valid template definition (duration, ≥1 required participant or ≥1 pool rule, buffers, daily cap, optional pools, blackouts), **When** they create the template, **Then** it is persisted within their workspace and can be read back with every field intact.
2. **Given** a template definition with an invalid value (duration ≤ 0; daily cap < 1; a pool whose "any N" is 0 or exceeds the pool size; a blackout whose end is at or before its start; a negative buffer; zero required-and-pool participants; a member reference that is not a member of this workspace; the same member appearing both as required and inside a pool; more members/pools/blackouts than the configured per-template caps), **When** it is submitted, **Then** the whole submission is refused with a per-field, **value-free** validation message (field + rule, never echoing the submitted value) and nothing is persisted.
3. **Given** an existing template, **When** a Recruiter edits it, **Then** the change is validated and persisted, and slot computations run after the edit use the new rules (edits are not retroactive to already-booked interviews — those are F13/F20's concern).
4. **Given** an existing template, **When** a Recruiter retires (deactivates) it, **Then** it is no longer offered for starting new scheduling, but it is not hard-deleted and remains resolvable for audit and for any in-flight booking that already referenced it.
5. **Given** any template create/edit/retire, **When** it succeeds, **Then** the action is recorded in the audit trail using internal identifiers only (no participant PII, no template name, no free text).
6. **Given** a non-permitted role (Hiring Manager, Interviewer, Read-only), **When** they attempt to create, edit, or retire a template, **Then** the request is refused with HTTP 403 and no state changes.

---

### User Story 2 - Compute compliant slots for a candidate (the rule engine) (Priority: P1)

As the scheduling system, given an interview stage template and a target date range, I can compute the set of time slots that satisfy *every* rule in the template against the participants' real availability, so that only genuinely compliant, conflict-free times are ever offered to a candidate.

**Why this priority**: This is the core value of the feature and the capability everything downstream depends on — the candidate slot-picker (F14) renders this list and the booking flow (F13) reserves against it. A wrong slot here means a double-booking, a missed required attendee, or a candidate offered a time no one can attend. It is independently demonstrable: with seeded availability and a template, the engine produces exactly the compliant slots and no others.

**Independent Test**: Seed a template (45 min, one required interviewer, one "any 1 of pool {A,B}", 15-min buffers, max 2/day, working hours 09:00–17:00) and seed each participant's availability (the required interviewer busy 10:00–11:00; A free all day; B busy all day) over a single working day; request slot computation for that day and assert: every returned slot is 45 minutes, none overlaps the required interviewer's 10:00–11:00 busy block *or its buffers*, every returned slot lists exactly {A} as the pool's qualifying member (B is busy, so excluded), none falls outside 09:00–17:00.

**Acceptance Scenarios**:

1. **Given** a template with a fixed duration, **When** slots are computed, **Then** every offered slot is exactly the template duration and begins on the configured slot-start cadence, anchored to the working-day start in the applicable zone.
2. **Given** one or more **required** participants, **When** slots are computed, **Then** every offered slot is one in which **all** required participants are free for the entire slot **and** its before/after buffer windows; a slot where any required participant is busy, or whose availability is unknown (see fail-safe), is not offered.
3. **Given** an **"any N of pool"** rule, **When** slots are computed, **Then** a slot is offered only if at least N **distinct** pool members are **positively known free** across the slot and its buffers (a pool member whose availability is unknown does NOT count toward the quorum), and each offered slot is annotated, per pool, with which pool members qualify (so F13 can finalise the concrete panel).
4. **Given** a **buffer before/after**, **When** slots are computed, **Then** no offered slot places a required/qualifying-pool participant within the buffer distance of one of their existing commitments, and a slot that cannot fit duration + buffers inside working hours is not offered.
5. **Given** a **daily interview cap** of N for an interviewer who is a **required** participant, **When** slots are computed for a day on which that interviewer already has N Cadence-managed interviews, **Then** no further slot is offered that day (the cap is enforced against required participants at compute time; pool-member cap enforcement is finalised by F13 at booking, since a pool member is not bound to a slot until then).
6. **Given** a **blackout period**, **When** slots are computed, **Then** no slot overlapping the blackout is offered.
7. **Given** a target range with no rule-compliant time, **When** slots are computed, **Then** the engine returns an empty slot set (not an error).
8. **Given** the same template, range, and availability snapshot, **When** slot computation runs twice, **Then** it returns the identical compliant slots in the identical order (deterministic).
9. **Given** a **retired** template, **When** a new slot computation is requested against it, **Then** the request is refused with a distinguishable error (not an empty slot set, which is indistinguishable from "no availability").

---

### User Story 3 - Inherit workspace defaults, override per template (Priority: P2)

As a Recruiter or Admin, a template uses the workspace's configured working hours and time zone by default, but I can override them on an individual template, so that most templates need no time configuration while a special case (e.g. an early-shift role) can differ.

**Why this priority**: Inheritance keeps the common case effortless and consistent with the workspace configuration F03 already owns, while overrides cover real exceptions. It is P2 because the engine and templates (Stories 1–2) are usable with workspace defaults alone; per-template override is a refinement, independently testable.

**Independent Test**: With workspace working hours 09:00–17:00 in `Europe/London`, create a template with no working-hours override and confirm computed slots fall within 09:00–17:00 London time; create a second template overriding hours to 07:00–11:00 and confirm its computed slots fall only within 07:00–11:00; confirm a template can override the time zone and that slots render at the correct wall-clock time in the overriding zone.

**Acceptance Scenarios**:

1. **Given** a template with no working-hours/time-zone override, **When** slots are computed, **Then** the workspace's configured working hours and time zone (F03) are used.
2. **Given** a template that overrides working hours and/or time zone, **When** slots are computed, **Then** the template's values are used instead of the workspace defaults, with the same validation bounds F03 enforces (valid IANA zone; end strictly after start; no overnight window in the MVP).
3. **Given** the workspace working hours are later changed (F03), **When** slots are computed for a template that inherits them, **Then** the new workspace hours take effect for that template (inheritance is by reference, not a copy taken at template-creation time).

---

### Edge Cases

- **Required participant not connected / needs reconnection / availability unknown**: the affected slots are **not** offered; an unknown availability is treated as *not schedulable*, never as "free". A slot may only be offered when every required participant's availability is positively known to be free (fail-safe against double-booking).
- **Pool member with unknown availability**: not counted toward the pool quorum (same fail-safe as a required participant); the quorum is the count of members *positively known free*. A pool reaches quorum only on positively-free distinct members.
- **Same member appears in more than one requirement** (required + pool, or two pools): the member is counted **once** per slot for both the free-check and the daily cap; a single member cannot satisfy two pool "seats". A member that is both required and a pool member is rejected at template validation (a required member is already committed; counting them toward an "any N" would double-count).
- **Pool cannot reach quorum**: if fewer than N distinct pool members are positively free for a candidate slot, that slot is not offered; an "any N of pool" where N is 0 or exceeds the pool size is rejected at template validation, not silently downgraded.
- **Daily cap already met**: an interviewer who already has N Cadence-managed interviews on a day yields no further slots that day; the cap counts Cadence-managed interview **start instants** falling within the zone-relative civil day, excluding events the system has deleted/rolled back (status DELETED/CLEANUP_INCOMPLETE), and is counted per civil day regardless of whether that day is 23h (spring-forward) or 25h (fall-back).
- **Cap consistency across separate computations**: within one computation the engine never offers a set of slots that would breach a required participant's cap; but two *independent* computations (two recruiters, same interviewer, same day) each see only already-reserved managed events, not each other's offered-but-unbooked slots — so the cap is intra-computation-guaranteed and otherwise advisory. F13 MUST re-validate the cap atomically at reservation time.
- **Buffer overruns the working-hours window or day boundary**: a slot whose required buffer would fall outside working hours (or which cannot fit duration + buffers inside the working window) is not offered.
- **DST boundary**: slots adjacent to a spring-forward/fall-back transition are generated and rendered at the correct intended wall-clock time; the non-existent spring-forward local hour yields no slot, and the repeated fall-back hour is offered once (not double-counted). A slot whose buffer-after lands in a spring-forward gap is evaluated on absolute instants, not naive local time.
- **Template duration longer than the working-hours window**: yields zero slots (not an error).
- **Target range invalid or excessive**: a range whose end is at or before its start, or that lies wholly in the past, yields an empty slot set (no slot is ever offered in the past); a range wider than the configured maximum is clamped to the maximum (reusing the availability layer's window guard) and the clamp is signalled to the caller so coverage is never silently misrepresented.
- **Template references a member who has since left the workspace or been deactivated**: that member contributes no availability; if they were required, the affected slots are not offered (fail-safe); if they were a pool member, the pool quorum is evaluated on the remaining positively-free members. The engine degrades gracefully rather than erroring.
- **Overlapping blackout and working hours**: the blackout always wins (no slot offered in the overlap).
- **Empty pool / pool with one member and "any 1"**: a pool of one with "any 1" behaves like a required constraint on that member; a pool whose size is below its N is rejected at validation.
- **Optional participant busy/unknown**: never makes a slot ineligible; optional participants do not gate slots (they may be annotated free/busy for F13/F14 display only).

## Requirements *(mandatory)*

### Functional Requirements

**Template definition & management**

- **FR-001**: The system MUST allow a permitted member to create an interview stage template within their workspace, capturing at minimum: a name, an interview duration, the set of **required participants**, zero or more **optional participants**, zero or more **"any N of pool"** panel rules, a **buffer before** and **buffer after** (each ≥ 0), a **daily interview cap** per interviewer, zero or more **blackout periods** (date/time ranges with no free-text label), and an optional **working-hours/time-zone override**.
- **FR-002**: The system MUST validate every template field before persisting and, on any invalid value, MUST refuse the whole submission with a per-field, **value-free** message (field + rule code, never the submitted value) and persist nothing (no partial write). At minimum: duration > 0; daily cap ≥ 1; each buffer ≥ 0; at least one required participant or at least one pool rule; each pool's "any N" between 1 and that pool's size inclusive; each blackout end strictly after its start; any working-hours override valid per the F03 bounds (valid IANA zone, end strictly after start, no overnight window); **every member reference (required / optional / pool) is a member of the template's workspace**; no member is both required and a pool member, and no member appears in two different pools (no member fills two seats); and the number of distinct members, pools, and blackouts per template is within the configured per-template caps (FR-024).
- **FR-003**: The system MUST allow a permitted member to edit an existing template, applying the same validation as creation; edits take effect for computations run after the edit and MUST NOT retroactively mutate already-booked interviews.
- **FR-004**: The system MUST allow a permitted member to retire (deactivate) a template such that it is no longer offered for starting new scheduling, WITHOUT hard-deleting it, so that audit history and any in-flight booking that referenced it remain resolvable.
- **FR-005**: The system MUST restrict template create/edit/retire to **Recruiter and Admin** roles; any other role (Hiring Manager, Interviewer, Read-only) attempting these MUST be refused with HTTP 403 and no state change, consistent with the F02 deny-by-default model.
- **FR-006**: Templates MUST be scoped to a single workspace; a member MUST NOT create, read, edit, or apply a template belonging to another workspace. Template resolution MUST be by `{workspaceId, templateId}` so a foreign template id is an indistinguishable not-found (per the F02 scoped-not-found pattern), never an existence oracle.

**Slot computation (the rule engine)**

- **FR-007**: The system MUST compute, for a given template and a bounded target date range, the set of time slots that satisfy **every** rule in the template against the participants' availability (read through the F10/F11 availability layer), returning only compliant slots and an empty set when none comply. A computation against a **retired** template MUST be refused with a distinguishable error (not an empty set).
- **FR-008**: Every computed slot MUST be exactly the template's configured duration and MUST start on the configured slot-start cadence, anchored to the working-day start in the applicable zone.
- **FR-009**: A slot MUST be offered only if **every required participant** is free for the entire slot **and** its before/after buffer windows. A member appearing in more than one role is evaluated once per slot.
- **FR-010**: A slot subject to an "any N of pool" rule MUST be offered only if at least N **distinct** pool members are **positively known free** across the slot and its buffers; a pool member whose availability is unknown MUST NOT count toward the quorum, and a single member MUST NOT fill two seats. Each offered slot MUST carry, **per pool**, the identity of the qualifying members (and the set of required members) so the booking flow (F13) can finalise the concrete panel independently per pool.
- **FR-011**: The engine MUST enforce buffers such that no offered slot places a required or qualifying-pool participant within the buffer distance of another of their existing commitments, and MUST NOT offer a slot whose duration + buffers do not fit within the applicable working-hours window. (The engine offers candidate options; the candidate ultimately picks exactly one, so offered slots need not be spaced apart from each other — inter-interview spacing of *booked* interviews is enforced by F13 at reservation.)
- **FR-012**: The engine MUST enforce a per-interviewer daily cap against **required** participants: it MUST NOT offer a slot for a required interviewer who already has the cap number of Cadence-managed interviews that day, and within a single computation MUST NOT offer a slot set that would let any required interviewer exceed the cap on any one day. The cap counts Cadence-managed interview start instants in the applicable (template/workspace) zone's civil day, excluding deleted/rolled-back events. Cap enforcement for **pool** members is finalised by F13 at booking (a pool member is unbound until then); F13 MUST re-validate the cap atomically at reservation (see Edge Cases — cap consistency).
- **FR-013**: The engine MUST NOT offer any slot that overlaps a blackout period or falls outside the applicable working hours.
- **FR-014**: The engine MUST treat a required participant — and, for quorum purposes, a pool member — whose availability is unknown (not connected, needs reconnection, or transiently unreadable) as **not schedulable / not counted** — the affected slots MUST NOT be offered (required) or the member MUST NOT count toward quorum (pool), and the unschedulable reason MUST be distinguishable from "busy" so the caller (F13/F14) can surface it. The engine MUST NEVER assume an unknown participant is free.
- **FR-015**: All slot computation MUST use absolute instants plus the applicable time zone (never naive local time), so that slots adjacent to a DST boundary are produced at the correct intended wall-clock time, the non-existent spring-forward local hour yields no slot, and the repeated fall-back hour is offered exactly once.
- **FR-016**: The engine MUST be deterministic for identical inputs: the same template, range, and availability snapshot MUST produce the identical compliant slot set in a stable order (slots ascending by start instant; qualifying members ordered by member identifier). Determinism is defined over a fixed availability snapshot — the engine MUST take exactly one availability read per participant per computation (no within-computation retry variance) — and any "now"/future-only logic MUST be relative to an injected reference instant so runs are reproducible.
- **FR-017**: The computation window MUST be bounded (clamped to a configured maximum, with the clamp signalled to the caller) so a single request cannot trigger an unbounded availability scan.

**Defaults & inheritance**

- **FR-018**: A template without a working-hours/time-zone override MUST use the workspace's configured working hours and time zone (F03), resolved by reference at computation time so a later workspace change takes effect for inheriting templates.
- **FR-019**: A template with a working-hours/time-zone override MUST use its own values in place of the workspace defaults, validated to the same bounds F03 enforces.

**Abstraction, correctness, secrecy & limits**

- **FR-020**: The compliant-slot output MUST be a provider-agnostic internal model independent of which calendar provider (Google/Microsoft) supplied each participant's availability, so a mixed-provider panel produces one uniform slot list.
- **FR-021**: The slot-computation result shape MUST be stable and documented so the candidate scheduling page (F14, the Angular consumer) and the booking flow (F13) can depend on it (verified by an API contract test). The asserted shape MUST include the per-pool qualifying-member annotation (FR-010) and the distinguishable unschedulable-reason values (FR-014), not only the happy-path slot list.
- **FR-022**: The system MUST NOT write participant or candidate personal data (names, emails, the content of any calendar entry) — **nor the recruiter-supplied template name or any free-text field** — to logs at any level during template management or slot computation; only internal identifiers (workspace, template, member object ids) may be logged, consistent with constitution §VIII and the F10/F11 logging discipline (status/enum values logged as strings, never via structured-argument enum interpolation).
- **FR-023**: Template management MUST record its lifecycle actions (template created/edited/retired) and the refused-compute-against-retired-template attempt in the audit trail using internal identifiers only — no participant PII, no template name, no free-text payload.
- **FR-024**: The system MUST enforce a configured upper bound on the number of distinct members, pools, and blackout periods per template, so that a single template combined with a max-window computation cannot amplify the per-member availability fan-out into a denial-of-service or a mass member-availability probe. The availability fan-out MUST reuse the existing bounded pool/executor from the F10/F11 availability layer.

### Key Entities *(include if feature involves data)*

- **Interview Stage Template**: A reusable, workspace-scoped definition of an interview type. Attributes: owning workspace, name, status (active/retired), duration, required participants, optional participants, "any N of pool" panel rules, buffer-before, buffer-after, daily interviewer cap, blackout periods (no free-text), optional working-hours/time-zone override, and audit metadata (actor member id + timestamp for created/edited — never an email or display-name snapshot). Persisted; the source of rules the engine applies. Holds only internal member-id references (no candidate or participant PII).
- **Participant Requirement**: A component of a template describing who must or may attend — a required participant (must be free), an optional participant (never gates a slot), or a pool with an "any N of" quorum. Holds member references (internal ids), never PII.
- **Blackout Period**: A date/time range (with applicable zone) during which the template offers no slots. No free-text label.
- **Slot Computation Request (transient)**: An ask to compute compliant slots — workspace, template reference, target date range, and the applicable time zone. Bounded in size.
- **Compliant Slot (transient, internal model)**: One offerable interview time produced by the engine: absolute start and end (instant + applicable zone), the set of required members, and, per pool, the qualifying members that satisfy that pool's quorum. Provider-agnostic; the unit F14 renders and F13 reserves against. Holds no calendar event content.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: The rule engine never offers a slot that violates any template rule — verified by unit tests covering each rule independently (duration, slot-start cadence anchor, required-participant freedom, buffer enforcement, "any N of pool" quorum on distinct positively-free members, daily cap, blackout, working-hours, time-zone normalisation) with 0 violating slots across all cases.
- **SC-002**: A template configured `max 2 interviews/day` for a required interviewer never produces a third same-day slot, in both the pre-existing case (two managed events already that day → 0 additional slots) and the within-computation case (0 pre-existing → the engine never offers a slot set exceeding 2/day for that interviewer).
- **SC-003**: An interview offered one hour before a DST boundary is produced at the correct intended wall-clock time (synthetic DST-crossing fixture); the non-existent spring-forward hour yields 0 slots and the repeated fall-back hour is offered exactly once; the daily cap is counted per civil day regardless of the day being 23h or 25h — in 100% of runs.
- **SC-004**: A required participant whose availability is unknown (unconnected / needs reconnection) results in 0 offered slots requiring them; a pool member whose availability is unknown is excluded from the quorum (not counted as free) — both with a reason distinguishable from "busy", verified by tests asserting no silent-free path.
- **SC-005**: An "any N of pool" rule offers a slot only when ≥ N distinct positively-free pool members exist, and each offered slot is annotated **per pool** with exactly the qualifying members — verified by a test with multiple pools where each quorum binds (the annotation distinguishes which member satisfies which pool).
- **SC-006**: The slot-computation endpoint's response shape — including the per-pool qualifying-member annotation and the unschedulable-reason values — matches the contract the candidate scheduling page (F14) consumes, verified by a MockMvc contract test (response body, status codes, error envelope, retired-template error).
- **SC-007**: The engine's own rule evaluation over a fixed seeded availability snapshot for a 5-person panel across a 14-day range completes within a deterministic compute budget defined in `plan.md` (measured with no live network, against the stub), with exactly one availability read issued per participant. (End-to-end latency under real provider latency is owned by F13/F14.)
- **SC-008**: 100% of invalid template submissions are refused with no persisted change (0 partial writes) and a value-free message — verified by validation tests across the boundary cases (duration ≤ 0; daily cap < 1; pool "any N" of 0 or > pool size; blackout end ≤ start; negative buffer; zero required-and-pool participants; foreign-workspace member reference; member both required and in a pool; over-cap member/pool/blackout counts; invalid working-hours override).
- **SC-009**: Template create/edit/retire is restricted to Recruiter and Admin: every other role receives HTTP 403 with no state change (per-role contract test); a template (or member reference) from another workspace is never readable or applicable (cross-workspace isolation test, including a pool referencing a foreign-workspace member); and the slot-computation path is role-gated (or a system-internal primitive covered by the F02 build-time endpoint-inventory test).
- **SC-010**: An automated CI log scan across template management and slot computation finds 0 occurrences of participant/candidate names, emails, calendar event content, **or a seeded template name** — only internal identifiers appear (the scan is extended with a template-name sentinel).

## Assumptions

- **Builds on F03, F10/F11 — not re-implemented**: Working hours and time zone come from the F03 workspace configuration; per-member busy/free availability comes from the F10/F11 availability layer (`AvailabilityService` → per-member status + busy intervals) through its provider-agnostic model. F12 consumes both unchanged and does not re-implement calendar reading or workspace config; the engine issues a **single** panel-wide availability read for the whole window (never per-day or per-slot).
- **Demonstrable slice; F13/F14 own orchestration and UX**: F12 delivers template management and the compliant-slot computation, exercised end-to-end against seeded availability (stubbed calendar providers, as in F10/F11). Atomic slot reservation, the candidate self-scheduling email, the concrete-panel finalisation, the **atomic re-validation of the daily cap at booking**, and pipeline status transitions are F13; the candidate slot-picker UI, its accessibility, and page-performance targets are F14. The engine returns eligible slots annotated per-pool with qualifying participants; F13 picks the final panel and reserves.
- **Single-stage only**: Only single-stage interview scheduling is in scope. The multi-stage interview loop solver (Flow A2) is deferred to v1.5 and is explicitly out of scope.
- **Daily cap counts Cadence-managed interviews**: The per-interviewer daily cap counts interviews Cadence has scheduled (managed events in `managedCalendarEvents`, excluding DELETED/CLEANUP_INCOMPLETE, plus the required-participant slots offered in the same computation), not arbitrary busy time — generic busy time is already excluded by the required-participant free-check (FR-009). Counting is per zone-relative civil day. This matches the backlog acceptance criterion ("does not offer a third slot").
- **Availability-unknown is fail-safe**: Any required participant whose availability cannot be positively confirmed free (unconnected, needs reconnection, transient read failure) makes the affected slots ineligible, and an unknown pool member does not count toward quorum. The engine never assumes free; this is the load-bearing correctness guarantee against double-booking.
- **Slot-start cadence is configurable, with a sensible default**: Offered slots begin on a configurable cadence (default 15 minutes) anchored to the working-day start in the applicable zone, independent of the interview duration. The exact default and anchor are pinned in `plan.md`.
- **"Any N of pool" = eligibility + per-pool qualifying-set annotation**: The engine determines a slot is *eligible* when each pool reaches quorum on distinct positively-free members and annotates the qualifying members per pool; it does not bind a single concrete interviewer at computation time (that is finalised by F13 at booking). This keeps the engine deterministic and the slot list stable for F14.
- **New collection + a new index on an existing collection**: A new MongoDB collection (`interviewTemplates`) holds templates; its required index (`{workspaceId, status}`, to list active templates per workspace) AND a new index on the existing `managedCalendarEvents` (`{workspaceId, memberId, startAt}`, to count a member's same-day managed interviews for the cap — a query/index that does not exist today) are both created in the next Mongock changeset. The next changeset `order` is derived from the highest **applied** ChangeUnit (`007`), not the branch number. `plan.md` must declare the cap-count repository query (`countBy… status not in {DELETED, CLEANUP_INCOMPLETE} … startAt between`) and both indexes.
- **No new infrastructure or dependency**: The engine is pure in-stack interval arithmetic over `java.time` (DST via `ZoneId`/`ZonedDateTime`) plus the existing services; no broker, cache, scheduling library, `@Scheduled` task, or SDK outside the fixed stack is added.
- **No-cloud-credentials testing**: All rule, cap, DST, and fail-safe behaviours are verified against seeded availability via the existing stubbed-provider approach (JDK `HttpServer` stubs from F10/F11), so CI needs no live calendar credentials. The new status/slot enums are logged as `.name()` strings, never via structured-argument enum interpolation (the F01.1 logstash Jackson-3 footgun).
- **No GDPR member-erasure seam required**: Templates hold only internal member-id references (no candidate or participant PII), consistent with the `managedCalendarEvents` content-free model, so member erasure (F04) needs no F12 cleanup hook; a departed/deactivated member is handled gracefully at computation time (no availability → fail-safe).
- **DST correctness via absolute time**: All slot times are handled as absolute instants plus an explicit zone; naive local-time arithmetic is never used for storage, comparison, or generation.

## Dependencies

- **F03 — Workspace Setup & Configuration** (complete): provides the workspace working hours and time zone the engine uses by default, and the validation bounds a per-template override must honour.
- **F10 — Google Calendar** and **F11 — Microsoft 365** (complete): provide each participant's busy/free availability through the provider-agnostic `AvailabilityService` model the engine reads; F12 produces a uniform slot list across mixed-provider panels by construction, and adds the `{workspaceId, memberId, startAt}` index + count query on the existing `managedCalendarEvents` collection for the daily cap.
- **F02 — RBAC** (complete): provides the Recruiter/Admin roles, the deny-by-default method security, the 403 envelope, the scoped-not-found pattern, and the build-time endpoint-inventory test that template-management (and any compute) endpoints must satisfy.
- **F01 — Authentication & Session Management** (complete): authenticated member + workspace context for every operation.
- **F00.1 — MongoDB Index Bootstrapping**: the new `interviewTemplates` index and the new `managedCalendarEvents` cap index are created via the next Mongock changeset in sequence (order off `007`).
- **Consumed by F13 (Flow A1 scheduling) and F14 (Candidate Scheduling Page)**: F13 reserves against the engine's compliant slots, finalises the panel per pool, and re-validates the daily cap atomically; F14 renders them. F20 (Reschedule) re-runs the engine.

## Out of Scope

- Atomic slot reservation, the candidate self-scheduling link/email, concrete-panel finalisation, the atomic cap re-validation at booking, and pipeline status transitions (F13).
- The candidate slot-picker UI, its accessibility (WCAG), localisation, and mobile-performance targets (F14).
- Reading or storing calendar availability itself (owned by F10/F11) and the OAuth connect/token handling (F01.1).
- Workspace working-hours / time-zone configuration and its management UI (owned by F03).
- The multi-stage interview loop solver (Flow A2) — deferred to v1.5.
- Interviewer load-balancing / optimisation analytics (FR-8) — deferred to v1.5. (The daily cap is a hard per-interviewer ceiling, not load-balancing.)
- Auto-generated meeting links (Google Meet/Teams/Zoom) — deferred to v1.5.
- Reschedule/cancellation flow and its tokens/notifications (F20) — F20 re-invokes this engine.
