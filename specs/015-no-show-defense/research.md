# Phase 0 Research: Flow A4 — No-Show Defense (F23)

All decisions verified against the real F13/F20/F22 source (`SlotReservationService`, `SchedulingReaper`, `EmailDispatchScheduler`, `EmailDispatchService`, `SchedulerCheckpointService`, `RecruiterNotificationService`, `MergeTokenCatalogue`, `CandidateBookingController`, `WorkspaceConfig`, `SchedulingRequest`, `SchedulingProperties`). No NEEDS CLARIFICATION remained from the spec; the items below resolve the *how* within the fixed stack. F23 is, like F13/F20, **almost entirely orchestration of existing seams** — no new collection, no new runtime dependency, no broker.

---

## D1 — The confirmation cascade is a NEW `@Scheduled NoShowDefenseScheduler` on its own checkpoint; three per-booking CAS stages

**Decision**: Add one new scheduler component `NoShowDefenseScheduler` (`@Scheduled(fixedDelay)` + `SchedulerCheckpointService` task `"no-show-cascade"` + a `@PostConstruct` replay registration — the exact `EmailDispatchScheduler`/`SchedulingReaper` shape). Each sweep advances three per-booking stages, each a `findAndModify` CAS on the booking row:
1. **Confirmation request** — `{status:BOOKED, confirmationRequestedAt:null, <due at lead time>}` → set `confirmationRequestedAt`, mint `confirmTokenHash`, enqueue the `REMINDER_24H` candidate reminder (D4).
2. **Unconfirmed escalation** — `{status:BOOKED, confirmationRequestedAt≠null, candidateConfirmedAt:null, escalatedAt:null, <due at deadline>}` → set `escalatedAt`, raise one coarse `INTERVIEW_UNCONFIRMED` recruiter notification (D5).
3. **No-show stamp** — `{status:BOOKED, candidateConfirmedAt:null, noShowAt:null, bookedStartAt ≤ now}` → set `noShowAt` (the F50 no-show signal, FR-016).

**Rationale**: The cascade is a **time-driven dispatcher**, a distinct concern from the `SchedulingReaper` (stuck-state recovery) — folding it into the reaper would overload one task with two cadences. The F22 `EmailDispatchScheduler` Javadoc explicitly frames F23 as a consumer ("Consuming features (F23/F31/F32) enqueue a future `scheduledFor` and inherit idempotency + missed-fire recovery"). A new `@Scheduled` component is the sanctioned §IV async mechanism (no broker), mirrors two existing precedents, and keeps correctness on the **per-booking per-stage CAS**, not single-threading (a double-pick on a rolling deploy is a clean no-op — the F22 lesson). Each stage finder is an explicit `@Query`, `Pageable`-capped (the F12 `InvalidMongoDbApiUsageException`/unbounded-scan lesson).

**Alternatives rejected**: (a) Enqueue the reminder into the F22 outbox at BOOKED time with a future `scheduledFor` — **rejected**: a scheduled F22 fire passes `null` context and re-derives merge values at render time (`EmailDispatchScheduler` line 91), but the per-booking confirm link is a secret token that **cannot be re-derived** at render time (raw tokens are never stored — see D3), so the confirm link could never be rendered. The cascade must mint-and-send at fire time. (b) Fold into `SchedulingReaper.sweep` — rejected: different cadence/concern; the reaper's threshold invariant is tuned for stale-`BOOKING` recovery, not lead-time dispatch.

---

## D2 — Denormalize a queryable `bookedStartAt` Instant on the booking; the cascade sweep predicate + index

**Decision**: Add a top-level `bookedStartAt` Instant to `schedulingRequests`, set in the `BOOKING→BOOKED` `findAndModify` CAS in `SlotReservationService.book` (the commit CAS, line ~293) — `.set("bookedStartAt", slot.getStart())`. That CAS is where **both** INITIAL bookings and F20 RESCHEDULE rounds flip `BOOKING→BOOKED`, so it is the only *live* write site (the recruiter re-invite `resendRescheduleInvitation` does not change the booked time → no write). The cascade finders scan `{status:BOOKED, … , bookedStartAt:{$lte: now.plus(globalBound)}}` over the new index `{status:1, bookedStartAt:1}`.

