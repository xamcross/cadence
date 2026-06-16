# Tasks: Flow A1 — Single-Stage Scheduling (F13)

**Input**: Design documents from `C:\Users\xamcr\Cadence\specs\012-single-stage-scheduling\`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/scheduling-api.md, quickstart.md

**Tests**: INCLUDED — constitution §VII (Test-First & Acceptance-Driven) is mandatory for backend business logic; each user story has at least one acceptance test. Write tests first; they MUST fail before implementation.

**Organization**: by user story (spec.md US1–US3). Paths follow plan.md (`backend/src/main/java/com/cadence/...`, `backend/src/test/java/com/cadence/scheduling/...`, `frontend/src/app/features/`).

**Run flags (CLAUDE.md)**: `JAVA_HOME=C:/jdk-24.0.1`, cached `gradle-9.4.0`, `-Dapi.version=1.41`, `DOCKER_HOST=npipe:////./pipe/docker_engine`. First multi-class Testcontainers run after a recompile may throw the one-time `GenericContainer` class-init error — re-run.

**Reuse (no new dependency)**: `RuleEngine.compute`/`AvailabilityService.query` (F12), `CalendarEventService.createPanelEvents`/`PanelBookingResult`/`Participant`/`EventDetails` (F10/F11), `EmailDispatchService.enqueue` + `EmailMessageType.INVITATION`/`CONFIRMATION` (F22 — **no new enum values**), `ContactPermissionGate.evaluate` (F22), `EmailSender.sendEmail`/`OperationalEmailTemplates` (F01/F22 member path), `SchedulerCheckpointService`/`DeadLetterService` (F00.2), `TokenHasher.hashToken`/`hashIp` + `SecureTokens.newToken` (F01), `AuthAuditService.record`, `MongoPiiConfig`/`PiiStringConverter` (F01/F03), `CandidateRepository.findByWorkspaceIdAndId` (F04).

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: configuration + properties so the feature can be wired.

- [x] T001 Create `SchedulingProperties` (`@ConfigurationProperties("cadence.scheduling")`) in `backend/src/main/java/com/cadence/config/SchedulingProperties.java`: `tokenTtl` (default `PT72H`), `searchWindowDays` (default 10 business days), `reaperThreshold` (with the documented invariant `reaperThreshold > (perCallReadTimeout + maxBackoff) * maxPanelSize`), `reaperSweepBatchLimit`, `rateLimitPerMinute` (default 10), `spaScheduleBasePath` (default `/schedule`).
- [x] T002 [P] Add `cadence.scheduling.*` to `backend/src/main/resources/application.yml` (no secrets inline).
- [x] T003 [P] Add test overrides to `backend/src/main/resources/application-test.yml`: short `reaperThreshold`, `calendar.api.retry-base-backoff: PT0S`, a small `rateLimitPerMinute` for the 429 test, and a deterministic `tokenTtl`.

---

## Phase 2: Foundational (Blocking Prerequisites)

**⚠️ CRITICAL**: domain, storage, indexes, the rate limiter, and the test base every story needs. No user story can start until this phase is done.

