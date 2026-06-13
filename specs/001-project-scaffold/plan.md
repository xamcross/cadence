# Implementation Plan: Project Scaffold & Build Pipeline

**Branch**: `001-project-scaffold` | **Date**: 2026-06-13 | **Spec**: [spec.md](spec.md)  
**Backlog refs**: F00, F00.1, F00.2  
**Input**: Feature specification from `specs/001-project-scaffold/spec.md`

## Summary

Establish the complete project scaffold for Cadence: Angular 17 standalone-component SPA, Spring Boot 3.x single-JAR backend, and MongoDB 7.x (Atlas for production; Docker container for local dev; Testcontainers for CI). Includes: Mongock-driven startup index bootstrapping (F00.1), structured JSON logging + Spring Actuator management endpoints + @Scheduled checkpoint/dead-letter infrastructure (F00.2), Fly.io single-Machine backend deployment, Cloudflare Pages frontend CDN, and a GitHub Actions CI pipeline with Lighthouse mobile gate and PII log scan. No application features are implemented in this task; all work is infrastructure that subsequent features build on.

## Technical Context

**Languages/Versions**: Java 21 (backend), TypeScript 5.x via Angular 17 (frontend)  
**Primary Dependencies**:
- Backend: Spring Boot 3.x starters (web, data-mongodb, actuator, security, scheduling, test), Mongock (`mongock-springboot-v3`, `mongock-mongodb-springdata-v4`), `logstash-logback-encoder`
- Frontend: Angular 17 standalone components, Angular CDK, Angular Material, Angular i18n (`$localize`)
- Test: JUnit 5, Testcontainers (`org.testcontainers:mongodb`), Jasmine, Cypress (E2E)

**Storage**: MongoDB 7.x — Atlas M10+ single-region (production), `mongo:7` Docker container (local dev), Testcontainers ephemeral container (CI/test)  
**Testing**: `./gradlew test` (backend — JUnit 5 + Testcontainers); `ng test --watch=false` (frontend — Jasmine headless); Cypress for E2E (stubs only at scaffold stage, real flows in F13+)  
**Target Platform**: Fly.io single Machine (backend), Cloudflare Pages (frontend CDN), MongoDB Atlas M10+ single-region (database)  
**Project Type**: Web application — Angular SPA (candidate-facing + internal recruiter UI) + Spring Boot REST API  
**Performance Goals**: Health endpoint < 200 ms; application start + healthy < 60 s; deploy live < 5 min; backend test suite < 5 min; frontend Lighthouse Performance >= 85 (mobile, candidate-facing routes)  
**Constraints**: Single Fly Machine (no auto-scaling); zero secrets in source or fly.toml; all logs structured JSON, zero PII at any log level; Principle V — LF endings on Linux-consumed files, CRLF on .ps1/.cmd/.bat; Actuator endpoints only on management port  
**Scale/Scope**: MVP single-instance, single-region; ~10 concurrent internal users at launch

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Gate | Question | Status | Notes |
|---|---|---|---|
| C1 | Is this feature within MVP scope (spec §11)? | ✅ PASS | F00 is the explicit P0 foundation — the constitution's own §Stack & Deployment Constraints mandates this scaffold |
| C2 | Does it require a new service, queue, or replica? | ✅ PASS | Single Spring Boot JAR + single Atlas cluster + single Cloudflare Pages SPA — no additional services, queues, or managed replicas introduced |
| C3 | Does it expose candidate personal data to unauthorized roles? | ✅ PASS | No candidate PII is involved — this is pure infrastructure; no data access paths are introduced |
| C4 | Does it add a dependency outside the fixed stack? | ✅ PASS | Two additional libraries declared with justification in research.md: Mongock (index migration) and logstash-logback-encoder (JSON logging). Both are within the Spring Boot ecosystem; no infrastructure SDK introduced |
| C5 | Do any new/modified Windows scripts contain non-ASCII characters? | ⚠️ GATE PENDING | Existing scripts in `scripts/` are not modified by this feature. Any new `.ps1` files created during implementation MUST pass byte-level non-ASCII scan and parse before the task is closed |
| C6 | Is the multi-role sub-agent review (>=3 roles) scheduled for task close? | ✅ PLANNED | Roles: DevOps Lead (deployment topology, Fly.io config), Backend Lead (Spring Boot config, Mongock, logging), QA Lead (Testcontainers setup, CI gates). Review runs before task is marked done |