**Backfill (Backend review fix)**: any `BOOKED` row created **before** F23 ships has `bookedStartAt == null` and would be invisible to the cascade (the stage finders key on `bookedStartAt`). `ChangeUnit014` therefore **backfills** `bookedStartAt` for every existing `BOOKED` row from its `offeredSlots[chosenSlotId].start` (a one-time data migration in the same changeset that adds the index — read the chosen slot, set the field; idempotent). This is not "one line"; it is a one-field write at the live CAS **plus** a backfill migration.

> `bookedStartAt` is **strictly the index/sweep key** — it is NOT the source of truth for any candidate-facing "is this interview in the past" decision. The candidate confirm 410-vs-400 check (D3) and the F20 `viewBooking`/`cancelByBooking` eligibility checks use the established in-memory `chosenStart(req)` (reconstructed from `offeredSlots[chosenSlotId]`), so the two never diverge after a reschedule. Keeping one start source for the candidate path avoids a stale-denormalization oracle (Backend review fix).

**Rationale**: The interview start instant currently lives **inside** `offeredSlots[].start`, addressed by `chosenSlotId`, and is only reconstructed in Java by the private `chosenStart(req)` (line 756) — it is **not a queryable top-level field and has no index**. A `@Scheduled` predicate "BOOKED bookings whose start is within the lead-time/deadline/now window" therefore cannot be expressed as a covered Mongo query without denormalization (the F00.1 "no scheduled task queries an uncovered collection" rule). The commit CAS is the correct single write site: **both** INITIAL bookings and F20 RESCHEDULE rounds flip `BOOKING→BOOKED` through it, so a rescheduled interview's new start is denormalized for free, and the parent (now `RESCHEDULED`, not `BOOKED`) is naturally excluded from the cascade. The recruiter re-invite (`resendRescheduleInvitation`) does not change the booked time, so it needs no `bookedStartAt` write.

**Per-workspace lead time vs a single Mongo range (D7 interaction)**: the lead time and escalation deadline are per-workspace (FR-014), so the exact "due" boundary cannot be a single Mongo arithmetic. The finders use a **global upper bound** (`cascadeQueryBound`, default 72 h ≥ any allowed per-workspace lead time) for the indexed range scan, then **Java-filter** each capped batch against the row's workspace setting (`bookedStartAt.minus(wsLeadTime) ≤ now`). The config validation (D7) enforces `wsLeadTime ≤ cascadeQueryBound`, so a workspace can never set a lead time the scan would miss.

**Alternatives rejected**: drive the sweep off `managedCalendarEvents.startAt` (a real top-level field) — rejected: that collection is keyed per-participant per-provider, carries no booking-confirmation state, and its only start index is `{workspaceId,memberId,startAt}` (ChangeUnit008), not a global cascade scan. Reconstruct start in Java per row — rejected: not queryable, unbounded scan.

---

## D3 — A distinct, fire-time-minted `confirmTokenHash` (separate from `tokenHash`/`manageTokenHash`)

**Decision**: The confirm credential is a **third** hashed-token field on the booking, `confirmTokenHash = TokenHasher.hashToken(SecureTokens.newToken())` (256-bit, HMAC at rest, unique **partial** index), minted **at confirmation-request dispatch time** (stage 1) — never at BOOKED time. The reminder carries `…/confirm?token=<rawConfirmToken>`. The candidate confirms via `POST /api/candidate/booking/{confirmToken}/confirm` (affirmative POST, never a GET — no prefetch/scanner auto-confirm, FR-006). Confirm = CAS `{confirmTokenHash, status:BOOKED, candidateConfirmedAt:null} → set candidateConfirmedAt`; a replay (already confirmed) returns the existing acknowledgement (FR-007); a past interview → **410**, a cancelled/rescheduled-away/unknown/cleared hash → indistinguishable **400** (the F20 `viewBooking` precedent). Link **resolution (read) is idempotent and not consumed**; only the positive confirm records (FR-017). The hash is retained for the booking lifetime (so replays resolve) and `$unset` on erasure (D9).

