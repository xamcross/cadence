---
description: "Task list for F21 — Email Template Library"
---

# Tasks: Email Template Library (F21)

**Input**: Design documents from `/specs/010-email-template-library/`
**Prerequisites**: plan.md, spec.md, research.md (D1–D13), data-model.md, contracts/email-template-api.md, quickstart.md

**Tests**: INCLUDED and TDD-ordered — constitution §VII (Test-First & Acceptance-Driven) is mandatory and plan.md enumerates the test files. Each story's tests are written FIRST and MUST fail before its implementation.

**Organization**: By user story. US1 (view/edit the library) and US2 (preview/render) are P1; US3 (Admin locking) is P2; US4 (per-stage variants) is P3. **US1 is the MVP slice and the first §II demonstrable leg** (template editor browser→DB); **US2 adds the renderer + preview** (the second §II leg, and the feature's core safety value). US2 reads the persisted/built-in template US1 resolves; US3 and US4 extend the US1/US2 service+controller+frontend.

**Reuse posture**: F21 is new in-stack business logic with **zero new dependency/infra/scheduler** (research D-set). It consumes the **unchanged** F04 `Candidate` (decrypted, scoped read for preview merge values), the F12 `InterviewTemplateRepository.findByWorkspaceIdAndId` (variant stage-reference validation), the F02 RBAC + `RbacEndpointInventoryTest`, the F03 controller/exception-handler pattern, the `AuthAuditService.record(...)`, the injected `Clock`, and `HtmlUtils.htmlEscape` (already on the classpath via spring-web). One new collection `emailTemplates`; one new Mongock changeset `ChangeUnit009` (order "009", off the highest applied `008`, NOT the branch number).

## Format: `[ID] [P?] [Story?] Description with file path`

- **[P]**: parallelizable (different file, no dependency on an incomplete task)
- **[Story]**: US1/US2/US3/US4 (story phases only)
- Backend root: `backend/src/main/java/com/cadence/`; tests: `backend/src/test/java/com/cadence/emailtemplate/`; resources: `backend/src/main/resources/email-templates/`; frontend: `frontend/src/app/features/email-templates/`

## Run flags (CLAUDE.md — every backend test/build invocation)

`JAVA_HOME=C:/jdk-24.0.1`, cached `gradle-9.4.0` binary, `-Dapi.version=1.41`, `DOCKER_HOST=npipe:////./pipe/docker_engine`, `-Dorg.gradle.java.installations.auto-download=false` (Principle X — zero downloads). First multi-class Testcontainers run after a recompile may throw the one-time `GenericContainer` class-init error — re-run.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: configuration, classpath content resources, CI plumbing.

- [x] T001 In `backend/src/main/resources/application.yml` add an `email.template.*` block: `max-subject-length: 200`, `max-body-length: 10000`, `max-tokens-per-template: 50`, `max-variants-per-type: 20` (FR-020/D11). No secrets.
- [x] T002 [P] Create `EmailTemplateProperties` (`@ConfigurationProperties("email.template")`) in `backend/src/main/java/com/cadence/config/EmailTemplateProperties.java` binding the T001 keys; register via `@EnableConfigurationProperties` (or the existing `@ConfigurationPropertiesScan` — match the F03 `AuthProperties` precedent).
- [x] T003 [P] Create the classpath content resources under `backend/src/main/resources/email-templates/`: a non-empty default `subject`+`body` for each of the 8 `EmailMessageType`s (`builtin/`) and a `subject`+`body` for each `(type × TonePreset)` (`tone/`), EN, using only allow-listed `{{tokens}}` per type (data-model §3). Plain ASCII/UTF-8 text; no secrets.
- [x] T004 [P] Extend the CI PII/log scan in `.github/workflows/ci.yml` with an `emailTemplates` **content sentinel** (a known high-entropy string seeded into a template body in `EmailTemplateLogPiiScanTest`) and confirm the existing **candidate-PII sentinels** cover preview merge values; a regression that logs template content or merged PII fails CI (FR-019, SC-009).

**Checkpoint**: config binds; default/tone content present; CI gate declared.

---

## Phase 2: Foundational (Blocking Prerequisites)

**⚠️ CRITICAL**: every user story depends on this phase — the enums, persisted domain, repository, the Mongock changeset, the appended audit values, and the code-shipped catalogues.

- [x] T005 [P] Create the domain enums in `backend/src/main/java/com/cadence/domain/`: `EmailMessageType` (INVITATION/CONFIRMATION/REMINDER_24H/REMINDER_1H/HOLD_UPDATE/REJECTION/FEEDBACK_REQUEST/SLA_HOLDING), `TonePreset` (FORMAL/FRIENDLY/CONCISE), and `MergeToken` (the 13-token catalogue per data-model §3, each flagged `urlTyped` true/false).
- [x] T006 Create `EmailTemplate` (`@Document("emailTemplates")`) in `backend/src/main/java/com/cadence/domain/EmailTemplate.java` — fields per data-model §1 (`workspaceId`, `messageType`, `stageKey` NON-NULL — the literal `"BASE"` is the **base sentinel**, any other value is an F12 interview-template id, D2; `subject`, `body`, `locked`, `@Version Long version`, `createdByMemberId`/`updatedByMemberId`, `createdAt`/`updatedAt`); `toString()` MUST omit `subject` and `body` (authoring content). Ids + content only — no candidate PII/secret, no encryption converter (data-model §1). Depends on T005.
- [x] T007 [P] Create the transient `RenderedMessage` type in `backend/src/main/java/com/cadence/domain/RenderedMessage.java` — `{ String subject; String bodyText; String bodyHtml; List<String> missingFields }` (data-model §4).
- [x] T008 [P] Append four values to `AuthEventType` in `backend/src/main/java/com/cadence/domain/AuthEventType.java` — `EMAIL_TEMPLATE_EDITED`, `EMAIL_TEMPLATE_LOCKED`, `EMAIL_TEMPLATE_UNLOCKED`, `EMAIL_TEMPLATE_RESET` (append-only after the F12 block — never reorder; D9).
- [x] T009 [P] Create `EmailTemplateRepository extends MongoRepository<EmailTemplate,String>` in `backend/src/main/java/com/cadence/repository/EmailTemplateRepository.java` with `findByWorkspaceIdAndMessageTypeAndStageKey(...)`, `findByWorkspaceIdAndStageKey(...)` (library list), `findByWorkspaceIdAndMessageType(...)` (a type's variants), and `countByWorkspaceIdAndMessageType(...)` (variant cap). All workspace-scoped (FR-009).
- [x] T010 Create `ChangeUnit009_EmailTemplateIndexes` (`@ChangeUnit(id="009-email-template-indexes", order="009", author="system")`) in `backend/src/main/java/com/cadence/config/migration/ChangeUnit009_EmailTemplateIndexes.java` creating, via native `createIndex` + `IndexOptions().unique(true)`: `emailTemplates {workspaceId, messageType, stageKey}` (UNIQUE — `stageKey` non-null discriminates base vs variant, D2); `@RollbackExecution` does a targeted `dropIndex` of that key (never `dropIndexes()`). Order "009" derives off the highest APPLIED changeset `008`, NOT the branch number (D2, CLAUDE.md lesson).
- [x] T011 Add an index-bootstrap assertion (extend the existing index test, e.g. `IndexBootstrapTest`, or a sibling under `backend/src/test/java/com/cadence/emailtemplate/`) verifying `listIndexes` shows the ChangeUnit009 unique index after startup. Clean via `mongoTemplate.remove(...)`, never `dropCollection` (CLAUDE.md F00.1).
- [x] T012 [P] Create `MergeTokenCatalogue` in `backend/src/main/java/com/cadence/service/MergeTokenCatalogue.java` — the per-`EmailMessageType` permitted-token subset (data-model §3), the URL-typed set, the token lexis `\{\{[a-z_]+\}\}`, the "looks-like-a-token" scan `\{\{[^{}]*\}\}`, and a `validate(messageType, subject, body)` returning value-free violations (D4 truth table). No PII; pure.
- [x] T013 Create `BuiltInEmailTemplates` and `TonePresetCatalogue` in `backend/src/main/java/com/cadence/service/` — load the T003 resources into `Map<EmailMessageType,…>` / `Map<(type,TonePreset),…>`; a `@PostConstruct` **completeness check** fails fast if any of the 8 defaults or any `(type × TonePreset)` preset is missing/empty (SC-001, D1/D10). Depends on T003, T005.
- [x] T014 [P] `BuiltInTemplateCompletenessTest` in `backend/src/test/java/com/cadence/emailtemplate/BuiltInTemplateCompletenessTest.java` — SC-001: asserts a non-empty subject+body resolves for every `EmailMessageType` and every `(type × TonePreset)`, and that every default/preset body uses only tokens the catalogue permits for that type.

**Checkpoint**: enums + domain + repo + migration + audit values + catalogues exist; stories can begin.

---

## Phase 3: User Story 1 - View and edit the workspace template library (Priority: P1) 🎯 MVP

**Goal**: Admin/Recruiter can list the 8 message types (built-in default until overridden), view one, edit subject+body (override), apply a tone preset, and reset to default — validated, versioned, audited, browser→DB.

**Independent Test**: list templates → all 8 present with non-empty built-in defaults; edit the invitation → persists as override (version++), built-in untouched; apply a tone preset → wording replaced, still editable; reset → falls back to built-in; an unknown/disallowed/malformed token or empty/over-cap field → value-free 400, nothing persisted; Interviewer/Read-only edit → 403.

### Tests for User Story 1 (write FIRST, must FAIL)

- [x] T015 [P] [US1] `EmailTemplateValidationTest` (unit) in `backend/src/test/java/com/cadence/emailtemplate/EmailTemplateValidationTest.java` — SC-004: the D4 **malformed-token truth table** (`{{candidate_name}}` permitted→accept; permitted-elsewhere-but-not-this-type→reject; `{{not_a_token}}`→reject; `{{}}`→reject; `{{ candidate_name }}` padded→reject; `{{name}` single brace→accept-as-literal; stray `{{`→literal); empty subject; empty body; over `max-subject-length`/`max-body-length`/`max-tokens-per-template` — each → **value-free** 400, 0 persisted.
- [x] T016 [P] [US1] `EmailTemplateCrudIntegrationTest` (Testcontainers) in `.../emailtemplate/EmailTemplateCrudIntegrationTest.java` — SC-001/006: list returns all 8 built-in defaults (`source=BUILTIN`); an **un-overridden** type renders the live in-code constant with **zero persisted rows** (raw-driver count == 0 — no seeding regression); edit → read-back + built-in default untouched + `version++`; apply-tone replaces wording; reset deletes the override → falls back to built-in; reset of an un-overridden type is an idempotent no-op (no version bump); a **concurrent first-edit** (two creates of the same `{workspaceId,messageType,stageKey}` racing the unique index) → exactly one persists, the other → 409 (the `DuplicateKeyException` leg of the dual-catch — proven here in the MVP slice, not deferred to US3); raw-driver doc carries no candidate PII.
- [x] T017 [US1] `EmailTemplateAuditTest` (Testcontainers) in `.../emailtemplate/EmailTemplateAuditTest.java` — SC-008: each change-kind writes **exactly one** append-only audit row tagged with its kind in `outcome="<messageType>/<stageKey>/<kind>"`, ids only — no subject/body/PII: **override-create** (`EMAIL_TEMPLATE_EDITED`, kind `create_override`), **edit** (kind `edit`), **tone-apply** (kind `tone_apply`), and **reset** (`EMAIL_TEMPLATE_RESET`). Assert the first-edit-of-an-un-overridden-type emits the `create_override` kind distinctly from a subsequent `edit`. *(US3 extends this file for lock/unlock — T034; US4 for variant-edit — T038.)*
- [x] T018 [US1] `EmailTemplateContractTest` (MockMvc) in `.../emailtemplate/EmailTemplateContractTest.java` — SC-005/007 (US1 slice): list / get / edit / apply-tone / reset × 5 roles per contract §D (ADMIN/RECRUITER 200, HM/INTERVIEWER/READ_ONLY 403); error envelopes (`invalid_template` 400 with value-free `fields`; `stale_template` 409 on a mismatched `expectedVersion`); `RbacEndpointInventoryTest` stays green. *(US2 extends for preview — T028; US3 for lock cells — T033.)*

### Implementation for User Story 1

- [x] T019 [P] [US1] `EmailTemplateExceptions` in `backend/src/main/java/com/cadence/api/EmailTemplateExceptions.java` — `InvalidTemplateException` (→400 `invalid_template`, value-free per-field `fields`), `TemplateLockedException` (→403 `template_locked`, used in US3), `StaleTemplateException` (→409 `stale_template`); reuse the F02 `ScopedNotFoundException` for 404.
- [x] T020 [P] [US1] `EmailTemplateDtos` in `backend/src/main/java/com/cadence/api/EmailTemplateDtos.java` — `EditRequest{stageKey,subject,body,expectedVersion}`, `ApplyToneRequest{stageKey,tone,expectedVersion}`, `ResetRequest{stageKey,expectedVersion}`, `TemplateResponse` (per contract §C — `source` BUILTIN/OVERRIDE, `permittedTokens`, no member email/name). *(US2 adds `PreviewRequest`/`RenderedMessageResponse` — T026; US3 adds `LockRequest` — T031.)*
- [x] T021 [US1] `EmailTemplateExceptionHandler` (`@RestControllerAdvice(assignableTypes=EmailTemplateController.class)`) in `backend/src/main/java/com/cadence/api/EmailTemplateExceptionHandler.java` — map InvalidTemplate→400, TemplateLocked→403, StaleTemplate→409 to the F02/F03/F12 envelope; `ScopedNotFoundException` is left to the global `RbacExceptionHandler`→404 (no advice here). Depends on T019.
- [x] T022 [US1] `EmailTemplateService` in `backend/src/main/java/com/cadence/service/EmailTemplateService.java` — `resolve(messageType, stageKey)` (override `findBy…` → fall back to built-in default; variant layer added in US4), `list`, `get`, `edit`, `applyTone`, `reset`; validation via `MergeTokenCatalogue` (T012) + bounds (T002); **all writes via `MongoRepository.save(...)`** (load→mutate→save) so `@Version` engages — dual-catch `OptimisticLockingFailureException`/`DuplicateKeyException`→`StaleTemplateException` (D8); audit edit/tone/reset (ids only, `.name()` Strings — never enum→`kv`, D9/D12); value-free messages/diagnostics. Depends on T006, T009, T012, T013, T002.
- [x] T023 [US1] `EmailTemplateController` in `backend/src/main/java/com/cadence/api/EmailTemplateController.java` — `@RestController @RequestMapping("/api/internal/email-templates")` with **class-level** `@PreAuthorize("hasAnyRole('ADMIN','RECRUITER')")` (D6); handlers `GET` (list, `?stageKey=`), `GET /{messageType}`, `PUT /{messageType}` (edit), `POST /{messageType}/apply-tone`, `POST /{messageType}/reset`; `@AuthenticationPrincipal SessionService.Principal` for workspace/actor. Depends on T020, T021, T022.
- [x] T024 [US1] Run the US1 backend tests (T015–T018) with the run flags and confirm green.
- [x] T025 [P] [US1] Frontend US1: `email-templates.service.ts` (typed HTTP client, relative `apiBaseUrl`), `email-templates.component.ts` (standalone, Angular Material, `$localize` — list / edit subject+body / apply tone / reset, client-side bounds mirroring the server), and the ADMIN/RECRUITER-guarded `/email-templates` route in `frontend/src/app/app.routes.ts` (reuse the F02 role guard). Jasmine `email-templates.component.spec.ts` covers the missing-field-warning render and edit form. *(US2 adds preview, US3 adds the lock affordance.)*

**Checkpoint**: US1 fully functional and demonstrable end-to-end (library view/edit/tone/reset, RBAC-gated, validated, versioned, audited).

---

## Phase 4: User Story 2 - Preview a rendered template with merge fields (Priority: P1)

**Goal**: render a template (resolved base/built-in) with merge values (sample data or a workspace-scoped candidate) into a safe `RenderedMessage`; expose it as a Recruiter/Admin preview. The feature's core safety value.

**Independent Test**: render with complete sample values → zero residual `{{token}}`; a missing/empty value → `[[missing:<token>]]` marker + `missingFields` entry (in the body, not just the array); a `<script>` value → inert in `bodyHtml`; a `\r\n`/`U+2028` subject value → stripped; a URL token → `href==text` anchor, a `javascript:` value → `[[invalid_url:...]]`; identical input → byte-identical output; preview with a foreign-workspace candidate → 404; rendered PII never logged.

### Tests for User Story 2 (write FIRST, must FAIL)

- [x] T026 [P] [US2] `MergeRendererTest` (unit) in `backend/src/test/java/com/cadence/emailtemplate/MergeRendererTest.java` — SC-002/003/006: substitute **all** occurrences incl. a **repeated token** (same token twice in one body); absent **and** empty → `[[missing:<token>]]` + `missingFields` (first-occurrence order); byte-identical determinism; body `htmlEscape(...,"UTF-8")` makes a `<script>` payload inert and preserves a non-Latin name; subject strips CR/LF **and** `U+2028`/`U+2029`/`U+0085`/control chars (not HTML-escaped); a URL token renders `href==text` anchor and a `javascript:`-scheme value → `[[invalid_url:<token>]]`; **hostile recruiter-authored markup surrounding a URL token** (e.g. body `... {{scheduling_link}} <a href="http://evil">`) is escaped to inert text (FR-016 spoof).
- [x] T027 [P] [US2] `EmailTemplateNoTransportTest` in `.../emailtemplate/EmailTemplateNoTransportTest.java` — SC-010: a structural assertion that no `EmailSender`/SMTP/mail type is a field or constructor dependency of `MergeRenderer` or `EmailTemplateService` (reflection/ArchUnit-style), and that no `EmailSender` bean exists yet — render is side-effect-free, dispatch is F22. *(FR-020 note: F21's only "approval/lock metadata" surface in the MVP slice is the `locked` flag on `EmailTemplate`; there is no `approval` field — the approval-basis / one-click-approve workflow is wholly F22/F31 and is intentionally NOT built here. Captured in the CLAUDE.md notes, T044.)*
- [x] T028 [US2] Extend `EmailTemplateContractTest` (the T018 file) — SC-007 preview: `POST /{messageType}/preview` returns the `RenderedMessageResponse` shape (`subject`/`bodyText`/`bodyHtml`/`missingFields`) including the `[[missing:<token>]]` literal **in the body** (not only `missingFields`); `Cache-Control: no-store`; preview is in the role matrix (ADMIN/RECRUITER 200, others 403); a foreign-workspace `candidateId` → 404 (oracle-free).
- [x] T029 [P] [US2] `EmailTemplateLogPiiScanTest` in `.../emailtemplate/EmailTemplateLogPiiScanTest.java` — SC-009: TRACE scoped to `com.cadence`; drive a **content sentinel** through edit→read-back→preview (non-vacuous, success path) AND a **candidate-PII sentinel** through a **failing** render/preview path (forced exception and/or foreign-candidate 404); assert neither the content sentinel nor the merged PII appears in logs incl. error paths; positive vacuity guard.

### Implementation for User Story 2

- [x] T030 [US2] `MergeRenderer` service in `backend/src/main/java/com/cadence/service/MergeRenderer.java` (D3/D4/D12): `render(EmailTemplate effective, Map<String,String> values)` → `RenderedMessage`. Subject = substitute → strip `[ --  ]`. Body HTML = `htmlEscape(body,"UTF-8")` → substitute (plain values escaped; URL-typed values `http(s)`-scheme-checked → `href==text` anchor else `[[invalid_url:<token>]]`) → `\r\n`/`\r`/`\n` normalised then `\n`→`<br>`. Absent/empty → `[[missing:<token>]]` + `missingFields` (first-occurrence order). Deterministic, side-effect-free, value-free on error (no value/PII in any message/log). Depends on T007, T012.
- [x] T031 [US2] Add preview to `EmailTemplateService` + `EmailTemplateController` (the T022/T023 files) and `PreviewRequest`/`RenderedMessageResponse` to `EmailTemplateDtos` (T020): the service builds merge values from `sampleValues` OR a candidate resolved via `CandidateRepository.findByWorkspaceIdAndId(workspaceId, candidateId)` (empty → `ScopedNotFoundException`→404; decrypt name/email on read, never logged), calls `MergeRenderer`, and the controller `POST /{messageType}/preview` returns 200 `Cache-Control: no-store`. Depends on T030.
- [x] T032 [US2] Run the US2 backend tests (T026–T029) with the run flags and confirm green; then frontend: `template-preview.component.ts` (standalone — sample/candidate merge → `POST preview` → render subject/`bodyHtml` + a missing-field warning panel), extend `email-templates.component.spec.ts` Jasmine for the preview-with-sample-data + missing-field-warning states, and `frontend/e2e/email-templates.spec.ts` Playwright (edit → preview → rendered output). Depends on T031, T025.

**Checkpoint**: US2 functional — the renderer + preview work end-to-end; US1 still green.

---

## Phase 5: User Story 3 - Admin template locking (Priority: P2)

**Goal**: an Admin can lock/unlock a template; a Recruiter cannot edit/tone/reset a locked template (403) but can still view/preview it; an Admin can edit/unlock it. Lock/unlock is Admin-only and audited.

**Independent Test**: Admin locks the rejection template → a Recruiter edit/apply-tone/reset → 403 `template_locked`, no state change, but view/preview still 200; Admin edits + unlocks → 200; a Recruiter lock/unlock → 403 `forbidden`; lock/unlock writes the matching audit row; a concurrent same-`expectedVersion` write → exactly one wins, the other → 409.

### Tests for User Story 3 (write FIRST, must FAIL)

- [x] T033 [US3] `EmailTemplateLockingTest` (Testcontainers + MockMvc) in `backend/src/test/java/com/cadence/emailtemplate/EmailTemplateLockingTest.java` — SC-005: lock blocks Recruiter edit/tone/reset (403 `template_locked`, 0 state change) but NOT view/preview; Admin edits + unlocks a locked template; lock/unlock is Admin-only (Recruiter → 403 `forbidden`); a concurrent edit at the same `expectedVersion` via `save()` → exactly one succeeds, the other → 409 (`OptimisticLockingFailureException` mapped). Also extend `EmailTemplateContractTest` (T018) so the contract §D matrix is complete: against a **locked** template assert **all five roles** (ADMIN edit/tone/reset → 200; RECRUITER edit/tone/reset → 403 `template_locked` while view/preview stay 200; HM/INTERVIEWER/READ_ONLY → 403 `forbidden`) and the lock/unlock row (ADMIN 200, RECRUITER + all lower roles → 403 `forbidden`).
- [x] T034 [US3] Extend `EmailTemplateAuditTest` (the T017 file) — SC-008: lock and unlock each write exactly one `EMAIL_TEMPLATE_LOCKED`/`EMAIL_TEMPLATE_UNLOCKED` audit row (ids + type/stage only).

### Implementation for User Story 3

- [x] T035 [US3] Add `lock`/`unlock` to `EmailTemplateService` (the T022 file) — materialise the override from the built-in default if absent, flip `locked`, `version++`, audit; and add the **locked-edit guard** to `edit`/`applyTone`/`reset`: if the target exists, is `locked`, and the actor is not ADMIN → `TemplateLockedException` (the actor role is passed from the controller). `LockRequest{stageKey,expectedVersion}` added to `EmailTemplateDtos` (T020). Depends on T022.
- [x] T036 [US3] Add `POST /{messageType}/lock` and `POST /{messageType}/unlock` to `EmailTemplateController` (the T023 file) with **method-level** `@PreAuthorize("hasRole('ADMIN')")` (most-specific wins over the class gate, D6); pass the principal role into the service edit/tone/reset calls so the locked-edit guard can distinguish ADMIN. Depends on T035.
- [x] T037 [US3] Run the US3 backend tests (T033–T034) green; then frontend (the T025 component): an Admin-only lock/unlock affordance and a read-only/disabled editor when `locked` (the SC-011 "locked disables edit" Jasmine case). Depends on T036.

**Checkpoint**: US3 functional — locking governs Recruiters, not Admins; US1/US2 still green.

---

## Phase 6: User Story 4 - Per-stage template variants (Priority: P3)

**Goal**: an optional per-stage variant of a message type (keyed to an F12 interview-template id) overrides the base for that stage; every other stage falls back to the base/built-in.

**Independent Test**: create a confirmation variant for stage X → render/preview for X uses the variant, for another stage uses the base; reset the variant → falls back to base; a variant referencing a non-existent/foreign-workspace stage → 404 (oracle-free); a variant of a locked base by a Recruiter → 403.

### Tests for User Story 4 (write FIRST, must FAIL)

- [x] T038 [US4] Extend `EmailTemplateCrudIntegrationTest` (the T016 file) — SC-006: a variant (`stageKey=<F12 id>`) is used when rendering for that stage and the base is used for other stages (fall-back); reset of a variant deletes only it → falls back to base; a variant `stageKey` not in the workspace → `ScopedNotFoundException`→404 (oracle-free); the reserved literal `"BASE"` is rejected on the variant-create path; the `max-variants-per-type` cap is enforced. **SC-008 completion**: assert a variant add/change writes exactly one `EMAIL_TEMPLATE_EDITED` audit row tagged `outcome="<messageType>/<stageId>/variant_edit"` (ids only) — the seventh change-kind.

### Implementation for User Story 4

- [x] T039 [US4] Extend `EmailTemplateService` (the T022 file) variant handling: for `stageKey != "BASE"`, validate the stage via `InterviewTemplateRepository.findByWorkspaceIdAndId(workspaceId, stageKey)` (empty → `ScopedNotFoundException`), reject a client-supplied literal `"BASE"` on the variant path (reserved-word guard, D2), enforce `max-variants-per-type`, and extend `resolve(...)` to **variant override → base override → built-in default** (data-model §1). The controller endpoints already accept `stageKey` (no new endpoint). Depends on T022, F12 `InterviewTemplateRepository`.
- [x] T040 [US4] Run T038 green; then frontend (the T025 component): per-stage variant management (pick an F12 stage, edit/reset its variant) + a Jasmine case for variant edit. Depends on T039.

**Checkpoint**: all four user stories independently functional.

---

## Phase 7: Polish & Cross-Cutting Concerns

- [x] T041 Run the **full backend suite** (run flags) — regression gate: F01/F02/F03/F04/F10/F11/F12, `RbacEndpointInventoryTest`, and the index-bootstrap test all stay green alongside the new `com.cadence.emailtemplate.*` tests.
- [x] T042 [P] Run `ng test --watch=false` and `ng build` from `frontend/` — confirm Jasmine green (incl. the SC-011 trio: missing-field warning, locked disables edit, preview with sample data) and a clean production build.
- [x] T043 Confirm no new `.ps1`/`.cmd`/`.bat` were added (Principle V — none expected; if any, byte-scan for non-ASCII = 0 matches and record the parse result); run the `quickstart.md` manual + verification steps.
- [x] T044 Append an **Implementation Notes (010-email-template-library)** section to `CLAUDE.md` capturing the load-bearing F21 lessons (built-in-default-by-reference + zero-rows for un-overridden; `stageKey="BASE"` non-null discriminator avoids the F01 partial-index footgun; the two distinct render transforms — subject control-strip incl. `U+2028` vs body `htmlEscape(UTF-8)`; URL-token `http(s)` scheme guard + `href==text`; `@Version` only via `save()` + dual-catch→409; the `[[missing:]]`/`[[invalid_url:]]` markers; ChangeUnit009 order off applied `008`).
- [x] T045 **Mandatory multi-role sub-agent implementation review (C6, ≥3 roles)** of the delivered diff (Backend/DevOps, Security/GDPR, QA) including an actual compile/test run (Principle V/VI); apply or explicitly report every finding before task closure.

---

## Dependencies & Execution Order

### Phase dependencies

- **Setup (P1: T001–T004)**: no dependencies — start immediately.
- **Foundational (P2: T005–T014)**: depends on Setup — **BLOCKS all user stories**.
- **US1 (P3: T015–T025)**: depends on Foundational. The MVP + first §II leg.
- **US2 (P4: T026–T032)**: depends on Foundational AND US1's service/controller/DTOs/frontend (preview extends them; renderer is new). Second §II leg.
- **US3 (P5: T033–T037)**: depends on US1 (edit/tone/reset paths it gates) and US2 (preview must remain allowed when locked).
- **US4 (P6: T038–T040)**: depends on US1 (the edit/resolve path) and the F12 `InterviewTemplateRepository`; a thin P3 refinement.
- **Polish (P7: T041–T045)**: depends on all desired stories.

### Within each story

- Tests (TDD) written first and MUST fail before implementation.
- Enums/domain → catalogues → service → controller → frontend → run-green.
- US1's `EmailTemplateService`/`EmailTemplateController`/`EmailTemplateDtos`/`EmailTemplateContractTest`/`EmailTemplateAuditTest`/frontend component are **extended** by US2 (preview), US3 (lock), US4 (variants) — those are sequential same-file tasks (no [P]).

### Parallel opportunities

- Setup: T002, T003, T004 in parallel after T001.
- Foundational: T005 first (enums); then T007, T008, T009 in parallel; T006 after T005; T010 after T006; T011 after T010; T012, T013 after T005 (T013 also after T003); T014 after T013.
- US1 tests: T015, T016 in parallel (T017, T018 touch files extended later — sequential). Impl: T019, T020 in parallel → T021 → T022 → T023; frontend T025 after T023.
- US2 tests: T026, T027, T029 in parallel (T028 extends the contract file — sequential). Impl T030 → T031 → T032.

---

## Parallel Example: User Story 2 tests

```bash
# Launch the independent US2 test files together (different files):
Task: "MergeRendererTest in backend/src/test/java/com/cadence/emailtemplate/MergeRendererTest.java"
Task: "EmailTemplateNoTransportTest in .../emailtemplate/EmailTemplateNoTransportTest.java"
Task: "EmailTemplateLogPiiScanTest in .../emailtemplate/EmailTemplateLogPiiScanTest.java"
```

---

## Implementation Strategy

### MVP first (US1 only)

1. Setup (Phase 1) → Foundational (Phase 2).
2. US1 (Phase 3) → **STOP and validate**: library view/edit/tone/reset works browser→DB, RBAC-gated, validated, versioned, audited. Demo-able.

### Incremental delivery

1. Setup + Foundational → foundation ready.
2. US1 → library management (MVP, first §II leg, demo).
3. US2 → the renderer + preview (the core safety value; the second §II leg).
4. US3 → Admin locking (P2 governance).
5. US4 → per-stage variants (P3 refinement).
6. Polish → full regression + review + docs.

### Notes

- [P] = different files, no incomplete-task dependency.
- Verify each story's tests fail before implementing.
- Commit after each task or logical group; never push to `main` directly (PR per the workflow).
- Test isolation: clean `emailTemplates`/`candidates`/`interviewTemplates`/`authAuditLog` in `@BeforeEach` with `mongoTemplate.remove(...)`, never `dropCollection` (CLAUDE.md F00.1); insert preview-candidate fixtures via the F04 production path so name/email are converter-encrypted and the decrypt path is exercised; use the injected `Clock`/`MutableClock` for any time-relative assertion.
- All template writes go through `MongoRepository.save(...)` so `@Version` engages — never `updateFirst`/`upsert` (D8).
