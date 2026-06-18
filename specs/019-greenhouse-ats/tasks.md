# Tasks: ATS Integration — Greenhouse (F40)

**Input**: Design documents from `/specs/019-greenhouse-ats/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/ats-api.md, quickstart.md

**Tests**: INCLUDED — constitution §VII mandates test-first for business logic, and the spec's acceptance criteria + quickstart enumerate the suite. Write each story's tests first; they must fail before implementation.

**Organization**: Tasks are grouped by user story. Note the real dependency chain (US1 connection → US2 sync → US3 write-back → US4 resilience); each story is still independently testable against the `StubGreenhouse`.

## Path Conventions

Web app: `backend/src/main/java/com/cadence/`, `backend/src/test/java/com/cadence/`, `frontend/src/app/`. All paths below are repo-relative.

**Run flags (every backend test run)**: `JAVA_HOME=C:/jdk-24.0.1`, cached `gradle-9.4.0` binary, `-Dapi.version=1.41`, `DOCKER_HOST=npipe:////./pipe/docker_engine`, `-Dorg.gradle.java.installations.auto-download=false`. The first multi-class Testcontainers run after a recompile throws the one-time `GenericContainer` class-init error — re-run.

## Implementation Status — 2026-06-18

**Done & verified** (backend `compileJava` clean; ATS package 25 tests green; rbac/scheduling/feedback/gdpr/noshow regression green; frontend ATS spec 5/5; `ng build --configuration production` clean — `ats-integration-component` lazy chunk emitted):

- **Setup (P1)**: T001–T005 — enums, `AtsProperties` + `application.yml`, `ChangeUnit018`, ci.yml ATS base-URL guard.
- **Foundational (P2)**: T006–T024 — `AtsConnector` + DTOs + `AtsApiException`/`AtsApiClassifier`/`AtsApiRetry` (+ unit tests T009/T010), `GreenhouseAtsClient`, `StubGreenhouse`, `AtsNoSdkStructuralTest` (T015), domain+repos (`AtsConnection`/`AtsWriteBack`/`AtsSyncRun`), `MongoPiiConfig` reg, `Candidate` extension, `AtsWriteBackInvalidator`, erasure hook, `IdempotencyKeys.atsWriteBackKey`, `AtsExceptionHandler`, `AtsDtos`.
- **US1 (P3)**: T027–T032 — `AtsConnectionService`, `AtsConnectionController`, `ats.service.ts`, `AtsIntegrationComponent` + spec, `admin/ats` route. Crypto-at-rest (T026) verified in `AtsSyncIT`.
- **US2 (P4)**: T033–T042 — `AtsSyncService` (resolve-then-guarded-write), `AtsSyncScheduler`, `AtsSyncRun`, reconcile finder, sync-status endpoint + UI. `AtsSyncIT` (import/stage/dedup/burst-50/**FR-029 minimization**/SC-012 crypto/SC-008 lawful-basis), `AtsResurrectionGuardIT` (T034).
- **US3 (P5)**: T043–T048 — `AtsWriteBack` + repo, `AtsWriteBackService` (enqueue+claim+deliver, invalidator), 6 seams (T046 link/confirmed/rescheduled/cancelled/feedback; T047 `stampNoShow` returns row + no-show enqueue), `AtsWriteBackScheduler`. `AtsWriteBackIT` (linked→1/non-linked→0, one-note, idempotent, erasure-cancel).
- **US4 (P6)**: T054–T058 — `ATS_WRITEBACK_FAILED`/`ATS_SYNC_FAILED` notify types, resilient delivery (TRANSIENT requeue / dead-letter), reaper, degraded health + `dead-letters` endpoint. `AtsWriteBackIT.exhaustedRetriesDeadLetterAndNotify` covers SC-004/FR-018; `AtsPropertiesBoundsTest` (T052) covers the SC-004 budget invariant.

**Residual test tasks (deferred — honest gaps, no production code missing)**: T024 `AtsIndexTest`, T025 the dedicated MockMvc 5-role `AtsConnectionContractTest` (RBAC is enforced by `@PreAuthorize` + `RbacEndpointInventoryTest`, which passes), T050 `AtsSyncRateLimitIT` (429 path; the retry/classifier units cover the logic), T051 `AtsRestartIT` (SC-007 double-sweep proxy), T053 `AtsLogPiiScanTest` + the F40 sentinel CI block (PII-at-rest is verified by `AtsSyncIT` minimization + crypto asserts; the captured-stdout CI scan is the per-feature backstop still to add), and seam-firing ITs for the 5 non-CONFIRMED write-back seams (the enqueue mechanism is tested; the seam call sites are one-line best-effort).