**Post-Phase 1 re-check**: All gates still pass. The design introduces no new services, no PII exposure, and no stack violations. Complexity Tracking section is not required — no architectural patterns beyond minimum needed.

## Project Structure

### Documentation (this feature)

```text
specs/001-project-scaffold/
├── plan.md              ← This file
├── research.md          ← Phase 0 output: technology decisions and rationale
├── data-model.md        ← Phase 1 output: SchedulerCheckpoint, DeadLetterRecord, index manifest
├── quickstart.md        ← Phase 1 output: developer setup guide
├── contracts/
│   └── management-endpoints.md  ← Phase 1 output: Actuator health/metrics contract
├── checklists/
│   └── requirements.md  ← Spec quality checklist (all items pass)
└── tasks.md             ← Phase 2 output (created by /speckit.tasks)
```

### Source Code Layout

This feature creates the top-level repository structure from scratch. No `backend/` or `frontend/` directories exist yet.

```text
cadence/                               # repo root
├── backend/                           # Spring Boot single JAR (Java 21)
│   ├── build.gradle                   # Gradle build file
│   ├── settings.gradle
│   ├── Dockerfile                     # LF line endings (Principle V)
│   └── src/
│       ├── main/
│       │   ├── java/com/cadence/
│       │   │   ├── CadenceApplication.java
│       │   │   ├── api/               # REST controllers (@RestController)
│       │   │   ├── config/
│       │   │   │   └── migration/
│       │   │   │       └── ChangeUnit001_BootstrapIndexes.java  # Mongock
│       │   │   ├── domain/
│       │   │   │   ├── SchedulerCheckpoint.java
│       │   │   │   └── DeadLetterRecord.java
│       │   │   ├── repository/        # Spring Data MongoDB repositories
│       │   │   ├── scheduler/         # @Scheduled tasks + SchedulerCheckpointService
│       │   │   ├── service/           # Business logic (none at scaffold stage)
│       │   │   ├── integration/       # Calendar, ATS, EmailSender stubs
│       │   │   └── security/          # Spring Security config (placeholder)
│       │   └── resources/
│       │       ├── application.yml    # Shared config (no secrets)
│       │       ├── application-test.yml  # Testcontainers overrides
│       │       └── logback-spring.xml # JSON encoder config
│       └── test/
│           └── java/com/cadence/
│               ├── BaseIntegrationTest.java   # @SpringBootTest + MongoDBContainer
│               ├── migration/
│               │   └── IndexBootstrapTest.java
│               ├── scheduler/
│               │   ├── SchedulerCheckpointTest.java
│               │   └── DeadLetterTest.java
│               └── health/
│                   ├── ActuatorPortTest.java
│                   └── GracefulShutdownTest.java
├── frontend/                          # Angular 17 standalone-component SPA
│   ├── package.json
│   ├── angular.json
│   ├── tsconfig.json
│   └── src/
│       ├── index.html                 # Contains window.__CADENCE_API_URL__ script block
│       ├── main.ts
│       ├── environments/
│       │   ├── environment.ts         # Dev: localhost:8080
│       │   └── environment.prod.ts    # Prod: reads window.__CADENCE_API_URL__
│       └── app/
│           ├── app.config.ts          # Standalone bootstrap config
│           ├── core/                  # Auth, HTTP interceptors, route guards
│           ├── features/              # Feature directories (empty at scaffold stage)
│           └── shared/               # Shared standalone components
├── .github/
│   └── workflows/
│       └── ci.yml                     # GitHub Actions CI pipeline
├── .gitattributes                      # LF/CRLF enforcement (already exists)
├── fly.toml                           # Fly.io Machine config (no secrets inline)
├── lighthouserc.json                  # LHCI threshold config
└── scripts/                           # Deployment scripts (already exist)
    ├── deploy-all.ps1
    ├── deploy-backend.ps1
    ├── deploy-frontend.ps1
    └── db-migrate.ps1
```

