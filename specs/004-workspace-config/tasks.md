# Tasks: Workspace Setup & Configuration

**Input**: Design documents from `C:\Users\xamcr\Cadence\specs\004-workspace-config\`
**Prerequisites**: plan.md, spec.md, research.md (D1–D12), data-model.md, contracts/workspace-api.md, quickstart.md

**Tests**: INCLUDED and written FIRST — the constitution (§VII Test-First & Acceptance-Driven) is non-negotiable for backend business logic and acceptance paths. Each story's tests are authored before its implementation and must fail first.

**Organization**: Tasks grouped by user story (US1–US6) for independent implementation/testing. Priorities from spec.md: US1/US2 = P1, US3/US4/US5 = P2, US6 = P3.

## Path Conventions (web app — see plan.md Structure)

- Backend main: `backend/src/main/java/com/cadence/`
- Backend test: `backend/src/test/java/com/cadence/`
- Frontend: `frontend/src/app/`
- All integration tests extend `BaseIntegrationTest` (shared `@ServiceConnection` singleton `mongo:7`), clean `workspaceConfig`, `workspaceLogo` AND `authAuditLog` in `@BeforeEach` via `mongoTemplate.remove(new Query(), Type.class)` — **never `dropCollection`** (drops the Mongock `004` indexes; CLAUDE.md F00.1). Classes that seed members per role / multiple Admins (T022, T048) MUST **also** `remove(...)` `Member` and `Session` (never `dropCollection` — drops the F01 `emailHash` unique index). Use `@MockBean` (Boot 3.3, not `@MockitoBean`) and `@Import` the F01 `MutableClock` test config where time matters.
- **Zero new runtime dependencies** — Admin gate reuses `@EnableMethodSecurity` (already in `SecurityConfig`); logo validation/headers use JDK `javax.imageio`/Spring; credential crypto reuses `PiiCrypto`/`PiiStringConverter` (research D2/C4).

## Shared-file sequencing note

`WorkspaceConfigController.java`, `WorkspaceConfigService.java`, `WorkspaceDtos.java`, and `frontend/.../workspace-settings.component.ts` are each touched by multiple stories. Tasks editing the **same** file are NOT `[P]` relative to each other and run in task-ID order; the controller/service skeletons are built in Foundational so each story only *adds* its handler/method.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Configuration only — F03 adds no new dependency or scaffold.

- [X] T001 [P] Verify `backend/build.gradle` adds **no** new runtime dependency (gate C4) and that `@EnableMethodSecurity` is present on `SecurityConfig` (reused for the Admin gate); confirm `-Djava.awt.headless=true` for the Fly JAR run (Spring Boot default — make explicit in `backend/Dockerfile`/run notes, research D6). Record the static check in task notes.

---

## Phase 2: Foundational (Blocking Prerequisites)

**⚠️ CRITICAL**: No user story can begin until this phase is complete. Builds the shared backbone: the two new collections + migration, the credential converter registration, the audit extension, the DTOs/exception handler, the read-only config service + controller skeleton, the `/me` flag, and the frontend auth/service core.

### Backend — domain & data

- [X] T002 [P] Create `WorkingHours` embedded value (`LocalTime start`, `LocalTime end`) in `backend/src/main/java/com/cadence/domain/WorkingHours.java` (data-model).
- [X] T003 [P] Create `WorkspaceConfig` `@Document("workspaceConfig")` in `backend/src/main/java/com/cadence/domain/WorkspaceConfig.java` with fields per data-model (id, workspaceId, configuredAt, name, timeZone, workingHours, slaSilenceWindowDays, retentionPeriodDays, retentionAcknowledgedAt, brandColor, hasLogo, emailSendingDomain, emailProviderCredential, templateLocks Map, createdAt, updatedAt). The `emailProviderCredential` field is **structurally write-only**: annotate `@com.fasterxml.jackson.annotation.JsonIgnore` and hand-override `toString()` to omit it (research D2/BE-3). Depends on T002.
- [X] T004 [P] Create `WorkspaceLogo` `@Document("workspaceLogo")` (id, workspaceId, `byte[] bytes`, contentType, size, updatedAt) in `backend/src/main/java/com/cadence/domain/WorkspaceLogo.java` (data-model).
- [X] T005 [P] Add `WORKSPACE_CONFIGURED` and `WORKSPACE_CONFIG_CHANGED` to `backend/src/main/java/com/cadence/domain/AuthEventType.java` (MODIFIED — data-model/D8).
- [X] T006 [P] Add nullable non-PII `oldValue`, `newValue` (`String`) fields + getters/setters to `backend/src/main/java/com/cadence/domain/AuthAuditEvent.java` (MODIFIED — data-model/D8).
- [X] T007 [P] Create `WorkspaceConfigRepository` (`findByWorkspaceId`, `existsByWorkspaceIdAndConfiguredAtNotNull` — both READ-ONLY, never get-or-create per D4) in `backend/src/main/java/com/cadence/repository/WorkspaceConfigRepository.java`.
- [X] T008 [P] Create `WorkspaceLogoRepository` (`findByWorkspaceId`, `deleteByWorkspaceId`) in `backend/src/main/java/com/cadence/repository/WorkspaceLogoRepository.java`.
- [X] T009 Create Mongock `ChangeUnit004_WorkspaceConfigIndexes` (`@ChangeUnit(id="004-workspace-config-indexes", order="004", author="system")`, never renamed; native `getCollection(...).createIndex(new Document("workspaceId",1), new IndexOptions().unique(true))` on **both** `workspaceConfig` and `workspaceLogo`; targeted `dropIndex(...)` per collection in `@RollbackExecution`, never `dropIndexes()`; inject `MongoTemplate` as the `@Execution`/`@RollbackExecution` **method parameter**, mirroring `ChangeUnit003`) in `backend/src/main/java/com/cadence/config/migration/ChangeUnit004_WorkspaceConfigIndexes.java` (research D12; depends on T003, T004).
- [X] T010 Register the existing `PiiStringConverter` for `WorkspaceConfig.emailProviderCredential` in the **existing** `MongoCustomConversions` bean lambda in `backend/src/main/java/com/cadence/config/MongoPiiConfig.java` (MODIFIED — add `registrar.registerConverter(WorkspaceConfig.class, "emailProviderCredential", converter)`; reuse the same `converter` instance; do NOT add a second bean — research D2; depends on T003).

### Backend — shared DTOs, errors, service & controller skeleton

- [X] T011 [P] Create `WorkspaceDtos` in `backend/src/main/java/com/cadence/api/WorkspaceDtos.java` — request types (`SetupRequest` with `retentionAcknowledged:boolean`; `SettingsPatch` (all optional); `BrandingRequest`; `EmailConfigRequest`; `TemplateLockRequest`) and response types per the **full field list in data-model.md §DTOs** (`WorkspaceConfigResponse{configured, name, timeZone, workingHours, slaSilenceWindowDays, retentionPeriodDays, retentionAcknowledgedAt, brandColor, hasLogo, emailSendingDomain, credentialSet, templateLocks}` — `credentialSet:boolean` and **no** credential field; `BrandingResponse{brandColor, logoUrl}`). **`EmailConfigRequest` MUST be a class (not a record) with a hand-written `toString()` that omits `credential`** (research D2/SEC-NIT-1).
- [X] T012 [P] Create `WorkspaceExceptionHandler` (`@RestControllerAdvice`) mapping to the `{error,message}` envelope: validation → 400 `validation_failed` with a per-field `fields` map; already-configured → 409 `already_configured`; not-configured → 409 `not_configured`; invalid logo → 400 `invalid_logo`; retention-not-acknowledged → 400 `retention_not_acknowledged`. In `backend/src/main/java/com/cadence/api/WorkspaceExceptionHandler.java` (contracts). Ensure no bound request DTO (with a credential) is serialized into an error body/log.
- [X] T013 Add `workspaceConfigured(workspaceId, actorMemberId, acknowledgedRetentionDays)` (writes `WORKSPACE_CONFIGURED`, `newValue`=days), `configChanged(workspaceId, actorMemberId, settingCode, oldValue, newValue)` (writes `WORKSPACE_CONFIG_CHANGED`; old/new non-null only for `retention_period`, else null; never the credential value), and `setupConflict(workspaceId, actorMemberId)` (writes `WORKSPACE_CONFIG_CHANGED`, `outcome="setup_conflict"`) for the concurrent-loser path (US1 AS-7) to `backend/src/main/java/com/cadence/service/AuthAuditService.java`. Each new method MUST set `occurredAt` via the **injected `Clock`** (not `Instant.now()`, so the F01 `MutableClock` tests stay deterministic) and leave `sourceIpHash`/`targetMemberId` null (mirror the existing `roleChanged(...)`) (MODIFIED — research D8; depends on T005, T006).
- [X] T014 Create `WorkspaceConfigService` skeleton in `backend/src/main/java/com/cadence/service/WorkspaceConfigService.java` with: validation helpers (D7 bounds as constants — tz `ZoneId.of`, hours end>start no-overnight, SLA 1–30, retention 30–3650, colour `^#[0-9A-Fa-f]{6}$`, domain ASCII-LDH hostname), `isConfigured(workspaceId)` (read-only `existsBy...`), and `getConfig(workspaceId)` returning a `WorkspaceConfigResponse` (never creates a doc; `credentialSet` derived; defaults when no doc) (depends on T003, T007, T011).
- [X] T015 Create `WorkspaceConfigController` in `backend/src/main/java/com/cadence/api/WorkspaceConfigController.java` with **class-level `@PreAuthorize("hasRole('ADMIN')")`** under base path `/api/internal/workspace`, and the read-only `GET /config` handler (depends on T014, T011, T012). Endpoints for setup/settings/branding/logo/email/lock are added in their story phases (same file — sequential).
- [X] T016 Add `workspaceConfigured:boolean` to `backend/src/main/java/com/cadence/api/AuthDtos.java` `MemberSummary` and populate it in `AuthController.me()` via `WorkspaceConfigService.isConfigured(principal.workspaceId())`. **Also update the OTHER `MemberSummary` construction site in the same controller — `AuthController.login()` (and any other caller) — or the build will not compile** (login may pass `isConfigured(...)` too, an extra cheap read, or compute it). In `backend/src/main/java/com/cadence/api/AuthController.java` (MODIFIED — research D3; depends on T014).

