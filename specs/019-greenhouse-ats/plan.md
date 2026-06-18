# Implementation Plan: ATS Integration — Greenhouse (F40)

**Branch**: `019-greenhouse-ats` | **Date**: 2026-06-18 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/019-greenhouse-ats/spec.md`

## Summary

F40 connects a workspace to **Greenhouse** with a bidirectional sync: candidate + pipeline-stage data flow **in** (a scheduled poll of the authenticated Greenhouse API), and Cadence scheduling activity flows **out** (a durable, idempotent write-back outbox onto the candidate's Greenhouse timeline). All Greenhouse access is wrapped behind a new `AtsConnector` interface (constitution Dependency Policy), so workspace/business logic never references a provider client and F41 (Lever) can reuse the contract by swapping one bean.

The feature is **pure orchestration of existing platform seams** — no new runtime dependency, no broker, no new infrastructure service. It reuses: the write-only encrypted-secret pattern (`WorkspaceConfig.emailProviderCredential` + `PiiStringConverter`), the no-`@Version` `findAndModify`-CAS outbox + idempotency-key pattern (F22 `EmailDispatch`), the `SchedulerCheckpointService` + `@Scheduled` + missed-fire-replay pattern (F00.2), the `DeadLetterService` + `RecruiterNotificationService` operator-alert seam, the `RestClient` + `JdkClientHttpRequestFactory` + retry/classifier integration-adapter pattern (F10/F11), the JDK `HttpServer` integration-pending stub harness (`StubGoogleCalendar` sibling), candidate PII encryption/erasure/consent (F04), and the Admin-only internal-controller + RBAC-inventory pattern (F02/F03).

Because live Greenhouse credentials are not yet provisioned, the feature is delivered and verified end-to-end against a locally-runnable `StubGreenhouse` explicitly labelled **integration-pending**; live-credential promotion (and its mandatory security re-review) is a separate, later step.

## Technical Context

**Language/Version**: Java 21 (backend, toolchain pinned); TypeScript 5.4 / Angular 17.3 (frontend)
**Primary Dependencies**: Spring Boot 3.3.5 (web — incl. `RestClient`, data-mongodb, security w/ method security, actuator, aop, **scheduling**); Mongock 5.4.4; logstash-logback-encoder 9.0. **No new backend or frontend runtime dependency** — Greenhouse HTTP via `RestClient` on a `JdkClientHttpRequestFactory` (the F10 lesson); secret crypto via `PiiCrypto`/`PiiStringConverter`/`MongoPiiConfig`; reconcile lookup via `PiiCrypto.emailHash`; outbox/scheduler/dead-letter/notify reused from F22/F00.2. Test-only: `spring-boot-testcontainers` + `mongodb` (present); the JDK `com.sun.net.httpserver.HttpServer` stub harness (present pattern). **WireMock is NOT used** (F01.1 Jackson conflict).
**Storage**: MongoDB 7.x (Atlas prod, Testcontainers `mongo:7` tests). **Three new collections** — `atsConnections` (one doc/workspace; API key encrypted), `atsWriteBacks` (the outbox — ids/enums/instants only, **no PII**), `atsSyncRuns` (status-surface + audit records — counts/instants/category only, **no PII**). **Extends `candidates`** (F04) with additive ATS-link fields (`atsProvider`, `atsExternalRef`, `atsExternalJobId`, `atsExternalJobTitle`, `atsStageLabel` [encrypted], `atsSyncedAt`) — purged/guarded on erasure. **One new Mongock changeset** `ChangeUnit018_AtsConnectorIndexes` (order **"018"** off the highest applied **"017"**).
**Testing**: JUnit 5 + Mockito (unit: retry/classifier/idempotency-key/reconcile precedence), Testcontainers (integration: connect/verify, poll-import idempotency, erasure-vs-sync race, write-back CAS + dead-letter, PII-scan, index bootstrap), MockMvc (API contract + RBAC matrix), Jasmine (frontend admin component). `StubGreenhouse` JDK HttpServer for all provider interactions.
**Target Platform**: Single Fly.io Machine (backend JAR), Cloudflare Pages (Angular SPA), MongoDB Atlas — unchanged single-instance topology.
**Project Type**: Web application (Spring Boot backend + Angular frontend) — existing structure.
**Performance Goals**: SC-001 candidate appears/updates ≤5 min (poll interval ≤5 min, default `PT5M`); SC-002 burst of 50 imported within 5 min (one bulk paginated list GET + 50 upserts — trivial, ~seconds); write-back delivered within 15 min of provider recovery (SC-004; drain fixed-delay `PT30S`, retry backoff bounded so worst-case recovery < 15 min).
**Constraints**: No queue broker / Redis / second service (constitution §IV); no new dependency (§III/C4); no tool download (C7); candidate PII + the Greenhouse credential never logged (§VIII / FR-022 / SC-005); Greenhouse Harvest rate limit (~50 req/10s) respected via backoff (FR-020); Mongock changeset Java source pure-ASCII (the F30 binary-detection lesson).
**Scale/Scope**: MVP single workspace-scale; one Greenhouse connection per workspace; poll iterates only CONNECTED connections; per-poll candidate page cap + per-drain batch cap (the F12 `Pageable` lesson).

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Gate | Question | Result |
|---|---|---|
| **C1** | Within MVP scope (spec §11)? | **PASS** — F40 Greenhouse ATS is explicitly in §11 MVP and the constitution §I scope list ("ATS integrations — Greenhouse and Lever"). |
| **C2** | New service, queue, or replica? | **PASS** — none. Inbound poll + outbound write-back both use `@Scheduled` + `SchedulerCheckpoint` + a MongoDB outbox collection (the F22 precedent). Three new collections are data, not services. No broker. |
| **C3** | Exposes candidate PII to unauthorized roles? | **PASS** — connection/credential is Admin-only (FR-004); imported candidates inherit the existing candidate RBAC and carry no new visibility surface (FR-028); HM-requisition scoping is *not weakened* (and full HM requisition scoping is deferred to F51 — see Complexity/Scope note, the F32 precedent: no candidate→requisition→assignment link exists yet). Write-back rows + sync-run records hold no PII. |
| **C4** | Dependency outside the fixed stack? | **PASS** — no new dependency. Greenhouse via `RestClient`; all provider access behind `AtsConnector` (Dependency Policy). |
| **C5** | New/modified Windows scripts contain non-ASCII? | **PASS (N/A)** — no new `.ps1`/`.cmd`/`.bat`. The CI PII-scan in `ci.yml` gains ATS sentinels (ASCII). The Mongock `.java` source is held pure-ASCII (the F30 lesson — scan new sources for NUL/binary, not just scripts). |
| **C6** | Multi-role sub-agent review (>=3 roles) scheduled? | **PASS** — two-loop multi-role review (Backend, Security/GDPR, QA) scheduled at task close per the established cadence; the spec already passed a 4-role review + clarify. |
| **C7** | Downloads any build tool / runtime / CLI? | **PASS** — none. Cached Gradle 9.4.0, local JDK, no new npm/Gradle dep. |

**Result: all gates PASS. No Complexity Tracking entries required.**

## Project Structure

### Documentation (this feature)

```text
specs/019-greenhouse-ats/
├── plan.md              # This file
├── research.md          # Phase 0 — decisions (auth, poll, idempotency, reconcile, stub)
├── data-model.md        # Phase 1 — collections, fields, indexes, state machines
├── quickstart.md        # Phase 1 — how to run/demo the integration-pending flow
├── contracts/
│   └── ats-api.md       # AtsConnector interface + internal REST endpoints + Greenhouse mapping
├── checklists/
│   └── requirements.md  # Spec quality checklist (done)
└── tasks.md             # Phase 2 — /speckit.tasks (NOT created here)
```

### Source Code (repository root)

```text
backend/src/main/java/com/cadence/
├── integration/
│   ├── AtsConnector.java                 # NEW provider-agnostic contract (verify/fetchCandidates/pushActivity)
│   ├── AtsProvider.java                  # NEW enum { GREENHOUSE }
│   ├── GreenhouseAtsClient.java          # NEW @Component impl — RestClient + JdkClientHttpRequestFactory, no SDK
│   ├── AtsApiRetry.java                  # NEW backoff+jitter+Retry-After (CalendarApiRetry sibling)
│   ├── AtsApiClassifier.java             # NEW TRANSIENT / AUTH(needs-reauth) / FATAL
│   ├── AtsApiException.java              # NEW RuntimeException(status, category, retryAfter)
│   ├── AtsCandidateRecord.java           # NEW normalized inbound DTO (externalRef,name,email,phone,jobId,jobTitle,stageLabel)
│   └── AtsActivity.java                  # NEW normalized outbound DTO (type, occurredAt, note)
├── domain/
│   ├── AtsConnection.java                # NEW @Document(atsConnections) — encrypted apiKey, status, timestamps, cursor
│   ├── AtsConnectionStatus.java          # NEW enum { CONNECTED, NEEDS_REAUTH, ERROR, INTEGRATION_PENDING, DISCONNECTED }
│   ├── AtsWriteBack.java                 # NEW @Document(atsWriteBacks) — outbox row (no PII)
│   ├── AtsWriteBackStatus.java           # NEW enum { PENDING, SENDING, DELIVERED, DEAD_LETTER, CANCELLED }
│   ├── AtsWriteBackType.java             # NEW enum { LINK_SENT, CONFIRMED, RESCHEDULED, CANCELLED, NO_SHOW, FEEDBACK_SUBMITTED }
│   ├── AtsSyncRun.java                   # NEW @Document(atsSyncRuns) — counts/instants/category (no PII)
│   └── Candidate.java                    # EXTEND — additive atsProvider/atsExternalRef/atsExternalJobId/atsExternalJobTitle/atsStageLabel/atsSyncedAt
├── repository/
│   ├── AtsConnectionRepository.java      # NEW (findByWorkspaceId, findByStatus)
│   ├── AtsWriteBackRepository.java       # NEW (findByWorkspaceIdAndIdempotencyKey, findDue @Query+Pageable)
│   └── AtsSyncRunRepository.java         # NEW (findByWorkspaceId latest)
├── service/
│   ├── AtsConnectionService.java         # NEW connect/verify/disconnect (encrypted $set; WorkspaceConfig precedent)
│   ├── AtsSyncService.java               # NEW per-workspace pull → reconcile → active-state-guarded upsert; records AtsSyncRun
│   ├── AtsWriteBackService.java          # NEW enqueue (insert-catch-Dup) + claim CAS + deliver + retry/dead-letter/notify
│   └── CandidateErasureService.java      # EXTEND — clear ATS PII fields ($set null) + cancel pending write-backs (via invalidator)
├── scheduler/
│   ├── AtsSyncScheduler.java             # NEW @Scheduled (checkpoint "ats-sync-scan") iterate CONNECTED workspaces
│   └── AtsWriteBackScheduler.java        # NEW @Scheduled drain + reaper (checkpoint "ats-writeback-drain")
├── config/
│   ├── AtsProperties.java                # NEW @ConfigurationProperties(prefix="cadence.ats")
│   └── MongoPiiConfig.java               # EXTEND — register AtsConnection.apiKey + Candidate.atsStageLabel
├── api/
│   ├── AtsConnectionController.java      # NEW /api/internal/ats/** (Admin) — status/connect/disconnect/sync-status/dead-letters
│   └── AtsExceptionHandler.java          # NEW @Order(HIGHEST_PRECEDENCE) no-oracle envelope (F31 lesson)
└── config/migration/
    └── ChangeUnit018_AtsConnectorIndexes.java  # NEW order "018" (pure ASCII)