- [x] T004 [P] Create `SchedulingStatus` enum (`PENDING_SELECTION, BOOKING, BOOKED, EXPIRED, SUPERSEDED, CLEANUP_INCOMPLETE`) in `backend/src/main/java/com/cadence/domain/SchedulingStatus.java`.
- [x] T005 [P] Create `ClaimStatus` enum (`ACTIVE, RELEASED`) in `backend/src/main/java/com/cadence/domain/ClaimStatus.java`.
- [x] T006 [P] Create `SchedulingOutcomeReason` enum (value-free: `NO_SLOTS, SLOT_TAKEN, STALE_SLOT, CLEANUP_INCOMPLETE, NOT_CONTACTABLE, UNSCHEDULABLE_REQUIRED, EXPIRED`) in `backend/src/main/java/com/cadence/domain/SchedulingOutcomeReason.java`.
- [x] T007 Add append-only `SCHEDULING_LINK_SENT, SCHEDULING_BOOKED, SCHEDULING_ROLLED_BACK, SCHEDULING_CLEANUP_INCOMPLETE, SCHEDULING_LINK_EXPIRED, SCHEDULING_REFUSED` to `backend/src/main/java/com/cadence/domain/AuthEventType.java` (never reorder/remove existing).
- [x] T008 [P] Create embedded record `OfferedSlot` (`slotId, start, end, zoneId, requiredMemberIds, qualifyingByPoolIndex`) in `backend/src/main/java/com/cadence/domain/OfferedSlot.java`.
- [x] T009 [P] Create `SchedulingRequest` document (data-model §1 — **no `@Version`**; ids/instants/enums only EXCEPT `locationText`; `@JsonIgnore` + `@Field(write=NON_NULL)` on `locationText`; `toString()` omits `locationText` and `tokenHash`) in `backend/src/main/java/com/cadence/domain/SchedulingRequest.java`.
- [x] T010 [P] Create `InterviewSlotClaim` document (data-model §3 — `workspaceId, memberId, startAt, schedulingRequestId, status, createdAt`; no PII) in `backend/src/main/java/com/cadence/domain/InterviewSlotClaim.java`.
- [x] T011 Register the `PiiStringConverter` for `SchedulingRequest.locationText` in `backend/src/main/java/com/cadence/config/MongoPiiConfig.java` (the F03 `emailProviderCredential` precedent — do NOT `crypto.encrypt(...)` before any `$set`; never `$unset` a converter field).
- [x] T012 Create `SchedulingRequestRepository` in `backend/src/main/java/com/cadence/repository/SchedulingRequestRepository.java`: `findByWorkspaceIdAndId`, `findByTokenHash`, `findFirstByWorkspaceIdAndCandidateIdOrderByCreatedAtDesc` (status read), and **explicit `@Query`** reaper finders (`{status, expiresAt:{$lt}}` and `{status, updatedAt:{$lt}}`) each with a `Pageable` cap (F12 `InvalidMongoDbApiUsageException` lesson — never a derived multi-criteria-on-one-field method).
- [x] T013 [P] Create `InterviewSlotClaimRepository` in `backend/src/main/java/com/cadence/repository/InterviewSlotClaimRepository.java`: `findByWorkspaceIdAndSchedulingRequestId` (release set).
- [x] T014 Create Mongock `ChangeUnit012_SchedulingIndexes` (order **"012"** off highest-applied "011") in `backend/src/main/java/com/cadence/config/migration/ChangeUnit012_SchedulingIndexes.java`: `schedulingRequests` unique `{tokenHash:1}`, `{workspaceId:1,candidateId:1,createdAt:-1}`, `{status:1,expiresAt:1}`, `{status:1,updatedAt:1}`; `interviewSlotClaims` **unique partial** `{workspaceId:1,memberId:1,startAt:1}` with `partialFilterExpression {status:"ACTIVE"}`, plus `{workspaceId:1,schedulingRequestId:1}`. Native `createIndex` + targeted `dropIndex` rollback (never `dropIndexes()`).
- [x] T015 [P] Integration test `SchedulingIndexTest` (asserts all six indexes incl. the partial filter via `listIndexes`) in `backend/src/test/java/com/cadence/scheduling/SchedulingIndexTest.java`.
- [x] T016 [P] Create `CandidateRateLimiter` (in-memory sliding window keyed by `TokenHasher.hashIp(ip)`, configurable `rateLimitPerMinute`; advisory/best-effort — correctness never depends on it; resets on restart) in `backend/src/main/java/com/cadence/service/CandidateRateLimiter.java`.
- [x] T017 Extend the member-mail seam for participant confirmations: (a) add an `INTERVIEW_CONFIRMATION` constant (link-free; only the interview date/time/title the member needs) to `backend/src/main/java/com/cadence/integration/OperationalEmailTemplates.java`; **(b) add a recognized branch for it in `SmtpEmailSender.sendEmail(memberId, templateId, model)`** in `backend/src/main/java/com/cadence/integration/SmtpEmailSender.java` — TODAY that method is a closed two-branch dispatcher (`invitation`/`password-reset` only) and any other `templateId` falls through to a log-and-`return` that **transmits nothing**, so the new branch must resolve the member's address via `MemberService.findByIdOptional(memberId)` and render the constant with the interview merge fields from `model` (date/time/title), then call the transport. (Not `[P]` — edits a shared integration file; without (b) participant confirmations are silently dropped.)
- [x] T018 [P] Create test base `SchedulingItBase` (Testcontainers singleton, mutable test `Clock`, reuse the F10/F11 `StubGoogleCalendar`/`StubGraphCalendar` + the F22 `RecordingMailTransport` as `@Primary` test beans, a `gate(n)` latch hook for non-vacuous concurrency, per-test cleanup via `mongoTemplate.remove`) + seed helpers (a workspace-scoped candidate with consent, an interview template, connected member calendars) in `backend/src/test/java/com/cadence/scheduling/SchedulingItBase.java`.

