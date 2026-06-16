# Phase 0 Research: Flow A3 — Reschedule & Cancellation (F20)

All decisions verified against the real F13/F10 source (`SchedulingService`, `SlotReservationService`, `SchedulingReaper`, `CalendarEventService`, `CandidateErasureService`, `SchedulingRequest`). No NEEDS CLARIFICATION remained from the spec; the items below resolve the *how* within the fixed stack.

---

## D1 — A reschedule books the new time under a NEW `bookingRef` (a new `schedulingRequests` round)

**Decision**: Model a reschedule as a new `schedulingRequests` document (`mode=RESCHEDULE`) linked to the original booking via `parentRequestId` + `rootRequestId`. The new round's `id` is the new calendar `bookingRef`; the new events are created under it, and the original booking's events are cancelled under the original `bookingRef`.

**Rationale**: `CalendarEventService.createForParticipant` has an idempotent fast-path: if a `managedCalendarEvents` row `{workspaceId, bookingRef, memberId, provider}` is already `CREATED`, it returns the existing provider event id and makes **no** provider call. F13 sets `bookingRef = schedulingRequest.id`. If a reschedule reused that id, `createPanelEvents` would return the *old* event at the *old* time and never create the new one. A fresh round id is therefore mandatory — and it makes the entire F13 confirm saga (`SlotReservationService.book`) reusable unchanged for the new booking, and keeps the per-participant claim on a different `startAt` (no self-collision with the parent's still-`ACTIVE` claim).

**Alternatives rejected**: (a) Reuse the original `bookingRef` + `updatePanelEvents` (in-place move) — rejected: `updatePanelEvents` resolves the stored provider event id and PATCHes it, but it cannot express the atomic "new-before-old / preserve original on failure" invariant (FR-009) — an in-place PATCH that fails leaves the event at neither a clean old nor new state, and there is no second booking to roll back to. (b) A brand-new `bookings` collection — rejected: unnecessary; `schedulingRequests` already is the booking aggregate, and reuse keeps the reaper/claim/token machinery intact (C2).

---

## D2 — Atomic swap = create-new-then-cancel-old; the child `status==BOOKED` flip is the durable commit point

**Decision**: The reschedule confirm reuses `SlotReservationService.book` to create the new round's events FIRST. Only on `PanelOutcome.CREATED` (child flips `BOOKING→BOOKED`) does the **forward-commit** step run: `CalendarEventService.cancelBooking(workspaceId, parentId)` removes the old events, the parent's claims are released, and the parent flips `BOOKED→RESCHEDULED`. On `ROLLED_BACK`/`CLEANUP_INCOMPLETE`/any throw before the child `BOOKED` flip, the parent is **never touched** — it remains the authoritative `BOOKED` booking (FR-009/FR-010).

**Rationale**: This reuses F13's existing ordering (events created before the status flip; rollback compensates only the child) so the "original preserved on any failure" guarantee is structural, not bolted on. The child `status==BOOKED` is already persisted by the existing CAS, so it **is** the durable commit boundary FR-023 needs — no extra intent marker. Observable invariant (FR-009): a reader sees the parent `BOOKED` until the instant the child commits, then the child `BOOKED`; the transient window where both have live events is internal and bounded by the recovery sweep (D3) — **the bound is `reaperThreshold`** (default `PT10M`), during which a `status()` read returns the parent as authoritative (D10).

**Implementation precision (Backend review fix)**: the real `book()` does `mongo.findAndModify({_id, status:BOOKING} → BOOKED)` and ignores the result. The F20 `RESCHEDULE` branch MUST **capture** that result with `returnNew(true)` and forward-commit **only when it is non-null** (the CAS actually matched), and the parent cancel MUST itself be a CAS `findAndModify({_id: parentId, status: BOOKED} → RESCHEDULED)` with the parent-event-cancel + claim-release keyed off **that** match — so a reaper that already forward-committed, or a concurrent path, is a clean no-op. This means the `CREATED` case is *edited*, not reused verbatim.

**Alternatives rejected**: cancel-old-then-create-new — rejected: a failure after cancel leaves the candidate with **no** interview (violates FR-009). Two-phase commit / Mongo multi-doc transaction across the swap — rejected: heavier than needed and the §IV "no broker / single-document CAS" posture already gives exactly-once via the claim index; the recovery sweep covers the crash window.

---

## D3 — Deterministic recovery: a new pass in the existing `SchedulingReaper`

**Decision**: Add one pass to `SchedulingReaper.sweep()`. The parent-`BOOKED` condition is **cross-document**, so it cannot be a single covered finder predicate (QA review fix). The pass is therefore an **indexed child-scan + per-row parent CAS**: finder `{mode:RESCHEDULE, status:BOOKED, updatedAt < now-threshold}` (covered by `{mode,status,updatedAt}`, `Pageable`-capped), then for each row a CAS `findAndModify({_id: parentRequestId, status: BOOKED} → RESCHEDULED)`; on match → cancel parent events + release parent claims (forward-commit). This exactly mirrors the existing stuck-`BOOKING` pass shape (an indexed scan + per-row CAS). The existing pass that releases a stuck `BOOKING` round back to `PENDING_SELECTION` already covers the "crash before child committed → roll back, parent stands" case with **no change** (`findStuck` filters on `status` only, not `mode`, so a stuck `BOOKING` reschedule round is reaped exactly like a stuck initial booking; its parent was never touched).

**Rationale**: The forward/rollback decision is driven entirely by two already-persisted booleans (child `BOOKED?`, parent `BOOKED?`) — fully deterministic (FR-023, the QA/Backend MAJOR). Reuses the F00.2 `SchedulerCheckpointService` (idempotent + missed-fire replay); correctness rests on the per-row `findAndModify` CAS, not single-threading. The reaper threshold invariant from F13 (`reaperThreshold > (perCallReadTimeout + maxBackoff) × maxPanelSize`) keeps it from racing a live confirm; the forward-commit pass keys on the parent-still-`BOOKED` CAS so a concurrent live confirm that already finished is a no-op.

**Alternatives rejected**: a separate `@Scheduled` recovery task — rejected: needless second checkpoint; the booking reaper already owns this collection and cadence.

---

## D4 — The reschedule/cancel credential: a rotating `manageTokenHash` on the booking

**Decision**: The currently-`BOOKED` round carries a `manageTokenHash` = `TokenHasher.hashToken(SecureTokens.newToken())` (256-bit, HMAC at rest, unique index). The confirmation email (initial and every post-reschedule) carries `…/booking?token=<manageToken>`. The candidate `GET`s the current booking (times only + `{canReschedule, canCancel}`), then either:
- **Reschedule**: `POST …/reschedule` opens a `RESCHEDULE` round (computes fresh slots, copies `locationText`, mints the round's own slot-pick `tokenHash`) and returns `{ rescheduleToken, zoneHint, slots[] }`; the candidate then uses the **existing** F13 `GET/POST /api/candidate/scheduling/{rescheduleToken}` view/confirm (confirm runs the D2 swap because the round's `mode=RESCHEDULE`). On forward-commit, a **fresh** `manageTokenHash` is minted onto the new round and delivered in the new confirmation (rotation); the consumed slot-pick token is single-use (F13).
- **Cancel**: `POST …/cancel` (affirmative POST, never GET — no prefetch/scanner auto-cancel, FR-012).

**Rationale**: One booking-scoped credential gives both verbs (the UX "same link"); rotation-on-reschedule satisfies "single-use per slot-pick / fresh credential per round" (FR-017). The reschedule round reuses the F13 view/confirm endpoints verbatim (the slot-pick token is delivered over TLS in the JSON response, never in a persisted URL). All target resolution is from the credential-bound booking — the `/reschedule` and `/cancel` request bodies are **ignored for target resolution** (no client-supplied booking/candidate/slot id may override the binding; FR-017a, IDOR). `manageTokenHash` is `@Field(write=NON_NULL)` and cleared via `$unset` (NOT `$set(null)`) so the partial-unique index never sees two present-as-null keys (the F01 footgun).

**Token-response precedence (Security review fix — the manage credential has NO 72h TTL; it is bound to the booking lifecycle, unlike the F13 slot-pick token)**: `BOOKED`/cancellable → 200 (with capabilities; the "cap reached / lead-time" *reason* shown only on this authenticated page, never as a distinguishable token response — FR-018, no oracle); `CANCELLING/CANCELLED/RESCHEDULED` → 200 terminal (honest closure); the booking's **interview already in the past** (genuinely existed, now un-manageable) → **410 Gone** distinct (satisfies SC-008's distinct-expired experience for the manage link); unknown/cleared/superseded manage hash → byte-identical **400**; rate-limited → 429. The F13 **410 for an expired slot-pick token** still applies to the reschedule *round* token via the unchanged F13 `view`/`confirm`.

