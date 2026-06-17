# Implementation Plan: Flow A4 — No-Show Defense (F23)

**Branch**: `015-no-show-defense` | **Date**: 2026-06-16 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/015-no-show-defense/spec.md`

## Summary

F23 defends against no-shows with a confirmation cascade on top of the F13/F20 single-stage booking: a configurable lead time before the interview (default 24 h) Cadence emails the candidate a "Confirm attendance" link; if the candidate doesn't confirm by a configurable deadline (default 2 h before start), it raises one coarse recruiter alert with a one-tap "release slot" recovery action — turning a likely no-show into a recovered slot. It is the constitution §11 / Principle I "Flow A4" MVP flow, and — like F13/F20 — **almost entirely orchestration of existing seams**: no new collection, no new runtime dependency, no broker. The cascade is **email-only** (no SMS/WhatsApp — deferred to v1.5); waitlist auto-invite is out of scope (one-tap release returns the slot to general availability); the no-show analytics *rendering* is F50 (F23 records the signal).

Three load-bearing design decisions, all forced by the real code:

- **A new `@Scheduled NoShowDefenseScheduler`** (own `SchedulerCheckpoint` task, the `EmailDispatchScheduler` shape) drives three per-booking CAS stages — confirmation-request, unconfirmed-escalation, no-show-stamp. It cannot reuse F22 future-dating because the per-booking confirm link is a secret token that can't be re-derived at the scheduled render time (raw tokens are never stored).
- **A denormalized top-level `bookedStartAt`** (set in the existing `BOOKING→BOOKED` CAS in `SlotReservationService.book`, covering initial + reschedule rounds, plus a one-time `ChangeUnit014` backfill for pre-F23 BOOKED rows) — the interview start currently lives inside `offeredSlots[].start` (only reconstructed in Java by `chosenStart`), so the cascade's time-window sweep has nothing queryable/indexable without it. It is strictly the sweep/index key; candidate-facing "is it past" checks keep using `chosenStart()`.
- **A distinct fire-time-minted `confirmTokenHash`** (a third hashed-token field, separate from the F13 slot-pick `tokenHash` and the F20 reschedule/cancel `manageTokenHash`) — the cascade fires the reminder *later* than the booking and holds only stored hashes, so it must mint a fresh raw token to deliver a working confirm link; rotating the manage token would break the already-delivered reschedule link.

The cascade halts on cancel/reschedule/erasure **by construction** (every stage CAS requires `status:BOOKED`); erasure adds one `$unset confirmTokenHash` to the F20 wipe flip. The recruiter one-tap release **reuses the F20 `cancelByRecruiter`/`cancelByBooking` primitive** (events removed, slot released, candidate consent-gated notice, single-winner CAS). The reminder reuses the existing `REMINDER_24H` template (F23 is its first sender) plus a new `MergeToken.CONFIRM_LINK`; per-workspace settings extend `WorkspaceConfig` with two `Duration` fields validated `0 < escalation < lead ≤ queryBound`.

The §II/§IX demonstrable leg: a candidate opens the confirm link from the reminder email → an Angular confirm page (no login, time-zone-correct, WCAG 2.2 AA) → taps "Confirm attendance"; and the recruiter, on an unconfirmed interview, taps "Release slot" → old calendar events vanish, slot freed — browser to database against the F10/F11 provider stubs.

## Technical Context

**Language/Version**: Java 21 (backend); TypeScript 5.4 / Angular 17.3 (frontend)
**Primary Dependencies**: Spring Boot 3.3.5 (web, data-mongodb, security w/ method security, actuator, aop, **scheduling**) — **no new runtime dependency**. Reuses F13/F20 `SlotReservationService`/`SchedulingService`/`SchedulingRequest`/`SchedulingProperties`/`CandidateBookingController`/`CandidateRateLimiter`, F22 `EmailDispatchService.enqueue`/`ContactPermissionGate`/`RecruiterNotificationService`/`EmailDispatchScheduler` pattern, F21 `REMINDER_24H` template + `MergeTokenCatalogue`/`BuiltInEmailTemplates`, F10/F11 `CalendarEventService.cancelBooking`, F04 `CandidateErasureService`/`CandidateAuditService`, F03 `WorkspaceConfig`/`WorkspaceConfigService`, F01 `SecureTokens`/`TokenHasher`, F00.2 `SchedulerCheckpointService`. Frontend: Angular standalone + Angular CDK a11y + `axe-core`/`@lhci/cli` (already F14 devDependencies). Mongock 5.4.4; logstash-logback-encoder 9.0.
**Storage**: MongoDB 7.x (Atlas prod, Testcontainers `mongo:7` tests). **No new collection.** Extends `schedulingRequests` (denormalized `bookedStartAt` + confirmation-attendance fields `confirmationRequestedAt`/`confirmTokenHash`/`confirmationNotRequestable`/`candidateConfirmedAt`/`escalatedAt`/`noShowAt` — all ids/instants/enums/booleans, the confirm credential stored only as an HMAC hash) and `workspaceConfig` (two nullable `Duration` cascade settings). Reuses `interviewSlotClaims`, `managedCalendarEvents`, `emailDispatches` (F22 outbox — the reminder), `candidates`, `schedulerCheckpoints`, `authAuditLog`, `auditLog`, `recruiterNotifications`, `members`, `sessions`. **One new Mongock changeset** `ChangeUnit014` (order **"014"** off the highest applied **"013"**) adds `{status,bookedStartAt}` + a unique partial `{confirmTokenHash}` index.
**Testing**: JUnit 5 + Mockito (unit: cascade stage CAS predicates, per-workspace offset resolution, confirm idempotency/410/400, no-show classification, config validation); Testcontainers MongoDB (integration: **mocked/test-clock** cascade timing — request at lead time, escalate at deadline, no-show at start; **gated/latched `@RepeatedTest`** concurrent release-vs-confirm/cancel single-winner — the F13/F20 precedent; missed-fire/mid-task-restart no-duplicate + lost-reminder-still-escalates; contactability-skip-still-escalates; erasure halts cascade + `$unset confirmTokenHash`; partial-unique `confirmTokenHash` no-null-collision; audit/PII scan with `SENTINELF23*`); MockMvc (contract: candidate confirm endpoint 200/410/400/429 + no-GET-confirm; recruiter release endpoint 5-role matrix + workspace-scoped 404; config 400 on contradiction); Jasmine + axe-core (candidate confirm page: 0 WCAG violations across states, no-login, time-zone, no token in storage/console). **E2E (backlog-required: scheduled fire → confirmation email → unconfirmed → recruiter alert → slot released → available in MongoDB) runs as a Testcontainers test-clock integration test against the F10/F11 stubs — NOT Playwright (no Chromium download, C7/Principle X)**. `spring-security-test` (present).
**Target Platform**: Fly.io single Machine (backend), Cloudflare Pages (frontend) — topology unchanged.
**Project Type**: Web application (Spring Boot backend + Angular SPA) — both change.
**Performance Goals**: Candidate confirm (open → confirm) under 1 min, no login (SC-002); confirm page < 2 s on 4G / Lighthouse ≥ 85 (FR-019); release removes events on all calendars within the provider-harness window (SC-004). Cascade sweep is `Pageable`-capped per stage (no unbounded scan).
**Constraints**: Exactly-once-no-duplicate cascade dispatch + escalation under overlapping sweeps / mid-task restart (FR-002/SC-006) via per-booking-per-stage `findAndModify` CAS + the F22 outbox unique key — correctness rests on the CAS, not single-threading. **Honest delivery bound (D8)**: a crash between the stage-1 CAS-claim and the reminder enqueue loses at most one reminder, caught by the stage-2 escalation (no silent no-show; duplicates impossible). Single-winner release under simultaneous conflicting actions (FR-013/SC-007). Confirm credential: 410 past / 400 used-invalid-unknown (no oracle), 429 rate-limited; the not-contactable escalation is the **same coarse** `INTERVIEW_UNCONFIRMED` alert (no GDPR/contactability oracle — C3). No PII / no token value in logs/audit/persisted docs (FR-022/SC-010). DST-correct lead/deadline math via absolute `Instant` offsets (SC-013). Candidate confirm page: WCAG 2.2 AA axe **blocking** (F23 owns its candidate surface — no successor polish feature), no login, all strings `$localize` (FR-019 / §IX). Email-only — no SMS/WhatsApp path in the codebase (FR-023/SC-012).
**Scale/Scope**: Zero new collections; one Mongock changeset (`ChangeUnit014`); one new `@Scheduled` component (`NoShowDefenseScheduler`) + one new service (`NoShowCascadeService`, or methods on `SlotReservationService`); extends `SlotReservationService.book` (one `bookedStartAt` write), `CandidateErasureService` (`$unset confirmTokenHash`), `SchedulingController` (a `/release` route reusing the existing `cancelByRecruiter`), `CandidateBookingController` (confirm endpoint), `WorkspaceConfig`/`WorkspaceConfigService` (two settings + validation), `BuiltInEmailTemplates`/`MergeTokenCatalogue` (`CONFIRM_LINK`); new config `NoShowProperties`; enums (`MergeToken.CONFIRM_LINK`, `RecruiterNotificationType.INTERVIEW_UNCONFIRMED`, three `AuthEventType.NOSHOW_*`, `CandidateAuditOutcome.ATTENDANCE_CONFIRMED`); one new Angular candidate confirm page + a recruiter release action on the existing per-candidate view.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Gate | Question | Result |
|---|---|---|
| **C1** | Within MVP scope (spec §11)? | ✅ Flow A4 no-show defense (confirmation cascade, recruiter alert, slot release) is named explicitly in constitution Principle I and §11 MVP. Backlog F23, Tier 2 P2. SMS/WhatsApp + waitlist auto-invite correctly deferred (FR-023). |
| **C2** | New service, queue, or replica? | ✅ No. No new collection; the cascade is a new `@Scheduled` component on the **existing** `SchedulerCheckpointService` (the sanctioned §IV async mechanism — the `EmailDispatchScheduler`/`SchedulingReaper` precedent), driving per-booking `findAndModify` CAS. No broker, no queue, no cache tier. |
| **C3** | Exposes candidate PII to unauthorized roles? | ✅ No. The candidate confirm endpoint is public-by-token, times-only, and resolves the booking **solely** from the credential (FR-008, no IDOR). The recruiter release/escalation are `@PreAuthorize` ADMIN/RECRUITER + workspace-scoped (oracle-free 404). The escalation alert is a **single coarse** value-free `INTERVIEW_UNCONFIRMED` — it never discloses *why* a confirmation could not be requested (no consent/erasure oracle to the recruiter). New persisted fields are ids/instants/enums/booleans; the confirm credential is an HMAC hash only. |
| **C4** | Dependency outside the fixed stack? | ✅ No new dependency. `axe-core`/`@lhci/cli` already F14 devDependencies; everything else reuses existing seams. |
| **C5** | New/modified Windows scripts with non-ASCII? | ✅ No new/modified `.ps1`/`.cmd`/`.bat`. CI PII-scan extended (ASCII only). |
| **C6** | Multi-role sub-agent review (≥3) scheduled? | ✅ Spec reviewed (4 roles); this plan is reviewed in this command (below); implementation review at task close. |
| **C7** | Downloads a build tool/runtime/CLI? | ✅ No. Reuses cached `gradle-9.4.0` + installed JDK; `npm ci` installs the already-declared F14 devDeps. **The E2E uses the existing Karma/EdgeHeadless + Testcontainers harness — `playwright install` (Chromium download) is explicitly NOT run** (the F14/F20 decision, carried forward). |

**Initial gate: PASS.** No Complexity Tracking entries required.

**Post-Phase-1 re-check: PASS** — design adds zero collections, one changeset, zero dependencies, reuses every F13/F20/F22/F04/F03/F00.2 seam, and the only schema changes are additive fields + two indexes on existing collections. §IX obligation (F23 owns the blocking axe/Lighthouse gate on its new candidate confirm page) recorded in the DoD note below. See Phase 1 artifacts (research.md, data-model.md, contracts/, quickstart.md).

## Project Structure

### Documentation (this feature)

```text
specs/015-no-show-defense/
├── plan.md              # This file
├── research.md          # Phase 0 — D1 new cascade scheduler, D2 bookedStartAt, D3 confirm token, D4 REMINDER_24H, D5 coarse alert, D6 release reuse, D7 workspace settings, D8 idempotency honest-bound, D9 erasure, D10 §IX gate
├── data-model.md        # Phase 1 — schedulingRequests cascade fields + stage CAS, workspaceConfig settings, enums, ChangeUnit014, validation
├── quickstart.md        # Phase 1 — run/test/demo walkthrough (cascade + confirm + release)
├── contracts/
│   └── no-show-defense-api.md  # candidate confirm + recruiter release + workspace-config settings + cascade SPI
├── checklists/
│   └── requirements.md  # spec quality + multi-role spec-review notes (already present)
└── tasks.md             # Phase 2 (/speckit.tasks — NOT created here)
```

### Source Code (repository root)

```text
backend/src/main/java/com/cadence/
├── api/
│   ├── CandidateBookingController.java       # MODIFIED + POST /api/candidate/booking/{confirmToken}/confirm (public-by-token, affirmative POST, rate-limited)
│   ├── SchedulingController.java             # MODIFIED + POST /api/internal/candidates/{candidateId}/scheduling/release (sibling of /cancel,/reschedule; reuses cancelByRecruiter)
│   ├── SchedulingDtos.java                   # MODIFIED + ConfirmAttendanceResponse, ReleaseResponse
│   ├── SchedulingExceptions.java             # (reuse TokenExpired/TokenInvalid/RateLimited/NoActiveBooking/Ineligible — likely no new type)
│   └── WorkspaceConfigController/Dtos        # MODIFIED + confirmationLeadTime / unconfirmedEscalationDeadline fields (reuse F03 update)
├── config/
│   ├── NoShowProperties.java                 # NEW cadence.noshow.* (lead-time PT24H, escalation PT2H, cascade interval/bound/batch)
│   ├── SchedulingProperties.java             # MODIFIED + spaConfirmBasePath (default /confirm)
│   └── migration/
│       └── ChangeUnit014_NoShowIndexes.java  # NEW order "014" ({status,bookedStartAt}; unique partial {confirmTokenHash}; + backfill bookedStartAt on pre-F23 BOOKED rows)
├── domain/
│   ├── SchedulingRequest.java                # MODIFIED + bookedStartAt, confirmationRequestedAt, confirmTokenHash (@Field write=NON_NULL), confirmationNotRequestable, candidateConfirmedAt, escalatedAt, noShowAt; toString omits confirmTokenHash
│   ├── WorkspaceConfig.java                  # MODIFIED + confirmationLeadTime, unconfirmedEscalationDeadline (nullable Duration)
│   ├── MergeToken.java                       # MODIFIED + CONFIRM_LINK (append-only)
│   ├── RecruiterNotificationType.java        # MODIFIED + INTERVIEW_UNCONFIRMED (append-only, value-free)
│   ├── AuthEventType.java                    # MODIFIED + NOSHOW_CONFIRMATION_REQUESTED, NOSHOW_ATTENDANCE_CONFIRMED, NOSHOW_UNCONFIRMED_ESCALATED (append-only)
│   └── CandidateAuditOutcome.java            # MODIFIED + ATTENDANCE_CONFIRMED (append-only)
├── repository/
│   └── SchedulingRequestRepository.java      # MODIFIED + findByConfirmTokenHash, three Pageable-capped cascade-stage finders (status+bookedStartAt+stage-null)
├── service/
│   ├── NoShowCascadeService.java             # NEW requestConfirmation/escalateUnconfirmed/stampNoShow/confirmAttendance (stage CAS + dispatch + notify + audit)
│   ├── SlotReservationService.java           # MODIFIED book(): set bookedStartAt in the BOOKING->BOOKED CAS (covers initial + reschedule rounds)
│   ├── SchedulingService.java                # REUSED cancelByRecruiter for /release (already resolves booking + refuses past + cancelByBooking) — no new method
│   ├── CandidateErasureService.java          # MODIFIED $unset confirmTokenHash on the BOOKED-cancel flip (D9)
│   ├── WorkspaceConfigService.java           # MODIFIED + validate/persist the two cascade settings (0 < escalation < lead <= bound)
│   ├── BuiltInEmailTemplates.java            # MODIFIED REMINDER_24H body gains the {{confirm_link}} CTA
│   └── MergeTokenCatalogue.java              # MODIFIED permit CONFIRM_LINK for REMINDER_24H (+ REMINDER_1H)
└── scheduler/
    └── NoShowDefenseScheduler.java           # NEW @Scheduled sweep + @PostConstruct replay (checkpoint "no-show-cascade"), 3 Pageable-capped stages, per-workspace offset filter

