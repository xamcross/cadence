# Phase 1 Data Model: Flow A4 — No-Show Defense (F23)

**No new collection.** F23 adds fields to `schedulingRequests` and `workspaceConfig`, new values to three enums + one merge-token enum (all append-only), one new `@Scheduled` component (`NoShowDefenseScheduler`), one `NoShowProperties` config block, behavioural extensions to `CandidateErasureService` and `BuiltInEmailTemplates`/`MergeTokenCatalogue`, and one Mongock changeset (`ChangeUnit014`). Every new persisted field is an id / instant / enum / boolean / duration (no candidate PII); the confirm credential is stored only as an HMAC hash. The cascade reminder rides the existing F22 consent-gated outbox.

---

## 1. `schedulingRequests` (MODIFIED) — the booking aggregate gains a confirmation-attendance lifecycle

Added fields (all additive; existing F13/F20 fields unchanged). These are **fields, not new `SchedulingStatus` values** — a booking stays `BOOKED` through the whole cascade (the F20 `cancelledAt`/`rescheduleInvitedAt` precedent of layering lifecycle as fields).

| Field | Type | Notes |
|---|---|---|
| `bookedStartAt` | Instant | **Denormalized** interview start (D2). Set in the `BOOKING→BOOKED` `findAndModify` CAS in `SlotReservationService.book` (the single commit site — both INITIAL and RESCHEDULE rounds flip through it). The cascade's only queryable start field; `null` on a non-BOOKED row. (The in-memory `chosenStart(req)` remains the source for the candidate payload; `bookedStartAt` is purely the index/sweep key.) |
| `confirmationRequestedAt` | Instant | Stage-1 stamp. Set by the cascade when the lead-time boundary is reached — **even when the candidate is not contactable** (records the attempt so stage 2 still escalates, D5). `null` ⇒ stage 1 not yet run. |
| `confirmTokenHash` | String | `HMAC-SHA-256(rawConfirmToken, TOKEN_PEPPER)` (D3). Minted at stage 1 **only when an email is actually sent** (contactable). **`@Field(write=NON_NULL)`** + **unique PARTIAL** index `{confirmTokenHash:{$exists:true}}` (NOT sparse — the F01 present-as-null collision footgun; the `manageTokenHash` precedent). Cleared via `$unset` on erasure (D9). Raw never stored. Distinct from `tokenHash` (slot-pick) and `manageTokenHash` (F20 reschedule/cancel). |
| `confirmationNotRequestable` | boolean | Internal, value-free (D5). `true` when stage 1 found the candidate not contactable (no email, no confirm token). Never surfaced as a differential recruiter signal (the escalation is the same coarse `INTERVIEW_UNCONFIRMED`); diagnostics only. **`@JsonIgnore`** + excluded from every recruiter/F50-facing DTO (Security review fix — prevents the contactability oracle re-emerging at the dashboard layer). |
| `candidateConfirmedAt` | Instant | Set by the candidate confirm action (FR-007), exactly once via CAS. `null` ⇒ unconfirmed. Excludes the booking from stage 2 (escalation) and stage 3 (no-show). |
| `escalatedAt` | Instant | Stage-2 stamp. Set when the escalation deadline is reached unconfirmed; drives the observable "unconfirmed — escalated" state (SC-005). Guards stage 2 idempotency. |
| `noShowAt` | Instant | Stage-3 stamp (FR-016). Set when `bookedStartAt` is reached with `candidateConfirmedAt==null` — the MVP no-show signal for F50. A confirmed booking never gets it; a confirmed-then-cancelled booking is `CANCELLED` (not a no-show). |

> No `@Version` (unchanged) — every transition is a `findAndModify` CAS. `toString()` is extended to omit `confirmTokenHash` (alongside the existing `locationText`/`tokenHash`/`manageTokenHash`).

**Stage CAS predicates** (each a `findAndModify`, single-winner; D1/D8):