### Backend — foundational tests

- [X] T017 [P] Create `WorkspaceConfigIndexBootstrapTest` asserting `listIndexes` returns the exact unique index names `workspaceConfig.workspaceId_1` and `workspaceLogo.workspaceId_1` (mirrors the F00.1/F02 name-assertion pattern; clean via `mongoTemplate.remove`) in `backend/src/test/java/com/cadence/workspace/WorkspaceConfigIndexBootstrapTest.java` (depends on T009).
- [X] T018 [P] Create `WorkspaceConfigServiceTest` (Mockito unit) — **parameterized** boundary cases (research D7/QA-4): SLA {0→reject,1→accept,30→accept,31→reject}; retention {0,29,3651→reject; 30,3650→accept}; working hours {end==start→reject, end<start (overnight)→reject, end>start→accept}; tz {non-IANA→reject, valid→accept}; colour {3-digit/named/`rgb()`→reject, `#RRGGBB`→accept}; domain {Unicode/control→reject, ASCII-LDH→accept}; ack-missing → setup refused. In `backend/src/test/java/com/cadence/workspace/WorkspaceConfigServiceTest.java` (depends on T014).

### Frontend — shared auth & service core

- [X] T019 [P] Add `workspaceConfigured: boolean` to the `MemberSummary` interface in `frontend/src/app/core/auth/auth.models.ts` (MODIFIED — FE-1) and update the `member()` test factory in `frontend/src/app/core/auth/role.guard.spec.ts` to include the new required field (so it still compiles).
- [X] T020 [P] Expose `workspaceConfigured` off the existing `member$` stream in `frontend/src/app/core/auth/auth.service.ts` (MODIFIED — FE-1/D11; no new accessor — it rides `member$`). Reuse the F02 `hasRole(...)` helper.
- [X] T021 [P] Create `WorkspaceService` in `frontend/src/app/features/admin/workspace/workspace.service.ts` with typed HTTP wrappers: `getConfig()`, `completeSetup(req)`, `patchConfig(req)`, `putBranding(req)`, `uploadLogo(file)` (builds `FormData`, **does not set `Content-Type`** so the browser sets the multipart boundary — FE-4), `deleteLogo()`, `putEmail(req)`, `deleteCredential()`, `putTemplateLock(key, locked)`. Uses the existing API interceptor (`withCredentials` + XSRF).

