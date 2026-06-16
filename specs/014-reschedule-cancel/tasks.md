---
description: "Task list for F20 — Flow A3 Reschedule & Cancellation"
---

# Tasks: Flow A3 — Reschedule & Cancellation (F20)

**Input**: Design documents from `/specs/014-reschedule-cancel/`
**Prerequisites**: plan.md, spec.md, research.md (D1–D11), data-model.md, contracts/reschedule-cancel-api.md, quickstart.md

**Tests**: INCLUDED — Constitution Principle VII (test-first) and the plan §Testing mandate unit + integration + contract + frontend-unit + E2E. Write each test task FIRST and confirm it FAILS before the matching implementation.

**Run flags (every backend test/build)**: `JAVA_HOME=C:/jdk-24.0.1`, the cached `gradle-9.4.0` binary (never the wrapper download — Principle X / C7), `-Dapi.version=1.41`, `DOCKER_HOST=npipe:////./pipe/docker_engine`. The first multi-class Testcontainers run after a recompile may throw the one-time `GenericContainer` class-init error — re-run.

**Path conventions** (web app): backend `backend/src/main/java/com/cadence/`, backend tests `backend/src/test/java/com/cadence/scheduling/`, frontend `frontend/src/app/features/`.

**Reuse posture**: F20 adds NO new collection, NO new runtime dependency, NO broker. It extends F13 (`SchedulingService`/`SlotReservationService`/`SchedulingReaper`/`SchedulingRequest`), reuses F10 `CalendarEventService.createPanelEvents`/`cancelBooking`, F12 `RuleEngine`/`AvailabilityService`, F22 `EmailDispatchService`/`ContactPermissionGate`/`RecruiterNotificationService`, F01 `SecureTokens`/`TokenHasher`/member-mail, F00.2 `SchedulerCheckpointService`, F04 `CandidateErasureService`/`CandidateAuditService`.

**Multi-role task review (2026-06-16, C6 gate — 3 roles, verified against real source)**: Backend/Architecture + QA returned CHANGES-REQUESTED, BA APPROVE-WITH-NITS. All findings folded in: **(BLOCKER)** register `CandidateBookingController` in the `SchedulingExceptionHandler` `assignableTypes` (T011) — else all booking errors 500; **(BLOCKER)** SC-006 DST had no backend test → added **T051** (wire-body offset+IANA); **(MAJOR)** `spaBookingBasePath` config added (T001); **(MAJOR)** T007 now definitively appends `CandidateAuditOutcome` values; **(MAJOR)** FR-005 cap-breach now wires the recruiter notification + candidate email + manage-link invalidation and consumes the `*_CAP_REACHED` enums (T020) + tested (T015); **(MAJOR)** SC-013 full (rollback/refused legs) + FR-027 no-false-`slot_taken` + FR-014 round-discriminator now tested (T016); **(MAJOR)** SC-007 recruiter-succeeds-after-cap tested (T030); **(MINOR)** `status()` lineage finder added (T010), FR-013 exactly-once (T035/T039), 3-arg `notify` signature noted. File-sequencing, FR traceability (FR-001..FR-027 all mapped), story labels, checklist format, scope discipline, and C7-compliant non-Playwright E2E all confirmed sound.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Configuration + CI scan groundwork. No new project scaffolding (extends F13).

- [X] T001 [P] Add reschedule config to `backend/src/main/java/com/cadence/config/SchedulingProperties.java` (`rescheduleCap` default 3, `selfServiceLeadTime` default `PT0S`, **`spaBookingBasePath` default `/booking`** — used by T014 to build the manage link; reuse the existing `reaperThreshold`/`reaperSweepBatchLimit`) and the matching defaults block in `backend/src/main/resources/application.yml` under `cadence.scheduling.*`.
- [X] T002 [P] Extend the CI PII scan in `.github/workflows/ci.yml` with the `SENTINELF20*` sentinel set (reschedule/cancel token sentinel + `locationText` sentinel + candidate-PII sentinel), mirroring the F13 `SENTINELF13*` scan block.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Domain/enum/index/repository/credential plumbing that ALL three stories require. The manage-token issuance (T014) is what gives every booking a reschedule/cancel credential — without it no candidate-facing story works.

