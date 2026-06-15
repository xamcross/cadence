# Tasks: GDPR Baseline — Consent, Erasure & Audit Log

**Input**: Design documents from `C:\Users\xamcr\Cadence\specs\005-gdpr-baseline\`
**Prerequisites**: plan.md, spec.md, research.md (D1–D14), data-model.md, contracts/gdpr-api.md, quickstart.md

**Tests**: INCLUDED and written FIRST — the constitution (§VII Test-First & Acceptance-Driven) is non-negotiable for backend business logic and acceptance paths. Each story's tests are authored before its implementation and must fail first.

**Organization**: Tasks grouped by user story (US1–US7) for independent implementation/testing. Priorities from spec.md: US1/US2/US3/US6 = P1, US4/US5 = P2, US7 = P3.

## Path Conventions (web app — see plan.md Structure)

- Backend main: `backend/src/main/java/com/cadence/`
- Backend test: `backend/src/test/java/com/cadence/gdpr/`
- Frontend: `frontend/src/app/`
- All integration tests extend `BaseIntegrationTest` (shared `@ServiceConnection` singleton `mongo:7`), clean `candidates`, `auditLog`, `erasureRequests` AND `authAuditLog` in `@BeforeEach` via `mongoTemplate.remove(new Query(), Type.class)` — **never `dropCollection`** (drops the Mongock `001`/`005` indexes; CLAUDE.md F00.1). Retention tests MUST **also** `remove(SchedulerCheckpoint.class)` (a leftover row contaminates the missed-fire replay test). Classes seeding members per role (T049) also `remove(...)` `Member`/`Session` (never `dropCollection` — drops the F01 `emailHash` unique index). Use `@MockBean` (Boot 3.3, not `@MockitoBean`) and `@Import` the F01 `MutableClock` test config where time matters.
- **Seeding rule**: seed candidates via `CandidateService.create(...)` (production-path), **not** raw `save`. Two sanctioned deviations: (a) `ContactPermissionGateTest` is a pure Mockito unit over hand-built `Candidate` states (no Mongo); (b) retention boundary fixtures set `lastContactAt` explicitly via a targeted `$set` after `create` (the only way to reach a specific past instant).
- **Zero new runtime dependencies** — PII crypto reuses `PiiCrypto`/`PiiStringConverter`/`MongoPiiConfig`; the retention scan reuses `SchedulerCheckpointService` + `@Scheduled` (`@EnableScheduling` already present); RBAC reuses F02 `@PreAuthorize` (research C4).

## Shared-file sequencing note

`CandidateGdprController.java` (US1 basis + US2 erasure + US3 audit), `CandidateService.java` (foundational create + US1 basis methods), `frontend/.../candidate-erasure-action.ts` (US1 basis + US2 erasure), and `frontend/.../app.routes.ts` + `shell.component.ts` (foundational scaffold + US7 finalize) are each touched by multiple stories. Tasks editing the **same** file are NOT `[P]` relative to each other and run in task-ID order; the controller/service skeletons are built in Foundational so each story only *adds* its handler/method. The **shared wipe** (`CandidateErasureService.wipe`, T029) is built in US2 and reused by US4 (confirm) and US5 (retention delete) — those stories' implementations depend on T029, but each remains independently testable.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Configuration only — F04 adds no new dependency or scaffold.

- [X] T001 [P] Verify `backend/build.gradle` adds **no** new runtime dependency (gate C4); confirm `@EnableScheduling` is present (on `CadenceApplication` or a config) so `RetentionScanTask`'s `@Scheduled` fires, and that `SchedulerCheckpointService` + `PiiCrypto`/`MongoPiiConfig` are reachable for reuse. Record the static check in task notes.

---

## Phase 2: Foundational (Blocking Prerequisites)

**[!] CRITICAL**: No user story can begin until this phase is complete. Builds the shared backbone: the candidate domain + enums, the three collections + migration, the PII converter registration, the append-only audit service, the canonical-create seam, the DTOs/exception handler, the three controller skeletons, and the frontend GDPR service + route/nav scaffold.

### Backend — enums & domain

- [X] T002 [P] Create enums `LawfulBasis` (`CONSENT, LEGITIMATE_INTEREST, CONTRACT`) and `ErasureState` (`ACTIVE, ERASED`) in `backend/src/main/java/com/cadence/domain/LawfulBasis.java` and `ErasureState.java` (data-model §1).
- [X] T003 [P] Create enums `CandidateEventType` (`RECORD_CREATED, BASIS_RECORDED, BASIS_WITHDRAWN, ERASURE_REQUESTED, ERASURE_REQUEST_CONFIRMED, ERASURE_REQUEST_REJECTED, ERASURE_COMPLETED, RETENTION_FLAGGED, RETENTION_FLAG_CLEARED, RETENTION_DELETED` + the forward-contract values `MESSAGE_SENT, BOOKING_CHANGED, STAGE_CHANGED`) and `CandidateAuditOutcome` (`CREATED, RECORDED, WITHDRAWN, REQUESTED, CONFIRMED, REJECTED, FLAGGED, CLEARED, DELETED, OPERATOR, CANDIDATE_REQUEST, RETENTION`) in `backend/src/main/java/com/cadence/domain/` (data-model §2).
- [X] T004 [P] Create enums `RequestStatus` (`PENDING, RESOLVED_CONFIRMED, RESOLVED_REJECTED`) and `ErasureReasonCode` (closed vocabulary, e.g. `OPERATOR, CANDIDATE_REQUEST, RETENTION, NOT_A_CANDIDATE, OTHER`) in `backend/src/main/java/com/cadence/domain/` (data-model §3).
- [X] T005 [P] Create `Candidate` `@Document("candidates")` in `backend/src/main/java/com/cadence/domain/Candidate.java` per data-model §1: `id, workspaceId, name, email, phone` (PII), `emailHash` annotated `@Field(value="emailHash", write = Field.Write.NON_NULL)`, `lawfulBasis, basisRecordedAt, basisActorMemberId, basisWithdrawn, basisWithdrawnAt, erasureState (default ACTIVE), erasedAt, retentionFlagged (default false), retentionFlaggedAt, lastContactAt, createdAt`. Hand-write `toString()` to include **only** `id/workspaceId/erasureState` (never name/email/phone — D10). Depends on T002.
- [X] T006 [P] Create `CandidateAuditEvent` `@Document("auditLog")` in `backend/src/main/java/com/cadence/domain/CandidateAuditEvent.java`: `id, workspaceId, candidateId, eventType (CandidateEventType), outcome (CandidateAuditOutcome), actorMemberId (nullable), occurredAt (Instant)`. **No free-text value column**; the `_id` ObjectId is the order tiebreaker (data-model §2). Depends on T003.
- [X] T007 [P] Create `ErasureRequest` `@Document("erasureRequests")` in `backend/src/main/java/com/cadence/domain/ErasureRequest.java`: `id, workspaceId, candidateId, status (RequestStatus), reasonCode (ErasureReasonCode, nullable), createdAt, decidedByMemberId, decidedAt` (data-model §3). Depends on T004.

### Backend — repositories & migration

- [X] T008 [P] Create `CandidateRepository` in `backend/src/main/java/com/cadence/repository/CandidateRepository.java`: `findByWorkspaceIdAndEmailHash` (non-unique lookup), `findByWorkspaceIdAndRetentionFlaggedTrueAndErasureState`, and a retention-scan finder `findByWorkspaceIdAndErasureStateAndLastContactAtBefore` (D8). Depends on T005.
- [X] T009 [P] Create `CandidateAuditEventRepository` in `backend/src/main/java/com/cadence/repository/CandidateAuditEventRepository.java` extending a **narrow** `org.springframework.data.repository.Repository<CandidateAuditEvent, String>` (NOT `CrudRepository`/`MongoRepository` — they expose `delete*`). A bare `Repository<>` is an empty marker, so declare the **reserved** append method with its exact Spring-Data signature `<S extends CandidateAuditEvent> S insert(S entity);` (`insert`/`save` are special-cased by `MongoRepositoryFactory`; a custom-named append would be parsed as a query and fail at startup) plus the finder `findByCandidateIdOrderByOccurredAtAscIdAsc(...)`. No `delete*`/`update*`/`remove*` method (FR-015 append-only — data-model §2). *(Equally acceptable: keep only the finder here and have `CandidateAuditService` write via `mongoTemplate.insert(...)`.)* Depends on T006.
- [X] T010 [P] Create `ErasureRequestRepository` (`findByWorkspaceIdAndStatus`, `findByIdAndWorkspaceId`) in `backend/src/main/java/com/cadence/repository/ErasureRequestRepository.java`. Depends on T007.
- [X] T011 Create Mongock `ChangeUnit005_GdprIndexes` (`@ChangeUnit(id="005-gdpr-baseline-indexes", order="005", author="system")`, never renamed; inject `MongoTemplate` as the `@Execution`/`@RollbackExecution` **method parameter**, mirroring `ChangeUnit003`/`004`; native `getCollection("candidates").createIndex(new Document("workspaceId",1).append("emailHash",1))` **non-unique** and `getCollection("erasureRequests").createIndex(new Document("workspaceId",1).append("status",1))`; **do NOT recreate** the `auditLog{candidateId,occurredAt}` or `candidates{workspaceId,lastContactAt}` indexes — they exist from ChangeUnit001; targeted `dropIndex(...)` per index in `@RollbackExecution`, never `dropIndexes()`) in `backend/src/main/java/com/cadence/config/migration/ChangeUnit005_GdprIndexes.java` (research D12; depends on T005, T007).
- [X] T012 Register the existing `PiiStringConverter` for `Candidate.name`, `Candidate.email`, `Candidate.phone` in the **existing** `MongoCustomConversions` bean lambda in `backend/src/main/java/com/cadence/config/MongoPiiConfig.java` (MODIFIED — add three `registrar.registerConverter(Candidate.class, "<field>", converter)` lines; reuse the same `converter` instance; do NOT add a second bean — research D1; depends on T005).

### Backend — shared services, DTOs, errors & controller skeletons

- [X] T013 Create `CandidateAuditService` in `backend/src/main/java/com/cadence/service/CandidateAuditService.java`: a single append primitive `append(workspaceId, candidateId, CandidateEventType, CandidateAuditOutcome, actorMemberId)` — **enum params only, no String** — setting `occurredAt` via the **injected `Clock`** (so `MutableClock` tests stay deterministic); a read `list(workspaceId, candidateId)` ordered `(occurredAt ASC, _id ASC)`. Logs only `StructuredArguments.kv("candidateId", id)` + codes (D3/D10; depends on T006, T009).
- [X] T014 Create `CandidateService` in `backend/src/main/java/com/cadence/service/CandidateService.java` with the **canonical-create seam** `create(workspaceId, name, email, phone, Optional<LawfulBasis>, actorMemberId)`: set GDPR defaults (`erasureState=ACTIVE`, `basisWithdrawn=false`, `retentionFlagged=false`), compute `emailHash = crypto.emailHash(email)`, set `lastContactAt = now(clock)` and `createdAt`, record the optional initial basis, append `RECORD_CREATED`/`CREATED`. **No HTTP create endpoint.** Inject `PiiCrypto`, `Clock`, `CandidateAuditService` (research D6; depends on T005, T008, T013).
- [X] T015 [P] Create `GdprDtos` in `backend/src/main/java/com/cadence/api/GdprDtos.java` — response types carrying **NO candidate PII** (internal ids + codes + booleans only): `AuditEntryResponse{eventType, outcome, actorMemberId, occurredAt}`, `ErasureRequestResponse{id, candidateId, status, reasonCode, createdAt}`, `FlaggedResponse{candidateId, retentionFlaggedAt, lastContactAt}`, `BasisRequest{lawfulBasis}` (enum), `RejectRequest{reasonCode}` (enum), and status responses (`{status}`/`{basisRecorded}`/`{basisWithdrawn}`) (contracts/gdpr-api.md).
- [X] T016 [P] Create `GdprExceptionHandler` (`@RestControllerAdvice`) in `backend/src/main/java/com/cadence/api/GdprExceptionHandler.java` mapping to the `{error,message}` envelope: validation/enum-parse → 400 `invalid_basis`/`invalid_reason`; already-resolved request → 409 `already_resolved`. Ensure no bound request DTO or candidate field is serialized into an error body/log (no PII in errors). Reuse the F01/F02 envelope shape.
- [X] T017 [P] Create `CandidateGdprController` skeleton in `backend/src/main/java/com/cadence/api/CandidateGdprController.java` under base path `/api/internal/candidates`, with **per-method** `@PreAuthorize` (erasure + basis handlers → `hasAnyRole('ADMIN','RECRUITER')`; audit read → `hasRole('ADMIN')`). Handlers are added in their story phases (same file — sequential). Depends on T015, T016.
- [X] T018 [P] Create `ErasureRequestController` skeleton (`@PreAuthorize("hasRole('ADMIN')")` class-level; `/api/internal/erasure-requests`) in `backend/src/main/java/com/cadence/api/ErasureRequestController.java`; handlers added in US4. Depends on T015, T016.
- [X] T019 [P] Create `RetentionController` skeleton (`@PreAuthorize("hasRole('ADMIN')")` class-level; `/api/internal/retention`) in `backend/src/main/java/com/cadence/api/RetentionController.java`; handlers added in US5. Depends on T015, T016.

### Backend — foundational tests

- [X] T020 [P] Create `GdprIndexBootstrapTest` asserting (by **key spec**, tolerant of Mongo's auto-naming — `candidateId_1_occurredAt_-1` etc.) that the new indexes `candidates {workspaceId,emailHash}` (non-unique) and `erasureRequests {workspaceId,status}` exist, and that the pre-existing `auditLog {candidateId,occurredAt:-1}` and `candidates {workspaceId,lastContactAt}` (ChangeUnit001) are present (clean via `mongoTemplate.remove`) in `backend/src/test/java/com/cadence/gdpr/GdprIndexBootstrapTest.java` (depends on T011).
- [X] T021 [P] Create `AuditAppendOnlyTest` in `backend/src/test/java/com/cadence/gdpr/AuditAppendOnlyTest.java`: reflectively assert `CandidateAuditEventRepository` declares **no** `delete*`/`update*`/`remove*` method (FR-015/SC-007), and (via `RequestMappingHandlerMapping.getHandlerMethods()`) that **no** controller maps `DELETE`/`PUT`/`PATCH` to an `/audit` path AND **no** handler maps `POST /api/internal/candidates` — i.e. F04 ships no HTTP candidate-create endpoint (**SC-016** route half) (depends on T009, T017).

### Frontend — shared GDPR service & route/nav scaffold

- [X] T022 [P] Create `GdprService` in `frontend/src/app/features/admin/gdpr/gdpr.service.ts` with typed HTTP wrappers for every `/api/internal` GDPR endpoint (erasure, basis record/withdraw, audit read, erasure-request list/confirm/reject, retention list/delete); programmatic error strings via `$localize`. Uses the existing API interceptor (`withCredentials` + `X-XSRF-TOKEN`).
- [X] T023 Scaffold the GDPR routes in `frontend/src/app/app.routes.ts` and nav entries in `frontend/src/app/features/shell/shell.component.ts`: add Admin GDPR links (audit/queue/retention) under the existing `@if (m.role === 'ADMIN')` block, and an erasure-action entry under a new `@if (m.role === 'ADMIN' || m.role === 'RECRUITER')` block (else the Recruiter erasure surface is unreachable — FE/US7 AS-2). Per-surface guards: erasure-action+basis → `roleGuard('ADMIN','RECRUITER')`; audit/queue/retention → `roleGuard('ADMIN')`. (Components are created in their stories; this is the shared scaffold — finalized in US7.)

**Checkpoint**: Foundation ready — candidate domain, migration, encryption, append-only audit, the canonical-create seam, DTOs/errors, three controller skeletons, and the frontend GDPR service + route/nav scaffold are in place. User stories can now proceed.

---

## Phase 3: User Story 1 — Lawful basis recorded before email + contact-permission gate (Priority: P1) [MVP]

**Goal**: Record/withdraw a candidate's email lawful basis; the contact-permission gate (fail-closed, fixed precedence) returns permit/deny for F22 to consult.

**Independent test**: Seed a candidate (no basis) → gate denies `no_basis`; record basis → permit; withdraw → deny `withdrawn`; re-record → permit; erase → deny `erased`.

### Tests (write first, must fail)

- [X] T024 [P] [US1] Create `ContactPermissionGateTest` (**pure Mockito unit** over a mocked `CandidateRepository` returning hand-built `Candidate` states — EXEMPT from the seed-via-create rule) in `backend/src/test/java/com/cadence/gdpr/ContactPermissionGateTest.java`: PARAMETERIZED truth table (SC-001) — `permit` ONLY on `(ACTIVE âˆ§ Â¬flagged âˆ§ basis set âˆ§ Â¬withdrawn)`; each deny reason by precedence `erased > over_retention > withdrawn > no_basis` for overlapping states; **fail-closed `deny: unavailable`** on missing candidate / null / unrecognized state (D4/S6).
- [X] T025 [P] [US1] Create `CandidateBasisIntegrationTest` in `backend/src/test/java/com/cadence/gdpr/CandidateBasisIntegrationTest.java`: seed via `create`; record basis (ADMIN then RECRUITER) → gate permits + `BASIS_RECORDED` audited (US1 AS-2); withdraw → gate `deny: withdrawn` + `BASIS_WITHDRAWN` audited (AS-3); re-record after withdrawal → permit (SC-013); cold-`MongoTemplate` restart re-reads basis + timestamp unchanged (AS-5). Mutating calls `.with(csrf())`.

### Implementation

- [X] T026 [US1] Create `ContactPermissionGate` in `backend/src/main/java/com/cadence/service/ContactPermissionGate.java`: `evaluate(candidateId) → Decision{permit | deny(reason)}` using **positive evaluation** (permit only on the explicit-good row; any other/missing/null → deny), precedence `erased > over_retention > withdrawn > no_basis`, fail-closed `unavailable` on error. Pure read, no write (research D4; depends on T005, T008).
- [X] T027 [US1] Add `recordBasis(workspaceId, candidateId, LawfulBasis, actorMemberId)` and `withdrawBasis(workspaceId, candidateId, actorMemberId)` to `backend/src/main/java/com/cadence/service/CandidateService.java`: targeted `$set` of the basis fields; append `BASIS_RECORDED`/`BASIS_WITHDRAWN` (research D5; depends on T014, T013).
- [X] T028 [US1] Add `PUT /api/internal/candidates/{id}/basis` (`hasAnyRole('ADMIN','RECRUITER')`, body `BasisRequest` enum-validated → 400 `invalid_basis`) and `DELETE /{id}/basis` (withdraw) handlers to `CandidateGdprController` in `backend/src/main/java/com/cadence/api/CandidateGdprController.java` (depends on T027, T017).
- [X] T029 [US1] Create `candidate-erasure-action.ts` (standalone) in `frontend/src/app/features/admin/gdpr/candidate-erasure-action.ts` with the **basis record/withdraw** controls (candidate-id paste field; `role="alert"` errors; `$localize`/`i18n="@@gdpr.basis.*"`). The erasure trigger is added in US2 (same file, sequential). Depends on T022, T023.

**Checkpoint**: US1 independently demoable — basis is recorded/withdrawn via HTTP and the gate returns the correct decision for F22.

---

## Phase 4: User Story 2 — Operator-triggered erasure (the shared wipe) (Priority: P1)

**Goal**: Admin/Recruiter erases a candidate's PII via one shared, idempotent, indistinguishable wipe that destroys the email-derived key and writes an immutable audit.

**Independent test**: Erase a seeded candidate (Admin then Recruiter) → name/email/phone decrypt to `[ERASED]`, `emailHash` key absent, one `ERASURE_COMPLETED` audit; HM/Interviewer/Read-only → 403; re-erase is a no-op.

### Tests (write first, must fail)

- [X] T030 [P] [US2] Create `CandidateErasureIntegrationTest` in `backend/src/test/java/com/cadence/gdpr/CandidateErasureIntegrationTest.java`: after erasure, a read **through the converter** returns `[ERASED]` for name/email/phone, a **RAW-driver** read shows ciphertext (not original) and **no `emailHash` field key** (SC-002/SC-006/S1); recompute `crypto.emailHash(originalEmail)` then `findByWorkspaceIdAndEmailHash` → empty (non-findable); idempotent re-erase is a no-op; **concurrent** triggers via `CountDownLatch` (N≥20) → exactly ONE wipe and the candidate's `auditLog` count rises by **exactly one** `ERASURE_COMPLETED` (losers append nothing, SC-005); **unknown / already-erased / fresh** ids all return **byte-identical `200 {"status":"erased"}`**, NOT 404 (FR-009); **O(1)**: with a large seeded audit history, the wipe issues one `$set` + one append (assert on the `erasureService.wipe(...)` service call, not MockMvc wall-clock — SC-003); **no resurrection (SC-013)**: after erasure, `CandidateService.create(...)` the same person → a **fresh independent** candidate (new `id`, fresh `emailHash`, `erasureState=ACTIVE`, independent audit trail with no linkage to the erased record); the seed-via-`create` path itself exercises the canonical-create **production-path** (SC-016, paired with the no-create-route check in T021). Mutating calls `.with(csrf())`.

### Implementation

- [X] T031 [US2] Create `CandidateErasureService` in `backend/src/main/java/com/cadence/service/CandidateErasureService.java`: `wipe(workspaceId, candidateId, CandidateAuditOutcome reason, actorMemberId)` — guarded `findAndModify(query={_id, workspaceId, erasureState:ACTIVE}, update=$set name/email/phone="[ERASED]", emailHash=null, erasureState=ERASED, erasedAt=now)`; append `ERASURE_COMPLETED` **only when `matchedCount==1`** (CAS winner; losers/no-ops append nothing); synchronous, O(1). The basis values are retained as evidence (internal-id actor only). Inject `Clock`, `CandidateAuditService` (research D2/S1; depends on T005, T013).
- [X] T032 [US2] Add `POST /api/internal/candidates/{id}/erasure` handler (`hasAnyRole('ADMIN','RECRUITER')`) to `CandidateGdprController` → `wipe(..., OPERATOR, ...)` returning **200 `{"status":"erased"}` for all cases** (never 404 — no `ScopedNotFoundException`) in `backend/src/main/java/com/cadence/api/CandidateGdprController.java` (depends on T031, T017).
- [X] T033 [US2] Add the **erasure trigger** to `frontend/src/app/features/admin/gdpr/candidate-erasure-action.ts`: a destructive action with a **confirmation step** before dispatch, `role="alert"` result/error, `$localize` strings (FE; depends on T029).

**Checkpoint**: US2 delivers the shared wipe reused by US4/US5; Art. 17 de-identification verified.

---

## Phase 5: User Story 3 — Append-only per-candidate audit log + Admin view (Priority: P1)

**Goal**: Every material candidate event is appended to a non-PII, append-only log; an Admin reads it in order; it survives erasure.

**Independent test**: Drive create → record-basis → erase → read the audit (Admin) → ordered non-PII entries present after erasure; non-Admin read → 403.

### Tests (write first, must fail)

- [X] T034 [P] [US3] Create `CandidateAuditIntegrationTest` in `backend/src/test/java/com/cadence/gdpr/CandidateAuditIntegrationTest.java`: each event (create/basis/erasure) → exactly one non-PII entry containing no name/email/phone (SC-012); read ordered `(occurredAt,_id)` including two **same-tick** events (distinct, stable `_id` order); **survives erasure** — capture the pre-wipe entry set, run wipe, assert those entries are **byte-identical** afterwards plus exactly one new `ERASURE_COMPLETED` (SC-008); empty-log read and unknown-id read return a defined non-oracle response; non-Admin read → 403.

### Implementation

- [X] T035 [US3] Add `GET /api/internal/candidates/{id}/audit` handler (`hasRole('ADMIN')`) to `CandidateGdprController` → `CandidateAuditService.list(...)` mapped to `AuditEntryResponse` (non-PII, ordered) in `backend/src/main/java/com/cadence/api/CandidateGdprController.java` (depends on T013, T017).
- [X] T036 [US3] Create `candidate-audit.component.ts` (standalone) in `frontend/src/app/features/admin/gdpr/candidate-audit.component.ts`: candidate-id **paste/text field** (no browser — F51), ordered audit table, empty-state, `role="alert"` errors, `i18n="@@gdpr.audit.*"` (depends on T022, T023).

**Checkpoint**: Audit mechanism complete; later features append their event types through `CandidateAuditService` (forward contract).

---

## Phase 6: User Story 4 — Candidate-initiated erasure request routed to Admin (Priority: P2)

**Goal**: A PII-free erasure request is created (the F30-forward intake primitive); an Admin lists, confirms (→ shared wipe), or rejects; transitions are guarded.

**Independent test**: Create a request → pending; Admin confirm → wipe runs + audited; reject → no wipe + audited; double/concurrent confirm → 409/single wipe; non-Admin → 403.

### Tests (write first, must fail)

- [X] T037 [P] [US4] Create `ErasureRequestIntegrationTest` in `backend/src/test/java/com/cadence/gdpr/ErasureRequestIntegrationTest.java`: `requestErasure(candidateId, CANDIDATE_REQUEST)` → PENDING + `ERASURE_REQUESTED` audited and the request record carries **no candidate free text** (only id + enum reason, S3); Admin confirm → shared wipe runs + `ERASURE_REQUEST_CONFIRMED` + one `ERASURE_COMPLETED(CANDIDATE_REQUEST)` (US4 AS-3); reject (enum reason) → no wipe + `ERASURE_REQUEST_REJECTED`; **double/concurrent confirm** of one request via latch → 409 `already_resolved` / exactly one wipe (SC-015); reject with absent/unknown `reasonCode` → 400 `invalid_reason`, stays PENDING; non-Admin view/decide → 403. Mutating calls `.with(csrf())`.

### Implementation

- [X] T038 [US4] Create `ErasureRequestService` in `backend/src/main/java/com/cadence/service/ErasureRequestService.java`: `requestErasure(workspaceId, candidateId, ErasureReasonCode)` intake (PII-free; append `ERASURE_REQUESTED`); `confirm(id, actorMemberId)` — guarded `findAndModify(status:PENDING→RESOLVED_CONFIRMED)` then `erasureService.wipe(..., CANDIDATE_REQUEST, ...)` (single wipe under concurrency); `reject(id, actorMemberId, ErasureReasonCode)` — guarded transition, no wipe; both audited (research D7; depends on T031 (wipe), T013, T010).
- [X] T039 [US4] Add `GET /api/internal/erasure-requests?status=PENDING`, `POST /{id}/confirm`, `POST /{id}/reject` handlers (`hasRole('ADMIN')`; confirm/reject on a non-PENDING request → 409 `already_resolved`) to `ErasureRequestController` in `backend/src/main/java/com/cadence/api/ErasureRequestController.java` (depends on T038, T018).
- [X] T040 [US4] Create `erasure-queue.component.ts` (standalone) in `frontend/src/app/features/admin/gdpr/erasure-queue.component.ts`: Admin pending-request queue (confirm/reject with confirmation for confirm), empty-state, `i18n="@@gdpr.queue.*"` (depends on T022, T023).

**Checkpoint**: Data-subject erasure path complete; F30 wires the candidate-facing submission to `requestErasure` later (forward contract).

---

## Phase 7: User Story 5 — Retention-driven enforcement (Priority: P2)

**Goal**: A checkpointed scan flags over-age candidates (age basis `lastContactAt`, strict boundary, self-clearing); the gate denies them; an Admin confirms deletion via the shared wipe.

**Independent test**: With a retention period set (F03), seed an over-age candidate → scan flags it; gate denies `over_retention`; Admin delete → wiped; lengthen the period → next scan clears the flag → gate permits (if basis recorded).

### Tests (write first, must fail)

- [X] T041 [P] [US5] Create `RetentionIntegrationTest` in `backend/src/test/java/com/cadence/gdpr/RetentionIntegrationTest.java` (also `remove(SchedulerCheckpoint.class)` in `@BeforeEach`): seed a `WorkspaceConfig` retention period; boundary fixtures set `lastContactAt` **explicitly** (sanctioned deviation) — a record **at** the period is NOT flagged, one tick **over** IS (strict `<`, SC-014); gate denies `over_retention` for a flagged candidate; `POST /retention/{id}/delete` wipes **only** a flagged candidate (an unflagged ACTIVE candidate is a no-op, NOT wiped — BE-MAJOR); **lengthen** the period (or refresh `lastContactAt`) → next scan emits `RETENTION_FLAG_CLEARED` and the gate returns `permit` **iff a basis was recorded first**, else `deny: no_basis` (Q5/Q8); **missed-fire** verified by seeding a **stale `RUNNING` `SchedulerCheckpoint`** for the scan `taskName` and asserting the `ApplicationReadyEvent` replay completes it (NOT a `MutableClock`, B4). Mutating calls `.with(csrf())`.

### Implementation

- [X] T042 [US5] Create `RetentionService` in `backend/src/main/java/com/cadence/service/RetentionService.java`: `scan(workspaceId)` reads the F03 retention period from `WorkspaceConfig`, flags `erasureState==ACTIVE âˆ§ lastContactAt < now(clock)-retentionDays` (strict; `$set retentionFlagged=true` + `RETENTION_FLAGGED`), and **clears** stale flags no longer over-age (`RETENTION_FLAG_CLEARED`); `confirmDelete(workspaceId, candidateId, actorMemberId)` runs the shared wipe **only via a guarded update on `retentionFlagged==true`** (`RETENTION_DELETED` + `ERASURE_COMPLETED(RETENTION)`); inject `Clock` for the age comparison (research D8; depends on T031 (wipe), T013, T005, T008).
- [X] T043 [US5] Create `RetentionScanTask` in `backend/src/main/java/com/cadence/scheduler/RetentionScanTask.java`: a daily `@Scheduled` method wrapping `schedulerCheckpointService.start(taskName)` → `retentionService.scan(...)` → `complete(taskName)`; **register the replay action in `@PostConstruct`** (before `ApplicationReadyEvent`, else a real missed fire is silently swallowed — BE-MINOR). Reuses `SchedulerCheckpointService` unchanged (research D8; depends on T042).
- [X] T044 [US5] Add `GET /api/internal/retention/flagged` and `POST /api/internal/retention/{candidateId}/delete` handlers (`hasRole('ADMIN')`; delete guarded on flagged, indistinguishable response) to `RetentionController` in `backend/src/main/java/com/cadence/api/RetentionController.java` (depends on T042, T019).
- [X] T045 [US5] Create `retention-review.component.ts` (standalone) in `frontend/src/app/features/admin/gdpr/retention-review.component.ts`: Admin flagged-records list (non-PII), confirm-delete with confirmation, empty-state, `i18n="@@gdpr.retention.*"` (depends on T022, T023).

**Checkpoint**: Retention enforcement live (flag → gate-deny → Admin-confirmed delete); F22 enforces the dispatch-time block via the same gate (forward contract).

---

## Phase 8: User Story 6 — Zero plaintext PII in logs across every GDPR flow (Priority: P1)

**Goal**: No candidate name/email/phone or message content reaches logs at any level across all GDPR flows; verified by an automated sentinel scan.

**Independent test**: At root TRACE, drive create → basis → gate → audit-read → erase → request → retention → validation-error; the captured log contains zero seeded sentinels.

### Tests (write first, must fail)

- [X] T046 [P] [US6] Create `GdprLogPiiScanTest` in `backend/src/test/java/com/cadence/gdpr/GdprLogPiiScanTest.java`: set the root logger to `TRACE`; seed a candidate with **high-entropy sentinels** (name `ZZSENTINELNAME_DONOTLOG`, email `sentinel@dont.log`, phone `+15550101010`); drive create → record/withdraw basis → gate evaluate → `GET …/audit` (the decrypt/read path) → erase → erasure-request confirm → retention scan+delete → a forced validation error through `GdprExceptionHandler`; assert the captured log surface (message + argument array + MDC + throwable) contains **none** of the sentinels (and the email regex) at any level; a **positive vacuity guard** asserts at least one `candidateId=` kv was emitted (so a skipped run cannot pass); restore the level in `@AfterEach` (research D10/SC-010).

### Implementation

- [X] T047 [US6] Audit/sweep the GDPR services (`CandidateService`, `CandidateErasureService`, `ContactPermissionGate`, `ErasureRequestService`, `RetentionService`, `CandidateAuditService`) and `GdprExceptionHandler` to ensure every log statement uses `StructuredArguments.kv("candidateId", id)` + non-PII codes only, and add a unit test asserting `Candidate.toString()` contains **none** of the seeded PII sentinels (binding the future-field rule, D10). Touches the above files as needed (depends on T026, T031, T038, T042, T013, T016).

**Checkpoint**: The zero-PII-in-logs P1 gate is machine-verified across every flow (CI extension lands in Polish, T052).

---

## Phase 9: User Story 7 — Admin/Recruiter GDPR surfaces in the frontend (Priority: P3)

**Goal**: Admins reach audit/queue/retention; Admin+Recruiter reach the erasure action; unauthorized roles see no controls and are redirected to `/not-authorized`; all strings localized.

**Independent test**: As Admin, all four surfaces reachable; as Recruiter, only the erasure action; as each other role, controls hidden + direct navigation → `/not-authorized` + API 403.

### Tests (write first, must fail)

- [X] T048 [P] [US7] Frontend guard/route specs: in `frontend/src/app/core/auth/role.guard.spec.ts` (MODIFIED) assert **both** guard-arg sets per role — `roleGuard('ADMIN')` (Recruiter **redirects**) and `roleGuard('ADMIN','RECRUITER')` (Recruiter **passes**), with HM/Interviewer/Read-only redirecting in both (SC-011, the F03 single-route lesson); create per-component specs `candidate-audit.component.spec.ts`, `erasure-queue.component.spec.ts`, `retention-review.component.spec.ts` (Admin renders), and `candidate-erasure-action.spec.ts` (Recruiter passes; other non-Admins redirect) under `frontend/src/app/features/admin/gdpr/`.

### Implementation

- [X] T049 [US7] Finalize `frontend/src/app/app.routes.ts` (per-surface `roleGuard` mapping) and `frontend/src/app/features/shell/shell.component.ts` (Admin GDPR nav links + the `ADMIN||RECRUITER` erasure entry; ensure unauthorized roles never see the controls); confirm all programmatic strings use `$localize` (depends on T023, T036, T040, T045, T033).
- [ ] T050 [P] [US7] Create `frontend/e2e/gdpr.spec.ts` (Playwright): Admin reaches all GDPR surfaces; Recruiter reaches the erasure action but not the Admin-only surfaces; a non-authorized role hitting an Admin route → `/not-authorized` while the API independently 403s (depends on prior phases).

**Checkpoint**: Full UX with defense-in-depth guards; the server remains the boundary.

---

## Phase 10: Polish & Cross-Cutting Concerns

- [X] T051 [P] Create `GdprRbacContractTest` in `backend/src/test/java/com/cadence/gdpr/GdprRbacContractTest.java`: per the contracts matrix, loop roles x the five surfaces — erasure-trigger + record/withdraw-basis {ADMIN,RECRUITER ok; HM/Interviewer/Read-only 403}; audit-read + erasure-request view/confirm/reject + retention list/confirm-delete {ADMIN ok; 4 others 403}. **All mutating calls `.with(csrf())`** (BE-MAJOR — so 403s are role-denials, not CSRF). Each 403 re-reads and asserts `erasureState`/`lawfulBasis`/`retentionFlagged` + the candidate's audit-row count are **unchanged** (no state change). Depends on all controllers (T032, T028, T035, T039, T044).
- [X] T052 Extend the PII log-scan step in `.github/workflows/ci.yml` with the **candidate sentinels** (`ZZSENTINELNAME_DONOTLOG`, `sentinel@dont.log`, `+15550101010`) and a positive vacuity guard (a known `candidateId=` marker must appear), failing CI if any sentinel appears in captured test logs (keep the existing email + F03 secret patterns). Pure-ASCII; no `.ps1` change (SEC/QA-MAJOR; pairs with T046).
- [ ] T053 [P] Create `CandidateRestartPersistenceTest` in `backend/src/test/java/com/cadence/gdpr/CandidateRestartPersistenceTest.java`: persist a candidate, then re-read through a freshly-built **cold** `MongoTemplate` (new `MongoClient` + a fresh `MongoCustomConversions` registering the same `PiiStringConverter` — NOT a JVM restart) and assert name/email/phone **decrypt to the original** values (SC-006 cold path, F03 pattern).
- [X] T054 [P] Run the existing F02 `RbacEndpointInventoryTest` and confirm it stays green with the new `/api/internal/candidates|erasure-requests|retention/**` handlers (every handler carries `@PreAuthorize`); record the result (no code change expected).
- [X] T055 [P] Walk `specs/005-gdpr-baseline/quickstart.md` end-to-end (manual verification of US1–US7 incl. the raw-driver ciphertext check); if any `.ps1`/`.cmd`/`.bat` was touched, run the byte-level non-ASCII scan (C5) — none expected.
- [X] T056 Run the full suite — `./gradlew test --tests "com.cadence.gdpr.*"` + `RbacEndpointInventoryTest` + the F01/F02/F03 suites, `ng test --watch=false`, Playwright `gdpr.spec.ts` — all green; then run the **final multi-role sub-agent review** (≥3 roles, C6) on the implementation and apply findings.

---

## Dependencies & Execution Order

- **Setup (Phase 1)** → **Foundational (Phase 2)** → user stories.
- **Foundational blocks everything**: T002–T023 must complete before any story. Within Foundational: T011 needs T005/T007; T012 needs T005; T013 needs T006/T009; T014 needs T005/T008/T013; T017–T019 need T015/T016; T020/T021 need their domain/repo deps.
- **User stories after Foundational**:
  - US1 (T024–T029), US2 (T030–T033), US3 (T034–T036) are P1. US1/US2/US3 each add handlers to the **shared** `CandidateGdprController` (sequential by task-ID on that file) and US1/US2 share `candidate-erasure-action.ts` (sequential); their **test files are independent** and parallel.
  - **US2's `CandidateErasureService.wipe` (T031) is a dependency of US4 (T038 confirm) and US5 (T042 delete)** — schedule US2 before US4/US5. Each story remains independently testable.
  - US4 (T037–T040) and US5 (T041–T045) are P2; independent test files, parallel.
  - US6 (T046–T047) is P1 but its scan drives all flows, so it sequences after US1–US5 exist (the per-service logging discipline is built into those tasks; US6 verifies it).
  - US7 (T048–T050) is P3 and depends on the four frontend components + the route/nav scaffold.
- **Polish (Phase 10)** after all stories: T051 needs all controllers; T052 pairs with T046's sentinels; T056 is last.

## Parallel Execution Examples

- **Foundational domain/enum burst** (all different files): T002, T003, T004, T005, T006, T007, T008, T009, T010, T015, T016 in parallel; then T011/T012/T013 in parallel (T014 after, it depends on T013); then T017/T018/T019; then T020/T021.
- **Frontend core**: T022, T023 in parallel with backend foundational tasks.
- **Story test authoring** (TDD, before implementation, different files): T024, T025, T030, T034, T037, T041, T046 can all be written in parallel.
- **Polish tests**: T051, T053, T054, T055 in parallel.

## Implementation Strategy

- **MVP = Phase 1 + Phase 2 + US1 + US2 + US3 (T001–T036)** — a candidate's lawful basis + gate (US1), the shared erasure wipe (US2), and the append-only audit (US3): the core GDPR foundation, demoable and the P1 obligations met.
- **Increment 2 = US4 + US5** (P2) — the data-subject erasure-request path and retention enforcement (both reuse the US2 wipe).
- **Increment 3 = US6** (P1 gate) — the zero-PII-in-logs scan + CI extension (verifies discipline built into all prior stories).
- **Increment 4 = US7** (P3) — frontend GDPR surfaces with per-role guards.
- **Always-on**: the existing `RbacEndpointInventoryTest` fails CI if any new internal endpoint lacks a role; T052 makes the SC-010 candidate-PII scan real.

## Format validation

All tasks use `- [ ] [TaskID] [P?] [Story?] description + file path`. Story labels [US1]–[US7] appear only in story phases; Setup/Foundational/Polish carry none. Total: **56 tasks** (Setup 1, Foundational 22 [T002–T023], US1 6, US2 4, US3 3, US4 4, US5 5, US6 2, US7 3, Polish 6).

