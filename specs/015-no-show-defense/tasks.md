# Tasks: Flow A4 — No-Show Defense (F23)

**Input**: Design documents from `C:\Users\xamcr\Cadence\specs\015-no-show-defense\`
**Prerequisites**: plan.md, spec.md, research.md (D1–D10), data-model.md, contracts/no-show-defense-api.md, quickstart.md

**Tests**: INCLUDED — constitution Principle VII (Test-First) mandates tests against the spec's acceptance criteria; the plan lists them per story. Write each story's tests first (they must fail), then implement.

**Organization**: Phase 1 Setup → Phase 2 Foundational (blocks all stories) → Phase 3 US1 (P1) → Phase 4 US2 (P1) → Phase 5 US3 (P2) → Phase 6 Polish. US1 and US2 are co-P1 (the backlog value "turn an unconfirmed interview into a recovered slot" needs both); US1 is the demonstrable MVP slice.

**Path conventions**: Web app — `backend/src/main/java/com/cadence/...`, `backend/src/test/java/com/cadence/scheduling/...`, `frontend/src/app/features/...`.

**Run flags (zero-download, C7)**: `JAVA_HOME=C:/jdk-24.0.1`, cached `gradle-9.4.0` binary, `-Dapi.version=1.41`, `DOCKER_HOST=npipe:////./pipe/docker_engine`. The first multi-class Testcontainers run after a recompile may throw a one-time `GenericContainer` class-init error — re-run.

---

## Phase 1: Setup (Shared Configuration)

- [X] T001 [P] Create `NoShowProperties` (`@ConfigurationProperties("cadence.noshow")`) with `confirmationLeadTime=PT24H`, `escalationDeadline=PT2H`, `cascadeIntervalMs=60000`, `cascadeQueryBound=PT72H`, `cascadeSweepBatchLimit=200` in `backend/src/main/java/com/cadence/config/NoShowProperties.java`; add the `cadence.noshow.*` block to `backend/src/main/resources/application.yml` (no secrets).
- [X] T002 [P] Add `spaConfirmBasePath` (default `/confirm`) + accessors to `backend/src/main/java/com/cadence/config/SchedulingProperties.java` (the candidate confirm link base path).
- [X] T003 [P] Extend `.github/workflows/ci.yml` PII/scope scan: add `SENTINELF23BODY`/`SENTINELF23CANDIDATE`/`SENTINELF23TOKEN` sentinels and a grep guard that **fails the build on any `sms|whatsapp|twilio|waitlist` literal** in `backend/src/main/java` / `frontend/src` (SC-012 scope guard; the F14 grep-guard precedent — pure ASCII).

---

## Phase 2: Foundational (Blocking Prerequisites)

**⚠️ CRITICAL**: Every user story reads/writes the cascade fields and the new enums; no story work can begin until this phase is complete and the build is green.

