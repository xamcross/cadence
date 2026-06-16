# Implementation Plan: Flow A3 — Reschedule & Cancellation (F20)

**Branch**: `014-reschedule-cancel` | **Date**: 2026-06-16 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/014-reschedule-cancel/spec.md`

## Summary

F20 lets either side move or cancel an already-booked single-stage interview through one link, with automatic propagation to every calendar and inbox — the constitution §11 / Principle I "Flow A3" MVP flow. It is, like F13, **almost entirely orchestration of existing seams** — no new collection, no new runtime dependency, no broker.

The load-bearing design decision (forced by the real F10 code): a **reschedule books the new time under a NEW `bookingRef`** — i.e. a new `schedulingRequests` round (`mode=RESCHEDULE`) linked to the original booking. `CalendarEventService.createForParticipant` has an idempotent fast-path keyed on `{workspaceId, bookingRef, memberId, provider}` (a CREATED row short-circuits the provider call), so reusing the original `bookingRef` would return the *old* event at the *old* time and never create at the new time. A fresh round means:

- The entire F13 confirm saga (`SlotReservationService.book`: request-status CAS → contactability re-check → re-validate + pool re-select → per-participant `interviewSlotClaim` CAS → `createPanelEvents` → confirmations) is **reused unchanged** to create the new booking.
- The original booking is left fully intact during the whole reschedule; it is cancelled (`CalendarEventService.cancelBooking`) **only after** the new round flips to `BOOKED`. So the F13 rollback/cleanup branches preserve the original "for free" (FR-009/FR-010), and the new round's **`status==BOOKED` flip is the durable commit point** that makes the FR-023 recovery decision deterministic (parent still `BOOKED` after child `BOOKED` ⇒ roll forward = cancel parent; child still `BOOKING` ⇒ roll back = release child, parent stands).
- The per-participant claim guard self-collision (FR-027) is avoided because the new time has a different `startAt`; the same-time case is a no-op short-circuited *before* any claim insert.

Cancellation is a direct `cancelBooking` + claim-release + status flip behind an affirmative candidate **POST** (never a GET — no prefetch/scanner auto-cancel). Notifications reuse F22 (candidate, consent-gated) and the F01 member-mail path (participants); the reschedule/cancel credential is a rotating 256-bit `manageTokenHash` on the booking (the F13 `TokenHasher` HMAC pattern). The stuck-state recovery is a new pass in the **existing** `SchedulingReaper` on the F00.2 checkpoint. Erasure (`CandidateErasureService`) is extended to cancel a **BOOKED** booking's calendar events (the F13 wipe only superseded `PENDING/BOOKING`).

The §II demonstrable leg: a candidate opens "Reschedule" from their confirmation email → an Angular reschedule page (no login, time-zone-correct, WCAG 2.2 AA) shows fresh slots → picks one → old calendar events vanish, new ones appear, updated invites + a fresh confirmation are sent — browser to database against the F10/F11 provider stubs; plus the candidate "Cancel" page and the recruiter reschedule/cancel actions.

## Technical Context

**Language/Version**: Java 21 (backend); TypeScript 5.4 / Angular 17.3 (frontend)
**Primary Dependencies**: Spring Boot 3.3.5 (web, data-mongodb, security w/ method security, actuator, aop, scheduling) — **no new runtime dependency**. Reuses F13 `SchedulingService`/`SlotReservationService`/`SchedulingReaper`/`SchedulingRequest`/`InterviewSlotClaim`/`SchedulingProperties`, F10/F11 `CalendarEventService` (`createPanelEvents`/`cancelBooking` already present), F12 `RuleEngine`/`AvailabilityService`, F22 `EmailDispatchService`/`ContactPermissionGate`/`RecruiterNotificationService`, F01 `SecureTokens`/`TokenHasher`/member-mail `EmailSender.sendEmail`+`OperationalEmailTemplates`, F00.2 `SchedulerCheckpointService`, F04 `CandidateErasureService`/`CandidateAuditService`. Per-IP rate limiting reuses the F13 in-memory `CandidateRateLimiter`. Frontend: Angular standalone + Angular CDK a11y + `axe-core`/`@lhci/cli` (already devDependencies from F14) for the new candidate pages. Mongock 5.4.4; logstash-logback-encoder 9.0.
**Storage**: MongoDB 7.x (Atlas prod, Testcontainers `mongo:7` tests). **No new collection.** Extends `schedulingRequests` (lineage + mode + `manageTokenHash` + reschedule/cancel timestamps; all ids/instants/enums — `locationText` remains the one encrypted field, copied onto the reschedule round so it survives the candidate's async confirm). Reuses `interviewSlotClaims` (the new round claims a different `startAt`; release-via-status-flip unchanged), `managedCalendarEvents` (F10; create under the new `bookingRef`, cancel under the old), `candidates` (F04 read + extended erasure), `emailDispatches` (F22), `schedulerCheckpoints` (F00.2), `interviewTemplates`/`workspaceConfig`, `authAuditLog`, `members`, `sessions`. **One new Mongock changeset** `ChangeUnit013` (order **"013"** off the highest applied **"012"**) adds a unique `{manageTokenHash}` index + a lineage/recovery index on `schedulingRequests`.
**Testing**: JUnit 5 + Mockito (unit: reschedule saga forward/rollback, same-time no-op, cap derivation, manage-token state, recovery decision rule); Testcontainers MongoDB (integration: **gated/latched + `@RepeatedTest` multi-trial** concurrent reschedule-vs-cancel + double-confirm single-winner — the F13 `SlotReservationConcurrencyTest` precedent, atomic swap new-before-old, original-preserved-on-failure, cleanup-incomplete, recovery sweep roll-forward/roll-back, erasure-cancels-booked-async, manage-token 410/400 precedence, IDOR cross-booking rejection, partial-unique `manageTokenHash` no-null-collision, audit/PII scan with `SENTINELF20*`); MockMvc (contract: candidate manage/reschedule/cancel endpoints + recruiter reschedule/cancel endpoints, 5-role matrix, workspace-scoped 404); Jasmine + axe-core (candidate manage + cancel pages: 0 WCAG violations, no-login, time-zone, RTL/overflow). **E2E (backlog-required: reschedule → old cancelled → new invites → audit) runs in the existing Karma/EdgeHeadless harness against the F10/F11 stubs — NOT Playwright (no `playwright install` Chromium download, C7/Principle X)**. `spring-security-test` (present).
**Target Platform**: Fly.io single Machine (backend), Cloudflare Pages (frontend) — topology unchanged.
**Project Type**: Web application (Spring Boot backend + Angular SPA) — both change.
**Performance Goals**: Candidate reschedule (open → pick → confirmed) under 2 min, human-initiated reschedule under 60 s effort (SC-001); old cancelled + new created on all calendars within 30 s of confirm (SC-002, against the provider harness); new candidate pages < 2 s on 4G / Lighthouse ≥ 85 (FR-020).
**Constraints**: Exactly-one-winner under simultaneous conflicting actions on one booking, zero double-booking and zero split state (FR-008/SC-004) via the F13 request-status `findAndModify` CAS + the per-participant unique-index claim. **Original booking preserved in 100% of failed reschedules** (FR-009/SC-003) — guaranteed structurally because the parent is cancelled only after the child `BOOKED` commit. Carries forward F13's documented honest bound: the exact-`(member,startAt)` claim fully serializes same-time/same-booking contention; a sub-second cross-template partial-overlap TOCTOU remains an accepted MVP limit. No PII / no token value in logs/audit/persisted docs (FR-022/FR-026/SC-010). Reschedule/cancel credential: 410 expired / 400 used-invalid-unknown (no oracle), 429 rate-limited; cap-breach/cancelled "helpful" message only post-authentication (FR-018). Candidate pages: WCAG 2.2 AA axe **blocking** (F20 owns its candidate surfaces — there is no later polish feature), no login, all strings `$localize` (FR-020 / §IX).
**Scale/Scope**: Zero new collections; one Mongock changeset (`ChangeUnit013`); extends `SchedulingService` (recruiter reschedule/cancel), `SlotReservationService` (reschedule book-saga branch + cancel + same-time no-op + manage-token view), `SchedulingReaper` (forward/rollback recovery pass), `CandidateErasureService` (cancel BOOKED), `SchedulingController` + a new `CandidateBookingController` (manage/reschedule/cancel, public-by-token), enums (`SchedulingStatus`, `AuthEventType`, `RecruiterNotificationType`, `CandidateEventType.BOOKING_CHANGED`); two new Angular candidate pages (reschedule slot-picker reuse + cancel-confirm) + recruiter reschedule/cancel actions.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Gate | Question | Result |
|---|---|---|
| **C1** | Within MVP scope (spec §11)? | ✅ Flow A3 one-link reschedule/cancellation is named explicitly in constitution Principle I and §11 MVP. Backlog F20, Tier 2 P2. |
| **C2** | New service, queue, or replica? | ✅ No. No new collection; reschedule reuses the F13 two-layer `findAndModify`/unique-index CAS and a new pass in the **existing** `@Scheduled SchedulingReaper` on the F00.2 checkpoint. No broker, no transaction coordinator, no cache tier (§IV async rule honoured). |
| **C3** | Exposes candidate PII to unauthorized roles? | ✅ No. Candidate manage/reschedule/cancel endpoints are public-by-token, return **times only** (never participant identities or `locationText`), and resolve the target booking **solely** from the credential (FR-017a, no IDOR). Recruiter endpoints are `@PreAuthorize` ADMIN/RECRUITER + workspace-scoped (oracle-free 404). New persisted fields are ids/instants/enums; `locationText` stays encrypted. |
| **C4** | Dependency outside the fixed stack? | ✅ No new dependency. `axe-core`/`@lhci/cli` are already F14 devDependencies; everything else reuses existing seams. |
| **C5** | New/modified Windows scripts with non-ASCII? | ✅ No new/modified `.ps1`/`.cmd`/`.bat`. CI PII-scan extended (ASCII only). |
| **C6** | Multi-role sub-agent review (≥3) scheduled? | ✅ Spec reviewed (4 roles); this plan is reviewed in this command (below); implementation review at task close. |
| **C7** | Downloads a build tool/runtime/CLI? | ✅ No. Reuses cached gradle-9.4.0 + installed JDK; `npm ci` installs the already-declared F14 devDeps. **The E2E uses the existing Karma/EdgeHeadless browser — `playwright install` (Chromium download) is explicitly NOT run** (the F14 decision, carried forward). |

**Initial gate: PASS.** No Complexity Tracking entries required.

**Post-Phase-1 re-check: PASS** — design adds zero collections, one changeset, zero dependencies, reuses every F13/F10/F11/F22/F04/F00.2 seam, and the only schema change is additive fields + two indexes on an existing collection. §IX obligation (F20 owns the blocking axe/Lighthouse gate on its new candidate pages) recorded in the DoD note below. See Phase 1 artifacts.

## Project Structure

### Documentation (this feature)

```text
specs/014-reschedule-cancel/
├── plan.md              # This file
├── research.md          # Phase 0 — reschedule-round model, atomic swap + commit point, recovery rule, manage-token, cap derivation, erasure, §IX
├── data-model.md        # Phase 1 — schedulingRequests lineage/mode/manage-token deltas, status machine, indexes, enum additions, erasure
├── quickstart.md        # Phase 1 — run/test/demo walkthrough (reschedule + cancel, both initiators)
├── contracts/
│   └── reschedule-cancel-api.md  # candidate manage/reschedule/cancel + recruiter reschedule/cancel endpoints, SPI
├── checklists/
│   └── requirements.md  # spec quality + multi-role spec-review notes (already present)
└── tasks.md             # Phase 2 (/speckit.tasks — NOT created here)
```

### Source Code (repository root)

```text
backend/src/main/java/com/cadence/
├── api/
│   ├── SchedulingController.java             # MODIFIED + POST .../scheduling/reschedule, POST .../scheduling/cancel (ADMIN/RECRUITER, workspace-scoped)
│   ├── CandidateBookingController.java       # NEW GET /api/candidate/booking/{manageToken} (current booking + capabilities),
│   │                                         #     POST .../{manageToken}/reschedule (open round → times-only slots), POST .../{manageToken}/cancel — public-by-token, rate-limited
│   ├── SchedulingDtos.java                   # MODIFIED + manage/reschedule/cancel DTOs (times only, no participant identity)
│   ├── SchedulingExceptions.java             # MODIFIED + CapReached / RescheduleNoSlots / Ineligible / AlreadyCancelled
│   └── SchedulingExceptionHandler.java       # MODIFIED map the new exceptions (410/400/409/422 per contract)
├── config/
│   ├── SchedulingProperties.java             # MODIFIED + reschedule cap (default 3), self-service lead-time (default PT0S), reschedule-recovery reuse of reaper threshold
│   └── migration/
│       └── ChangeUnit013_RescheduleIndexes.java  # NEW order "013" (unique {manageTokenHash}; {rootRequestId,mode,status}; recovery {mode,status,updatedAt})
├── domain/
│   ├── SchedulingRequest.java                # MODIFIED + mode, rootRequestId, parentRequestId, manageTokenHash, rescheduleInvitedAt, cancelledAt (ids/instants/enums)
│   ├── SchedulingStatus.java                 # MODIFIED + CANCELLING, CANCELLED, RESCHEDULED
│   ├── SchedulingMode.java                   # NEW enum INITIAL / RESCHEDULE
│   ├── AuthEventType.java                    # MODIFIED + SCHEDULING_RESCHEDULED, SCHEDULING_CANCELLED, SCHEDULING_CAP_REACHED (append-only)
│   ├── CandidateEventType.java               # (BOOKING_CHANGED forward contract — emit on reschedule/cancel; add if absent, append-only)
│   └── RecruiterNotificationType.java        # MODIFIED + INTERVIEW_CANCELLED_BY_CANDIDATE, RESCHEDULE_NO_SLOTS, RESCHEDULE_CAP_REACHED (value-free, append-only)
├── repository/
│   └── SchedulingRequestRepository.java      # MODIFIED + findByManageTokenHash, countByRootRequestIdAndModeAndStatus (cap), recovery finder (mode=RESCHEDULE,status=BOOKED w/ parent BOOKED)
├── service/
│   ├── SchedulingService.java                # MODIFIED + rescheduleByRecruiter (re-invite, preserve booking), cancelByRecruiter; status() returns reschedule lifecycle
│   ├── SlotReservationService.java           # MODIFIED book() RESCHEDULE branch (forward-commit: cancel parent after child BOOKED); same-time no-op (FR-027); openReschedule(); cancel(); manage-token view
│   └── CandidateErasureService.java          # MODIFIED supersedeLiveScheduling also cancels a BOOKED booking's calendar events + releases its claims (FR-024)
└── scheduler/
    └── SchedulingReaper.java                 # MODIFIED + recovery pass: RESCHEDULE round BOOKED w/ parent still BOOKED → roll forward (cancel parent); child stuck BOOKING already handled

