# Tasks: Email Delivery Channel (F22)

**Input**: Design documents from `C:\Users\xamcr\Cadence\specs\011-email-delivery\`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/email-delivery-api.md

**Tests**: INCLUDED — constitution §VII (Test-First & Acceptance-Driven) is mandatory for backend business logic; each user story has at least one acceptance test (constitution requirement). Write tests first; they MUST fail before implementation.

**Organization**: by user story (spec.md US1–US5). Paths follow plan.md (`backend/src/main/java/com/cadence/...`, `backend/src/test/java/com/cadence/emaildelivery/...`, `frontend/src/app/features/email-templates/`).

**Run flags (CLAUDE.md)**: `JAVA_HOME=C:/jdk-24.0.1`, cached `gradle-9.4.0`, `-Dapi.version=1.41`, `DOCKER_HOST=npipe:////./pipe/docker_engine`. First multi-class Testcontainers run after a recompile may throw the one-time `GenericContainer` class-init error — re-run.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Dependency + configuration so the channel can be wired.

- [x] T001 Add `org.springframework.boot:spring-boot-starter-mail` to `backend/build.gradle` (the only new runtime dep; constitution §III Spring Mail — record the one-line justification in the dependency block comment).
- [x] T002 [P] Create `EmailDeliveryProperties` (`@ConfigurationProperties("cadence.email")`) in `backend/src/main/java/com/cadence/config/EmailDeliveryProperties.java`: SMTP host/port/username/password, `webhookSecret`, `opsAlertAddress`, `retryMaxAttempts` (default 3), `retryBaseBackoff`, `reaperThreshold` (with the documented invariant `reaperThreshold > smtp.read-timeout + maxBackoff`), `sweepBatchLimit`.
- [x] T003 [P] Add `cadence.email.*` to `backend/src/main/resources/application.yml` binding env Fly secrets (`${CADENCE_EMAIL_SMTP_HOST/PORT/USERNAME/PASSWORD}`, `${CADENCE_EMAIL_WEBHOOK_SECRET}`) + spring mail/JavaMailSender properties; add no secrets inline.
- [x] T004 [P] Add test overrides to `backend/src/main/resources/application-test.yml`: `retryBaseBackoff: PT0S`, a short `reaperThreshold`, a fixed `webhookSecret`, a dummy SMTP host (recording transport is used in tests).

---

## Phase 2: Foundational (Blocking Prerequisites)

**⚠️ CRITICAL**: Completes the transport (makes F01 member emails real — the §II leg) and the outbox storage every story needs. No user story can start until this phase is done.