**Checkpoint**: Foundation ready — domain, migration, encryption, audit, read-only config read, `/me` flag, and frontend core all in place. User stories can now proceed.

---

## Phase 3: User Story 1 — First-run setup wizard (Priority: P1) 🎯 MVP

**Goal**: An Admin completes the wizard (name, tz, hours, SLA, retention + acknowledgment) which atomically transitions the workspace *unconfigured → configured*.

**Independent test**: Sign in as the first Admin on a fresh workspace, submit the wizard with valid values + acknowledgment → workspace persisted as configured; submit without acknowledgment → refused, stays unconfigured.

### Tests (write first, must fail)

- [X] T022 [P] [US1] Create `WorkspaceSetupIntegrationTest` in `backend/src/test/java/com/cadence/workspace/WorkspaceSetupIntegrationTest.java`: valid setup persists + `configured` (US1 AS-1/SC-004 via re-read); ack-missing → 400, stays unconfigured (US1 AS-2/SC-003); invalid field → 400 per-field, nothing persisted (US1 AS-3/SC-008); direct re-call on configured workspace → 409 (US1 AS-6/FR-006); **read-only invariant** — after a `GET /config` and a `/me` call on a fresh workspace, `workspaceConfig` document count == 0 (no read ever creates the doc — BE-1/D4); **concurrent first-run** via `CountDownLatch` (F02 last-admin pattern, N≥20) → exactly one `WORKSPACE_CONFIGURED` audit (the winner) **plus** a `setup_conflict` audit for the loser, so both attempts are audited and no server error occurs (US1 AS-7/SC-009); **restart-persistence** by re-reading through a freshly-built cold `MongoTemplate` (new `MongoClient` + a fresh `MongoCustomConversions` registering the same `PiiStringConverter` — NOT a JVM restart) asserting all settings unchanged AND the credential **decrypts to its original value** through the cold converter (US1 AS-5/SC-004, QA-1).