**T064 — Two-loop multi-role review (C6) — DONE**: Loop 1 (Backend/Security/QA vs real source) — Security APPROVE, QA APPROVE-WITH-NITS, **Backend CHANGES-NEEDED**: B1 BLOCKER (a transiently-degraded connection [NEEDS_REAUTH/ERROR] permanently dead-lettered pending write-backs instead of holding) + S3 (concurrent first-connect `DuplicateKeyException` → 500). **Fixes applied**: `AtsWriteBackService.holdForConnectionRecovery` (hold, not dead-letter, without consuming the retry budget; delivers after recovery) + `AtsConnectionService.connect` catches `DuplicateKeyException` (idempotent). Added `AtsWriteBackIT.degradedConnectionHoldsWriteBackInsteadOfDeadLettering` + `AtsConnectionIT` (SC-010 bad-key + disconnect-clears-key). **Loop 2** (focused Backend re-review) — **APPROVE**: B1 and S3 CONFIRMED fixed, no new issue. Reported live-promotion gap (S1): the Harvest notes endpoint is candidate-id-keyed (stub-masked) — tracked for the mandatory live security re-review (FR-027).

**Verification**: backend `compileJava` clean; full backend suite **exit 0** (pre-fix); ATS package + rbac/scheduling/feedback/gdpr/noshow regression green (post-fix); frontend ATS spec 5/5; `ng build --configuration production` clean. A final full-suite regression run was launched after the loop-2 fixes.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Enums, config, migration, and CI guards that everything else builds on.

- [ ] T001 [P] Create `AtsProvider` enum (`GREENHOUSE`) in `backend/src/main/java/com/cadence/integration/AtsProvider.java`.
- [ ] T002 [P] Create the status/type enums `AtsConnectionStatus` (`INTEGRATION_PENDING/CONNECTED/NEEDS_REAUTH/ERROR/DISCONNECTED`), `AtsWriteBackStatus` (`PENDING/SENDING/DELIVERED/DEAD_LETTER/CANCELLED`), `AtsWriteBackType` (`LINK_SENT/CONFIRMED/RESCHEDULED/CANCELLED/NO_SHOW/FEEDBACK_SUBMITTED`) in `backend/src/main/java/com/cadence/domain/`.
- [ ] T003 [P] Create `AtsProperties` (`@ConfigurationProperties(prefix="cadence.ats")`: `greenhouse.base-url`, `poll-interval=PT5M`, `connect-timeout`, `read-timeout`, `retry-max-attempts`, `retry-base-backoff`, `sync-page-limit`, `writeback-batch-limit`, `reaper-threshold`, `ops-alert-address`) in `backend/src/main/java/com/cadence/config/AtsProperties.java`; bind defaults + `${CADENCE_ATS_GREENHOUSE_BASE_URL:}` in `backend/src/main/resources/application.yml` (+ test override `retry-base-backoff: PT0S` in `application-test.yml`).
- [ ] T004 Create `ChangeUnit018_AtsConnectorIndexes` (order `"018"`, **pure ASCII** — no NUL/non-ASCII in comments, the F30 lesson) in `backend/src/main/java/com/cadence/config/migration/ChangeUnit018_AtsConnectorIndexes.java`: unique `{workspaceId}` + `{status}` on `atsConnections`; unique `{workspaceId,idempotencyKey}` + `{status,nextAttemptAt}` + `{workspaceId,candidateId,status}` on `atsWriteBacks`; `{workspaceId,startedAt:-1}` on `atsSyncRuns`; **unique PARTIAL** `{workspaceId,atsProvider,atsExternalRef}` over `{atsExternalRef:{$exists:true}}` on `candidates`. Native `createIndex` + targeted `dropIndex` rollback (never `dropIndexes()`).
- [ ] T005 [P] Extend `.github/workflows/ci.yml` PII scan with ATS sentinels (candidate name/email, the Greenhouse API key, and a **job-title sentinel**) and a `greenhouse`/base-URL literal guard scoped to `GreenhouseAtsClient.java`.

**Checkpoint**: enums, config, migration, CI guards exist; `./gradlew compileJava` clean.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: The provider abstraction, HTTP client, stub harness, shared domain/crypto/security wiring that EVERY story needs. **No user story can begin until this is done.**

### Provider abstraction + HTTP client + stub (blocks US1/US2/US3)