```
Stage 1 (request):   { _id, status:BOOKED, confirmationRequestedAt:null }
                     → set confirmationRequestedAt=now [+ confirmTokenHash if contactable | confirmationNotRequestable=true if not]
Stage 2 (escalate):  { _id, status:BOOKED, confirmationRequestedAt:{$ne:null}, candidateConfirmedAt:null, escalatedAt:null }
                     → set escalatedAt=now ; notify INTERVIEW_UNCONFIRMED
Stage 3 (no-show):   { _id, status:BOOKED, candidateConfirmedAt:null, noShowAt:null }   (selected by bookedStartAt ≤ now)
                     → set noShowAt=now
Confirm (candidate): { confirmTokenHash, status:BOOKED, candidateConfirmedAt:null }
                     → set candidateConfirmedAt=now   (replay: already-set → return existing ack)
```

The per-stage **selection** is an explicit `Pageable`-capped `@Query` over `{status:BOOKED, bookedStartAt ≤ now+globalBound, <stage null field>}` (the F12 unbounded-scan lesson); the per-row **transition** is the CAS above. Per-workspace lead/deadline is Java-filtered on each capped batch (D2/D7).

---

## 2. State interaction (no new `SchedulingStatus` value)

```
F13/F20:  PENDING_SELECTION → BOOKING → BOOKED ─────────────────────────────► (RESCHEDULED | CANCELLED | CLEANUP_INCOMPLETE)
                                          │
   F23 cascade (layered over BOOKED, via the fields above):
        bookedStartAt − leadTime reached ──► stage 1: confirmationRequestedAt (+confirmTokenHash | notRequestable)  → REMINDER_24H
        candidate POSTs confirm           ──► candidateConfirmedAt           (idempotent; past→410, gone→400)
        bookedStartAt − deadline reached, still unconfirmed ──► stage 2: escalatedAt → INTERVIEW_UNCONFIRMED (recruiter)
        recruiter one-tap release         ──► F20 cancelByRecruiter: BOOKED→CANCELLING→CANCELLED (events removed, slot released)
        bookedStartAt reached, unconfirmed──► stage 3: noShowAt (F50 signal)
```

- The cascade **only ever acts on `status:BOOKED`** — so a reschedule (parent → `RESCHEDULED`, fresh child → new `BOOKED` row with null cascade fields ⇒ fresh cascade) and an erasure/cancel (→ `CANCELLED`) **halt the cascade by construction** (D9), no scheduler-side check needed.
- A release is an F20 recruiter cancel; the no-show classification for F50 is derived from `escalatedAt≠null && candidateConfirmedAt==null` at cancel time (an optional explicit `releasedUnconfirmed` marker is a task-time refinement).

---

## 3. `workspaceConfig` (MODIFIED) — per-workspace cascade settings (D7)

| Field | Type | Notes |
|---|---|---|
| `confirmationLeadTime` | Duration (nullable) | How far before start the confirmation request is dispatched. `null` ⇒ the `NoShowProperties` global default (PT24H). |
| `unconfirmedEscalationDeadline` | Duration (nullable) | How far before start an unconfirmed interview escalates. `null` ⇒ global default (PT2H). |

**Validation** (in `WorkspaceConfigService`, targeted `$set` — the F03 lost-update lesson; FR-014): both positive; `0 < unconfirmedEscalationDeadline < confirmationLeadTime`; `confirmationLeadTime ≤ noShow.cascadeQueryBound` (so the D2 indexed scan never misses a workspace). An invalid edit is rejected (`invalid_config` 400) and the prior valid settings stand.

---

## 4. Enums & config (append-only)

**`MergeToken`** (NEW value, append-only): `CONFIRM_LINK` (`{{confirm_link}}`). Permitted for `REMINDER_24H` (+ `REMINDER_1H`) in `MergeTokenCatalogue`. The built-in `REMINDER_24H` body in `BuiltInEmailTemplates` adds the "Confirm attendance: {{confirm_link}}" CTA. (URL-typed token → rendered as an `href==text` anchor restricted to `http(s)`, the F21 anchor discipline.)

**`RecruiterNotificationType`** (append-only, value-free): `INTERVIEW_UNCONFIRMED` — the single coarse escalation alert (covers candidate-non-response AND not-contactable, D5). Logged via `.name()` only (the logstash `kv` footgun).