**Alternatives rejected**: reuse the consumed F13 scheduling token as the manage credential — rejected: it is single-use-consumed at booking (F13 FR-009) and semantically a slot-pick token, not a booking-management token; a distinct rotating credential is cleaner and auditable. A JWT manage token — rejected: opaque-hashed random is the established Cadence candidate-link pattern (no new crypto surface).

---

## D5 — Reschedule cap is DERIVED from committed rounds, not a stored counter

**Decision**: The cap (default 3, `SchedulingProperties`) is enforced by counting committed reschedule rounds in the lineage: `countByRootRequestIdAndModeAndStatus(root, RESCHEDULE, BOOKED) >= cap` → refuse self-service reschedule (invalidate manage link, notify recruiter `RESCHEDULE_CAP_REACHED`, candidate sees "contact your recruiter"). Recruiter-initiated reschedule ignores the cap.

**Rationale**: Deriving from `BOOKED`/`RESCHEDULED` rounds makes the count crash-safe and rollback-safe **by construction** — a rolled-back, refused, or same-time no-op reschedule never produced a committed round, so it cannot consume an attempt (FR-005/SC-013, the QA MAJOR). No separate counter to drift under the recovery sweep. Cumulative over the booking lifetime (counts the whole `rootRequestId` lineage), never reset.