- [x] T005 [P] Create `DispatchStatus` enum (`PENDING, SENDING, SENT, SENT_UNCONFIRMED, FAILED, BOUNCED, REFUSED`) in `backend/src/main/java/com/cadence/domain/DispatchStatus.java`.
- [x] T006 [P] Create `DispatchOutcomeReason` enum (data-model §4 — value-free codes) in `backend/src/main/java/com/cadence/domain/DispatchOutcomeReason.java`.
- [x] T007 Add append-only `EMAIL_DISPATCH_SENT/REFUSED/FAILED/BOUNCED` values to `backend/src/main/java/com/cadence/domain/AuthEventType.java` (never reorder/remove existing).
- [x] T008 [P] Create `EmailDispatch` document (data-model §1 — **no `@Version`**; candidate-ID-only, no PII; `updatedAt` on every transition) in `backend/src/main/java/com/cadence/domain/EmailDispatch.java`; `toString()` omits any candidate-resolvable value.
- [x] T009 Add `undeliverable` + `undeliverableReason` + `undeliverableAt` + `undeliverableClearedAt` fields (data-model §2) to `backend/src/main/java/com/cadence/domain/Candidate.java` (non-PII booleans/instants — may stay in `toString()`); the gate (T020) reads `isUndeliverable()`, the webhook (T040) writes it.
- [x] T010 Create `EmailDispatchRepository` in `backend/src/main/java/com/cadence/repository/EmailDispatchRepository.java`: `findByWorkspaceIdAndIdempotencyKey`, `findByProviderMessageRef`, and an **explicit `@Query`** due-row finder `{ status, scheduledFor:{$lte}, nextAttemptAt:{$lte} }` with a `Pageable`/limit cap (F12 `InvalidMongoDbApiUsageException` lesson — never a derived multi-criteria method).
- [x] T011 Create Mongock `ChangeUnit010_EmailDispatchIndexes` (order **"010"** off highest-applied "009") in `backend/src/main/java/com/cadence/config/migration/ChangeUnit010_EmailDispatchIndexes.java`: unique `{workspaceId,idempotencyKey}`, `{status,nextAttemptAt}`, sparse `{providerMessageRef}`, `{workspaceId,candidateId,createdAt:-1}`; native `createIndex` + targeted `dropIndex` rollback (never `dropIndexes()`).
- [x] T012 [P] Integration test `EmailDispatchIndexTest` (asserts the four indexes via `listIndexes`) in `backend/src/test/java/com/cadence/emaildelivery/EmailDispatchIndexTest.java`.
- [x] T013 Widen `EmailSender` with `SendOutcome send(OutboundEmail)` and add records `OutboundEmail` {workspaceId,toAddress,subject,htmlBody,messageId} + `SendOutcome` {accepted,providerMessageRef,transient,reasonCode} in `backend/src/main/java/com/cadence/integration/` (keep legacy `sendEmail`/`sendSystemAlert`).
- [x] T014 Create `MailTransport` SPI + `SmtpMailTransport` (`JavaMailSender`-backed; sets `Message-ID` header; classifies transient vs permanent SMTP errors into `SendOutcome`) in `backend/src/main/java/com/cadence/integration/`.
- [x] T015 Create `MailConfig` (builds/caches a `JavaMailSender` from `EmailDeliveryProperties`; per-workspace selection from F03 `WorkspaceConfig` credential with the app-level default fallback for member/operational mail). When neither a workspace credential nor the app-level default is present for a candidate send, surface a `NO_PROVIDER_CONFIG` outcome (consumed by T025/FR-004) rather than throwing — in `backend/src/main/java/com/cadence/config/MailConfig.java`.
- [x] T016 [P] Create `OperationalEmailTemplates` constants (member-invitation, password-reset, system-alert — link-bearing, no candidate PII) in `backend/src/main/java/com/cadence/integration/OperationalEmailTemplates.java`.
- [x] T017 Create `SmtpEmailSender` (`@Primary` `EmailSender`): legacy `sendEmail(memberId,...)` resolves the member via `MemberService` + renders an `OperationalEmailTemplates` constant → `MailTransport`; `sendSystemAlert` → ops address; `send(OutboundEmail)` → `MailTransport`. **Remove `@Primary` from `NoOpEmailSender`** (single primary) in `backend/src/main/java/com/cadence/integration/`.
- [x] T018 Create test double `RecordingMailTransport` (records `OutboundEmail`s; injectable per-call failure/transient sequences; `sentCount()`; **a `gate(n)` latch that blocks on send** for non-vacuous concurrency, the F10/F11 stub precedent) + `EmailDeliveryItBase` (Testcontainers singleton base, recording transport as `@Primary` test bean, mutable test clock for deterministic `updatedAt`/scheduled-time control, clean-up) in `backend/src/test/java/com/cadence/emaildelivery/`.

**Checkpoint**: F01 member invitation + password-reset emails now transmit (via recording transport in tests / real SMTP locally); outbox storage ready.

---

## Phase 3: User Story 1 — Reliable transactional send with consent enforcement (Priority: P1) 🎯 MVP

**Goal**: Render + send a template to a candidate, but only if the consent gate permits.

**Independent Test**: dispatch to a consenting candidate → delivered + dispatch row `SENT`; dispatch to an erased/withdrawn/over-retention/no-basis candidate → no transmission, row `REFUSED` with reason.

### Tests (write first, must fail)

- [x] T019 [P] [US1] `EmailDispatchConsentGateTest` — each refusal reason (ERASED/WITHDRAWN/OVER_RETENTION/NO_BASIS/UNAVAILABLE/UNDELIVERABLE) → `REFUSED`, zero transport sends, value-free audit; **plus FR-004**: a workspace with no email-provider config → fails cleanly with `NO_PROVIDER_CONFIG` recorded + recruiter-visible, no silent drop, no transmit — in `backend/src/test/java/com/cadence/emaildelivery/EmailDispatchConsentGateTest.java`.
- [x] T020 [P] [US1] `EmailDispatchSendTest` — consenting candidate → `SENT`, one transport send, `EMAIL_DISPATCH_SENT` audit, no PII on row, and the send completes within the SC-001 inline-latency bound (asserted against the recording sink); **plus the render-failure edge case**: a template that fails to render → dispatch fails with `RENDER_FAILED`, zero transport sends (no broken message), recorded reason — in `backend/src/test/java/com/cadence/emaildelivery/EmailDispatchSendTest.java`.
- [x] T021 [P] [US1] `EmailDispatchContractTest` (MockMvc) — `POST /api/internal/candidates/{id}/emails`: 202/200-idempotent/403-per-role/404-scoped/409-each-refusal-reason/400-bad-body; response has no PII + `Cache-Control: no-store` — in `backend/src/test/java/com/cadence/emaildelivery/EmailDispatchContractTest.java`.
- [x] T022 [P] [US1] `MemberEmailLiveTest` — F01 invitation + password-reset now call the transport (assert via `RecordingMailTransport`), consent gate NOT applied to member mail (§II) — in `backend/src/test/java/com/cadence/emaildelivery/MemberEmailLiveTest.java`.