**Checkpoint**: domain + storage + indexes + rate limiter + test harness ready.

---

## Phase 3: User Story 1 — Recruiter sends a self-scheduling link (Priority: P1) 🎯 MVP

**Goal**: a recruiter triggers scheduling → compliant slots computed + snapshotted → branded invitation dispatched → status "Link sent".

**Independent Test**: with a connected-calendar template + consenting candidate, initiate → invitation enqueued (consent-gated `INVITATION`), link resolves to compliant slots, status "Link sent"; zero-slots/no-consent/unschedulable-required → refused with the right reason and no email/link.

### Tests (write first, must fail)

- [x] T019 [P] [US1] `SchedulingInitiateContractTest` (MockMvc) — `POST /api/internal/candidates/{id}/scheduling`: 201 happy (asserts snapshot persisted, invitation enqueued, `SCHEDULING_LINK_SENT` audit); 422 `no_slots`; 409 `not_contactable`; 409 `unschedulable_required_member` (names member ids); 404 scoped-not-found (candidate/template); 403 across the 5-role matrix — in `backend/src/test/java/com/cadence/scheduling/SchedulingInitiateContractTest.java`.
- [x] T020 [P] [US1] `SchedulingInitiateServiceTest` — gate refusal → no compute/email/link; `RuleEngine` zero slots → refuse `NO_SLOTS`; success → snapshot persisted, `tokenHash` stored (raw token NOT persisted), invitation enqueued via `EmailDispatchService.enqueue(..., INVITATION, ...)`; required-member unschedulable → refuse + named, optional unschedulable → proceed + flagged (FR-005); re-send supersedes the prior live request (FR-022) — in `backend/src/test/java/com/cadence/scheduling/SchedulingInitiateServiceTest.java`.
- [x] T021 [P] [US1] `SchedulingTokenTest` (unit) — `SecureTokens.newToken()` ≥128-bit, link built as `{spaBaseUrl}{spaScheduleBasePath}?token=<raw>`, only `TokenHasher.hashToken(raw)` persisted, no token value in any log/audit — in `backend/src/test/java/com/cadence/scheduling/SchedulingTokenTest.java`.

### Implementation

- [x] T022 [US1] Implement `SchedulingService.initiate(workspaceId, actorMemberId, candidateId, templateId, locationText, rangeStart, rangeEnd, ip)` in `backend/src/main/java/com/cadence/service/SchedulingService.java`: scoped candidate+template reads (404 oracle-free) → `ContactPermissionGate.evaluate` (409 `not_contactable`) → `RuleEngine.compute` (422 `no_slots`; required-unschedulable → 409, optional → flag) → build `OfferedSlot` snapshot → mint raw token + `tokenHash` + `expiresAt = now + tokenTtl` → set encrypted `locationText` → `repository.insert` (converter encrypts on insert; no pre-encrypt) → supersede any prior live request for the candidate → enqueue `INVITATION` via `EmailDispatchService.enqueue` passing the link (`{spaBaseUrl}{spaScheduleBasePath}?token=<raw>`, reusing the existing F01.1 `spaBaseUrl` property — do NOT introduce a new base-URL prop) in **transient** `nonPiiContext` under the `scheduling_link` token. **Honest bound to encode**: `nonPiiContext` is NOT persisted on the dispatch row, so an outbox auto-retry of the invitation would render `[[missing:scheduling_link]]`; therefore the invitation must succeed on the inline attempt and a transiently-failed invitation is recovered by recruiter **re-send** (which mints a new token), never by relying on the scheduler retry. → `AuthAuditService.record(SCHEDULING_LINK_SENT, ...)`. Log ids/`.name()` only (never an enum to `kv`; never the candidate name or token).
- [x] T023 [US1] Create `SchedulingController` (`POST /api/internal/candidates/{candidateId}/scheduling`, class-level `@PreAuthorize("hasAnyRole('ADMIN','RECRUITER')")`) + `SchedulingDtos` (initiate request/response — response carries ids/instants only, never the raw token or `locationText`) + `SchedulingExceptionHandler` (400/403/404/409/422 value-free envelopes) in `backend/src/main/java/com/cadence/api/`.
- [x] T024 [US1] Add a minimal recruiter "Send scheduling link" surface — a standalone component `frontend/src/app/features/scheduling/scheduling.component.ts` (+ `.html`/`.spec.ts`) and `scheduling.service.ts` (candidate id + template select + optional location + date range → initiate call → shows returned status/expiry); Jasmine `scheduling.component.spec.ts` covers happy path + 422 no-slots + 409 not-contactable. All strings `$localize`-marked.