backend/src/test/java/com/cadence/scheduling/  # EXTENDED (cascade timing, confirm, release, concurrency, erasure, config, E2E, PII scan)
frontend/src/app/features/
├── booking/                                   # MODIFIED + confirm-attendance standalone page (public route /confirm?token=) — WCAG 2.2 AA, $localize, <2s; recruiter "Release slot" action wiring
└── (recruiter per-candidate view)             # MODIFIED add "Unconfirmed" indicator + "Release slot" action + confirmation-status chip
frontend/lighthouse/serve-with-stub.mjs        # MODIFIED + canned GET/POST /api/candidate/booking/<demo>/confirm handler (else the gate measures the vacuous invalid state — the F14 bug)
lighthouserc.json                              # MODIFIED + …/confirm?token=lighthouse-demo (+ confirmed/expired states) in ci.collect.url[]
```

**Structure Decision**: Standard Cadence layout. F23 adds **one new scheduler** (`NoShowDefenseScheduler`) and **one new service** (`NoShowCascadeService`) — the rest extends F13/F20/F22/F04/F03 seams. The cascade is expressed entirely through per-booking `findAndModify` CAS on the existing `schedulingRequests` collection (no edits to the F13/F20 confirm/reschedule saga beyond the one `bookedStartAt` write); the release reuses `cancelByBooking` verbatim. The new candidate confirm page lives under `features/booking/` (the F20 sibling).

## Multi-role plan review (2026-06-16) — verdict: APPROVE-WITH-NITS after fixes

Reviewers: Backend/Architecture, Security/GDPR, QA/DevOps — each verified claims against the **real source**. All three returned **APPROVE-WITH-NITS**. MUST-FIX and high-value SHOULD-FIX findings folded into the artifacts before `tasks.md`:

- **`bookedStartAt` was overstated as "one line / single site" (Backend, MAJOR)**: correct — the BOOKED CAS covers initial + reschedule rounds, but **pre-F23 BOOKED rows have `bookedStartAt==null` and are invisible to the cascade**. **Fixed** → `ChangeUnit014` backfills `bookedStartAt` from `offeredSlots[chosenSlotId].start` (research D2, data-model §6, plan).
- **`REMINDER_24H` built-in body contains `{{reschedule_link}}` (Backend, MAJOR)**: the cascade context doesn't supply it → it would render as the F21 `[[missing:reschedule_link]]` marker. **Fixed** → the built-in body drops `{{reschedule_link}}` and adds the `{{confirm_link}}` CTA; the `MergeTokenCatalogue` permission + `BuiltInTemplateCompletenessTest` move together (research D4, data-model §4).
- **Erasure "atomic / no race window" was an overclaim (Security, MAJOR)**: the real `supersedeLiveScheduling` is `find`-then-`updateMulti({_id ∈ ids})` — **not** a per-row CAS, so a stage-1 sweep can fire in the find→update gap. **Fixed** → the honest backstop is the **F22 consent re-gate at send** (re-evaluated after the outbox claim) which suppresses any reminder enqueued for a now-erased subject; `$unset confirmTokenHash` on the BOOKED branch; new SC-009 between-CAS-and-dispatch erasure test (research D9, data-model §7).
- **Confirm 410-vs-400 precedence / start-source divergence (Security+Backend, MAJOR)**: **Fixed** → evaluate **status-before-time** (not-`BOOKED` → 400 precedes past-start → 410, so a rescheduled-away past parent yields the indistinguishable 400); the past check uses `chosenStart()` (NOT the sweep-only `bookedStartAt`); contract test asserts byte-identical 400 across {unknown, released-CANCELLED, erased, SUPERSEDED} (research D3, contract A1, data-model §7).
- **Scheduler must read the injected `Clock` (QA, MAJOR)**: else the DST/timing tests assert against wall-clock and flake. **Fixed** → `NoShowDefenseScheduler` resolves `now` from the F01 `@Primary MutableClock`, never `Instant.now()` (research D8, data-model §5).
- **`WorkspaceConfigService` "reuse F03 update" undersold the work (Backend, MINOR→pinned)**: the patch surface is per-field hard-coded; the two settings need two fields + two branches + a **cross-field** validator running after `wsValue ?? default` resolution (research D7, data-model §3).
- **Idempotency-key wording was inaccurate (Backend, MINOR)**: the real F22 key is `dispatchKey(ws,candidate,type,scheduledForMillis)` — no round id. **Fixed** → rely on distinct `scheduledFor` per round (the F13 trick); no `IdempotencyKeys` change (research D8).
- **CSRF posture stated (Security, MINOR)**: the public confirm POST is token-authenticated on the STATELESS chain (no cookie → no CSRF token needed); the internal release POST inherits the `@Order(3)` chain CSRF posture (research D3, contract A1).
- **`confirmationNotRequestable` oracle guard (Security, MINOR)**: marked `@JsonIgnore` + excluded from every recruiter/F50 DTO so the contactability oracle can't re-emerge at the dashboard (data-model §1).

**Test-plan additions folded into quickstart/DoD (QA)**: persisted-`escalatedAt` assertion in the SC-005 E2E; a start-passes-mid-sweep stage-2-suppression test; reschedule-resets-cascade (fresh `REMINDER_24H` not stale-key-suppressed); multiple-bookings-per-candidate independence; confirm-after-release ordering; the D8 lost-reminder seam (mock `dispatch.enqueue` to throw post-claim); the SC-007 gated `CountDownLatch` collision seam; the `lead > cascadeQueryBound` config-rejection arm + at-bound positive; and a CI grep guard banning `sms|whatsapp|twilio|waitlist` literals (SC-012) alongside the `SENTINELF23*` PII scan.

No remaining blocking items. Residual mechanics intentionally left to `tasks.md`: the exact backfill loop in `ChangeUnit014`; the optional hardening of the erasure `updateMulti` to a per-row `status:BOOKED` CAS (the send-time re-gate already closes the gap); the `serve-with-stub.mjs` GET+POST confirm handlers + `lighthouserc.json` url[] states; the cross-field config validator wiring.

DoD note (§IX): **F23 owns the blocking accessibility/performance gate on its new candidate confirm page** (the F20 precedent — no successor polish feature). The confirm page MUST pass axe-core 0 WCAG 2.2 AA violations and Lighthouse ≥ 85 (mobile) as blocking CI gates, all strings `$localize`, no-login, time-zone-correct, no PII/token in URL or logs. Concretely: extend `frontend/src/testing/axe.ts` per-state specs to the confirm component; add `…/confirm?token=lighthouse-demo` (+ confirmed/expired states) to `lighthouserc.json` `ci.collect.url[]`; extend `frontend/lighthouse/serve-with-stub.mjs` with canned `GET`/`POST /api/candidate/booking/<demo>/confirm` handlers + SPA fallback (else the gate measures the vacuous `invalid` state — the F14 bug). Recruiter release/indicator are internal screens (Lighthouse/WCAG N/A, F50/F51 precedent).

## Complexity Tracking

No constitution violations — table intentionally empty.

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| (none) | — | — |
