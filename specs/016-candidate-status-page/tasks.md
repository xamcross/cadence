# Tasks: Candidate Status Page (F30)

**Input**: Design documents from `C:\Users\xamcr\Cadence\specs\016-candidate-status-page\`
**Prerequisites**: plan.md, spec.md, research.md (D1–D15), data-model.md, contracts/status-page-api.md, quickstart.md

**Tests**: INCLUDED — constitution Principle VII (Test-First) is mandatory for business-logic/acceptance paths; the plan lists unit + Testcontainers + MockMvc + Jasmine/axe + Lighthouse. Write each test FIRST and see it fail before implementing.

**Organization**: By user story (spec.md). US1 (candidate view, P1) and US2 (recruiter maintain, P1) ship together as the MVP; US3 (candidate erasure, P2) follows. Multi-role plan-review fixes are folded into specific tasks (flagged ⚠️FIX).

## Path Conventions

Web app: `backend/src/main/java/com/cadence/...`, `backend/src/test/java/com/cadence/...`, `frontend/src/app/...`. Run flags (CLAUDE.md): `JAVA_HOME=C:/jdk-24.0.1`, cached `gradle-9.4.0`, `-Dapi.version=1.41`, `DOCKER_HOST=npipe:////./pipe/docker_engine`.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Config + the new enums the rest of the feature references.

- [X] T001 [P] Create `backend/src/main/java/com/cadence/config/StatusPageProperties.java` (`@ConfigurationProperties("cadence.status")`: `spaStatusBasePath` default `/status`; reuse `SchedulingProperties` for the rate limit) and add the `cadence.status.*` block to `backend/src/main/resources/application.yml` (LF).
- [X] T002 [P] Create `backend/src/main/java/com/cadence/domain/CandidateStatusOutcome.java` enum (`IN_PROGRESS`, `COMPLETE_OFFER`, `COMPLETE_REJECTED`).
- [X] T003 [P] Add append-only values `STATUS_PUBLISHED`, `STATUS_LINK_ISSUED`, `STATUS_LINK_ROTATED` to `backend/src/main/java/com/cadence/domain/CandidateEventType.java` (do NOT reorder existing values).

---

## Phase 2: Foundational (Blocking Prerequisites)

**⚠️ CRITICAL**: No user-story work begins until this phase is complete — the candidate status fields, token plumbing, migration, and the no-oracle exception handler are shared by all three stories.