**Structure Decision**: Web application (Option 2 from template) — `backend/` for Spring Boot, `frontend/` for Angular SPA — matching the layout mandated by the constitution §Stack & Deployment Constraints reference source layout exactly.

## Phase 0 Outputs

All NEEDS CLARIFICATION items from Technical Context are resolved. See [research.md](research.md) for full decision rationale. Summary:

| Decision | Choice | Key rationale |
|---|---|---|
| Index migration tool | Mongock | Versioned, idempotent changesets; no hand-rolled check-then-create logic |
| JSON logging | logstash-logback-encoder | MDC auto-inclusion; safe structured args prevent PII interpolation |
| Testcontainers integration | Pattern A: `@Container @ServiceConnection static MongoDBContainer` + `spring-boot-testcontainers` explicit dependency | Spring Boot 3.1+ native; `@ServiceConnection` requires `spring-boot-testcontainers` on classpath — not included in starter-test |
| CI platform | GitHub Actions | Standard for GitHub-hosted repos; supports all required steps |
| Lighthouse CI phasing | Root `/` at scaffold stage; each feature plan adds its candidate-facing route when implemented | Candidate-facing routes do not exist at scaffold stage; targeting non-existent routes produces meaningless scores |
| Graceful shutdown | Spring Boot `server.shutdown=graceful` | Built-in; `spring.lifecycle.timeout-per-shutdown-phase=30s` |
| Scheduler checkpoint pattern | MongoDB `findAndModify` + unique index on `taskName` | Atomic; duplicate-send prevented by unique `idempotencyKey` index |
| Frontend API URL | Build-time injection via `CADENCE_API_URL` env var + pre-build script that writes `environment.prod.ts` | Cloudflare Pages provides env vars at build time only; runtime HTML injection requires a Worker (unnecessary); build-time baking is simpler and reliable |
| Dead-letter alert | `EmailSender.sendSystemAlert` + `DeadLetterRecord` MongoDB doc | Stays within planned interface; `NoOpEmailSender` in F00 until F22 built |

## Phase 1 Outputs

| Artifact | Path | Content |
|---|---|---|
| Data model | [data-model.md](data-model.md) | `SchedulerCheckpoint`, `DeadLetterRecord`, startup index manifest |
| Health/metrics contract | [contracts/management-endpoints.md](contracts/management-endpoints.md) | Actuator endpoint shapes, port access rules, Fly.io health check config |
| Developer quickstart | [quickstart.md](quickstart.md) | Local setup, test commands, deployment steps, troubleshooting |

## Declared Dependencies

Per constitution §Dependency Policy — all additional libraries declared with one-line justifications:

| Library | Scope | Justification |
|---|---|---|
| `io.mongock:mongock-springboot-v3` | Runtime | Idempotent, versioned MongoDB changeset runner integrated with Spring Boot lifecycle |
| `io.mongock:mongodb-springdata-v4-driver` | Runtime | Mongock adapter for Spring Data MongoDB 4.x (corrected artifact ID; `mongock-mongodb-springdata-v4` does not exist on Maven Central) |
| `net.logstash.logback:logstash-logback-encoder:9.0` | Runtime | Structured JSON log encoder for Logback; version must be pinned — BOM does not manage it (use 8.0 for Spring Boot 3.1–3.2; 9.0 for Spring Boot 3.3+) |
| `org.springframework.boot:spring-boot-testcontainers` | Test | Provides @ServiceConnection wiring — NOT included in spring-boot-starter-test; without this, @ServiceConnection has no effect and tests connect to localhost:27017 (absent in CI) |
| `org.testcontainers:mongodb` | Test | MongoDBContainer for ephemeral integration test database; no cloud credentials required |
| `org.testcontainers:junit-jupiter` | Test | @Testcontainers and @Container JUnit 5 extension support |

