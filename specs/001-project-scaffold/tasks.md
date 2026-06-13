# Tasks: Project Scaffold & Build Pipeline

**Branch**: `001-project-scaffold`  
**Input**: Design documents from `specs/001-project-scaffold/`  
**Backlog refs**: F00, F00.1, F00.2  
**Sub-agent review**: (1) Pre-implementation — Completed 2026-06-13 (DevOps + Backend + QA); findings applied to plan.md, research.md, data-model.md, contracts/, and quickstart.md before this task list was generated. (2) Post-implementation — Completed 2026-06-13 (DevOps + Backend + QA) after the full test suite went green; high-value findings applied to the backend services, test suite, CI workflow, and docs; the remainder reported. See the implementation summary for the applied-vs-reported breakdown.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies on incomplete tasks)
- **[Story]**: Which user story this task belongs to (US1–US9 from spec.md)
- Exact file paths included in every task description

## Path Conventions

Web application layout per constitution §Stack & Deployment Constraints:
- Backend: `backend/src/main/java/com/cadence/`, `backend/src/test/java/com/cadence/`
- Frontend: `frontend/src/`
- CI: `.github/workflows/`
- Deploy config: repo root (`fly.toml`, `lighthouserc.json`)

---

## Phase 1: Setup — Repository & Project Initialization

**Purpose**: Create the bare project scaffolding before any source code is written. No application logic — only project structure and tooling.

- [X] T001 Initialize Gradle wrapper in `backend/` — run `gradle wrapper --gradle-version 8.x` (latest stable), confirm `backend/gradlew`, `backend/gradlew.bat`, `backend/gradle/wrapper/gradle-wrapper.properties` exist; verify `backend/gradlew` has LF line endings per `.gitattributes`
- [X] T002 Initialize Angular 17 project in `frontend/` — run `ng new cadence --routing --style=scss --standalone --no-create-application=false`; confirm `frontend/package.json`, `frontend/angular.json`, `frontend/tsconfig.json`, `frontend/src/` exist; confirm no NgModules were generated
- [X] T003 [P] Add Angular Material and CDK — `cd frontend && ng add @angular/material` (no animations prompt: Disabled); confirm `@angular/material` and `@angular/cdk` appear in `frontend/package.json`
- [X] T004 [P] Update `.gitignore` — add entries for `backend/build/`, `backend/.gradle/`, `frontend/node_modules/`, `frontend/dist/`, `backend/src/main/resources/application-local.yml`; confirm file has LF line endings
- [X] T005 Create backend source package skeleton — create empty directories: `backend/src/main/java/com/cadence/api/`, `config/migration/`, `domain/`, `repository/`, `scheduler/`, `service/`, `integration/`, `security/`; create `backend/src/test/java/com/cadence/health/`, `migration/`, `scheduler/`

---

## Phase 2: Foundational — Build Configuration & Spring Boot Skeleton

**Purpose**: Core build files and Spring Boot entry point that every subsequent phase depends on. All tasks here must complete before any user story phase begins.

**⚠️ CRITICAL**: No user story work can begin until this phase is complete.

- [X] T006 Write `backend/build.gradle` — declare all 6 libraries with exact artifact IDs and scopes from plan.md: `io.mongock:mongock-springboot-v3`, `io.mongock:mongodb-springdata-v4-driver` (NOT `mongock-mongodb-springdata-v4` — that artifact does not exist), `net.logstash.logback:logstash-logback-encoder:9.0` (pinned; BOM does not manage this), `org.springframework.boot:spring-boot-testcontainers` (test), `org.testcontainers:mongodb` (test), `org.testcontainers:junit-jupiter` (test); include Spring Boot starters: web, data-mongodb, actuator, security, scheduling, test; Java 21 toolchain; verify `./gradlew dependencies` resolves without error
- [X] T007 Write `backend/settings.gradle` — set `rootProject.name = 'cadence-backend'`; confirm it matches the Gradle project conventions
- [X] T008 Write `backend/src/main/resources/application.yml` — configure: `server.port=8080`, `server.shutdown=graceful`, `spring.lifecycle.timeout-per-shutdown-phase=30s`, `management.server.port=8081`, `management.endpoints.web.exposure.include=health,metrics`, `management.endpoint.health.show-details=always`, `management.endpoint.health.show-components=always`, `management.health.mongo.enabled=true`, `spring.data.mongodb.uri=${MONGODB_URI:mongodb://localhost:27017/cadence}` (env var with local fallback); NO secrets inline
- [X] T009 Write `backend/src/main/resources/application-test.yml` — set `management.server.port=18081` to avoid port conflicts in parallel test runs; leave MongoDB URI absent (Testcontainers @ServiceConnection overrides it)
- [X] T010 Write `backend/src/main/java/com/cadence/CadenceApplication.java` — `@SpringBootApplication`, `@EnableScheduling`, `@EnableMongock`; standard `SpringApplication.run()` main; verify `./gradlew compileJava` passes
- [X] T011 [P] Write `frontend/src/environments/environment.ts` — `export const environment = { production: false, apiBaseUrl: 'http://localhost:8080' };`
- [X] T012 [P] Write `frontend/src/app/app.config.ts` — standalone `ApplicationConfig` with `provideRouter([])`, `provideHttpClient()`, `provideAnimationsAsync()`; export as `appConfig`
- [X] T013 [P] Write `frontend/src/main.ts` — `bootstrapApplication(AppComponent, appConfig)`; create stub `frontend/src/app/app.component.ts` as a minimal standalone component with `<h1>Cadence</h1>` template (TypeScript compilation target only; no features)