- [X] T004 Extend `backend/src/main/java/com/cadence/domain/Candidate.java` with additive fields per data-model §1: `statusStage`, `statusNextStep`, `statusToken` (all `@Field(write=NON_NULL)` + `@JsonIgnore`), `statusTokenHash` (`@Field(write=NON_NULL)` + `@JsonIgnore`), `statusExpectedDate` (`LocalDate`), `statusOutcome` (`CandidateStatusOutcome`), `statusPublishedAt` (`Instant`), `statusPublishedByMemberId` (`String`); add `@JsonIgnore` import; extend `toString()` to OMIT `statusStage`/`statusNextStep`/`statusToken`/`statusTokenHash` (PII/credential — the F04 discipline).
- [X] T005 Register the three converter-managed fields in `backend/src/main/java/com/cadence/config/MongoPiiConfig.java` `configurePropertyConversions(...)`: `Candidate.class "statusStage"`, `"statusNextStep"`, `"statusToken"` → `PiiStringConverter` (the `SchedulingRequest.locationText` precedent). `statusTokenHash` is a hash — NOT registered.
- [X] T006 [P] Add `Optional<Candidate> findByStatusTokenHash(String statusTokenHash)` to `backend/src/main/java/com/cadence/repository/CandidateRepository.java` (non-workspace-scoped, the `findByTokenHash` precedent).
- [X] T007 [P] Create `backend/src/main/java/com/cadence/api/CandidateStatusExceptions.java`: `StatusNotFoundException` (→ indistinguishable 404), `InvalidStatusPublishException` (→ 400 `invalid_status`, value-free message). Reuse the existing rate-limit + `ScopedNotFoundException` types.
- [X] T008 ⚠️FIX (Security) Create `backend/src/main/java/com/cadence/api/CandidateStatusExceptionHandler.java` — a `@RestControllerAdvice(assignableTypes = {CandidateStatusController.class, CandidateStatusAdminController.class})` (the `SchedulingExceptionHandler` is type-scoped and NOT inherited). Map `StatusNotFoundException`→byte-identical 404 `{"error":"not_found"}`; `InvalidStatusPublishException`→400 `{"error":"invalid_status"}`; rate-limit→429 `{"error":"rate_limited"}`; scoped→404. This handler is the load-bearing no-oracle piece (SC-007/SC-010).
- [X] T009 Create `backend/src/main/java/com/cadence/service/CandidateStatusService.java` skeleton: inject `CandidateRepository`, `MongoTemplate`, `TokenHasher`, `SecureTokens`-mint, `PiiCrypto` (only via converter; decrypt for link), `CandidateAuditService`, `CandidateRateLimiter`, `WorkspaceConfigService` (zone + branding), `StatusPageProperties`, and **`java.time.Clock`** (⚠️FIX QA — the `MutableClock`/`AuthTestConfig` pattern; `today = LocalDate.ofInstant(Instant.now(clock), workspaceZone)`, never `LocalDate.now()`). Add private helpers `ensureProvisioned(candidate)` (mint + atomic `$set statusToken/statusTokenHash` if absent, audit `STATUS_LINK_ISSUED`), `statusLinkFor(ws,candidateId)` (decrypt → `{spaBaseUrl}{spaStatusBasePath}?token=`), `resolveActiveByToken(rawToken)` (hash→`findByStatusTokenHash`→reject if empty/erased→`StatusNotFoundException`).
- [X] T010 ⚠️FIX (Backend+QA) Create `backend/src/main/java/com/cadence/config/migration/ChangeUnit015_CandidateStatusIndexes.java` (`@ChangeUnit(id="015-candidate-status-indexes", order="015", author="system")`): in `@Execution`, FIRST **dedupe** `erasureRequests` — group by `{workspaceId,candidateId}` where `status:"PENDING"`, keep earliest `createdAt`, flip the rest to a terminal status (data-model §8, or the unique index build aborts startup); THEN `candidates.createIndex({statusTokenHash:1}, unique, partialFilterExpression {statusTokenHash:{$exists:true}})` and `erasureRequests.createIndex({workspaceId:1,candidateId:1}, unique, partialFilterExpression {status:"PENDING"})`. `@RollbackExecution` targeted `dropIndex` (never `dropIndexes()`). Native driver API (the `ChangeUnit014` pattern).
- [X] T011 [P] Confirmed reuse — the test profile's existing candidate `rate-limit-per-minute: 5` covers F30 (F30 reuses the same `SchedulingProperties`/`CandidateRateLimiter`); no new key added. to `backend/src/main/resources/application-test.yml` only if not already covered by the existing candidate `rate-limit-per-minute: 5` (confirm reuse; no new key if shared).

**Checkpoint**: Foundation ready — model, token plumbing, migration (with dedupe), and the no-oracle handler exist. User stories can begin.

---

## Phase 3: User Story 1 - Candidate sees their honest status (Priority: P1) 🎯 MVP

**Goal**: A candidate opens their private link (no login) and sees stage + next step + expected date (or the honest terminal/past-date/under-review variant), branded, time-zone-correct, accessible.

**Independent Test**: Seed a published status + token directly in Mongo → `GET /api/candidate/status/{token}` returns the correct `displayState` + fields; an unknown/erased token returns the byte-identical 404; the Angular page renders all states with 0 axe violations.

### Tests for User Story 1 (write FIRST, ensure they FAIL) ⚠️

- [X] T012 [P] [US1] Unit test `backend/src/test/java/com/cadence/status/DisplayStateResolverTest.java`: the precedence matrix (TERMINAL > PAST_DATE > PUBLISHED > UNDER_REVIEW, SC-016) and past-date computed in the workspace zone with a **controlled `MutableClock`** (SC-013) — including the conflict cases (terminal+past, under-review+past).
- [X] T013 [P] [US1] Contract test `backend/src/test/java/com/cadence/status/CandidateStatusViewContractTest.java` (MockMvc, STATELESS chain, no csrf): 200 shape per `displayState`; **byte-identical 404 across {unknown, malformed, erased}** (SC-007); 429 at the test-profile threshold+1 (⚠️FIX QA — 6th call, not 11th); `Cache-Control: no-store` on every response.
- [X] T014 [P] [US1] Integration test `backend/src/test/java/com/cadence/status/CandidateStatusViewIT.java` (Testcontainers): raw-driver read shows ciphertext on `statusStage`/`statusNextStep`; a published-then-viewed round-trip decrypts correctly.
- [X] T015 [P] [US1] Jasmine + axe `frontend/src/app/features/status/candidate-status.component.spec.ts`: 0 WCAG 2.2 AA violations across PUBLISHED/PAST_DATE/TERMINAL/UNDER_REVIEW (the `axe.ts` body-attach harness); first-paint stage/next-step/date visible @375px (SC-001); free-text with markup renders **inert** via interpolation, never `[innerHTML]` (SC-015/Story1 AC-5); token held memory-only (no `localStorage`/`sessionStorage`/`console`, SC-012); long-unbroken + RTL next-step → no horizontal scroll (SC-003); 44px targets; ⚠️FIX (QA) assert the `/status` route is covered by the global `frontend/src/_headers` CSP + `Referrer-Policy: no-referrer` (the F14 leg, SC-012) — e.g. a CI grep / `_headers` assertion that `/status` rides the same `/*` headers; and assert the contact route renders a workspace-sourced destination, never candidate email/phone (FR-007 negative).