backend/src/test/java/com/cadence/ats/         # NEW test package
└── StubGreenhouse.java + *IT / *Test / contract / PII-scan / index tests

frontend/src/app/
├── features/admin/ats/
│   ├── ats-integration.component.ts      # NEW standalone Admin screen (status, connect, sync, dead-letters)
│   ├── ats-integration.component.spec.ts # NEW Jasmine
│   └── ats.service.ts                    # NEW HttpClient → /api/internal/ats/**
└── app.routes.ts                          # EXTEND — admin/ats route (authGuard + roleGuard('ADMIN'))
```

**Structure Decision**: Existing web-app layout (`backend/` Spring Boot + `frontend/` Angular). ATS code lives in the constitution-blessed `integration/` package behind `AtsConnector`; orchestration in `service/`+`scheduler/`; the Admin surface is an internal screen (no §IX WCAG/Lighthouse gate — the F50/F51 internal-screen precedent).

## Architecture & Key Decisions

1. **`AtsConnector` is the swap boundary (FR-026/SC-009)**. The interface exposes `verifyCredential`, `fetchCandidates(workspaceId, cursor)`, and `pushActivity(workspaceId, externalRef, AtsActivity)`. `GreenhouseAtsClient` is the only class that references Greenhouse URLs/JSON. Services depend on `AtsConnector` (selected from a `Map<AtsProvider,AtsConnector>` built from the injected `List<AtsConnector>` — the calendar `Map<CalendarProvider,CalendarProviderClient>` precedent). A structural test asserts no `com.cadence.service`/`scheduler` class references a Greenhouse literal (the F22 `MailTransportSwapTest` constant-pool-scan precedent).

2. **Inbound = authenticated scheduled poll only (FR-009/011/012)**. `AtsSyncScheduler` (`@Scheduled fixedDelay`, checkpoint `"ats-sync-scan"`, `registerReplayAction`) iterates `atsConnections` in CONNECTED state and calls `AtsSyncService.syncWorkspace`. The poll authenticates with the stored API key (Greenhouse Harvest = HTTP Basic, key as username). **No inbound endpoint is exposed** — a net attack-surface reduction vs the original webhook idea. Burst-of-50 is one paginated list GET + 50 upserts.

3. **Reconcile precedence + resurrection guard (FR-007/FR-008/FR-015)**. `AtsSyncService` reconciles by the authoritative `{workspaceId, atsProvider, atsExternalRef}` key. Secondary email-hash link (`PiiCrypto.emailHash`) is used ONLY to adopt a pre-existing native candidate that has **no `atsExternalRef`** yet (a candidate that already carries a *different* external ref must NOT be email-adopted); two distinct external refs sharing an email never merge.
   - **The reconcile MUST be an explicit RESOLVE-then-guarded-WRITE, NOT a single `upsert=true` carrying `erasureState:ACTIVE` in the filter.** That trap (flagged in review) would *miss* an ERASED row (it fails the `erasureState:ACTIVE` predicate) and then **insert a brand-new PII-populated document** — a resurrection via the upsert mechanic. Instead: (a) **resolve** by external ref with NO erasure filter; (b) if a row is found, do a guarded `updateFirst({_id, erasureState:ACTIVE} → set fields)` — an ERASED row no-ops (a non-PII stage update is also suppressed); (c) **insert only when genuinely absent** (`repo.insert` + catch `DuplicateKeyException` on the partial-unique index = idempotent).
   - **Keep `atsExternalRef` on erasure** (decision #8): because erasure wipes PII but retains the non-PII external-ref anchor, step (a) always *finds* the erased row, so step (b) no-ops and step (c) is never reached — no resurrection. If the ref were wiped, step (a) would miss and step (c) would re-create a PII record.

4. **Outbound = durable idempotent outbox (FR-013–FR-018)**. `AtsWriteBack` mirrors `EmailDispatch`: **no `@Version`**; unique `{workspaceId, idempotencyKey}` is the exactly-once guarantee; `findAndModify` PENDING→SENDING is the single-winner claim; insert-then-catch-`DuplicateKeyException` is the idempotent enqueue. `idempotencyKey` is a length-prefixed sha256 of `{workspaceId, candidateId, AtsWriteBackType, eventAtMillis}` — **derived from the deterministic `eventAt` (the originating event instant), NOT from `Instant.now()` at enqueue** (review fix: an enqueue-time key degrades exactly-once to at-least-once). This needs a **new `IdempotencyKeys` overload** taking `AtsWriteBackType` (the existing `dispatchKey` is hard-typed to `EmailMessageType` — extend, don't reuse). The 6 event seams (below) each call `AtsWriteBackService.enqueue` best-effort (never block the originating flow — the F20 `status_link` precedent). `AtsWriteBackScheduler` drains PENDING-due; a reaper (scanning `{status:SENDING, updatedAt < threshold}`, the F22 `EmailDispatchReaper` shape; reaper-threshold invariant `> readTimeout + max-backoff`) marks SENDING-stuck rows as an in-flight honest-bound state (`SENT_UNCONFIRMED` analogue) and reconciles on restart rather than blindly re-sending (SC-003 honest bound — Greenhouse has no client-supplied dedup key).

5. **Six write-back event seams** (enqueue only; no behavior change to the originating flow):
   - LINK_SENT — `SchedulingService.initiate` (after the invitation `dispatch.enqueue`).
   - CONFIRMED — `SlotReservationService.book` (after BOOKING→BOOKED CAS, alongside `advanceLastContact`).
   - RESCHEDULED — `SlotReservationService.forwardCommitParent` (after parent BOOKED→RESCHEDULED CAS).
   - CANCELLED — `SlotReservationService.cancelByBooking` (after terminal CANCELLED commit).
   - NO_SHOW — `NoShowDefenseScheduler.sweep` after `NoShowCascadeService.stampNoShow`. **Build-watch (review)**: `stampNoShow` is currently `void` with a `findAndModify` lacking `returnNew(true)`; this seam requires changing it to return the booked row (`void`→`SchedulingRequest` + `FindAndModifyOptions.returnNew(true)`) — the only one of the six seams that touches an existing signature. Back-compatible at the existing call site; update the CAS test.
   - FEEDBACK_SUBMITTED — `FeedbackService.submit` (after PENDING→SUBMITTED CAS; enqueue carries **ids only**, never the encrypted `scorecardPayload`).
   - `book`/`forwardCommitParent` are **private** internal sites — enqueue there is fine (same-class best-effort, not a public seam).
   Each seam first checks the candidate is ATS-linked (`atsExternalRef != null`) and ACTIVE; a non-linked candidate is a no-op (no write-back — assert linked→1 / non-linked→0). Erased candidates are swept (FR-015).

6. **Credential secrecy (FR-003/SC-005/SC-006)**. The API key lives only on `AtsConnection.apiKey`, registered in `MongoPiiConfig` (encrypted at rest, the `emailProviderCredential` precedent), `@JsonIgnore` + `@Field(write=NON_NULL)` + omitted from `toString()`; written via targeted `$set` (converter encrypts), cleared on disconnect via `$set null` (never `$unset` — the F03 ClassCastException trap). Exposed only as a derived `credentialSet`/status. Provider error bodies are reduced to a status/category before persist/display (no raw-body echo into sync-run/dead-letter records).

7. **Dead-letter + operator alert (FR-018/FR-019)**. Retry-exhausted write-backs → DEAD_LETTER + `DeadLetterService.recordFailure` (PII-sanitizing) + `RecruiterNotificationService.notify` with new value-free types (`ATS_WRITEBACK_FAILED`, `ATS_SYNC_FAILED`); sync failures also set the degraded state on the connection. **Build-watch (review)**: the new `RecruiterNotificationType` values must be added to the closed enum AND (if they drive an operational member email) to the `SmtpEmailSender` closed `if/else` dispatcher — the F13/F32 build-breaker lesson. The Admin status screen reads connection state + last sync + dead-letter count.

8. **Erasure interaction (FR-015/FR-025)**. `CandidateErasureService.wipe` is extended to `$set null` the ATS PII-adjacent field (`atsStageLabel`) and the job-title denormalization, **retain `atsExternalRef`** (resurrection guard, decision #3), and cancel pending write-backs via a narrow `AtsWriteBackInvalidator` interface (the F31 `SlaDraftInvalidator` lazy-seam to avoid a Spring constructor cycle). All folded into the existing single guarded wipe where possible.

## Phase 0 — Research

See [research.md](./research.md). Resolves: Greenhouse Harvest auth shape (HTTP Basic, API key as username; pagination/`If-Modified-Since`/rate-limit headers), how candidate↔application↔job maps to one `atsExternalRef`, write-back target (candidate "Activity Feed" note), the no-dedup-key honest bound, the active-state-guarded-upsert resurrection defense, and confirmation that all four clarified decisions (API key, poll-only, expanded write-back set, raw stage label) are buildable on the fixed stack. No NEEDS CLARIFICATION remain (the clarify session closed them).

## Phase 1 — Design & Contracts

- [data-model.md](./data-model.md) — the three new collections + `candidates` extension, every field, the `ChangeUnit018` indexes (unique `{workspaceId}` on `atsConnections`; unique `{workspaceId,idempotencyKey}` + `{status,nextAttemptAt}` on `atsWriteBacks`; unique **partial** `{workspaceId,atsProvider,atsExternalRef}` over `atsExternalRef:{$exists:true}` on `candidates`; `{workspaceId,startedAt:-1}` on `atsSyncRuns`), and the connection/write-back state machines.
- [contracts/ats-api.md](./contracts/ats-api.md) — the `AtsConnector` Java interface contract, the internal REST endpoints (`/api/internal/ats/connection` GET/POST/DELETE, `/api/internal/ats/sync-status`, `/api/internal/ats/dead-letters`) with role gates + error envelopes, and the Greenhouse Harvest endpoint mapping the stub honors.
- [quickstart.md](./quickstart.md) — run the backend + `StubGreenhouse`, connect as Admin, observe an import and a write-back, simulate degraded mode, and the test-run flags.
- Agent context updated via `update-agent-context.ps1 -AgentType claude`.

## Multi-role review (constitution C6) — 2026-06-18 (plan phase)

Three role reviewers (Backend/DevOps, Security/GDPR, QA) reviewed the plan + design against the **real codebase**. **All three: APPROVE-WITH-NITS, zero BLOCKERS.** Seam references verified accurate (Mongock highest applied 017→new 018; `EmailDispatch`/`SchedulerCheckpointService`/`GoogleCalendarClient`/`CandidateErasureService`/`MongoPiiConfig`/`WorkspaceConfigService` all confirmed; all six write-back methods exist).

**Applied to the plan/design now (correctness-affecting):**
- **Resurrection trap closed (Security S1, the key finding)**: reconcile is an explicit resolve-then-guarded-write, never a single `upsert` with `erasureState:ACTIVE` in the filter (which would miss an ERASED row and insert a fresh PII doc). — decision #3, data-model §4.
- **Idempotency key from deterministic `eventAt`** (Backend N4), not enqueue-time `Instant.now()`, or exactly-once degrades. — decision #4.
- **Build-watch items surfaced**: `stampNoShow` `void`→returns-row signature change (the only seam touching a signature); `IdempotencyKeys` needs an `AtsWriteBackType` overload (extend, not reuse); new `RecruiterNotificationType` values + `SmtpEmailSender` dispatcher (F13/F32 build-breaker). — decisions #4/#5/#7.

**Carried to `/speckit.tasks` (test-enumeration SHOULD-FIX/NITs — not plan defects):**
- QA: explicit **SC-007** named tests for *both* sync-restart (no dup import) and write-back-restart (carry the F31/F32 double-sweep honest-residual label); **FR-020** named *sync-path* 429+`Retry-After` integration test (not just the retry unit test); **SC-004** config-invariant test on `AtsProperties` bounds (`retry-base-backoff × 2^maxAttempts < 15min`, the F23 `reaperThreshold` precedent); FR-008 "already-has-a-different-ref blocks email-adopt" sub-case; FR-013 linked→1 / non-linked→0; disconnect→`credentialSet:false`; degraded-flag assertion; SC-002 explicit 50/50/0 count assertion.
- Security: PII-scan case driving a **Greenhouse 401 whose body echoes the submitted key**, asserting the key/body never lands in `atsConnections.lastErrorCategory` or the dead-letter; add a **job-title sentinel** to the CI PII scan (the one plaintext PII-adjacent field, "by discipline" backstop).
- Backend: note the reaper scans `atsWriteBacks {status,updatedAt}` with no dedicated index (matches the F22 precedent — acceptable at MVP volume).

## Complexity Tracking

No constitution gate failed; no entries required.

**Scope note (honest deferral, not a violation)**: Full Hiring-Manager → requisition scoping (FR-028's forward intent) is **deferred to F51**, because no candidate→requisition→assignment link exists in the codebase today (documented in the F32 notes). F40 stores the external job id/title denormalized on the candidate and does **not** widen any role's visibility (imported candidates are reachable exactly as native candidates are). Building HM-requisition scoping now would be an F51 stub (§II) / scope-creep (§I). Reported here, mirroring the F31/F32 precedent.