### Implementation

- [x] T023 [US1] Extend `ContactPermissionGate` with `UNDELIVERABLE` reason as the **last deny before `Decision.allow()`** (precedence `erased > over_retention > withdrawn > no_basis > undeliverable`; do not overload `UNAVAILABLE`) in `backend/src/main/java/com/cadence/service/ContactPermissionGate.java`; extend the existing precedence unit test.
- [x] T024 [US1] Add public `RenderedMessage renderForSend(workspaceId, type, stageKey, candidateId, Map<String,String> nonPiiContext)` to `backend/src/main/java/com/cadence/service/EmailTemplateService.java` (scoped candidate read + name-decrypt + `resolveForRender` + `renderer.render`; PII decryption stays inside F21).
- [x] T025 [US1] Create `EmailDispatchService.enqueue(...)` + dispatch core (claim CAS → gate at claim time → `renderForSend` → `EmailSender.send` → record status/audit; value-free logs, `.name()` only) in `backend/src/main/java/com/cadence/service/EmailDispatchService.java`. Scope for US1: the **single-attempt** happy path of claim→render→send→record + the gate refusal (`REFUSED`), render-failure (`RENDER_FAILED`), and no-provider (`NO_PROVIDER_CONFIG`) terminal branches; idempotent dup-key handling and retry/backoff classification are added in US2 (T033/T034). `nonPiiContext` is transient (never persisted); `renderContextRef` shape-guarded.
- [x] T026 [US1] Create `CandidateEmailController` (`POST /api/internal/candidates/{candidateId}/emails`, `@PreAuthorize("hasAnyRole('ADMIN','RECRUITER')")`, `no-store`) + `EmailDeliveryDtos` + `EmailDeliveryExceptionHandler` (400/403/404/409 value-free envelopes) in `backend/src/main/java/com/cadence/api/`.
- [x] T027 [US1] Add a "Send to candidate" action to the F21 preview in `frontend/src/app/features/email-templates/` (service call + button + Jasmine spec for the happy path + a 409 not-contactable path).

**Checkpoint**: a recruiter can send a consent-gated templated email; member emails are live. US1 demonstrable independently.

---

## Phase 4: User Story 2 — No duplicate sends, even across restarts (Priority: P1)

**Goal**: exactly-once per logical message under duplicate/concurrent triggers and mid-send crash.

**Independent Test**: same logical message dispatched twice / concurrently / with a simulated crash between accept and commit → exactly one delivery (counted at the transport sink).

### Tests (write first, must fail)

- [x] T028 [P] [US2] `IdempotencyKeyTest` (unit) — `sha256(workspaceId|candidateId|messageType|scheduledForMillis)` length-prefixed, stable, distinct on each field — in `backend/src/test/java/com/cadence/emaildelivery/IdempotencyKeyTest.java`.
- [x] T029 [P] [US2] `EmailDispatchIdempotentEnqueueTest` — duplicate enqueue (same key) → one row, one transport send, HTTP 200 idempotent — in `.../emaildelivery/EmailDispatchIdempotentEnqueueTest.java`.
- [x] T030 [P] [US2] `EmailDispatchConcurrencyTest` — gated N-thread same-key dispatch → exactly one `SENDING` winner and `recordingTransport.sentCount()==1` (assert at the sink, not the row) — in `.../emaildelivery/EmailDispatchConcurrencyTest.java`.
- [x] T031 [P] [US2] `EmailDispatchCrashWindowTest` — a row left `SENDING` is reaped to `SENT_UNCONFIRMED` with **no** resend, and a transient failure recovers to a single `SENT`. Drive the crash window **deterministically** by stamping `updatedAt` into the past (the `updatedAt < threshold` CAS predicate) via the test clock — NOT by wall-clock sleeps — so the reaper-vs-retry race cannot flake — in `.../emaildelivery/EmailDispatchCrashWindowTest.java`.