**Checkpoint**: `./gradlew compileJava` and `ng build` both complete without errors before proceeding.

---

## Phase 3: User Story 1 — Working Local Development Environment (Priority: P1) 🎯 MVP

**Goal**: `./gradlew bootRun` starts the backend; `ng serve` starts the frontend. Developer can verify both are alive within 5 minutes of cloning.

**Independent Test**: `./gradlew bootRun` starts cleanly against a local `docker run -d mongo:7`; `curl http://localhost:8081/actuator/health` returns `{"status":"UP"}`. `ng serve` compiles and serves on port 4200 with zero TypeScript errors.

### Implementation for User Story 1

- [X] T014 [US1] Write `backend/src/test/java/com/cadence/health/ActuatorPortTest.java` — extends `BaseIntegrationTest` (created in Phase 4; write stub first); annotate `@SpringBootTest(webEnvironment = DEFINED_PORT)`; inject `@Value("${local.management.port}")` for management port; assert `GET http://localhost:{managementPort}/actuator/health` returns 200; assert `GET http://localhost:8080/actuator/health` returns non-200 (404 or 401); **write test first and confirm it FAILS before implementing**
- [X] T015 [US1] Write `backend/src/main/java/com/cadence/security/SecurityConfig.java` — define `@Bean @Order(1) SecurityFilterChain managementSecurityChain(HttpSecurity http)` matching `AntPathRequestMatcher("/actuator/**")` with `anyRequest().permitAll()` and `.csrf(csrf -> csrf.disable())`; add stub main chain `@Bean @Order(2)` with `anyRequest().authenticated()` placeholder; make `ActuatorPortTest` pass
- [ ] T016 [US1] Verify full local stack works end-to-end — start `docker run -d --name cadence-mongo -p 27017:27017 mongo:7`; run `./gradlew bootRun`; confirm health endpoint returns `{"status":"UP"}`; run `ng serve`; confirm Angular compiles and loads in browser at localhost:4200; stop docker container; confirm bootRun fails fast with connection error (not silent partial start)
  - NOTE (2026-06-13): NOT executed as a literal manual run. The equivalent was verified automatically: `ActuatorPortTest` confirms the management-port health endpoint returns 200/`UP` against a real (Testcontainers) MongoDB, and the frontend compiles + `ng test` passes (3/3). The manual `bootRun`/`ng serve` walkthrough remains for a human to run from a clean clone.

**Checkpoint**: Developer can bring up the full stack with two commands. `ActuatorPortTest` is green.

---

## Phase 4: User Story 2 — Automated Test Execution Without Cloud Dependencies (Priority: P1)

**Goal**: `./gradlew test` and `ng test --watch=false` both pass with zero cloud credentials or external services.

**Independent Test**: Run `./gradlew test` with no `MONGODB_URI` environment variable; all tests pass; no Docker containers remain running after the suite completes.

### Implementation for User Story 2

- [X] T017 [US2] Write `backend/src/test/java/com/cadence/BaseIntegrationTest.java` — abstract class annotated `@Testcontainers`, `@SpringBootTest`; declare `@Container @ServiceConnection static MongoDBContainer mongo = new MongoDBContainer("mongo:7")`; the `@ServiceConnection` annotation goes directly on the `@Container` field (Pattern A — NOT on a `@Bean` method); `spring-boot-testcontainers` on the test classpath (from T006) activates this annotation; add a `@Test void contextLoads()` and a MongoDB round-trip test: `mongoTemplate.save(...)` then `mongoTemplate.findById(...)` asserts document persisted; **confirm test FAILS without the Testcontainers dependency before verifying it passes**
- [X] T018 [US2] Verify `./gradlew test` passes with no cloud credentials — unset `MONGODB_URI` environment variable; run `./gradlew test`; assert test output shows `MongoDBContainer` starting and stopping; assert all tests pass; assert no containers remain after suite (`docker ps` is empty); run `ng test --watch=false` in `frontend/`; assert Jasmine suite passes in headless Chrome

**Checkpoint**: Full test suite runs offline. No external credentials needed.

---

## Phase 5: User Story 3 — Containerised Backend (Priority: P2)