### Implementation for User Story 1

- [X] T016 [US1] Implement `CandidateStatusService.view(rawToken, ip)` in `backend/src/main/java/com/cadence/service/CandidateStatusService.java`: rate-limit (429), `resolveActiveByToken`, compute `displayState` (Clock + workspace zone), return a minimal view (escaped fields + `workspaceZone`). No per-view audit (FR-034).
- [X] T017 [P] [US1] Create `backend/src/main/java/com/cadence/api/CandidateStatusDtos.java` `CandidateStatusView` record (displayState, stage?, nextStep?, expectedDate?, outcome, workspaceZone) per contract A.
- [X] T018 [US1] Create `backend/src/main/java/com/cadence/api/CandidateStatusController.java` `GET /api/candidate/status/{token}` (`@RequestMapping("/api/candidate/status")`, on the `@Order(2)` permitAll/STATELESS chain; `HttpServletRequest.getRemoteAddr()` for IP; `Cache-Control: no-store`). Confirm `/api/candidate/` stays allow-listed in `RbacEndpointInventoryTest`.
- [X] T019 [P] [US1] Create `frontend/src/app/features/status/status.service.ts` (GET view, `encodeURIComponent(token)`, `CacheControl.noStore()` equivalent — the `booking.service.ts` pattern) and a public branding fetch reuse.
- [X] T020 [US1] Create `frontend/src/app/features/status/candidate-status.component.ts` (standalone, no login, token from URL query held in a memory-only field re-resolved on `ngOnInit`; renders the server `displayState` as ONE block; branding logo/colour via `/api/public/workspace/branding`+`/logo`; expected date in candidate-local presentation; contact route from branding — never candidate PII; all strings `$localize`; free text via interpolation only).
- [X] T021 [US1] Add the public lazy route `/status` to `frontend/src/app/app.routes.ts` (guard-free, like `/schedule`).
- [X] T022 [US1] Extend `frontend/lighthouse/serve-with-stub.mjs` with canned `GET /api/candidate/status/<token>` handlers keyed by DISTINCT demo tokens (⚠️FIX QA): `lighthouse-demo`→PUBLISHED (the perf-gate state), `lighthouse-demo-terminal`→TERMINAL, `lighthouse-demo-review`→UNDER_REVIEW; SPA fallback. Add ONLY `/status?token=lighthouse-demo` to `lighthouserc.json` `ci.collect.url[]` (one perf URL; a11y is the axe gate).
- [X] T023 [P] [US1] PII-scan test `backend/src/test/java/com/cadence/status/StatusLogPiiScanTest.java` (view leg): drive a view with `SENTINELF30STAGE_*`/`SENTINELF30NEXT_*` + a status-token sentinel; assert absence across logs + the raw doc fields that must be ciphertext.

**Checkpoint**: A seeded status is viewable end-to-end (browser→DB), accessible, oracle-free. (Demoable once US2 lets a recruiter create the status.)

---

## Phase 4: User Story 2 - Recruiter keeps the status current (Priority: P1)

**Goal**: A Recruiter/Admin publishes/updates stage + next step + expected date (refused if dateless/contentless), copies the candidate's status link, and can rotate it. The post-booking CONFIRMATION email carries the link.

**Independent Test**: As Recruiter, `PUT .../status` persists + audits + surfaces on the candidate view; a missing-date publish is rejected 400; rotate invalidates the old link; HM/Interviewer/Read-only get 403.

### Tests for User Story 2 (write FIRST, ensure they FAIL) ⚠️

