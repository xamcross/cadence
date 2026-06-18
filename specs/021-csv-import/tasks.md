---
description: "Task list for F42 Standalone CSV Import Mode"
---

# Tasks: Standalone CSV Import Mode (F42)

**Input**: Design documents from `C:\Users\xamcr\Cadence\specs\021-csv-import\`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/import-api.md, quickstart.md

**Tests**: Test tasks ARE included — constitution Principle VII (Test-First & Acceptance-Driven) is mandatory for this codebase; every prior feature is TDD. Write each test, see it fail, then implement.

**Organization**: Tasks are grouped by user story (US1/US2/US3 from spec.md) for independent implementation and testing.

## Path Conventions

Web app: `backend/src/main/java/com/cadence/...`, `backend/src/test/java/com/cadence/...`, `frontend/src/app/...`. All paths below are repository-relative.

## Run flags (every backend test run)

`JAVA_HOME=C:/jdk-24.0.1`, the cached `gradle-9.4.0` binary (no download — C7), `-Dapi.version=1.41`, `DOCKER_HOST=npipe:////./pipe/docker_engine`. The first multi-class Testcontainers run after a recompile may throw the one-time `GenericContainer` class-init error — re-run.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: dependency, config, and the Mongock changeset scaffolding all stories rely on.

- [ ] T001 Add `implementation 'org.apache.commons:commons-csv:1.11.0'` to `backend/build.gradle` dependencies with the one-line Dependency-Policy justification comment (RFC-4180 parsing of untrusted candidate CSV — FR-009); confirm the build resolves it from the existing Gradle cache (no tool download — C7).
- [ ] T002 [P] Add the `cadence.import.*` block to `backend/src/main/resources/application.yml` (`max-file-size: 5MB`, `max-row-count: 10000`, `max-field-length: 4096`, `reject-ratio: 0.80`, `sweep-fixed-delay: PT5S`, `sweep-batch-limit: 20`, `job-ttl: PT24H`, `processing-threshold: PT15M`) and raise **both** `spring.servlet.multipart.max-file-size` AND `spring.servlet.multipart.max-request-size` to `6MB` (above the in-service gate — D9).
- [ ] T003 [P] Add the test overrides to `backend/src/main/resources/application-test.yml` (`cadence.import.max-file-size: 64KB`, `max-row-count: 200`, `job-ttl: PT2S`, `sweep-fixed-delay: PT0.2S`, `processing-threshold: PT3S`) for deterministic tests.
- [ ] T004 [P] Create `backend/src/main/java/com/cadence/config/ImportProperties.java` — `@ConfigurationProperties(prefix="cadence.import")` mapping the keys in T002 (Duration/DataSize/int fields + getters).

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: domain model, persistence, indexes, candidate extension, scheduler/controller/exception skeletons, and the Angular shell that EVERY user story depends on.

**⚠️ CRITICAL**: No user story work can begin until this phase is complete.

### Domain & enums

- [ ] T005 [P] Create `backend/src/main/java/com/cadence/domain/CandidateOrigin.java` — enum `{ NATIVE, ATS, CSV_IMPORT }`.
- [ ] T006 [P] Create `backend/src/main/java/com/cadence/domain/CsvImportJobStatus.java` — enum `{ ACCEPTED, PROCESSING, AWAITING_DUPLICATE_DECISION, COMPLETED, REJECTED, FAILED, EXPIRED }`.
- [ ] T007 [P] Create `backend/src/main/java/com/cadence/domain/CsvImportRowStatus.java` — enum `{ IMPORTED, REJECTED, DUPLICATE_PENDING, MERGED, SKIPPED }`.
- [ ] T008 [P] Create `backend/src/main/java/com/cadence/domain/CsvRowFailureReason.java` — enum `{ MISSING_REQUIRED, INVALID_EMAIL, MALFORMED_ROW, FIELD_TOO_LONG }`.
- [ ] T009 [P] Create `backend/src/main/java/com/cadence/domain/CsvImportRejectReason.java` — enum `{ SCHEMA_INVALID, TOO_MANY_INVALID, OVER_LIMIT }`.
- [ ] T010 [P] Create `backend/src/main/java/com/cadence/domain/CsvImportRowResult.java` — embedded POJO: `rowNumber:int`, `status:CsvImportRowStatus`, `failingField:String`, `reason:CsvRowFailureReason`, `existingCandidateId:String`, `candidateId:String` (no raw cell values); omit nulls.
- [ ] T011 [P] Create `backend/src/main/java/com/cadence/domain/CsvImportJob.java` — `@Document("csvImportJobs")` per data-model §1 (workspaceId, actorMemberId, status, originalFilename, fileId, counters, `rowResults:List<CsvImportRowResult>`, rejectionReason, expiresAt, createdAt, updatedAt, completedAt); `toString()` omits any PII-adjacent content.
- [ ] T012 [P] Create `backend/src/main/java/com/cadence/domain/CsvImportFile.java` — `@Document("csvImportFiles")` per data-model §2 (jobId, workspaceId, `dataBase64:String` `@JsonIgnore` + `@Field(write=NON_NULL)`, contentType, sizeBytes, createdAt); `toString()` omits `dataBase64`.
- [ ] T013 Extend `backend/src/main/java/com/cadence/domain/Candidate.java` — add `origin:CandidateOrigin` (`@Field(write=NON_NULL)`), `importJobId:String` (`@Field(write=NON_NULL)`), `importStageLabel:String` (`@JsonIgnore` + `@Field(write=NON_NULL)` — encrypted), `importRequisitionLabel:String` (`@Field(write=NON_NULL)`) + getters/setters; keep all four out of `toString()` except non-PII origin (importStageLabel/importRequisitionLabel MUST stay out).