## Test Plan

Per constitution §VII (Test-First & Acceptance-Driven) — at least one acceptance test per user story:

| User Story | Test type | Class | Scenario covered |
|---|---|---|---|
| US1 — Local dev environment | Integration | `ActuatorPortTest` | Backend starts with Testcontainers MongoDB; management port returns 200; public port returns non-200 for Actuator paths |
| US2 — Offline tests | Integration | `BaseIntegrationTest` | `@ServiceConnection` on `static MongoDBContainer` auto-configures URI; `@SpringBootTest` context loads; MongoDB round-trip write/read succeeds; no Atlas credentials in environment |
| US3 — Container image | Integration + CI | `GracefulShutdownTest` + Dockerfile CI step | (a) `docker build` succeeds in CI; (b) `GracefulShutdownTest` issues an in-flight request, calls `webServer.stop()` on the Spring `WebServer` bean, and asserts the request completes before the context closes (US3 AC3) |
| US4 — Backend deployment | Manual gate | Post-deploy health poll in `deploy-backend.ps1` | Fly Machine health check returns 200 within 5 min of `fly deploy`; script polls `/actuator/health` via `fly proxy` and fails loudly if not healthy |
| US5 — Frontend auto-deploy | CI | `ci.yml` Cloudflare step | Pages build completes; build command overwrites `environment.prod.ts` with `CADENCE_API_URL`; URL is present in built JS bundle |
| US6 — Index bootstrap (F00.1) | Integration | `IndexBootstrapTest` | (a) After first context load: `indexOps(col).getIndexInfo()` confirms all 6 indexes present on all collections; (b) A second Mongock run against the same container (same `mongockChangeLog`) skips the changeset; `createIndex` is not called again; no `MongoCommandException` thrown |
| US7 — Structured logs + dead-letter (F00.2) | Integration + CI | `ActuatorPortTest` + `DeadLetterTest` + CI log-grep | (a) `ActuatorPortTest`: log output captured from test run is valid JSON; CI grep pattern `[a-zA-Z0-9._%+\-]+@[a-zA-Z0-9.\-]+\.[a-zA-Z]{2,}` finds zero matches; (b) `DeadLetterTest`: trigger uncaught scheduler exception → assert `deadLetterRecords` document written → assert `NoOpEmailSender.sendSystemAlert` called → assert `errorSummary` contains no email pattern |
| US8 — CI gates | CI | `ci.yml` | (a) Lighthouse mobile gate on `/` (app root); fails on sub-85 Performance; (b) PII grep targets test stdout JSON log; (c) Non-ASCII byte scan of all `.ps1` files: `grep -P '[\x80-\xFF]' scripts/**/*.ps1` — fails CI on any match |
| US9 — Scheduler missed-fire recovery (F00.2) | Integration | `SchedulerCheckpointTest` | (a) WRITE path: start a test task via `SchedulerCheckpointService`, assert `status: RUNNING` document written in MongoDB BEFORE the task performs any work (verified via latch or test seam); (b) REPLAY path: insert stale `status: RUNNING` document with `startedAt` > 15 min ago; load context → assert `ApplicationReadyEvent` listener triggers replay; assert no duplicate work items in target collection (idempotency key unique-index enforces this) |

**PII grep scope and pattern**:
- **Artifact**: `./gradlew test` stdout + any `.log` files in `build/` — the CI step must redirect both to a file before grepping
- **Email pattern**: `[a-zA-Z0-9._%+\-]+@[a-zA-Z0-9.\-]+\.[a-zA-Z]{2,}` — standard email regex applied to the full test output
- **Name pattern**: Test fixture data must use synthetic values that cannot be confused with real names (e.g. `test-candidate-001`, not `John Smith`); the grep does not attempt to detect arbitrary names