**⚠️ CRITICAL**: No user story work can begin until this phase is complete.

- [X] T003 [P] Create `backend/src/main/java/com/cadence/domain/SchedulingMode.java` enum (`INITIAL`, `RESCHEDULE`).
- [X] T004 [P] Append `CANCELLING`, `CANCELLED`, `RESCHEDULED` to `backend/src/main/java/com/cadence/domain/SchedulingStatus.java` (append-only, never reorder).
- [X] T005 [P] Append `SCHEDULING_RESCHEDULED`, `SCHEDULING_CANCELLED`, `SCHEDULING_CAP_REACHED` to `backend/src/main/java/com/cadence/domain/AuthEventType.java` (append-only).
- [X] T006 [P] Append `INTERVIEW_CANCELLED_BY_CANDIDATE`, `RESCHEDULE_NO_SLOTS`, `RESCHEDULE_CAP_REACHED` to `backend/src/main/java/com/cadence/domain/RecruiterNotificationType.java` (append-only, value-free; logged via `.name()` only — the logstash `kv` enum footgun).
- [X] T007 [P] `CandidateEventType.BOOKING_CHANGED` already exists (do NOT re-add). Append concrete `CandidateAuditOutcome` values `BOOKING_RESCHEDULED` and `BOOKING_CANCELLED` (append-only, never reorder) to `backend/src/main/java/com/cadence/domain/CandidateAuditOutcome.java` — T021/T038/T039 emit `BOOKING_CHANGED` with one of these as the (required) 4th arg of `CandidateAuditService.append(...)`; do NOT reuse a semantically-wrong existing value.
- [X] T008 Add fields to `backend/src/main/java/com/cadence/domain/SchedulingRequest.java`: `mode` (default null→INITIAL semantics), `rootRequestId`, `parentRequestId`, `manageTokenHash` annotated **`@Field(write = Field.Write.NON_NULL)`**, `rescheduleInvitedAt`, `cancelledAt`, `calendarTeardownPending` — with getters/setters; extend `toString()` to also omit `manageTokenHash` (depends on T003).
- [X] T009 Create `backend/src/main/java/com/cadence/config/migration/ChangeUnit013_RescheduleIndexes.java` (order **"013"** off applied "012"): unique **partial** `{manageTokenHash:1}` `partialFilterExpression {manageTokenHash:{$exists:true}}`; `{rootRequestId:1, mode:1, status:1}`; `{mode:1, status:1, updatedAt:1}`; partial `{calendarTeardownPending:1}` `{calendarTeardownPending:true}` — native `createIndex`; targeted per-index `dropIndex` rollback (never `dropIndexes()`).
- [X] T010 Add repository methods to `backend/src/main/java/com/cadence/repository/SchedulingRequestRepository.java`: `findByManageTokenHash`, `countByRootRequestIdAndModeAndStatus`, a `@Query` `findRescheduleAwaitingForwardCommit(mode,status,before, Pageable)` (`{mode:RESCHEDULE, status:BOOKED, updatedAt:{$lt:?}}`, `Pageable`-capped), `findByCalendarTeardownPendingTrue(Pageable)`, and the **authoritative-booking lineage read for `status()`** `findFirstByWorkspaceIdAndCandidateIdAndStatusOrderByCreatedAtDesc(workspaceId, candidateId, BOOKED)` (so T033 resolves the live booking, not the newest possibly-in-flight child).
- [X] T011 Add exceptions to `backend/src/main/java/com/cadence/api/SchedulingExceptions.java` (`CapReachedException`→409, `RescheduleNoSlotsException`→422, `IneligibleException`→409, `NoActiveBookingException`→409, `AlreadyCancelledException`→409) and map them (plus the manage-token 410-for-past-interview) in `backend/src/main/java/com/cadence/api/SchedulingExceptionHandler.java`, reusing the existing safe-envelope shape. **Add `CandidateBookingController.class` to the handler's `@RestControllerAdvice(assignableTypes = {...})` list** (currently `SchedulingController`/`CandidateSchedulingController` only) — otherwise every booking-endpoint `SchedulingExceptions.*` falls through to a generic 500 and the T015/T035 contract envelopes break.
- [X] T012 Add manage/reschedule/cancel response DTOs to `backend/src/main/java/com/cadence/api/SchedulingDtos.java` (`BookingView` = status/bookedStart/zone/capabilities/rescheduleRemaining — **times only, no participant identity, no `locationText`**; `OpenRescheduleResponse` = rescheduleToken + times-only slots; `CancelResponse`).
- [X] T013 Add a `releaseClaims(String workspaceId, String requestId)` overload to `backend/src/main/java/com/cadence/service/SlotReservationService.java` (an `updateMulti` on `{schedulingRequestId, status:ACTIVE}→RELEASED`, the reaper's shape) — the existing `releaseClaims(List,now)` only releases the in-memory just-inserted list and cannot release a parent's claims.
- [X] T014 Foundational manage-token issuance: in `backend/src/main/java/com/cadence/service/SlotReservationService.java`, on a successful `INITIAL` booking (the `CREATED` case) mint a 256-bit manage token via `SecureTokens.newToken()`, persist `manageTokenHash = TokenHasher.hashToken(...)` on the BOOKED row, and add the manage link (`{spaBaseUrl}{spaBookingBasePath}?token=<manageToken>`) to the candidate confirmation context in `sendConfirmations(...)`; set `rootRequestId` per the INITIAL rule (null/means-self). Never log the raw manage token (depends on T008, T013).

**Checkpoint**: Domain, indexes, repository, exceptions, DTOs, and the booking-management credential exist. User stories can begin.

---

## Phase 3: User Story 1 — Candidate reschedules without contacting the recruiter (Priority: P1) 🎯 MVP

**Goal**: From the confirmation-email link, a candidate (no login) opens fresh compliant slots, picks one, and Cadence atomically swaps the interview — creating new events before cancelling the old, preserving the original on any failure.

**Independent Test**: From a confirmed booking, open `/booking?token=<manageToken>`, reschedule to a new slot, and verify old events cancelled + new created + updated invites + a fresh confirmation + one audit entry — all without authenticating; and that a forced new-event failure leaves the original booking fully intact.

### Tests for User Story 1 (write first, must fail) ⚠️

- [X] T015 [P] [US1] Contract test (MockMvc) for `CandidateBookingController` GET `/api/candidate/booking/{token}` + POST `.../reschedule` in `backend/src/test/java/com/cadence/scheduling/CandidateBookingContractTest.java`: 200 open (non-circular **no participant identity / no `locationText`** — seed member ids + a location sentinel, assert absent), 410 past-interview, 400 byte-identical invalid, 429, 409 cap_reached/ineligible, 422 no_slots, 409 not_available (byte-identical deny reasons). **On cap_reached: assert the recruiter notification was dispatched and the now-invalidated manage link resolves to byte-identical 400 (SC-007 link-invalidation / FR-005 / no oracle).**
- [X] T016 [P] [US1] Integration test (Testcontainers) reschedule swap in `backend/src/test/java/com/cadence/scheduling/RescheduleSwapTest.java`: new-before-old ordering (against the **mixed Google+Microsoft stub** path via `createPanelEvents`/`cancelBooking`), **original-preserved-on-failure (SC-003)** (stub new-event failure → parent stays BOOKED with live events), cleanup-incomplete (SC-005). **SC-013 in full: assert `rescheduleRemaining`/`countByRootRequestIdAndModeAndStatus` is unchanged after (a) a rolled-back reschedule, (b) a refused stale-slot reschedule, and (c) a same-time no-op.** **FR-027: a same-time confirm returns the existing booking and never a `slot_taken`/409 (the short-circuit runs before any `claims.insert`).** **FR-014: two reschedule rounds resolving to the same instant each dispatch a confirmation (no idempotency-key collision).**
- [X] T017 [P] [US1] Gated/latched **`@RepeatedTest`** concurrency test in `backend/src/test/java/com/cadence/scheduling/RescheduleConcurrencyTest.java`: simultaneous reschedule-vs-cancel and double-confirm-of-same-new-slot → exactly one commits, no double-booking, no split state (**SC-004**) — mirrors the F13 `SlotReservationConcurrencyTest` latch pattern against the real unmocked claim CAS.
- [X] T018 [P] [US1] Recovery test in `backend/src/test/java/com/cadence/scheduling/RescheduleRecoveryTest.java`: roll-forward (child BOOKED, parent BOOKED → reaper cancels parent → RESCHEDULED) and roll-back (child stuck BOOKING → released, parent stands), driven by a stamped `updatedAt`/clock (no wall-clock sleeps).
- [X] T019 [P] [US1] Isolation test in `backend/src/test/java/com/cadence/scheduling/RescheduleIsolationTest.java`: IDOR — a manage token bound to booking X cannot affect booking Y even with Y's id in the body (**SC-014**); and the carve-out (D7) — a reschedule on the same day is NOT falsely refused "no slots" because the moved booking's own events/cap are excluded.
- [X] T051 [P] [US1] DST wire-body test in `backend/src/test/java/com/cadence/scheduling/RescheduleDstWireBodyTest.java` (US1-phase test, added in review): a reschedule to a slot within one hour of a DST transition — assert the **recorded** new-event create body on the F10/F11 stub carries the correct UTC offset (pre/post transition) AND the IANA zone, not naive local time (**SC-006**, the F10/F11 DST wire-body precedent). Write first, must fail.

### Implementation for User Story 1

- [X] T020 [US1] Implement `viewBooking(rawManageToken, ip)` and `openReschedule(rawManageToken, ip)` in `backend/src/main/java/com/cadence/service/SlotReservationService.java`: resolve the booking SOLELY from `findByManageTokenHash` (no client id); enforce eligibility (FR-004 lead-time/past → 410/ineligible) + cap derivation (`countByRootRequestIdAndModeAndStatus(root, RESCHEDULE, BOOKED) < cap`) + contactability; compute fresh slots via `RuleEngine` excluding the booked instant; insert a new `RESCHEDULE` round (set `mode`, `parentRequestId`, `rootRequestId = parent.rootRequestId ?? parent.id`, copy `locationText` plaintext-in-memory, own slot-pick token); return `{rescheduleToken, slots}`. **On the cap-reached branch (FR-005, consuming the new enums): `$unset` the booking's `manageTokenHash` to invalidate the self-service link, dispatch the recruiter notification `RecruiterNotificationService.notify(workspaceId, candidateId, RESCHEDULE_CAP_REACHED)` (3-arg, no actor) + a candidate "contact your recruiter" email, record `SCHEDULING_CAP_REACHED`, and throw `CapReachedException` (the public link thereafter resolves to byte-identical 400 — no oracle).** (depends on T014).
- [X] T021 [US1] Add the `RESCHEDULE` forward-commit branch to `SlotReservationService.book()` CREATED case in `backend/src/main/java/com/cadence/service/SlotReservationService.java`: **capture** the child→BOOKED `findAndModify` (`returnNew(true)`) and proceed only on match; parent cancel = CAS `findAndModify({_id:parentId, status:BOOKED}→RESCHEDULED)` + `CalendarEventService.cancelBooking(parentId)` + `releaseClaims(workspaceId, parentId)` + rotate a fresh `manageTokenHash` onto the new BOOKED round; emit `SCHEDULING_RESCHEDULED` + candidate `BOOKING_CHANGED`; PLUS the same-time no-op pre-claim guard (compare chosen new start to the parent's booked start, **before** any `claims.insert`). **The F22 `enqueue` idempotency key for the reschedule confirmation MUST incorporate the new round/booking id (a reschedule-round discriminator) so a round that returns the candidate to an earlier-used instant is not suppressed as a duplicate (FR-014).** (depends on T020).
- [X] T022 [US1] Add the moved-booking carve-out seam (D7) in `backend/src/main/java/com/cadence/service/AvailabilityService.java` (and/or `RuleEngine.java`): an `excludeBookingRef` parameter (or post-filter) so the parent booking's `managedCalendarEvents` rows and its daily-cap contribution are not counted when computing the reschedule round's slots.
- [X] T023 [US1] Add the forward-commit recovery pass to `backend/src/main/java/com/cadence/scheduler/SchedulingReaper.java`: indexed scan `findRescheduleAwaitingForwardCommit(...)` (`Pageable`-capped) + per-row parent CAS `findAndModify({_id:parentId, status:BOOKED}→RESCHEDULED)` → cancel parent events + `releaseClaims(workspaceId, parentId)`; inside the existing `checkpoints.start/complete` (depends on T010).
- [X] T024 [US1] Create `backend/src/main/java/com/cadence/api/CandidateBookingController.java`: GET `/api/candidate/booking/{token}` (viewBooking) + POST `/api/candidate/booking/{token}/reschedule` (openReschedule) — public-by-token on the existing `@Order(2)` permitAll/STATELESS chain, rate-limited via the F13 `CandidateRateLimiter`, `Cache-Control: no-store`; the reschedule **confirm** reuses the unchanged F13 `POST /api/candidate/scheduling/{rescheduleToken}/confirm` (depends on T020, T021).
- [X] T025 [P] [US1] Create the candidate booking-manage standalone page `frontend/src/app/features/booking/booking-manage.component.ts` (+ `.html`/`.scss`): public, no-login, manage token held in a memory-only field (re-resolved on `ngOnInit`, never `localStorage`/`console`), times-only display in the candidate's local zone with DST-correct labels, "Reschedule" / "Cancel" actions, all strings `$localize`, `overflow-wrap:anywhere` for long zone/RTL strings.
- [X] T026 [US1] Add the `/booking` route as a **top-level public sibling** (no `authGuard`) in `frontend/src/app/app.routes.ts` and wire the reschedule slot-picker reuse: `features/schedule` consumes the `rescheduleToken` returned by `openReschedule` and confirms via the existing F13 path (depends on T025).
- [X] T027 [P] [US1] axe-core specs `frontend/src/app/features/booking/booking-manage.component.spec.ts`: 0 WCAG 2.2 AA violations across the booked / reschedule-open / error states (body-attached fixture, `await axe.run` per the F14 `frontend/src/testing/axe.ts` harness); plus no-storage / no-console-token / 44px-target assertions.
- [X] T028 [US1] Extend `lighthouserc.json` `ci.collect.url[]` with `…/booking?token=lighthouse-demo` (and the reschedule-open state) and add canned handlers to `frontend/lighthouse/serve-with-stub.mjs` for `GET /api/candidate/booking/<demo>` (booked + capabilities) and `POST /api/candidate/booking/<demo>/reschedule` (times-only slots) so the **blocking** Lighthouse gate measures the real content-bearing state, not the vacuous `invalid` state.
- [X] T029 [US1] E2E in the existing Karma/EdgeHeadless harness (NO Playwright / no Chromium download — C7): reschedule → old events cancelled → new invites + confirmation sent → one audit entry (**SC-012**), driven against the F10/F11 stubs, in `frontend/src/app/features/booking/booking-reschedule.e2e.spec.ts` (or a backend stub-driven `RescheduleEndToEndTest.java`).

**Checkpoint**: Candidate self-service reschedule is fully functional, independently testable, and the §IX blocking gate passes — MVP deliverable.

---

## Phase 4: User Story 2 — Recruiter reschedules on behalf of any party (Priority: P2)

**Goal**: A recruiter triggers a reschedule from the candidate view; the existing booking stays active while the candidate receives a fresh reschedule invitation; the recruiter sees "Reschedule in progress."

**Independent Test**: As a Recruiter, trigger reschedule for a confirmed booking → a reschedule invitation is dispatched, the booking stays BOOKED, status reads "Reschedule in progress," and after the candidate picks a new slot it reads "Scheduled" at the new time.

### Tests for User Story 2 (write first, must fail) ⚠️

- [X] T030 [P] [US2] Contract test (MockMvc) for the recruiter reschedule endpoint in `backend/src/test/java/com/cadence/scheduling/RecruiterRescheduleContractTest.java`: 200 reschedule_in_progress (booking stays BOOKED, candidate emailed); 422 no_slots (booking retained, no candidate email); 409 not_contactable; 409 no_active_booking; 404 scoped-not-found (out-of-workspace, oracle-free); 403 for each disallowed role (5-role matrix). **Assert the recruiter path SUCCEEDS (200) for a booking whose candidate self-service cap is already exhausted — recruiter reschedule is uncapped (SC-007 / FR-005).**

### Implementation for User Story 2

- [X] T031 [US2] Implement `rescheduleByRecruiter(workspaceId, actorMemberId, candidateId, ip)` in `backend/src/main/java/com/cadence/service/SchedulingService.java`: contactability check → verify ≥1 compliant slot exists (carve-out) else `RescheduleNoSlotsException` (booking retained, recruiter notified `RESCHEDULE_NO_SLOTS`, no candidate email) → stamp `rescheduleInvitedAt` → supersede any prior live reschedule round for the booking (FR-017b) → dispatch the consent-gated reschedule-invitation email (manage link) → audit. Booking stays BOOKED.
- [X] T032 [US2] Add POST `/api/internal/candidates/{candidateId}/scheduling/reschedule` to `backend/src/main/java/com/cadence/api/SchedulingController.java` (class-level `@PreAuthorize("hasAnyRole('ADMIN','RECRUITER')")`, workspace-scoped → shared scoped-404).
- [X] T033 [US2] Replace the `status()` resolution in `backend/src/main/java/com/cadence/service/SchedulingService.java` to resolve the authoritative booking from the **root lineage** (NOT `findFirstByWorkspaceIdAndCandidateIdOrderByCreatedAtDesc` — the newest row may be an in-flight reschedule child) and derive `RESCHEDULE_IN_PROGRESS` (`BOOKED && rescheduleInvitedAt != null && no committed child`); surface `RESCHEDULED`/`CANCELLED` (FR-021, contract B3).
- [X] T034 [P] [US2] Add the recruiter "Reschedule" action + booking-status chip (incl. "Reschedule in progress") to the recruiter scheduling surface in `frontend/src/app/features/` (the F13 candidate/scheduling component), issuing `POST .../scheduling/reschedule`.

**Checkpoint**: Recruiter-initiated reschedule works end-to-end; the booking is never lost while a reschedule is in progress.

---

## Phase 5: User Story 3 — Candidate (or recruiter) cancels via the same link (Priority: P2)

**Goal**: Cancel an interview behind an affirmative confirmation — remove all calendar events, release the slot, notify the other side — and ensure an erased candidate's booking is torn down with zero residual events.

**Independent Test**: From the manage link, cancel a booking → events removed, slot released and re-selectable, recruiter notified, one audit entry; and erasing a candidate with a BOOKED interview leaves zero residual calendar events.

### Tests for User Story 3 (write first, must fail) ⚠️

- [X] T035 [P] [US3] Contract test (MockMvc) in `backend/src/test/java/com/cadence/scheduling/CancelContractTest.java`: candidate cancel — **affirmative POST** (a GET to the cancel path does NOT cancel), 200, idempotent replay, 409 cleanup_incomplete, 409 ineligible, 400 invalid, 429; recruiter cancel — 5-role matrix, candidate notified **exactly once** (FR-013), 409 no_active_booking, 404 scoped.
- [X] T036 [P] [US3] Integration test in `backend/src/test/java/com/cadence/scheduling/CancelTest.java`: cancel saga removes events for all participants + releases claims so the slot is **immediately re-selectable (SC-011)** + recruiter notified exactly once; and two cancelled bookings with cleared (`$unset`) manage tokens do NOT collide on the partial-unique `{manageTokenHash}` index (the F01 null-collision regression).
- [X] T037 [P] [US3] Integration test in `backend/src/test/java/com/cadence/scheduling/ErasureBookingTeardownTest.java`: erasing a candidate with a BOOKED interview → `wipe()` synchronously CASes CANCELLED + releases claims + `$unset` manage token + sets `calendarTeardownPending`; the next reaper pass removes the provider events → **zero residual events (SC-009)**; PII-free audit entries survive erasure unmodified (FR-022).

### Implementation for User Story 3

- [X] T038 [US3] Implement `cancel(rawManageToken, ip)` in `backend/src/main/java/com/cadence/service/SlotReservationService.java`: resolve booking from the credential only; CAS `BOOKED→CANCELLING` (single-winner vs. a concurrent reschedule/cancel) → `CalendarEventService.cancelBooking(bookingRef)` (inline) → `releaseClaims(workspaceId, bookingId)` → CAS `→CANCELLED` (or `→CLEANUP_INCOMPLETE` + recruiter alert) → `$unset manageTokenHash` → notify the recruiter in-app (`INTERVIEW_CANCELLED_BY_CANDIDATE`, NOT consent-gated) → `SCHEDULING_CANCELLED` + `BOOKING_CHANGED` audit.
- [X] T039 [US3] Implement `cancelByRecruiter(workspaceId, actorMemberId, candidateId, ip)` in `backend/src/main/java/com/cadence/service/SchedulingService.java`: the same cancel saga (resolve the candidate's live BOOKED booking via the new lineage finder, workspace-scoped) but notify the **candidate** (consent-gated F22, exactly once — FR-013); actor = the recruiter member id. Note `RecruiterNotificationService.notify` is 3-arg `(workspaceId, candidateId, type)` — no actor param; do not invent an overload.
- [X] T040 [US3] Add POST `/api/candidate/booking/{token}/cancel` (affirmative POST only) to `backend/src/main/java/com/cadence/api/CandidateBookingController.java` and POST `/api/internal/candidates/{candidateId}/scheduling/cancel` to `backend/src/main/java/com/cadence/api/SchedulingController.java` (depends on T024, T032, T038, T039).
- [X] T041 [US3] Extend `supersedeLiveScheduling` in `backend/src/main/java/com/cadence/service/CandidateErasureService.java` to also handle a `BOOKED`/in-flight booking with **O(1) writes only**: release ACTIVE claims, CAS `→CANCELLED`, `$unset manageTokenHash`, supersede in-flight reschedule rounds, set `calendarTeardownPending=true` (the provider teardown is the reaper's job — keep `wipe()` non-blocking, FR-024).
- [X] T042 [US3] Add the erasure calendar-teardown pass to `backend/src/main/java/com/cadence/scheduler/SchedulingReaper.java`: `findByCalendarTeardownPendingTrue(Pageable)` → `CalendarEventService.cancelBooking(bookingRef)` → clear the flag (idempotent; already-DELETED events are no-ops; cleanup-incomplete surfaced) (depends on T023 — same file).
- [X] T043 [P] [US3] Create the candidate cancel-confirm standalone page `frontend/src/app/features/booking/cancel-confirm.component.ts` (+ `.html`/`.scss`): public, an explicit affirmative confirm step (state-changing POST, never on load), respectful post-cancel messaging, all strings `$localize`.
- [X] T044 [P] [US3] axe-core spec `frontend/src/app/features/booking/cancel-confirm.component.spec.ts` (0 WCAG violations, body-attached) and extend `lighthouserc.json` url[] + `frontend/lighthouse/serve-with-stub.mjs` with the cancel-confirm state.
- [X] T045 [US3] Add the recruiter "Cancel" action to the recruiter scheduling surface in `frontend/src/app/features/` (issues `POST .../scheduling/cancel`).

**Checkpoint**: Cancellation (candidate + recruiter) and the erasure teardown all work; no residual calendar events for an erased subject.

---

## Phase 6: Polish & Cross-Cutting Concerns

- [X] T046 [P] PII log-scan test `backend/src/test/java/com/cadence/scheduling/SchedulingLogPiiScanTest.java`: drive a failing reschedule and a cancel with `SENTINELF20*` token/`locationText`/candidate sentinels and assert absence across logs, the persisted row, audit, and dead-letters.
- [X] T047 [P] Confirm `RbacEndpointInventoryTest` stays green — the new `/api/candidate/booking/**` rides the permitAll prefix; the new recruiter `/api/internal/.../scheduling/reschedule|cancel` carry the class `@PreAuthorize` (no unannotated internal handler).
- [ ] T048 [P] (Optional) Richer cancellation participant member-email: add an `OperationalEmailTemplates` cancellation constant AND a matching branch in `backend/src/main/java/com/cadence/integration/SmtpEmailSender.java` (the closed-dispatcher build-breaker — both edits or it transmits nothing). Skip if calendar-event removal alone is accepted as the FR-013 participant-awareness floor.
- [X] T049 Run `quickstart.md` validation end-to-end: full backend suite green (incl. all new `com.cadence.scheduling.*`), `ng test` + `ng build --configuration production` clean, and the real `@lhci/cli` audit of the `/booking` route(s) ≥ 85.
- [X] T050 Multi-role sub-agent implementation review (≥3 roles: Backend/Architecture, Security/GDPR, DevOps/QA) per Constitution Principle VI / Gate C6 — verify against the real source, apply or report all findings before task closure (incl. the Principle V non-ASCII scan if any `.ps1` was touched — none expected).

---

## Dependencies & Execution Order

### Phase dependencies

- **Setup (Phase 1)**: no dependencies — start immediately.
- **Foundational (Phase 2)**: depends on Setup — **BLOCKS all user stories** (T014 manage-token issuance is the gate).
- **US1 (Phase 3)**: depends on Foundational. The MVP.
- **US2 (Phase 4)**: depends on Foundational. Largely independent of US1 (recruiter re-invite reuses the same `openReschedule`/swap machinery built in US1's T020/T021 — so US2 implementation **should follow US1** for the shared swap, though its recruiter endpoint + status are independent).
- **US3 (Phase 5)**: depends on Foundational. The cancel saga is independent of the reschedule saga; the erasure teardown pass (T042) shares `SchedulingReaper.java` with US1's T023 (sequential, same file).
- **Polish (Phase 6)**: depends on all desired stories.

### Key cross-file sequencing (NOT parallel — same file)

- `SlotReservationService.java`: T013 → T014 → T020 → T021 → T038 (sequential).
- `SchedulingReaper.java`: T023 → T042 (sequential).
- `SchedulingController.java`: T032 → T040 (sequential).
- `CandidateBookingController.java`: T024 → T040 (sequential).
- `SchedulingService.java`: T031 → T033 → T039 (sequential).
- `lighthouserc.json` / `serve-with-stub.mjs`: T028 → T044 (sequential).

### Within each story

- Tests (T015–T019, T030, T035–T037) written FIRST and failing before implementation (Principle VII).
- Domain/repo/index before services; services before controllers; backend before the frontend page that calls it.

---

## Parallel Opportunities

```text
# Phase 2 Foundational — independent enum/domain files:
T003 SchedulingMode | T004 SchedulingStatus | T005 AuthEventType | T006 RecruiterNotificationType | T007 CandidateAuditOutcome

# US1 tests — all parallel (distinct test files), write first:
T015 contract | T016 swap | T017 concurrency | T018 recovery | T019 isolation | T051 DST wire-body

# Cross-story frontend pages — distinct component files:
T025 booking-manage (US1) | T043 cancel-confirm (US3)   [different files → parallel]
T027 axe booking-manage (US1) | T044 axe cancel (US3, but shares lighthouserc/stub → sequence the config edit)

# Polish — distinct files:
T046 PII scan test | T047 RBAC inventory | T048 optional member-email
```

---

## Implementation Strategy

### MVP first (US1 only)

1. Phase 1 Setup → Phase 2 Foundational (CRITICAL — T014 unblocks the credential).
2. Phase 3 US1 (candidate reschedule) → **STOP and VALIDATE**: SC-003 (original preserved), SC-004 (single-winner), SC-012 (E2E), the §IX blocking gate.
3. Demo: candidate reschedules from the email link, calendar swaps, browser→DB.

### Incremental delivery

1. Setup + Foundational → foundation ready.
2. US1 → test → demo (MVP).
3. US2 (recruiter reschedule) → test → demo.
4. US3 (cancel + erasure teardown) → test → demo.
5. Polish (PII scan, RBAC inventory, quickstart, multi-role review).

---

## Notes

- `[P]` = different files, no incomplete-task dependency. Same-file tasks are sequenced above.
- Every candidate-facing string `$localize`; the candidate booking pages own the **blocking** axe + Lighthouse gate (no successor polish feature).
- Reuse, do not reinvent: `CalendarEventService.createPanelEvents`/`cancelBooking`, `RuleEngine`, `ContactPermissionGate`, `EmailDispatchService`, `RecruiterNotificationService`, `SchedulerCheckpointService`, `TokenHasher`/`SecureTokens` — F20 adds no new collection, broker, or dependency.
- Enum→`kv` footgun: log only `.name()`/id Strings, never the enum object (the F01.1 logstash crash). No raw token / `locationText` / candidate PII in any log.
- Verify each test fails before implementing; commit after each task or logical group.