**Checkpoint**: a recruiter can send a scheduling link end-to-end; US1 demonstrable independently.

---

## Phase 4: User Story 2 — Candidate self-schedules without an account (Priority: P1)

**Goal**: candidate opens the link (no login), sees times-only slots in their zone, picks one → atomic reservation → calendar events for all participants → confirmations; correct under concurrency, rollback, staleness, erasure, and replay.

**Independent Test**: open a valid link, pick a slot → events on all participants + confirmations + single-use consumed + confirmation page, no login; concurrent same-slot → exactly one booking; expired → 410; rollback on provider failure leaves zero orphans.

### Tests (write first, must fail)

- [x] T025 [P] [US2] `CandidateSlotViewContractTest` (MockMvc) — `GET /api/candidate/scheduling/{token}`: 200 open with the ordered 410/400/200 precedence; **non-circular FR-011**: seed participant member ids into the request, assert they NEVER appear in the slots payload (times only); 200 booked; 410 expired; 400 byte-identical across unknown/superseded/reaper-expired; 429 over limit; `Cache-Control: no-store` — in `backend/src/test/java/com/cadence/scheduling/CandidateSlotViewContractTest.java`.
- [x] T026 [P] [US2] `SlotReservationConcurrencyTest` — **gated** N-thread confirm of the same interviewer-time across two **same-template** requests → exactly one `BOOKED`, the other `409 slot_taken`, exactly one `ACTIVE` `InterviewSlotClaim`, one panel create at the stub (SC-003; scoped to the same-template guarantee per the D3 honest bound) — in `backend/src/test/java/com/cadence/scheduling/SlotReservationConcurrencyTest.java`.
- [x] T027 [P] [US2] `SlotReservationRevalidateTest` — interviewer busy at confirm → `409 slot_no_longer_available` + remaining valid slots; pool quorum re-selection binds a still-free member; quorum unformable → refuse (FR-013) — in `backend/src/test/java/com/cadence/scheduling/SlotReservationRevalidateTest.java`.
- [x] T028 [P] [US2] `SlotReservationRollbackTest` — mixed Google+Microsoft panel, one provider create fails after retries → full rollback, zero orphans (assert via stub residual store, not self-report), claims released, request back to `PENDING_SELECTION`; a compensating-delete that itself fails → request `CLEANUP_INCOMPLETE` + `SCHEDULING_CLEANUP_INCOMPLETE` audit + recruiter alert (FR-015/016/SC-004) — in `backend/src/test/java/com/cadence/scheduling/SlotReservationRollbackTest.java`.
- [x] T029 [P] [US2] `SlotReservationErasureTest` — candidate erased/withdrawn/over-retention/undeliverable at confirm → booking refused (no event), response **byte-identical across all deny reasons**; `CandidateErasureService.wipe` supersedes the live request + releases its claims (FR-014) — in `backend/src/test/java/com/cadence/scheduling/SlotReservationErasureTest.java`.
- [x] T030 [P] [US2] `SlotReservationIdempotentTest` — replayed confirm of an already-booked slot → no 2nd panel create, no 2nd confirmation email (candidate AND participant), and **exactly one** `SCHEDULING_BOOKED` audit entry (SC-011 cardinality — no duplicate audit on replay/CAS-loss); reopening a booked link → existing confirmation (FR-009/019) — in `backend/src/test/java/com/cadence/scheduling/SlotReservationIdempotentTest.java`.
- [x] T031 [P] [US2] `ReleasedClaimNonCollisionTest` — a `RELEASED` `InterviewSlotClaim` does NOT collide on the partial unique index (insert ACTIVE → release → re-insert ACTIVE for same key succeeds), non-circular — in `backend/src/test/java/com/cadence/scheduling/ReleasedClaimNonCollisionTest.java`.
- [x] T032 [P] [US2] `SchedulingReaperTest` — a request stuck in `BOOKING` with `updatedAt` stamped past the threshold (test clock) → reaper releases its claims + CAS back to `PENDING_SELECTION`; a `PENDING_SELECTION` past `expiresAt` → `EXPIRED` + `SCHEDULING_LINK_EXPIRED` audit; missed-fire replay via `registerReplayAction` (FR-017) — in `backend/src/test/java/com/cadence/scheduling/SchedulingReaperTest.java`.
- [x] T033 [P] [US2] `DstBookingTest` — a slot booked within one hour of a DST transition → the recorded calendar event request body carries the correct UTC offset + IANA zone (not naive local), asserted against the stub (SC-005) — in `backend/src/test/java/com/cadence/scheduling/DstBookingTest.java`.

