# Cadence Development Guidelines

Auto-generated from all feature plans. Last updated: 2026-06-13

## Active Technologies

- **Backend**: Java 21, Spring Boot 3.3.5 (web, data-mongodb, actuator, security, scheduling, aop, test starters)
- **Frontend**: Angular 17 standalone components, Angular CDK/Material, Angular i18n (`$localize`)
- **Database**: MongoDB 7.x — Atlas M10+ single-region (production), `mongo:7` Docker (local dev), Testcontainers (CI/test)
- **Migration**: Mongock 5.4.4 (`mongock-springboot-v3` + `mongodb-springdata-v4-driver`)
- **Logging**: Logback + `logstash-logback-encoder:9.0` (structured JSON; zero PII at any level)
- **Testing**: JUnit 5 + Testcontainers, Jasmine, Cypress/Playwright (E2E)
- **Deployment**: Fly.io single Machine (backend), Cloudflare Pages (frontend CDN)
- **CI**: GitHub Actions — Gradle build/test, Angular build/test, Lighthouse CI (mobile, >= 85), PII log scan

## Project Structure

```text
backend/
  src/main/java/com/cadence/
    api/            # REST controllers
    config/
      migration/    # Mongock @ChangeUnit classes
    domain/         # MongoDB document POJOs
    repository/     # Spring Data MongoDB repositories
    scheduler/      # @Scheduled tasks + SchedulerCheckpointService
    service/        # Business logic
    integration/    # CalendarProvider / AtsConnector / EmailSender adapters
    security/       # Spring Security config
  src/main/resources/
    application.yml          # Shared config (no secrets)
    application-test.yml     # Testcontainers overrides
    logback-spring.xml       # JSON encoder config
  src/test/java/com/cadence/
    BaseIntegrationTest.java  # @SpringBootTest + MongoDBContainer (@ServiceConnection)

frontend/
  src/
    environments/  # environment.ts (dev) / environment.prod.ts (build-time URL injection via Node.js)
    app/
      core/        # Auth, HTTP interceptors, route guards
      features/    # Feature directories
      shared/      # Shared standalone components

.github/workflows/ci.yml   # CI pipeline
fly.toml                   # Fly.io Machine config (no secrets inline)
lighthouserc.json          # LHCI thresholds
scripts/                   # Deployment PS1 scripts (pure ASCII, CRLF)
specs/                     # Feature specs, plans, task lists
```

## Commands

```bash
# Local dev
./gradlew bootRun                     # Start backend (requires docker run mongo:7 first)
ng serve                              # Start frontend (from frontend/)

# Tests
./gradlew test                        # Backend — JUnit 5 + Testcontainers (no cloud creds)
ng test --watch=false                 # Frontend — Jasmine headless

# Deploy
scripts\deploy-all.ps1               # Full release (db-migrate + backend + frontend)
scripts\deploy-backend.ps1           # Backend only
scripts\deploy-frontend.ps1          # Frontend only
scripts\db-migrate.ps1               # Verify Atlas reachable
```

## Code Style

- **Backend**: Standard Spring Boot conventions; domain objects as `@Document` POJOs; repositories as Spring Data interfaces; no direct SDK references in service layer (use `CalendarProvider`, `AtsConnector`, `EmailSender` interfaces)
- **Frontend**: Angular standalone components only (no NgModules); use `$localize` for all user-facing strings; Angular Material for UI primitives
- **Logging**: Use MDC for correlation IDs; log only internal ObjectIds (never email, name, or phone); use `StructuredArguments` from logstash-logback-encoder to prevent PII interpolation
- **Secrets**: Never in source or `fly.toml`; always via `fly secrets set`
- **Line endings**: LF for Dockerfile/yml/conf/env files; CRLF for .ps1/.cmd/.bat (enforced by .gitattributes)
- **Scripts**: All .ps1 files MUST be pure ASCII — no em-dashes, curly quotes, or non-ASCII punctuation (Principle V)

## Implementation Notes (001-project-scaffold)

Critical patterns to follow in future features:

- **Mongock changeset order**: Use zero-padded three-digit order strings (`"001"`, `"002"`) in `@ChangeUnit(order = "...")`. IDs are persisted in `mongockChangeLog` — never rename or delete a changeset after it has been applied.
- **Testcontainers singleton pattern (REQUIRED for the multi-class suite)**: declare `@ServiceConnection static final MongoDBContainer mongo = new MongoDBContainer("mongo:7")` and start it once in a `static {}` block — do NOT use `@Testcontainers`/`@Container`. The `@Container` lifecycle stops and restarts the container per test class (new mapped port each time) while Spring caches the ApplicationContext by config, so a second class reusing a cached context points at a dead port and fails with `MongoTimeoutException`. The singleton starts once and Ryuk reaps it at JVM exit. Still requires the `spring-boot-testcontainers` dep (NOT in `spring-boot-starter-test`); `@ServiceConnection` works on the started static field without `@Container`.
- **Test data isolation (shared singleton container)**: because all test classes share ONE database, clean up in `@BeforeEach` with `mongoTemplate.remove(new Query(), Type.class)` (delete documents), NEVER `mongoTemplate.dropCollection(...)`. Dropping a collection also drops the Mongock-created indexes on it; Mongock will not re-create them (the changeset is already in `mongockChangeLog`), breaking `IndexBootstrapTest`.
- **`mongoTemplate.save(Map)` needs a MUTABLE map**: Spring Data injects the generated `_id` via `Map.put()`, so `Map.of(...)` (immutable) throws `UnsupportedOperationException`. Use `new HashMap<>()`.
- **Docker Desktop on Windows + Testcontainers**: Docker Desktop requires Docker API >= 1.40 but docker-java defaults to 1.32 ("client version 1.32 is too old"). Fix it in the Gradle `test` task with `systemProperty 'api.version', '1.41'` — docker-java reads the JVM system property `api.version`, NOT the `DOCKER_API_VERSION` env var (only the Go CLI honours that). Also set `environment 'DOCKER_HOST', 'npipe:////./pipe/docker_engine'`. The local JDK is `C:\jdk-24.0.1`; run `gradlew` with that as `JAVA_HOME` and `-Dorg.gradle.java.installations.auto-download=false` to honour the zero-download rule (Principle X).
- **MongoDB index creation**: Use the native MongoDB driver API via `mongoTemplate.getCollection(name).createIndex(new Document(...), new IndexOptions().unique(true))`. Do NOT use `mongoTemplate.indexOps(collection).createIndex(Index)` — the Spring Data 4.x `IndexOperations` interface does not have this method accessible in all contexts.
- **Mongock rollback**: Use targeted `dropIndex(new Document(...))` per index. Never call `dropIndexes()` — it destroys ALL indexes on the collection including those created by other changesets.
- **Management port + Spring Security**: `management.server.port=8081` runs actuator in a separate context, but defining ANY `SecurityFilterChain` bean makes Spring Boot's `ManagementWebSecurityAutoConfiguration` back off — so the main `authenticated()` rule then also blocks `/actuator/health` and it returns **403** (verified by `ActuatorPortTest`; the management port is NOT auto-isolated from the filter chain). `SecurityConfig` therefore defines an `@Order(1)` chain `securityMatcher("/actuator/**").anyRequest().permitAll()` plus an `@Order(2)` main `authenticated()` chain. Use the path matcher `"/actuator/**"`, NOT `EndpointRequest.toAnyEndpoint()`: the path matcher also matches on the public port so `/actuator/**` there is permitted and falls through to a 404 (the contract-required behaviour), whereas `EndpointRequest` matches nothing on the public port and lets the main `authenticated()` chain return 403. The management port is additionally network-isolated in production.
- **Dead-letter PII safety**: `DeadLetterService.recordFailure()` sanitises `errorSummary` AND `errorType` with the email regex `[a-zA-Z0-9._%+\-]+@[a-zA-Z0-9.\-]+\.[a-zA-Z]{2,}` (→ `[REDACTED]`), and replaces `affectedCandidateId` with `[REDACTED]` unless it is a bare 24-char ObjectId hex string. The safety guarantee must not depend on every caller being careful.
- **Frontend production URL**: `environment.prod.ts` is a source-controlled placeholder. The real API URL is injected at Cloudflare Pages build time via a Node.js one-liner that overwrites the file from `CADENCE_API_URL` env var (using `JSON.stringify` to prevent injection).
- **Spring Boot 3.3.x test mocks**: Use `@MockBean` from `org.springframework.boot.test.mock.mockito`. `@MockitoBean` was introduced in Spring Boot 3.4.0 and does NOT exist in 3.3.x.
- **Gradle**: Cached at `~/.gradle/wrapper/dists/gradle-9.4.0-bin/`. Always invoke the cached binary directly — never trigger a wrapper download.
- **Dockerfile JAR**: `bootJar { archiveFileName = 'cadence-backend.jar' }` + `jar { enabled = false }` ensures a single predictable JAR name for the multi-stage COPY.

## Recent Changes

- **2026-06-13** (001-project-scaffold): Initial scaffold — backend/ and frontend/ project structure, Mongock index bootstrap, scheduler checkpoint infrastructure, Actuator management endpoints, GitHub Actions CI pipeline with Lighthouse gate, multi-role sub-agent review applied (DevOps + Backend + QA)

<!-- MANUAL ADDITIONS START -->
<!-- MANUAL ADDITIONS END -->