### Persistence, crypto, indexes

- [ ] T014 [P] Create `backend/src/main/java/com/cadence/repository/CsvImportJobRepository.java` — `MongoRepository<CsvImportJob,String>` + `Optional<CsvImportJob> findByWorkspaceIdAndId(...)`, `@Query`+`Pageable` `findDue(CsvImportJobStatus,Instant,Pageable)` (ACCEPTED/createdAt), `findExpiredAwaiting(Instant,Pageable)`, `findOrphanedProcessing(Instant,Pageable)` (the F12 explicit-`@Query`+`Pageable` lesson — never a derived multi-criteria method).
- [ ] T015 [P] Create `backend/src/main/java/com/cadence/repository/CsvImportFileRepository.java` — `MongoRepository<CsvImportFile,String>` + `Optional<CsvImportFile> findByJobId(String)`, `void deleteByJobId(String)`.
- [ ] T016 Extend `backend/src/main/java/com/cadence/config/MongoPiiConfig.java` — register `PiiStringConverter` on `CsvImportFile.class` field `"dataBase64"` and `Candidate.class` field `"importStageLabel"` (the `emailProviderCredential`/`statusToken` String-converter precedent — NOT `byte[]`).
- [ ] T017 Create `backend/src/main/java/com/cadence/config/migration/ChangeUnit020_CsvImportIndexes.java` — `@ChangeUnit(id="020-csv-import-indexes", order="020", author="system")`, PURE ASCII (the F30 NUL/binary lesson), native `createIndex` + targeted `dropIndex` rollback (never `dropIndexes()`). Indexes per data-model §4: `csvImportJobs {workspaceId,_id}`, `{status,createdAt}`, `{status,expiresAt}`, `{status,updatedAt}`; `csvImportFiles` unique `{jobId}` + optional TTL on `{createdAt}` (48h backstop, > job-ttl); `candidates {workspaceId,origin}` (non-unique) and **unique PARTIAL** `{workspaceId,emailHash}` over `{emailHash:{$exists:true}, origin:"CSV_IMPORT"}`.

### Skeletons (compile-ready, behavior filled per story)