**`AuthEventType`** (append-only, never reorder): `NOSHOW_CONFIRMATION_REQUESTED`, `NOSHOW_ATTENDANCE_CONFIRMED`, `NOSHOW_UNCONFIRMED_ESCALATED`. The recruiter **release** reuses the existing `SCHEDULING_CANCELLED` (emitted by `cancelByBooking`). All written via `AuthAuditService.record(type, workspaceId, actor("CANDIDATE"|"SYSTEM"|recruiterId), outcomeLiteral, ip)` — value-free, no token, no PII.

**`CandidateAuditOutcome`** (append-only): `ATTENDANCE_CONFIRMED` — emitted on confirm via `CandidateAuditService.append(workspaceId, candidateId, CandidateEventType.BOOKING_CHANGED, ATTENDANCE_CONFIRMED, "CANDIDATE")`. Release reuses the F20 `BOOKING_CANCELLED`. These PII-free append-only entries **survive candidate erasure unmodified** (FR-021).

**`NoShowProperties`** (NEW, `cadence.noshow.*`): `confirmationLeadTime=PT24H`, `escalationDeadline=PT2H` (global defaults), `cascadeIntervalMs=60000` (`@Scheduled` fixed delay), `cascadeQueryBound=PT72H` (the D2 global range upper bound), `cascadeSweepBatchLimit=200` (per-stage `Pageable` cap). No secrets. Auto-registers via `@ConfigurationPropertiesScan`.

---

## 5. Modified / new components (behavioural)

- **`NoShowDefenseScheduler`** (NEW `@Component`, `scheduler/`): `@Scheduled(fixedDelayString="${cadence.noshow.cascade-interval-ms:60000}")` + `@PostConstruct registerReplayAction("no-show-cascade", this::sweep)` + `sweep()` wrapped in `checkpoints.start/complete`. `sweep()` runs the three stage finders (each `Pageable`-capped), Java-filters per-workspace offsets (reading `WorkspaceConfig` per distinct workspace, `null`→`NoShowProperties` default), and drives each row through its stage CAS on a new `NoShowCascadeService` (or `SlotReservationService` methods — see below). **Resolves `now` from the injected `java.time.Clock`** (the F01 `@Primary MutableClock` test bean), NEVER `Instant.now()`/`System` — required for the deterministic cascade-timing and DST tests (QA review fix). Correctness rests on the per-row CAS, not single-threading (the `EmailDispatchScheduler` precedent). Logs counts + `.name()` only.
- **`NoShowCascadeService`** (NEW `@Service`, or methods on `SlotReservationService`): `requestConfirmation(booking, now)` (stage-1 CAS + mint confirm token + enqueue `REMINDER_24H` via `dispatch.enqueue`, or set `confirmationNotRequestable` when `gate.evaluate(...).permit()==false`); `escalateUnconfirmed(booking, now)` (stage-2 CAS + `notifications.notify(..., INTERVIEW_UNCONFIRMED)` + audit); `stampNoShow(booking, now)` (stage-3 CAS); `confirmAttendance(rawConfirmToken, ip)` (the candidate confirm CAS, rate-limited, 410/400 policy + idempotent replay).
- **`SlotReservationService.book`** — one added `.set("bookedStartAt", slot.getStart())` in the existing `BOOKING→BOOKED` CAS (D2). No other change to the F13/F20 saga.
- **`CandidateErasureService`** — extend the BOOKED-booking erasure flip to also `$unset confirmTokenHash` (D9). No other erasure change (the cascade halts via the `status:BOOKED` guard).
- **`SchedulingService`** — `releaseUnconfirmed(workspaceId, candidateId, actor)` (resolves the authoritative BOOKED booking from the root lineage — the F20 `status()` precedent — and calls `cancelByBooking(booking, false, actor)`; refuses a past interview). Reuses the F20 recruiter-cancel primitive (D6).
- **`BuiltInEmailTemplates` / `MergeTokenCatalogue`** — `REMINDER_24H` gains the `{{confirm_link}}` CTA + `CONFIRM_LINK` permission (D4). The F21 `@PostConstruct` completeness check still passes (no new `EmailMessageType`).
- **`SchedulingProperties`** — gains `spaConfirmBasePath` (default `/confirm`) for the confirm link; the global cascade durations live in the new `NoShowProperties`.

---

