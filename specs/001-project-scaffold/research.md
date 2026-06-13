# Research: Project Scaffold & Build Pipeline

**Feature**: 001-project-scaffold  
**Phase**: 0 — Outline & Research  
**Date**: 2026-06-13

All technology choices in this feature are constrained by the constitution (§III Fixed Technology Stack, §IV Single-Instance Deployment Topology). Research focuses on confirming the best-practice configuration for each predetermined technology pairing and resolving the two open decisions noted in the plan.

---

## Decision 1: Database Index Migration Tool

**Candidates**: Mongock (io.mongock:mongock-springboot-v3) vs. Spring Data `IndexOperations` in `@PostConstruct`

**Decision**: **Mongock**

**Rationale**:
- Mongock stores a changelog in MongoDB (`mongockChangeLog` collection) so each changeset runs exactly once — idempotency is enforced at the persistence layer, not by hand-rolled "check-then-create" logic.
- Changesets are ordered, versioned, and rollback-safe; each future feature can add a new `@ChangeUnit` without touching existing changesets.
- Mongock integrates with Spring Boot 3.x via `mongock-springboot-v3` + `mongodb-springdata-v4-driver` (note: NOT `mongock-mongodb-springdata-v4` which does not exist on Maven Central); no additional configuration server is required.
- Spring Boot 3.x's `@ServiceConnection` support for Testcontainers means integration tests start an ephemeral MongoDB container; Mongock's changesets run against it on startup, confirming index creation in the test cycle without Atlas credentials.

**Alternatives considered**:
- `@PostConstruct` with `IndexOperations.ensureIndex(...)` — simpler (no new dependency) but requires manually checking for existing indexes, produces no migration history, and becomes unmanageable once many features add indexes.
- Mongock's own test starter (`mongock-test`) — evaluated but not needed; Testcontainers + the real Mongock changeset is the correct integration test path.

**Dependency declaration** (constitution §Dependency Policy):
- `io.mongock:mongock-springboot-v3` — idempotent, versioned MongoDB changeset runner integrated with Spring Boot lifecycle
- `io.mongock:mongodb-springdata-v4-driver` — Mongock driver adapter for Spring Data MongoDB 4.x (correct artifact ID; `mongock-mongodb-springdata-v4` does not exist)

---

## Decision 2: Structured JSON Logging Library

**Candidates**: `net.logstash.logback:logstash-logback-encoder` vs. custom Logback `PatternLayout` vs. Logback's built-in `JsonLayout`

**Decision**: **logstash-logback-encoder**

**Rationale**:
- Produces RFC-compliant JSON log entries (timestamp, level, logger, thread, message, MDC fields) with no configuration beyond adding `<encoder class="net.logstash.logback.encoder.LogstashEncoder"/>` to `logback-spring.xml`.
- MDC (Mapped Diagnostic Context) fields (e.g., `requestId`, `candidateId` as an internal UUID — never email or name) are emitted automatically alongside each log entry.
- Built-in support for structured `StructuredArguments` allows key-value pairs in log messages without string interpolation — making it impossible to accidentally embed a PII field value in a formatted string.
- CI log-grep for PII patterns works reliably against JSON output (the field names are predictable; a grep for `"email"` or `"name"` will catch accidental PII logging even in structured entries).
- `Logback's built-in JsonLayout` requires additional configuration for correct field naming and lacks MDC auto-inclusion.

**Version pinning required** (Spring Boot BOM does not manage this artifact):
- Spring Boot 3.3+ ships Logback 1.5.x → use `logstash-logback-encoder:9.0` (requires Logback >= 1.5, Java 17+)
- Spring Boot 3.1–3.2 ships Logback 1.4.x → use `logstash-logback-encoder:8.0`
- Version `7.x` is incompatible with Spring Boot 3.x and MUST NOT be used.
- Implementer must confirm the exact Spring Boot patch version and declare the version explicitly in `build.gradle`; BOM will not supply it.

**Test profile note**: `logback-spring.xml` must include a `<springProfile name="test">` console JSON appender block so that `./gradlew test` stdout contains the structured JSON log output for the CI PII scan to grep. Without it, only a file appender is active during tests and the grep targets empty output.

**Dependency declaration**:
- `net.logstash.logback:logstash-logback-encoder:9.0` (Spring Boot 3.3+) or `:8.0` (Spring Boot 3.1–3.2) — structured JSON log encoder for Logback; required for machine-parseable logs and PII-safe MDC field handling

---

## Decision 3: Testcontainers + Spring Boot 3.1+ Integration

**Decision**: `@ServiceConnection` on a `@Container static MongoDBContainer` field in a shared `BaseIntegrationTest` base class

