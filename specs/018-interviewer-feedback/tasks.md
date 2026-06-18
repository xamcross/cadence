# Tasks: Interviewer Feedback Forms & Reminder Escalation (F32)

**Input**: Design documents from `C:\Users\xamcr\Cadence\specs\018-interviewer-feedback\`
**Prerequisites**: plan.md, spec.md, research.md (D1–D14), data-model.md, contracts/feedback-api.md, quickstart.md

**Tests**: INCLUDED — constitution Principle VII (Test-First) is mandatory for business-logic/acceptance paths; the plan lists unit + Testcontainers + MockMvc + structural + Jasmine/axe + Lighthouse. Write each test FIRST and see it FAIL before implementing.

**Organization**: By user story (spec.md). **US1** (interviewer submits a scorecard from an email link) and **US2** (escalating reminders) are **P1** and ship together as the MVP; **US3** (recruiter reads scorecards) is **P2**. Multi-role plan-review fixes are folded into specific tasks (flagged ⚠️FIX, with the role and finding).

## Path Conventions

Web app: `backend/src/main/java/com/cadence/...`, `backend/src/test/java/com/cadence/...`, `frontend/src/app/...`. Run flags (CLAUDE.md): `JAVA_HOME=C:/jdk-24.0.1`, cached `gradle-9.4.0`, `-Dapi.version=1.41`, `DOCKER_HOST=npipe:////./pipe/docker_engine`. Re-run once if the first multi-class Testcontainers run throws the one-time `GenericContainer` class-init error. All new Java sources keep comments **pure ASCII** (the F30 NUL-byte/binary-detection lesson — scan new files with `git diff --numstat`, not just `.ps1`). Never pass an enum to `StructuredArguments.kv(...)` (the F01.1 logstash Jackson-3 crash) — log `.name()` strings only.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Config + the new enums + operational templates the rest of the feature references.

- [X] T001 [P] Create `backend/src/main/java/com/cadence/config/FeedbackProperties.java` (`@ConfigurationProperties("cadence.feedback")`): `Duration generationDelay` default `PT3H` (post-interview offset, research D2), `Duration submissionDeadline` default `PT24H`, `Duration reminderInterval` default `PT24H`, `int maxReminders` default 3, `Duration tokenTtl` default `PT72H`, `Duration scanInterval` default `PT5M`, `int scanBatchLimit` default 500, `Duration generationQueryLowerBound` default `PT720H` (window floor so the generation scan never ranges ancient bookings), and `String spaFeedbackBasePath` default `/feedback` (the F30 `spaStatusBasePath` precedent — used to build the candidate-link URL). Add an `@PostConstruct` bounds check (all Durations > 0, `maxReminders` in 1..10). Add the `cadence.feedback.*` block to `backend/src/main/resources/application.yml` (LF endings).
- [X] T002 [P] Create `backend/src/main/java/com/cadence/domain/FeedbackRequestStatus.java` enum (`PENDING`, `SUBMITTED`, `INVALIDATED`, `UNCOLLECTIBLE`, `EXPIRED`). Append-only.
- [X] T003 [P] Create `backend/src/main/java/com/cadence/domain/Recommendation.java` enum (`STRONG_YES`, `YES`, `NO`, `STRONG_NO`) — the fixed four-point scale; serialized inside `scorecardPayload` JSON, never a top-level queryable field.
- [X] T004 [P] Add append-only value `FEEDBACK_UNCOLLECTIBLE` to `backend/src/main/java/com/cadence/domain/RecruiterNotificationType.java` (do NOT reorder existing values).
- [X] T005 [P] Add append-only values `SCORECARD_SUBMITTED`, `FEEDBACK_INVALIDATED` to `backend/src/main/java/com/cadence/domain/CandidateEventType.java` (append after the F31 `SLA_DRAFT_*` values; do NOT reorder).
- [X] T006 [P] Add `FEEDBACK_REQUEST_ID = "feedback-request"` + `FEEDBACK_REMINDER_ID = "feedback-reminder"` constants and their subject/body templates to `backend/src/main/java/com/cadence/integration/OperationalEmailTemplates.java` (the `INTERVIEW_CONFIRMATION_*` precedent). Body carries `{link}` and `{stage}`; the reminder body also carries `{urgency}`. Plain text/HTML with `{key}` placeholders only (no candidate-template tokens).

---

## Phase 2: Foundational (Blocking Prerequisites)

**⚠️ CRITICAL**: No user-story work begins until this phase is complete — the request entity, the migration + index test, the encryption hook, the public-chain wiring + RBAC inventory allow-list, the member-mail dispatcher branches, the no-oracle handler, the cycle-break seam, and the service/scheduler skeletons are shared by all stories.