## 6. Mongock `ChangeUnit014_NoShowIndexes` (order "014")

Native driver `createIndex`; targeted `dropIndex` rollback (never `dropIndexes()`). Off the highest **applied** changeset "013" (NOT the branch number "015").

**`schedulingRequests`** (add):
- `{status: 1, bookedStartAt: 1}` — the cascade stage selection scans (D1/D2), shared by all three stages (stage null-fields are post-filtered in the capped batch).
- unique **PARTIAL** `{confirmTokenHash: 1}` with `partialFilterExpression {confirmTokenHash:{$exists:true}}` (NOT sparse — paired with `@Field(write=NON_NULL)`; the `manageTokenHash` precedent) — the confirm-credential lookup (`findByConfirmTokenHash`).

**Backfill (same changeset, Backend review fix)**: `ChangeUnit014` also **backfills** `bookedStartAt` for every existing `BOOKED` row from its `offeredSlots[chosenSlotId].start` (a one-time idempotent data migration), so bookings made before F23 ships are visible to the cascade. Without this, the `{status:BOOKED, bookedStartAt:…}` finders silently skip all pre-F23 bookings. Targeted `dropIndex` rollback (the backfill is forward-only; never `dropIndexes()`).

> Reuses, no new index: the F13 `{tokenHash}`, `{workspaceId,candidateId,createdAt:-1}`, `{status,expiresAt}`, `{status,updatedAt}`; the F20 unique-partial `{manageTokenHash}`, `{rootRequestId,mode,status}`, `{mode,status,updatedAt}`, partial `{calendarTeardownPending}`; `workspaceConfig` unique `{workspaceId}` (ChangeUnit004 — the two new Duration fields need no index).

---

## 7. Validation rules (from spec FRs)

- **Cascade stage 1** (FR-001/FR-004/FR-005): a `BOOKED` row with `confirmationRequestedAt==null` and `bookedStartAt − wsLeadTime ≤ now` → stamp + (contactable ? mint confirm token + enqueue `REMINDER_24H` : set `confirmationNotRequestable`). A booking made inside the lead window (`bookedStartAt − wsLeadTime` already past) fires at the next sweep (FR-004).
- **Cascade stage 2** (FR-010): `BOOKED`, requested, unconfirmed, not yet escalated, `bookedStartAt − wsDeadline ≤ now`, and `bookedStartAt > now` (don't escalate a past interview — stage 3 handles it) → stamp `escalatedAt` + one `INTERVIEW_UNCONFIRMED`.
- **Cascade stage 3** (FR-016): `BOOKED`, unconfirmed, `noShowAt==null`, `bookedStartAt ≤ now` → stamp `noShowAt`.
- **Confirm** (FR-006/FR-007/FR-008/FR-009): affirmative **POST** (never GET — no scanner/prefetch confirm); resolve booking **solely** from `confirmTokenHash` (no client id override — IDOR). **Precedence (status before time, no oracle — Security review fix)**: not found/cleared/superseded → 400; resolved-but-**not-`BOOKED`** (cancelled/released/rescheduled-away) → 400 (byte-identical); `BOOKED` & `chosenStart(row)` already passed → 410; else CAS set `candidateConfirmedAt` → 200 (idempotent replay returns existing). The past check uses `chosenStart()` (NOT the sweep-only `bookedStartAt`) so it never diverges from `viewBooking`/`cancelByBooking` after a reschedule. Rate-limited → 429.
- **Release** (FR-011/FR-012/FR-013): ADMIN/RECRUITER, workspace-scoped (oracle-free 404); reuse `cancelByBooking` (events removed, slot released, candidate consent-gated notice, audit); refuse a past interview (`IneligibleException`).
- **Config** (FR-014): `0 < escalationDeadline < confirmationLeadTime ≤ cascadeQueryBound`, both positive; reject otherwise, retain prior.
- **Audit** exactly once per confirmation / escalation / release (FR-021/SC-010); no PII/token in logs (FR-022/SC-010); recruiter notification value-free (D5).
- **Erasure** (FR-024/D9): the F20 BOOKED-cancel flip additionally `$unset confirmTokenHash`; the cascade halts via the `status:BOOKED` guard; async teardown unchanged; audit entries survive.