**Pattern** (Pattern A — JUnit 5 field; chosen over the `@Bean` pattern for simplicity):
```java
@Testcontainers
@SpringBootTest
public abstract class BaseIntegrationTest {

    @Container
    @ServiceConnection
    static MongoDBContainer mongo = new MongoDBContainer("mongo:7");
}
```
- `@ServiceConnection` is placed directly on the `@Container`-annotated `static` field, not on a `@Bean` method. The two patterns are mutually exclusive; this plan uses Pattern A.
- `static` field + `@Testcontainers` means the container starts once per test JVM (not once per class), keeping total test suite time under the 5-minute target.
- `@ServiceConnection` auto-configures `spring.data.mongodb.uri` from the container's mapped port — no `@DynamicPropertySource` or `application-test.yml` URI override required.
- For the index creation verification test, the test uses `mongoTemplate.indexOps("collectionName").getIndexInfo()` (returns `List<IndexInfo>`) — NOT `mongoTemplate.executeCommand("{ listIndexes: ... }")` which requires manual Document parsing.

**Additional test dependencies required** (`spring-boot-starter-test` does NOT include these; they must be declared explicitly):
- `org.springframework.boot:spring-boot-testcontainers` — provides `@ServiceConnection` binding support
- `org.testcontainers:mongodb` — `MongoDBContainer` implementation
- `org.testcontainers:junit-jupiter` — `@Testcontainers` and `@Container` annotations

---

## Decision 4: CI Platform

**Decision**: **GitHub Actions**

**Rationale**:
- The repository is assumed to be hosted on GitHub (standard for this stack; no alternative CI platform specified in the constitution or backlog).
- GitHub Actions supports all required CI steps: Gradle build + test, Angular build + test, Lighthouse CI via `lhci/github-action`, log-grep scan via `grep`/`ripgrep` shell step, and Cloudflare Pages deployment trigger.
- The LHCI (Lighthouse CI) action runs Lighthouse against a locally-served Angular production build using `@lhci/cli autorun`.

**Lighthouse CI configuration**:
- At scaffold stage, candidate-facing routes (`/schedule/:token`, `/status/:token`, `/feedback/:token`) do not exist in the Angular app. Running LHCI against a 404 produces a trivially-passing or trivially-failing score with no meaningful DOM content.
- **Phased gate**: The scaffold CI step targets only the app root (`/`) using `formFactor: mobile` to validate that the Angular build pipeline itself works and that the root serves content. `lighthouserc.json` sets `"formFactor": "mobile"`, `"throttlingMethod": "simulate"`, and `"performance": 85`.
- When F13/F14 introduce the `/schedule/:token` route, `lighthouserc.json` is updated to add that route. Same for F30 (`/status/:token`) and F32 (`/feedback/:token`). Each feature plan is responsible for updating the LHCI target list.
- CI serves the built `dist/` directory via `npx http-server` — NOT `ng serve` (which is dev-only and not representative of the production build). If Angular routes are not mapped as redirects in `http-server`, the SPA router will not match deep paths; the root (`/`) path is always safe.
- On fail, the CI step exits non-zero and blocks merge.

**Assumption**: If the repository is not hosted on GitHub, the CI workflow file (`.github/workflows/ci.yml`) must be replaced with the equivalent for the actual CI platform. All step logic remains identical.

---

## Decision 5: Graceful Shutdown Configuration

**Decision**: Spring Boot built-in graceful shutdown (`server.shutdown=graceful`)

**Rationale**:
- Spring Boot 2.3+ (and all of 3.x) supports graceful shutdown out of the box; setting `server.shutdown=graceful` in `application.yml` causes the embedded Tomcat to stop accepting new requests on `SIGTERM` and drain in-flight requests for up to the `spring.lifecycle.timeout-per-shutdown-phase` duration before exiting.
- `spring.lifecycle.timeout-per-shutdown-phase=30s` satisfies the 30-second drain requirement from the spec.
- Fly.io sends `SIGTERM` then waits for the grace period before force-killing with `SIGKILL`; the Fly Machine's `kill_timeout` must match the Spring Boot timeout to avoid premature kills.
- The integration test for graceful shutdown sends a `SIGTERM` to the embedded Tomcat via `webServer.stop()` while an in-flight request is in progress and asserts the request completes before the context closes.

**No additional dependency** — built-in Spring Boot 3.x capability.

---

## Decision 6: Scheduler Checkpoint Pattern

**Decision**: `SchedulerCheckpoint` MongoDB document with unique index on `taskName`; idempotency key via unique index on the notification record

**Pattern**:
1. At task start: `findAndModify({ taskName }, { $set: { status: "RUNNING", startedAt: now } }, upsert: true)` — atomic write.
2. Task performs work item by item; each item is guarded by an `idempotencyKey` unique index (`candidateId + eventType + scheduledAt`) on the target collection.
3. At task completion: `updateOne({ taskName }, { $set: { status: "COMPLETED", completedAt: now } })`.
4. On application startup (`ApplicationReadyEvent`): query all `SchedulerCheckpoint` documents with `{ status: "RUNNING", startedAt: { $lt: now - 15min } }` — these are missed fires. Replay each affected task immediately.
5. On uncaught exception in a `@Scheduled` method: `ExceptionHandler` intercepts, writes `DeadLetterRecord`, sends alert via `EmailSender.sendSystemAlert(taskName, errorSummary)` (no PII in payload).