- [ ] T018 [P] Create `backend/src/main/java/com/cadence/service/CsvImportService.java` skeleton — methods `accept(...)`, `status(...)`, `resolve(...)` (signatures per contracts/import-api.md §Internal service contract); inject repos, `ImportProperties`, `Clock`, `CandidateRateLimiter`.
- [ ] T019 [P] Create `backend/src/main/java/com/cadence/api/CsvImportController.java` skeleton — `@RestController @RequestMapping("/api/internal/import") @PreAuthorize("hasAnyRole('ADMIN','RECRUITER')")`; `POST /csv` (multipart), `GET /{jobId}/status`, `POST /{jobId}/resolve`; bind `SessionService.Principal` for `workspaceId()`/`memberId()`.
- [ ] T020 [P] Create `backend/src/main/java/com/cadence/api/CsvImportExceptionHandler.java` — `@Order(Ordered.HIGHEST_PRECEDENCE) @RestControllerAdvice(assignableTypes=CsvImportController.class)`; map `RbacExceptions.ScopedNotFoundException`→404 `not_found` (byte-identical, no message divergence — the F31 lesson), `MaxUploadSizeExceededException`/`MultipartException`→400 `invalid_import`, an `InvalidImportRequest`→400, a wrong-state→409 `invalid_state`; catch-all `RuntimeException`→500 but **re-throw** `AccessDeniedException`/`AuthenticationException` (the F31 lesson). Verify (via T041) that an over-cap multipart resolves to THIS scoped advice and not the default `/error` — two scoped multipart handlers (this + `WorkspaceExceptionHandler`) now coexist.
- [ ] T021 [P] Create `backend/src/main/java/com/cadence/scheduler/CsvImportScheduler.java` skeleton — `TASK_NAME="csv-import-sweep"`, `@PostConstruct registerReplay()` → `checkpoints.registerReplayAction(TASK_NAME, this::sweep)`, `@Scheduled(fixedDelayString="${cadence.import.sweep-fixed-delay:PT5S}") sweep()` wrapping `checkpoints.start/complete` (the `EmailDispatchScheduler` shape).
- [ ] T022 [P] Create `frontend/src/app/features/admin/csv-import/csv-import.service.ts` — `HttpClient` to `/api/internal/import`: `upload(file)` (FormData, no Content-Type), `status(jobId)`, `resolve(jobId, decisions)`.
- [ ] T023 [P] Create `frontend/src/app/features/admin/csv-import/csv-import.component.ts` standalone shell + add the route in `frontend/src/app/app.routes.ts`: `path: 'admin/csv-import', canActivate:[authGuard, roleGuard('ADMIN','RECRUITER')], loadComponent: ...` (internal screen — no §IX gate, the F50/F51 precedent).

**Checkpoint**: project compiles; collections/indexes bootstrap; controller is RBAC-gated and inventory-clean. User stories can begin.

---

## Phase 3: User Story 1 - Import a candidate list from CSV without an ATS (Priority: P1) 🎯 MVP

**Goal**: a Recruiter uploads a well-formed CSV → `202`+jobId → background worker imports every valid row as a `Candidate` (PII encrypted, consent fail-closed, CSV provenance) → status reflects completion.

**Independent Test**: upload a clean CSV; poll status to COMPLETED; assert candidates exist with `name`/`email` ciphertext at rest, `origin=CSV_IMPORT`; attempt an email send → blocked (`NO_BASIS`).

### Tests for User Story 1 (write first, must fail) ⚠️

- [ ] T024 [P] [US1] Create `backend/src/test/java/com/cadence/csvimport/CsvImportItBase.java` — extends `BaseIntegrationTest`; `@BeforeEach` `mongoTemplate.remove(new Query(), …)` for `CsvImportJob`, `CsvImportFile`, `Candidate` (NEVER `dropCollection` — the F00.1 lesson); CSV fixture strings + `uploadCsv(...)`/`pollStatus(...)` helpers; seed an Admin/Recruiter principal.
- [ ] T025 [P] [US1] Contract test `backend/src/test/java/com/cadence/csvimport/CsvImportUploadContractTest.java` (MockMvc) — `POST /csv` with a valid multipart returns `202` + `{jobId,status:ACCEPTED}`; asserts the handler returns before any row is committed (SC-002/SC-013 structural: 0 candidates immediately after 202, before the sweep runs). Also upload a **structurally-malformed** CSV and assert it STILL returns `202 ACCEPTED` (parse is deferred — the malformed row surfaces only later via status), proving no parse happens on the request thread.
- [ ] T026 [P] [US1] Integration test `backend/src/test/java/com/cadence/csvimport/CsvImportHappyPathIT.java` — upload clean 3-row CSV (with stage + requisition cells) → drive `sweep()` → 3 candidates created, `origin=CSV_IMPORT`, `importJobId` set, **`importStageLabel`/`importRequisitionLabel` populated verbatim from the CSV cells** (US1-2 label fidelity), job COMPLETED, counters correct, blob disposed (`findByJobId` empty), `fileId` null (SC-001/SC-012/SC-015-disposal). Also assert SC-014: the F40/F41 ATS reconcile lookup `findByWorkspaceIdAndAtsProviderAndAtsExternalRef` returns no match for the imported candidate (it has null `atsProvider`).
- [ ] T027 [P] [US1] Integration test `backend/src/test/java/com/cadence/csvimport/CsvImportPiiAtRestIT.java` — raw-driver read of `candidates` shows `name`/`email`/`phone`/`importStageLabel` as ciphertext; raw read of `csvImportFiles` (before disposal) shows `dataBase64` ciphertext (SC-007).
- [ ] T028 [P] [US1] Integration test `backend/src/test/java/com/cadence/csvimport/CsvImportConsentGateIT.java` — an imported candidate has null `lawfulBasis`; `ContactPermissionGate.evaluate` returns `NO_BASIS`; an attempted send is blocked (SC-008).
- [ ] T029 [P] [US1] Structural test `backend/src/test/java/com/cadence/csvimport/CsvParserConfinementTest.java` — constant-pool scan of `build/classes/java/main/com/cadence/service/*.class` asserts no class except `CsvImportProcessor` references `org/apache/commons/csv` (the `MailTransportSwapTest` precedent).