- [X] T024 [P] [US2] Contract test `backend/src/test/java/com/cadence/status/RecruiterStatusContractTest.java` (MockMvc, `.with(csrf())`): publish 200; **400 `invalid_status`** for in-progress missing date AND blank next-step (SC-004, value-free); scoped 404 (not-in-workspace / erased); **5-role matrix** — ADMIN/RECRUITER allowed, HM/Interviewer/Read-only 403 (FR-010); rotate 200 + 404/403; recruiter GET read 200 with `statusLink` + 404/403.
- [X] T025 [P] [US2] ⚠️FIX (QA) Integration test `backend/src/test/java/com/cadence/status/RecruiterStatusIT.java` (Testcontainers): (a) **read-your-write update** — publish v1→view shows v1; publish v2→view shows v2, no stale (SC-005); (b) **rotation** invalidates old hash (old token→404) and new resolves (SC-011); (c) **cold-converter reload** decrypts `statusStage`/`statusNextStep`; (d) publish provisions the token + the audit record carries actor (=`statusPublishedByMemberId`), the `RECORDED` outcome, and a timestamp (⚠️FIX QA — SC-014/FR-015 audit-content assertion, not just "event fired"); (e) publish guarded on `erasureState:ACTIVE` (publish onto erased→scoped 404).
- [X] T026 [P] [US2] ⚠️FIX (QA/FR-016) Concurrency test `backend/src/test/java/com/cadence/status/ConcurrentPublishIT.java` (gated `@RepeatedTest`/latch, the F21 `concurrentFirstEdit`/F13 precedent): two simultaneous publishes → exactly one consistent published status, no partial/mixed state.

### Implementation for User Story 2

- [X] T027 [US2] Add to `CandidateStatusService`: `publish(ws, candidateId, actor, PublishStatusRequest)` — validate per data-model §4 (IN_PROGRESS requires stage+nextStep+expectedDate; terminal requires nextStep; value-free `InvalidStatusPublishException`), atomic `updateFirst({_id,workspaceId,erasureState:ACTIVE}, $set all status fields + statusPublishedAt + statusPublishedByMemberId)` (converter encrypts `$set` free-text — F03 precedent; do NOT pre-encrypt), `ensureProvisioned`, audit `STATUS_PUBLISHED`; `matchedCount==0`→`ScopedNotFoundException`.
- [X] T028 [US2] Add to `CandidateStatusService`: `rotateLink(ws, candidateId, actor)` (mint + atomic `$set statusToken/statusTokenHash`, audit `STATUS_LINK_ROTATED`, return new link) and `readForRecruiter(ws, candidateId)` (decrypt fields + `statusLinkFor`; lazy-provision audited if absent).
- [X] T029 [P] [US2] Add to `backend/src/main/java/com/cadence/api/CandidateStatusDtos.java`: `PublishStatusRequest`, `RecruiterStatusResponse` (incl. `statusLink`), `RotateLinkResponse`.
- [X] T030 [US2] Create `backend/src/main/java/com/cadence/api/CandidateStatusAdminController.java` (`@RequestMapping("/api/internal/candidates/{candidateId}/status")`, class `@PreAuthorize("hasAnyRole('ADMIN','RECRUITER')")`): `PUT` publish, `POST /rotate-link`, `GET` read; workspace-scoped via the principal; declared method security so `RbacEndpointInventoryTest` stays green.
- [X] T031 [US2] ⚠️FIX (Backend — ONE atomic change) In the SAME commit: add `{{status_link}}` to the `CONFIRMATION` body in `backend/src/main/java/com/cadence/service/BuiltInEmailTemplates.java` AND permit `STATUS_LINK` for `CONFIRMATION` in `backend/src/main/java/com/cadence/service/MergeTokenCatalogue.java` (else `@PostConstruct` completeness + `BuiltInTemplateCompletenessTest` crash on the tone presets). Verify `BuiltInTemplateCompletenessTest` green.
- [X] T032 [US2] Add the recruiter **Status panel** to the existing per-candidate view in `frontend/src/app/features/` (stage/next-step/expected-date form with client validation mirroring the server rules, "Copy status link", "Rotate link"); internal screen (Lighthouse/WCAG N/A, F50/F51 precedent). Jasmine for the validation + copy/rotate actions.
- [X] T033 [P] [US2] Extend `StatusLogPiiScanTest` (publish + email-render leg, ⚠️FIX Security): drive publish→CONFIRMATION render→dispatch with a token-in-link sentinel; assert the decrypted `statusLink` never reaches logs / audit / `DeadLetterService.recordFailure`.