### Implementation

- [X] T023 [US1] Add `completeSetup(workspaceId, actorMemberId, SetupRequest)` to `backend/src/main/java/com/cadence/service/WorkspaceConfigService.java`: enforce `retentionAcknowledged==true` (else refuse, no write); validate all fields; **conditional upsert** `findAndModify(query={workspaceId, configuredAt:null}, update=$set all fields + configuredAt=now + retentionAcknowledgedAt=now, upsert=true)`; on success audit `workspaceConfigured(...)` with the acknowledged days; catch `DuplicateKeyException` → audit `setupConflict(...)` then throw already-configured (409) — so the concurrent loser still leaves an audit trail (US1 AS-7); also throw already-configured when a configured doc already exists (filter miss). Relies on reads being get-or-create-free (T007/T014) so the upsert is the only inserter (research D4/D8; depends on T014, T013).
- [X] T024 [US1] Add `POST /setup` handler to `WorkspaceConfigController` binding `SetupRequest` → `completeSetup(...)` → `WorkspaceConfigResponse` in `backend/src/main/java/com/cadence/api/WorkspaceConfigController.java` (depends on T023, T015).
- [X] T025 [US1] Create `workspace-setup-wizard.component.ts` (standalone, Angular Material) in `frontend/src/app/features/admin/workspace/workspace-setup-wizard.component.ts`: form (name, IANA tz picker, working-hours time inputs, SLA number, retention number, **mandatory ack checkbox**); per-field error display in a `role="alert"` region; all strings via `i18n="@@workspace.setup.*"` template + `$localize\`:@@workspace.setup.*:...\`` for programmatic errors (FE-6); labelled inputs, focus-to-error, ≥44px targets (FE-7); on success calls `auth.me()` to refresh `member$` then routes to the shell (FE-2); depends on T021, T020.
- [X] T026 [US1] Add `/workspace/setup` route guarded `[authGuard, roleGuard('ADMIN')]` in `frontend/src/app/app.routes.ts`, and add the redirect in `frontend/src/app/features/shell/shell.component.ts` `ngOnInit` **subscribe** (not template side-effect): filter null, then unconfigured Admin → `/workspace/setup` (FE-2/FE-3; depends on T025, T020).

**Checkpoint**: US1 independently demoable — a fresh workspace can be configured by an Admin; wizard runs once; GDPR gate enforced.

---

## Phase 4: User Story 2 — Ongoing settings management (Priority: P1)

**Goal**: An Admin updates operational settings post-setup; every non-Admin is refused (403).

**Independent test**: As Admin, change SLA 5→7 and tz, reload → persisted; as each non-Admin, read and write → 403, no change.

### Tests (write first, must fail)

- [X] T027 [P] [US2] Create `WorkspaceSettingsIntegrationTest` in `backend/src/test/java/com/cadence/workspace/WorkspaceSettingsIntegrationTest.java`: update persists + reflected on next read (US2 AS-1); invalid → per-field 400, no partial write (US2 AS-4/SC-008); PATCH on an unconfigured workspace → 409 `not_configured`; **concurrent DIFFERENT-field edits** via `CountDownLatch` (N≥20) → BOTH preserved, no lost update (FR-010/SC-009, QA-5); **concurrent SAME-field edits** (N≥20 racing the same field) → final value is one of the submitted values, the document is internally consistent, and both attempts are audited (US2 AS-5); confirm `configuredAt`/`retentionAcknowledgedAt` are never mutated by a PATCH (SEC-2).