- [X] T004 [P] Add cascade fields to `backend/src/main/java/com/cadence/domain/SchedulingRequest.java`: `bookedStartAt` (Instant), `confirmationRequestedAt` (Instant), `confirmTokenHash` (`@JsonIgnore @Field(value="confirmTokenHash", write=Field.Write.NON_NULL)`), `confirmationNotRequestable` (`@JsonIgnore` boolean), `candidateConfirmedAt` (Instant), `escalatedAt` (Instant), `noShowAt` (Instant) + getters/setters; extend `toString()` to also omit `confirmTokenHash` (data-model §1).
- [X] T005 [P] Add `confirmationLeadTime` + `unconfirmedEscalationDeadline` (nullable `Duration`) + accessors to `backend/src/main/java/com/cadence/domain/WorkspaceConfig.java` (data-model §3).
- [X] T006 [P] Append `CONFIRM_LINK` (`{{confirm_link}}`, URL-typed) to `backend/src/main/java/com/cadence/domain/MergeToken.java` (append-only).
- [X] T007 [P] Append `INTERVIEW_UNCONFIRMED` (value-free) to `backend/src/main/java/com/cadence/domain/RecruiterNotificationType.java` (append-only — after `CALENDAR_CLEANUP_INCOMPLETE`).
- [X] T008 [P] Append `NOSHOW_CONFIRMATION_REQUESTED`, `NOSHOW_ATTENDANCE_CONFIRMED`, `NOSHOW_UNCONFIRMED_ESCALATED` to `backend/src/main/java/com/cadence/domain/AuthEventType.java` (append-only, never reorder).
- [X] T009 [P] Append `ATTENDANCE_CONFIRMED` to `backend/src/main/java/com/cadence/domain/CandidateAuditOutcome.java` (append-only).
- [X] T010 Add `.set("bookedStartAt", slot.getStart())` to the existing `BOOKING→BOOKED` `findAndModify` Update (the one that already sets `manageTokenHash`/`bookedAt`/`lastOutcomeReason`, ~line 293 in `book()` — use the in-scope chosen `OfferedSlot.getStart()`, NOT a reconstructed value) in `backend/src/main/java/com/cadence/service/SlotReservationService.java`. This is the single live commit site covering both INITIAL and RESCHEDULE rounds (research D2). Depends on T004.
- [X] T011 Add to `backend/src/main/java/com/cadence/repository/SchedulingRequestRepository.java`: `findByConfirmTokenHash(...)` and three `Pageable`-capped explicit `@Query` cascade-stage finders over `{status:BOOKED, bookedStartAt:{$lte:?}}` + the stage null-field (request-due / escalate-due / no-show-due) — explicit `@Query`, NOT derived multi-criteria (the F12 `InvalidMongoDbApiUsageException` lesson). Depends on T004.
- [X] T012 Create `backend/src/main/java/com/cadence/config/migration/ChangeUnit014_NoShowIndexes.java` (order `"014"` off applied `"013"`): native `createIndex` `{status:1,bookedStartAt:1}` and unique **partial** `{confirmTokenHash:1}` `partialFilterExpression {confirmTokenHash:{$exists:true}}`; **backfill** `bookedStartAt` for existing `BOOKED` rows from `offeredSlots[chosenSlotId].start` (idempotent); targeted `dropIndex` rollback (never `dropIndexes()`). Depends on T004.
- [X] T013 Create `backend/src/main/java/com/cadence/service/NoShowCascadeService.java` shell: constructor-inject `SchedulingRequestRepository`, `ContactPermissionGate`, `EmailDispatchService`, `RecruiterNotificationService`, `AuthAuditService`, `CandidateAuditService`, `CandidateRateLimiter`, `TokenHasher`, `MongoTemplate`, `SchedulingProperties`, `AuthProperties`, `Clock`; declare no-op stubs `requestConfirmation(req,now)`, `escalateUnconfirmed(req,now)`, `stampNoShow(req,now)`, `confirmAttendance(rawToken,ip)` (filled per story). Depends on T004, T006–T009, T011.
- [X] T014 Create `backend/src/main/java/com/cadence/scheduler/NoShowDefenseScheduler.java`: `@Scheduled(fixedDelayString="${cadence.noshow.cascade-interval-ms:60000}")` + `@PostConstruct registerReplayAction("no-show-cascade", this::sweep)` + `sweep()` wrapped in `checkpoints.start/complete`; resolve `now` from the **injected `Clock`** (never `Instant.now()` — QA fix); run the three `Pageable`-capped finders over `now+cascadeQueryBound`, resolve each row's effective offsets `WorkspaceConfig.value ?? NoShowProperties.default` (read `WorkspaceConfig` per distinct workspace), Java-filter the per-workspace boundary, dispatch to the `NoShowCascadeService` stubs; logs counts + `.name()` only. Depends on T001, T011, T013.
- [X] T015 [P] Integration test `backend/src/test/java/com/cadence/scheduling/NoShowIndexTest.java`: assert `ChangeUnit014` created both indexes (incl. the partial filter) and backfilled `bookedStartAt` on a pre-existing `BOOKED` row. Depends on T012.

**Checkpoint**: schema/enums/changeset/repo/scheduler skeleton in place, build green, cascade is a no-op until stories fill the stubs.

---

## Phase 3: User Story 1 — Candidate confirms attendance (Priority: P1) 🎯 MVP

**Goal**: At the lead-time boundary the candidate gets one consent-gated `REMINDER_24H` with a confirm link; they confirm (no login) and the booking records it exactly once.