**Checkpoint**: US1+US2 together = the MVP — recruiter publishes, candidate sees it, reflects on reload, link rotates. Demoable browser→DB.

---

## Phase 5: User Story 3 - Candidate requests erasure from the status page (Priority: P2)

**Goal**: The candidate taps "Request data deletion" → an Admin-confirmable erasure request is recorded (never immediate), idempotent, oracle-free; erasure clears status + token atomically.

**Independent Test**: From a valid token, `POST .../erasure-request` → one PENDING request for Admin; repeat → still one; unknown/erased token → same 202 ack; candidate stays ACTIVE until Admin confirms.

### Tests for User Story 3 (write FIRST, ensure they FAIL) ⚠️

- [X] T034 [P] [US3] Contract test `backend/src/test/java/com/cadence/status/CandidateErasureSubmitContractTest.java` (MockMvc): **202 `{"status":"received"}` identical across {valid, unknown, malformed, erased}** (SC-010, no oracle); `GET` on the path → 405 (affirmative POST only); 429 at threshold.
- [X] T035 [P] [US3] ⚠️FIX (Backend/Security — the BLOCKER) Integration test `backend/src/test/java/com/cadence/status/ErasureClearsStatusIT.java` (Testcontainers): erase a candidate that has a provisioned token + published status → the wipe **succeeds** (no `ClassCastException`), status fields null, `statusToken` cleared, old status token → 404, and the candidate view is the indistinguishable 404.
- [X] T036 [P] [US3] Integration test `backend/src/test/java/com/cadence/status/ErasureRequestIdempotencyIT.java` (+ `ChangeUnit015DedupeTest.java` for the seed-2-PENDING-dupes dedupe-builds-clean leg) (Testcontainers): submit→1 PENDING (`ErasureRequest` is id+enum only — FR-021); repeat→still 1 (SC-008/Story3 AC-4, via the unique partial index); candidate ACTIVE until Admin `confirm`; the `ChangeUnit015` dedupe builds cleanly when 2 pre-existing PENDING duplicates are seeded.

### Implementation for User Story 3

- [X] T037 [US3] ⚠️FIX (BLOCKER) Extend `backend/src/main/java/com/cadence/service/CandidateErasureService.java` `wipe(...)`: in the existing wipe update add `$set null` for `statusStage`/`statusNextStep`/`statusExpectedDate`/`statusOutcome`/`statusPublishedAt` AND `$set null` for `statusToken` (converter-managed → NEVER `$unset`, the F03 `ClassCastException` trap) AND `$unset statusTokenHash` (plain field). Atomic within the existing flip.
- [X] T038 [US3] Make `backend/src/main/java/com/cadence/service/ErasureRequestService.java` `requestErasure(...)` idempotent: insert + catch `DuplicateKeyException` → return the existing open request (the unique partial index from T010 is the real guard; FR-022 "no second PENDING"). Add `existsByWorkspaceIdAndCandidateIdAndStatus(...)` to `ErasureRequestRepository` only if needed for the fast path.
- [X] T039 [US3] Add to `CandidateStatusService`: `requestErasureByToken(rawToken, ip)` — rate-limit, `resolveActiveByToken` (only record for an active candidate), call `ErasureRequestService.requestErasure(ws, candidateId, ErasureReasonCode.CANDIDATE_REQUEST)` (reuse the existing enum value), ALWAYS return the constant ack (no oracle).
- [X] T040 [US3] Add `POST /api/candidate/status/{token}/erasure-request` to `CandidateStatusController` (affirmative POST; `GET`→405; rate-limited; 202 ack via `ErasureAckResponse` in `CandidateStatusDtos`).
- [X] T041 [US3] Add the "Request data deletion" control + on-page acknowledgement to `frontend/src/app/features/status/candidate-status.component.ts` (affirmative action → `status.service.ts` POST → ack state); Jasmine for the ack flow; axe still 0 violations with the control present.
- [X] T042 [P] [US3] Extend `StatusLogPiiScanTest` (erasure leg): drive an erasure submit + a wipe with sentinels; assert no PII/token in logs or the erased doc.

**Checkpoint**: All three stories independently functional; GDPR self-service path complete and oracle-free.

---

## Phase 6: Polish & Cross-Cutting Concerns

