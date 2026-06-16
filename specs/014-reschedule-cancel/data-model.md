# Phase 1 Data Model: Flow A3 — Reschedule & Cancellation (F20)

**No new collection.** F20 adds fields to `schedulingRequests`, new values to three enums (append-only), one new enum, behavioural extensions to `CandidateErasureService`/`SchedulingReaper`, and one Mongock changeset (`ChangeUnit013`). Every new persisted field is an id / instant / enum (no candidate PII); `locationText` remains the single encrypted field (copied onto a reschedule round so it survives the candidate's async confirm).

---

## 1. `schedulingRequests` (MODIFIED) — now the manageable booking aggregate + reschedule lineage

Added fields (all additive; existing F13 fields unchanged):

| Field | Type | Notes |
|---|---|---|
| `mode` | `SchedulingMode` | `INITIAL` (F13 booking) or `RESCHEDULE` (a reschedule round). Absent on existing rows → treated as `INITIAL`. |
| `rootRequestId` | String | Lineage key for the cap derivation (D5). **Bootstrap rule (Backend review fix)**: an `INITIAL` row leaves it **null** (its own id is generated on insert and means-self); the first `RESCHEDULE` round sets `rootRequestId = parent.rootRequestId != null ? parent.rootRequestId : parent.id`, so the whole chain shares one root = the INITIAL booking's id. The cap count roots on that id. (Never a second post-insert write to backfill it — that adds a crash window.) |
| `parentRequestId` | String | The immediately-preceding booking this round supersedes on forward-commit (`RESCHEDULE` only). |
| `manageTokenHash` | String | `HMAC-SHA-256(rawManageToken, TOKEN_PEPPER)`; the reschedule/cancel credential on the currently-`BOOKED` round. **`@Field(write = Field.Write.NON_NULL)`** + **unique PARTIAL** index `{manageTokenHash:{$exists:true}}` (NOT sparse — sparse alone is the F01 null-collision footgun: Spring writes `null` by default, and a sparse index still indexes present-as-null keys → two cleared rows collide; `write=NON_NULL` omits null from BSON and the partial filter is the established `interviewSlotClaims`/`calendarOAuthState` precedent). Rotated on each reschedule forward-commit; **cleared via `$unset`** (never `$set(null)`) on cancel/cap/ineligible. Raw never stored. |
| `rescheduleInvitedAt` | Instant | Set by recruiter-initiated reschedule (D10) — drives the derived "Reschedule in progress" status; null otherwise. |
| `cancelledAt` | Instant | Set on `CANCELLED`. |
| `calendarTeardownPending` | boolean | Set true by **erasure** (D9) when it CASes a BOOKED booking to CANCELLED synchronously but defers the provider-side event removal to the reaper (keeps `wipe()` O(1) / non-blocking, FR-024). Cleared when the reaper's idempotent teardown completes. Normal (interactive) cancel tears down inline and never sets it. |

> `locationText` (encrypted, `@JsonIgnore`, `write=NON_NULL`) is **copied plaintext-in-memory** (`getLocationText()`→`setLocationText()`, never pre-encrypted — the F03 double-encryption lesson) from the parent onto a `RESCHEDULE` round at open time so the new calendar event carries the same recruiter dial-in. Because the round is the **same `SchedulingRequest` class**, the existing `MongoPiiConfig` converter registration covers it — **no new converter registration**. The `toString()` is extended to omit `manageTokenHash` (in addition to the existing `locationText`/`tokenHash`).

**No `@Version`** (unchanged) — every transition is a `findAndModify` CAS.

---

## 2. State machine — `SchedulingStatus` (extended)

New values: `CANCELLING` (transient, cancel in flight), `CANCELLED` (terminal), `RESCHEDULED` (terminal — a booking superseded by a committed reschedule round).

```
   ── F13 ──►  PENDING_SELECTION ──confirm CAS──► BOOKING ──CREATED──► BOOKED
                                                     │                  │  │
                                       rollback/fail │                  │  └── reopen (idempotent) → existing confirmation
                                                     ▼                  │
                                             PENDING_SELECTION          │
                                                                        │
   ── F20 reschedule (child round R1, mode=RESCHEDULE) ─────────────────┤
        R1: PENDING_SELECTION → BOOKING → BOOKED   (reuses the F13 saga)│
        on R1 BOOKED (commit point) → forward-commit:                   │
            cancelBooking(parent) + release parent claims              ▼
            parent: BOOKED ─────────────────────────────────────► RESCHEDULED
        on R1 rollback/cleanup/crash-before-BOOKED → parent UNTOUCHED (stays BOOKED)

   ── F20 cancel ──►  BOOKED ──CAS──► CANCELLING ──cancelBooking ok──► CANCELLED
                                          │
                                          └── cleanup failed ──► CLEANUP_INCOMPLETE (recruiter alert)

   ── F20 erasure (D9) ──►  BOOKED ──► cancelBooking + release + ──► CANCELLED   (async path)
   ── reaper (D3): RESCHEDULE BOOKED w/ parent still BOOKED ──► forward-commit (cancel parent → RESCHEDULED)
   ── reaper (F13, unchanged): stuck BOOKING round ──► PENDING_SELECTION (parent, if any, stands)
```

- The **durable commit point** is the child round's `status==BOOKED` flip (already persisted by the existing CAS) — it deterministically tells the recovery sweep to roll forward vs. roll back (FR-023).
- A `RESCHEDULE` round in `PENDING_SELECTION`/`BOOKING` is reaped exactly like an initial round (no F13 change); its parent is only ever touched in the forward-commit, which CASes on `parent.status==BOOKED`.

---

## 3. `interviewSlotClaims` (REUSED, unchanged schema)

The reschedule round claims `{workspaceId, memberId, newStartAt}` — a **different** `startAt` than the parent's live claim, so the unique partial index (`status=ACTIVE`) does not self-collide. The same-time no-op (D6/FR-027) short-circuits before any insert, so the parent's own `{member, startAt}` is never re-claimed. Release on parent cancel / forward-commit is the existing CAS `ACTIVE→RELEASED` (a RELEASED row leaves the partial index → the freed slot is immediately re-selectable, SC-011).

---

## 4. Enums

**`SchedulingMode`** (NEW): `INITIAL`, `RESCHEDULE`.

**`SchedulingStatus`** (append): `CANCELLING`, `CANCELLED`, `RESCHEDULED` (never reorder existing).

**`AuthEventType`** (append-only, never reorder): `SCHEDULING_RESCHEDULED`, `SCHEDULING_CANCELLED`, `SCHEDULING_CAP_REACHED`. Reuse `SCHEDULING_REFUSED` for not-contactable / no-slots / stale on the reschedule path. Reuse `CALENDAR_EVENT_DELETED` / `CALENDAR_EVENT_CLEANUP_INCOMPLETE` (emitted by `cancelBooking`). Written via `AuthAuditService.record(type, workspaceId, actorMemberId-or-"CANDIDATE", outcomeLiteral, ip)` — value-free, no token, no PII.

**`CandidateEventType`** — `BOOKING_CHANGED` **already exists** in the enum (the F04 declared forward contract — do NOT re-add/reorder). Emit it on every reschedule and cancellation via the real signature `CandidateAuditService.append(workspaceId, candidateId, CandidateEventType.BOOKING_CHANGED, CandidateAuditOutcome.<value>, actorMemberId)` — the 4th arg is a `CandidateAuditOutcome` enum (not free text); reuse an existing outcome value or append one (append-only). These PII-free, append-only entries **survive candidate erasure unmodified** (FR-022) — `wipe()` touches only candidate PII fields, never `auditLog`.

**`RecruiterNotificationType`** (append-only, value-free): `INTERVIEW_CANCELLED_BY_CANDIDATE`, `RESCHEDULE_NO_SLOTS`, `RESCHEDULE_CAP_REACHED`. Logged via `.name()` only (the logstash `kv` enum footgun).

---

## 5. Modified services (behavioural, not schema)

- **`CandidateErasureService.wipe`** (which is **synchronous** — F04 D11, verified) — extend `supersedeLiveScheduling` to also handle a `BOOKED` (or in-flight `RESCHEDULE`) booking by doing only **O(1) writes synchronously**: release `ACTIVE` claims, CAS `→CANCELLED`, `$unset manageTokenHash`, supersede in-flight rounds, and set `calendarTeardownPending=true`. The **provider-side event removal is delegated to the reaper** (a few calendar HTTP deletes must NOT block the erasure ack — FR-024 async + §IV). The reaper teardown is idempotent and inherits the cleanup-incomplete honest bound (a provider failure surfaces, never blocks erasure).
- **`SchedulingReaper.sweep`** — add two passes (both `Pageable`-capped via `reaperSweepBatchLimit`, the F12 unbounded-query lesson; both inside the existing single `checkpoints.start/complete`):
  1. **Forward-commit recovery (D3)**: an **indexed** finder `{mode:RESCHEDULE, status:BOOKED, updatedAt < now-threshold}` (covered by `{mode,status,updatedAt}`), then a **per-row parent-status CAS** `findAndModify({_id: parentId, status: BOOKED} → RESCHEDULED)` (the parent-`BOOKED` condition is cross-document, so it is a per-row CAS, not part of the finder — mirrors the existing stuck-`BOOKING` pass) → on match: cancel parent events + release parent claims. A concurrent live forward-commit that already ran is a no-op.
  2. **Erasure calendar teardown**: an indexed finder `{calendarTeardownPending: true}` → `cancelBooking` + clear the flag (idempotent; already-DELETED events are no-ops).
  > Existing F13 pass unchanged: a stuck `BOOKING` round (initial OR reschedule — `findStuck` filters on `status` only, not `mode`) → released to `PENDING_SELECTION`; its parent (if any) is never touched ⇒ the roll-back case (crash before child committed) is covered for free.
- **`SlotReservationService.book`** — `RESCHEDULE` branch in the `CREATED` case: **capture** the child→`BOOKED` `findAndModify` result (`returnNew(true)`) and forward-commit **only if it matched**; the parent cancel is its own CAS `findAndModify({_id: parentId, status: BOOKED} → RESCHEDULED)` + `cancelBooking(parentId)` + release parent claims keyed off **that** match (so a reaper that already forward-committed is a no-op). Plus: the same-time no-op pre-claim guard (D6, compares the chosen new start to the **parent's** booked start resolved from the credential-bound parent, before the `claims.insert` loop). New methods `openReschedule(manageToken)`, `cancel(manageToken, actor)`, `viewBooking(manageToken)`. **Add a `releaseClaims(workspaceId, requestId)` overload** (an `updateMulti` on `schedulingRequestId`, the *reaper's* shape) — the existing `releaseClaims(List<InterviewSlotClaim>, now)` only releases the in-memory just-inserted list and cannot release the **parent's** claims.
- **`SchedulingService`** — `rescheduleByRecruiter` (re-invite, preserve booking, D10) and `cancelByRecruiter`; **`status()` resolves the authoritative booking from the root lineage**, NOT `findFirstByWorkspaceIdAndCandidateIdOrderByCreatedAtDesc` (the newest row may be an in-flight `PENDING_SELECTION` reschedule child while the parent is the authoritative `BOOKED` — returning the newest would wrongly show "not booked"). `RESCHEDULE_IN_PROGRESS` is derived from the parent (`BOOKED && rescheduleInvitedAt != null && no committed child`).
- **Member-mail notices (Security review fix)**: `SmtpEmailSender.sendEmail` is a **closed dispatcher** — an unknown `templateId` is logged-and-dropped (transmits nothing). The reschedule participant confirmation reuses the existing `OperationalEmailTemplates.INTERVIEW_CONFIRMATION_ID` (it carries the new time — no new template). For **cancellation**, participant awareness is satisfied by the calendar-event removal (FR-013 "at minimum via removal of their calendar event"); a dedicated cancellation member email is **optional** and, if added, requires BOTH a new `OperationalEmailTemplates` constant AND a new branch in the `SmtpEmailSender` if-chain (the F13 closed-seam build-breaker) — pinned as such in `tasks.md`.

---

## 6. Mongock `ChangeUnit013_RescheduleIndexes` (order "013")

Native driver `createIndex`; targeted `dropIndex` rollback (never `dropIndexes()`). Off the highest **applied** changeset "012" (NOT the branch number).

**`schedulingRequests`** (add):
- unique **PARTIAL** `{manageTokenHash: 1}` with `partialFilterExpression {manageTokenHash: {$exists: true}}` (NOT sparse — paired with `@Field(write=NON_NULL)` on the field; the `interviewSlotClaims` partial-index precedent, avoids the F01 present-as-null collision) — the reschedule/cancel credential lookup (`findByManageTokenHash`).
- `{rootRequestId: 1, mode: 1, status: 1}` — the cap-derivation count (D5, `countByRootRequestIdAndModeAndStatus`) + lineage reads.
- `{mode: 1, status: 1, updatedAt: 1}` — the reschedule forward-commit recovery scan (D3, `Pageable`-capped).
- partial `{calendarTeardownPending: 1}` with `partialFilterExpression {calendarTeardownPending: true}` — the erasure async-teardown reaper scan (D9, `Pageable`-capped).

> Reuses, no new index: the existing F13 `{tokenHash}` (unique), `{workspaceId, candidateId, createdAt:-1}`, `{status, expiresAt}`, `{status, updatedAt}`; `interviewSlotClaims` unique partial `{workspaceId, memberId, startAt}`; `managedCalendarEvents {workspaceId, bookingRef, …}` and `{workspaceId, memberId, startAt}` (availability/carve-out reads).

---

## 7. Validation rules (from spec FRs)

- **Open reschedule** (candidate or recruiter): manage token resolves a `BOOKED` booking (else 410/400 per precedence); interview is still eligible — `now < startAt − leadTime` (FR-004) else refuse `ineligible`; cap not reached — `countByRootRequestIdAndModeAndStatus(root, RESCHEDULE, BOOKED) < cap` (FR-005, candidate only); contactability permits for a candidate-channel dispatch (FR-016); `RuleEngine` returns ≥1 slot (carving out the moved booking, D7) else `RESCHEDULE_NO_SLOTS` (booking retained, recruiter notified) (FR-007).
- **Confirm reschedule**: reuses the F13 confirm guards (token live + not expired, slotId in snapshot, contactability re-check, re-validate + pool re-select, claim CAS, panel book) PLUS the same-time no-op pre-claim guard (D6) and the forward-commit on `CREATED` (D2). Rollback/cleanup leave the parent `BOOKED` (FR-009/FR-010).
- **Cancel**: manage token resolves a `BOOKED` booking; affirmative POST (FR-012); CAS `BOOKED→CANCELLING` (single-winner vs. a concurrent reschedule/cancel, FR-008); `cancelBooking` + release + `→CANCELLED`/`CLEANUP_INCOMPLETE`; notify per initiator (D8).
- **IDOR** (FR-017a): the booking, candidate, participant set, and slot are resolved **only** from the credential-bound row; no client-supplied id may override.
- **Audit** exactly once per action (FR-022/SC-010); no PII/token in logs (FR-026/SC-010). Recruiter endpoints workspace-scoped → oracle-free 404 (FR-025).
