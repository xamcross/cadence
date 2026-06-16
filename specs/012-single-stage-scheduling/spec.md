# Feature Specification: Flow A1 — Single-Stage Scheduling (F13)

**Feature Branch**: `012-single-stage-scheduling`  
**Created**: 2026-06-16  
**Status**: Draft  
**Input**: User description: "checkout main branch, update from origin. then find the next unimplemented feature in the backlog and create a spec for it. review with appropriate sub-agents"

> Backlog reference: F13 — Flow A1: Single-Stage Scheduling (Tier 1, P1). Spec refs: product spec §4 Flow A1, §5.1 (FR-1, FR-2, FR-3, FR-5). This is the first deployable end-to-end demo: it ties together the calendar adapters (F10/F11), the rule engine (F12), the template library (F21), and the email channel (F22) into a single recruiter-to-candidate scheduling journey.
>
> **OD-1 resolved (backlog v1.2.0)**: no automatic video-call link generation in the MVP (FR-7 deferred to v1.5). Interview location/dial-in is recruiter-provided free text carried onto the calendar event. This spec assumes that resolution.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Recruiter sends a self-scheduling link (Priority: P1)

A recruiter opens a candidate, chooses an interview stage template, and clicks "Send scheduling link" in a single action. Cadence reads the real-time availability of all required and optional participants, applies the template's rules, computes the set of genuinely free, compliant slots, records the scheduling request, and dispatches a branded self-scheduling email to the candidate. The recruiter immediately sees the candidate's status change to "Link sent."

**Why this priority**: This is the entry point of the entire flow and the recruiter's core value moment — replacing the manual back-and-forth of finding interview times. Without it, nothing downstream can happen. It is independently demonstrable: a recruiter can trigger it and verify the candidate received a working link with valid slots.

**Independent Test**: With a connected interviewer calendar and an existing interview template, a recruiter triggers scheduling for a test candidate and confirms (a) a scheduling email is dispatched, (b) the link resolves to a set of compliant slots, and (c) the candidate's pipeline status reads "Link sent."

**Acceptance Scenarios**:

1. **Given** a candidate with recorded contact consent and an interview template whose required participants have connected calendars, **When** the recruiter clicks "Send scheduling link", **Then** Cadence computes the compliant slots, persists a scheduling request, dispatches the invitation email, audit-logs the action, and the candidate status becomes "Link sent."
2. **Given** a template whose rules yield zero compliant slots in the searched window (e.g., all interviewers fully booked), **When** the recruiter triggers scheduling, **Then** no email is sent, no link is created, and the recruiter sees a clear "No available slots in the next N days — widen the window or adjust the template" message.
3. **Given** a candidate whose contact consent is missing, withdrawn, over-retention, or who is marked undeliverable/erased, **When** the recruiter triggers scheduling, **Then** the request is refused with a clear not-contactable reason and no email is dispatched.
4. **Given** a required participant whose calendar is not connected or needs reconnection, **When** the recruiter triggers scheduling, **Then** Cadence refuses (or clearly flags the unschedulable participant) rather than silently treating that participant as free.

---

### User Story 2 - Candidate self-schedules without an account (Priority: P1)

A candidate receives the branded email, opens the link on any device, sees only available, rule-compliant times shown in their own time zone, and picks one — with no login and no app install. Cadence atomically reserves the slot, books calendar events for every participant (with the recruiter-provided location/dial-in details), and sends confirmation emails to the candidate and participants. The candidate sees an immediate confirmation.

**Why this priority**: This is the candidate-facing payoff and the second half of the end-to-end flow. It must be correct under concurrency (no double-booking) and must never require candidate authentication. It is independently testable from a valid link.

**Independent Test**: Open a valid scheduling link, pick a slot, and confirm that (a) calendar events appear for all participants, (b) confirmation emails are sent, (c) the slot can no longer be picked again, and (d) the candidate sees a confirmation page — all without authenticating.

**Acceptance Scenarios**:

1. **Given** a valid, unexpired scheduling link, **When** the candidate opens it, **Then** the page loads the offered slots rendered in the candidate's local time zone with clear date/time labels and no personal data in the URL.
2. **Given** the candidate picks an available slot, **When** they confirm, **Then** the slot is reserved atomically, calendar events are created for all participants within the confirmation window, confirmation emails are dispatched, the booking is audit-logged, and the candidate sees a success confirmation.
3. **Given** two candidates' actions (or two devices) submit the same slot simultaneously, **When** both confirm, **Then** exactly one succeeds and the other receives a "slot already taken — please pick another" message with the remaining slots, and no double-booking occurs.
4. **Given** a calendar event creation fails for one participant after retries, **When** the booking is attempted, **Then** the booking is rolled back to a consistent state (no orphaned events on any provider, slot released) and the candidate is asked to try again or pick another slot.
5. **Given** an expired link, **When** the candidate opens it, **Then** they see a helpful "this link has expired — contact your recruiter" message (distinct from an invalid/used link), not an error page.
6. **Given** a link whose slot was already booked (single-use consumed), **When** the candidate reopens it, **Then** they see their existing confirmation details, not a re-booking opportunity.

---

### User Story 3 - Recruiter tracks scheduling status (Priority: P2)

The recruiter can see, per candidate, where each one is in the scheduling flow — link sent, slot picked / confirmed, or expired/failed — so they know who still needs attention without chasing email threads.

**Why this priority**: Visibility closes the loop and lets the recruiter act on stalled candidates, but the core scheduling value (Stories 1 and 2) is deliverable before a polished status surface exists. F13 ships a minimal per-candidate scheduling-status indicator; the full multi-candidate pipeline view with bulk actions is F51.

**Independent Test**: After sending a link and after a candidate books, confirm the candidate's scheduling status reflects each transition ("Link sent" → "Scheduled") on the recruiter's view.

**Acceptance Scenarios**:

1. **Given** a recruiter has sent a scheduling link, **When** they view the candidate, **Then** the status reads "Link sent" with the send timestamp.
2. **Given** the candidate has confirmed a slot, **When** the recruiter views the candidate, **Then** the status reads "Scheduled" with the chosen time, and the audit trail shows who/what/when.
3. **Given** a scheduling link has expired with no booking, **When** the recruiter views the candidate, **Then** the status reads "Link expired" so the recruiter can re-send.

---

### Edge Cases

