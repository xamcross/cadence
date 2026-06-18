# Implementation Plan: ATS Integration — Lever (F41)

**Branch**: `020-lever-ats` | **Date**: 2026-06-18 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/020-lever-ats/spec.md`

## Summary

F41 adds **Lever** as the second ATS connector behind the existing `AtsConnector` contract, and makes the F40 ATS subsystem **multi-connector**: a single workspace can hold a Greenhouse connection and a Lever connection simultaneously, provider-isolated. It is overwhelmingly **reuse** — the connector contract, sync orchestration, write-back outbox, retry/dead-letter, role model, candidate-PII/erasure/consent, the `@Scheduled`+checkpoint pattern, and the JDK-`HttpServer` integration-pending stub harness all already exist (F40, verified against the real source). 

The net-new work is three things: (1) a `LeverAtsClient` adapter (RestClient on `JdkClientHttpRequestFactory`, no SDK); (2) the **provider-awareness refactor** of the F40 connection/routing/status code, which is workspace-keyed today and must become `(workspace, provider)`-keyed — including the one unavoidable schema change, migrating the `atsConnections` unique index from `{workspaceId}` to `{workspaceId, provider}` (`ChangeUnit019`); and (3) coexistence guarantees — provider-correct write-back routing, provider isolation, and per-provider status surface. Delivered and verified end-to-end against `StubLever` (a `StubGreenhouse` sibling), explicitly **integration-pending**; live-credential promotion + security re-review is a separate later step.

## Technical Context

**Language/Version**: Java 21 (backend, toolchain pinned); TypeScript 5.4 / Angular 17.3 (frontend)
**Primary Dependencies**: Spring Boot 3.3.5 (web — incl. `RestClient`, data-mongodb, security w/ method security, actuator, aop, scheduling); Mongock 5.4.4; logstash-logback-encoder 9.0. **No new backend or frontend runtime dependency** — Lever HTTP via `RestClient` on a `JdkClientHttpRequestFactory` (the F10/F40 pattern); secret crypto via `PiiCrypto`/`PiiStringConverter`/`MongoPiiConfig`; reconcile/outbox/scheduler/dead-letter/notify all reused from F40/F22/F00.2. Test-only: `spring-boot-testcontainers` + `mongodb` (present); the JDK `com.sun.net.httpserver.HttpServer` stub harness (present pattern). **WireMock is NOT used** (F01.1 Jackson conflict).
**Storage**: MongoDB 7.x (Atlas prod, Testcontainers `mongo:7` tests). **No new collection.** Extends F40: `AtsWriteBack` gains a `provider` field (routing key); `AtsSyncRun` gains a `provider` field (per-provider status); the `candidates` ATS fields are unchanged (already provider-discriminated). **One new Mongock changeset** `ChangeUnit019_AtsLeverMultiConnector` (order **"019"** off the highest applied **"018"**): migrate `atsConnections` unique `{workspaceId}` → unique `{workspaceId, provider}`, add `atsSyncRuns {workspaceId, provider, startedAt:-1}`.
**Testing**: JUnit 5 + Mockito (unit: `LeverAtsClient` parse/minimization, classifier reuse, structural no-Lever-literal-in-service); Testcontainers (integration: two-connector coexistence, provider isolation, erasure/disconnect scope, index bootstrap, PII scan); MockMvc (provider-parameterized API contract + 5-role RBAC matrix); Jasmine (two-provider admin component). `StubLever` + `StubGreenhouse` JDK HttpServer.
**Target Platform**: Single Fly.io Machine (backend JAR), Cloudflare Pages (Angular SPA), MongoDB Atlas — unchanged single-instance topology.
**Project Type**: Web application (Spring Boot backend + Angular frontend) — existing structure.
**Performance Goals**: SC-001 candidate appears/updates ≤5 min (poll ≤5 min, default `PT5M`, shared scan iterates both providers); SC-002 burst of 50 imported within 5 min (one paginated list GET + 50 upserts per provider — trivial); SC-004 write-back delivered within 15 min of provider recovery; SC-014 a Lever outage does not delay Greenhouse (per-connection try/catch — by construction).
**Constraints**: No queue broker / Redis / second service (§IV); no new dependency (§III/C4); no tool download (C7); candidate PII + the Lever credential never logged (§VIII / FR-022 / SC-005); Lever Data API rate limit respected via the existing backoff (FR-020); Mongock changeset Java source pure-ASCII (the F30 binary-detection lesson).
**Scale/Scope**: MVP single-workspace scale; ≤1 connection per (workspace, provider); the poll iterates all CONNECTED connections across providers; per-poll page cap + per-drain batch cap (the F12 `Pageable` lesson).

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Gate | Question | Result |
|---|---|---|
| **C1** | Within MVP scope (spec §11)? | **PASS** — F41 Lever ATS is explicitly in §11 MVP and the constitution §I scope list ("ATS integrations — Greenhouse and Lever"). |
| **C2** | New service, queue, or replica? | **PASS** — none. Reuses the F40 `@Scheduled`+`SchedulerCheckpoint`+MongoDB-outbox subsystem. The `ChangeUnit019` index migration is *data*, not a topology change. No broker, no second service. |
| **C3** | Exposes candidate PII to unauthorized roles? | **PASS** — connection/credential is Admin-only (FR-004) per provider; imported Lever candidates inherit the existing candidate RBAC and carry no new visibility surface (FR-028); HM-requisition scoping stays deferred to F51 (no candidate→requisition link exists — the F32/F40 precedent). Write-back/sync-run rows hold no PII; the new `provider` fields are enums. |
| **C4** | Dependency outside the fixed stack? | **PASS** — no new dependency. Lever via `RestClient`; all provider access behind `AtsConnector` (Dependency Policy). |
| **C5** | New/modified Windows scripts contain non-ASCII? | **PASS (N/A)** — no new `.ps1`/`.cmd`/`.bat`. `ci.yml` gains ASCII Lever sentinels. The Mongock `.java` source is held pure-ASCII (the F30 lesson). |
| **C6** | Multi-role sub-agent review (>=3 roles) scheduled? | **PASS** — plan-phase multi-role review (Backend/DevOps, Security/GDPR, QA) run now (below); a second loop scheduled at task close per the established cadence. |
| **C7** | Downloads any build tool / runtime / CLI? | **PASS** — none. Cached Gradle 9.4.0, local JDK, no new npm/Gradle dep. |

**Result: all gates PASS. No Complexity Tracking entries required.**

## Project Structure

### Documentation (this feature)

```text
specs/020-lever-ats/
├── plan.md              # This file
├── research.md          # Phase 0 — Lever API shape + the coexistence model changes
├── data-model.md        # Phase 1 — field/index deltas + ChangeUnit019
├── quickstart.md        # Phase 1 — run/demo both providers + isolation
├── contracts/
│   └── ats-api.md       # AtsConnector (unchanged) + provider-parameterized REST + Lever mapping
├── checklists/
│   └── requirements.md  # Spec quality checklist (done)
└── tasks.md             # Phase 2 — /speckit.tasks (NOT created here)
```

### Source Code (repository root)

```text
backend/src/main/java/com/cadence/
├── integration/
│   ├── AtsProvider.java                  # EXTEND enum { GREENHOUSE, LEVER }
│   └── LeverAtsClient.java               # NEW @Component impl — RestClient + JdkClientHttpRequestFactory, no SDK
│                                         #   (AtsConnector/AtsApiRetry/AtsApiClassifier/AtsApiException/
│                                         #    AtsCandidateRecord/AtsActivity/AtsFetchResult all REUSED unchanged)
├── domain/
│   ├── AtsWriteBack.java                 # EXTEND — add `provider` (routing key)
│   └── AtsSyncRun.java                   # EXTEND — add `provider` (per-provider status)
├── repository/
│   ├── AtsConnectionRepository.java      # CHANGE — findByWorkspaceId Optional→List; ADD findByWorkspaceIdAndProvider
│   └── AtsSyncRunRepository.java         # ADD findFirstByWorkspaceIdAndProviderOrderByStartedAtDesc
├── service/
│   ├── AtsConnectionService.java         # CHANGE — connect/disconnect/health become (workspace,provider); listHealth(ws)
│   ├── AtsSyncService.java               # CHANGE — status-update filters {ws,provider}; reconcile uses conn.getProvider()
│   ├── AtsWriteBackService.java          # CHANGE — enqueue sets provider; claimAndDeliver routes by row.provider; +scoped cancel
│   └── AtsWriteBackInvalidator.java      # ADD cancelPendingForWorkspaceAndProvider(ws, provider)
├── scheduler/
│   └── AtsSyncScheduler.java             # UNCHANGED — already iterates findByStatus(CONNECTED) across providers
├── config/
│   ├── AtsProperties.java                # EXTEND — add Lever { base-url } nested (shared retry/poll/timeout reused)
│   └── (MongoPiiConfig.java)             # UNCHANGED — AtsConnection.apiKey + Candidate.atsStageLabel already registered
├── api/
│   ├── AtsConnectionController.java      # CHANGE — provider-parameterized paths + GET /connections (list both)
│   └── AtsDtos.java                      # CHANGE — HealthResponse list; provider in DTOs
└── config/migration/
    └── ChangeUnit019_AtsLeverMultiConnector.java  # NEW order "019" (pure ASCII) — index migration