**Goal**: `docker build` produces a runnable image; the container passes its health check; graceful shutdown works under in-flight load.

**Independent Test**: `docker build -t cadence-backend:local backend/` succeeds; `docker run -d -p 8080:8080 cadence-backend:local`; `docker exec <id> curl -sf http://localhost:8081/actuator/health` returns `{"status":"UP"}` within 60 s.

### Implementation for User Story 3

- [X] T019 [US3] Write `backend/src/test/java/com/cadence/health/GracefulShutdownTest.java` — extends `BaseIntegrationTest`; inject `WebServer` from `ServletWebServerApplicationContext`; issue an async HTTP request via `TestRestTemplate` to a slow endpoint (add a stub `GET /api/internal/slow` controller that sleeps 5 s); call `applicationContext.getBean(WebServer.class).stop()` on a background thread; assert the in-flight request completes and returns 200 before the context is fully closed; **write test first and confirm it FAILS before implementing**
- [X] T020 [US3] Add slow-endpoint stub for GracefulShutdownTest — write `backend/src/main/java/com/cadence/api/HealthTestController.java` annotated `@RestController @Profile("test")`; expose `GET /api/internal/slow` that `Thread.sleep(5000)` and returns 200; this controller MUST NOT exist in production profile
- [X] T021 [US3] Write `backend/Dockerfile` — multi-stage build: stage 1 (`builder`) FROM `eclipse-temurin:21-jdk-alpine`, copies `build.gradle`, `settings.gradle`, `gradlew`, `gradle/` then `src/`; runs `./gradlew bootJar --no-daemon`; stage 2 (`runner`) FROM `eclipse-temurin:21-jre-alpine`; creates non-root user `RUN addgroup -S cadence && adduser -S cadence -G cadence`; `USER cadence`; copies JAR from builder; `EXPOSE 8080 8081`; `HEALTHCHECK --interval=10s --timeout=5s --start-period=60s CMD curl -f http://localhost:8081/actuator/health || exit 1`; `ENTRYPOINT ["java", "-jar", "/app/cadence-backend.jar"]`; file MUST use LF line endings (enforced by `.gitattributes`)
- [ ] T022 [US3] Make GracefulShutdownTest pass — verify `application.yml` has `server.shutdown=graceful` and `spring.lifecycle.timeout-per-shutdown-phase=30s`; confirm `GracefulShutdownTest` passes green; run `docker build -t cadence-backend:local backend/` and verify build succeeds
  - NOTE (2026-06-13): `GracefulShutdownTest` is GREEN and the graceful-shutdown config is verified. `docker build` was NOT run: the Dockerfile's builder stage runs `./gradlew bootJar`, which would download the Gradle 9.4.0 distribution and pull the `eclipse-temurin` base images inside the container — prohibited by Constitution Principle X (Zero-Download Rule) in an implementation session. Run the container build at deploy time / in CI where downloads are expected.

**Checkpoint**: `docker build` succeeds; `GracefulShutdownTest` is green; graceful shutdown drains in-flight requests.

---

## Phase 6: User Story 4 — Backend Deployment to Production Host (Priority: P2)

**Goal**: `scripts\deploy-backend.ps1` deploys the backend to Fly.io and polls until healthy; all secrets managed via `fly secrets set`.

**Independent Test**: After `fly deploy`, the script polls `/actuator/health` via Fly proxy until 200 is returned or 5-minute timeout is exceeded (script exits non-zero on timeout).

### Implementation for User Story 4