### Implementation

- [x] T032 [US2] Implement the idempotency-key derivation (length-prefixed SHA-256, F10 `GoogleEventId` precedent) + set the SMTP `Message-ID = keyHash` (T014 hook) — in `EmailDispatchService` / a small `IdempotencyKeys` helper.
- [x] T033 [US2] Make `enqueue` insert `PENDING` and treat the unique-index `DuplicateKeyException` as the idempotent success (return existing row) in `EmailDispatchService`.
- [x] T034 [US2] Implement the claim CAS + transient/permanent classification: `SENDING→PENDING` with `nextAttemptAt = now + backoff(attempt)+jitter` until `retryMaxAttempts` → `SENDING→FAILED` (data-model §3) in `EmailDispatchService`.
- [x] T035 [US2] Implement the stale-`SENDING` reaper as a **standalone** `@Scheduled` `EmailDispatchReaper` — CAS `{status:SENDING, updatedAt<threshold}→SENT_UNCONFIRMED` (no resend) honouring the `reaperThreshold` invariant — in `backend/src/main/java/com/cadence/scheduler/EmailDispatchReaper.java`. (Standalone so US2 closes without depending on the US4 scheduler file; the US4 sweep may optionally invoke it.)

**Checkpoint**: exactly-once proven under duplicate/concurrent/crash.

---

## Phase 5: User Story 3 — Failure & bounce visibility for recruiters (Priority: P2)

**Goal**: hard bounces, provider rejections, and consent refusals are recorded against the candidate and surfaced to the recruiter; no silent loss; no PII.

**Independent Test**: signed hard-bounce event → candidate `undeliverable` + recruiter notification + next send refused; terminal send failure → dead-letter + notification; soft bounce → no flag; forged event → no state change.

### Tests (write first, must fail)

- [x] T036 [P] [US3] `EmailBounceWebhookTest` — bad signature → 401 no state change; hard bounce → row `BOUNCED` + candidate flagged + `EMAIL_DISPATCH_BOUNCED` audit + notification; soft bounce → no flag; duplicate `eventId` → single flag/notify; unknown ref → 200 ack-no-change — in `backend/src/test/java/com/cadence/emaildelivery/EmailBounceWebhookTest.java`.
- [x] T037 [P] [US3] `WebhookSecurityChainTest` — `/api/webhooks/email/events` reachable unauthenticated (signature-gated), CSRF-exempt; the `/api/**` 401, `/api/internal/**` 403, and actuator-404 contracts unchanged; `RbacEndpointInventoryTest` still green with the webhook allow-listed — in `.../emaildelivery/WebhookSecurityChainTest.java`.
- [x] T038 [P] [US3] `CandidateErasurePurgeTest` — an erased candidate has no residual `undeliverable*` state (FR-017/SC-002) — in `.../emaildelivery/CandidateErasurePurgeTest.java`.
- [x] T039 [P] [US3] `DeadLetterDispatchTest` — terminal dispatch failure → `DeadLetterService` record (candidate-id-only) + recruiter notification, no PII — in `.../emaildelivery/DeadLetterDispatchTest.java`.

### Implementation

- [x] T040 [US3] Create `EmailBounceService` (idempotent by `eventId`; ordered non-transactional flips: row `→BOUNCED` CAS, then candidate `undeliverable=true`+value-free metadata, then audit+notify; soft bounce → row reason only) in `backend/src/main/java/com/cadence/service/EmailBounceService.java`.
- [x] T041 [US3] Create `EmailWebhookController` (`POST /api/webhooks/email/events`; verify signature with `CADENCE_EMAIL_WEBHOOK_SECRET` BEFORE any state change; parse only `{eventId,providerMessageRef,type,occurredAt}` via explicit `JsonNode.path` — never bind provider free-text) in `backend/src/main/java/com/cadence/api/EmailWebhookController.java`.
- [x] T042 [US3] Add the dedicated security chain `securityMatcher("/api/webhooks/email/**").permitAll()` (CSRF-exempt, STATELESS) without widening the existing 401/403/actuator chains, and add the webhook handler to the `RbacEndpointInventory` allow-list, in `backend/src/main/java/com/cadence/security/SecurityConfig.java`.
- [x] T043 [US3] Extend `CandidateErasureService.wipe`'s `Update` to reset `undeliverable=false` + clear the three nullable fields (FR-017) in `backend/src/main/java/com/cadence/service/CandidateErasureService.java`; extend the existing erasure test.
- [x] T044 [US3] Create `RecruiterNotificationService` (in-app notification seam, value-free) and wire it into the `REFUSED` (FR-008), `FAILED` (FR-012), and `BOUNCED` (FR-017) paths in `backend/src/main/java/com/cadence/service/RecruiterNotificationService.java`.
- [x] T045 [US3] Wire terminal `FAILED` in `EmailDispatchService` to `DeadLetterService.recordFailure(taskName, ex, candidateId)` (candidate-id-only) + notification.