**Independent Test**: advance the test clock to the lead time → one `REMINDER_24H` dispatched → open the confirm link → POST confirm → `candidateConfirmedAt` set once, accessible success page, no PII in URL.

### Tests for User Story 1 (write first, must fail)

- [X] T016 [P] [US1] Unit (Mockito) `backend/src/test/java/com/cadence/scheduling/NoShowConfirmServiceTest.java`: `confirmAttendance` CAS idempotency (replay is a no-op returning the existing ack — **no additional email, no additional recruiter signal**, FR-007/US1 AC#3), status-before-time precedence, rate-limit; `requestConfirmation` contactable-vs-not branch (mints token + enqueues vs sets `confirmationNotRequestable`).
- [X] T017 [P] [US1] Integration (Testcontainers, `@Primary MutableClock`) `backend/src/test/java/com/cadence/scheduling/NoShowRequestCascadeTest.java`: stage 1 fires exactly one `REMINDER_24H` at the lead-time boundary (SC-001); a booking made inside the lead window fires at the next sweep (FR-004); a not-contactable candidate gets no email but `confirmationRequestedAt` is set + `confirmationNotRequestable=true` (FR-005); mid-task-restart replay sends no duplicate (SC-006); the **lost-reminder seam** — mock `EmailDispatchService.enqueue` to throw **after** the stage-1 CAS commits, then assert stage 2 (US2) still escalates (D8).
- [X] T018 [P] [US1] Contract (MockMvc + Testcontainers) `backend/src/test/java/com/cadence/scheduling/CandidateConfirmContractTest.java`: `POST /confirm` 200 + idempotent replay; **GET does not confirm** (FR-006); **status-before-time precedence** — byte-identical 400 across {unknown token, released-`CANCELLED`, erased (token `$unset`), `SUPERSEDED`}, 410 only for a still-`BOOKED` past interview, 429 on rate breach (SC-008); IDOR — a confirm token cannot act on another booking (FR-008); two bookings for one candidate → two distinct confirm tokens, confirm A ⇏ confirm B.
- [X] T019 [P] [US1] Frontend (Jasmine + axe-core) `frontend/src/app/features/booking/confirm-attendance.component.spec.ts`: 0 WCAG 2.2 AA violations across states (loading/confirm/confirmed/expired/invalid), no-login, interview time in local zone (DST label), all strings `$localize`, token held in memory only (not storage), never `console.error`-logged.

### Implementation for User Story 1

- [X] T020 [US1] Permit `CONFIRM_LINK` for `REMINDER_24H` (+ `REMINDER_1H`) in `backend/src/main/java/com/cadence/service/MergeTokenCatalogue.java` (data-model §4). Depends on T006.
- [X] T021 [US1] Edit the built-in `REMINDER_24H` body in `backend/src/main/java/com/cadence/service/BuiltInEmailTemplates.java`: **drop `{{reschedule_link}}`** and **add** a "Confirm attendance: {{confirm_link}}" CTA (else it renders `[[missing:reschedule_link]]` — Backend fix); keep `BuiltInTemplateCompletenessTest` green. Depends on T020.
- [X] T022 [US1] Implement `NoShowCascadeService.requestConfirmation` (stage 1): CAS `{_id,status:BOOKED,confirmationRequestedAt:null}` → set `confirmationRequestedAt`; if `gate.evaluate(...).permit()` → mint `confirmTokenHash` + `dispatch.enqueue(ws, candidateId, REMINDER_24H, "BASE", now, {confirm_link, interview_date, interview_time, time_zone, location, stage_name}, null)`; else set `confirmationNotRequestable=true`; audit `NOSHOW_CONFIRMATION_REQUESTED`. CAS-claim-then-enqueue (D8). In `backend/src/main/java/com/cadence/service/NoShowCascadeService.java`. Depends on T013, T021.
- [X] T023 [US1] Implement `NoShowCascadeService.confirmAttendance`: rate-limit (IP); resolve by `confirmTokenHash`; **status-before-time precedence** (not-found/cleared/superseded → invalid; not-`BOOKED` → invalid; `BOOKED` & `chosenStart` past → expired; else CAS set `candidateConfirmedAt`) using `chosenStart()` (not `bookedStartAt`); idempotent replay returns existing; audit `NOSHOW_ATTENDANCE_CONFIRMED` + `candidateAudit.append(... BOOKING_CHANGED, ATTENDANCE_CONFIRMED, "CANDIDATE")`. In `NoShowCascadeService.java`. Depends on T013.
- [X] T024 [US1] Add `POST /api/candidate/booking/{token}/confirm` to `backend/src/main/java/com/cadence/api/CandidateBookingController.java` (public-by-token, `no-store`, affirmative POST) calling `service.confirmAttendance(token, http.getRemoteAddr())`; add `ConfirmAttendanceResponse` to `backend/src/main/java/com/cadence/api/SchedulingDtos.java`; map outcomes to 200/410/400/429 in `SchedulingExceptionHandler` (reuse existing `TokenExpired`/`TokenInvalid`/`RateLimited`). Depends on T023.
- [X] T025 [US1] Extend the BOOKED-cancel erasure flip in `backend/src/main/java/com/cadence/service/CandidateErasureService.java` to also `$unset confirmTokenHash` (BOOKED branch only); the cascade halts via the `status:BOOKED` guard, the send-time consent re-gate is the dispatch backstop (research D9). Depends on T004.
- [X] T026 [P] [US1] Create the candidate confirm page `frontend/src/app/features/booking/confirm-attendance.component.ts` (+ template/scss) and a public route `/confirm?token=` in `frontend/src/app/app.routes.ts` (guard-free, the F13/F20 sibling); add `confirm(token)` to `frontend/src/app/features/booking/booking.service.ts`. Memory-only token, re-resolved on `ngOnInit`, `$localize`, local-zone DST label, WCAG-AA structure.
- [X] T027 [US1] §IX blocking gate for the new page: extend `frontend/src/testing/axe.ts`-driven per-state specs to the confirm component; add `…/confirm?token=lighthouse-demo` (+ confirmed + expired states) to `lighthouserc.json` `ci.collect.url[]`; extend `frontend/lighthouse/serve-with-stub.mjs` with canned `GET` + `POST /api/candidate/booking/<demo>/confirm` handlers **and** the SPA fallback (else the gate measures the vacuous `invalid` state — the F14 bug). Depends on T026.

**Checkpoint**: US1 fully functional — cascade requests confirmation and the candidate can confirm end-to-end (browser→DB). MVP demonstrable.

---

## Phase 4: User Story 2 — Recruiter alerted & one-tap release (Priority: P1)

**Goal**: An unconfirmed interview escalates to one coarse recruiter alert at the deadline; the recruiter releases the slot in one action (events removed, slot freed); a no-show signal is recorded.

**Independent Test**: leave a booking unconfirmed → advance past the deadline → one `INTERVIEW_UNCONFIRMED` + `escalatedAt` set → recruiter `POST .../release` → calendar events removed, slot re-selectable, booking `CANCELLED`, audited.

### Tests for User Story 2 (write first, must fail)

- [X] T028 [P] [US2] Integration (Testcontainers, test clock) `backend/src/test/java/com/cadence/scheduling/NoShowEscalationTest.java`: stage 2 raises exactly one `INTERVIEW_UNCONFIRMED` at the deadline + sets `escalatedAt` (SC-003); zero alert for a confirmed / cancelled / rescheduled-away booking; a not-contactable booking still escalates via the **same coarse** alert (FR-005, no oracle); **start-passes-mid-sweep** — stage 2 does NOT escalate a past interview (`bookedStartAt>now`) and stage 3 stamps `noShowAt` (spec edge); **confirm-after-escalation (US2 AC#3)** — an already-escalated (`escalatedAt` set) booking can still be confirmed → `candidateConfirmedAt` set, the booking-status read returns "confirmed" and no further release is prompted; idempotent across restart (SC-006).
- [X] T029 [P] [US2] Integration `backend/src/test/java/com/cadence/scheduling/NoShowReleaseTest.java`: recruiter release reuses `cancelByBooking` — events removed on all participants' stubs, claim `ACTIVE→RELEASED` + slot immediately re-selectable (SC-004), audit `SCHEDULING_CANCELLED`, candidate consent-gated `CANCELLATION` notice; a past interview is refused (`ineligible`); a cleanup failure surfaces `CLEANUP_INCOMPLETE` + recruiter alert (FR-012).
- [X] T030 [P] [US2] Concurrency `backend/src/test/java/com/cadence/scheduling/NoShowReleaseConcurrencyTest.java`: a gated `CountDownLatch` released after both threads resolve the booking but **before** they CAS (so they genuinely collide), `@RepeatedTest`, across: two releases / release-vs-confirm / release-vs-cancel → exactly one authoritative transition, no double cancellation, no duplicate notifications (SC-007).
- [X] T031 [P] [US2] Contract (MockMvc) `backend/src/test/java/com/cadence/scheduling/RecruiterReleaseContractTest.java`: `POST .../release` 5-role matrix (ADMIN/RECRUITER 200; HM/Interviewer/Read-only 403), workspace-scoped indistinguishable 404, 409 `no_active_booking`/`ineligible`.
- [X] T032 [P] [US2] E2E (Testcontainers, test clock, F10/F11 stubs — NOT Playwright, C7) `backend/src/test/java/com/cadence/scheduling/NoShowCascadeE2ETest.java`: scheduled fire → `REMINDER_24H` sent → unconfirmed → **assert persisted `escalatedAt` on the `schedulingRequests` row** → `INTERVIEW_UNCONFIRMED` raised → recruiter release → slot available again in MongoDB → audited (SC-005).

### Implementation for User Story 2

- [X] T033 [US2] Implement `NoShowCascadeService.escalateUnconfirmed` (stage 2): CAS `{_id,status:BOOKED,confirmationRequestedAt:{$ne:null},candidateConfirmedAt:null,escalatedAt:null}` (selected with `bookedStartAt>now` guard) → set `escalatedAt` + `notifications.notify(ws,candidateId,INTERVIEW_UNCONFIRMED)` + audit `NOSHOW_UNCONFIRMED_ESCALATED`. In `NoShowCascadeService.java`. Depends on T013.
- [X] T034 [US2] Implement `NoShowCascadeService.stampNoShow` (stage 3): CAS `{_id,status:BOOKED,candidateConfirmedAt:null,noShowAt:null}` (selected with `bookedStartAt<=now`) → set `noShowAt` (FR-016). In `NoShowCascadeService.java`. Depends on T013.
- [X] T035 [US2] **Reuse the existing `SchedulingService.cancelByRecruiter(workspaceId, actorMemberId, candidateId, ip)`** (verified, line 241) for release — it already resolves the authoritative `BOOKED` booking via `liveBooking` (scoped-404), throws `NoActiveBookingException` (409) for none, and `cancelByBooking(booking, false, actor)` already refuses a past interview (`IneligibleException`) and audits `SCHEDULING_CANCELLED`. **Do NOT add a new `releaseUnconfirmed` resolution method** (Backend review — it would duplicate `cancelByRecruiter`/`cancelByBooking`). The no-show classification is **derived at the F50 read** from `escalatedAt != null && candidateConfirmedAt == null`, not stamped at release. If a distinct release audit literal is wanted, pass it through `cancelByRecruiter`'s actor/outcome (no new cancel path). No new service code expected; this task is the decision + any thin delegation.
- [X] T036 [US2] Add `@PostMapping("/release")` → `POST /api/internal/candidates/{candidateId}/scheduling/release` (sibling of the existing `/cancel`, `/reschedule`; the controller is `@RequestMapping("/api/internal/candidates/{candidateId}/scheduling")` with class-level `@PreAuthorize("hasAnyRole('ADMIN','RECRUITER')")`) to `backend/src/main/java/com/cadence/api/SchedulingController.java`, calling `service.cancelByRecruiter(...)`; add `ReleaseResponse` to `SchedulingDtos.java` (or reuse `CancelBookingResponse`); the `NoActiveBooking`/`Ineligible`/scoped-404 mappings already exist in `SchedulingExceptionHandler`. Depends on T035.
- [X] T037 [P] [US2] Recruiter UI in `frontend/src/app/features/scheduling/` (+ `scheduling.service.ts`): "Unconfirmed" indicator + confirmation-status chip on the per-candidate booking-status view, and a "Release slot" action calling the release endpoint (internal screen — Lighthouse/WCAG N/A). Depends on T036.

**Checkpoint**: US1 + US2 deliver the full no-show defense loop (confirm → escalate → release).

---

## Phase 5: User Story 3 — Admin configures the cascade (Priority: P2)

**Goal**: An admin sets the per-workspace confirmation lead time and escalation deadline; the cascade honours them; defaults apply with no admin action.

**Independent Test**: set non-default offsets → cascade fires at the new boundaries (test clock); set `escalation ≥ lead` or `lead > queryBound` → rejected, prior settings stand.

### Tests for User Story 3 (write first, must fail)

- [X] T038 [P] [US3] Integration (Testcontainers, test clock) `backend/src/test/java/com/cadence/scheduling/NoShowConfigCascadeTest.java`: non-default per-workspace offsets shift the request + escalation boundaries; an un-customized workspace uses the documented defaults with zero admin action (SC-011).
- [X] T039 [P] [US3] Contract (MockMvc) `backend/src/test/java/com/cadence/workspace/NoShowSettingsContractTest.java`: valid update persists + survives restart; rejects `escalation ≥ lead` and `lead > cascadeQueryBound` (`invalid_config`, prior retained); a lead set **at** the bound is accepted and still swept; ADMIN-only (403 otherwise).

### Implementation for User Story 3

- [X] T040 [US3] Extend `backend/src/main/java/com/cadence/service/WorkspaceConfigService.java`: persist `confirmationLeadTime`/`unconfirmedEscalationDeadline` via targeted `$set`; a **cross-field** validator run **after** resolving each effective value (`wsValue ?? NoShowProperties.default`): both positive, `0 < escalation < lead ≤ cascadeQueryBound`; reject `invalid_config` (400), retain prior (FR-014). Depends on T005.
- [X] T041 [US3] Add the two `Duration` fields to the workspace-config settings-patch DTO + the config read response in `backend/src/main/java/com/cadence/api/` (the F03 update surface); validation errors mapped to 400. Depends on T040.

**Checkpoint**: all three stories independently functional.

---

## Phase 6: Polish & Cross-Cutting Concerns

- [X] T042 [P] Cross-cutting integration `backend/src/test/java/com/cadence/scheduling/RescheduleResetsCascadeTest.java`: confirm round 1 → F20 reschedule → the round-2 booking gets a **fresh** `REMINDER_24H` (not suppressed by the F22 outbox key — distinct `scheduledFor`) and round-1's `candidateConfirmedAt` does not satisfy the new time (FR-003).
- [X] T043 [P] Cross-cutting integration `backend/src/test/java/com/cadence/scheduling/NoShowDstTest.java`: a synthetic DST-crossing fixture + test clock — the lead/deadline offsets fire at the correct wall-clock instant across the transition (no hour drift), asserted on the absolute fire `Instant` (SC-013).
- [X] T044 [P] Security integration `backend/src/test/java/com/cadence/scheduling/ErasureDuringCascadeTest.java`: erase the candidate **between** a stage-1 CAS and the outbox dispatch → **zero email leaves the transport** (the F22 send-time consent re-gate) and the confirm token is unusable (`$unset`); audit entries survive (SC-009, FR-024).
- [X] T045 [P] Index/footgun integration `backend/src/test/java/com/cadence/scheduling/ConfirmTokenIndexTest.java`: two cleared-`confirmTokenHash` rows do not collide on the partial-unique index (the F01 present-as-null `write=NON_NULL` footgun).
- [X] T046 [P] PII log scan `backend/src/test/java/com/cadence/scheduling/NoShowLogPiiScanTest.java`: drive cascade/confirm/escalate/release at TRACE with `SENTINELF23*` candidate/token sentinels and assert zero occurrences across logs/audit/recruiter-notification/persisted docs (SC-010); value-free `INTERVIEW_UNCONFIRMED` (`.name()` only — the logstash `kv` footgun).
- [X] T047 Run the `specs/015-no-show-defense/quickstart.md` validation: full backend `gradlew test` green (incl. all new `com.cadence.scheduling.*`/`workspace.*` + `RbacEndpointInventoryTest` still green), `ng test --watch=false` + `ng build --configuration production` clean, and the `@lhci/cli` confirm-route audit non-vacuous.
- [X] T048 Multi-role sub-agent implementation review (≥3 roles: Backend, Security, QA) against the real working-tree diff (C6 / constitution Principle VI); apply or report all findings before closure. Verify CI PII/scope scan + ASCII (C5). Record the outcome in `specs/015-no-show-defense/plan.md`.

---

## Dependencies & Execution Order

### Phase dependencies

- **Setup (P1)**: no dependencies.
- **Foundational (P2)**: depends on Setup; **blocks all stories**. Within it: T004–T009 (domain/enums) are `[P]`; T010–T012 depend on T004; T013 depends on T004/T006–T009/T011; T014 depends on T001/T011/T013; T015 depends on T012.
- **US1 (P3)**, **US2 (P4)**, **US3 (P5)**: all depend on Foundational. US1 and US2 both fill `NoShowCascadeService` (T022/T023 vs T033/T034 — different methods, same file → sequential, not `[P]` across the two). US3 is independent of US1/US2. After Foundational, US1/US2/US3 can proceed in parallel by different developers (mind the shared `NoShowCascadeService` and `SchedulingDtos`/`SchedulingExceptionHandler` files).
- **Polish (P6)**: depends on the stories it asserts (T042/T043 need the cascade; T044 needs US1 stage-1 + erasure; T047/T048 need everything).

### Within each story

- Tests (T016–T019 / T028–T032 / T038–T039) are written FIRST and must fail before implementation.
- Service methods before controllers; controllers before frontend wiring.

### Parallel opportunities

- Setup: T001, T002, T003 all `[P]`.
- Foundational: T004–T009 `[P]` (distinct files); T015 `[P]` once T012 lands.
- Each story's test tasks are `[P]` (distinct test files).
- Frontend (T026, T037) parallel with their backend once the contract is fixed.
- Polish T042–T046 all `[P]` (distinct test files).

---

## Parallel Example: Foundational domain/enum tasks

```
# After Setup, launch the independent domain/enum edits together:
T004 SchedulingRequest cascade fields (domain/SchedulingRequest.java)
T005 WorkspaceConfig Duration fields (domain/WorkspaceConfig.java)
T006 MergeToken.CONFIRM_LINK (domain/MergeToken.java)
T007 RecruiterNotificationType.INTERVIEW_UNCONFIRMED (domain/RecruiterNotificationType.java)
T008 AuthEventType NOSHOW_* (domain/AuthEventType.java)
T009 CandidateAuditOutcome.ATTENDANCE_CONFIRMED (domain/CandidateAuditOutcome.java)
```

## Parallel Example: User Story 1 tests

```
T016 Unit confirm/request service test
T017 Integration request-cascade test
T018 Contract candidate-confirm test
T019 Frontend axe confirm-page spec
```

---

## Implementation Strategy

### MVP (US1 only)

1. Phase 1 Setup → 2. Phase 2 Foundational (CRITICAL — blocks all) → 3. Phase 3 US1 → **STOP & validate**: the cascade requests confirmation and a candidate confirms attendance browser→DB. Demonstrable MVP slice.

### Incremental delivery

US1 (confirm) → add US2 (escalate + release — completes the no-show *defense* loop) → add US3 (per-workspace tuning) → Polish (DST/erasure/reschedule-reset/PII cross-cutting tests + the C6 review). Each story is independently testable; US1+US2 together are the full backlog F23 value.

---

## Notes

- `[P]` = different files, no incomplete-dependency. The shared `NoShowCascadeService`, `SchedulingDtos`, `SchedulingExceptionHandler`, and `SchedulingController` files force sequential edits across US1/US2 tasks that touch them.
- The cascade scheduler reads the **injected `Clock`** so all timing tests are deterministic (no wall-clock sleeps; stamp `bookedStartAt`/cascade fields + advance the test clock).
- No new collection, no new runtime dependency, no broker (C2/C4); one Mongock changeset `ChangeUnit014` off `"013"`.
- Email-only (FR-023): the cascade dispatches `REMINDER_24H` + one in-app `INTERVIEW_UNCONFIRMED`; no SMS/WhatsApp/waitlist path (T003 grep guard, SC-012).
- Commit after each task or logical group; stop at any checkpoint to validate a story independently.

---

## Multi-role tasks review (2026-06-16) — applied

Reviewers: Backend/Architecture, QA, Delivery/Process — verdicts CHANGES-REQUESTED / APPROVE-WITH-NITS / APPROVE-WITH-NITS. Findings folded in:

- **Recruiter release endpoint path was wrong (Backend, MUST-FIX)**: the real `SchedulingController` is `@RequestMapping("/api/internal/candidates/{candidateId}/scheduling")`. **Fixed** → T036 + contract B1 + plan now use `POST /api/internal/candidates/{candidateId}/scheduling/release` (sibling of `/cancel`, `/reschedule`).
- **`releaseUnconfirmed` duplicated the existing `cancelByRecruiter` (Backend, MUST-FIX)**: `cancelByRecruiter(workspaceId, actorMemberId, candidateId, ip)` (line 241) already resolves the booking (scoped-404), 409s on none, and `cancelByBooking` already refuses a past interview + audits. **Fixed** → T035 re-scoped to **reuse** it (no new method); the no-show classification is derived at the F50 read.
- **US2 Acceptance Scenario #3 was untasked (QA, MUST-FIX)**: confirm-after-escalation-before-release. **Fixed** → folded into T028 (an already-`escalatedAt` booking can still be confirmed → status reads confirmed, no further release prompt).
- **Minor (Backend/QA/Process, SHOULD/NICE)**: T010 pins the exact CAS site + `slot.getStart()`; T016 asserts "no additional recruiter signal" on replay (US1 AC#3); T047/T048 reference concrete artifacts. The US1↔US2 shared-file coupling (`NoShowCascadeService`/`SchedulingDtos`/`SchedulingExceptionHandler`) is already handled as sequential (non-`[P]`) and documented.

Format: all tasks pass `- [ ] T### [P?] [US#?] description + path` (T047/T048 are run/review meta-tasks referencing their target artifacts). No remaining blocking items.

---

## Multi-role IMPLEMENTATION review (T048) — 2 loops, completed

**Loop 1** (Backend / Security / QA, against the real diff): APPROVE-WITH-NITS / SHIP-WITH-ONE-FIX / CHANGES-REQUESTED. Findings applied:
- **MUST/SHOULD (Backend+Security)**: the stage-1 token mint was a status-unguarded second write → an erasure race could resurrect a live `confirmTokenHash` on an erased row (GDPR residual) + a lost-credential crash window. **Fixed** → `requestConfirmation` evaluates the gate first and folds `confirmationRequestedAt` + (`confirmTokenHash` | `confirmationNotRequestable`) into ONE atomic `status:BOOKED` CAS.
- **SHOULD (Backend)**: stage-1 finder had no lower time bound → it emailed "confirm attendance" for PAST interviews. **Fixed** → `findConfirmationRequestDue` is now `bookedStartAt {$gt: now, $lte: bound}`.
- **QA coverage gaps**: added `NoShowCascadeExtraTest` (SC-004/005 escalate→release recovers slot + persisted `escalatedAt` + claim released; US2 AC#3 confirm-after-escalation; SC-011 per-workspace lead honoured; SC-013 DST absolute-instant), `NoShowLostReminderTest` (D8 lost-reminder still escalates), strengthened `NoShowReleaseConcurrencyTest` (exactly-one `SCHEDULING_CANCELLED` audit), and F23 `SchedulingIndexTest` methods (T015/T045).

**Loop 2** (verification): Backend+Security **APPROVE** (both fixes resolved, no regression); QA **APPROVE-WITH-NITS** (all 7 loop-1 gaps closed; the one remaining nit — untested `ChangeUnit014` index/collision — was then added and passes). No loop 3 needed.

Honest residuals (out of loop scope, pre-existing patterns): no true mid-restart checkpoint-replay test (double-`sweep()` proxies idempotency, SC-006); the PII scan asserts persisted docs not captured stdout (the `ci.yml` grep is the backstop, SC-010).

All 48 tasks `[X]`. Full backend suite + frontend (165/165) green; `ng build` clean.