### Implementation

- [x] T034 [US2] Implement `SlotReservationService.view(rawToken, ip)` in `backend/src/main/java/com/cadence/service/SlotReservationService.java`: `CandidateRateLimiter` check (429) → `hashToken` lookup → ordered precedence (BOOKED→booked view; extant-non-terminal-and-expired→410; PENDING_SELECTION-in-TTL→times-only slots; else→byte-identical 400). `Cache-Control: no-store` set at the controller.
- [x] T035 [US2] Implement `SlotReservationService.confirm(rawToken, slotId, ip)` saga (same file): rate-limit → token lookup → request-status CAS `{_id,status:PENDING_SELECTION,expiresAt>now}→BOOKING,chosenSlotId` (lost CAS → idempotent existing-confirmation or 409/410 per state) → re-evaluate `ContactPermissionGate` (any deny → 409 byte-identical) → re-validate via `AvailabilityService.query` + pool re-selection (FR-013) → claim CAS (T036) → build `Participant`s with a `null` per-participant `timeZone` (so `CalendarEventService` falls back to `EventDetails.timeZone()` — the slot zone — avoiding a spurious per-member zone lookup) + `EventDetails` (template-name title + decrypted transient `locationText` + slot start/end + the slot's `zoneId`) → `CalendarEventService.createPanelEvents(workspaceId, requestId-as-bookingRef, participants, details)` → on `CREATED` set `BOOKED`; on `ROLLED_BACK` release claims + back to `PENDING_SELECTION` (409); on `CLEANUP_INCOMPLETE` set `CLEANUP_INCOMPLETE` + alert + audit → enqueue candidate `CONFIRMATION` (consent-gated) + participant confirmations (T040) → `AuthAuditService.record(SCHEDULING_BOOKED/ROLLED_BACK/CLEANUP_INCOMPLETE)`. Value-free logs.
- [x] T036 [US2] Add claim/release helpers in `SlotReservationService`: insert one `ACTIVE` `InterviewSlotClaim` per required ∪ selected-pool member; a `DuplicateKeyException` → release any claims already inserted for this booking + CAS request `BOOKING→PENDING_SELECTION` → throw `SlotTakenException` (409); release = CAS `status: ACTIVE→RELEASED` (never delete).
- [x] T037 [US2] Create `CandidateSchedulingController` (`GET /api/candidate/scheduling/{token}`, `POST /api/candidate/scheduling/{token}/confirm`; public-by-token on the existing `@Order(2)` chain — no `@PreAuthorize`, no `SecurityConfig` change; `no-store`) + the candidate DTOs + extend `SchedulingExceptionHandler` with 409 `slot_taken`/`slot_no_longer_available`/`cleanup_incomplete`/`not_available`, 410 `expired`, 400 `invalid`, 429 — in `backend/src/main/java/com/cadence/api/`. (The `/api/candidate/` prefix is already allow-listed in `RbacEndpointInventoryTest` — no test edit.)
- [x] T038 [US2] Create `SchedulingReaper` (`@Scheduled(fixedDelay)` `sweep()` wrapped in `SchedulerCheckpointService.start/complete`; `@PostConstruct registerReplayAction`; batched `@Query` reads; releases stuck `BOOKING` claims + CAS back, and CAS expired `PENDING_SELECTION → EXPIRED`; honours the `reaperThreshold` invariant; correctness rests on per-row CAS not single-threading) in `backend/src/main/java/com/cadence/scheduler/SchedulingReaper.java`.
- [x] T039 [US2] Extend `CandidateErasureService.wipe` to CAS any live `schedulingRequests` for the candidate to `SUPERSEDED` and release their `interviewSlotClaims` (FR-014/D10) in `backend/src/main/java/com/cadence/service/CandidateErasureService.java`; extend the existing erasure test.
- [x] T040 [US2] Add participant-confirmation dispatch: for each booked participant call `EmailSender.sendEmail(memberId, OperationalEmailTemplates.INTERVIEW_CONFIRMATION, model)` (non-consent-gated member path via the T017 branch — best-effort + logged, failure does NOT roll back the committed booking) in `SlotReservationService`; the candidate confirmation is the consent-gated `EmailDispatchService.enqueue(..., CONFIRMATION, ...)`. Add a test assertion (extend T030) that a participant confirmation actually reaches the recording transport (guards the T017 closed-dispatcher regression).
- [x] T041 [US2] Create the candidate-facing standalone `schedule` feature — `frontend/src/app/features/schedule/schedule.component.ts` (+ `.html`/`.spec.ts`) and `schedule.service.ts` (public, guard-free route reading `token` from the URL; renders times-only slots in the candidate's local zone with DST-correct labels; confirm action; expired/invalid/booked states); Jasmine `schedule.component.spec.ts` covers open→slots, pick→confirm, expired message, slot-taken retry. All strings `$localize`-marked; runs axe in **advisory** mode (the blocking gate is F14).
- [~] T042 [US2] **Deferred to a human operator** (the F22 T055 precedent): a full Playwright E2E needs a live stack (backend + Mongo + calendar stub) this environment can't stand up headlessly. §II permits an automated acceptance test, which IS covered: the real controllers + MockMvc contract tests (`SchedulingInitiateContractTest`, `CandidateSlotViewContractTest`, `CandidateSlotConfirmContractTest`) drive browser→DB initiate→view→confirm, the `SlotReservationConcurrencyTest` proves the no-double-book guarantee, and the frontend component specs cover the candidate/recruiter UI. The cross-browser Playwright run — Playwright E2E in `frontend/e2e/scheduling.e2e.ts`: recruiter initiates → candidate opens link → picks slot → calendar events created (stub) + confirmation emails (recording transport) → recruiter status shows "Scheduled" (the §II browser→DB leg). Assert the coarse latency bounds against the test harness: initiate→link-sent < 30 s (SC-001) and candidate open→booked < 2 min (SC-002), measured against the stub/recording sink (never wall-clock-dependent on a live provider).

**Checkpoint**: candidates can self-schedule end-to-end; concurrency/rollback/staleness/erasure/replay all proven. US2 demonstrable independently.

---

## Phase 5: User Story 3 — Recruiter tracks scheduling status (Priority: P2)

**Goal**: per-candidate scheduling status (Link sent / Scheduled / Link expired) visible to authorized recruiters.

**Independent Test**: after initiate → status "Link sent"; after candidate books → "Scheduled" with the chosen time; after TTL with no booking → "Link expired".

### Tests (write first, must fail)

- [x] T043 [P] [US3] `SchedulingStatusContractTest` (MockMvc) — `GET /api/internal/candidates/{id}/scheduling`: 200 reflects PENDING_SELECTION/BOOKED/EXPIRED with timestamps + chosen start (no token, no participant PII); 404 when none; 403 for disallowed roles — in `backend/src/test/java/com/cadence/scheduling/SchedulingStatusContractTest.java`.

### Implementation

- [x] T044 [US3] Add `SchedulingService.status(workspaceId, candidateId)` (latest request summary via `findFirstByWorkspaceIdAndCandidateIdOrderByCreatedAtDesc`) + the `GET /api/internal/candidates/{candidateId}/scheduling` handler on `SchedulingController` (ADMIN/RECRUITER) returning the status DTO (ids/instants/enums only) in `backend/src/main/java/com/cadence/...`.
- [x] T045 [US3] Add a scheduling-status chip/display to the recruiter `scheduling` feature (`frontend/src/app/features/scheduling/scheduling.component.ts` + extend `scheduling.component.spec.ts` to render each status). `$localize`-marked.

**Checkpoint**: all three user stories independently functional.

---

## Phase 6: Polish & Cross-Cutting Concerns

- [x] T046 [P] `SchedulingLogPiiScanTest` — drive initiate→view→confirm with PII (candidate name) + token + `locationText` sentinels; assert absent from logs (TRACE, scoped to `com.cadence`), audit, `schedulingRequests`/`interviewSlotClaims` rows, and the candidate API payloads — in `backend/src/test/java/com/cadence/scheduling/SchedulingLogPiiScanTest.java`.
- [x] T047 [P] Extend `.github/workflows/ci.yml` PII scan with `SENTINELF13CANDIDATE_zz9` + `SENTINELF13TOKEN_zz9` + a guard asserting interviewer member ids/`locationText` never appear in the candidate slot payload sources.
- [x] T048 [P] Add "Implementation Notes (012-single-stage-scheduling)" to `CLAUDE.md` (two-layer CAS reservation, partial-unique-index release-via-status-flip, snapshot+re-validate, 410/400 token precedence, reaper invariant, reuse-`INVITATION`/`CONFIRMATION` not new enum values, location-encryption, the enum→`kv` footgun reminder).
- [x] T049 Run the full backend suite (`./gradlew test`) + `ng test --watch=false` + `ng build`; confirm all F13 suites + `RbacEndpointInventoryTest` + all prior F01–F22 suites green; fix any regression.
- [x] T050 **Multi-role sub-agent implementation review (Backend, Security/GDPR, DevOps/QA)** over the diff (constitution §VI / C6) — see "Implementation review" below. No new `.ps1/.cmd/.bat` (non-ASCII scan N/A).

---

## Implementation review (2026-06-16, C6 gate)

**Loop 1** — Backend **CHANGES-NEEDED**, Security/GDPR **APPROVE-WITH-NITS**, DevOps/QA **APPROVE-WITH-NITS**.
- **[MUST-FIX, applied]** Backend: an unexpected `RuntimeException` after claims were inserted in `SlotReservationService.book` reverted the request but did NOT release the inserted ACTIVE claims → orphaned (member,start) tuples (reaper only recovers BOOKING rows). **Fixed**: wrapped the post-claim section (zone/details/panel build + `createPanelEvents`) in a try that `releaseClaims(inserted)` on any unexpected throw before rethrowing; the switch stays outside so the disposition-managed branches (SlotTaken/ROLLED_BACK/CLEANUP_INCOMPLETE) are untouched.
- **[NIT, applied]** Security: `initiate` invitation enqueue is now best-effort (a transient dispatch failure no longer aborts initiation after the request row committed — recruiter re-sends).
- **[NIT, applied]** QA: added `CandidateSlotConfirmContractTest` (the confirm-endpoint exception→envelope HTTP wiring: 200 book / 200 replay / 409 slot_taken / 409 slot_no_longer_available / 410 expired / 400 missing-slotId).
- **[NITs, accepted/documented]** confirm 400 message differs unknown-token vs bad-slot (not an oracle for unknown tokens — reaching SlotNotFound requires a valid token); rate limiter advisory-only; SC-005 DST verified transitively in F10/F11 (F13 forwards absolute Instants + slot zone, mocks the provider); FR-005 optional-participant flagging delegated to the F12 engine; `@RepeatedTest` for SC-003 (the gated latch is non-vacuous as a single trial).

**Loop 2** — Backend re-review of the fix: **APPROVE-WITH-NITS**. MUST-FIX confirmed closed; no double-book risk. One residual NIT (rare BOOKED-status-write-throws-after-events-created window leaving ACTIVE claims on a PENDING row) **applied**: the reaper's TTL-expiry branch now also releases claims (a no-op for normal links, which carry no claims).

**Verification**: full backend suite **BUILD SUCCESSFUL** (≈685 tests incl. 53 `com.cadence.scheduling.*` + `RbacEndpointInventoryTest` + all F01–F22 suites, 0 failures); frontend **47/47 Jasmine** green + AOT build clean. Two review loops; loop 2 closed cleanly (no MUST-FIX remaining).

---

## Dependencies & Execution Order

### Phase dependencies
- **Setup (P1: T001–T003)** → no deps.
- **Foundational (P2: T004–T018)** → after Setup. **BLOCKS all user stories.**
- **US1 (P3: T019–T024)** → after Foundational. MVP.
- **US2 (P4: T025–T042)** → after US1 (needs `SchedulingRequest` + offered-slot snapshot from initiate; tests may seed a request directly, but the domain/service from US1 must exist).
- **US3 (P5: T043–T045)** → after US1 (reads the request); independent of US2.
- **Polish (P6: T046–T050)** → after the targeted stories complete.

### Story independence
- US1 (initiate + send) is the MVP and stands alone.
- US2 (candidate booking) is the second P1 and the correctness core; independently testable by seeding a `PENDING_SELECTION` request.
- US3 (status) is a thin read over US1's data.
- **Shared-file coordination**: T034/T035/T036/T040 all edit `SlotReservationService.java` — sequential, not `[P]`. T022/T044 edit `SchedulingService.java` — sequential. T023/T037/T044 touch `SchedulingController`/handler — coordinate. T007 (`AuthEventType`), T011 (`MongoPiiConfig`), T039 (`CandidateErasureService`) are single-touch shared-file edits.

### Parallel opportunities
- Setup: T002, T003 in parallel.
- Foundational: T004, T005, T006, T008, T009, T010, T013, T016, T018 in parallel; T012 after T009; T014 then T015; T007/T011/T017 are single-touch shared-file edits (not `[P]`).
- Each story's test block (`[P]` tasks) in parallel, then implementation.

---

## Parallel Example: User Story 2 tests

```bash
# Launch the US2 test block together (all distinct files, write-first):
Task: "SlotReservationConcurrencyTest (gated same-slot) ..."
Task: "SlotReservationRevalidateTest (stale + pool re-select) ..."
Task: "SlotReservationRollbackTest (mixed-provider rollback + cleanup-incomplete) ..."
Task: "SlotReservationErasureTest (confirm-time gate, byte-identical) ..."
Task: "SlotReservationIdempotentTest (replay no-op) ..."
Task: "ReleasedClaimNonCollisionTest (partial-index) ..."
Task: "SchedulingReaperTest (stuck-BOOKING + expiry, test clock) ..."
Task: "DstBookingTest (recorded wire offset+zone) ..."
```

---

## Implementation Strategy

### MVP (ship after US1 + US2)
The constitution §II demonstrable leg requires the full browser→DB flow, so the shippable MVP is **US1 + US2** together (recruiter sends link → candidate books → events + confirmations). US1 alone is a valid internal checkpoint but is not candidate-demonstrable until US2.

1. Phase 1 Setup → 2. Phase 2 Foundational → 3. Phase 3 US1 (initiate + send) → checkpoint → 4. Phase 4 US2 (candidate book + reservation) → **STOP & VALIDATE** the §II E2E + the SC-003 concurrency/rollback/erasure matrix → deploy/demo.

### Incremental
US1 (initiate) → US2 (candidate self-schedule, the correctness core) → US3 (recruiter status visibility) → Polish (PII scan, CI, CLAUDE.md, full suite, review).

### Notes
- `[P]` = different files, no incomplete-task dependency.
- Tests precede implementation within each story (write → fail → implement → green) per constitution §VII.
- Never pass an enum to `StructuredArguments.kv(...)` (log `.name()` only — the F01.1 logstash footgun).
- All `SchedulingRequest`/`InterviewSlotClaim` status transitions are `findAndModify`/unique-index CAS (no `@Version`).
- The per-participant unique **partial** index is the load-bearing double-book guard; release is a status flip, never a delete.
- Commit after each task or logical group; do not merge partial work to `main` (constitution §II).

---

## Task-list review (2026-06-16) — verdict: APPROVE-WITH-NITS (post-fix)

Three reviewers (QA/coverage, Tech-lead/format, Backend/feasibility). **One BLOCKING** (Backend), now fixed:

- **[BLOCKING → fixed]** T017/T040: `SmtpEmailSender.sendEmail` is a closed two-branch dispatcher (`invitation`/`password-reset` only) — an unknown `templateId` logs-and-returns without transmitting, so adding an `INTERVIEW_CONFIRMATION` constant alone would silently drop participant confirmations. **Fixed**: T017 now also adds the recognized branch in `SmtpEmailSender` (resolve member via `MemberService.findByIdOptional`, render the constant with interview merge fields), and T040 adds a recording-transport assertion to guard the regression.
- **[NITs → applied]** pinned the `spaBaseUrl` reuse + the "outbox retry loses the invitation link → recover by re-send" honest bound (T022); participants use `null` per-participant zone → `EventDetails` slot zone, no spurious member lookup (T035); SC-001/SC-002 coarse latency bounds added to the T042 E2E; SC-011 audit-cardinality assertion added to T030; concrete frontend filenames for T024/T041/T045.
- **Coverage** (QA): all 24 FRs, all contract §D cases, and all 9 edge cases map to tasks; all SCs now have a verifying task. **Format** (Tech-lead): IDs T001–T050 sequential, tests-first per story, no `[P]` file conflicts, no forward references, correct `[US#]` labels.

Residual cosmetic items (some directory-level paths for net-new controllers/DTOs) accepted — filenames are unambiguous from the class names.
