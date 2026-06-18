# Tasks: ATS Integration - Lever (F41)

**Input**: Design documents from `C:\Users\xamcr\Cadence\specs\020-lever-ats\`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/ats-api.md, quickstart.md (all present)

**Tests**: INCLUDED - constitution Section VII (Test-First) is mandatory and the plan's Test Plan enumerates them. Test tasks precede/accompany the implementation they cover.

**Organization**: By user story (US1-US4 from spec.md). A large **Foundational** phase carries the multi-connector refactor, which is compile-atomic (the F40 connection/repo/service signatures change together) and blocks every story.

## Format: `[ID] [P?] [Story?] Description`

- **[P]**: different file, no dependency on an incomplete task -> parallelizable
- **[Story]**: US1-US4; Setup/Foundational/Polish carry no story label
- All paths are repo-root-relative to `C:\Users\xamcr\Cadence\`

## Critical context (read before starting)

- **F40 is workspace-keyed; F41 makes it `(workspace, provider)`-keyed.** Changing `AtsConnectionRepository.findByWorkspaceId` from `Optional`->`List` is a **compile-break** that ripples to `AtsConnectionService`, `AtsWriteBackService`, `AtsSyncService`, `AtsConnectionController`, and the F40 fixtures `AtsItBase`/`AtsConnectionIT`. All of Phase 2 must land together to keep the build green; do not stop mid-phase.
- **Two confused-deputy `{workspaceId}`-only filters MUST become provider-scoped** (Security review): `AtsWriteBackService.claimAndDeliver`'s connection lookup + its NEEDS_REAUTH flip; and `AtsConnectionService.health`'s dead-letter `count`.
- **Greenhouse runtime behaviour is preserved** under the new shape (single-Greenhouse path unchanged); only signatures + filters change.
- **Mongock**: `ChangeUnit019` (order `"019"` off the highest applied `"018"`), **pure ASCII** Java source (the F30 binary-detection lesson). Native `createIndex`/targeted `dropIndex` (never `dropIndexes()`).
- **Run flags** (CLAUDE.md): `JAVA_HOME=C:\jdk-24.0.1`, cached `gradle-9.4.0`, `-Dapi.version=1.41`, `DOCKER_HOST=npipe:////./pipe/docker_engine`. First multi-class Testcontainers run after recompile throws the one-time `GenericContainer` class-init error - re-run.
- **Stubs are JVM-lifetime singletons** - never `@AfterAll stop()` (the F40 dead-port footgun).

---

## Phase 1: Setup (Shared Configuration)

**Purpose**: The non-breaking config additions that the refactor and the Lever adapter need.

- [X] T001 [P] Add `LEVER` to the enum in `backend/src/main/java/com/cadence/integration/AtsProvider.java` (`{ GREENHOUSE, LEVER }`); update the javadoc to drop "Greenhouse is the only MVP connector".
- [X] T002 [P] Add a `Lever` nested class (`baseUrl` default `https://api.lever.co` + getter) and `getLever()` to `backend/src/main/java/com/cadence/config/AtsProperties.java`, mirroring the existing `Greenhouse` nested class.
- [X] T003 [P] Add `cadence.ats.lever.base-url: ${CADENCE_ATS_LEVER_BASE_URL:https://api.lever.co}` to the `cadence.ats` block in `backend/src/main/resources/application.yml` (mirroring `greenhouse.base-url`).
- [X] T004 [P] Extend the CI PII scan in `.github/workflows/ci.yml`: add Lever candidate-name + credential sentinels and an `api.lever.co` base-URL guard restricted to `LeverAtsClient.java` (the F11 `graph.microsoft.com` / F40 `harvest.greenhouse.io` precedent; pure ASCII).

---

## Phase 2: Foundational - the multi-connector refactor (BLOCKING)

**Purpose**: Make the ATS subsystem `(workspace, provider)`-keyed so Greenhouse + Lever can coexist. **No user story can begin until this compiles and the migrated F40 suite is green.**

** Compile-atomic**: T005-T015 change interdependent signatures; complete the whole phase before running the suite.

