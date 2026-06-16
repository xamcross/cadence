# API Contracts: Flow A3 — Reschedule & Cancellation (F20)

Error envelope is the existing Cadence shape `{ "error": "<code>", "message": "<safe>" }` — no PII, no token values. Status codes per spec. Candidate booking endpoints resolve the target booking **solely** from the manage credential (FR-017a — no booking/candidate/slot id in the request may override the binding).

---

## A. Candidate booking management — `/api/candidate/booking/**` (public-by-token, rate-limited 10/min/IP → 429)

`CandidateBookingController`, on the existing `@Order(2)` permitAll/STATELESS chain (the `/api/candidate/` prefix is already allow-listed in `RbacEndpointInventoryTest`). Auth = the manage token; CSRF-exempt (STATELESS).

### A1. View current booking — `GET /api/candidate/booking/{manageToken}`

The manage credential is bound to the booking lifecycle and has **no separate 72h TTL** (unlike the F13 slot-pick token); eligibility is time-based on the interview.

**Response precedence (ordered, top-down):**
1. token hashes to a `BOOKED` booking → `200` with the booking + capabilities.
2. token hashes to a `CANCELLING`/`CANCELLED`/`RESCHEDULED` booking → `200` reflecting that terminal state (the candidate legitimately holds it; honest closure).
3. token hashes to a booking whose **interview start is already in the past** → `410 Gone` (a genuinely-existed, now-un-manageable booking — the distinct "expired" experience, SC-008).
4. everything else (unknown / cleared / superseded manage hash) → `400 invalid`, byte-identical (no existence oracle).

- `200 OK` (booked) — `{ "status": "booked", "bookedStart": "<instant>", "zoneId": "America/New_York",
    "canReschedule": true, "canCancel": true, "rescheduleRemaining": 2 }`.
  **Times only** — no participant names/emails, no `locationText` (FR-020/C3). `canReschedule=false` when the cap is reached or the interview is within the lead-time (FR-004/FR-005); the *reason* is shown only on this authenticated 200 page, never as a distinguishable token-validation response (FR-018, no oracle). `Cache-Control: no-store`.
- `200 OK` (cancelled / rescheduled) — `{ "status": "cancelled" | "rescheduled", "at": "<instant>" }`.
- `410 Gone` — `{ "error": "expired", "message": "This interview has already taken place — contact your recruiter." }` (interview in the past).
- `400 invalid` — byte-identical across unknown / cleared / superseded.
- `429 rate_limited`.

> **IDOR (FR-017a)**: A2/A3 request bodies are **ignored for target resolution** — the booking, candidate, participant set, and current slot come **only** from the manage-credential-bound row. A reschedule round's `parentRequestId` + participant set are set server-side at `openReschedule` time from the bound parent, never from client input.

### A2. Open a reschedule — `POST /api/candidate/booking/{manageToken}/reschedule`