**Checkpoint**: bounces/failures visible to recruiters; webhook secure; erasure clean.

---

## Phase 6: User Story 4 — Reusable scheduled-dispatch mechanism (Priority: P2)

**Goal**: future-dated sends fire once through the same gated, idempotent path; missed windows replay once.

**Independent Test**: enqueue a future `scheduledFor` → fires once after the time passes; downtime spanning the time → replays once; candidate non-contactable at fire time → refused, not delivered.

### Tests (write first, must fail)

- [x] T046 [P] [US4] `EmailDispatchSchedulerTest` — a due future row fires exactly once; a row whose candidate became non-contactable between enqueue and fire → `REFUSED` at fire time (FR-007) — in `backend/src/test/java/com/cadence/emaildelivery/EmailDispatchSchedulerTest.java`.
- [x] T047 [P] [US4] `EmailDispatchMissedFireTest` — a stale `RUNNING` checkpoint replay (downtime) → the missed sweep runs once, no duplicate (drives `registerReplayAction`) — in `.../emaildelivery/EmailDispatchMissedFireTest.java`.

### Implementation

- [x] T048 [US4] Create `EmailDispatchScheduler` (`@Scheduled(fixedDelay)` `sweep()` wrapped in `SchedulerCheckpointService.start(TASK)/complete(TASK)`; `@PostConstruct registerReplayAction(TASK, this::sweep)`; batched `@Query` due-row read; per-tick PII-free `due/sent/refused/failed` log; comment that correctness rests on the per-row CAS, not single-threading) in `backend/src/main/java/com/cadence/scheduler/EmailDispatchScheduler.java`.

**Checkpoint**: F23/F31/F32 can now enqueue future-dated sends and inherit idempotency + missed-fire recovery.

---

## Phase 7: User Story 5 — Provider portability (Priority: P3)

**Goal**: swap the provider with no business-code change.

**Independent Test**: replace the `MailTransport`/`EmailSender` bean → all dispatch behaviour preserved, zero calling-service edits.

- [x] T049 [P] [US5] `MailTransportSwapTest` — replace `MailTransport` with an alternate recording impl; assert dispatch (gate, idempotency, dead-letter, bounce) unchanged and no service class references a provider/SMTP type directly (structural reflection check) — in `backend/src/test/java/com/cadence/emaildelivery/MailTransportSwapTest.java`.

---

## Phase 8: Polish & Cross-Cutting Concerns