### Implementation for User Story 1

- [ ] T030 [P] [US1] Create `backend/src/main/java/com/cadence/service/CsvRow.java` — normalized POJO (name/email/phone/stage/requisition + `rowNumber`) so the service layer never touches the parser type.
- [ ] T031 [US1] Implement `CsvImportService.accept(...)` in `backend/src/main/java/com/cadence/service/CsvImportService.java` — enforce in-service size/empty checks, `CandidateRateLimiter.tryAcquire` (429 — note: the existing limiter is keyed by `SchedulingProperties`, reused here as an advisory shared cap; no new config), store bytes as base64 in a `CsvImportFile`, insert `CsvImportJob{ACCEPTED, expiresAt=now+job-ttl, originalFilename}`, return `{jobId,ACCEPTED}`; NO parse/validate/commit here (SC-002/SC-013). **Never log `originalFilename`** (recruiter-chosen, the one PII-adjacent String on the hot path the sentinel scan won't catch).
- [ ] T032 [US1] Implement `CsvImportProcessor` in `backend/src/main/java/com/cadence/service/CsvImportProcessor.java` (happy path) — load the blob, parse with Commons CSV (`setHeader().setSkipHeaderRecord(true).setIgnoreSurroundingSpaces(true)`, BOM-safe), map header columns case-insensitively to `CsvRow` (logical `getRecordNumber()`), and for each valid row create a candidate via `CandidateService.create` with CSV provenance. Idempotency: record each row's `CsvImportRowResult` on the job **before/with** the candidate write; skip a row whose result is already present (restart-safe — SC-010).
- [ ] T033 [US1] Add the CSV-provenance create path to `backend/src/main/java/com/cadence/service/CandidateService.java` — an overload (or post-set within `create`) that stamps `origin=CSV_IMPORT`, `importJobId`, `importStageLabel`, `importRequisitionLabel` while reusing the existing encrypt + `emailHash` + `lastContactAt` + `RECORD_CREATED` audit body; existing native/ATS callers unchanged (no signature break — the F13/F32 build-breaker lesson).
- [ ] T034 [US1] Implement the claim + dispatch in `backend/src/main/java/com/cadence/scheduler/CsvImportScheduler.java` `sweep()` — `findDue(ACCEPTED, now, page)` → per-job `findAndModify {_id,status:ACCEPTED→PROCESSING}` single-winner claim → `CsvImportProcessor.process(job)` → on clean completion CAS to COMPLETED + dispose blob; wrap each job in try/catch(RuntimeException) so one failure doesn't starve the batch (the F41 isolation lesson). **On the catch branch**: dispose the blob, CAS the job → FAILED, and call `DeadLetterService.recordFailure(TASK_NAME, <cause class only — never `e.getMessage()`, the F22 lesson>, jobId)`. Blob disposal must therefore run on **every** terminal path (COMPLETED here, REJECTED/OVER_LIMIT/SCHEMA in T044, FAILED here, EXPIRED/orphan in T056).
- [ ] T035 [US1] Implement `CsvImportService.status(...)` — workspace-scoped `findByWorkspaceIdAndId`; empty → `ScopedNotFoundException` (no-oracle 404, SC-015); map to `JobStatusResponse` (counts + value-free `rowResults`).
- [ ] T036 [US1] Wire `CsvImportController` `POST /csv` (→`accept`) and `GET /{jobId}/status` (→`status`) in `backend/src/main/java/com/cadence/api/CsvImportController.java`; ensure logs reference only `jobId`/`rowNumber`/`candidateId` (FR-017; `.name()` Strings only — the logstash enum→kv footgun).

**Checkpoint**: a clean CSV imports end-to-end browser→DB; US1 independently testable.

---

## Phase 4: User Story 2 - Per-row validation with actionable feedback (Priority: P1)

**Goal**: invalid rows are reported per-row (row number + field + value-free reason) while valid rows still import; schema-invalid / >80%-invalid / over-limit files are rejected wholesale; nothing leaks to logs.

**Independent Test**: upload a mixed valid/invalid CSV → valid rows imported, each invalid row reported with row number + field/reason; a >80%-invalid file commits zero; an over-size file is refused 400.

### Tests for User Story 2 (write first, must fail) ⚠️

- [ ] T037 [P] [US2] Unit test `backend/src/test/java/com/cadence/csvimport/CsvRowValidatorTest.java` — required-field/blank, malformed email, field-too-long → correct `CsvRowFailureReason`; reason is value-free (asserts the enum, no cell value); email normalization (trim+lowercase) matches `PiiCrypto.emailHash` input.
- [ ] T038 [P] [US2] Unit test `backend/src/test/java/com/cadence/csvimport/RejectRatioTest.java` — `failures/N > 0.80` math: 5 rows/4 malformed = exactly 80% → commits 1; 5/5 = 100% → reject; duplicates excluded from numerator but counted in N; N=0 → COMPLETED-0 (no divide-by-zero) (SC-003/D7).
- [ ] T039 [P] [US2] Integration test `backend/src/test/java/com/cadence/csvimport/CsvImportValidationIT.java` — mixed file: valid rows imported, each invalid row a `CsvImportRowResult{rowNumber,failingField,reason}`; an unterminated-quote/embedded-newline row → `MALFORMED_ROW` per-row (logical row number, not physical line), file not crashed (SC-003, FR-009). Assert the `GET /status` `JobStatusResponse` (US2-5) returns the imported/rejected/duplicate **counts AND** the per-row error list AND (for a mixed valid/invalid/duplicate file) the per-row duplicate list together.
- [ ] T040 [P] [US2] Integration test `backend/src/test/java/com/cadence/csvimport/CsvImportWholeFileRejectIT.java` — a >80%-invalid file → job REJECTED `TOO_MANY_INVALID`, zero candidates; a missing `name`/`email` header → REJECTED `SCHEMA_INVALID`; a file exceeding `max-row-count` (test override 200) → REJECTED `OVER_LIMIT`, zero candidates (SC-003/SC-009, FR-008/FR-010/FR-004). In every reject case assert the blob is disposed (`findByJobId` empty, `fileId` null) — terminal-path disposal coverage (FR-021a).
- [ ] T041 [P] [US2] Contract test `backend/src/test/java/com/cadence/csvimport/CsvImportLimitsContractTest.java` (MockMvc) — an over-the-6MB-cap multipart part → `400 invalid_import` resolved by **`CsvImportExceptionHandler`** (the assignableTypes-scoped advice, not the default `/error` and not `WorkspaceExceptionHandler`), mapped from `MaxUploadSizeExceededException`/`MultipartException`, not a 500 (SC-009, D9). Separately run the existing `BrandingIntegrationTest` to confirm the logo path stays green under the raised caps (its own 1 MB in-service gate is independent — it does not exercise the multipart cap).
- [ ] T042 [P] [US2] PII-log-scan test `backend/src/test/java/com/cadence/csvimport/CsvImportLogPiiScanTest.java` — drive a failing/malformed import with PII + formula sentinels in cells; assert no candidate name/email/phone/raw-cell value appears in captured logs, the job doc, `rowResults`, **or the dead-letter record** (the FAILED path — confirm `recordFailure` got only the cause class, not `CSVException.getMessage()`); a `CSVException` is reduced to a value-free cause (SC-005, FR-017).

### Implementation for User Story 2

- [ ] T043 [P] [US2] Implement `backend/src/main/java/com/cadence/service/CsvRowValidator.java` — pure `Optional<CsvRowFailureReason> validate(CsvRow)`: required `name`/`email`, email-format, field-length; value-free reasons only.
- [ ] T044 [US2] Add header-schema validation + the per-row validation loop + the >80%/`SCHEMA_INVALID`/`OVER_LIMIT` reject gates to `CsvImportProcessor` — count `N`/failures per D7, exclude duplicates from the failure numerator, record per-row results; on whole-file reject commit nothing, **dispose the blob**, and CAS job → REJECTED with `rejectionReason`. Reduce any `CSVException`/`IOException` to a value-free **cause class** (`e.getClass().getSimpleName()`, never `e.getMessage()` — the F22 lesson) before logging, storing in `reason`, or passing to `DeadLetterService.recordFailure` (FR-009/FR-017).
- [ ] T045 [US2] Enforce the in-service `max-row-count` + `max-field-length`/total-parsed-cell bound in `CsvImportProcessor`/`accept` (FR-004 memory guard) → `OVER_LIMIT` reject; ensure the status `rowResults` are bounded by `max-row-count`.

**Checkpoint**: US1 + US2 both work; validation feedback is per-row and value-free.

---

## Phase 5: User Story 3 - Duplicate detection with merge-or-skip resolution (Priority: P2)

**Goal**: a row matching an existing workspace candidate (by `emailHash`) is flagged `DUPLICATE_PENDING` (clean rows still commit); the recruiter resolves per-row merge/skip; intra-file duplicates collapse; merge is atomic + non-resurrecting; an abandoned decision auto-expires to skip.

**Independent Test**: pre-seed a candidate, upload a file with that email + clean rows → clean rows commit, the dupe is flagged, job AWAITING; resolve merge (updates existing) / skip (unchanged); intra-file dupes collapse to one; TTL expiry defaults remaining to skip.

### Tests for User Story 3 (write first, must fail) ⚠️

- [ ] T046 [P] [US3] Unit test `backend/src/test/java/com/cadence/csvimport/IntraFileDedupTest.java` — two rows sharing a normalized email collapse to one candidate, counted once (SC-004, FR-013).
- [ ] T047 [P] [US3] Integration test `backend/src/test/java/com/cadence/csvimport/CsvImportDuplicateFlagIT.java` — pre-seed candidate; upload dupe + clean rows → clean rows IMPORTED, dupe `DUPLICATE_PENDING` (not committed), job AWAITING_DUPLICATE_DECISION (SC-004, FR-011/FR-015).
- [ ] T048 [P] [US3] Contract test `backend/src/test/java/com/cadence/csvimport/CsvImportResolveContractTest.java` (MockMvc) — `POST /{jobId}/resolve` MERGE/SKIP → 200 + updated status; resolve on a non-AWAITING job → 409 `invalid_state`; re-POST the same decision is idempotent (asserts `mergedCount`/`skippedCount` do NOT double-increment); unknown rowNumber → 400; 5-role RBAC (ADMIN/RECRUITER 200, HM/INTERVIEWER/READ_ONLY 403) (FR-012, SC-011).
- [ ] T049 [P] [US3] Integration test `backend/src/test/java/com/cadence/csvimport/CsvImportMergeIT.java` — MERGE updates name/phone/`importStageLabel`/`importRequisitionLabel` from non-empty cells only (blank cell leaves existing value); never touches email/emailHash/consent/erasure/retention/origin/ATS keys (FR-014).
- [ ] T050 [P] [US3] Integration test `backend/src/test/java/com/cadence/csvimport/MergeErasureRaceIT.java` — erase the matched candidate, then resolve MERGE → active-state-guarded `updateFirst` no-ops (matchedCount 0), no PII resurrection, row recorded skipped-equivalent (FR-014, SC-012).
- [ ] T051 [P] [US3] Concurrency test `backend/src/test/java/com/cadence/csvimport/ConcurrentImportIT.java` — two concurrent jobs importing the same NEW email (gated latch, the F40 overlapping-sync shape) → exactly one candidate via the partial-unique index + `DuplicateKeyException` catch (SC-013).
- [ ] T052 [P] [US3] Integration test `backend/src/test/java/com/cadence/csvimport/CsvImportTtlExpiryIT.java` — stamp `expiresAt` into the past (no wall-clock sleep — the F23 lesson) → reaper defaults unresolved dupes to SKIP, disposes the blob, job → EXPIRED (SC-015, FR-021a).

### Implementation for User Story 3

- [ ] T053 [US3] Add duplicate detection to `CsvImportProcessor` — normalize email + `emailHash`; intra-file collapse (first wins); `findByWorkspaceIdAndEmailHash` against an ACTIVE candidate → record `DUPLICATE_PENDING` with `existingCandidateId` (store `emailHash`, never plaintext — FR-021); the new-candidate `insert` catches `DuplicateKeyException` on the partial-unique index → re-read/flag (SC-013). If any pending remain after processing → CAS job → AWAITING_DUPLICATE_DECISION, else COMPLETED + dispose blob.
- [ ] T054 [US3] Implement `CsvImportService.resolve(...)` — require AWAITING state (else 409); apply per-row + `defaultAction`; MERGE = atomic `updateFirst({_id,workspaceId,erasureState:ACTIVE} → $set non-empty content fields)` (name/phone/importStageLabel/importRequisitionLabel only); SKIP = unchanged; idempotent (skip already-resolved rows); audit the resolution actor (which may differ from the uploader) via `CandidateAuditService.append(...)` using the existing `CandidateEventType.STAGE_CHANGED` + `CandidateAuditOutcome.RECORDED` (nearest existing pair for a label update — adding a dedicated `CSV_MERGED` value is optional and NOT a build-breaker since no `switch` consumes the enum); when no pending remain → COMPLETED + dispose blob.
- [ ] T055 [US3] Wire `CsvImportController` `POST /{jobId}/resolve` (→`resolve`) in `backend/src/main/java/com/cadence/api/CsvImportController.java`.
- [ ] T056 [US3] Add the TTL/orphan reaper pass to `CsvImportScheduler.sweep()` — `findExpiredAwaiting(now)` → default remaining dupes to SKIP + dispose blob + CAS → EXPIRED; `findOrphanedProcessing(now-threshold)` → dispose + FAILED (invariant `processing-threshold > sweep-delay + max per-job time`).
- [ ] T057 [US3] Extend `backend/src/main/java/com/cadence/service/CandidateErasureService.java` `wipe` — `$set null` `importStageLabel` (converter — NEVER `$unset`, the F03 trap) and `importRequisitionLabel`; retain `origin`/`importJobId`; fold into the existing single guarded wipe `updateFirst`. (No new invalidator — an import holds no candidate-keyed sender record.)

**Checkpoint**: all three stories independently functional.

---

## Phase 6: Frontend, Polish & Cross-Cutting Concerns

- [ ] T058 [US1] Implement `frontend/src/app/features/admin/csv-import/csv-import.component.ts` — file picker → `upload()` (FormData), poll `status()` rendering ACCEPTED/PROCESSING/AWAITING/COMPLETED/REJECTED/EXPIRED + counters + per-row results, and a merge/skip resolve UI for duplicates. `$localize` all strings.
- [ ] T059 [P] Create `frontend/src/app/features/admin/csv-import/csv-import.component.spec.ts` (Jasmine) — upload posts FormData (no Content-Type), poll renders the state machine + per-row results, resolve sends merge/skip decisions; confirm no axe/Lighthouse gate (internal screen — F50/F51 precedent).
- [ ] T060 [P] Implement `backend/src/main/java/com/cadence/service/CsvInjectionEscaper.java` + `backend/src/test/java/com/cadence/csvimport/CsvInjectionEscaperTest.java` — `escapeForSpreadsheet(String)` neutralizes a leading `=`,`+`,`-`,`@`,`|`, tab(`\t`), CR(`\r`), or post-BOM/leading-whitespace-then-trigger, AND RFC-4180-quotes a value containing delimiter/quote/newline on emit; a legitimate `+44…`/`-`-led value is stored verbatim and only escaped at emit (SC-006, FR-018).
- [ ] T061 [P] Add the F42 PII-scan block to `.github/workflows/ci.yml` — fail on `SENTINELF42NAME_zz9|SENTINELF42EMAIL_zz9|SENTINELF42PHONE_zz9|SENTINELF42FORMULA_zz9` in `test-output.txt` (the F40/F41 sentinel-block precedent); ensure the scan covers `csvImportJobs.rowResults` and deliberately excludes `originalFilename`.
- [ ] T062 [P] Index-bootstrap test `backend/src/test/java/com/cadence/csvimport/CsvImportIndexTest.java` — assert `ChangeUnit020` created every index incl. the unique-partial `{workspaceId,emailHash}` over `origin:CSV_IMPORT` and the `csvImportFiles` unique `{jobId}` (the F40 index-test precedent).
- [ ] T063 [P] Restart-idempotency test `backend/src/test/java/com/cadence/csvimport/CsvImportRestartIT.java` — double-`sweep()` over a partially-processed job produces no duplicate candidates / no lost rows (the F31/F40 double-sweep proxy — label the honest bound that it is not a true kill-9) (SC-010).
- [ ] T064 Run `RbacEndpointInventoryTest` (`backend/src/test/java/com/cadence/rbac/RbacEndpointInventoryTest.java`) and confirm the three `/api/internal/import/**` endpoints are covered by method security (build-time deny-by-default — no allow-list change needed).
- [ ] T065 Run the full quickstart.md flow (upload → status → resolve → consent-blocked → ciphertext) against a local backend; record results in the task notes.

---

## Phase 7: Constitution C6 — Two-loop Multi-Role Implementation Review

- [ ] T066 Loop 1: three role sub-agents (Backend/DevOps, Security/GDPR, QA) review the implemented diff against the spec/plan and real source; capture findings tagged BLOCKER/SHOULD-FIX/NIT. Include a byte-level non-ASCII scan of `ChangeUnit020...java` (the F30/Principle V lesson) and a `./gradlew test` compile/run record (Principle V verification gate).
- [ ] T067 Loop 2: apply all loop-1 findings; re-review the fixes (focused) until Backend + Security APPROVE and QA APPROVE(-WITH-NITS); record the residuals (e.g. the double-sweep-restart proxy, CSV-vs-NATIVE concurrent best-effort bound, captured-stdout PII-scan backstop) in CLAUDE.md Implementation Notes.

---

## Dependencies & Execution Order

### Phase dependencies

- **Setup (P1)** → no deps; start immediately.
- **Foundational (P2)** → depends on Setup; **BLOCKS all user stories**.
- **US1 (P3)** → depends on Foundational. **MVP.**
- **US2 (P4)** → depends on Foundational; builds on the US1 processor (shares `CsvImportProcessor`); independently testable.
- **US3 (P5)** → depends on Foundational; builds on the US1/US2 processor + the partial-unique index (T017); independently testable.
- **Polish (P6)** + **Review (P7)** → after the desired stories.

### Within each story

Tests (write first, must fail) → models/POJOs → service logic → scheduler/endpoint wiring. `CsvImportProcessor` is extended across US1→US2→US3 (sequential on that file); the partial-unique index (T017) must exist before T053's `DuplicateKeyException` path.

### Parallel opportunities

- Setup: T002/T003/T004 in parallel after T001.
- Foundational: all enum/POJO/repo tasks T005–T012, T014–T015 in parallel; T013/T016/T017 touch shared files (sequence); skeletons T018–T023 in parallel.
- Within a story, all `[P]` test tasks run in parallel (different files); implementation tasks that touch `CsvImportProcessor`/`CsvImportService`/the controller are sequential.

---

## Implementation Strategy

### MVP first (US1)

1. Setup → Foundational → US1 → **STOP and validate** the clean-import flow browser→DB (the §II demonstrable leg). Deploy/demo.

### Incremental delivery

2. US2 (validation feedback) → test → demo. 3. US3 (duplicates + TTL) → test → demo. 4. Polish + the two-loop review → close.

---

## Notes

- `[P]` = different files, no incomplete-task dependency.
- Tests precede implementation (Principle VII); verify red before green.
- `CsvImportProcessor`, `CsvImportService`, `CsvImportController`, and `CsvImportScheduler` each grow across stories — do NOT mark them `[P]` against each other.
- Never log a raw cell value or a `CSVException` message (reduce to cause class — FR-017). Never `$unset` a converter field (the F03 trap). Mongock source pure-ASCII (the F30 lesson).
- Honest bounds to carry to CLAUDE.md: the restart test is a double-sweep proxy (not a true kill-9); CSV-vs-NATIVE concurrent same-new-email is best-effort (CSV-vs-CSV is guaranteed by the partial-unique index); the PII scan asserts persisted docs + the CI grep is the captured-stdout backstop; **SC-002's 2 s wall-clock is asserted via the structural no-work-before-202 proof (T025), not a timed assertion**; **SC-011's "no visibility widening" is the default-closed posture — full HM-requisition scoping is deferred to F51 (no candidate→requisition link exists), the F40/F32 precedent**; **candidate retention parity (US1-3 / edge "retention conflict") is covered by reuse of `CandidateService.create` (sets `lastContactAt` → the existing `{workspaceId,lastContactAt}` retention scan), not a dedicated F42 test**.