**Response precedence (Security review fix — pin the ordering, no oracle)**: the confirm path evaluates **status before time**: resolve by `confirmTokenHash` → not found / cleared / superseded → **400** `invalid`; resolved row **not `BOOKED`** (cancelled, recruiter-released, rescheduled-away) → **400** `invalid` (byte-identical to not-found); resolved row `BOOKED` but `chosenStart(row)` already passed → **410** `expired`; otherwise CAS-confirm → **200**. Evaluating status-not-BOOKED → 400 **before** the past-start → 410 check means a rescheduled-away (now-`RESCHEDULED`) parent whose old start is in the past yields the indistinguishable **400**, never a distinguishable 410 (no existence/identity oracle). An integration test MUST assert byte-identical 400 across {unknown token, released-`CANCELLED`, erased (token `$unset`), `SUPERSEDED`}. The "expired" past check uses `chosenStart()` (the F20 `viewBooking`/`cancelByBooking` source), NOT the sweep-only `bookedStartAt` (D2), so the two never diverge after a reschedule.

**CSRF posture (Security review fix)**: the confirm `POST` rides the existing `@Order(2)` permitAll/**STATELESS** chain — it carries **no session cookie**; the confirm token (a path secret, not a cookie) is the sole authenticator, so a forged cross-site POST without the secret cannot confirm and no CSRF token is required (the established candidate-endpoint posture). The recruiter release `POST /api/internal/...` rides the session-authenticated `@Order(3)` chain and inherits that chain's normal internal-API CSRF posture.

**Rationale — why a distinct token is forced, not just preferred**: raw tokens are never persisted (only their HMAC). The cascade fires the reminder **later** than the booking, so at fire time it holds only the stored *hashes* of `tokenHash` (consumed slot-pick) and `manageTokenHash` (the F20 reschedule/cancel link) — it cannot recover either raw token to place a working link in the reminder. It must therefore mint a **fresh** raw token at fire time. Rotating the *manage* token would break the reschedule link already delivered in the booking confirmation, so the fresh token must be a **separate** confirm credential. This independently confirms the spec's "distinct confirm credential" assumption. `confirmTokenHash` is `@Field(write=NON_NULL)` + unique **partial** `{$exists:true}` (NOT sparse — the F01 present-as-null collision footgun; the `manageTokenHash` precedent) and cleared via `$unset` (never `$set(null)`).

**Alternatives rejected**: reuse `manageTokenHash` for confirm — rejected: confirming would either rotate-and-break the reschedule link or muddle two verbs on one credential; and the fire-time-mint problem applies regardless. A confirm action on the existing F20 booking-manage page keyed by the manage token — rejected for the same fire-time-mint reason (the reminder still needs a fresh deliverable link) and because FR-019 calls for a dedicated confirm surface.

---

## D4 — The reminder reuses `REMINDER_24H` + a new `MergeToken.CONFIRM_LINK`; dispatched via the F22 consent-gated channel

**Decision**: Stage 1 enqueues the existing `EmailMessageType.REMINDER_24H` through `EmailDispatchService.enqueue(workspaceId, candidateId, REMINDER_24H, "BASE", now, ctx, null)` with an **immediate** `scheduledFor=now` and a context map carrying `{{confirm_link}}` (= `…/confirm?token=<raw>`) plus the existing interview tokens (`interview_date/time`, `time_zone`, `location`, `stage_name`). Add **`MergeToken.CONFIRM_LINK`** (append-only enum value) and permit it on `REMINDER_24H` (and `REMINDER_1H` for symmetry) in `MergeTokenCatalogue`. **The built-in `REMINDER_24H` body in `BuiltInEmailTemplates` currently contains `{{reschedule_link}}` (verified) — which the cascade context does NOT supply, so it would render as the F21 `[[missing:reschedule_link]]` marker. The built-in body MUST be edited to DROP `{{reschedule_link}}` and ADD the "Confirm attendance: {{confirm_link}}" CTA** (Backend review fix); the `MergeTokenCatalogue` permission and the `BuiltInTemplateCompletenessTest` move together with the body. The candidate dispatch is **consent-gated** — the F22 `ContactPermissionGate` is re-evaluated **after** the outbox claim at actual send time (never cached), which is also the erasure backstop (D9) — and idempotent via the F22 outbox unique `{workspaceId, idempotencyKey}`.

**Rationale**: `REMINDER_24H` already exists as a template type (so **no new `EmailMessageType`** → no F21 `@PostConstruct` completeness break) but is dispatched by nothing yet — F23 is its first sender (the spec's "reusing the existing reminder template"). Its current permitted token set (`MergeTokenCatalogue` line 41) is the generic `interview` set with `RESCHEDULE_LINK` but **no confirm token**, so a `CONFIRM_LINK` token is the minimal addition. Reusing the F22 enqueue path gives consent-gating, the outbox idempotency key, retry, and the bounce/undeliverable interaction for free. Only `REMINDER_24H` is wired; `REMINDER_1H` (a second pre-interview reminder) is **out of F23 scope** (the backlog AC is a single confirmation request → escalation) and remains unused.

**Alternatives rejected**: repurpose `RESCHEDULE_LINK` to carry the confirm URL — rejected: semantically wrong (different endpoint/verb) and would confuse the F20 link. A brand-new `EmailMessageType.CONFIRMATION_REQUEST` — rejected: would trip the F21 `BuiltInEmailTemplates` `@PostConstruct` completeness check and duplicate `REMINDER_24H`'s purpose.

---

## D5 — Escalation is a single coarse `INTERVIEW_UNCONFIRMED` recruiter notification (no GDPR oracle, C3)

**Decision**: Stage 2 raises exactly one `RecruiterNotificationType.INTERVIEW_UNCONFIRMED` (append-only, value-free) via the existing `RecruiterNotificationService.notify(workspaceId, candidateId, type)` and stamps `escalatedAt`. The **same** coarse alert is raised whether the candidate simply did not respond OR could not be emailed at all (not contactable) — the recruiter signal never discloses *why* (FR-005/FR-010). To make a not-contactable booking still escalate, stage 1 sets `confirmationRequestedAt` **even when the contactability gate denies** (recording the attempt), sets an internal `confirmationNotRequestable=true`, mints **no** `confirmTokenHash`, and sends **no** email — so stage 2's predicate (`confirmationRequestedAt≠null`) still fires the escalation.

**Rationale**: A distinct "could not request confirmation" recruiter signal would let the recruiter infer the candidate's consent/erasure/undeliverable state — a GDPR-relevant disclosure (the Security review C3 finding). One coarse "this interview is unconfirmed — release it" alert preserves the recruiter's needed action without the oracle (the F13 byte-identical-deny precedent). `confirmationNotRequestable` is an internal value-free boolean for analytics/diagnostics, never surfaced as a differential recruiter signal. The notification is value-free (ids + `.name()` only — the logstash `kv` footgun avoided).

**Alternatives rejected**: two notification kinds (`INTERVIEW_UNCONFIRMED` + `CONFIRMATION_NOT_REQUESTABLE`) — rejected (C3 oracle). Skip escalation for a not-contactable candidate — rejected: that is the silent gap FR-005 forbids.

---

## D6 — One-tap release reuses the F20 `cancelByRecruiter` / `cancelByBooking` primitive

**Decision**: The recruiter "release slot" action reuses the existing F20 recruiter-initiated cancellation primitive (`SlotReservationService.cancelByBooking(booking, candidateInitiated=false, actor=recruiterId)`, driven by `SchedulingService.cancelByRecruiter`): CAS `BOOKED→CANCELLING` (single-winner vs a concurrent confirm/cancel/reschedule, FR-013) → `CalendarEventService.cancelBooking` removes every participant's event → release `ACTIVE` claims → `CANCELLED` (or `CLEANUP_INCOMPLETE` + recruiter alert) → candidate consent-gated cancellation notice → audit `SCHEDULING_CANCELLED` + candidate-audit `BOOKING_CANCELLED`. A new recruiter endpoint `POST /api/internal/scheduling/{candidateId}/release` (ADMIN/RECRUITER, workspace-scoped) resolves the candidate's authoritative booking and invokes it. The released slot's claim flips `ACTIVE→RELEASED`, leaving the partial unique index → immediately re-selectable (SC-004, the F13/F20 precedent). The no-show classification for F50 is **derived** from the booking's confirmation fields (`escalatedAt≠null && candidateConfirmedAt==null` at cancel ⇒ a no-show release) — recorded via the existing `noShowAt`/`escalatedAt` fields; an explicit `releasedUnconfirmed` marker is an optional task-time refinement, not a new mechanism.

**Rationale**: Releasing an unconfirmed slot **is** a recruiter cancellation; reusing the F20 primitive avoids a parallel teardown path (§I YAGNI) and inherits its single-winner CAS, cleanup-incomplete honest bound, and candidate notice (consistent with F20 FR-013 — the spec's release-notifies-candidate decision). The candidate notice on release is the existing consent-gated `CANCELLATION`; F23 adds no separate no-notify path.

**Honest bound**: `cancelByBooking` refuses a booking whose start has already passed (`IneligibleException`). The escalation deadline (default 2 h before start) means the recruiter normally acts before start; a release attempt after start is refused (a past interview is a no-show record, not a releasable slot) — noted in the contract.

**Alternatives rejected**: a dedicated `releaseUnconfirmed` saga — rejected: it would re-implement `cancelByBooking`'s exact CAS/teardown/notify; the only F23-specific need is the no-show *classification*, which is derived from existing fields.

---

## D7 — Per-workspace cascade settings extend `WorkspaceConfig`; global defaults in `NoShowProperties`

**Decision**: Add two nullable `Duration` fields to `WorkspaceConfig` (F03): `confirmationLeadTime` and `unconfirmedEscalationDeadline`. A new `NoShowProperties` (`cadence.noshow.*`) holds the **global defaults** (`confirmationLeadTime=PT24H`, `escalationDeadline=PT2H`, `cascadeIntervalMs`, `cascadeQueryBound=PT72H`, `cascadeSweepBatchLimit=200`). The cascade resolves each booking's effective offsets as `wsValue ?? globalDefault`. Admin edits ride the **existing** F03 workspace-config update surface (`WorkspaceConfigService.updateSettings`), validating: both durations positive; `0 < escalationDeadline < confirmationLeadTime` (request → escalation → start ordering, FR-014); and `confirmationLeadTime ≤ cascadeQueryBound` (so the D2 indexed scan never misses a workspace).

**Implementation precision (Backend review fix)**: `WorkspaceConfigService.updateSettings` (verified) is a **per-field hard-coded** patch surface with single-field validators — there is no generic Duration path. So "ride the existing F03 update" means real work: add the two `Duration` fields to `WorkspaceConfig`, two patch branches + the `SettingsPatch`/response DTO fields, and — new shape — a **cross-field** validator that runs **after** both effective values are resolved (`wsValue ?? globalDefault`), so a single-field edit cannot pass an individually-valid value that violates the pair. Targeted `$set` (not whole-doc `save` — the F03 lost-update lesson). An invalid edit is rejected (`invalid_config` 400), retaining the prior valid settings.

**Rationale**: FR-014 mandates per-workspace configuration; `WorkspaceConfig` is the established single per-workspace settings doc (already holds working hours, time zone, SLA window, retention). Nullable Durations with a global-default fallback mean an un-customized workspace works on the documented defaults (FR-015) with zero migration. Targeted `$set` (not whole-doc `save`) avoids the F03 lost-update; the validity constraint is the FR-014 contradiction guard.

**Alternatives rejected**: store the settings only in `SchedulingProperties` (global, not per-workspace) — rejected: violates FR-014's per-workspace requirement. A new `noShowSettings` collection — rejected: §I/C2, `WorkspaceConfig` is the right home (one doc/workspace already).

---

## D8 — Idempotency, missed-fire, and the honest delivery bound

**Decision**: Every stage is a per-booking-per-stage `findAndModify` CAS, so a duplicate sweep, an overlapping run, or a missed-fire replay is a clean no-op (the stage's null-field predicate no longer matches once stamped). Stage 1 **CAS-claims first** (set `confirmationRequestedAt` + mint `confirmTokenHash`, `returnNew`), and **only if the claim matched** enqueues the reminder (best-effort; the F22 outbox key dedupes any double-enqueue). The cascade reads `now` from the **injected `Clock`** (never `Instant.now()`/`System` — the F01 test-clock rule; required for the deterministic DST/timing tests) and is wrapped in `SchedulerCheckpointService.start/complete` + a `@PostConstruct` replay registration (the F00.2 contract).

**Idempotency key (Backend review fix)**: the real F22 key is `dispatchKey(workspaceId, candidateId, type, scheduledFor.toEpochMilli())` — it does **not** include a booking/round id. F23 sends **one** `REMINDER_24H` per booking, and a reschedule produces a **new booking row** whose stage 1 fires at a **distinct `scheduledFor`** (the fire instant `now`), so two rounds never collide on the key (the F13 distinct-`scheduledFor`-per-round trick, `SlotReservationService` line ~378). No change to `IdempotencyKeys.dispatchKey` is needed; the earlier "incorporate `bookedStartAt`" wording was inaccurate and is corrected to "distinct `scheduledFor` per round."

**Honest bound (stated, not hidden)**: a crash **between** the stage-1 CAS-claim and the reminder enqueue loses that one reminder (the replay finds `confirmationRequestedAt` already set and skips). This is the same best-effort posture as the existing F13/F20 confirmation emails — but here it is **non-fatal**: the unconfirmed booking still escalates to the recruiter at the deadline (stage 2 keys only on `confirmationRequestedAt≠null`), so a lost reminder surfaces as a recruiter alert, never a silent no-show. **Duplicates are impossible** (per-stage CAS single-winner + F22 outbox unique key); SC-006's "no duplicate on mid-task restart" holds; the residual is a rare lost *single* send caught by escalation.

**Rationale**: Putting the durable "did we send" record before the token mint is impossible (the raw token can't be recovered on replay — D3), so CAS-claim-then-enqueue is the correct ordering, and the escalation safety-net makes the residual gap harmless. Stamp-after-success everywhere else keeps stages 2/3 fully replay-safe.

**Alternatives rejected**: a two-phase "intent then send" with a stored raw token — rejected: storing raw tokens violates the candidate-link policy. Treat the cascade as exactly-once-delivery — rejected: dishonest; the bound above is the truthful guarantee.

---

## D9 — Erasure halts future cascade stages; the F22 consent re-gate is the authoritative erased-subject backstop

**Decision**: F23 extends the F20 erasure path (`CandidateErasureService` → the **synchronous** `supersedeLiveScheduling`, run inside `wipe()`) with one addition: `$unset confirmTokenHash` alongside the existing `$unset manageTokenHash` on the BOOKED→CANCELLED write (the BOOKED branch only — a PENDING/BOOKING row never holds a confirm token). Two layered guarantees, stated honestly:

1. **Future stages are halted by the `status:BOOKED` guard.** Once erasure flips the booking to CANCELLED, no cascade stage CAS can match (every predicate requires `status:BOOKED`). The async calendar teardown (F20 D9, `calendarTeardownPending` + the reaper pass) is unchanged. Append-only audit entries survive erasure (FR-021).
2. **The narrow find→update gap is closed by the F22 consent re-gate, NOT by atomicity.** The real `supersedeLiveScheduling` is `find({status:BOOKED})` → collect ids → `updateMulti({_id ∈ ids}, …)` (verified, `CandidateErasureService` line ~108) — the update filter is `_id ∈ ids`, **not** a per-row `status:BOOKED` CAS. So it is **not** a single-winner contest: a stage-1 sweep could fire in the gap between the erasure `find` and its `updateMulti`, enqueuing a reminder for a now-erased subject. **The authoritative backstop is that `EmailDispatchService` re-evaluates `ContactPermissionGate` AFTER winning the outbox claim, at actual send time** (the F22 "consent gate re-evaluated on EVERY dispatch, never cached" guarantee) — so any reminder enqueued in that gap is **suppressed at dispatch** once the candidate's state is ERASED. This is the FR-005/FR-024 control. (The earlier "atomic, no race window" framing was an overclaim — corrected per the Security review.)

**Rationale**: F20 already cancels a BOOKED booking and removes its events on erasure (the residual-PII obligation); F23's new duty is killing the confirm link (`$unset`) and ensuring no reminder reaches an erased subject. The honest guarantee is the send-time consent re-gate, which F23 leans on rather than claiming an atomicity the erasure code does not have. `wipe()` stays O(1)/non-blocking (the F04 202-within-2s SLA, §IV).

**Test (Security review fix, SC-009)**: an integration test MUST erase the candidate **between** a stage-1 CAS and the outbox dispatch and assert **zero email leaves the transport** (the send-time re-gate fired), plus that the confirm token is unusable post-erasure.

**Alternatives rejected**: tighten the erasure `updateMulti` to a per-row `status:BOOKED` CAS — viable but unnecessary given the send-time re-gate already closes the gap; noted as an optional hardening in tasks. A separate erasure→cascade signal — rejected: §IV no broker; the shared status field + the send-time gate are the controls.

---

## D10 — §II / §IX demonstrable leg and blocking-gate ownership

**Decision**: F23 ships a real candidate **confirm-attendance** page (`features/booking/confirm-attendance.component`, a public route `/confirm?token=` — the F13/F20 guard-free candidate-route precedent), reached from the `REMINDER_24H` email's `{{confirm_link}}`. Because **no polish feature follows F23**, it owns the **blocking** axe-core (0 WCAG 2.2 AA violations) + Lighthouse ≥ 85 gates on this new candidate route (the F20 precedent): extend `frontend/src/testing/axe.ts`-driven per-state specs to the confirm component; add `…/confirm?token=lighthouse-demo` (+ already-confirmed and expired states) to `lighthouserc.json` `ci.collect.url[]`; extend `frontend/lighthouse/serve-with-stub.mjs` with a canned `GET`/`POST /api/candidate/booking/<demo>/confirm` handler (else Lighthouse renders the SPA-fallback `invalid` state and the gate measures nothing — the exact F14 vacuous-measurement bug). All strings `$localize`; interview time in the candidate's local zone with DST-correct labels; no PII/token in URL or logs. The recruiter unconfirmed-indicator + "Release slot" action are surfaced on the existing per-candidate recruiter booking-status view (internal screens — Lighthouse/WCAG N/A, the F50/F51 precedent).

**E2E without a Chromium download (C7 / Principle X)**: the backlog-required no-show E2E (scheduled fire → confirmation email → unconfirmed → recruiter alert → slot released → slot available in MongoDB) runs as a Testcontainers integration test with a **test clock** advancing through the cascade boundaries against the F10/F11 provider stubs, plus a Jasmine/Karma EdgeHeadless candidate-page spec — **NOT Playwright** (`playwright install` downloads Chromium, violating the NON-NEGOTIABLE C7).

**Rationale**: §IX applies to all candidate surfaces; F13 only deferred its gate because F14 owned it, and F20 self-certified for the same "no successor" reason — F23 inherits that posture for its one new candidate page.

---

## Decisions summary

| # | Decision | Key reuse |
|---|---|---|
| D1 | New `@Scheduled NoShowDefenseScheduler`, 3 per-booking CAS stages | `SchedulerCheckpointService`, `EmailDispatchScheduler` shape |
| D2 | Denormalize queryable `bookedStartAt` set in the BOOKED CAS | `SlotReservationService.book` |
| D3 | Distinct fire-time-minted `confirmTokenHash`; POST confirm | `SecureTokens`/`TokenHasher`, F20 `viewBooking` policy |
| D4 | Reminder = `REMINDER_24H` + new `MergeToken.CONFIRM_LINK` | `EmailDispatchService.enqueue`, `MergeTokenCatalogue` |
| D5 | One coarse `INTERVIEW_UNCONFIRMED` alert (no GDPR oracle) | `RecruiterNotificationService.notify` |
| D6 | One-tap release reuses F20 `cancelByRecruiter`/`cancelByBooking` | `SlotReservationService.cancelByBooking` |
| D7 | Per-workspace settings on `WorkspaceConfig` + `NoShowProperties` defaults | F03 `WorkspaceConfigService` |
| D8 | CAS-claim-then-enqueue; escalation safety-net for a lost reminder | `SchedulerCheckpointService`, F22 outbox key |
| D9 | Erasure halts cascade via the `status:BOOKED` guard + `$unset confirmTokenHash` | `CandidateErasureService` |
| D10 | F23 owns the blocking §IX gate on its confirm page; no Playwright | F14/F20 `axe.ts` / LHCI harness |