### Implementation

- [X] T028 [US2] Add `updateSettings(workspaceId, actorMemberId, SettingsPatch)` to `backend/src/main/java/com/cadence/service/WorkspaceConfigService.java`: validate present fields; refuse if unconfigured (409); apply a **targeted `$set`** of only the changed fields via `findAndModify` keyed by `{workspaceId}` (no whole-doc read-modify-write); audit `configChanged(...)` per field (retention also records old/new); never touch `configuredAt`/`retentionAcknowledgedAt` (research D4/D8; depends on T014, T013).
- [X] T029 [US2] Add `PATCH /config` handler to `WorkspaceConfigController` → `updateSettings(...)` in `backend/src/main/java/com/cadence/api/WorkspaceConfigController.java` (depends on T028, T015).
- [X] T030 [US2] Create `workspace-settings.component.ts` (standalone) in `frontend/src/app/features/admin/workspace/workspace-settings.component.ts` with the operational-settings section (name/tz/hours/SLA/retention edit), `role="alert"` errors, `i18n="@@workspace.settings.*"`; add `/admin/workspace` route `[authGuard, roleGuard('ADMIN')]` in `frontend/src/app/app.routes.ts` and an Admin-only nav link (`hasRole('ADMIN')`) in `frontend/src/app/features/shell/shell.component.ts` (depends on T021, T020). **Note**: this component scaffold + route is intentionally shared infrastructure — US3/US4/US5/US6 *append* sections to it (T035/T040/T044) and depend on this task; the same-file edits are sequential, not parallel.

**Checkpoint**: US1 + US2 deliver the full P1 Admin config surface with server-side RBAC.

---

## Phase 5: User Story 3 — Candidate-facing branding (Priority: P2)

**Goal**: Admin sets logo + brand colour; a public read serves them (or defaults) to candidate pages.

**Independent test**: Set a PNG + colour → persists and returns via the public branding read; SVG/oversize/magic-mismatch → rejected; unset → default returned.

### Tests (write first, must fail)

- [X] T031 [P] [US3] Create `BrandingIntegrationTest` in `backend/src/test/java/com/cadence/workspace/BrandingIntegrationTest.java`. First provision the binary fixtures under `backend/src/test/resources/workspace/` (or generate in `@BeforeAll`): `valid.png`, `valid.jpg`, `svg-renamed.png` (SVG bytes, `.png` name), `oversize.png` (>1 MB), `corrupt-magic.png` (valid PNG header, truncated body → `ImageIO` null), `big-dims.png` (>2048²), `bomb.png` (small file, huge declared dimensions). Assertions: valid PNG/JPEG accepted; each bad fixture → 400 `invalid_logo`, nothing persisted (`hasLogo` unchanged, `workspaceLogo` empty) (US3 AS-2/SC-008, QA-2); colour regex (US3 AS-3); per-attribute default in set/unset/partial states, asserting the exact default colour `#1F2937` (US3 AS-4/SC-011); **unset logo → public read returns placeholder AND a `logo` audit row written** (US3 AS-6/QA-6); public `GET /branding` + `/logo` reachable without a session, with `X-Content-Type-Options: nosniff` + cache headers (SEC-2); **adversarially assert the public `/branding` body contains ONLY `brandColor` + `logoUrl`** — never `credentialSet`, `configured`, or any setting (C3/SEC-MAJOR-1). Mutating MockMvc calls carry the CSRF token (header `X-XSRF-TOKEN`); the multipart `POST /logo` token rides the header, not a form part (BE-2).

### Implementation