- **Stale slot at booking time**: an offered slot becomes unavailable after the link was sent (an interviewer's calendar changed). The system re-validates availability at confirmation and, if the slot is no longer compliant/free, refuses that slot with a "no longer available" message and offers the remaining valid slots rather than booking a conflicted meeting.
- **All offered slots consumed/expired before the candidate picks**: the candidate sees a "no times currently available — your recruiter will follow up" message; the recruiter sees the request as needing a re-send.
- **Re-send while an earlier link is still live**: sending a new scheduling link invalidates the prior link for the same scheduling request so only one active booking path exists at a time.
- **Candidate becomes not-contactable between send and booking** (consent withdrawn, erased, over-retention, undeliverable): contactability is re-evaluated at confirmation (FR-014). An **erased** candidate's booking is refused outright (no calendar event for an erased data subject), and the candidate sees a neutral "this link is no longer available" message. For other not-contactable states the booking is likewise refused so a confirmed interview always has a deliverable confirmation path.
- **Daily-cap / pool exhaustion at booking**: a "any N of pool" panel must still satisfy the required quorum at booking time; if a pool member who was counted is no longer free, re-validation must re-select from the remaining qualifying pool or refuse.
- **Mixed-provider panel** (some Google, some Microsoft 365): events must be created on every participant's own provider; a failure on one provider rolls back the whole booking.
- **DST boundary**: a slot booked near a daylight-saving transition must land at the correct wall-clock time on every attendee's calendar and read correctly in the candidate's displayed time zone.
- **Rapid double-click / replay of the confirm action**: a repeated confirmation of an already-booked slot is a no-op that returns the existing confirmation, never a second booking or duplicate emails.
- **Token probing**: high-rate or invalid token attempts are rate-limited and do not reveal whether a token exists or map to candidate identity.

## Requirements *(mandatory)*

### Functional Requirements

**Initiation & slot computation**
- **FR-001**: A recruiter (Admin or Recruiter role) MUST be able to initiate single-stage scheduling for a candidate by selecting one interview stage template and triggering it in a single action.
- **FR-002**: The system MUST compute the offered slots by reading real-time availability of the template's required and optional participants and applying the template rules (working hours, time zone, buffers, daily caps, blackouts, "any N of pool" composition), reusing the existing rule engine; it MUST NOT offer a slot that is not rule-compliant and genuinely free.
- **FR-003**: The system MUST refuse initiation and surface a clear, actionable message when zero compliant slots exist in the searched window, without dispatching any email or creating a link.
- **FR-004**: The system MUST evaluate candidate contactability (consent recorded, not withdrawn, not over-retention, not erased, not undeliverable) before dispatching the invitation, and MUST refuse with a not-contactable reason when the candidate cannot be contacted.
- **FR-005**: The system MUST NOT treat any participant whose calendar is unavailable (not connected, needs reconnection, or transiently failing) as free. When a **required** participant is unschedulable for this reason, the system MUST refuse initiation and name the unschedulable participant to the recruiter. When an **optional** participant is unschedulable, the system MAY proceed without that participant and flag the exclusion.

**Scheduling link & candidate access**
- **FR-006**: The system MUST generate a self-scheduling link backed by a cryptographically random token of at least 128 bits of entropy that is not derived from candidate identity; the token MUST be stored only in hashed form and the link URL MUST contain no personal data or internal identifiers beyond the opaque token.
- **FR-007**: The candidate-facing scheduling page MUST be reachable without any login, account creation, or app install, and MUST authenticate solely via the link token.
- **FR-008**: The scheduling token MUST expire after a configurable time-to-live (default 72 hours from send). A token that genuinely existed and has expired MUST yield a distinct "expired" response (HTTP 410 Gone) with a helpful message; a used, invalidated, or non-existent token MUST yield a single indistinguishable "invalid" response (HTTP 400) so that the expired/invalid distinction never reveals whether an unknown token ever existed (no existence oracle, see FR-010).
- **FR-009**: The scheduling token MUST be single-use for booking: once a slot is confirmed, the token MUST NOT permit a second booking; reopening a consumed link MUST show the candidate their existing confirmation.
- **FR-010**: The token validation/scheduling endpoints MUST be rate-limited per source IP (default 10 requests/minute, returning HTTP 429 on breach) to resist brute-force/enumeration, and MUST NOT act as an existence or identity oracle (a request for an unknown token is indistinguishable from one for a used/invalid token).
- **FR-011**: The candidate-facing page MUST display offered slots in the candidate's local time zone with clear, DST-correct date/time labels, and MUST expose interview times only — never the identities (names/emails) of the internal participants.

**Reservation & booking**
- **FR-012**: Slot confirmation MUST be atomic: the system MUST guarantee that under simultaneous submissions for the same slot, exactly one booking succeeds and all others receive a "slot already taken" (HTTP 409 Conflict) outcome with the remaining options — no double-booking is possible. The reservation transitions the slot to a held state before any calendar event is created.
- **FR-013**: At confirmation time the system MUST re-validate that the chosen slot is still compliant and that the required participant quorum is still free; if not, it MUST refuse that slot and offer the remaining valid slots rather than booking a conflict. For an "any N of pool" panel, re-validation MUST re-select a still-free qualifying quorum from the pool; if a qualifying quorum can no longer be formed, the slot is refused. A successful re-selection MAY book a different qualifying participant set than the one snapshotted, and the booking still succeeds with the re-selected set.
- **FR-014**: The system MUST re-evaluate candidate contactability at confirmation time (not only at initiation); if the candidate has become erased or otherwise not-contactable since the link was sent, the booking MUST be refused (no calendar event is created for an erased/withdrawn data subject) rather than silently booked with a suppressed email.
- **FR-015**: On successful reservation the system MUST create calendar events for every participant on their respective provider, carrying the interview title, correct time zone, and recruiter-provided location/dial-in details (no auto-generated video link).
- **FR-016**: If any participant's calendar event creation fails after the adapter's retry policy is exhausted, the system MUST roll the booking back (release the slot, remove any events already created on any provider) so no orphaned events remain; the candidate MUST be able to retry or pick another slot. If a compensating deletion cannot itself complete after retries, the system MUST record a distinct "cleanup-incomplete" terminal state (a known, surfaced orphan — never a silent leak), surface it to the recruiter, and MUST NOT report the booking as cleanly completed.
- **FR-017**: A slot left in the held state by an interrupted confirmation (e.g., a crash mid-booking) MUST be automatically released back to available after a configurable threshold by a recovery sweep, so a stuck reservation never permanently blocks a slot. The recovery sweep MUST be idempotent and missed-fire-safe (it MUST use the shared scheduler-checkpoint pattern).
- **FR-018**: After a successful booking the system MUST dispatch a confirmation to the candidate (consent-gated, via the candidate email channel) and to each internal participant (via the member email path, which is not consent-gated — the same path as account/system mail), exactly once per recipient per booking. Each dispatch MUST be guarded by a per-recipient idempotency key so retries and replays cannot produce duplicate sends. A confirmation-email failure MUST NOT roll back the (already committed) booking — delivery is absorbed by the email channel's outbox.
- **FR-019**: A repeated/replayed confirmation of an already-booked slot MUST be a no-op that returns the existing confirmation and triggers no duplicate events or emails.

**Status & audit**
- **FR-020**: The system MUST expose the per-candidate scheduling status (at least: Link sent, Scheduled, Link expired/failed) to authorized recruiters, with the relevant timestamps. (In F13, "slot picked" and "confirmed" are a single atomic transition — there is no durable picked-but-unconfirmed state — so the recruiter-visible status moves directly from "Link sent" to "Scheduled".)
- **FR-021**: The system MUST audit-log scheduling-link generation, slot confirmation, booking success/rollback/cleanup-incomplete, and link expiry/invalidation — recording actor/initiator, candidate (internal identifier only), and timestamps, with no token value and no personal data in the log.
- **FR-022**: Re-sending a scheduling link for a candidate MUST invalidate any prior still-live link for the same scheduling request so only one active booking path exists; opening the superseded link MUST yield the same indistinguishable "invalid" response as any used/invalid token (FR-008).

**Privacy & access control**
- **FR-023**: All initiation and status endpoints MUST enforce role-based access (recruiter/admin scope, server-side); candidate-facing endpoints MUST remain public-by-token only.
- **FR-024**: No personal data (candidate name, email, phone) may appear in application logs at any level; only internal identifiers and opaque references are permitted.

### Key Entities *(include if feature involves data)*

- **Scheduling Request**: represents one recruiter-initiated attempt to schedule a candidate for a given interview template. Holds the candidate reference, template reference, search window, current status, the set of offered slots (snapshot), and lifecycle timestamps. Owns the link/token state.
- **Offered Slot**: a single proposed interview time within a scheduling request, with start/end instant, the participant set that would be booked (including which pool members satisfy a quorum), and a reservation state (available / held / booked). The unit of atomic reservation.
- **Scheduling Link / Token**: the candidate's keyed, hashed, expiring, single-use credential granting access to exactly one scheduling request's slot picker.
- **Booking / Confirmed Interview**: the result of a successful reservation — the chosen slot, the created calendar event references per participant, the recruiter-provided location details, and confirmation-dispatch state.
- **Audit Entry**: append-only record of each scheduling lifecycle event (link sent, slot booked, rolled back, expired), keyed by candidate, free of personal data and token values.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A recruiter can go from "open candidate" to "scheduling link sent" in under 30 seconds for a panel of up to 5 participants (measured against the test provider harness for reproducibility).
- **SC-002**: A candidate can open a valid link and complete booking (slot picked → confirmed) in under 2 minutes, with no login.
- **SC-003**: Under genuinely simultaneous confirmations of the same slot (a gated/latched concurrent test where all contenders reach the reservation point before any commits, repeated across multiple trials), exactly one booking is created and every other contender is cleanly refused — 100% free of double-bookings.
- **SC-004**: Every confirmed booking results in calendar events on all participants' calendars within 30 seconds of confirmation (measured against the test provider harness); on a mid-booking provider failure the result is either a complete rollback with zero orphaned events or a surfaced "cleanup-incomplete" state — never a silent orphan and never a clean-success report alongside an orphaned event.
- **SC-005**: A slot booked within one hour of a DST transition lands at the correct wall-clock time, verified against the recorded calendar event payload (correct UTC offset and IANA time zone, not naive local time), and renders correctly in the candidate's displayed time zone.
- **SC-006**: Zero occurrences of candidate personal data or token values in application logs across the full initiate→book→confirm flow (verified by log scan).
- **SC-007**: An expired link produces a helpful, distinct "expired" (410) experience in 100% of expired-link accesses, while used/invalid/unknown tokens are indistinguishable from one another (no existence oracle).
- **SC-008**: No scheduling or confirmation email is dispatched, and no booking is created, for a candidate who fails the contactability check (consent missing/withdrawn/erased/over-retention/undeliverable), in 100% of such cases — checked at both initiation and confirmation.
- **SC-009**: When zero compliant slots exist, initiation is refused with an actionable message and produces no link and no email, in 100% of such cases.
- **SC-010**: Re-sending a scheduling link invalidates the prior link, such that opening the superseded link 100% of the time yields the indistinguishable "invalid" response rather than a usable slot picker.
- **SC-011**: Every scheduling-link send, booking, rollback/cleanup-incomplete, and link expiry/invalidation is represented by exactly one append-only audit entry, free of personal data and token values.

## Assumptions

- **Dependencies are complete**: calendar adapters (F10 Google, F11 Microsoft 365), the interview template & rule engine (F12), the email template library (F21), and the email delivery channel (F22) are implemented and reused; F13 orchestrates them rather than reimplementing availability, rendering, or delivery.
- **Offered slots are snapshotted at send time**, then re-validated at booking time (FR-013). This avoids requiring the candidate's device to recompute panel availability live while still preventing conflicted bookings.
- **No automatic meeting-link generation** (OD-1 resolved): the interview location/dial-in is recruiter-provided free text. Auto-generated Meet/Teams/Zoom links (FR-7) are deferred to v1.5.
- **Single-stage only**: multi-stage / panel "loop" solving (Flow A2) is out of scope and deferred to v1.5.
- **Reschedule and cancellation** (Flow A3 / F20) are out of scope here beyond not precluding them; the single-use token model and audit trail are designed to be extended by F20.
- **Candidate UX polish is F14**: F13 delivers a functional candidate scheduling page; the mobile-first performance budget (<2 s on 4G), WCAG 2.2 AA conformance, and full localization targets are owned and verified by F14. F13 must not preclude them (no PII in URLs, time-zone-aware rendering, no login).
- **Full pipeline view is F51**: F13 ships a minimal per-candidate scheduling-status indicator; bulk actions and the colour-coded multi-candidate board are F51.
- **Default search window** for slot computation is the configurable working window already used by the rule engine (assumed a sane default such as the next 10 business days) unless the recruiter narrows it.
- **Confirmation window**: "within 30 s" / "within 60 s" targets follow the existing email and calendar acceptance criteria from F10/F11/F22.
- **Token TTL default is 72 hours**, configurable per workspace, consistent with the backlog's F14 token requirements. Token status-code contract (410 expired / 400 invalid / 429 rate-limited) and the 10 req/min/IP limit are adopted from the backlog's F14 "Token & expiry requirements," which apply equally to F13.
- **Two confirmation recipient paths**: the candidate confirmation rides the consent-gated candidate email channel (F22); internal-participant confirmations ride the non-consent-gated member/system email path (the F01 account-mail precedent). They use distinct per-recipient idempotency keys so participants never collide on a candidate-keyed key.
- **Post-booking confirmation delivery failures** (e.g., a hard bounce after the booking is already committed) are surfaced through the email channel's existing bounce handling (F22) and do not reverse the booking.
- **The stuck-reservation recovery sweep and any scheduled work reuse the shared scheduler-checkpoint pattern** (F00.2) for idempotency and missed-fire replay; no new broker, queue, or scheduler infrastructure is introduced.
- **MongoDB indexes** for the token-hash lookup, the slot-reservation CAS, the per-candidate status read, and the held-reservation recovery scan are declared in `plan.md` per the F00.1 pattern (the project's pre-implementation gate).