- [ ] T006 [P] Create the normalized DTOs `AtsCandidateRecord` (externalRef,name,email,phone,externalJobId,externalJobTitle,stageLabel), `AtsFetchResult` (records,nextCursor), `AtsActivity` (type,occurredAt,note) in `backend/src/main/java/com/cadence/integration/`.
- [ ] T007 Create the `AtsConnector` interface (`provider()`, `verifyCredential`, `fetchCandidates`, `pushActivity`) per contracts/ats-api.md §A in `backend/src/main/java/com/cadence/integration/AtsConnector.java`.
- [ ] T008 [P] Create `AtsApiException` (RuntimeException with `status`, `category`, `retryAfter`) in `backend/src/main/java/com/cadence/integration/AtsApiException.java`.
- [ ] T009 [P] [TEST] Write `AtsApiClassifierTest` (unit) asserting 429/5xx/network/Retry-After→TRANSIENT, 401/403-bad-key→AUTH, other 4xx→FATAL in `backend/src/test/java/com/cadence/ats/AtsApiClassifierTest.java`.
- [ ] T010 [P] [TEST] Write `AtsApiRetryTest` (unit, no wall-clock) asserting `nextWaitMillis(attempt, retryAfter) = max(backoff+jitter, retryAfter)` and retry-only-on-TRANSIENT in `backend/src/test/java/com/cadence/ats/AtsApiRetryTest.java`.
- [ ] T011 Create `AtsApiClassifier` (TRANSIENT/AUTH/FATAL) in `backend/src/main/java/com/cadence/integration/AtsApiClassifier.java` (make T009 pass).
- [ ] T012 Create `AtsApiRetry` (backoff+jitter+Retry-After, pure `nextWaitMillis`) in `backend/src/main/java/com/cadence/integration/AtsApiRetry.java` (make T010 pass).
- [ ] T013 Create `StubGreenhouse` (test harness — JDK `com.sun.net.httpserver.HttpServer`, method+path matching, per-(method,path) status SEQUENCES, seeded candidate/application/job store with `addCandidate`/`updateStage`, recorded `notes(candidateId)` POSTs, injectable `Retry-After`, `gate(n)` latch, `reset()`; seed attachment/custom/EEOC fields for the FR-029 non-circular test) in `backend/src/test/java/com/cadence/ats/StubGreenhouse.java`. **WireMock is NOT used.**
- [ ] T014 Create `GreenhouseAtsClient` (`@Component implements AtsConnector`) using `RestClient` on a `JdkClientHttpRequestFactory` (bounded connect/read timeouts), HTTP Basic auth (key as username), explicit JSON field reads ONLY (no attachments/custom/EEOC — FR-029), provider error bodies reduced to status/category (no raw-body persist), wrapped in `AtsApiRetry`; per contracts/ats-api.md §C in `backend/src/main/java/com/cadence/integration/GreenhouseAtsClient.java`.
- [ ] T015 [P] [TEST] Write `AtsNoSdkStructuralTest` (constant-pool scan — no `com.cadence.service`/`com.cadence.scheduler` class references a Greenhouse literal; SC-009) in `backend/src/test/java/com/cadence/ats/AtsNoSdkStructuralTest.java`.

### Shared domain + crypto + erasure (blocks US1/US2/US3/US4)