- [x] T050 [P] Create `EmailDispatchMetrics` (Micrometer on the existing actuator, FR-024/D11): status gauge (PENDING backlog depth), counters sent/refused/failed/bounced + reaper `SENT_UNCONFIRMED` + webhook unmatched-event, in `backend/src/main/java/com/cadence/service/EmailDispatchMetrics.java`.
- [x] T051 [P] `EmailDeliveryLogPiiScanTest` — drive a failing render + a hard bounce with sentinel recipient/body; assert absent from logs (TRACE, scoped to `com.cadence`), audit, outbox row, and dead-letter; **also assert the webhook secret value never appears in logs or persisted state** (FR-016 write-only-secret discipline) — in `backend/src/test/java/com/cadence/emaildelivery/EmailDeliveryLogPiiScanTest.java`.
- [x] T052 [P] Extend `.github/workflows/ci.yml` PII scan with `SENTINELF22BODY_zz9` + `SENTINELF22RECIPIENT_zz9` sentinels and a `Message-ID`/SMTP-header-injection guard on the email-delivery sources.
- [x] T053 [P] Add "Implementation Notes (011-email-delivery)" to `CLAUDE.md` (transport seam, no-`@Version` CAS outbox, reaper honest-bound, webhook chain, erasure-purge, Fly secret names, FR-024 metrics, the enum→`kv` footgun reminder).
- [x] T054 Run the full backend suite (`./gradlew test`) + `ng test --watch=false` + `ng build`; confirm all F22 suites + `RbacEndpointInventoryTest` + all prior F01–F21 suites green; fix any regression.
- [~] T055 Manual `quickstart.md` end-to-end against a real SMTP sink (MailHog) — **deferred to a human operator**: this environment has no local SMTP server. The automated equivalent is covered (§II permits an automated acceptance test): `MemberEmailLiveTest` proves the F01 invite/reset path reaches the transport; `EmailDispatchContractTest`/`EmailBounceWebhookTest` cover recruiter-send + signed-bounce. The `SmtpMailTransport`→real-SMTP wire is manual-verify-only per research D1.
- [x] T056 **Multi-role sub-agent implementation review (Backend, Security/GDPR, DevOps/QA)** completed over the diff (constitution §VI / C6). **Loop 1**: all three APPROVE-WITH-NITS, zero MUST-FIX. **Loop 2**: applied the substantive nits (unchecked SENT-write CAS → reconciliation-conflict metric/no-false-SENT; `MailConfig` cache key → SHA-256; dedicated `SENT_UNCONFIRMED` reaper reason; `SecurityConfig` `@Order` doc; CLAUDE.md BOM removed; T050 gauge doc) and re-verified green. Full backend suite (628) + frontend (38) + `ng build` green; no new `.ps1/.cmd/.bat` (non-ASCII scan N/A). Residual cosmetic nits (CI header-guard tightness, metrics-via-registry-vs-HTTP) accepted.

---

## Dependencies & Execution Order

### Phase dependencies
- **Setup (P1: T001–T004)** → no deps.
- **Foundational (P2: T005–T018)** → after Setup. **BLOCKS all user stories.** (Makes member email real — already a shippable §II increment.)
- **US1 (P3: T019–T027)** → after Foundational. MVP.
- **US2 (P4: T028–T035)** → after US1 (hardens the dispatch core T025 builds).
- **US3 (P5: T036–T045)** → after US1 (needs the dispatch service + candidate fields); independent of US2/US4.
- **US4 (P6: T046–T048)** → after US1. (US2's reaper T035 is now a standalone `EmailDispatchReaper` — no shared-file hazard with the US4 scheduler; T048's sweep may optionally invoke it.)
- **US5 (P7: T049)** → after Foundational (transport seam) + US1.
- **Polish (P8: T050–T056)** → after the targeted stories complete.

### Story independence
- US1 is the MVP and stands alone (send + gate + member-email-live).
- US3 (bounce/failure) and US4 (scheduled) each build on US1's dispatch service but are independently testable.
- US5 is a structural property test over the transport seam.
- **Shared-file coordination**: T025/T032/T033/T034/T045 all edit `EmailDispatchService.java` — sequential, not `[P]`. T035 (`EmailDispatchReaper.java`) and T048 (`EmailDispatchScheduler.java`) are now **separate files** — no conflict. T042 (`SecurityConfig.java`) and T043 (`CandidateErasureService.java`) are single-touch.

### Parallel opportunities
- Setup: T002, T003, T004 in parallel.
- Foundational: T005, T006, T008, T016 in parallel; T012 after T011.
- Each story's test tasks (the `[P]` block) in parallel, then implementation.

---

## Implementation Strategy

### MVP (ship after US1)
1. Phase 1 Setup → 2. Phase 2 Foundational (member emails go live — already demonstrable) → 3. Phase 3 US1 (consent-gated candidate send) → **STOP & VALIDATE** the §II leg + the consent-refusal matrix → deploy/demo.

### Incremental
US1 (send+gate, MVP) → US2 (exactly-once) → US3 (bounce/failure visibility) → US4 (scheduled mechanism for F23/F31/F32) → US5 (portability) → Polish (metrics, PII scan, CI, review).

### Notes
- `[P]` = different files, no incomplete-task dependency.
- Tests precede implementation within each story (write → fail → implement → green) per constitution §VII.
- Never pass an enum to `StructuredArguments.kv(...)` (log `.name()` only — the F01.1 logstash footgun).
- All status transitions are `findAndModify` CAS (no `@Version` on `EmailDispatch`).
- Commit after each task or logical group; do not merge partial work to `main` (constitution §II).