- [X] T043 [P] Extend `.github/workflows/ci.yml` PII scan with the `SENTINELF30STAGE_*`/`SENTINELF30NEXT_*` + status-token sentinels (the `SENTINELF23*` block pattern; ASCII only — Principle V).
- [X] T044 [P] Add deploy note: `ChangeUnit015` ⇒ `scripts\db-migrate.ps1` then `scripts\deploy-all.ps1` (Mongock applies on startup); no new Fly secret. (No script edits — verify the existing scripts are pure ASCII/CRLF, Principle V.)
- [X] T045 Run the full backend suite (`gradlew test`), `frontend ng test --watch=false`, `ng build --configuration production`, and the Lighthouse stub audit against `/status?token=lighthouse-demo`; record results. The first multi-class Testcontainers run after a recompile may throw the one-time `GenericContainer` class-init error — re-run.
- [X] T046 Execute `quickstart.md` end-to-end (publish → view → update-reflect-on-reload → erasure-request → rotate) and confirm each SC→test mapping.
- [X] T047 ⚠️ Multi-role sub-agent implementation review (≥3 roles: Backend, Security/GDPR, QA — Constitution C6) against the real diff; apply or report findings before close. Confirm the two BLOCKER fixes (`$set null statusToken`; rate-limit threshold) and the no-oracle handler are present.
- [X] T048 [P] Add the F30 "Implementation Notes" section to `CLAUDE.md` (the per-feature precedent) capturing the dual-token store, the `$set null` converter-clear rule, the no-oracle handler, the dedupe migration, and the displayState/Clock pattern.

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (P1: T001–T003)** — no deps; the enums/props the rest references.
- **Foundational (P2: T004–T011)** — depends on Setup; **BLOCKS all stories** (model, token plumbing, migration, no-oracle handler). T004→T005 (fields before converter registration); T004→T006; T009 depends on T004–T007; T010 is independent of T009.
- **US1 (P3)**, **US2 (P4)**, **US3 (P5)** — all depend on Foundational. US1 and US2 are the co-shipping P1 MVP; US2 creates the data US1 displays (US1 is independently testable by seeding). US3 is P2.
- **Polish (P6)** — after the desired stories.

### Story-level

- **US1**: T012–T015 (tests) before T016–T023 (impl). T016 depends on T009; T018 depends on T016+T017+T008.
- **US2**: T024–T026 (tests) before T027–T033. T027/T028 depend on T009; T030 depends on T027–T029+T008; T031 is one atomic commit.
- **US3**: T034–T036 (tests) before T037–T042. T037 (wipe) + T038 (idempotency) + T039 depend on Foundational; T040 depends on T039+T008.

### Parallel Opportunities

- Setup T001/T002/T003 all [P].
- Foundational: T006/T007/T011 [P]; T010 [P] alongside T009.
- Within each story, all test tasks marked [P] run together; DTO/frontend-service tasks marked [P] run alongside service impl.
- With capacity: once Foundational lands, US1 and US2 proceed in parallel (different files); US3 after (touches the shared wipe + erasure service).

---

## Parallel Example: User Story 1 tests

```
T012 Unit DisplayStateResolverTest         (backend/.../status/)
T013 Contract CandidateStatusViewContractTest
T014 Integration CandidateStatusViewIT
T015 Jasmine+axe candidate-status.component.spec.ts   (frontend)
T023 StatusLogPiiScanTest (view leg)
```

---

## Implementation Strategy

### MVP (the two P1 stories ship together)

1. Phase 1 Setup → Phase 2 Foundational (CRITICAL).
2. Phase 3 US1 (candidate view) + Phase 4 US2 (recruiter maintain) — these are the MVP; US2 makes US1 demoable.
3. **STOP and VALIDATE**: recruiter publishes → candidate sees it → reflects on reload → link rotates (browser→DB).

### Incremental

4. Phase 5 US3 (candidate erasure) → validate GDPR self-service.
5. Phase 6 Polish (CI PII scan, deploy note, full-suite run, multi-role review, CLAUDE.md notes).

---

## Notes

- [P] = different files, no incomplete-task dependency. [US#] maps to spec.md stories.
- ⚠️FIX tasks carry the multi-role plan-review fixes — do not drop them: the `$set null statusToken` (T037), the rate-limit threshold (T013), the no-oracle handler (T008), the Clock injection (T009/T012), the dedupe migration (T010/T036), the read-your-write + concurrency tests (T025/T026), the atomic CONFIRMATION template change (T031), and the decrypted-link leak scan (T033).
- Tests fail before implementation (Principle VII). Commit per task or logical group. No new dependency, no new collection, no broker (C2/C4). All candidate strings `$localize` (§IX). PII/token never logged (§VIII).
