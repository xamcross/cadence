# Tasks: Join / Express-Interest Request Form (F70)

**Input**: Design documents from `/specs/029-join-interest-form/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/interest-api.md, quickstart.md

**Tests**: INCLUDED — Constitution Principle VII (Test-First) is mandatory for this project; the spec/quickstart enumerate required tests. Tests are written before implementation within each phase and MUST fail first.

**Organization**: Tasks are grouped by user story (US1 P1 → US2 P2 → US3 P3) for independent implementation and testing.

## ⚠️ Gate before any implementation (C1 governance)

This feature is outside Constitution §11 MVP scope. The plan records C1 as **FLAGGED — proceeding under the F60/F61 precedent (supporting capability), pending owner ratification**. **No implementation task (T001+) may start until the owner records the C1 decision** (proceed / defer / amend). Running `/speckit.tasks` is taken as electing "proceed under F60/F61 precedent"; confirm explicitly before coding.

## Path Conventions

Web app: `backend/src/main/java/com/cadence/...`, `backend/src/test/java/com/cadence/...`, `frontend/src/app/...`. Absolute paths from repo root `C:\Users\xamcr\Cadence`.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Feature configuration knobs.

- [X] T001 Create `InterestProperties` (`@ConfigurationProperties("cadence.interest")`) with `defaultWorkspaceId` (default `cadence`), `retentionFallbackDays` (180), `maxPerIpPerWindow` (5), `ipWindow` (PT10M for the dedicated limiter — matches quickstart), `maxPerWorkspacePerWindow` (100), `workspaceWindow` (PT1H), `minFillMillis` (1500) in `backend/src/main/java/com/cadence/service/InterestProperties.java`; add the `cadence.interest.*` block to `backend/src/main/resources/application.yml` and tighter overrides to `application-test.yml`.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Core entity, persistence, encryption, indexes, and the shared no-oracle + rate-limit infrastructure that ALL stories depend on.

**⚠️ CRITICAL**: No user story work begins until this phase is complete.

- [X] T002 [P] Create `InterestRequestStatus` enum (`NEW`, `REVIEWED`, `INVITED`, `DISMISSED`) in `backend/src/main/java/com/cadence/domain/InterestRequestStatus.java`.
- [X] T003 Create `InterestRequest` `@Document("interestRequests")` POJO (depends on T002 — references `InterestRequestStatus`; not `[P]`) (fields per data-model.md: `workspaceId`, encrypted `name`/`email`/`organization`/`message`, `emailHash`, `openEmailHash`, `status`, `submittedAt`, `updatedAt`, nullable `lastActorMemberId`/`actionedAt`/`invitationId`; `@Field(write=NON_NULL)` on `emailHash`/`openEmailHash`/`organization`/`message`/the nullable id fields; PII-free `toString()`) in `backend/src/main/java/com/cadence/domain/InterestRequest.java`.
- [X] T004 [P] Create `InterestRequestRepository extends MongoRepository<InterestRequest,String>` with `findByWorkspaceIdAndId`, `findByWorkspaceIdAndEmailHash`, `findByWorkspaceIdAndStatusInOrderBySubmittedAtDesc`, `countByWorkspaceIdAndSubmittedAtAfter`, plus the open-request finder for coalesce, in `backend/src/main/java/com/cadence/repository/InterestRequestRepository.java`.
- [X] T005 Register `InterestRequest` properties `name`,`email`,`organization`,`message` → `PiiStringConverter` in `backend/src/main/java/com/cadence/config/MongoPiiConfig.java` (do NOT register `emailHash`/`openEmailHash` — keyed HMAC stored as-is).
- [X] T006 [P] Write index-bootstrap test (Testcontainers) asserting the 4 `ChangeUnit023` indexes exist with the right keys/partial filter/uniqueness, in `backend/src/test/java/com/cadence/interest/InterestIndexTest.java` (MUST fail before T007).
- [X] T007 Create `ChangeUnit023_InterestRequestIndexes` (order `"023"`, **pure ASCII**) creating: unique-partial `{workspaceId,openEmailHash}` over `{openEmailHash:{$exists:true}}`; `{workspaceId,status,submittedAt:-1}`; `{workspaceId,submittedAt}`; non-unique `{workspaceId,emailHash}` — native `createIndex` + targeted `dropIndex` rollback, in `backend/src/main/java/com/cadence/config/migration/ChangeUnit023_InterestRequestIndexes.java`.
- [X] T008 [P] Create `InterestRateLimiter` (dedicated; configurable window/cap from `InterestProperties`; keyed on `TokenHasher.hashIp`) and a real-client-IP resolver helper: prefer `CF-Connecting-IP` **only when behind the known trusted proxy** (else the validated leftmost `X-Forwarded-For`), falling back to `getRemoteAddr()`; absent a trusted-proxy determination, document/treat layer-1 keying as **best-effort, not security-relied-upon** (the durable guard is the per-workspace DB ceiling). In `backend/src/main/java/com/cadence/service/InterestRateLimiter.java`.

**Checkpoint**: entity persists with encryption, indexes bootstrap, rate-limit + IP resolution ready.

---

## Phase 3: User Story 1 - Prospective user expresses interest (Priority: P1) 🎯 MVP

**Goal**: A public, no-login form captures an interest submission (name/email + optional org/message), stores it encrypted, coalesces duplicates, and returns a neutral no-oracle confirmation.

**Independent Test**: Submit valid details at `/request-access` as an anonymous visitor → confirmation shown and an encrypted row persists; a second submission with the same email leaves exactly one open row; member/unknown emails yield identical responses.

### Tests for User Story 1 ⚠️ (write first, must fail)

- [X] T009 [P] [US1] Contract test `PublicInterestController` (MockMvc): 202 `{"status":"received"}` **byte-identical body+status+headers** across {active-member email, pending-invitation email, existing open request, unknown email} (the member/invite cases are indistinguishable by construction — the submit path does no such lookup); 400 `invalid_request` (missing/format/length); 429 `rate_limited`; honeypot-tripped → same 202, no row. Add a **structural** assertion (spy/ArgumentCaptor — the F12 multiplicity precedent) that the dedup insert-attempt runs on every branch and `notify` is invoked only on a genuine new insert, so "same code path" is regression-guarded not just byte-checked. In `backend/src/test/java/com/cadence/interest/PublicInterestContractTest.java`.
- [X] T010 [P] [US1] Integration test (Testcontainers): submit → row persisted; `name`/`email`/`organization`/`message` **encrypted at rest** (raw-driver ciphertext assert); `emailHash`/`openEmailHash` stored as-is; `workspaceId` resolved from config (never request); in `backend/src/test/java/com/cadence/interest/InterestSubmitIT.java`.
- [X] T011 [P] [US1] Integration test: dedup coalesce — **gated concurrent** double-submit of the same email → exactly one open row (DuplicateKeyException → update); resubmit after a terminal row → a fresh NEW row; in `backend/src/test/java/com/cadence/interest/InterestDedupeIT.java`.
- [X] T012 [P] [US1] Integration test (SC-006): per-source limiter blocks a single-source flood; per-workspace **DB-count ceiling** blocks a flood of rotated-IP-hash submissions while a normal single submit succeeds; in `backend/src/test/java/com/cadence/interest/InterestRateLimitIT.java`.
- [X] T013 [P] [US1] Unit test: honeypot non-empty + sub-`minFillMillis` fill → neutral accept with no row; real-client-IP resolution (`CF-Connecting-IP` preferred, validated `X-Forwarded-For` fallback) INCLUDING a **spoofed-header case** asserting layer-1 keying is best-effort/not security-relied-upon when no trusted proxy is established; in `backend/src/test/java/com/cadence/interest/InterestBotHeuristicTest.java`.

### Implementation for User Story 1

- [X] T014 [US1] Implement `InterestRequestService.submit(SubmitCommand, ipHeaders)` in `backend/src/main/java/com/cadence/service/InterestRequestService.java`: resolve `workspaceId` from `InterestProperties.defaultWorkspaceId` (never from input); honeypot/min-fill check; per-source limiter + per-workspace `countByWorkspaceIdAndSubmittedAtAfter` ceiling; compute `emailHash`/`openEmailHash`. **Perform NO member- or invitation-existence check** (those cases are indistinguishable by construction — there is no email-keyed invitation finder and none is needed; FR-005/R8). The only branch is the dedup: `insert` catch `DuplicateKeyException` → re-resolve the open row + update (coalesce); both branches return identically. Expose a **deferred side-effect seam** (no-op in US1; US3 plugs in notify) invoked AFTER the response decision (off the response path; new-insert only). Add a code comment recording that the no-oracle guarantee is structural (no existence-lookup branch), not wall-clock-asserted.
- [X] T015 [US1] Implement `PublicInterestController` `POST /api/public/interest` (rides the `@Order(2)` `/api/public/**` permitAll/STATELESS chain; honeypot field `website`; bean-validation name≤200/email≤254/org≤200/message≤2000; returns 202/400/429 per contract) in `backend/src/main/java/com/cadence/api/PublicInterestController.java`.
- [X] T016 [US1] Create `InterestExceptionHandler` (`@Order(HIGHEST_PRECEDENCE)`, `@RestControllerAdvice(assignableTypes=PublicInterestController.class)`, byte-identical `invalid_request`/`rate_limited` envelopes, catch-all 500 value-free, **re-throws** `AccessDeniedException`/`AuthenticationException`) in `backend/src/main/java/com/cadence/api/InterestExceptionHandler.java`.
- [X] T017 [P] [US1] Frontend `interest.service.ts` — `submit(payload)` POST `/api/public/interest`, in `frontend/src/app/features/request-access/interest.service.ts`.
- [X] T018 [US1] Frontend `request-access.component.ts` (standalone lazy route `/request-access`, `data:{ seo: PRIVATE }` so it is `noindex`): form name/email + optional org/message + hidden honeypot; privacy notice (data collected, purpose, legitimate-interest basis, retention); confirmation state; all strings `$localize`; token/PII-free; no web-storage; focus management; in `frontend/src/app/features/request-access/request-access.component.ts` + register route in `frontend/src/app/app.routes.ts`.
- [X] T019 [US1] Add "Request access" entry links from the sign-in screen (`frontend/src/app/features/auth/login/login.component.ts`) and the public home (`frontend/src/app/features/home/home.component.ts`).
- [X] T020 [P] [US1] Frontend axe spec: `/request-access` 0 WCAG 2.2 AA violations across {form, submitting, confirmation, error} states, 44px touch targets, no web-storage of input, no horizontal overflow at 375px (`scrollWidth <= clientWidth` — the F14 precedent), AND assert the **privacy notice** renders all four elements (data collected / purpose / legitimate-interest basis / retention period — FR-006/US1 Sc.5); in `frontend/src/app/features/request-access/request-access.component.spec.ts`.
- [X] T021 [US1] Add the `/request-access` url + matching `assertMatrix` pattern to `lighthouserc.json` (≥85 mobile; the F14 stub needs no new canned route — the form renders without a backend call).

**Checkpoint**: anonymous submit → encrypted persisted row → no-oracle confirmation; public page passes axe + Lighthouse. MVP demonstrable.

---

## Phase 4: User Story 2 - Administrator reviews requests and converts to invitations (Priority: P2)

**Goal**: Admins view the workspace's interest queue and act on each request: review, dismiss, invite (via the existing invitation flow with a role), or erase.

**Independent Test**: As an Admin, list requests, mark one reviewed, invite another with a role (→ a real invitation issued, request `INVITED`), dismiss a third; a non-Admin and a cross-workspace request are both denied.

### Tests for User Story 2 ⚠️ (write first, must fail)

- [X] T022 [P] [US2] Contract test (MockMvc): 5-role matrix on `/api/internal/interest-requests/**` (only ADMIN passes; others 403, and **403 stays 403, not a swallowed 500**); scoped 404 `not_found` byte-identical for absent/other-workspace; `Cache-Control: no-store` on list; **status-filter semantics — `status=open` (default triage) EXCLUDES `REVIEWED` while `status=reviewed`/`all` INCLUDE it (FR-013/US2 Sc.2)**; in `backend/src/test/java/com/cadence/interest/InterestAdminContractTest.java`.
- [X] T023 [P] [US2] Integration test: invite from a NEW/REVIEWED request → `InvitationService.create` issues an invitation, request → `INVITED` with `invitationId` set and `openEmailHash` unset; invite a request whose email is an active member → terminal, **no second invitation, no 500**, `alreadyMember` outcome; in `backend/src/test/java/com/cadence/interest/InterestInviteIT.java`.
- [X] T024 [P] [US2] Integration test (FR-016): **gated concurrent** invite by 2 admins on one request → exactly one `InvitationService.create` call + one 409; review/dismiss on an already-terminal request → 409 no-op; in `backend/src/test/java/com/cadence/interest/InterestConcurrentActionIT.java`.
- [X] T025 [P] [US2] Integration test (FR-022): admin erase → `$set "[ERASED]"` on the 4 PII fields + `$unset emailHash`/`openEmailHash`; row no longer discoverable by email; idempotent; in `backend/src/test/java/com/cadence/interest/InterestErasureIT.java`.

### Implementation for User Story 2

- [X] T026 [US2] Extend `InterestRequestService` with `list(workspaceId, statusFilter)`, status-guarded CAS `review`/`dismiss` (`findAndModify {_id,workspaceId,status:<from>} -> <to>`; dismiss `$unset openEmailHash`), `invite(workspaceId, id, role, actorMemberId, ip)` (claim CAS → `InvitationService.create` → set `invitationId`+`$unset openEmailHash`; catch `AlreadyMemberException` → terminal + `alreadyMember` result), and `erase(workspaceId, id)` (the `CandidateErasureService.wipe` pattern), in `backend/src/main/java/com/cadence/service/InterestRequestService.java`.
- [X] T027 [US2] Implement `InterestRequestController` (`/api/internal/interest-requests`, class-level `@PreAuthorize("hasRole('ADMIN')")`, workspace+actor from session principal): `GET ?status=`, `POST /{id}/review`, `POST /{id}/dismiss`, `POST /{id}/invite` (body `{role}`), `POST /{id}/erase`; `emailUnverified`/`organizationUnverified` constant flags in the list DTO; in `backend/src/main/java/com/cadence/api/InterestRequestController.java`.
- [X] T028 [US2] Extend `InterestExceptionHandler` `assignableTypes` to include `InterestRequestController` (scoped 404 `not_found` byte-identical) in `backend/src/main/java/com/cadence/api/InterestExceptionHandler.java`.
- [X] T029 [P] [US2] Frontend `interest-requests.service.ts` — typed `list()`/`review()`/`dismiss()`/`invite(id,role)`/`erase()`, in `frontend/src/app/features/admin/interest-requests/interest-requests.service.ts`.
- [X] T030 [US2] Frontend `interest-requests.component.ts` (internal admin screen, ADMIN route guard, NOT held to the §IX gate per the F31/F50 precedent): queue list with status filter, per-row actions, role selector on invite, email/org labelled "unverified"; `$localize`; in `frontend/src/app/features/admin/interest-requests/interest-requests.component.ts` + admin route registration.
- [X] T031 [P] [US2] Frontend Jasmine: admin queue component logic (list render, status filter incl. REVIEWED-excluded-from-default, action dispatch, unverified labels) AND a **SC-012 display-inert** assertion that a `<script>`/`=cmd` value in a request field renders inert (Angular interpolation auto-escape, no `innerHTML` bypass); in `frontend/src/app/features/admin/interest-requests/interest-requests.component.spec.ts`.

**Checkpoint**: full review→invite loop works browser→Spring→Mongo; RBAC + scoping + no-oracle enforced.

---

## Phase 5: User Story 3 - Administrators are alerted to new interest (Priority: P3)

**Goal**: A new interest request fires a value-free, coalesced in-app notification to the workspace's admins.

**Independent Test**: Submit a new request → exactly one `RecruiterNotification` row appears for the workspace, containing no submitter PII; a same-email burst yields exactly one alert.

### Tests for User Story 3 ⚠️ (write first, must fail)

- [X] T032 [P] [US3] Integration test (SC-011 + US3 Sc.3): a new submit creates exactly one value-free `RecruiterNotification` (type `INTEREST_REQUEST`, null `candidateId`, no submitter PII); a same-email burst (coalesced) yields exactly one row; AND assert submit enqueues **zero `emailDispatches`/outbound mail** (the notification is never emailed to the submitter — structural anti-amplification); in `backend/src/test/java/com/cadence/interest/InterestNotificationIT.java`.

### Implementation for User Story 3

- [X] T033 [US3] Add `INTEREST_REQUEST` to `RecruiterNotificationType` (append-only) in `backend/src/main/java/com/cadence/domain/RecruiterNotificationType.java` (confirm a null `candidateId` is tolerated — the `ATS_SYNC_FAILED` precedent).
- [X] T034 [US3] Plug the US1 deferred side-effect seam to call `recruiterNotificationService.notify(workspaceId, null, INTEREST_REQUEST)` best-effort, AFTER the response decision and ONLY on a new insert (not on coalesce), in `backend/src/main/java/com/cadence/service/InterestRequestService.java`.

**Checkpoint**: admins are alerted without PII and without flooding.

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: Data-lifecycle, privacy verification, and the house-bar gates. Retention (T035/T036) is REQUIRED (FR-021), not optional.

- [X] T035 [P] Retention integration test (SC-008, write FIRST/must fail): purge deletes aged rows under `MutableClock`; `0`/unset `retentionPeriodDays` uses the 180-day fallback (not immediate delete); only `isConfigured()` workspaces scanned; double-sweep idempotency proxy; in `backend/src/test/java/com/cadence/interest/InterestRetentionIT.java`.
- [X] T036 [P] Implement `InterestRetentionScheduler` (`@Scheduled` + `SchedulerCheckpointService` checkpoint `"interest-retention-scan"` + `@PostConstruct registerReplayAction`; iterate `workspaceConfigRepository.findAll()` where `isConfigured()`; cutoff = `now(clock) - (retentionPeriodDays<=0 ? fallback : retentionPeriodDays)`; hard-delete `submittedAt < cutoff`) in `backend/src/main/java/com/cadence/scheduler/InterestRetentionScheduler.java`.
- [X] T037 [P] `InterestLogPiiScanTest` (FR-009/SC-010): drive submit + a forced-failure path + the notification with high-entropy sentinels for name/email/org/message; assert absent from logs, the `deadLetters` collection, the `recruiterNotifications` row, and the persisted `interestRequests` doc; ensure exception messages are reduced to a PII-free cause-class string at the service boundary (the F22 lesson); in `backend/src/test/java/com/cadence/interest/InterestLogPiiScanTest.java`.
- [X] T038 [P] Add the `SENTINELF70*` PII-scan block to `.github/workflows/ci.yml` (pure ASCII), covering the interest sentinels across captured test output.
- [X] T039 SC-012 / FR-010 export half — **IMPLEMENTED (no longer deferred)**: a CSV export of the admin review queue (`GET /api/internal/interest-requests/export?status=`, Admin-only, workspace-scoped, audits `INTEREST_REQUESTS_EXPORTED`) routes every free-text cell (name/email/organization/message) through the existing `CsvInjectionEscaper` at the export boundary (the F50 `DashboardService.renderCsv` precedent), so `= + - @ |`/tab/CR payloads cannot execute in a spreadsheet. Covered by `InterestExportIT` (neutralization + Admin-only + content-type/attachment + workspace-scoping) and the frontend `interest-requests.component.spec.ts` export assertion. The display-inert half remains covered by the US2 component spec (T031). SC-012 / FR-010 is now **fully closed** (both the display-inert and the export-neutralization halves). Docs updated in `quickstart.md` + `contracts/interest-api.md`. (Process/doc task — Polish phase, no story label.)
- [ ] T040 Run the `specs/029-join-interest-form/quickstart.md` manual E2E (submit → queue → invite → already-member → notification → erasure → retention) and record results in that quickstart.md (or a sibling validation log). (Process task.)
- [X] T041 Byte-level non-ASCII scan of `backend/src/main/java/com/cadence/config/migration/ChangeUnit023_InterestRequestIndexes.java` and `.github/workflows/ci.yml` (Principle V / gate C5); compile via `cd backend ; ..\gradlew.bat test-compile`; record result. (Process/verification task.)
- [X] T042 Two-loop multi-role sub-agent review (Backend, Security, QA — Principle VI / gate C6); apply or report all findings; record outcome in `specs/029-join-interest-form/` review notes. (Process task.)

---

## Dependencies & Execution Order

### Phase dependencies

- **Setup (P1)** → no deps.
- **Foundational (P2)** → depends on Setup; **blocks all user stories**.
- **US1 (P3)** → depends on Foundational; the MVP.
- **US2 (P4)** and **US3 (P5)** → depend on Foundational AND on US1's `InterestRequestService` + `InterestRequest` (they extend the same service/entity); US2 and US3 are independent of each other and can run in parallel once US1 lands.
- **Polish (P6)** → retention is independent (can start after Foundational); PII scan / quickstart / review depend on the relevant stories being present.

### Within each story

- Tests (must fail first) → models → service → controller → frontend → a11y/lighthouse.

### Parallel opportunities

- Foundational: T002, T003, T004 [P]; T006 [P] before T007; T008 [P].
- US1 tests T009–T013 all [P]; frontend T017/T020 [P] alongside backend.
- US2 tests T022–T025 all [P]; T029/T031 [P].
- Polish T035/T037/T038 [P].
- US2 and US3 can proceed in parallel after US1.

---

## Implementation Strategy

### MVP first (US1 only)

1. Setup (P1) → Foundational (P2) → US1 (P3).
2. **STOP and VALIDATE**: anonymous submit persists an encrypted, coalesced, no-oracle row; public page passes axe + Lighthouse.
3. Demo the captured-lead flow (admins can act out-of-band even before the queue).

### Incremental delivery

US1 (capture) → US2 (review + convert to invitation) → US3 (alerting) → Polish (retention, PII scan, review). Each adds value without breaking the prior.

---

## Notes

- **C1 gate**: do not start T001 until the owner confirms the §11-scope decision (see top).
- Tests are written first and MUST fail before implementation (Principle VII).
- `[P]` = different files, no incomplete-task dependency.
- Enum→`StructuredArguments.kv` footgun: log `.name()`/ids only for `InterestRequestStatus`/`RecruiterNotificationType` (the F01.1 logstash crash).
- Commit after each task or logical group; ASCII-scan the Mongock changeset before marking T007 done.