- [X] T032 [US3] Create `BrandingService` in `backend/src/main/java/com/cadence/service/BrandingService.java`: validate logo in the D6 order (size → magic-byte PNG/JPEG → **header-only dimensions via `ImageReader` before decode** → bounded `ImageIO` decode, null/IOException→invalid); store/replace/clear `WorkspaceLogo` + set `WorkspaceConfig.hasLogo`; `resolveBranding(workspaceId)` returning per-attribute defaults (colour `#1F2937`, placeholder logo) (research D5/D6; depends on T004, T008, T003).
- [X] T033 [US3] Add `PUT /branding`, `POST /logo` (multipart), `DELETE /logo` handlers to `WorkspaceConfigController` (audit `branding`/`logo` via `configChanged`) in `backend/src/main/java/com/cadence/api/WorkspaceConfigController.java` (depends on T032, T015).
- [X] T034 [US3] Create `PublicBrandingController` in `backend/src/main/java/com/cadence/api/PublicBrandingController.java`: `GET /api/public/workspace/branding` (JSON colour+logoUrl, resolved defaults) and `GET /api/public/workspace/logo` (verified `Content-Type`, `X-Content-Type-Options: nosniff`, `Content-Disposition: inline`, `Content-Security-Policy: default-src 'none'; sandbox`, `Cache-Control: public, max-age=300` + `ETag`; placeholder when `hasLogo=false`). No session (F02 `@Order(2)` permitAll prefix). Depends on T032.
- [X] T035 [US3] Add the branding section to `frontend/src/app/features/admin/workspace/workspace-settings.component.ts`: colour input + logo upload via `WorkspaceService.uploadLogo` (client-side type/size pre-check, `accept="image/png,image/jpeg"`, labelled file input, `role="alert"` error, optional Admin-side preview). The CSRF token must ride the `X-XSRF-TOKEN` **header** on the multipart POST (not a form part) — let the interceptor add it; do not set `Content-Type` manually (FE-4/BE-2; depends on T030, T021).

**Checkpoint**: Branding configurable and publicly consumable (candidate-page rendering remains F14/F30).

---

## Phase 6: User Story 4 — Email-sending domain & provider credential (Priority: P2)

**Goal**: Admin configures the sending domain + credential; credential is encrypted at rest, never returned, never logged.

**Independent test**: Set domain + credential → `credentialSet:true`, value never echoed; raw datastore shows ciphertext; logs contain no credential; non-Admin refused.

### Tests (write first, must fail)

- [X] T036 [P] [US4] Create `EmailConfigIntegrationTest` in `backend/src/test/java/com/cadence/workspace/EmailConfigIntegrationTest.java`: credential stored as **ciphertext at rest read via the RAW driver collection** (bypassing the converter, SC-007); credential **never** in any read response for **any** role AND not present when the `WorkspaceConfig` **entity itself** is serialized (SC-006/BE-3); rotation replaces ciphertext (old unrecoverable) **and writes an `email_config` audit row with no credential value**; unset clears → `credentialSet:false` (re-read via `GET /config` since DELETE returns 204) **and writes an `email_config` audit row** (US4 AS-6/AS-7/FR-024); domain ASCII-LDH validation (reject Unicode/control); **restart-persistence via a cold `MongoTemplate`** asserting the credential decrypts to the original AND branding is still returned (US4 AS-8/SC-004). Mutating calls carry the CSRF header.
- [X] T037 [P] [US4] Create `WorkspaceLogPiiScanTest` in `backend/src/test/java/com/cadence/workspace/WorkspaceLogPiiScanTest.java`: programmatically set the root logger to `TRACE`; drive set → **`GET /config` (read flow — the converter decrypts the field into the entity here)** → rotate → unset → a forced validation error routed through `WorkspaceExceptionHandler` (malformed domain + a sentinel credential `SG.SENTINEL_DO_NOT_LOG`) through MockMvc; assert the captured log surface (message + argument array + MDC + throwable) contains **neither** the sentinel value **nor** `(?i)api[_-]?key|secret|password` token values (SC-005/QA-3); restore level in `@AfterEach`.

### Implementation

- [X] T038 [US4] Add `setEmailConfig(workspaceId, actorMemberId, EmailConfigRequest)` and `unsetCredential(workspaceId, actorMemberId)` to `backend/src/main/java/com/cadence/service/WorkspaceConfigService.java`: validate domain (ASCII-LDH); set the (converter-encrypted) credential via targeted `$set`; audit `email_config` (never the value); never log the credential (research D2/D7/D8; depends on T014, T010, T013).
- [X] T039 [US4] Add `PUT /email` and `DELETE /email/credential` handlers to `WorkspaceConfigController` (responses carry `emailSendingDomain` + `credentialSet`, never the credential) in `backend/src/main/java/com/cadence/api/WorkspaceConfigController.java` (depends on T038, T015).
- [X] T040 [US4] Add the email section to `frontend/src/app/features/admin/workspace/workspace-settings.component.ts`: domain input + a write-only credential field (placeholder shows whether one is set via `credentialSet`, never the value), `i18n="@@workspace.email.*"` (depends on T030, T021).