backend/src/test/java/com/cadence/scheduling/  # EXTENDED test package (reschedule/cancel/recovery/erasure/IDOR/concurrency)
frontend/src/app/features/
├── schedule/                                  # MODIFIED reuse the slot-picker for the reschedule round (open via manage token); same §IX standards
├── booking/                                   # NEW candidate booking-manage + cancel-confirm standalone pages (public route, no guard) — WCAG 2.2 AA, $localize, <2s
└── candidates/ (or pipeline placeholder)      # MODIFIED add recruiter "Reschedule" / "Cancel" actions + booking-status chip
```

**Structure Decision**: Standard Cadence layout. F20 adds **no new service class** — it extends the F13 orchestrators (`SchedulingService`, `SlotReservationService`), the reaper, and the erasure service, and adds one candidate controller. The atomic swap is expressed entirely through the existing `CalendarEventService.createPanelEvents`/`cancelBooking` primitives (no edits to F10/F11/F22 logic). The new candidate pages live under `features/booking/`; the reschedule slot-picker reuses `features/schedule/`.

## Multi-role plan review (2026-06-16) — verdict: APPROVE-WITH-NITS after fixes

Reviewers: Backend/Architecture, Security/GDPR, DevOps/QA — each verified claims against the **real source**. Initial verdicts: Backend CHANGES-REQUESTED, Security APPROVE-WITH-NITS, DevOps/QA CHANGES-REQUESTED. All MAJOR findings folded into the artifacts before `tasks.md`:

- **Index footgun (Backend+Security, MAJOR)**: the `{manageTokenHash}` index was specified *sparse*; sparse alone is the F01 present-as-null collision footgun. **Fixed** → unique **partial** `{$exists:true}` + `@Field(write=NON_NULL)` on the field + clear via `$unset` (data-model §1/§6).
- **`rootRequestId` bootstrap (Backend, MAJOR)**: the INITIAL id is generated on insert. **Fixed** → INITIAL leaves it null (means-self); first RESCHEDULE derives `parent.rootRequestId ?? parent.id`; cap count roots on the INITIAL id (research D5, data-model §1).
- **Commit-point CAS (Backend, MAJOR)**: the real `book()` ignores the child→BOOKED `findAndModify` result. **Fixed** → capture it (`returnNew`), forward-commit only on match, parent cancel is its own `{status:BOOKED}` CAS; double-live window bound stated = `reaperThreshold` (research D2, data-model §5).
- **Recovery finder (QA, MAJOR)**: a single index can't cover the cross-document parent-status predicate. **Fixed** → indexed `{mode,status,updatedAt}` child-scan + per-row parent CAS, `Pageable`-capped (research D3, data-model §5/§6).
- **Erasure is synchronous (Backend+Security, MAJOR)**: `wipe()` is the F04 sync 202 path; an inline `cancelBooking` would block the SLA. **Fixed** → erasure does O(1) state flips synchronously + `calendarTeardownPending=true`; the reaper does the async provider teardown (research D9, data-model §5/§6).
- **Closed member-mail dispatcher (Security, MAJOR)**: `SmtpEmailSender` drops unknown templateIds. **Fixed** → reschedule confirmation reuses `INTERVIEW_CONFIRMATION_ID`; cancellation participant awareness via calendar-removal (FR-013 floor); a dedicated cancel member email is optional and needs both a template constant + a dispatcher branch (data-model §5, research D8).
- **LHCI gate vacuous (QA, MAJOR)**: `lighthouserc.json` url[] + `serve-with-stub.mjs` don't cover `/booking`. **Fixed** → concrete url[] additions + stub handlers named (research D11, DoD note below).
- **Playwright = C7 violation (QA, MAJOR)**: **Fixed** → E2E runs in the existing Karma/EdgeHeadless harness, no `playwright install` (Testing + C7 row).
- MINORs folded: `releaseClaims(workspaceId,requestId)` overload (not the in-memory-list method); `status()` resolves from the root lineage (not newest row); `BOOKING_CHANGED` already exists (don't re-add) + `CandidateAuditOutcome` 4th-arg signature; manage-token 410-for-past-interview branch + no-72h-TTL clarification (contract A1); `Pageable`-cap + `@RepeatedTest` multi-trial concurrency; `/booking` as a top-level public route sibling; `SENTINELF20*` log-scan sentinels.

No remaining blocking items. Residual mechanics intentionally left to `tasks.md`: the exact `partialFilterExpression` syntax + a cleared-manage-token-non-collision integration assertion; the availability/cap carve-out seam for the moved booking (research D7); the LHCI url[]/stub edits; the optional cancellation member-email template.

DoD note (§IX): **F20 owns the blocking accessibility/performance gate on its own candidate-facing surfaces.** Unlike F13 (which deferred the blocking axe/Lighthouse gate to F14), there is no separate polish feature after F20, so its new reschedule and cancel candidate pages MUST pass axe-core 0 WCAG 2.2 AA violations and Lighthouse ≥ 85 (mobile) as blocking CI gates, all strings `$localize`-marked, no-login, time-zone-correct, no PII/token in URL or logs. Concretely: extend `frontend/src/testing/axe.ts` per-state specs to the manage + cancel components; add `…/booking?token=lighthouse-demo` (+ cancel + reschedule states) to `lighthouserc.json` `ci.collect.url[]`; extend `frontend/lighthouse/serve-with-stub.mjs` with canned `GET /api/candidate/booking/<demo>` + `POST …/reschedule` handlers (else the gate measures the vacuous `invalid` state — the F14 bug). Recruiter actions are internal screens (Lighthouse/WCAG N/A, F50/F51 precedent).

## Complexity Tracking

No constitution violations — table intentionally empty.

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| (none) | — | — |