Opens a `RESCHEDULE` round (computes fresh slots, carving out the moved booking — D7), returns its slot-pick token + times. The candidate then uses the existing F13 `GET/POST /api/candidate/scheduling/{rescheduleToken}` to view/confirm; confirm runs the swap saga (the round's `mode=RESCHEDULE`).

- `200 OK` — `{ "rescheduleToken": "<opaque>", "zoneHint": "America/New_York",
    "slots": [ { "slotId": "...", "start": "<instant>", "end": "<instant>", "zoneId": "..." } ] }`. The new slot excludes the currently-booked instant (FR-006). `rescheduleToken` is delivered over TLS in the body only — never in an email/persisted URL.
- `409 cap_reached` — self-service reschedule cap reached; manage link invalidated, recruiter notified `RESCHEDULE_CAP_REACHED`; candidate message "you've reached the reschedule limit — contact your recruiter" (FR-005). Authenticated page, not a token oracle.
- `409 ineligible` — interview past / within the self-service lead-time (FR-004); booking unchanged.
- `422 no_slots` — zero compliant alternatives; **booking retained unchanged**, recruiter notified `RESCHEDULE_NO_SLOTS` (FR-007).
- `409 not_available` — candidate became not-contactable since booking (FR-016); byte-identical across deny reasons (no GDPR oracle).
- `400 invalid` — unknown/cleared manage token. `429 rate_limited`.

> **Confirm** of the reschedule reuses **F13 contract B2** `POST /api/candidate/scheduling/{rescheduleToken}/confirm` verbatim, with these F20-specific outcomes layered on the existing ones:
> - `200 OK` (rescheduled) — new events created, **old events cancelled**, fresh confirmation + manage token issued, `SCHEDULING_RESCHEDULED` + `BOOKING_CHANGED` audit. Same-time pick → idempotent no-op returning the existing booking (FR-027).
> - `409 slot_taken` / `409 slot_no_longer_available` / `409 cleanup_incomplete` / `410 expired` / `409 not_available` — exactly as F13 B2; on any of these the **original booking remains intact and BOOKED** (FR-009/FR-010), and the response offers the remaining valid slots where applicable.

### A3. Cancel the booking — `POST /api/candidate/booking/{manageToken}/cancel`

Affirmative, state-changing POST (never a GET — no prefetch/scanner auto-cancel, FR-012). Body may carry an explicit confirm flag; resolution is from the credential only.

- `200 OK` — `{ "status": "cancelled", "at": "<instant>" }`. Side effects: CAS `BOOKED→CANCELLING` → `cancelBooking` (events removed for all participants) → release claims → `CANCELLED` → manage token cleared → recruiter notified `INTERVIEW_CANCELLED_BY_CANDIDATE` → `SCHEDULING_CANCELLED` + `BOOKING_CHANGED` audit (FR-012/FR-013).
- `200 OK` (idempotent) — already `CANCELLED` for this token → returns the existing cancellation (FR-015).
- `409 cleanup_incomplete` — a participant's event could not be removed after retries; booking `CLEANUP_INCOMPLETE`, recruiter alerted to remove the orphan; candidate sees "cancelled — your recruiter will confirm" (FR-012 honest bound).
- `409 ineligible` — interview past/within lead-time (FR-004). `400 invalid` — unknown token. `429 rate_limited`.

---

## B. Recruiter reschedule & cancel — `/api/internal/**` (RBAC: ADMIN or RECRUITER, workspace-scoped)

`SchedulingController` (extended), class-level `@PreAuthorize("hasAnyRole('ADMIN','RECRUITER')")`. Out-of-workspace booking → the shared scoped-not-found `404` (oracle-free, FR-025).

### B1. Recruiter reschedule — `POST /api/internal/candidates/{candidateId}/scheduling/reschedule`

Re-invites the candidate, preserving the existing booking (D10/FR-003).

- `200 OK` — `{ "status": "reschedule_in_progress", "invitedAt": "<instant>" }`. Side effects: contactability check → verify ≥1 slot exists (carve-out) → stamp `rescheduleInvitedAt` → dispatch consent-gated reschedule-invitation email (manage link) → supersede any prior live reschedule round (FR-017b) → audit. Existing booking stays `BOOKED`.
- `422 no_slots` — no alternatives; **booking retained**, recruiter told "no alternatives — original booking retained" (FR-007), no candidate email.
- `409 not_contactable` — candidate not contactable (reason category only) (FR-016).
- `409 no_active_booking` — candidate has no `BOOKED` interview to reschedule.
- `404 not_found` — candidate not in workspace (oracle-free). `403 forbidden` — wrong role.

### B2. Recruiter cancel — `POST /api/internal/candidates/{candidateId}/scheduling/cancel`

- `200 OK` — `{ "status": "cancelled", "at": "<instant>" }`. Side effects: CAS `BOOKED→CANCELLING` → `cancelBooking` → release claims → `CANCELLED` → notify the **candidate** (consent-gated) → `SCHEDULING_CANCELLED` + `BOOKING_CHANGED` audit (initiator = the recruiter member id).
- `409 cleanup_incomplete` — surfaced orphan (recruiter is the actor; shown in-line).
- `409 no_active_booking` — nothing booked. `404 not_found`. `403 forbidden`.

### B3. Booking status (extended F13 A2) — `GET /api/internal/candidates/{candidateId}/scheduling`

`200 OK` — now surfaces the reschedule lifecycle: `{ "status": "PENDING_SELECTION|BOOKED|RESCHEDULE_IN_PROGRESS|RESCHEDULED|CANCELLED|EXPIRED|...", "bookedStart": "<instant|null>", "rescheduleCount": 1, "sentAt": "...", "updatedAt": "..." }`. No token, no participant PII. `RESCHEDULE_IN_PROGRESS` is derived (`BOOKED && rescheduleInvitedAt != null && no committed child`).

---

## C. Internal SPI (service-layer, no HTTP)

```java
// SlotReservationService (candidate booking controller)
BookingView           viewBooking(String rawManageToken, String ip);
OpenRescheduleResult  openReschedule(String rawManageToken, String ip);   // -> {rescheduleToken, slots} or refusal
CancelResult          cancel(String rawManageToken, String ip);           // candidate-initiated
// (confirm of the reschedule round is the existing F13 confirm(rawToken, slotId, ip) — mode=RESCHEDULE triggers the swap)

// SchedulingService (recruiter controller)
RescheduleInviteResult rescheduleByRecruiter(String workspaceId, String actorMemberId, String candidateId, String ip);
CancelResult           cancelByRecruiter(String workspaceId, String actorMemberId, String candidateId, String ip);
```

Both services remain privileged internal primitives reached only via their gated controllers (the F13/F10 precedent).

---

## D. Contract test coverage (MockMvc)

- A1: 200 booked (asserts capabilities + **no participant identity / no locationText** — non-circular: seed member ids + a location sentinel, assert absent); 200 cancelled/rescheduled; 400 invalid (byte-identical unknown/cleared/superseded); 429.
- A2: 200 open (slots exclude booked instant); 409 cap_reached (recruiter notified, link invalidated); 409 ineligible; 422 no_slots (booking unchanged + recruiter notified); 409 not_available (erased, byte-identical); 400; 429.
- A2-confirm (reuses B2 harness): 200 rescheduled (asserts **old events cancelled + new created** + confirmation + fresh manage token + audit); same-time no-op (no churn); 409 slot_taken (gated concurrent reschedule-vs-cancel + double-confirm single-winner, SC-004); original-preserved-on-failure (SC-003).
- A3: 200 cancelled (asserts events removed + slot released + recruiter notified + audit); 200 idempotent replay; 409 cleanup_incomplete; 409 ineligible; 400; 429; **affirmative-POST** (a GET to the cancel path does not cancel).
- B1: 200 reschedule_in_progress (booking stays BOOKED, candidate emailed); 422 no_slots (retained); 409 not_contactable; 409 no_active_booking; 404 scoped; 403 each disallowed role (5-role matrix).
- B2: 200 cancelled (candidate notified); 409 cleanup_incomplete; 409 no_active_booking; 404; 403.
- B3: status reflects RESCHEDULE_IN_PROGRESS / RESCHEDULED / CANCELLED.
- IDOR: a manage token bound to booking X cannot affect booking Y even with Y's id in the body (FR-017a / SC-014).
- Recovery: reaper rolls forward (child BOOKED, parent BOOKED → parent RESCHEDULED) and rolls back (child stuck BOOKING → released, parent stands); erasure async-teardown pass cancels events for `calendarTeardownPending` bookings.
- Erasure: `wipe()` synchronously CASes the BOOKED booking → CANCELLED + releases claims + `$unset` manage token + sets `calendarTeardownPending`; the reaper removes the events async → zero residual events (FR-024/SC-009); PII-free audit entries survive.
- Index: two cleared (`$unset` manage-token) bookings do NOT collide on the partial-unique `{manageTokenHash}` (the F01 null-collision regression test).