**Checkpoint**: Email config captured securely for F22; secret-handling gates enforced and scanned.

---

## Phase 7: User Story 5 — Template governance / locking (Priority: P2)

**Goal**: Admin locks/unlocks templates by key; F21 enforces the rule later (forward contract).

**Independent test**: Set a template key locked → persists and reads back; only Admin can change it.

### Tests (write first, must fail)

- [X] T041 [P] [US5] Create `TemplateLockIntegrationTest` in `backend/src/test/java/com/cadence/workspace/TemplateLockIntegrationTest.java`: lock then unlock persists + appears in `GET /config` `templateLocks` (US5 AS-1/AS-2); empty/oversize key → 400; non-Admin → 403 (US5 AS-3). Note in the test header that the "locked ⇒ Recruiter cannot edit" rule (US5 AS-4) is a **F21 forward contract not exercised here**.

### Implementation

- [X] T042 [US5] Add `setTemplateLock(workspaceId, actorMemberId, key, locked)` to `backend/src/main/java/com/cadence/service/WorkspaceConfigService.java`: validate key (non-empty, bounded length); targeted `$set` of the `templateLocks.<key>` entry; audit `template_lock` (research D10; depends on T014, T013).
- [X] T043 [US5] Add `PUT /templates/{key}/lock` handler to `WorkspaceConfigController` in `backend/src/main/java/com/cadence/api/WorkspaceConfigController.java` (depends on T042, T015).
- [X] T044 [US5] Add the template-lock section to `frontend/src/app/features/admin/workspace/workspace-settings.component.ts` (list keys + lock toggle), `i18n="@@workspace.templates.*"` (depends on T030, T021).

**Checkpoint**: Governance state persisted; F21 binds to it when it lands.

---

## Phase 8: User Story 6 — Frontend authorization experience (Priority: P3)

**Goal**: Admins reach settings/wizard; non-Admins see no nav, are redirected to `/not-authorized`, and on an unconfigured workspace see a neutral "setup pending" state.

**Independent test**: As a non-Admin, settings nav hidden; direct navigation → `/not-authorized`; API still 403; on an unconfigured workspace, a neutral panel (not the wizard, not an error).

### Tests (write first, must fail)

- [X] T045 [P] [US6] Frontend unit tests: extend `frontend/src/app/core/auth/role.guard.spec.ts` to assert the Admin passes and **each** non-Admin role redirects to `/not-authorized` for **both** `/workspace/setup` and `/admin/workspace` (SC-012/FE-5); create `frontend/src/app/features/admin/workspace/workspace-settings.component.spec.ts` (renders for Admin); create/extend `frontend/src/app/features/shell/shell.component.spec.ts` asserting a non-Admin with `workspaceConfigured=false` renders the "setup pending" panel — not the wizard, not an error (US6 AS-5/FE-3).

### Implementation