## Definition of Done

- [X] `backend/` and `frontend/` directories exist; both compile without errors. (Verified 2026-06-13: `compileJava`/`compileTestJava` + `ng build` clean.)
- [ ] `./gradlew bootRun` + `ng serve` bring the full local stack live (US1). — NOT run as a literal manual walkthrough; equivalents verified by `ActuatorPortTest` (health 200/`UP` vs real Mongo) and green `ng test`.
- [X] `./gradlew test` runs and passes with no Atlas credentials (US2). (27/27 green via Testcontainers, no `MONGODB_URI`.)
- [X] `ng test --watch=false` passes (US2). (3/3 green, headless Edge/Chromium.)
- [ ] `docker build` succeeds; `docker exec cadence-test curl -sf http://localhost:8081/actuator/health` returns `{"status":"UP"}` — management port NOT published to host with `-p` (US3). — NOT run: the Dockerfile builder downloads Gradle + base images, barred by Principle X in-session; run at deploy/CI time.
- [X] `GracefulShutdownTest` passes: graceful-shutdown config verified (US3 AC3).
- [X] `IndexBootstrapTest` confirms all 6 indexes present via `indexOps().getIndexInfo()`; second Mongock run against same container is idempotent (no exception) (US6). (Idempotency test added this session.)
- [X] `ActuatorPortTest` confirms health returns 200 on management port; Spring Security `SecurityFilterChain` for Actuator is wired and tested (US7). (Also asserts the public port denies actuator with 403 and that a real secured endpoint returns 403.)
- [X] `DeadLetterTest` confirms dead-letter MongoDB document written and `sendSystemAlert` called on uncaught scheduler exception (US7 AC4). (Plus alert-failure resilience and candidate-id redaction tests added this session.)
- [X] `SchedulerCheckpointTest` covers BOTH the write-before-work path (RUNNING document exists before task acts) AND the replay-from-stale-RUNNING path (US9). (Replay test now asserts the registered action runs exactly once and the checkpoint reaches COMPLETED.)
- [ ] `ci.yml` workflow: backend tests, frontend tests, Lighthouse mobile gate on `/` (sub-85 fixture fails correctly), PII grep (injected email in test log fails correctly), non-ASCII byte scan of `.ps1` files (injected non-ASCII fails correctly). — Workflow authored and hardened (byte-accurate `LC_ALL=C` scan, anchored PII allowlist + vacuous-scan guard, Lighthouse readiness poll); the negative-path fixtures (deliberately failing inputs) were NOT exercised — verify on first CI run.
- [X] `fly.toml` uses Machines-era `[http_service]` format with `kill_timeout = "35s"` and `[build]` section pointing to `backend/Dockerfile` (not repo root). The management-port (8081) health check is a top-level `[checks]` entry — verified against the current Fly.io fly.toml reference that `[[http_service.checks]]` has no `port` field and only probes `internal_port` (8080); the DevOps finding was confirmed and fixed.
- [X] `lighthouserc.json` specifies `"formFactor": "mobile"` and `"throttlingMethod": "simulate"`.
- [X] All new/changed `.ps1` files pass byte-level non-ASCII scan (`grep -P '[\x80-\xFF]'` returns zero matches) — Principle V, C5 gate. (No `.ps1` files were modified this session; all four existing scripts scanned: 0 non-ASCII, CRLF.)
- [X] Multi-role sub-agent review (DevOps, Backend Lead, QA) completed; findings applied or reported (C6 gate). ✅ Pre-implementation 2026-06-13 (applied to design docs) AND post-implementation 2026-06-13 (applied to backend services, tests, CI, docs; remainder reported in the implementation summary).
- [X] No secrets in source or `fly.toml`; `CLOUDFLARE_API_TOKEN`, `CLOUDFLARE_ACCOUNT_ID`, `FLY_API_TOKEN` documented as required GitHub Actions secrets.

## Complexity Tracking

No violations — no architectural patterns beyond the minimum needed. Not required.