- [X] T023 [US4] Write `fly.toml` at repo root — use Fly Machines-era format (NOT legacy Nomad `[[services]]` format); include: `[http_service]` with `internal_port = 8080`, `force_https = true`, `auto_stop_machines = false`, `auto_start_machines = false`; `[[http_service.checks]]` with `grace_period="60s"`, `interval="10s"`, `method="GET"`, `path="/actuator/health"`, `timeout="5s"`, `port=8081`; `kill_timeout = "35s"` (must exceed Spring's 30 s drain; default 5 s would SIGKILL mid-drain); `[build]` with `dockerfile = "backend/Dockerfile"` and `build-context = "backend"`; `[env]` block with non-secret config: `SERVER_PORT = "8080"`, `MANAGEMENT_SERVER_PORT = "8081"`, `SPRING_PROFILES_ACTIVE = "production"`; ZERO secrets inline — all credentials via `fly secrets set`
- [X] T024 [US4] Update `scripts\deploy-backend.ps1` — add post-deploy health poll after `fly deploy` completes: loop up to 30 times with 10 s sleep, call `fly proxy 8081:8081` + `curl http://localhost:8081/actuator/health`, break on 200; exit with code 1 if health check never passes (with clear error message "Deployment failed: backend not healthy after 5 minutes"); **Principle V verification**: run `grep -P '[\x80-\xFF]' scripts\deploy-backend.ps1` (expect zero matches); run `pwsh -NoProfile -File scripts\deploy-backend.ps1 -WhatIf` or syntax-check (`Get-Command` parse); record results in task notes; CRLF line endings required

**Checkpoint**: `fly.toml` is valid Machines-era format; `deploy-backend.ps1` passes Principle V scan and parse; post-deploy health poll logic is in place.

---

## Phase 7: User Story 5 — Automatic Frontend Deployment on Merge (Priority: P2)

**Goal**: Merging to `main` triggers Cloudflare Pages build with the correct `CADENCE_API_URL` baked into the Angular bundle for that environment.

**Independent Test**: Confirm `environment.prod.ts` is NOT committed with a real API URL; Cloudflare Pages build command pre-generates it from `CADENCE_API_URL` env var; inspect the built JS bundle for the URL value.

### Implementation for User Story 5

- [X] T025 [US5] Write `frontend/src/environments/environment.prod.ts` — placeholder URL only: `export const environment = { production: true, apiBaseUrl: 'https://api.cadence.example.com' };`; this file IS committed to source (placeholder only); the Cloudflare Pages build command overwrites it with the real URL at build time via a pre-build Node.js script; do NOT use `window.__CADENCE_API_URL__` or `APP_INITIALIZER` (resolved in research.md Decision 7 — Cloudflare Pages does not perform runtime HTML injection without a Worker)
- [X] T026 [US5] Update `angular.json` `fileReplacements` — add `{ "replace": "src/environments/environment.ts", "with": "src/environments/environment.prod.ts" }` under the `production` configuration; confirm `ng build --configuration production` compiles cleanly with the placeholder URL
- [X] T027 [US5] Update `quickstart.md` CI/CD secrets section — document the exact Cloudflare Pages build command: `node -e "const fs=require('fs'); fs.writeFileSync('src/environments/environment.prod.ts', 'export const environment = { production: true, apiBaseUrl: \"' + process.env.CADENCE_API_URL + '\" };');" && ng build --configuration production` (this overwrites `environment.prod.ts` with the real URL before Webpack runs); document the 3 required GitHub Actions secrets: `CLOUDFLARE_API_TOKEN`, `CLOUDFLARE_ACCOUNT_ID`, `FLY_API_TOKEN`; confirm quickstart output directory is `dist/cadence/browser` for Angular 17 without SSR, or `dist/cadence` if SSR is disabled

**Checkpoint**: `ng build --configuration production` succeeds; `environment.prod.ts` contains only a placeholder in source control; build command documented in quickstart.

---

## Phase 8: User Story 6 — Database Indexes Created Before Traffic / F00.1 (Priority: P2)

**Goal**: All 6 required indexes are created by Mongock at startup, idempotently, before the health endpoint reports healthy. Verified by `IndexBootstrapTest`.

**Independent Test**: Start application against fresh Testcontainers MongoDB; call `indexOps(collection).getIndexInfo()` for each of the 6 collections; assert all indexes present; restart Mongock against same container; assert no exception thrown.

### Implementation for User Story 6

- [X] T028 [US6] Write `backend/src/test/java/com/cadence/migration/IndexBootstrapTest.java` — extends `BaseIntegrationTest`; inject `MongoTemplate`; for each of the 6 collections in the index manifest, call `mongoTemplate.indexOps(collectionName).getIndexInfo()` and assert the expected index fields are present (do NOT use `mongoTemplate.executeCommand("{ listIndexes: ... }")` — use the typed API); add second test method that calls `mongockRunner.execute()` again against the same Testcontainers container and asserts no `MongoCommandException` is thrown (idempotency test; Mongock skips applied changesets via `mongockChangeLog`); **write test first and confirm it FAILS before implementing Mongock changeset**
- [X] T029 [P] [US6] Write `backend/src/main/java/com/cadence/domain/SchedulerCheckpoint.java` — `@Document(collection = "schedulerCheckpoints")`, fields from data-model.md: `@Id String id`, `String taskName`, `CheckpointStatus status`, `Instant startedAt`, `Instant completedAt`, `Instant missedFireReplayedAt`; also write `CheckpointStatus.java` enum with `RUNNING`, `COMPLETED`
- [X] T030 [P] [US6] Write `backend/src/main/java/com/cadence/domain/DeadLetterRecord.java` — `@Document(collection = "deadLetterRecords")`, fields from data-model.md: `@Id String id`, `String taskName`, `Instant failedAt`, `String errorType`, `String errorSummary`, `String affectedCandidateId`, `Instant alertSentAt`; all fields nullable except `taskName`, `failedAt`, `errorType`, `errorSummary`
- [X] T031 [US6] Write `backend/src/main/java/com/cadence/config/migration/ChangeUnit001_BootstrapIndexes.java` — annotate `@ChangeUnit(id = "001-bootstrap-indexes", order = "001", author = "system")`; inject `MongoTemplate`; use `mongoTemplate.indexOps(collection).createIndex(new Index().on(field, direction))` for each of the 6 indexes (use `createIndex` NOT `ensureIndex` — `ensureIndex` is deprecated in Spring Data MongoDB 4.5 with `forRemoval=true`); implement all 6 indexes: `interviews { scheduledAt:1, confirmationStatus:1 }`, `candidates { workspaceId:1, lastContactAt:1 }`, `feedbackRequests { interviewEventId:1, submittedAt:1 }`, `schedulingTokens { token:1 } unique`, `auditLog { candidateId:1, occurredAt:-1 }`, `schedulerCheckpoints { taskName:1 } unique`
- [X] T032 [US6] Configure Mongock in `application.yml` — add `mongock.migration-scan-package=com.cadence.config.migration`; run `./gradlew test`; confirm `IndexBootstrapTest` is green; confirm all 6 indexes present; confirm idempotency test passes

**Checkpoint**: `IndexBootstrapTest` is fully green. All 6 indexes created on first context load; idempotent on second load.

---

## Phase 9: User Story 7 — Structured Logs + Actuator Security + Dead-Letter / F00.2 (Priority: P2)

**Goal**: All logs are structured JSON; health/metrics endpoints are accessible on management port and inaccessible on public port (Spring Security aware); uncaught scheduler exceptions write a dead-letter record and send an alert with no PII.

**Independent Test**: `./gradlew test` stdout contains only JSON log lines; CI PII grep finds no email patterns; `DeadLetterTest` and `ActuatorPortTest` both pass.

### Tests for User Story 7

- [X] T033 [US7] Write `backend/src/test/java/com/cadence/scheduler/DeadLetterTest.java` — extends `BaseIntegrationTest`; use `@MockitoBean` to capture `NoOpEmailSender.sendSystemAlert` calls; trigger an uncaught exception from a test `@Scheduled` method (use a spy or a configurable `ScheduledTaskExceptionHandler`); assert `deadLetterRecords` collection has one document; assert `sendSystemAlert` was called once with `taskName` and `errorSummary` arguments; assert `errorSummary` does NOT match email regex `[a-zA-Z0-9._%+\-]+@[a-zA-Z0-9.\-]+\.[a-zA-Z]{2,}`; assert `affectedCandidateId` (if set) is a 24-char hex string, not an email; **write test first and confirm it FAILS**

### Implementation for User Story 7

- [X] T034 [US7] Write `backend/src/main/resources/logback-spring.xml` — default profile: `LogstashEncoder` to a file appender (JSON output to `logs/cadence.log`); `<springProfile name="test">` section: add a `ConsoleAppender` with `LogstashEncoder` so `./gradlew test` stdout contains JSON log lines (required for CI PII grep); `<springProfile name="!test">` section: console appender with `LogstashEncoder` for dev; confirm no PII fields appear in sample log output by reviewing encoder configuration
- [X] T035 [P] [US7] Define `backend/src/main/java/com/cadence/integration/EmailSender.java` interface — two methods: `void sendEmail(String toInternalId, String templateId, Map<String, String> mergeFields)` and `void sendSystemAlert(String taskName, String errorSummary)`; no PII in method signatures (no `emailAddress` parameter — callers use internal IDs)
- [X] T036 [P] [US7] Implement `backend/src/main/java/com/cadence/integration/NoOpEmailSender.java` — `@Component @Primary`; `sendSystemAlert` logs at ERROR level using `StructuredArguments.kv("taskName", taskName)` (never interpolating the summary as a string — use structured arg to prevent accidental PII leakage); `sendEmail` is a no-op that logs at DEBUG; this bean is replaced by F22's real implementation
- [X] T037 [US7] Write `backend/src/main/java/com/cadence/repository/DeadLetterRepository.java` — `MongoRepository<DeadLetterRecord, String>`; no custom queries needed at scaffold stage
- [X] T038 [US7] Write `backend/src/main/java/com/cadence/scheduler/DeadLetterService.java` — `recordFailure(String taskName, Throwable ex, String candidateId)`: (1) sanitise `ex.getMessage()` by replacing any substring matching email regex with `[REDACTED]`; (2) build `DeadLetterRecord` with sanitised summary; (3) save via `DeadLetterRepository`; (4) call `emailSender.sendSystemAlert(taskName, sanitisedSummary)`; (5) on alert success, set `alertSentAt` and save again; any exception in step 4–5 is caught and logged — dead-letter write is never aborted by alert failure
- [X] T039 [US7] Make `DeadLetterTest` and `ActuatorPortTest` pass — verify `SecurityConfig` `@Order(1)` chain covers `/actuator/**`; verify `logback-spring.xml` test profile emits JSON to console; confirm `./gradlew test` stdout contains parseable JSON log lines

**Checkpoint**: `DeadLetterTest` green; `ActuatorPortTest` green; stdout JSON logging confirmed; Spring Security does not block `/actuator/health` on management port.

---

## Phase 10: User Story 9 — Scheduler Checkpoint Infrastructure / F00.2 (Priority: P2)

**Goal**: Every `@Scheduled` task writes a checkpoint before doing work and marks it complete after. On startup, stale RUNNING checkpoints are replayed without duplicating work. Idempotency keys prevent duplicate notification dispatch.

**Independent Test**: `SchedulerCheckpointTest` passes both the WRITE path (RUNNING doc exists before task proceeds) and the REPLAY path (stale RUNNING doc triggers replay on `ApplicationReadyEvent` with no duplicate output).

### Tests for User Story 9

- [X] T040 [US9] Write `backend/src/test/java/com/cadence/scheduler/SchedulerCheckpointTest.java` — extends `BaseIntegrationTest`; **WRITE path test**: inject `SchedulerCheckpointService`; call `checkpointService.start("testTask")`; immediately query `mongoTemplate.findOne(Query.query(Criteria.where("taskName").is("testTask")), SchedulerCheckpoint.class)`; assert status is `RUNNING` and `startedAt` is set; call `checkpointService.complete("testTask")`; assert status is `COMPLETED`; **REPLAY path test**: insert a `SchedulerCheckpoint` document with `status=RUNNING` and `startedAt=Instant.now().minus(20, MINUTES)` directly via `mongoTemplate`; publish a synthetic `ApplicationReadyEvent` via `applicationContext.publishEvent(...)`; wait for async processing; assert `missedFireReplayedAt` is set on the checkpoint; assert a second call to the replay action does not insert a second work item (simulate idempotency key collision producing `DuplicateKeyException` that is swallowed gracefully); **write tests first and confirm they FAIL**

### Implementation for User Story 9

- [X] T041 [US9] Write `backend/src/main/java/com/cadence/repository/SchedulerCheckpointRepository.java` — `MongoRepository<SchedulerCheckpoint, String>`; add `Optional<SchedulerCheckpoint> findByTaskName(String taskName)`; add `List<SchedulerCheckpoint> findByStatusAndStartedAtBefore(CheckpointStatus status, Instant threshold)`
- [X] T042 [US9] Write `backend/src/main/java/com/cadence/scheduler/SchedulerCheckpointService.java` — `start(String taskName)`: atomic `MongoTemplate.findAndModify(query, update with status=RUNNING+startedAt=now, FindAndModifyOptions.options().upsert(true).returnNew(true), SchedulerCheckpoint.class)`; `complete(String taskName)`: `updateOne` setting `status=COMPLETED`, `completedAt=now`; `@EventListener(ApplicationReadyEvent.class) void replayMissedFires()`: query `findByStatusAndStartedAtBefore(RUNNING, Instant.now().minus(configurable threshold, default 15 min))`, for each result set `missedFireReplayedAt=now`, call the task's replay method (via a `Map<String, Runnable>` registry of task name → replay action)
- [X] T043 [US9] Write `backend/src/main/java/com/cadence/scheduler/ScheduledTaskExceptionHandler.java` — `@Aspect` or an `@Around` advice on `@Scheduled` methods; catches `Throwable`; calls `deadLetterService.recordFailure(taskName, ex, null)` (candidateId is null for task-level exceptions; task implementations pass candidateId if known); re-throws so Spring's scheduler sees the failure
- [X] T044 [US9] Make `SchedulerCheckpointTest` pass — wire `SchedulerCheckpointService` into `CadenceApplication`; verify `ApplicationReadyEvent` listener fires after context ready (not before); verify replay logic handles concurrent normal-fire + replay scenario without producing duplicate work items; confirm both test methods pass

**Checkpoint**: `SchedulerCheckpointTest` WRITE path and REPLAY path both green. Idempotency key collision handled gracefully.

---

## Phase 11: User Story 8 — CI Pipeline Enforces Quality Gates (Priority: P2)

**Goal**: GitHub Actions workflow runs 5 jobs on every PR/push; all quality gates are automated; no manual intervention required to confirm test results or Lighthouse scores.

**Independent Test**: Open a PR; confirm CI runs all 5 jobs; confirm Lighthouse job fails when `performance` threshold is lowered below the current score (tested by temporarily setting threshold to 100 and reverting).

### Implementation for User Story 8

- [X] T045 [US8] Write `lighthouserc.json` at repo root — target URL: `http://localhost:4200` (app root `/` — candidate-facing routes are phased in by F13, F30, F32); `collect.settings.formFactor: "mobile"` (NOT desktop — this was identified as a critical gap by QA sub-agent); `collect.settings.throttlingMethod: "simulate"`; `collect.settings.screenEmulation: { mobile: true, width: 375, height: 667, deviceScaleFactor: 2 }`; `assert.preset: "lighthouse:no-pwa"`; `assert.assertions: { "categories:performance": ["error", { "minScore": 0.85 }] }`; `upload.target: "temporary-public-storage"`
- [X] T046 [US8] Write `.github/workflows/ci.yml` — define 5 jobs:

  **Job 1: `backend-test`** (trigger: push + PR to any branch): checkout; setup Java 21; run `./gradlew test`; redirect stdout AND `backend/build/**/*.log` to `test-output.txt`; PII scan: `grep -P '[a-zA-Z0-9._%+\-]+@[a-zA-Z0-9.\-]+\.[a-zA-Z]{2,}' test-output.txt` — fail CI if any match found; non-ASCII scan: `grep -rP '[\x80-\xFF]' scripts/` — fail CI if any match found (Principle V gate, constitution C5); upload test results

  **Job 2: `frontend-test`** (trigger: push + PR): checkout; setup Node 20 LTS; `cd frontend && npm ci && ng test --watch=false --browsers=ChromeHeadless`; fail on any test failure

  **Job 3: `lighthouse`** (trigger: push + PR; depends on frontend-test): checkout; setup Node 20 LTS; `cd frontend && npm ci && ng build --configuration production`; start `npx http-server dist/cadence -p 4200 --silent &`; sleep 5; `npx lhci autorun`; fail CI if performance < 0.85

  **Job 4: `deploy-backend`** (trigger: push to `main` only; depends on backend-test): checkout; setup Fly CLI; run `scripts/deploy-backend.ps1` using `FLY_API_TOKEN` from GitHub Actions secrets; fail if health poll times out

  **Job 5: `deploy-frontend`** (trigger: push to `main` only; depends on frontend-test): checkout; setup Node 20 LTS; run pre-build Node script to write `environment.prod.ts` from `CADENCE_API_URL` secret; `ng build --configuration production`; `wrangler pages deploy dist/cadence --project-name cadence` using `CLOUDFLARE_API_TOKEN` and `CLOUDFLARE_ACCOUNT_ID` secrets

  All required secrets declared in workflow: `FLY_API_TOKEN`, `CLOUDFLARE_API_TOKEN`, `CLOUDFLARE_ACCOUNT_ID`, `CADENCE_API_URL`

**Checkpoint**: All 5 CI jobs run on push; backend and frontend tests block merge on failure; Lighthouse mobile gate blocks on sub-85 Performance; PII grep blocks on email pattern in logs; non-ASCII scan blocks on byte > 0x7F in any `scripts/` `.ps1` file.

---

## Phase 12: Polish & Cross-Cutting Concerns

**Purpose**: Principle V verification, end-to-end validation, and documentation cleanup.

- [X] T047 [P] Principle V scan — run `grep -rP '[\x80-\xFF]' scripts/deploy-backend.ps1 scripts/deploy-all.ps1 scripts/db-migrate.ps1 scripts/deploy-frontend.ps1` (expect zero matches for all); run `pwsh -NoProfile -Command "Get-Command -Syntax" > $null; . scripts/deploy-backend.ps1 -WhatIf 2>&1` to parse-check; if any `.ps1` files were created new in this feature, run the same scan on them; record result in plan.md Definition of Done C5 checkbox
- [X] T048 [P] Clean up `frontend/src/index.html` — confirm there is NO `window.__CADENCE_API_URL__` script block (the build-time injection approach does not use runtime injection); remove if present; confirm `ng build --configuration production` still compiles cleanly after any change
- [ ] T049 End-to-end local validation — follow `quickstart.md` steps exactly from a clean terminal (no existing processes); confirm all 7 steps complete within 15 minutes; note any step that fails or requires undocumented setup; update `quickstart.md` if any steps are wrong
  - NOTE (2026-06-13): NOT executed as a literal clean-terminal walkthrough. Component-level evidence gathered this session: `./gradlew test` (27/27 green, offline) and `ng test` (3/3 green) pass; structured JSON logs confirmed on stdout. The timed manual quickstart (incl. `bootRun`/`ng serve`/`docker build`) is left for a human, partly because `docker build` would trigger downloads barred by Principle X in-session.
- [X] T050 [P] Update `CLAUDE.md` — confirm the stack, commands, and code style sections reflect the final implemented state; add any implementation notes from this feature that future LLMs need to know (e.g., the Mongock changeset order convention, the Testcontainers Pattern A requirement, the `createIndex` vs `ensureIndex` note). Done 2026-06-13: corrected the Testcontainers note to the singleton pattern, the actuator SecurityFilterChain note, and added the Docker-Desktop-Windows `api.version` and mutable-map findings from this session.
- [X] T051 Multi-role sub-agent review verification — confirm all 10 critical findings and 15 non-critical findings from the 2026-06-13 review are addressed; check each DoD checkbox in `plan.md`; mark C6 gate complete in `plan.md` Constitution Check table

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — start immediately
- **Foundational (Phase 2)**: Requires Phase 1 complete — **BLOCKS all user story phases**
- **US1 (Phase 3)**: Requires Phase 2 — can start immediately after Foundational
- **US2 (Phase 4)**: Requires Phase 2 — can run in parallel with US1
- **US3 (Phase 5)**: Requires Phase 2 + US2 (inherits BaseIntegrationTest)
- **US4 (Phase 6)**: Requires Phase 2 + US3 (Dockerfile must exist before fly.toml is tested)
- **US5 (Phase 7)**: Requires Phase 2 — can run in parallel with US3/US4
- **US6 (Phase 8)**: Requires Phase 2 + US2 (IndexBootstrapTest extends BaseIntegrationTest)
- **US7 (Phase 9)**: Requires Phase 2 + US6 (DeadLetterRecord domain POJO created in US6)
- **US9 (Phase 10)**: Requires Phase 2 + US7 (DeadLetterService wired from US7)
- **US8 (Phase 11)**: Requires all other phases — CI gates cover all prior deliverables
- **Polish (Phase 12)**: Requires all phases complete

### User Story Dependencies

```
Phase 2: Foundational
│
├── Phase 3: US1 (P1) — independent after Foundational
├── Phase 4: US2 (P1) — independent after Foundational; provides BaseIntegrationTest for all later phases
│
├── Phase 5: US3 ──── depends on US2 (test base)
├── Phase 6: US4 ──── depends on US3 (Dockerfile)
├── Phase 7: US5 ──── independent after Foundational (frontend only)
│
├── Phase 8: US6 ──── depends on US2 (test base)
├── Phase 9: US7 ──── depends on US6 (domain POJOs: DeadLetterRecord)
├── Phase 10: US9 ─── depends on US7 (DeadLetterService)
│
└── Phase 11: US8 ─── depends on all above (CI gates cover all deliverables)
```

### Within Each Phase

Per constitution §VII: write test first → confirm it FAILS → implement → confirm it PASSES.

- Tests (marked FAILING first in task description) MUST be written and MUST FAIL before implementation begins
- Domain models before services
- Services before repositories where services drive the repository interface
- Core implementation before wiring into application context

---

## Parallel Execution Examples

### Phase 2 Parallelisable tasks (all create different files)

```
Parallel set A (backend config):
  T006: build.gradle
  T007: application.yml
  T008: application-test.yml
  T010: CadenceApplication.java

Parallel set B (frontend):
  T011: environment.ts
  T012: app.config.ts
  T013: main.ts + AppComponent stub
```

### Phase 8 Parallelisable tasks (domain models)

```
Parallel:
  T029: SchedulerCheckpoint.java + CheckpointStatus.java
  T030: DeadLetterRecord.java
```

### Phase 9 Parallelisable tasks

```
Parallel:
  T035: EmailSender.java interface
  T036: NoOpEmailSender.java implementation
```

---

## Implementation Strategy

### MVP First (US1 + US2 only)

1. Complete Phase 1: Setup
2. Complete Phase 2: Foundational
3. Complete Phase 3: US1 — local dev environment works
4. Complete Phase 4: US2 — tests run offline
5. **STOP and VALIDATE**: `./gradlew bootRun` + `./gradlew test` + `ng serve` + `ng test` all pass
6. The project is a working, testable scaffold — subsequent features can now be developed

### Full Scaffold Delivery Order

1. Phase 1 → Phase 2 (setup + build config)
2. Phase 3 + Phase 4 (P1 stories — local dev + tests)
3. Phase 5 + Phase 7 (container + frontend deployment — can parallel)
4. Phase 6 (backend deployment — after container)
5. Phase 8 (index bootstrap)
6. Phase 9 (dead-letter + logging)
7. Phase 10 (scheduler checkpoint)
8. Phase 11 (CI pipeline — last, it gates everything else)
9. Phase 12 (polish + validation)

---

## Notes

- **[P] tasks** = different files, no incomplete dependencies — can run in parallel
- **[US#] label** = maps directly to user story in spec.md for traceability
- **Principle V** (C5 gate): every `.ps1` file created or modified MUST pass `grep -P '[\x80-\xFF]'` (zero matches) AND `pwsh` parse before the task is marked done
- **Sub-agent findings already applied**: all 10 critical findings and 15 non-critical findings from the 2026-06-13 DevOps + Backend + QA review are reflected in these task descriptions — notably: corrected Mongock artifact ID, Testcontainers Pattern A with explicit `spring-boot-testcontainers` dep, `createIndex` not `ensureIndex`, Fly Machines-era `fly.toml` format with `kill_timeout=35s`, build-time Angular URL injection, pinned `logstash-logback-encoder:9.0`, logback test profile console appender, Spring Security `@Order(1)` FilterChain for Actuator, both SchedulerCheckpointTest paths, Lighthouse targeting `/` at scaffold stage with `formFactor: mobile`
- Stop at any checkpoint to validate the story independently before continuing
- Commit after each task or logical group; do not merge to `main` until all DoD items in `plan.md` are checked off