backend/src/main/resources/application.yml   # ADD cadence.ats.lever.base-url

backend/src/test/java/com/cadence/ats/        # EXTEND test package
└── StubLever.java + LeverAtsClientTest + AtsMultiConnectorIT + AtsProviderIsolationIT
    + AtsLeverErasureIT + AtsIndexTest + AtsLogPiiScanTest + provider-parameterized contract/RBAC tests

frontend/src/app/features/admin/ats/
├── ats-integration.component.ts          # CHANGE — list + manage both providers
├── ats-integration.component.spec.ts     # CHANGE — two-provider Jasmine
└── ats.service.ts                        # CHANGE — methods take provider

.github/workflows/ci.yml                  # EXTEND — Lever sentinels + api.lever.co base-URL guard
```

**Structure Decision**: Existing web-app layout. The Lever adapter lives in the constitution-blessed `integration/` package behind `AtsConnector`; the coexistence refactor touches `service/`+`repository/`+`domain/`+`api/`; the Admin surface stays an internal screen (no §IX WCAG/Lighthouse gate — the F40/F50/F51 precedent).

## Architecture & Key Decisions

1. **`LeverAtsClient` joins the existing connector map by construction (FR-026/SC-009)**. Adding a `@Component implements AtsConnector` with `provider()==LEVER` makes it auto-join the `Map<AtsProvider,AtsConnector>` in `AtsSyncService`/`AtsWriteBackService`/`AtsConnectionService` (built from the injected `List<AtsConnector>`) with **zero change to those services' wiring** — the Dependency-Policy payoff. A structural test asserts no `com.cadence.service`/`scheduler` class references a Lever literal (the F22 `MailTransportSwapTest` constant-pool precedent). HTTP Basic with the key as username (the F40 `basic()` helper); parse-discipline minimization (FR-029); base URL guarded by CI grep.

2. **The connection model becomes `(workspace, provider)`-keyed — the load-bearing refactor (FR-001/FR-031)**. F40 is workspace-keyed everywhere. Changes: `AtsConnectionRepository.findByWorkspaceId` Optional→List + new `findByWorkspaceIdAndProvider`; `AtsConnectionService.connect/disconnect/health` take a provider and filter `{workspaceId, provider}`; a new `listHealth(workspaceId)` returns both providers (INTEGRATION_PENDING default for an absent one); `AtsSyncService.syncWorkspace` status-update filters become `{workspaceId, provider}` and `reconcile` uses `conn.getProvider()` (today hardcoded `GREENHOUSE` at 4 sites). **`ChangeUnit019`** migrates the unique index `{workspaceId}`→`{workspaceId, provider}` (no data back-fill — `provider` already on every row). This is the one schema change; FR-030's "no business-logic change" is about the orchestration, not the schema.

3. **Write-back routing is made provider-correct by carrying the provider on the row (FR-016/SC-013c)**. `AtsWriteBack` gains a `provider` field set at `enqueue` from `candidate.getAtsProvider()`; `claimAndDeliver` loads `findByWorkspaceIdAndProvider(row.workspaceId, row.provider)`. A Lever candidate's activity can only reach Lever and a Greenhouse candidate's only Greenhouse — structurally, even if the connector map were mis-registered. The `idempotencyKey` formula is unchanged (a candidate holds one provider → no cross-provider collision). **Two `{workspaceId}`-only filters in `AtsWriteBackService` are confused-deputy hazards under two connectors and MUST be provider-scoped (Security review)**: (a) the `claimAndDeliver` connection lookup (today `findByWorkspaceId(...).orElse(null)` — would throw with two rows); (b) the NEEDS_REAUTH flip on a Lever auth failure (today filters `{workspaceId}` only → could flip the **Greenhouse** connection NEEDS_REAUTH — a real FR-022/SC-014 violation) → add `.and("provider").is(row.provider)`.

4. **Provider isolation is (mostly) by construction (FR-012/FR-022/FR-020a/SC-014)**. `AtsSyncScheduler.sweep` already iterates `findByStatus(CONNECTED)` with a **per-connection try/catch**; a Lever failure flips only the Lever connection and records its own `AtsSyncRun`, while the Greenhouse iteration continues in the same scan. The write-back drain is per-row. **Decision**: keep the single `"ats-sync-scan"` checkpoint — per-connection iteration+isolation satisfies FR-012's "independent checkpoint" intent more simply than two schedules; separate per-provider checkpoint documents would be unjustified complexity (§I). Surfaced as a decision, not an omission.

5. **Per-provider status surface (FR-019/SC-011)**. `AtsSyncRun` gains a `provider` field (`recordRun` sets it from `conn.getProvider()`) + `findFirstByWorkspaceIdAndProviderOrderByStartedAtDesc` + a `{workspaceId, provider, startedAt:-1}` index. **`AtsConnectionService.health` MUST scope its dead-letter `count` to `{workspaceId, provider, status:DEAD_LETTER}` (Security review)** — today it counts both providers' dead-letters into every per-provider card / `degraded` badge. The controller exposes `GET /connections` (both providers' health) + provider-scoped `POST`/`DELETE`/`sync-status`/`dead-letters` at `/{provider}/...`; the F40 hardcoded-GREENHOUSE endpoints are migrated and the Angular `ats.service.ts` moves to the new paths. **`{provider}` validation is NOT automatic (Backend review)**: an unknown enum path-variable raises `MethodArgumentTypeMismatchException` (not `IllegalArgumentException`) → would hit the catch-all 500; bind `{provider}` as `String` and resolve-to-enum in the controller (throw `InvalidRequestException` → 400), or add an explicit `@ExceptionHandler`. **The F40 envelope returns 409 (not 401) for `verification_failed`** — contracts corrected. There is **no existing F40 controller contract test** — the 5-role provider-parameterized RBAC matrix (`AtsConnectionContractTest`) is net-new, not a migration.

6. **Erasure & disconnect become provider-scoped (FR-005/FR-015/SC-015)**. `disconnect(workspaceId, provider)` cancels only that provider's pending write-backs via the new `AtsWriteBackInvalidator.cancelPendingForWorkspaceAndProvider`; a coexisting Greenhouse queue is untouched. The resolve-then-active-state-guarded-write resurrection defense and the FR-008 cross-provider non-merge are **already correct** in `AtsSyncService.reconcile` (the email-adopt guard `atsExternalRef == null` blocks adoption of a candidate already keyed to any provider) — F41 only swaps the hardcoded provider literal. `CandidateErasureService.wipe` is unchanged (the candidate's single-provider pending rows are swept by the existing `cancelPendingForCandidate`).

7. **Stub & CI (FR-032/SC-005)**. `StubLever` is a JDK `HttpServer` sibling of `StubGreenhouse`, a JVM-lifetime singleton (do NOT `@AfterAll stop()`), seeding opportunities with `SENTINEL`-marked minimization fields and matching `POST /v1/opportunities/{id}/notes`. `ci.yml` gains Lever candidate-name/credential sentinels + an `api.lever.co` base-URL guard on `LeverAtsClient.java`. The F40 honest-residual `AtsLogPiiScanTest` + per-feature sentinel block is added now (closes the F40 follow-up). **Live-promotion gap (carried to the mandatory FR-032 security re-review)**: Lever's notes endpoint may require a `perform_as` user id (the F40 Harvest candidate-id-vs-application-id analogue); confirm before live credentials.

## Phase 0 — Research

See [research.md](./research.md). Resolves the Lever Data API shape (Basic auth, opportunities/notes endpoints, `expand=stage`, pagination cursor), the external-ref/posting/stage mapping, and — the substantive part — the connection-key migration (D3), write-back routing field (D4), provider-isolation-by-construction (D5), per-provider status (D6), provider-scoped erasure/disconnect (D7), and the stub/CI plan (D8). No NEEDS CLARIFICATION remain (the clarify session closed them).

## Phase 1 — Design & Contracts

- [data-model.md](./data-model.md) — the field deltas (`AtsWriteBack.provider`, `AtsSyncRun.provider`), the unchanged candidate fields, and `ChangeUnit019` (drop unique `{workspaceId}`, create unique `{workspaceId, provider}` + `atsSyncRuns {workspaceId, provider, startedAt:-1}`).
- [contracts/ats-api.md](./contracts/ats-api.md) — the unchanged `AtsConnector` interface, the provider-parameterized internal REST endpoints with role gates + no-oracle envelope, and the Lever Data API mapping the stub honors.
- [quickstart.md](./quickstart.md) — run the backend + `StubLever`/`StubGreenhouse`, connect both providers, observe import + provider-correct write-back + isolation + provider-scoped disconnect, and the test-run flags.
- Agent context updated via `update-agent-context.ps1 -AgentType claude`.

## Test Plan (carried to `/speckit.tasks`)

The named acceptance tests F41 must ship (each independent; QA review-driven). `StubLever` mirrors `StubGreenhouse` incl. its `gate(n)` latch for non-vacuous concurrency.

- **F40 fixture migration (compile-break, do FIRST)** — `AtsItBase`, `AtsConnectionIT` to the provider-arg signatures (`findByWorkspaceIdAndProvider`, `health(ws,provider)`, `disconnect(ws,provider)`); the Angular `ats.service.ts`/component + Jasmine to the provider paths.
- **`LeverAtsClientTest`** — parse/minimization (FR-029): seed `SENTINEL` `links`/`tags`/`sources`/`origin`/`headline`/EEO into the stub opportunity; assert none reaches the record; EEO endpoint never called. Classifier reuse (401/403→reauth, 429/5xx→transient).
- **`AtsMultiConnectorIT`** (both connectors, one workspace): **SC-013a** same external identity seeded in both stubs → exactly two records with distinct `(atsProvider, atsExternalRef)`, stable count on re-sync; **SC-013b** shared-email non-merge; **SC-013c** + idempotency-key cross-provider non-collision: a GH and a Lever write-back with otherwise-identical params → two rows, each routed to the correct provider; **SC-002** Lever burst-50 imported exactly once.
- **`AtsProviderIsolationIT`** — **SC-014**: `StubLever` erroring/timing-out while `StubGreenhouse` healthy → Greenhouse sync + write-back proceed; a Lever auth failure flips ONLY the Lever connection (the confused-deputy NEEDS_REAUTH test); **overlapping-sync** gated 2-thread test (one GH `syncWorkspace`, one Lever, via `gate(n)`) → no double-import/cross-merge/corruption.
- **`AtsBothProviderRestartIT`** — **SC-007**: interleave GH + Lever in-flight cycles, re-invoke the sweep/drain → no duplicate imports, one note per provider, no cross-provider corruption. Carry the F40/F31 honest-bound label (double-sweep idempotency proxy, not a true process restart).
- **`AtsLeverErasureIT`** — **SC-015**: erase a Lever candidate → ATS PII wiped, ref retained, re-poll is a guarded no-op (no resurrection); disconnect Lever cancels only Lever's pending write-backs, a coexisting Greenhouse queue untouched.
- **`AtsIndexTest` / migration IT** — **FR-031/SC-013**: with a pre-seeded GREENHOUSE connection present, assert (a) the old unique `{workspaceId}` index is gone, (b) a LEVER row for the same workspace inserts, (c) a second GREENHOUSE row is rejected by `{workspaceId,provider}` uniqueness.
- **`AtsConnectionContractTest`** (net-new MockMvc) — **FR-004**: 5 roles × {`GET /connections`, `POST`/`DELETE /{provider}/connection`, `GET /{provider}/sync-status`, `/{provider}/dead-letters`}; Admin-mutate / Recruiter-read / HM-Interviewer-Readonly-403; the unknown-`{provider}`→400 no-oracle case; 409 `verification_failed`.
- **`AtsLogPiiScanTest`** (net-new, closes the F40 residual) — **SC-005**: full connect→sync→write-back→**failure** flow with Lever name/credential sentinels; non-circular SENTINEL discipline.
- **Structural no-Lever-literal-in-service test** — **SC-009**: constant-pool scan asserts no `com.cadence.service`/`scheduler` class references a Lever URL/type.
- **`ci.yml`** — Lever candidate-name/credential sentinels + `api.lever.co` base-URL guard on `LeverAtsClient.java`; `CADENCE_ATS_LEVER_BASE_URL` env override.

## Multi-role review (constitution C6) — 2026-06-18 (plan phase)

Three role reviewers (Backend/DevOps, Security/GDPR, QA) reviewed the plan + design against the **real codebase**. **All three: APPROVE-WITH-NITS, zero architectural BLOCKERS.** The connection-key refactor scope, write-back routing fix, Mongock "019"-off-"018" ordering, scheduler-needs-no-change, and per-connection isolation were all verified accurate against source. Applied to the plan/design now:

- **Confused-deputy NEEDS_REAUTH flip (Security, the key finding)**: `AtsWriteBackService.claimAndDeliver`'s auth-failure flip filters `{workspaceId}` only — a Lever auth failure could flip the Greenhouse connection. Now an explicit provider-scoped edit (decision #3, data-model Δ3).
- **`health()` dead-letter count cross-provider (Security)**: must scope `{workspaceId, provider, …}` or each card shows both providers' failures (decision #5, data-model Δ3).
- **`{provider}` path validation → 500 not 400 (Backend)**: `MethodArgumentTypeMismatchException` isn't `IllegalArgumentException`; bind-as-String + resolve, or add a handler (decision #5, contracts §2).
- **`verification_failed` is 409 not 401 (Backend)**: contracts corrected to the real F40 handler status.
- **"F40 tests untouched" was overstated (Backend + QA BLOCKER)**: `AtsItBase`/`AtsConnectionIT` use the old signatures and are source-compat breaks → migrated as an explicit F41 task (quickstart + Test Plan corrected); the 5-role provider RBAC contract is net-new (no F40 contract test exists).
- **Test enumeration gaps (QA)**: SC-007 both-connector restart, SC-013a distinct-two-records, overlapping-sync gated concurrency, ChangeUnit019 pre-existing-data migration, idempotency cross-provider non-collision, Lever burst-50 — all now named in the Test Plan above.

**Carried to `/speckit.tasks`**: the Test Plan list is the authoritative enumeration; the FR-012/FR-033 single-checkpoint deviation is ratified in the spec Assumptions (a reviewed decision, not an omission).

## Complexity Tracking

No constitution gate failed; no entries required.

**Scope note (honest deferral, not a violation)**: Full Hiring-Manager → requisition scoping (FR-028's forward intent) remains **deferred to F51** (no candidate→requisition→assignment link exists — the F32/F40 precedent). F41 stores the Lever posting id/title denormalized and widens no role's visibility. Reported here, mirroring F40.

**Design note (honest reconciliation, not a violation)**: FR-012's "checkpoint independent of the Greenhouse sync" is implemented as one shared `"ats-sync-scan"` checkpoint iterating both providers with per-connection isolation (decision #4) — simpler than, and behaviourally equivalent to, two separate checkpoints for the isolation the spec requires. Surfaced so it is a reviewed decision.