**Rationale**: MongoDB `findAndModify` (or `findOneAndUpdate`) is atomic; concurrent task firings on restart are correctly serialized. The unique `idempotencyKey` index makes duplicate notification sends a no-op at the database layer — the duplicate `insert` throws a `DuplicateKeyException` which the task catches and counts as already-sent.

---

## Decision 7: Cloudflare Pages — Angular Environment Configuration

**Decision**: Build-time URL injection via Cloudflare Pages build environment variable + pre-build script that writes `environment.prod.ts`

**Problem with the previous approach**: The earlier draft described using `window.__CADENCE_API_URL__` set in `index.html` via Cloudflare Pages environment variable substitution. Cloudflare Pages does NOT perform runtime HTML substitution by default — environment variables set in the Pages dashboard are available only during the BUILD step (as standard shell env vars), not injected into served files at request time. A runtime injection would require a Cloudflare Pages Function (Worker), which is unnecessary complexity for MVP.

**Correct approach** (build-time, no Worker required):
1. `environment.prod.ts` is NOT committed with a real URL. It contains a safe placeholder: `export const environment = { production: true, apiBaseUrl: 'https://api.cadence.example.com' };`
2. The Cloudflare Pages **build command** prepends a Node.js one-liner that overwrites `environment.prod.ts` with the value of `CADENCE_API_URL` before `ng build` runs:
   ```
   node -e "const fs=require('fs'); fs.writeFileSync('src/environments/environment.prod.ts', 'export const environment = { production: true, apiBaseUrl: \"' + process.env.CADENCE_API_URL + '\" };');" && ng build --configuration production
   ```
3. Because Cloudflare Pages provides `CADENCE_API_URL` as a process environment variable during the build, each environment (production, preview) builds with its own URL baked into the JavaScript bundle.
4. No `window.__CADENCE_API_URL__`, no `APP_INITIALIZER`, no Worker. Angular reads the URL from the standard `environment` import in `HttpClient` configuration.

**Cloudflare Pages settings** (documented for setup):
```
Build command: node -e "..." && ng build --configuration production
Build output directory: dist/cadence/browser
Environment variables (per environment):
  - CADENCE_API_URL=https://api.cadence.example.com  (production)
  - CADENCE_API_URL=https://api-preview.cadence.example.com  (preview)
CI/CD secrets (GitHub Actions):
  - CLOUDFLARE_API_TOKEN  (for wrangler pages deploy in CI)
  - CLOUDFLARE_ACCOUNT_ID
```

---

## Decision 8: Dead-Letter Alert Mechanism

**Decision**: `EmailSender.sendSystemAlert(String taskName, String errorSummary)` — a dedicated method on the `EmailSender` interface (to be defined in F22) that sends to a configured `workspace.alertEmail` address.

**Rationale**: Keeps the alert mechanism within the existing planned interface without introducing a new notification channel. For F00 (before F22 is built), the implementation uses a `NoOpEmailSender` that logs the alert at ERROR level — the dead-letter MongoDB record is always written regardless of whether the email sends successfully, ensuring alerts are never silently lost.

**Dead-letter record structure** (no PII):
```json
{
  "taskName": "noShowConfirmationTask",
  "failedAt": "2026-06-13T10:00:00Z",
  "errorType": "NullPointerException",
  "errorSummary": "SchedulerCheckpoint not found for taskName",
  "affectedCandidateId": "507f1f77bcf86cd799439011"
}
```
`affectedCandidateId` is the internal MongoDB ObjectId — not an email address or name.

---

## Summary of Additional Dependencies

| Library | GroupId:ArtifactId | Scope | Justification |
|---|---|---|---|
| Mongock Spring Boot | `io.mongock:mongock-springboot-v3` | Runtime | Idempotent versioned MongoDB changeset runner |
| Mongock MongoDB driver | `io.mongock:mongodb-springdata-v4-driver` | Runtime | Mongock adapter for Spring Data MongoDB 4.x (corrected artifact ID) |
| Logstash Logback encoder | `net.logstash.logback:logstash-logback-encoder:9.0` | Runtime | Structured JSON log encoding, MDC auto-inclusion (pin version; BOM does not manage) |
| Spring Boot Testcontainers | `org.springframework.boot:spring-boot-testcontainers` | Test | Provides @ServiceConnection binding — NOT included in spring-boot-starter-test |
| Testcontainers MongoDB | `org.testcontainers:mongodb` | Test | MongoDBContainer for ephemeral test database |
| Testcontainers JUnit Jupiter | `org.testcontainers:junit-jupiter` | Test | @Testcontainers and @Container JUnit 5 support |

All other capabilities (graceful shutdown, @Scheduled, Spring Actuator, Spring Security, Angular CDK/Material) are provided by standard Spring Boot starters or Angular's included packages.