- [X] T005 [P] Add a `provider` field (`AtsProvider`, `@Field(write=NON_NULL)`, getter/setter, include in `toString()`) to `backend/src/main/java/com/cadence/domain/AtsWriteBack.java` (data-model Delta3 - the routing key).
- [X] T006 [P] Add a `provider` field (`AtsProvider`, getter/setter) to `backend/src/main/java/com/cadence/domain/AtsSyncRun.java` (data-model Delta4 - per-provider status).
- [X] T007 [P] Create `backend/src/main/java/com/cadence/config/migration/ChangeUnit019_AtsLeverMultiConnector.java` (order `"019"`, author "system", **pure ASCII**): `@Execution` drops the unique `{workspaceId}` index on `atsConnections` (created by ChangeUnit018), creates unique `{workspaceId, provider}`, and creates the **additive** `atsSyncRuns {workspaceId, provider, startedAt:-1}` (the F40 `{workspaceId, startedAt:-1}` index stays - different key, no collision); `@RollbackExecution` drops ONLY the two new keys (`{workspaceId, provider}` and `{workspaceId, provider, startedAt:-1}`) and recreates unique `{workspaceId}` - never touches the F40 `atsSyncRuns` index. Native `createIndex`/targeted `dropIndex`.
- [X] T008 Change `backend/src/main/java/com/cadence/repository/AtsConnectionRepository.java`: `findByWorkspaceId` returns `List<AtsConnection>` (was `Optional`); add `Optional<AtsConnection> findByWorkspaceIdAndProvider(String workspaceId, AtsProvider provider)`. Update the javadoc.
- [X] T009 Add `Optional<AtsSyncRun> findFirstByWorkspaceIdAndProviderOrderByStartedAtDesc(String workspaceId, AtsProvider provider)` to `backend/src/main/java/com/cadence/repository/AtsSyncRunRepository.java`.
- [X] T010 Add `void cancelPendingForWorkspaceAndProvider(String workspaceId, AtsProvider provider)` to `backend/src/main/java/com/cadence/service/AtsWriteBackInvalidator.java`.
- [X] T011 Refactor `backend/src/main/java/com/cadence/service/AtsConnectionService.java` to be provider-scoped: `connect(ws, provider, key)` - change the **upsert query filter** (today `Criteria.where("workspaceId")` only, ~line 86) to `{workspaceId, provider}` so Greenhouse-then-Lever inserts a second row instead of updating the Greenhouse row; the DuplicateKey catch now races the `{workspaceId, provider}` index. `disconnect(ws, provider)` - change the filter (~line 108) to `{workspaceId, provider}` and call `writeBacks.cancelPendingForWorkspaceAndProvider(ws, provider)`. `health(ws, provider)` reads `findByWorkspaceIdAndProvider` and **scopes the dead-letter `count` to `{workspaceId, provider, status:DEAD_LETTER}`** (Security fix - today ~line 121 counts both providers); the absent-connection `Health` default currently hardcodes `AtsProvider.GREENHOUSE` (~line 126) - it MUST return the **requested** provider. Add `List<Health> listHealth(ws)` returning every provider (INTEGRATION_PENDING default carrying the correct absent provider, not always GREENHOUSE).
- [X] T012 Refactor `backend/src/main/java/com/cadence/service/AtsSyncService.java`: the two connection-status `updateFirst` filters (success ~line 103 + failure ~line 120) become `{workspaceId, provider}`; `reconcile` replaces the **4** hardcoded `AtsProvider.GREENHOUSE` with `conn.getProvider()` (the resolve ~139, the `$set atsProvider` ~153, the insert `setAtsProvider` ~170, the raced re-resolve ~184) - so `reconcile` must take the provider as a parameter; `recordRun` sets `provider` - **thread the provider through `recordRun(...)`** (its current signature has no provider param, ~line 198). The email-adopt guard `atsExternalRef == null` is UNCHANGED (it already enforces FR-008 cross-provider non-merge).
- [X] T013 Refactor `backend/src/main/java/com/cadence/service/AtsWriteBackService.java`: `enqueue` sets `w.setProvider(c.getAtsProvider())`; `claimAndDeliver` loads the connection via `connections.findByWorkspaceIdAndProvider(claimed.getWorkspaceId(), claimed.getProvider())`; the NEEDS_REAUTH flip filter adds `.and("provider").is(claimed.getProvider())` (confused-deputy fix); implement `cancelPendingForWorkspaceAndProvider` (add `.and("provider")` to the existing `cancelPendingForWorkspace` query - keep both).
- [X] T014 Provider-parameterize `backend/src/main/java/com/cadence/api/AtsConnectionController.java` + `AtsDtos.java`: new `GET /connections` (List<HealthResponse> for all providers via `listHealth`); `GET|POST|DELETE /{provider}/connection`, `GET /{provider}/sync-status`, `GET /{provider}/dead-letters`. Bind `{provider}` as `String` and resolve to the enum in-controller, throwing `AtsExceptions.InvalidRequestException` (-> 400) on an unknown value (do NOT rely on the enum binder - `MethodArgumentTypeMismatchException` would 500). Keep the class-level `hasRole('ADMIN')` + `hasAnyRole('ADMIN','RECRUITER')` on reads.
- [X] T015 Migrate the F40 fixtures to the new signatures so the suite compiles: in `backend/src/test/java/com/cadence/ats/AtsItBase.java`, the helper bodies `connect(String)`/`sync(String)` change internally (`findByWorkspaceId(ws).orElseThrow()` -> `findByWorkspaceIdAndProvider(ws, GREENHOUSE).orElseThrow()`) but **keep their single-arg signatures**, so the subclasses `AtsSyncIT`/`AtsWriteBackIT`/`AtsResurrectionGuardIT` that call the helpers stay green untouched (do NOT edit them). In `AtsConnectionIT.java`, the **direct** service calls break: `health(WS)`/`disconnect(WS)` -> provider-arg forms; the `findByWorkspaceId(WS)` Optional assertions -> `findByWorkspaceIdAndProvider(WS, GREENHOUSE)`. (Note: `AtsWriteBackIT`'s raw `mongoTemplate.updateFirst(Criteria.where("workspaceId"), AtsConnection.class)` compiles unchanged and is unambiguous with a single Greenhouse connection - leave it.)
- [X] T016 Add `backend/src/test/java/com/cadence/ats/AtsIndexTest.java` (the ChangeUnit019 migration test, **FR-031/SC-013**): with a pre-seeded GREENHOUSE `atsConnections` row, assert (a) the old unique `{workspaceId}` index is gone, (b) inserting a LEVER row for the same workspace succeeds, (c) a second GREENHOUSE row for that workspace is rejected by the `{workspaceId, provider}` unique index. Also assert `atsSyncRuns {workspaceId, provider, startedAt:-1}` exists.

**Checkpoint**: Build green; full `com.cadence.ats` + `RbacEndpointInventoryTest` + all F01-F40 suites pass under the new multi-provider shape (Greenhouse still works). `AtsIndexTest` green.

---

## Phase 3: User Story 1 - Connect a workspace to Lever (coexisting with Greenhouse) (Priority: P1)  MVP

**Goal**: An Admin can connect Lever (verify + store encrypted), see per-provider health, and manage it independently of a coexisting Greenhouse connection.

**Independent Test**: Connect Lever against `StubLever` -> CONNECTED with last-verified; invalid key -> no usable connection + non-leaking 409; with Greenhouse already connected, both coexist and disconnecting one leaves the other (US1 AS1-AS5, SC-006/SC-010).

### Tests for User Story 1

- [X] T017 [P] [US1] Create `backend/src/test/java/com/cadence/ats/StubLever.java` - a JDK `com.sun.net.httpserver.HttpServer` sibling of `StubGreenhouse`, **JVM-lifetime singleton** (no `@AfterAll stop()`), with: `GET /v1/opportunities` (seeded opportunities incl. `expand=stage`/`applications`, plus `SENTINEL`-marked `links`/`tags`/`sources`/`origin`/`headline`/EEO fields for the minimization test), `POST /v1/opportunities/{id}/notes`, per-(method,path) status SEQUENCES + injectable error/timeout for isolation tests, request recording, and the `gate(n)` latch (mirror StubGreenhouse). Wire its base URL via `@DynamicPropertySource` (`cadence.ats.lever.base-url`).
- [X] T018 [P] [US1] Create `backend/src/test/java/com/cadence/ats/AtsConnectionContractTest.java` (MockMvc, **FR-004**, net-new - no F40 contract test exists): 5 roles  {`GET /connections`, `POST`/`DELETE /{provider}/connection`, `GET /{provider}/sync-status`, `/{provider}/dead-letters`} -> Admin-mutate / Recruiter-read / HMInterviewerRead-only -> 403; plus the unknown-`{provider}` -> 400 no-oracle case and the 409 `verification_failed` envelope (no key echo). Internal endpoints use `.with(csrf())`.
- [X] T019 [P] [US1] Create `backend/src/test/java/com/cadence/ats/AtsLeverConnectIT.java` (Testcontainers): connect Lever via `StubLever` -> CONNECTED + lastVerifiedAt; invalid key -> `VerificationFailedException` (409), no usable connection; reading the stored `atsConnections` LEVER row directly shows `apiKey` only as ciphertext (**SC-006**), never returned in the health DTO; connect Greenhouse **and** Lever in one workspace -> two independent rows, disconnect Lever leaves Greenhouse intact (**US1 AS5**, the FR-031 coexistence proof).

### Implementation for User Story 1

- [X] T020 [US1] Create `backend/src/main/java/com/cadence/integration/LeverAtsClient.java` - `@Component implements AtsConnector`, `provider()==LEVER`; `RestClient` on a `JdkClientHttpRequestFactory` (bounded `connect`/`read` timeouts from `AtsProperties`, base `props.getLever().getBaseUrl()`); HTTP Basic with the key as username (reuse the F40 `basic()` shape); implement `verifyCredential` -> `GET /v1/opportunities?limit=1` via `retry.execute(...)`, normalising failures to `AtsApiException` through the existing `AtsApiClassifier` (401/403->reauth). No body-logging. (`fetchCandidates`/`pushActivity` stubbed to throw `UnsupportedOperationException` for now - filled in US2/US3.)
- [X] T021 [US1] Update the frontend Admin surface for two providers: `frontend/src/app/features/admin/ats/ats.service.ts` (methods take a `provider` arg; call `GET /connections` + `/{provider}/...`), `ats-integration.component.ts` (list both providers with per-provider connect/disconnect/health/last-sync/dead-letter-count badges), and `ats-integration.component.spec.ts` (Jasmine: list shows both providers, connect posts to the provider path, disconnect calls the provider DELETE, degraded badge renders). Internal screen - no Section IX gate.

**Checkpoint**: Lever connects and coexists with Greenhouse, browser->DB; US1 ITs + contract test green.

---

## Phase 4: User Story 2 - Lever candidates and stages flow in automatically (Priority: P1)

**Goal**: The scheduled poll imports Lever opportunities (minimized fields, raw stage) into `candidates`, reconciled provider-correctly, no duplicates, within 5 minutes.

**Independent Test**: Seed `StubLever`; run a sync; candidates appear with `atsProvider=LEVER` + external ref + posting + raw stage; re-sync is idempotent; a same-email/different-provider candidate stays two records; SENTINEL minimization fields never persist (US2 AS1-AS6, SC-001/SC-002/SC-013a/SC-013b).

### Tests for User Story 2

- [X] T022 [P] [US2] Create `backend/src/test/java/com/cadence/ats/LeverAtsClientTest.java` (pure unit, **FR-029**): drive `parseCandidates` over a seeded Lever opportunities body - assert only name/email/phone/opportunity-id/posting/stage.text are read and the `SENTINEL` `links`/`tags`/`sources`/`origin`/`headline`/EEO fields never reach the `AtsCandidateRecord`; assert the EEO endpoint is never requested; assert classifier mapping (401/403->reauth, 429/5xx->transient) via the reused `AtsApiClassifier`.
- [X] T023 [P] [US2] Create `backend/src/test/java/com/cadence/ats/AtsMultiConnectorIT.java` (Testcontainers, both stubs): **SC-013a** seed the same external identity in both stubs -> after both syncs exactly two candidates with distinct `(atsProvider, atsExternalRef)`, count stable on re-sync; **SC-013b** a Lever candidate sharing an email with an existing Greenhouse-keyed candidate stays a separate record (not adopted); **SC-002** a 50-opportunity Lever burst imported exactly once within one poll (also asserts the reused `cadence.ats.poll-interval` 5-min bound, FR-009); each record carries `atsProvider=LEVER` + raw stage label; **SC-012** reading the stored `candidates` LEVER doc directly via the raw driver shows name/email/phone only as ciphertext (the F40 `AtsSyncIT` crypto-assert precedent); **SC-008** an imported Lever candidate with no recorded consent/lawful basis cannot be emailed - attempt an outbound send and observe the `ContactPermissionGate` blocks it (NO_BASIS).
- [X] T024 [P] [US2] Create `backend/src/test/java/com/cadence/ats/AtsOverlappingSyncIT.java` (gated concurrency, **FR-022/edge case**): run a Greenhouse `syncWorkspace` and a Lever `syncWorkspace` for one workspace concurrently via the stub `gate(n)` latch; assert no double-import, no cross-provider merge, no corruption.

### Implementation for User Story 2

- [X] T025 [US2] Implement `fetchCandidates` in `backend/src/main/java/com/cadence/integration/LeverAtsClient.java`: `GET /v1/opportunities?limit=<page>&expand=stage&expand=applications[&updated_at_start=<cursor>]`; parse via explicit `JsonNode.path` ONLY id(externalRef)/name/emails[0]/phones[0]/posting(id+title)/`stage.text`; return `AtsFetchResult(records, nextCursor)` where `nextCursor` is the Lever `next` token (null when `hasNext` false). Never touch links/tags/sources/origin/headline/archived/EEO. (Reconcile-provider swap already landed in T012.)

**Checkpoint**: Lever candidates import and stay provider-correct alongside Greenhouse; US2 tests green.

---

## Phase 5: User Story 3 - Scheduling activity written back to Lever (Priority: P2)

**Goal**: A scheduling event for a mapped Lever candidate writes one note to that candidate's Lever timeline, routed by the candidate's provider, idempotently.

**Independent Test**: Trigger a scheduling event for a Lever candidate -> a note on the Lever opportunity in `StubLever`; a Greenhouse candidate's event routes to Greenhouse only; no candidate free-text in logs (US3 AS1-AS4, SC-013c).

### Tests for User Story 3

- [X] T026 [P] [US3] Create `backend/src/test/java/com/cadence/ats/AtsLeverWriteBackIT.java` (Testcontainers): a write-back enqueued for a mapped Lever candidate is delivered to `StubLever`'s notes endpoint (one note); **SC-013c** a Greenhouse candidate's write-back never reaches `StubLever` (routed to Greenhouse only); **idempotency cross-provider non-collision** - a GH candidate and a Lever candidate with otherwise-identical `(type, eventAt)` produce two distinct outbox rows, each routed to the correct provider; a re-drain delivers no duplicate.

### Implementation for User Story 3

- [X] T027 [US3] Implement `pushActivity` in `backend/src/main/java/com/cadence/integration/LeverAtsClient.java`: `POST /v1/opportunities/{externalRef}/notes` body `{ "value": "<activity.note()>" }` (the F40 non-PII scheduling-fact note, reused); return the opaque note id. (Routing via `AtsWriteBack.provider` already landed in T013.) Add a TODO referencing FR-032: confirm the live `perform_as`/note-addressing requirement at credential promotion.

**Checkpoint**: Lever write-backs deliver provider-correctly; US3 test green.

---

## Phase 6: User Story 4 - Resilience & provider isolation (Priority: P2)

**Goal**: A Lever outage queues (never drops) write-backs and shows degraded - without stalling a coexisting Greenhouse connection; erasure/disconnect are provider-scoped.

**Independent Test**: Make `StubLever` error -> Lever write-backs hold + degraded badge, Greenhouse keeps syncing; Lever recovers -> drains within policy; erase a Lever candidate -> PII wiped/ref retained/re-poll no-op; disconnect Lever cancels only Lever's queue (US4 AS1-AS5, SC-004/SC-014/SC-015/SC-007).

### Tests for User Story 4

- [X] T028 [P] [US4] Create `backend/src/test/java/com/cadence/ats/AtsProviderIsolationIT.java` (**SC-014/FR-022**): with `StubLever` erroring/timing-out and `StubGreenhouse` healthy in one workspace - Greenhouse sync + write-back proceed normally; queued Lever write-backs hold and drain on recovery (**SC-004**); a Lever auth (401) failure flips ONLY the Lever connection to NEEDS_REAUTH and leaves Greenhouse CONNECTED (the confused-deputy assertion on the T013 fix); Lever's degraded badge is independent.
- [X] T029 [P] [US4] Create `backend/src/test/java/com/cadence/ats/AtsLeverErasureIT.java` (**SC-015/FR-015**): erase a Lever candidate -> `atsStageLabel`/`atsExternalJobTitle` wiped, `atsProvider`/`atsExternalRef` retained, a subsequent Lever sync is a guarded no-op (no resurrection); disconnect Lever cancels only Lever's PENDING write-backs while a coexisting Greenhouse PENDING queue is untouched.
- [X] T030 [P] [US4] Create `backend/src/test/java/com/cadence/ats/AtsBothProviderRestartIT.java` (**SC-007**): interleave a Greenhouse and a Lever in-flight sync/drain, re-invoke the sweep/drain -> no duplicate imports, one note per provider, no cross-provider corruption. Document the honest bound in a class comment (double-sweep idempotency proxy, not a true process restart - the F40/F31 precedent).

**Checkpoint**: Provider isolation, degraded mode, and provider-scoped erasure/disconnect all verified.

---

## Phase 7: Polish & Cross-Cutting

- [X] T031 [P] Create `backend/src/test/java/com/cadence/ats/AtsNoLeverLiteralStructuralTest.java` (**SC-009**): constant-pool scan asserting no `com.cadence.service`/`com.cadence.scheduler` class references a Lever URL/type (the F22 `MailTransportSwapTest` precedent, extended for Lever).
- [X] T032 [P] Create `backend/src/test/java/com/cadence/ats/AtsLogPiiScanTest.java` (**SC-005**, closes the F40 residual): drive the full connect -> sync -> write-back -> **failure** flow with Lever candidate-name + credential sentinels; assert absence across logs / `atsConnections.lastErrorCategory` / dead-letter / sync-run records (non-circular SENTINEL discipline).
- [X] T033 Run `quickstart.md` end-to-end: backend suite green (incl. all new `com.cadence.ats.*`), `RbacEndpointInventoryTest` green, all F01-F40 suites green, `ng test --watch=false` + `ng build --configuration production` clean; CI PII scan (T004) passes. Record results.
- [X] T034 Multi-role sub-agent review loop 2 (constitution C6, 3 roles: Backend/DevOps, Security/GDPR, QA) against the implemented diff; apply or report all findings before close. Confirm the FR-032 live-promotion `perform_as` gap is carried to the mandatory live security re-review.

---

## Dependencies & Execution Order

### Phase dependencies

- **Setup (T001-T004)**: no dependencies; all [P].
- **Foundational (T005-T016)**: depends on Setup (needs `LEVER` enum, Lever props). **Compile-atomic - blocks ALL stories.** T005/T006/T007 are [P]; T008->{T011,T012,T013,T014} (signature change first); T015/T016 after the production refactor.
- **US1 (T017-T021)**: depends on Foundational. T017/T018/T019 [P] (tests, different files); T020 before T019 passes (LeverAtsClient.verify); T021 frontend independent.
- **US2 (T022-T025)**: depends on Foundational + T017 (StubLever) + T020 (client skeleton). T022/T023/T024 [P]; T025 (fetch) makes T023/T024 pass.
- **US3 (T026-T027)**: depends on Foundational + T017 + T020. T026 before T027 passes.
- **US4 (T028-T030)**: depends on Foundational + T017 + T020 + (T025 for sync isolation, T027 for write-back isolation). T028/T029/T030 [P].
- **Polish (T031-T034)**: after the stories it audits (T031/T032 after the adapter + flows exist).

### Story independence

US1-US4 each rest on the Foundational refactor but are then independently testable: US1 (connect/coexist) needs no import; US2 (import) needs no write-back; US3 (write-back) needs a mapped candidate (seed directly); US4 (resilience) layers on US2/US3 stubs. MVP = Setup + Foundational + US1.

### Parallel example (US1 tests)

```
T017 StubLever               (test infra)        
T018 AtsConnectionContractTest (MockMvc)           run together [P]
T019 AtsLeverConnectIT        (Testcontainers)   
```

---

## Implementation Strategy

1. **Setup -> Foundational** (T001-T016): land the entire multi-connector refactor + migrate F40 fixtures; **gate on the full F01-F40 suite staying green** before any Lever behaviour. This is the riskiest phase (compile-atomic, confused-deputy fixes) - do it first, verify, commit.
2. **US1** (MVP): LeverAtsClient.verify + StubLever + connect/coexistence + contract + frontend -> demoable "connect Lever alongside Greenhouse".
3. **US2 -> US3 -> US4** incrementally; each adds a `LeverAtsClient` method (sequential, same file) + its acceptance tests.
4. **Polish**: structural + PII-scan tests, quickstart validation, and the mandatory C6 loop-2 review.

## Notes

- `LeverAtsClient` is built across T020 (verify) -> T025 (fetch) -> T027 (push): same file, **sequential, not [P]** with each other.
- `StubLever` is built once (T017) with all endpoints + error injection + `gate(n)`; later stories only consume it.
- Honest bounds carried from F40: SC-003 at-most-once is a claim-before-send local guard (Lever has no client dedup key); SC-007 restart is a double-sweep idempotency proxy; the per-provider dead-letter `count`/disconnect sweep use a bounded read (no dedicated `atsWriteBacks.provider` index - acceptable at MVP volume).
- FR-032 (live-promotion security re-review, incl. the Lever `perform_as` note-addressing gap) remains a separate, tracked step - NOT closed by this feature.

## Multi-role review (constitution C6) - 2026-06-18 (tasks phase)

Two reviewers (QA/process, Backend/DevOps) reviewed tasks.md against spec/plan and the **real codebase**. **Both: APPROVE-WITH-NITS, zero BLOCKERS.** Format compliance verified (all 34 tasks well-formed; `[P]`/`[US#]` discipline correct; `LeverAtsClient` same-file sequencing T020->T025->T027 correctly non-`[P]`). Call-site counts confirmed against source (4 GREENHOUSE literals in `reconcile`; the two confused-deputy `{workspaceId}`-only filters; controller hardcode; ChangeUnit018->019). Applied:
- **SC-008 (consent-on-import) + SC-012 (candidate PII ciphertext)** were untraced -> folded into T023 (+ the FR-009 poll-bound assertion).
- **T011** - made explicit that the `connect` upsert *filter* and `disconnect` filter must change, and that the absent-connection `Health` default hardcodes GREENHOUSE (~line 126) and must return the requested provider.
- **T012** - `reconcile`/`recordRun` must take the provider as a parameter (`recordRun`'s signature has no provider today).
- **T015** - clarified the `AtsItBase` helpers keep single-arg signatures so `AtsSyncIT`/`AtsWriteBackIT`/`AtsResurrectionGuardIT` stay green untouched; only `AtsConnectionIT`'s direct calls change.
- **T007** - rollback drops ONLY the two new index keys (the F40 `atsSyncRuns {workspaceId,startedAt:-1}` is additive and must not be touched).

A second review loop (T034) runs against the implemented diff at task close.