- [X] T007 [P] Create `backend/src/main/java/com/cadence/domain/FeedbackRequest.java` (`@Document("feedbackRequests")`) per data-model §1: `id`, `workspaceId`, `candidateId`, `interviewEventId` (= `SchedulingRequest.id`), `interviewerMemberId`, `status` (`FeedbackRequestStatus`), `@JsonIgnore @Field(value="tokenHash", write=Field.Write.NON_NULL) String tokenHash`, `expiresAt`, `int reminderLevelSent`, `nextReminderDueAt`, `lastReminderAt`, `@JsonIgnore @Field(value="scorecardPayload", write=Field.Write.NON_NULL) String scorecardPayload`, `submittedAt`, `createdAt`, `updatedAt`. `toString()` = ids/status/instants only (NEVER `tokenHash` or `scorecardPayload`).
- [X] T008 [P] Create `backend/src/main/java/com/cadence/repository/FeedbackRequestRepository.java`: `Optional<FeedbackRequest> findByTokenHash(String)`; `List<FeedbackRequest> findByWorkspaceIdAndInterviewEventId(String, String)`; `boolean existsByInterviewEventIdAndInterviewerMemberId(String, String)`; `List<FeedbackRequest> findByWorkspaceIdAndCandidateIdAndStatus(String, String, FeedbackRequestStatus)`; `@Query("{ 'status': ?0, 'nextReminderDueAt': { $lte: ?1 } }") List<FeedbackRequest> findReminderDue(FeedbackRequestStatus, Instant, Pageable)`; and the workspace pending-list finder `findByWorkspaceIdAndStatus(String, FeedbackRequestStatus, Pageable)`. (Insert + `DuplicateKeyException` de-dup lives in the service.) Use explicit `@Query` for the range finder (the F12 `InvalidMongoDbApiUsageException` lesson — never a derived two-criteria-on-one-field method).
- [X] T009 [P] Add `@Field(value="feedbackGeneratedAt", write=Field.Write.NON_NULL) private Instant feedbackGeneratedAt;` (+ getter/setter) to `backend/src/main/java/com/cadence/domain/SchedulingRequest.java` — the F23 stamp pattern; null until generation CAS-sets it once. Do not touch other fields.
- [X] T010 [P] ⚠️FIX (Security/GDPR) Register the scorecard encryption converter in `backend/src/main/java/com/cadence/config/MongoPiiConfig.java`: add `registrar.registerConverter(FeedbackRequest.class, "scorecardPayload", converter);` (the verified F13 `SchedulingRequest.locationText` precedent). This makes `scorecardPayload` encrypted at rest; it MUST be cleared with `$set null` on erasure (NEVER `$unset` — the F03 `ClassCastException` trap).
- [X] T011 Create `backend/src/main/java/com/cadence/config/migration/ChangeUnit017_FeedbackIndexes.java` (`@ChangeUnit(id="017-feedback-indexes", order="017", author="system")`): in `@Execution` create on `feedbackRequests` — unique `{interviewEventId:1, interviewerMemberId:1}`; unique **partial** `{tokenHash:1}` with `partialFilterExpression {tokenHash:{$exists:true}}` (the `ChangeUnit014` `confirmTokenHash` pattern); non-unique `{status:1, nextReminderDueAt:1}`. Native driver `createIndex`. `@RollbackExecution` targeted `dropIndex` per index (never `dropIndexes()`). **No `schedulingRequests` index** (generation reuses `{status,bookedStartAt}`); the `{interviewEventId,submittedAt}` index already exists (`ChangeUnit001`). Pure-ASCII comments; order "017" off the highest applied "016" — never renumber.
- [X] T012 [P] ⚠️FIX (QA) Create `backend/src/test/java/com/cadence/feedback/FeedbackIndexTest.java` (Testcontainers, the F23 `SchedulingIndexTest` / F31 `SlaNudgeIndexTest` precedent): assert post-migration that `feedbackRequests` has the unique `{interviewEventId, interviewerMemberId}`, the partial-unique `{tokenHash}` (over `$exists:true`), the `{status, nextReminderDueAt}`, AND the pre-declared `{interviewEventId, submittedAt}`. Write FIRST; it fails until T011 runs.
- [X] T013 [P] ⚠️FIX (Backend, cycle-break) Create `backend/src/main/java/com/cadence/service/FeedbackInvalidator.java` — narrow interface `{ void invalidateForCandidate(String workspaceId, String candidateId); }`. `CandidateErasureService` depends on THIS, not the concrete `FeedbackService` (the verified `SlaDraftInvalidator` precedent).
- [X] T014 [P] ⚠️FIX (Security/QA, build-breaker) Add `"/api/feedback/"` to `ALLOWED_PREFIXES` in `backend/src/test/java/com/cadence/rbac/RbacEndpointInventoryTest.java` (package `com.cadence.rbac` — NOT `com.cadence.security`) — the public token endpoints carry no `@PreAuthorize`, so the deny-by-default inventory test would otherwise fail the build. (Edit target is the TEST's prefix list; there is no main `RbacEndpointInventory` class. The internal controllers keep `@PreAuthorize` and stay inventory-enforced.)
- [X] T015 Add `/api/feedback/**` to the `@Order(2)` `securityMatcher("/api/public/**","/api/candidate/**")` chain in `backend/src/main/java/com/cadence/security/SecurityConfig.java` (no-login token chain; CSRF disabled, STATELESS). Do NOT touch the `@Order(4)` authenticated/401 or actuator chains (path-scoped matcher — verified it does not widen them).
- [X] T016 ⚠️FIX (Backend, FR-011 + Backend, address resolution) Add two branches to the closed `if/else` dispatcher in `backend/src/main/java/com/cadence/integration/SmtpEmailSender.java` (the `INTERVIEW_CONFIRMATION_ID` precedent): on `FEEDBACK_REQUEST_ID` / `FEEDBACK_REMINDER_ID`, resolve the interviewer via `members.findByIdOptional(toInternalId)` (warn+return if absent), set `toAddress = member.getEmail()` (converter-decrypted; never logged), and `substitute(...)` the operational template with the supplied `mergeFields` (`link`, `stage`, and for the reminder `urgency`). Depends on T006. NOTE: `substitute` leaves an unsupplied `{key}` as a literal — every call site MUST supply all keys (asserted by T029).
- [X] T017 ⚠️FIX (Security/F31 lesson; compile-order) Create `backend/src/main/java/com/cadence/api/FeedbackExceptionHandler.java` — `@Order(Ordered.HIGHEST_PRECEDENCE) @RestControllerAdvice(assignableTypes = {ScorecardTokenController.class, InterviewFeedbackController.class})`. **It references the two controllers created later (T033/T049), so create minimal empty `ScorecardTokenController`/`InterviewFeedbackController` stub classes in THIS task (fleshed out in T033/T049) so the Foundational phase compiles** (the `CandidateStatusExceptionHandler` precedent). It MUST itself `@ExceptionHandler(RbacExceptions.ScopedNotFoundException.class)` → byte-identical 404 `{"error":"not_found"}` (the global `RbacExceptionHandler` emits `{"error":"not_found","message":"Not found."}` WITH a message → byte-divergent oracle; override here, SC-011). Add `400 {"error":"invalid_scorecard"}` and `429 {"error":"rate_limited"}`. Its catch-all `@ExceptionHandler(RuntimeException)` (preview/submit 500-hardening) MUST **re-throw** `AccessDeniedException`/`AuthenticationException` (else `@PreAuthorize` 403s become 500s — the F31 fix).
- [X] T018 Create `backend/src/main/java/com/cadence/service/FeedbackService.java` skeleton: `implements FeedbackInvalidator`; inject `FeedbackRequestRepository`, `SchedulingRequestRepository`, `InterviewSlotClaimRepository`, `MongoTemplate`, `EmailSender`, `MemberService`, `RecruiterNotificationService`, `WorkspaceConfigService`, `CandidateAuditService`, `CandidateRateLimiter`, `TokenHasher`, `SecureTokens`, `FeedbackProperties`, `AuthProperties` (for `getSpaBaseUrl()` — the feedback-link URL, the F30 precedent), `DeadLetterService`, and **`java.time.Clock`** (the `MutableClock`/`AuthTestConfig` pattern). ⚠️FIX (Backend) Do NOT inject `CandidateStatusService`/`ErasureRequestService` (avoids the F31 constructor cycle — none is needed here; add `@Lazy` only if a `@SpringBootTest` later reveals a cycle). Stub the public methods (`generateForOccurredInterview`, `sendReminderIfDue`, `loadForm`, `submit`, `interviewFeedback`, `pendingList`, `invalidateForCandidate`) — implemented in the story phases.
- [X] T019 Create `backend/src/main/java/com/cadence/scheduler/FeedbackScheduler.java` skeleton (the F23 `NoShowDefenseScheduler` shape): `TASK_NAME="feedback-scan"`; `@PostConstruct registerReplayAction(TASK_NAME, this::sweep)`; `@Scheduled(fixedDelayString="${cadence.feedback.scan-interval:PT5M}")`; `sweep()` = `checkpoints.start(TASK_NAME)` → stage 1 (generation) + stage 2 (reminders) → `checkpoints.complete(TASK_NAME)`. Inject `SchedulerCheckpointService`, `SchedulingRequestRepository`, `FeedbackRequestRepository`, `FeedbackService`, `FeedbackProperties`, `Clock`. Stage bodies delegate to `FeedbackService` (filled in US1/US2). No token-read path (SC-008).

**Checkpoint**: Foundation ready — entity + repository + migration + index test, encryption hook, public-chain + RBAC inventory, member-mail branches, no-oracle handler, cycle-break seam, and the service/scheduler skeletons exist. User stories can begin.

---

## Phase 3: User Story 1 - Interviewer submits a scorecard from an email link (Priority: P1) 🎯 MVP slice 1

**Goal**: After an interview occurs, each panel interviewer is generated a request + emailed a private no-login link; the interviewer opens a blank scorecard and submits; the scorecard is persisted (encrypted) against `{interview, interviewer}`; candidate erasure wipes it.

**Independent Test**: Seed a BOOKED `SchedulingRequest` with a past `bookedStartAt` + two ACTIVE claims → `sweep()` generates one request + email per interviewer → `GET /api/feedback/{token}` (no auth) returns a blank form → `POST` submit persists an encrypted scorecard → repeated sweeps create nothing new → erasing the candidate wipes the content.

### Tests for User Story 1 (write FIRST, ensure they FAIL) ⚠️

- [X] T020 [P] [US1] ⚠️FIX (QA, gated) Integration test `backend/src/test/java/com/cadence/feedback/FeedbackGenerationIT.java` (Testcontainers, `MutableClock`): a BOOKED past-`bookedStartAt` interview with 2 ACTIVE claims → exactly one `feedbackRequests` row + one `FEEDBACK_REQUEST_ID` email per interviewer (SC-001/SC-002); **gated (latch, ≥2-thread) concurrent `sweep()`** racing the `{_id,status:BOOKED,feedbackGeneratedAt:null}` CAS + the unique `{interviewEventId,interviewerMemberId}` insert → still one row + one email per interviewer (SC-003, non-vacuous); a CANCELLED / RESCHEDULED / future (`bookedStartAt > now-generationDelay`) booking → no generation (SC-012); generation scan is `Pageable`-bounded (assert it does not read beyond the cap). Note the `feedbackGeneratedAt=null` predicate is an in-memory residual on `{status,bookedStartAt}` (research D2) — assert it is bounded, not that it is a covered index key.
- [X] T021 [P] [US1] Contract test `backend/src/test/java/com/cadence/feedback/ScorecardTokenContractTest.java` (MockMvc, public chain — no auth, no csrf): `GET /api/feedback/{token}` on a PENDING request → `200 {state:FORM,...}` with **no submitted content** (SC-008); submit a valid scorecard → `200 {state:SUBMITTED}`; invalid (missing recommendation / rating out of 1..4 / over-length comment) → `400 invalid_scorecard`, nothing persisted (US1 scenario 4); the token-routed surface is bounded to GET-load + POST-submit (route-inventory assertion — no other token handler serves content, SC-008); `Cache-Control: no-store` on load.
- [X] T022 [P] [US1] ⚠️FIX (QA, gated) Integration test `backend/src/test/java/com/cadence/feedback/ScorecardSubmitConcurrencyIT.java` (gated latch, the F22/F23 pattern): N concurrent submits with the same token → exactly one persisted scorecard (the `PENDING→SUBMITTED` CAS); idempotent re-submit → `SUBMITTED`, no duplicate (SC-009/FR-019).
- [X] T023 [P] [US1] ⚠️FIX (Security, STATUS-before-TIME) Integration/contract test `backend/src/test/java/com/cadence/feedback/ScorecardTokenResolutionTest.java`: a genuinely past-TTL PENDING token → `200 {state:EXPIRED}` (distinct); a used/submitted, INVALIDATED (erased), UNCOLLECTIBLE, and unknown token all → **byte-identical** `200 {state:USED}` (no state oracle, SC-007/SC-023); resolution checks STATUS before TIME (data-model §6).
- [X] T024 [P] [US1] Integration test `backend/src/test/java/com/cadence/feedback/ScorecardRateLimitTest.java`: requests beyond the per-minute cap (test-profile) to `/api/feedback/{token}` → `429 rate_limited` (SC-021), keyed on hashed IP (`CandidateRateLimiter`).
- [X] T025 [P] [US1] Integration test `backend/src/test/java/com/cadence/feedback/ScorecardEncryptionIT.java`: after submit, a raw MongoDB driver read of the `feedbackRequests` doc shows `scorecardPayload` as ciphertext (FR-028); a `MongoTemplate` load decrypts it to the original JSON (cold-converter discipline).
- [X] T026 [P] [US1] ⚠️FIX (Security, BLOCKER) Integration test `backend/src/test/java/com/cadence/feedback/ErasureWipesScorecardIT.java`: erasing a candidate clears `scorecardPayload` on BOTH a **PENDING** row (→ INVALIDATED) AND a **SUBMITTED** row (status kept, payload null) — assert via raw-driver read that a *submitted* scorecard's content is gone (SC-013); the token is dropped (`$unset tokenHash`) so the link → `USED`; no further reminders; and a value-free `FEEDBACK_INVALIDATED` audit record is written (SC-018 invalidation leg). This is the review BLOCKER — the wipe MUST NOT filter `status:PENDING` only.
- [X] T027 [P] [US1] ⚠️FIX (QA, PII) PII-scan test `backend/src/test/java/com/cadence/feedback/FeedbackLogPiiScanTest.java`: drive generate→send→load→submit with `SENTINELF32TEXT_*` (scorecard comment), `SENTINELF32REC_*` (recommendation), `SENTINELF32EMAIL_*` (interviewer email), `SENTINELF32NAME_*` (candidate name); assert absence in captured logs, the `feedbackRequests` doc (beyond the encrypted blob), the audit entry, and the dead-letter record — **including a forced render/send failure** whose dead-letter carries only the PII-free cause class (`render_failed: <SimpleName>`, the F22 footgun) (SC-014/FR-029).
- [X] T028 [P] [US1] Integration test `backend/src/test/java/com/cadence/feedback/RescheduleRetainsFeedbackIT.java`: a submitted scorecard for an occurred interview is retained against the original `interviewEventId`; a new occurrence (a fresh `SchedulingRequest` id, `feedbackGeneratedAt=null`) generates a fresh independent request once past `bookedStartAt+generationDelay` (SC-022). Document that the occurred-and-generated-then-rescheduled-away case is unreachable (F20 refuses past-interview reschedule — research D2).
- [X] T029 [P] [US1] ⚠️FIX (Backend, FR-011) Test `backend/src/test/java/com/cadence/feedback/OperationalTemplateMergeTest.java`: assert every `{key}` in the `FEEDBACK_REQUEST_ID` and `FEEDBACK_REMINDER_ID` templates (incl. `{link}`, `{stage}`, `{urgency}`) is supplied at every `sendEmail` call site so no literal `{key}` ships to the interviewer (the operational `substitute` leaves unknown keys literal — no F21 warning on this path).

### Implementation for User Story 1

- [X] T030 [US1] ⚠️FIX (Backend, private method + Backend, address) Implement `FeedbackService.generateForOccurredInterview(SchedulingRequest req, Instant now)` in `backend/src/main/java/com/cadence/service/FeedbackService.java`: CAS `findAndModify({_id, status:BOOKED, feedbackGeneratedAt:null}, $set feedbackGeneratedAt=now)` (fire once; loser no-op); read participants from `interviewSlotClaimRepository.findByWorkspaceIdAndSchedulingRequestId(ws, reqId)` filtered to `ClaimStatus.ACTIVE` (the repo directly — `participantsFromClaims` is private); per interviewer — if `!member.isActive()` (resolve via `MemberService`) → `recruiterNotificationService.notify(ws, candidateId, FEEDBACK_UNCOLLECTIBLE)` + skip; else mint a 256-bit `SecureTokens.newToken()`, `repo.insert(new FeedbackRequest{PENDING, tokenHash=hash(raw), expiresAt=now+tokenTtl, reminderLevelSent=0, nextReminderDueAt=now+effectiveSubmissionDeadline})` (catch `DuplicateKeyException` → no-op, FR-003), `emailSender.sendEmail(interviewerMemberId, FEEDBACK_REQUEST_ID, {link:<feedbackUrl(raw)>, stage:<label>})`. ⚠️FIX (Backend) **`feedbackUrl(raw) = authProps.getSpaBaseUrl() + props.getSpaFeedbackBasePath() + "?token=" + raw`** (the F30 status-link precedent; the raw token rides the link only, never persisted). Wire stage 1 of `FeedbackScheduler.sweep()` to read `SchedulingRequest` `status=BOOKED, bookedStartAt <= now-generationDelay, bookedStartAt >= now-generationQueryLowerBound, feedbackGeneratedAt=null` (Pageable cap) and call this per booking. (`effectiveSubmissionDeadline` = global default here; the per-workspace override is US2/T041.) Inject `RecruiterNotificationService` (add to the skeleton). Log ids/`.name()` only.
- [X] T031 [US1] ⚠️FIX (Security, write-only + STATUS-before-TIME) In `backend/src/main/java/com/cadence/service/FeedbackService.java`, implement `loadForm(String rawToken, String ip)` and `submit(String rawToken, ScorecardSubmission body, String ip)`: both `rateLimiter.tryAcquire(ip)` else 429; `resolve(rawToken)` = `findByTokenHash(hash)` then STATUS-before-TIME (data-model §6) → return a `state` envelope. `loadForm` on PENDING returns the BLANK form (recommendation options + rating dimensions + a non-PII interview label) — **never** prior content. `submit` validates (recommendation required ∈ scale; ratings ∈ 1..4; comment ≤ max → else `400 invalid_scorecard`), CAS `{_id, status:PENDING} → SUBMITTED, $set scorecardPayload=<encrypted JSON>, submittedAt=now, nextReminderDueAt=null`, `audit.append(ws, candidateId, SCORECARD_SUBMITTED, actor=interviewerMemberId)`; idempotent re-submit (matched==0) → already-submitted state. The encrypted write rides the converter (set the plaintext JSON; `MongoPiiConfig` encrypts).
- [X] T032 [P] [US1] Create `backend/src/main/java/com/cadence/api/FeedbackDtos.java`: `ScorecardFormView(state, interviewLabel, recommendationOptions, ratingDimensions)`, `ScorecardSubmission(recommendation, ratings, comment)` (with a nested `Rating(dimension, score)`), `SubmitResponse(state)`. (The recruiter DTOs are added in US3.)
- [X] T033 [US1] Create `backend/src/main/java/com/cadence/api/ScorecardTokenController.java` — `@RestController` on the public chain (no `@PreAuthorize`): `GET /api/feedback/{token}` → `loadForm`; `POST /api/feedback/{token}` → `submit`. Both `Cache-Control: no-store`. Return the 200 state-envelope (FORM/USED/EXPIRED/SUBMITTED) per contract §A/§B (no status-code oracle). Resolve the client IP for the rate limiter.
- [X] T034 [US1] ⚠️FIX (Security, BLOCKER — wipe submitted too; + QA, SC-018 invalidation audit) Implement `FeedbackService.invalidateForCandidate(ws, candidateId)` (the `FeedbackInvalidator` impl): `mongoTemplate.updateMulti({workspaceId, candidateId, status:PENDING}, $set status=INVALIDATED, $set scorecardPayload=null, $unset tokenHash)` **AND** `updateMulti({workspaceId, candidateId, status:SUBMITTED}, $set scorecardPayload=null, $unset tokenHash)` (the SUBMITTED row keeps its status for the "who responded" trail; content gone). `$set null` for the converter field (NEVER `$unset`). If any row was modified, append one value-free `audit.append(ws, candidateId, FEEDBACK_INVALIDATED, ...)` (SC-018 — else the `FEEDBACK_INVALIDATED` enum from T005 is dead code and the invalidation-audit leg is untested). Then modify `backend/src/main/java/com/cadence/service/CandidateErasureService.java` `wipe(...)`: after the winning guarded `updateFirst`, call `feedbackInvalidator.invalidateForCandidate(ws, candidateId)` (best-effort) alongside `supersedeLiveScheduling` + `slaDraftInvalidator` (depend on the **interface**, cycle-break T013). (FR-023/SC-013/SC-018.)
- [X] T035 [P] [US1] Create the public no-login page `frontend/src/app/features/feedback/scorecard-page.component.ts` (lazy route `/feedback?token=…`): blank scorecard (recommendation radios + optional 1–4 rating inputs + optional comment textarea) + submit; states `loading/form/submitted/expired/invalid` driven by the `state` envelope. Token held in a **memory-only** field (never `localStorage`/`sessionStorage`), re-resolved on `ngOnInit` (bfcache-safe, the F14/F30 hardening); never `console.error`-logged. Mobile-first, `$localize` strings, ≥44 px targets. Add the lazy route + a `feedback.service.ts` (`loadForm(token)`, `submit(token, payload)` on `apiBaseUrl`).
- [X] T036 [P] [US1] ⚠️FIX (QA, F14 footguns) Jasmine + axe `frontend/src/app/features/feedback/scorecard-page.component.spec.ts`: axe **0 WCAG 2.2 AA violations** across all states (fixture MUST be `document.body.appendChild`-attached or axe color-contrast/visibility silently no-ops — the F14 lesson); an explicit `getBoundingClientRect` ≥44 px test (`target-size` is NOT in the axe WCAG tag set); focus moves to the state heading on transition; the token is never written to storage; long/RTL overflow handled. (§IX gate — this is the one F32 candidate-class public page.)
- [X] T037 [P] [US1] Add the `/feedback?token=lighthouse-demo` canned blank-form open-state to `frontend/lighthouse/serve-with-stub.mjs` and the `/feedback` route to `lighthouserc.json` (Performance ≥85, the F14 stub pattern). SPA fallback + the canned `GET /api/feedback/<demo>` open state.

**Checkpoint**: An interviewer can be generated a request, open the no-login form, and submit an encrypted scorecard; erasure wipes it; the public page passes axe/Lighthouse. MVP slice 1 complete and demonstrable browser→DB.

---

## Phase 4: User Story 2 - Escalating reminders until feedback is submitted (Priority: P1) 🎯 MVP slice 2

**Goal**: An unsubmitted request gets escalating reminder emails at the workspace cadence until submitted/uncollectible/expired/max — idempotent, missed-fire-safe, deterministic at the deadline boundary + DST.

**Independent Test**: With a `MutableClock`, an unsubmitted PENDING request gets no reminder before its deadline, then L1/L2/L3 at the cadence, stopping at max 3; submitting stops further reminders; a deactivated interviewer → UNCOLLECTIBLE + fallback alert; overlapping sweeps at one level send once.

### Tests for User Story 2 (write FIRST, ensure they FAIL) ⚠️

- [X] T038 [P] [US2] ⚠️FIX (QA, pinned instant) Integration test `backend/src/test/java/com/cadence/feedback/FeedbackReminderIT.java` (Testcontainers, `MutableClock`): the first-reminder instant is `bookedStartAt + generationDelay + effectiveSubmissionDeadline` — assert **zero reminders strictly before** it (SC-004 lower bound), L1 at it, L2/L3 at `+reminderInterval`, **stop at max 3**, each reminder a distinct `urgency`/level marker (SC-004); after submit, no further reminders and the SUBMITTED row is **absent from `findReminderDue`** (terminal drop-out, SC-005); a TTL-expired PENDING request → CAS `PENDING→EXPIRED`, zero sends, `nextReminderDueAt` cleared (SC-024).
- [X] T039 [P] [US2] ⚠️FIX (QA, gated) Concurrency test `backend/src/test/java/com/cadence/feedback/ReminderConcurrencyIT.java` (gated latch): two overlapping reminder-scan fires for the same request at the same level → exactly one email sent + one `reminderLevelSent` increment (the per-`{request,level}` CAS, SC-020, non-vacuous).
- [X] T040 [P] [US2] Integration test `backend/src/test/java/com/cadence/feedback/ReminderReplayAndDeactivationIT.java`: (a) ⚠️FIX (QA honest residual) a checkpoint-replay / double-`sweep()` produces no duplicate reminder (SC-006 — the F23/F31 double-sweep proxy, documented as not a true restart); (b) a deactivated/removed interviewer discovered at **generation** time AND at **reminder** time → no send, request → UNCOLLECTIBLE, `FEEDBACK_UNCOLLECTIBLE` workspace fallback alert (SC-015, both triggers; uses `member.isActive()`), **and the previously-issued live link now returns the byte-identical `USED` envelope** (FR-009 link cessation, end-to-end); (c) ⚠️FIX (QA, DST) reminder timing deterministic at the deadline boundary and across a DST change in the workspace zone under `MutableClock` (SC-016).
- [X] T041 [P] [US2] Contract test `backend/src/test/java/com/cadence/feedback/FeedbackSettingsContractTest.java` (MockMvc, `.with(csrf())`): `PATCH /api/internal/workspace/settings {feedbackSubmissionDeadline:"PT24H", feedbackReminderInterval:"PT24H"}` as Admin → 200 + persisted + audited (SC-018); non-Admin → 403; invalid (non-positive Duration) → 400, prior value unchanged. Reuses the F03 settings endpoint — no new endpoint.

### Implementation for User Story 2

- [X] T042 [P] [US2] ⚠️FIX (Backend, F23 positional lesson) Add nullable `Duration feedbackSubmissionDeadline`, `Duration feedbackReminderInterval` (+ getters) to `backend/src/main/java/com/cadence/domain/WorkspaceConfig.java` (the F23 `confirmationLeadTime` pattern). Extend the `SettingsPatch` and `WorkspaceConfigResponse` records in `backend/src/main/java/com/cadence/api/WorkspaceDtos.java` with the two fields **at the END** of the positional list, and fix `WorkspaceConfigResponse.from(...)` + `.unconfigured(...)`. **Grep `src/test` for `new WorkspaceConfigResponse(` and `new SettingsPatch(` and fix every positional call site** (the F23 lesson — this broke 3 test constructors there).
- [X] T043 [US2] Implement the effective-value resolution + validation in `backend/src/main/java/com/cadence/service/WorkspaceConfigService.java updateSettings`: hard-code the two new fields; validate the **effective** values (`patch ?? current ?? FeedbackProperties default`): `feedbackSubmissionDeadline > 0`, `feedbackReminderInterval > 0` (no cross-field ordering constraint — independent positivity only). Audit the change via the existing settings-audit path (SC-018). **FR-013 note**: `maxReminders` stays global (`FeedbackProperties`) for the MVP — per-workspace max is deferred; the documented global default (3) discharges FR-012's "maximum". State this in a comment so a later reader doesn't expect a per-workspace max field.
- [X] T044 [US2] ⚠️FIX (Backend, member liveness) In `backend/src/main/java/com/cadence/service/FeedbackService.java`, implement `sendReminderIfDue(FeedbackRequest req, Instant now)`: if `!member.isActive()` → CAS `PENDING→UNCOLLECTIBLE` + `notify(FEEDBACK_UNCOLLECTIBLE)` (no send); elif `now >= expiresAt` → CAS `PENDING→EXPIRED` (no send); else per-`{request,level}` CAS `findAndModify({_id, status:PENDING, reminderLevelSent:L}, $set reminderLevelSent=L+1, lastReminderAt=now, nextReminderDueAt = (L+1 < maxReminders ? now+effectiveReminderInterval : null))` then `emailSender.sendEmail(interviewerMemberId, FEEDBACK_REMINDER_ID, {link, stage, urgency:String.valueOf(L+1)})`. ⚠️FIX (Backend) **`effectiveReminderInterval` = the per-workspace `WorkspaceConfig.feedbackReminderInterval` else the global default** (resolve via `WorkspaceConfigService`, the F23 per-workspace pattern — else the T042/T043 setting is dead config). Wire stage 2 of `FeedbackScheduler.sweep()` to `findReminderDue(PENDING, now, Pageable)` and call this per row. Make `generateForOccurredInterview` (T030) read the per-workspace `feedbackSubmissionDeadline` (else the global default) for the initial `nextReminderDueAt`. NB: `findReminderDue` ranges all workspaces (the F23/F31 global-sweep pattern); per-workspace cadence is resolved in Java per row.

**Checkpoint**: Reminders escalate correctly and stop on every terminal condition; deactivation and TTL are handled; timing is deterministic. MVP (US1+US2) complete.

---

## Phase 5: User Story 3 - Recruiter sees who responded and reads scorecards (Priority: P2)

**Goal**: A Recruiter/Admin sees per-interview submission status and reads decrypted scorecards; a workspace pending list surfaces outstanding feedback. HM/Interviewer/Read-only denied; HM scoped read deferred to F51.

**Independent Test**: With mixed submitted/pending scorecards on an interview, `GET /api/internal/interviews/{id}/feedback` returns each interviewer's status + the submitted content for ADMIN/RECRUITER; HM/Interviewer/Read-only → 403; a cross-workspace interview id → indistinguishable 404; the pending list shows outstanding rows.

### Tests for User Story 3 (write FIRST, ensure they FAIL) ⚠️

- [X] T045 [P] [US3] Contract test `backend/src/test/java/com/cadence/feedback/InterviewFeedbackContractTest.java` (MockMvc, `.with(csrf())`): seed one SUBMITTED + one PENDING scorecard on an interview → `GET /api/internal/interviews/{schedulingRequestId}/feedback` for ADMIN/RECRUITER → 200 with per-interviewer status + the decrypted submitted scorecard + `no-store` (SC-001 read half / SC-017); **5-role matrix** — HM/INTERVIEWER/READ_ONLY → 403 (SC-010, HM deferred to F51); `GET /api/internal/feedback/pending` lists PENDING rows (FR-027).
- [X] T046 [P] [US3] ⚠️FIX (Security/Backend, no empty-list oracle) Contract test `backend/src/test/java/com/cadence/feedback/InterviewFeedbackScopeTest.java`: a cross-workspace / unknown `schedulingRequestId` → indistinguishable **404 `not_found`** (resolved via `SchedulingRequest.findByWorkspaceIdAndId` first → `ScopedNotFoundException`, NOT an empty list); a real in-workspace interview with no feedback generated yet → **200 `{items:[]}`** (distinct from 404). (SC-011.)

### Implementation for User Story 3

- [X] T047 [US3] ⚠️FIX (Security/Backend, booking-first resolution) In `backend/src/main/java/com/cadence/service/FeedbackService.java`, implement `interviewFeedback(ws, schedulingRequestId)` and `pendingList(ws)`: `interviewFeedback` first resolves `schedulingRequestRepository.findByWorkspaceIdAndId(ws, schedulingRequestId)` (→ `ScopedNotFoundException` if absent/cross-workspace, the 404), THEN `feedbackRequestRepository.findByWorkspaceIdAndInterviewEventId(ws, schedulingRequestId)`; decrypt `scorecardPayload` for SUBMITTED rows and parse to the response shape; never log the content. `pendingList` reads `findByWorkspaceIdAndStatus(ws, PENDING, Pageable)` → ids + reminderLevelSent only (no PII).
- [X] T048 [P] [US3] Add `InterviewFeedbackView(interviewEventId, items)` (item: `interviewerMemberId, status, scorecard?, submittedAt`), `ScorecardView(recommendation, ratings, comment)`, `PendingItem(interviewEventId, interviewerMemberId, candidateId, reminderLevelSent)`, `PendingListResponse(items)` to `backend/src/main/java/com/cadence/api/FeedbackDtos.java`.
- [X] T049 [US3] Create `backend/src/main/java/com/cadence/api/InterviewFeedbackController.java` — `@RestController @PreAuthorize("hasAnyRole('ADMIN','RECRUITER')")`: `GET /api/internal/interviews/{schedulingRequestId}/feedback` and `GET /api/internal/feedback/pending`, both `Cache-Control: no-store`, workspace-scoped via the principal. (HM/Interviewer/Read-only denied by the role gate — HM deferred to F51.)
- [X] T050 [P] [US3] Create `frontend/src/app/features/scheduling/interview-feedback.service.ts` (HttpClient + `apiBaseUrl`): `getInterviewFeedback(schedulingRequestId)`, `pending()` returning typed `Observable`s.
- [ ] T051 [US3] **DEFERRED (honest)**: the recruiter-facing `interview-feedback.service.ts` (T050) ships and the recruiter read is fully backend-tested (InterviewFeedbackContractTest — 5-role + no-oracle + pending list), but the on-page `.feedback-status-panel` was NOT wired into the large existing `scheduling.component.ts` in this pass (internal screen, no §IX gate). The capability exists service-side; the panel is a thin follow-up. Reported to the user.
- [ ] T052 [P] [US3] **DEFERRED (honest)**: the Jasmine panel spec, deferred with T051.

**Checkpoint**: All three stories functional — generation + no-login submit, escalating reminders, and the recruiter read surface; HM denied; no PII leaks.

---

## Phase 6: Polish & Cross-Cutting Concerns

- [X] T053 [P] Extend `.github/workflows/ci.yml` PII scan with `SENTINELF32*` (scorecard free-text/recommendation, interviewer email, candidate name) across the F32 sources/tests; keep scan lines pure ASCII (Principle V). Confirm no `voice|transcribe|whisper|twilio` scope creep (SC-019 — voice-to-scorecard is v1.5).
- [X] T054 [P] ⚠️FIX (QA, SC-019) Structural/absence check: confirm no voice-to-scorecard capability/endpoint and no configurable per-stage scorecard template exists in the build (a simple source/route absence assertion or a documented review note in the implementation-review task).
- [X] T055 [P] Run `RbacEndpointInventoryTest` + the full `com.cadence.feedback.*` suite green; confirm the one-time `GenericContainer` re-run note holds; verify no enum is ever passed to `StructuredArguments.kv(...)` across the new sources.
- [X] T056 [P] Byte-level scan all new Java sources for non-ASCII / NUL (the F30 binary-detection lesson — `git diff --numstat` must not show `-`/`-`); confirm `ChangeUnit017` is text, order "017", never renumbered.
- [X] T057 Run `frontend` `ng test --watch=false` (incl. the new scorecard-page axe specs) + `ng build --configuration production` clean; run `npx @lhci/cli autorun --config=../lighthouserc.json` and confirm the `/feedback` route Performance ≥85 (a Windows-only `chrome-launcher` teardown `EPERM` is benign — CI is Ubuntu).
- [X] T058 Execute `quickstart.md` end-to-end (seed occurred interview → scan generates request + email → open `/feedback?token` → submit → recruiter reads; unsubmitted → reminders escalate L1/L2/L3; deactivate interviewer → UNCOLLECTIBLE + alert; erase candidate → wiped) and record the result.
- [X] T059 Multi-role implementation review (≥3 roles: Backend, Security/GDPR, QA — constitution §VI/C6) against the real diff; apply or report findings before closing. (SC-025 traceability — confirm every FR maps to a green test.)

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: no dependencies — start immediately. T001–T006 all [P].
- **Foundational (Phase 2)**: depends on Setup — **BLOCKS all user stories**. Within it: T007/T008/T009/T010/T012/T013/T014 are [P]; T011 (migration) before T012 (index test runs against it); T015 (SecurityConfig) and T016 (member-mail, depends on T006) independent; T017 (handler) references the US controllers (compile-stub OK); T018 (service skeleton) depends on T007/T008/T013; T019 (scheduler skeleton) depends on T018.
- **US1 (Phase 3)**: depends on Foundational. The MVP headline (generation + no-login submit + erasure + public page).
- **US2 (Phase 4)**: depends on Foundational + US1's `generateForOccurredInterview` (reminders act on the rows US1 creates); adds the per-workspace settings + reminder sending.
- **US3 (Phase 5)**: depends on Foundational + the rows US1 creates; independent of US2.
- **Polish (Phase 6)**: after all desired stories.

### User Story Dependencies

- **US1 (P1)**: independent — generation + form + submit + erasure + public page (uses global `FeedbackProperties` defaults for the initial reminder due-time).
- **US2 (P1)**: builds on US1 (reminders escalate the requests US1 generated); adds the per-workspace deadline/cadence override.
- **US3 (P2)**: builds on US1 (reads the requests/scorecards); independent of US2.

### Within Each User Story

- Tests written FIRST and FAILING before implementation (constitution §VII).
- Enums/config → entity/repository/migration → service → controller → frontend.
- Drive all timing via the `MutableClock` / stamped instants, never wall-clock sleeps (the F23 lesson).

### Parallel Opportunities

- All of T001–T006 (Setup) in parallel.
- T007–T010, T012, T013, T014 (Foundational) in parallel; T011 before T012.
- US1 test files (T020–T029) all [P]; US2 tests (T038–T041) [P]; US3 tests (T045–T046) [P].
- Frontend tasks (T035/T036/T037, T050/T052) [P] with their backend siblings.

---

## Parallel Example: User Story 1 tests

```text
# Author these failing tests together (different files, no deps):
Task: FeedbackGenerationIT.java          (gated generation, idempotent, no-gen for cancelled/future)   # T020
Task: ScorecardTokenContractTest.java    (load/submit, write-only, validation, bounded surface)        # T021
Task: ScorecardSubmitConcurrencyIT.java  (gated double-submit, one record)                              # T022
Task: ScorecardTokenResolutionTest.java  (STATUS-before-TIME: expired vs used, no oracle)               # T023
Task: ScorecardRateLimitTest.java        (429)                                                          # T024
Task: ScorecardEncryptionIT.java         (raw-driver ciphertext)                                        # T025
Task: ErasureWipesScorecardIT.java       (PENDING + SUBMITTED content wiped)                            # T026
Task: FeedbackLogPiiScanTest.java        (SENTINELF32* + forced-failure dead-letter)                    # T027
Task: RescheduleRetainsFeedbackIT.java   (submitted retained; new occurrence fresh)                     # T028
Task: OperationalTemplateMergeTest.java  (every {key} supplied)                                         # T029
```

---

## Implementation Strategy

### MVP (US1 + US2 ship together — P1)

1. Phase 1 Setup → Phase 2 Foundational (CRITICAL — blocks everything; includes the encryption hook, public-chain wiring, member-mail branches, cycle-break seam, and the no-oracle handler).
2. US1 (generation + no-login submit + erasure + public page) → US2 (escalating reminders + per-workspace cadence).
3. **STOP and VALIDATE** at each checkpoint; run `quickstart.md` after US2.
4. US3 (recruiter read) → Polish (CI PII scan, ASCII/NUL scan, frontend build + Lighthouse, multi-role review) → demo browser→DB.

### Notes

- [P] = different files, no incomplete-task dependency. [US#] maps each task to its story.
- **Two review BLOCKERs are folded into tasks**: erasure wipes SUBMITTED scorecards too (T026 test, T034 impl); the first-reminder instant is pinned to `bookedStartAt + generationDelay + effectiveSubmissionDeadline` (T038), NOT the spec prose "end+24h" (`bookedEndAt` is not denormalized).
- **Reuse, do NOT recreate**: the `{interviewEventId, submittedAt}` index (F00.1/`ChangeUnit001`); the `{status, bookedStartAt}` index (F23/`ChangeUnit014`) for the generation scan; `EmailSender.sendEmail`/`OperationalEmailTemplates` (member mail, NOT the candidate `EmailDispatchService`); `SecureTokens`/`TokenHasher`/`CandidateRateLimiter`; the `@Order(2)` no-login chain; the `MongoPiiConfig` converter; the F14 axe/Lighthouse harness. The candidate-facing `EmailMessageType.FEEDBACK_REQUEST` + `MergeToken.FEEDBACK_LINK` are NOT used (wrong recipient — they resolve a candidate; the interviewer is a member) — left vestigial, not removed (would touch the F21 completeness machinery).
- **Cycle-break**: `FeedbackService` depends on no status/erasure service, so the narrow `FeedbackInvalidator` interface alone breaks the (already-absent) cycle; do NOT later add `CandidateStatusService` without `@Lazy` (the F31 trap). Verify any `@SpringBootTest` starts.
- **§IX gate** applies to the public `/feedback` page only (axe 0 + Lighthouse ≥85); the recruiter panel is an internal screen (N/A).

## Multi-role tasks review (2026-06-17) — verdict: APPROVE-WITH-NITS (fixes applied)

Reviewers: QA (coverage/test-first/format), Backend/Architecture (ordering/paths/feasibility, verified vs real source), Security/GDPR (control-by-control). No BLOCKERs in the task list itself; all SC-001..SC-025 map to tasks, both plan BLOCKERs are represented (T026/T034 erasure-wipes-SUBMITTED; T038 pinned reminder instant), test-first ordering and the checklist format are clean. Folded-in fixes:

- **Feedback-link URL was undefined (Backend, execution-blocker)** → `FeedbackProperties.spaFeedbackBasePath` (T001), `AuthProperties` injected (T018), `link = getSpaBaseUrl()+spaFeedbackBasePath+"?token="+raw` pinned (T030).
- **Wrong test package (Backend)** → `RbacEndpointInventoryTest` is `com.cadence.rbac`, not `com.cadence.security` (T014).
- **Per-workspace reminder interval was dead config (Backend)** → T044 resolves the effective `feedbackReminderInterval` (workspace ?? global), the F23 pattern.
- **Invalidation audit missing (QA, SC-018)** → T034 appends a value-free `FEEDBACK_INVALIDATED` audit; T026 asserts it (else the T005 enum was dead code).
- **T017 compile-order (Backend)** → create the two controller stubs in T017 so Foundational compiles.
- **FR-009 link cessation (Security)** → T040 asserts a deactivated interviewer's live link now returns the byte-identical `USED`.
- **FR-013 maximum (QA, NIT)** → per-workspace `maxReminders` deferred; the global default discharges FR-012 (documented in T043).

Optional NITs left as implementation-review items (T059): strengthen T021's bounded-surface check toward a constant-pool structural scan (the `NoAutoSendStructuralTest` precedent); assert `tokenHash != rawToken` in T025; the token-expired-on-use flip is intentionally not separately audited (read-time, unauthenticated actor).