**`rootRequestId` bootstrap (Backend review fix)**: the `INITIAL` row's id is Mongo-generated **on insert**, so it cannot set `rootRequestId` in the same builder. Rule: `INITIAL` leaves `rootRequestId` **null** (null-means-self); the first `RESCHEDULE` round sets `rootRequestId = parent.rootRequestId != null ? parent.rootRequestId : parent.id`. The cap count roots on the INITIAL booking's own id (and the lineage index `{rootRequestId,mode,status}` covers it). No second post-insert write to backfill the root (that would add a crash window where it is null).

**Alternatives rejected**: a `rescheduleCount` field incremented at commit — rejected: must be incremented atomically with the swap and reset-proof under recovery; deriving from durable committed rounds is simpler and inherently correct. A post-insert `rootRequestId=self` backfill — rejected: non-atomic null window.

---

## D6 — Same-time confirm is a no-op short-circuited before any claim insert (FR-027)

**Decision**: Reschedule slot computation **excludes the currently-booked instant** from the offered set (FR-006), so the same-time slot is normally never offered. As defence-in-depth, the reschedule book path checks, **before** inserting any `interviewSlotClaim`, whether the chosen new start equals the parent's booked start; if so it returns the existing booking as an idempotent no-op (no event churn, no notification, no credential consumption, no cap consumption).

**Rationale**: Without the pre-claim short-circuit, a same-time confirm would try to `insert` an `ACTIVE` claim on `{member, parentStartAt}` that the parent already holds → `DuplicateKeyException` → a false "slot already taken" against the candidate's **own** booking (the Backend MAJOR). Evaluating before the claim insert is the load-bearing ordering.

**Alternatives rejected**: rely only on FR-006 exclusion — rejected: a stale/forged client payload could still submit the booked instant; the guard must be at confirm.

---

## D7 — Reschedule computation carves out the booking being moved from caps/availability (FR-006)

**Decision**: When computing the reschedule round's slots, the original booking's own participants' held time must NOT count against their availability or the F12 daily-interview-cap. Approach: the original booking's calendar events are still live during compute (D2 keeps them until commit), so availability would see the interviewer as busy at the old time and the cap as consuming one slot. Carve-out: pass the parent `bookingRef` to the rule-engine/availability path as an "ignore this booking's events" set (the availability read already keys on `managedCalendarEvents`; exclude rows whose `bookingRef == parentId`), and decrement the relevant interviewer's effective daily-cap usage by the parent booking on the moved day.

**Rationale**: Otherwise a reschedule is falsely refused "no slots" because the interviewer is busy/at-cap solely on account of the very interview being moved (the QA finding). The carve-out is scoped to the parent booking only.