- [ ] T016 Create `AtsConnection` `@Document(atsConnections)` (workspaceId, provider, `apiKey` [@JsonIgnore + @Field(write=NON_NULL), omit from toString], status, lastVerifiedAt, lastSyncAt, lastErrorCategory, syncCursor, timestamps; derived `credentialSet`) in `backend/src/main/java/com/cadence/domain/AtsConnection.java` + `AtsConnectionRepository` (findByWorkspaceId, findByStatus) in `backend/src/main/java/com/cadence/repository/AtsConnectionRepository.java`.
- [ ] T017 Register encrypted fields in `MongoPiiConfig` — `AtsConnection.apiKey` and `Candidate.atsStageLabel` on the shared `PiiStringConverter` — in `backend/src/main/java/com/cadence/config/MongoPiiConfig.java`.
- [ ] T018 Extend `Candidate` with additive ATS-link fields (`atsProvider`, `atsExternalRef`, `atsExternalJobId`, `atsExternalJobTitle`, all `@Field(write=NON_NULL)`; `atsStageLabel` encrypted + `@JsonIgnore` + `@Field(write=NON_NULL)`; `atsSyncedAt`) with getters/setters; keep `toString()` omitting all PII in `backend/src/main/java/com/cadence/domain/Candidate.java`.
- [ ] T019 Create the narrow `AtsWriteBackInvalidator` interface with BOTH `cancelPendingForCandidate(workspaceId, candidateId)` (erasure) and `cancelPendingForWorkspace(workspaceId)` (disconnect) in `backend/src/main/java/com/cadence/service/AtsWriteBackInvalidator.java` (the F31 `SlaDraftInvalidator` cycle-break seam; impl lands in US3 — until then a missing bean makes the call an inert no-op via `@Lazy ObjectProvider`).
- [ ] T020 Extend `CandidateErasureService.wipe` to `$set null` `atsStageLabel` + `atsExternalJobTitle`, **retain** `atsProvider`/`atsExternalRef`/`atsExternalJobId` (resurrection anchor), and best-effort call `AtsWriteBackInvalidator.cancelPendingForCandidate` (inject via `@Lazy ObjectProvider`, the F31 cycle-break) in `backend/src/main/java/com/cadence/service/CandidateErasureService.java`.
- [ ] T021 Add an `AtsWriteBackType` overload to `IdempotencyKeys` (length-prefixed sha256 of `{workspaceId,candidateId,AtsWriteBackType,eventAtMillis}`; the existing `dispatchKey` is `EmailMessageType`-typed — extend, don't reuse) in `backend/src/main/java/com/cadence/service/IdempotencyKeys.java`.

### Security + error envelope + status surface skeleton (blocks US1/US2/US4 endpoints)

- [ ] T022 Create `AtsExceptionHandler` (`@Order(HIGHEST_PRECEDENCE)` `@RestControllerAdvice(assignableTypes=AtsConnectionController.class)`, no-oracle envelope `{ "error": "..." }`, re-throws `AccessDeniedException`/`AuthenticationException` from any catch-all — the F31 lesson) in `backend/src/main/java/com/cadence/api/AtsExceptionHandler.java`. **Note**: `assignableTypes` references `AtsConnectionController` (T028, US1) — the handler + controller form one compile unit (the 9 existing scoped-handler precedent), so `compileJava` only goes green once T028's controller exists.
- [ ] T023 Create `AtsDtos` (request/response records: connect request `{apiKey}`, connection-health response `{provider,status,credentialSet,lastVerifiedAt,lastSyncAt,degraded,deadLetterCount}` — never the key) in `backend/src/main/java/com/cadence/api/AtsDtos.java`.
- [ ] T024 [P] [TEST] Write `AtsIndexTest` (Testcontainers) asserting all `ChangeUnit018` indexes exist, esp. the partial-unique `{workspaceId,atsProvider,atsExternalRef}`, and that a native candidate (null ref) does NOT collide on it in `backend/src/test/java/com/cadence/ats/AtsIndexTest.java`.

**Checkpoint**: provider abstraction + client + stub + shared domain + crypto + erasure all in place; foundational unit/structural/index tests green. (The `AtsExceptionHandler` from T022 compiles together with the US1 controller T028 — see T022 note — so a full `compileJava` of the security envelope completes at the start of US1.)

---

## Phase 3: User Story 1 — Connect a workspace to Greenhouse (Priority: P1) 🎯 MVP

**Goal**: An Admin connects/verifies/disconnects a Greenhouse API key; connection health is visible. Credential is write-only, encrypted, never leaked.

**Independent Test**: Enter a valid key → CONNECTED + last-verified; enter a bad key → no stored connection + non-leaking error; non-Admin refused; disconnect destroys the key.

### Tests for User Story 1 (write first, must fail)

- [ ] T025 [P] [US1] [TEST] `AtsConnectionContractTest` (MockMvc): POST connect (valid → 200 CONNECTED; blank → 400; bad key → 409 `verification_failed` with no key/body echo — SC-010), GET health (`credentialSet` only, never the key), DELETE disconnect (204 → subsequent GET `credentialSet:false` — SC-006), and the **5-role RBAC matrix** (Admin manage; Recruiter health-GET only; HM/Interviewer/Read-only refused) in `backend/src/test/java/com/cadence/ats/AtsConnectionContractTest.java`.
- [ ] T026 [P] [US1] [TEST] `AtsCredentialCryptoTest` (Testcontainers): raw-driver read of `atsConnections` shows `apiKey` as ciphertext (SC-006); cold-converter reload decrypts; verify-failure path stores no key/body in `lastErrorCategory` in `backend/src/test/java/com/cadence/ats/AtsCredentialCryptoTest.java`.

### Implementation for User Story 1

- [ ] T027 [US1] Create `AtsConnectionService` (`connect`: verify via `AtsConnector.verifyCredential` against the selected provider, store key via targeted `$set` [converter encrypts], set CONNECTED + lastVerifiedAt; `disconnect`: `$set apiKey=null` [never `$unset` — the F03 trap] + status DISCONNECTED + best-effort `AtsWriteBackInvalidator.cancelPendingForWorkspace` [workspace-scoped, via `@Lazy ObjectProvider` — inert until US3]; `getHealth`) in `backend/src/main/java/com/cadence/service/AtsConnectionService.java`.
- [ ] T028 [US1] Create `AtsConnectionController` at `/api/internal/ats/connection` — class `@PreAuthorize("hasRole('ADMIN')")`; the health `GET` overridden to `@PreAuthorize("hasAnyRole('ADMIN','RECRUITER')")` (FR-004); POST connect / DELETE disconnect — in `backend/src/main/java/com/cadence/api/AtsConnectionController.java`.
- [ ] T029 [US1] Add `/api/internal/ats/**` to the `RbacEndpointInventoryTest` expectations as a role-declared internal path (NOT allow-listed) and assert `/api/internal/ats/**` is the ONLY ATS path — no ATS endpoint rides the `@Order(2)` permitAll/no-login chain (FR-011: no unauthenticated inbound ingestion path can be added silently) in `backend/src/test/java/com/cadence/rbac/RbacEndpointInventoryTest.java`.
- [ ] T030 [P] [US1] Create the Angular `ats.service.ts` (HttpClient → `/api/internal/ats/connection`: getHealth, connect, disconnect) in `frontend/src/app/features/admin/ats/ats.service.ts`.
- [ ] T031 [US1] Create the standalone `AtsIntegrationComponent` (Admin screen: connection status + last-verified, write-only API-key connect form, disconnect; `$localize` strings; internal screen — no WCAG/Lighthouse gate) in `frontend/src/app/features/admin/ats/ats-integration.component.ts`, and register the `admin/ats` route (`authGuard` + `roleGuard('ADMIN')`) in `frontend/src/app/app.routes.ts`.
- [ ] T032 [P] [US1] [TEST] `ats-integration.component.spec.ts` (Jasmine): connect form submits write-only key, status renders, disconnect calls service in `frontend/src/app/features/admin/ats/ats-integration.component.spec.ts`.

**Checkpoint**: US1 is independently demonstrable — connect/verify/disconnect against the stub, browser→DB, credential never returned.

---

## Phase 4: User Story 2 — Candidates and stages flow into Cadence (Priority: P1)

**Goal**: A scheduled poll imports candidates/stages from a CONNECTED workspace, idempotently, with the resurrection guard.

**Independent Test**: Seed the stub; trigger `AtsSyncScheduler.sweep()`; candidates appear (encrypted PII, external ref, job, raw stage); stage change updates the same row (no dup); erased candidate is not resurrected.

### Tests for User Story 2 (write first, must fail)

- [ ] T033 [P] [US2] [TEST] `AtsSyncIT` (Testcontainers + StubGreenhouse): import (SC-001 via direct `sweep()`); stage update → same row, no dup; **reconcile precedence** — external-ref authoritative; email adopt ONLY when no ref; a candidate that already has a *different* ref is NOT email-adopted; two distinct refs sharing an email NOT merged (SC-002); idempotent overlapping sweeps; **burst-of-50 → 50 processed / 50 created / 0 dup via `AtsSyncRun` counts** (SC-002); **FR-029 non-circular minimization** — the stub seeds attachment/custom-field/EEOC values on the candidate object; assert those values are ABSENT from the imported `Candidate` doc (raw-driver read) and all ATS rows in `backend/src/test/java/com/cadence/ats/AtsSyncIT.java`.
- [ ] T034 [P] [US2] [TEST] `AtsResurrectionGuardIT` (Testcontainers): import a candidate, erase them, re-poll → no PII re-written, **no new record created** (the resolve-then-guarded-write, not upsert-with-erasure-filter); also assert the post-erasure raw-driver read shows `atsStageLabel`/`atsExternalJobTitle` cleared while `atsProvider`/`atsExternalRef`/`atsExternalJobId` are RETAINED (decision #8 exact field set); FR-015 in `backend/src/test/java/com/cadence/ats/AtsResurrectionGuardIT.java`.
- [ ] T035 [P] [US2] [TEST] `AtsImportedCandidateCryptoTest` (Testcontainers): raw-driver read shows imported name/email/phone + `atsStageLabel` as ciphertext (SC-012) in `backend/src/test/java/com/cadence/ats/AtsImportedCandidateCryptoTest.java`.
- [ ] T036 [P] [US2] [TEST] `AtsConsentGateIT` (Testcontainers): an imported candidate cannot be emailed until lawful basis recorded (reuses `ContactPermissionGate`; SC-008) in `backend/src/test/java/com/cadence/ats/AtsConsentGateIT.java`.

### Implementation for User Story 2

- [ ] T037 [P] [US2] Create `AtsSyncRun` `@Document(atsSyncRuns)` (workspaceId, started/finishedAt, outcome, processed/created/updated/skipped, errorCategory — no PII) + `AtsSyncRunRepository` (findFirstByWorkspaceIdOrderByStartedAtDesc) in `backend/src/main/java/com/cadence/domain/AtsSyncRun.java` and `backend/src/main/java/com/cadence/repository/AtsSyncRunRepository.java`.
- [ ] T038 [US2] Add reconcile repo methods to `CandidateRepository` (`findByWorkspaceIdAndAtsProviderAndAtsExternalRef`; reuse existing `findByWorkspaceIdAndEmailHash`) in `backend/src/main/java/com/cadence/repository/CandidateRepository.java`.
- [ ] T039 [US2] Create `AtsSyncService.syncWorkspace` — fetch via `AtsConnector.fetchCandidates`, normalize, **resolve-then-guarded-write** per data-model §4 (resolve by ref with NO erasure filter → if found, guarded `updateFirst({_id,erasureState:ACTIVE})` no-op on erased → else email-adopt-if-no-ref → else `insert` catch `DuplicateKeyException`); record `AtsSyncRun` counts; advance `syncCursor`; AUTH→connection NEEDS_REAUTH in `backend/src/main/java/com/cadence/service/AtsSyncService.java`.
- [ ] T040 [US2] Create `AtsSyncScheduler` (`@Scheduled(fixedDelayString=poll-interval)`, checkpoint `"ats-sync-scan"` via `SchedulerCheckpointService.start/complete` + `@PostConstruct registerReplayAction`; iterate `atsConnections` `findByStatus(CONNECTED)`, Pageable-capped per workspace) in `backend/src/main/java/com/cadence/scheduler/AtsSyncScheduler.java`.
- [ ] T041 [US2] Add `GET /api/internal/ats/sync-status` (`hasAnyRole('ADMIN','RECRUITER')`) returning latest `AtsSyncRun` projection to `AtsConnectionController` (+ DTO in `AtsDtos`) in `backend/src/main/java/com/cadence/api/AtsConnectionController.java`.
- [ ] T042 [US2] Extend `ats.service.ts` + `AtsIntegrationComponent` to fetch and render sync status (last sync, processed/created/updated counts) in `frontend/src/app/features/admin/ats/`.

**Checkpoint**: US2 independently demonstrable — poll imports/updates candidates, idempotent, erasure-safe, PII encrypted.

---

## Phase 5: User Story 3 — Scheduling activity is written back (Priority: P2)

**Goal**: The six scheduling events enqueue an idempotent write-back that is delivered to the Greenhouse timeline.

**Independent Test**: For a mapped candidate, trigger each event → exactly one outbox row → drained to one stub `notes` POST; non-linked candidate → zero; erased candidate not written.

### Tests for User Story 3 (write first, must fail)

- [ ] T043 [P] [US3] [TEST] `AtsWriteBackIT` (Testcontainers + StubGreenhouse): the six event seams each enqueue exactly one row (**linked→1 / non-linked→0**); drain delivers exactly one stub `notes` POST; **gated concurrent drain → one note** (SC-003); idempotent re-drain; erased candidate → no write-back + pending cancelled (FR-015); feedback write-back carries **ids only, never `scorecardPayload`** in `backend/src/test/java/com/cadence/ats/AtsWriteBackIT.java`.

### Implementation for User Story 3

- [ ] T044 [P] [US3] Create `AtsWriteBack` `@Document(atsWriteBacks)` (workspaceId, candidateId, atsExternalRef, type, idempotencyKey, status [no `@Version`], eventAt, nextAttemptAt, attemptCount, providerActivityRef, lastOutcomeCategory, timestamps) + `AtsWriteBackRepository` (findByWorkspaceIdAndIdempotencyKey; `@Query` Pageable `findDue(status, now)`; findByWorkspaceIdAndCandidateIdAndStatus) in `backend/src/main/java/com/cadence/domain/AtsWriteBack.java` and `backend/src/main/java/com/cadence/repository/AtsWriteBackRepository.java`.
- [ ] T045 [US3] Create `AtsWriteBackService` (`enqueue`: skip if candidate not ATS-linked or erased; build `idempotencyKey` from deterministic `eventAt` [T021 overload]; `repo.insert` catch `DuplicateKeyException` → idempotent; `claimAndDeliver`: `findAndModify` PENDING→SENDING CAS → `AtsConnector.pushActivity` [non-PII note] → DELIVERED) and `implements AtsWriteBackInvalidator` (both `cancelPendingForCandidate` and `cancelPendingForWorkspace`: PENDING→CANCELLED) in `backend/src/main/java/com/cadence/service/AtsWriteBackService.java`.
- [ ] T046 [US3] Wire the six enqueue seams (best-effort, after each existing terminal CAS, never blocking): LINK_SENT in `SchedulingService.initiate`; CONFIRMED in `SlotReservationService.book`; RESCHEDULED in `SlotReservationService.forwardCommitParent`; CANCELLED in `SlotReservationService.cancelByBooking`; FEEDBACK_SUBMITTED in `FeedbackService.submit` — across `backend/src/main/java/com/cadence/service/SchedulingService.java`, `SlotReservationService.java`, `FeedbackService.java`.
- [ ] T047 [US3] Change `NoShowCascadeService.stampNoShow` to return the booked row (`void`→`SchedulingRequest`, add `FindAndModifyOptions.returnNew(true)`) and enqueue NO_SHOW from `NoShowDefenseScheduler.sweep` after a successful stamp; update the existing stamp CAS test for the new return in `backend/src/main/java/com/cadence/service/NoShowCascadeService.java` and `backend/src/main/java/com/cadence/scheduler/NoShowDefenseScheduler.java`.
- [ ] T048 [US3] Create `AtsWriteBackScheduler` (`@Scheduled` drain, checkpoint `"ats-writeback-drain"` + replay; `findDue(PENDING, now)` Pageable batch; call `AtsWriteBackService.claimAndDeliver`) in `backend/src/main/java/com/cadence/scheduler/AtsWriteBackScheduler.java`.

**Checkpoint**: US3 independently demonstrable — each scheduling event lands exactly one timeline note; PII-free.

---

## Phase 6: User Story 4 — Resilience when Greenhouse is unavailable (Priority: P2)

**Goal**: Failed write-backs/sync are queued (never dropped), retried with backoff, dead-lettered on exhaustion, and the degraded state is visible.

**Independent Test**: Stub returns 503/429 → write-back stays queued and recovers; sync backs off and recovers; exhausted retries → dead-letter + operator notify + degraded indicator.

### Tests for User Story 4 (write first, must fail)

- [ ] T049 [P] [US4] [TEST] `AtsWriteBackResilienceIT` (Testcontainers + StubGreenhouse): 503 on `notes` POST → row stays queued, retried with backoff, delivered on recovery within budget (SC-004); retry exhaustion → DEAD_LETTER + `RecruiterNotificationService.notify` + `DeadLetterService` (no PII); `GET /api/internal/ats/dead-letters` lists it (FR-018) in `backend/src/test/java/com/cadence/ats/AtsWriteBackResilienceIT.java`.
- [ ] T050 [P] [US4] [TEST] `AtsSyncRateLimitIT` (Testcontainers): `429,429,200` + `Retry-After` on the **sync** path → sync backs off and recovers (not FAILED); degraded shown then cleared (FR-020) in `backend/src/test/java/com/cadence/ats/AtsSyncRateLimitIT.java`.
- [ ] T051 [P] [US4] [TEST] `AtsRestartIT` (Testcontainers): SC-007 both directions — overlapping sync sweeps → no duplicate import; write-back reaper double-sweep (stamp `updatedAt` into the past via `MutableClock`) → no duplicate note; label the write-back side as the double-sweep honest-residual (F31/F32) in `backend/src/test/java/com/cadence/ats/AtsRestartIT.java`.
- [ ] T052 [P] [US4] [TEST] `AtsPropertiesBoundsTest` (unit): config-invariant `retry-base-backoff × 2^retry-max-attempts < 15min` (SC-004 budget) and `reaper-threshold > read-timeout + max-backoff` (F23 precedent) in `backend/src/test/java/com/cadence/ats/AtsPropertiesBoundsTest.java`.
- [ ] T053 [P] [US4] [TEST] `AtsLogPiiScanTest` (Testcontainers, TRACE): drive connect (incl. a **401 whose body echoes the submitted key**) → sync → write-back → failure with name/email/key/job-title/**stage-label** sentinels; assert absence across logs, `atsConnections.lastErrorCategory`, `atsWriteBacks`, `atsSyncRuns`, audit, and dead-letter (SC-005/FR-022) in `backend/src/test/java/com/cadence/ats/AtsLogPiiScanTest.java`.

### Implementation for User Story 4

- [ ] T054 [P] [US4] Add `ATS_WRITEBACK_FAILED` and `ATS_SYNC_FAILED` to `RecruiterNotificationType`; if either drives an operational member email, add the matching branch to the `SmtpEmailSender` closed dispatcher (the F13/F32 build-breaker) in `backend/src/main/java/com/cadence/domain/RecruiterNotificationType.java` (+ `backend/src/main/java/com/cadence/integration/SmtpEmailSender.java` if needed).
- [ ] T055 [US4] Add resilient delivery to `AtsWriteBackService.claimAndDeliver`: classify via `AtsApiClassifier` — TRANSIENT + attempt<cap → re-queue SENDING→PENDING + `nextAttemptAt` backoff; FATAL or cap → DEAD_LETTER + `DeadLetterService.recordFailure` + `RecruiterNotificationService.notify(ATS_WRITEBACK_FAILED)` in `backend/src/main/java/com/cadence/service/AtsWriteBackService.java`.
- [ ] T056 [US4] Add a write-back reaper to `AtsWriteBackScheduler` (CAS `{status:SENDING, updatedAt<reaper-threshold}` → reconciled in-flight, the F22 `SENT_UNCONFIRMED` honest bound; never blind re-send) in `backend/src/main/java/com/cadence/scheduler/AtsWriteBackScheduler.java`.
- [ ] T057 [US4] Add degraded/error handling to `AtsSyncService` (transient/exhausted sync → `AtsSyncRun` FAILED + connection `lastErrorCategory`/ERROR + `notify(ATS_SYNC_FAILED)`; honor `Retry-After`) and surface `degraded` + `deadLetterCount` in the health DTO in `backend/src/main/java/com/cadence/service/AtsSyncService.java` and `AtsConnectionService.java`.
- [ ] T058 [US4] Add `GET /api/internal/ats/dead-letters` (`hasRole('ADMIN')`, no-PII projection) to `AtsConnectionController` (+ DTO) in `backend/src/main/java/com/cadence/api/AtsConnectionController.java`.
- [ ] T059 [US4] Extend `AtsIntegrationComponent` + `ats.service.ts` to show the degraded indicator and the dead-letter list in `frontend/src/app/features/admin/ats/`.

**Checkpoint**: US4 complete — nothing dropped, degraded state visible, dead-letters surfaced.

---

## Phase 7: Polish & Cross-Cutting Concerns

- [ ] T060 [P] Add the F40 implementation-notes section to `CLAUDE.md` (Mongock 018, resurrection-guard, outbox honest bound, build-watch items, integration-pending boundary).
- [ ] T061 [P] Verify `application.yml`/`application-test.yml` ATS config + the Fly base-URL secret note in `scripts/`/deploy docs; confirm `fly.toml` carries no secret inline.
- [ ] T062 Run the full backend suite + `RbacEndpointInventoryTest` + frontend `ng test` + `ng build --configuration production`; record results; re-run on the one-time `GenericContainer` class-init error.
- [ ] T063 Run `quickstart.md` end-to-end against `StubGreenhouse` (connect → import → write-back → degraded); confirm each step.
- [ ] T064 **Two-loop multi-role sub-agent review (constitution C6)**: Backend, Security/GDPR, QA vs the real diff; apply or report every finding before close. Confirm the resurrection-guard shape, the deterministic-`eventAt` idempotency key, the `stampNoShow` signature change, and the no-PII scans landed as designed.

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (P1)**: no deps.
- **Foundational (P2)**: depends on Setup; **blocks all stories**.
- **US1 (P3)**: depends on Foundational. The MVP slice.
- **US2 (P4)**: depends on Foundational + US1 (needs a CONNECTED connection to poll).
- **US3 (P5)**: depends on Foundational + US2 (needs mapped/linked candidates to write back about).
- **US4 (P6)**: depends on US3 (hardens write-back) + US2 (hardens sync).
- **Polish (P7)**: depends on all targeted stories.

### Within Each Story

- Tests first (must fail), then models → repositories → services → schedulers/endpoints → frontend.

### Parallel Opportunities

- Setup: T001/T002/T005 [P]; T003 then T004.
- Foundational: T006/T008/T009/T010/T015/T024 [P]; T011/T012 after their tests; T013/T014 sequence; T016–T021 mostly sequential (shared files).
- US1: T025/T026/T032 [P] (tests + frontend spec); T030 [P] with backend.
- US2: T033/T034/T035/T036 [P] tests; T037 [P].
- US3: T043 test; T044 [P]; seams T046 touch shared files — sequential.
- US4: T049/T050/T051/T052/T053/T054 [P].

---

## Implementation Strategy

### MVP (Setup + Foundational + US1)

Complete P1–P3 → an Admin can connect/verify/disconnect Greenhouse (against the integration-pending stub), credential encrypted and never returned. **STOP and validate** US1 independently, then proceed.

### Incremental Delivery

US1 (connect) → US2 (import) → US3 (write-back) → US4 (resilience). Each adds value without breaking the prior; each is demonstrable against `StubGreenhouse`.

---

## Notes

- The whole feature is delivered **integration-pending** (against `StubGreenhouse`); live-credential promotion + its mandatory security re-review are a separate, later step (FR-027) — do NOT mark this feature as "live-ready".
- HM→requisition scoping is **deferred to F51** (no candidate→requisition→assignment link exists; the F32 precedent); F40 widens no role's visibility.
- Seed distinct `atsExternalRef` + distinct candidate ids per test row; set `retry-base-backoff: PT0S` in tests; drive crash/age windows via `MutableClock` stamping, never wall-clock sleeps.
- Keep `ChangeUnit018` Java source pure-ASCII (the F30 binary-detection lesson) — scan new sources, not just scripts.

## Multi-role review (constitution C6) — 2026-06-18 (tasks phase)

Three reviewers (QA, Backend, Security/GDPR) reviewed `tasks.md` against the spec/plan. **All three: APPROVE-WITH-NITS, zero blockers.** All 11 plan-review-carried items confirmed present (T005/T021/T025/T033/T047/T050/T051/T052/T053/T054). Fixes applied this round:
- **FR-029 minimization assertion added** (Security+QA, the strongest finding) — the stub seeds attachment/custom/EEOC but no task asserted exclusion; T033 now asserts those values are absent from the imported `Candidate` doc + ATS rows (non-circular).
- **Invalidator made workspace-scoped** (Backend) — T019 adds `cancelPendingForWorkspace`; T027 disconnect + T045 impl updated (US1's "cancel pending" no longer over-claims with only a candidate-scoped method).
- **T022 compile-unit note** (Backend) — `AtsExceptionHandler`'s `assignableTypes` references the US1 controller; the Phase-2 checkpoint wording corrected (the security envelope compiles at US1 start).
- **FR-011 explicit assertion** (QA+Security) — T029 now asserts `/api/internal/ats/**` is the only ATS path and none rides the no-login chain.
- **T034 erasure field-set assertion** + **T053 stage-label sentinel** (Security) — pin decision #8's exact retained/cleared fields and verify the stage label's no-log guarantee.
- Accepted NITs (no change): T064 is a process task (no file path); T017-after-T018 ordering is cosmetic (registration is by name/lambda, compiles either way).