- [X] T046 [US6] Finalize `frontend/src/app/features/shell/shell.component.ts`: non-Admin + `workspaceConfigured=false` → neutral "setup pending" panel (`i18n="@@workspace.setupPending.*"`); Admin nav link gated by `hasRole('ADMIN')`; ensure non-Admins are **never** routed to `/workspace/setup` (the neutral panel is the shell's job) (FE-3; depends on T030, T026).
- [X] T047 [P] [US6] Create `frontend/e2e/workspace-config.spec.ts` (Playwright): Admin completes the wizard; a non-Admin hits `/admin/workspace` → `/not-authorized` while the API independently 403s; the public branding endpoint renders an image (depends on prior phases).

**Checkpoint**: Full UX with defense-in-depth guards; server remains the boundary.

---

## Phase 9: Polish & Cross-Cutting Concerns

- [X] T048 [P] Create `WorkspaceRbacContractTest` in `backend/src/test/java/com/cadence/workspace/WorkspaceRbacContractTest.java`: loop the four non-Admin roles (Recruiter, HM, Interviewer, Read-only) × the five write surfaces (counting branding as one surface even though it spans `PUT /branding` + `POST /logo`): `POST /setup`, `PATCH /config`, branding, `PUT /email`, `PUT /templates/{key}/lock` — asserting **403 AND no state change** (re-read unchanged), plus `GET /config` read → 403; cover both configured and unconfigured workspace states (the unconfigured + non-Admin + direct-config-call → 403 arm also satisfies US6 AS-5's server-side half). Mutating calls carry the CSRF token so 403s are role-denials, not CSRF false-positives (BE-2). (SC-001/SC-002/QA-6/QA-9). Depends on all controllers (T024, T029, T033, T039, T043).
- [X] T049 [P] Create `WorkspaceAuditIntegrationTest` in `backend/src/test/java/com/cadence/workspace/WorkspaceAuditIntegrationTest.java`: every config change writes a non-PII audit row (SC-010); `WORKSPACE_CONFIGURED.newValue` is non-null = acknowledged days (SC-003); a `retention_period` change records old→new; a non-retention change (e.g. `sla_window`) has null old/new and an `email_config` row contains no credential value (QA-8); audit is **append-only** — no update/delete API exists (SC-013).
- [X] T050 Extend the PII log-scan step in `.github/workflows/ci.yml` with a **secret** pattern set (`(?i)(api[_-]?key|secret|password|credential)["'\s:=]+\S`) and a check for the sentinel credential token, failing CI if either appears in captured test logs (keep the existing email pattern + the anti-vacuous `@timestamp` guard) (SEC-BLOCKER-1). Pure-ASCII; no `.ps1` change.
- [X] T051 [P] Run `RbacEndpointInventoryTest` (existing F02) and confirm it stays green with the new `/api/internal/workspace/**` handlers (class-level `@PreAuthorize` satisfies it); record the result (no code change expected).
- [X] T052 [P] Walk `specs/004-workspace-config/quickstart.md` end-to-end (manual verification of US1–US6); if any `.ps1`/`.cmd`/`.bat` was touched, run the byte-level non-ASCII scan (C5) — none expected.
- [X] T053 Run the full suite — `./gradlew test --tests "com.cadence.workspace.*"` + `RbacEndpointInventoryTest`, `ng test --watch=false`, Playwright `workspace-config.spec.ts` — all green; then run the **final multi-role sub-agent review** (≥3 roles, C6) on the implementation and apply findings.

---

## Dependencies & Execution Order

- **Setup (Phase 1)** → **Foundational (Phase 2)** → user stories.
- **Foundational blocks everything**: T002–T021 must complete before any story. Within Foundational: T009 needs T003/T004; T010 needs T003; T013 needs T005/T006; T014 needs T003/T007/T011; T015/T016 need T014.
- **User stories after Foundational**:
  - US1 (T022–T026), US2 (T027–T030) are both P1. They share `WorkspaceConfigService` + `WorkspaceConfigController` + `workspace-settings`/shell files, so backend service/controller tasks are sequential by task-ID; their **tests** (T022, T027) are parallel.
  - US3 (T031–T035), US4 (T036–T040), US5 (T041–T044) are P2; each adds handlers to the shared controller/service/settings-component (sequential on those files) but their **test files are independent** and parallel.
  - US6 (T045–T047) is P3 and depends on US1/US2 routing + the settings component.
- **Polish (Phase 9)** after all stories: T048/T049 need all controllers; T050 pairs with T037's sentinel; T053 is last.

## Parallel Execution Examples

- **Foundational domain burst** (all different files): T002, T003, T004, T005, T006, T007, T008, T011, T012 in parallel; then T009/T010/T013/T014.
- **Frontend core**: T019, T020, T021 in parallel with the backend foundational tasks.
- **Story test authoring** (TDD, before implementation, different files): T022, T027, T031, T036, T037, T041 can all be written in parallel.
- **Polish tests**: T048, T049, T051, T052 in parallel.

## Implementation Strategy

- **MVP = Phase 1 + Phase 2 + US1 (T001–T026)** — a fresh workspace can be configured by an Admin with the GDPR gate enforced and persisted. Demoable on its own.
- **Increment 2 = US2** — full P1 Admin settings surface with server-side RBAC.
- **Increment 3 = US3 + US4 + US5** (P2) — branding (public read), secure email config, template governance.
- **Increment 4 = US6** (P3) — frontend authorization polish.
- **Always-on**: the existing `RbacEndpointInventoryTest` fails CI if any new internal endpoint lacks a role; T050 makes the SC-005 secret scan real.

## Format validation

All tasks use `- [ ] [TaskID] [P?] [Story?] description + file path`. Story labels [US1]–[US6] appear only in story phases; Setup/Foundational/Polish carry none. Total: **53 tasks** (Setup 1, Foundational 17, US1 5, US2 4, US3 5, US4 5, US5 4, US6 3, Polish 6).