**Alternatives rejected**: provisionally cancel the old events before computing — rejected: that breaks FR-009 (a failed reschedule would have already torn down the original). Accept the conservative false-refusal — rejected: it makes "move to a slightly different time same day" frequently impossible, defeating the feature.

**Open implementation note (to tasks.md)**: the exact carve-out seam in `AvailabilityService`/`RuleEngine` (an excluded-`bookingRef` parameter vs. a post-filter) is pinned during tasking; both are pure reads, no new collection.

---

## D8 — Cancellation path and notifications

**Decision**: Cancel = CAS booking `BOOKED→CANCELLING` → `CalendarEventService.cancelBooking(workspaceId, bookingId)` (idempotent per-participant delete; returns `false` if any cleanup failed) → release `ACTIVE` claims → CAS `CANCELLING→CANCELLED` (or `→CLEANUP_INCOMPLETE` + recruiter alert if cleanup failed) → `$unset manageTokenHash` → notify. **Normal (interactive) cancel runs `cancelBooking` inline** (it is a user-facing action; a few provider deletes are acceptable, like the F13 inline booking create). Candidate-initiated cancel notifies the recruiter via the **in-app `RecruiterNotificationService`** (`INTERVIEW_CANCELLED_BY_CANDIDATE`, durable, **never consent-gated** — so a withdrawn-consent candidate's cancel still notifies); recruiter-initiated cancel notifies the candidate (consent-gated F22). Internal participants are made aware via their calendar-event removal (FR-013 floor — see the member-mail note below). Audit `SCHEDULING_CANCELLED` + candidate-audit `BOOKING_CHANGED`.

**Member-mail note (Security review fix)**: `SmtpEmailSender.sendEmail` is a **closed dispatcher** (unknown `templateId` → logged-and-dropped). The reschedule participant confirmation reuses the existing `INTERVIEW_CONFIRMATION_ID` (carries the new time). A dedicated cancellation member email is **optional**; if wanted it requires a new `OperationalEmailTemplates` constant AND a new `SmtpEmailSender` branch (the F13 closed-seam build-breaker). The recruiter cancel notification is an **in-app** record, not an SMTP send.

**Rationale**: Reuses `cancelBooking` (already idempotent + cleanup-incomplete honest bound) and the F22 notification split (the F13 precedent). The released slot becomes immediately selectable (RELEASED claim leaves the partial unique index — F13 non-circular precedent, SC-011).

**Alternatives rejected**: hard-delete the booking row — rejected: loses the audit lineage and the append-only status history (FR-021/FR-022).

---

## D9 — Erasure extension: cancel a BOOKED booking's calendar events (FR-024)

**Decision**: `CandidateErasureService.wipe` is **synchronous** (F04 D11, verified — it is the 202 path's O(1) work). Extend `supersedeLiveScheduling` to also handle a `BOOKED`/in-flight booking with **only O(1) writes synchronously**: release `ACTIVE` claims, CAS `→CANCELLED`, `$unset manageTokenHash`, supersede in-flight reschedule rounds, and set `calendarTeardownPending=true`. The **provider-side calendar-event removal is delegated to the reaper** (the D3 erasure-teardown pass: scan `{calendarTeardownPending:true}` → `cancelBooking` → clear flag, idempotent). So `wipe()` stays O(1)/non-blocking and the multi-second provider deletes happen async (FR-024 + §IV: no broker, the `@Scheduled` reaper is the async mechanism).

**Rationale**: F13's erasure left a BOOKED interview's calendar events in place (verified: `supersedeLiveScheduling` filters `PENDING_SELECTION`/`BOOKING` only) — a real residual-PII gap once F20 makes bookings long-lived. FR-024 requires zero calendar event or usable link for an erased subject. Doing the provider deletes inside the synchronous `wipe()` would blow the F04 202-within-2s SLA for a 5-person panel; delegating to the reaper keeps the ack fast. Append-only PII-free audit entries survive erasure unmodified (FR-022).

**Alternatives rejected**: inline `cancelBooking` inside the synchronous `wipe()` — rejected: provider round-trips block the erasure ack (SLA + §IV). A new broker/queue for the teardown — rejected: §IV forbids it; the existing `@Scheduled` reaper is the sanctioned async path.

---

## D10 — Recruiter-initiated reschedule re-invites; "Reschedule in progress" is derived

**Decision**: `SchedulingService.rescheduleByRecruiter` checks contactability, verifies ≥1 compliant slot exists (else retains the booking and returns "no alternatives — original retained", no dead invite), stamps `rescheduleInvitedAt` on the booking, and dispatches a consent-gated reschedule-invitation email to the candidate (the manage link). The existing booking stays `BOOKED`. "Reschedule in progress" is the derived status `BOOKED && rescheduleInvitedAt != null && no committed child yet`. Issuing a new reschedule invitation supersedes any prior live reschedule round/credential for the booking (FR-017b, the F13 re-send/supersede precedent).

**Rationale**: Preserves the booking until the candidate confirms (FR-003), avoids stranding the candidate, and reuses the F13 supersede pattern so only one reschedule session is authoritative.

---

## D11 — §II / §IX demonstrable leg and the blocking gate ownership

**Decision**: F20 ships real candidate pages: a booking-manage page + a cancel-confirm page (`features/booking/`, a **top-level public route sibling** of `/schedule` — no `authGuard`, the F13 route precedent), and reuses the F13 `features/schedule/` slot-picker for the reschedule round. Because **no polish feature follows F20**, F20 owns the **blocking** axe-core (0 WCAG 2.2 AA violations) and Lighthouse ≥ 85 gates on these new candidate routes. Concretely (QA review fix — the gate is otherwise vacuous):
- Extend `frontend/src/testing/axe.ts`-driven per-state specs to the manage + cancel components.
- Add the new candidate URLs to `lighthouserc.json` `ci.collect.url[]`: `…/booking?token=lighthouse-demo` (manage), the cancel state, and the reschedule slot-picker state.
- Extend `frontend/lighthouse/serve-with-stub.mjs` with canned handlers for `GET /api/candidate/booking/<demo>` (booked + capabilities) and `POST /api/candidate/booking/<demo>/reschedule` (times-only slots) — else Lighthouse renders the SPA-fallback `invalid` state and the gate measures nothing (the exact F14 squatter/vacuous-measurement bug).
- Extend `frontend/src/_headers` coverage if a new route prefix is involved; all strings `$localize`; no PII/token in URL/logs.

**E2E without a Chromium download (QA review fix — C7/Principle X)**: the backlog-required reschedule E2E (reschedule → old cancelled → new invites → audit) runs in the **existing Karma/EdgeHeadless harness** (the F14 approach), NOT Playwright — `playwright install` downloads Chromium and violates the NON-NEGOTIABLE C7. The flow is exercised as a Jasmine integration-style spec against the F10/F11 provider stubs, consistent with how F14 ran its candidate-page checks. Recruiter reschedule/cancel actions are internal screens (axe/Lighthouse N/A, F50/F51 precedent).

**Rationale**: §IX applies to all candidate surfaces; F13 only deferred its gate because F14 explicitly owned it. F20 has no such successor, so it must self-certify — but the gate must measure the *real* content-bearing state (the LHCI stub + url[] must be extended) and must not reintroduce a tool download (C7).

---

## Decisions summary

| # | Decision | Key reuse |
|---|---|---|
| D1 | Reschedule = new `RESCHEDULE` round (new `bookingRef`) | `SlotReservationService.book`, `interviewSlotClaims` |
| D2 | Create-new-then-cancel-old; child `BOOKED` = commit point | `createPanelEvents` / `cancelBooking` |
| D3 | Recovery via a new pass in the existing reaper | `SchedulingReaper`, F00.2 checkpoint |
| D4 | Rotating `manageTokenHash` booking credential | `SecureTokens` / `TokenHasher` |
| D5 | Cap derived from committed `BOOKED` rounds | repo `count` query |
| D6 | Same-time no-op short-circuited pre-claim | confirm guard |
| D7 | Carve out the moved booking from caps/availability | `AvailabilityService` read filter |
| D8 | Cancel = `cancelBooking` + release + notify split | `cancelBooking`, F22 / member-mail |
| D9 | Erasure cancels BOOKED events | `CandidateErasureService` |
| D10 | Recruiter reschedule re-invites; status derived | F13 supersede pattern |
| D11 | F20 owns the blocking §IX gate on its pages | F14 `axe.ts` / LHCI harness |
