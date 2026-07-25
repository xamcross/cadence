# Cadence Development Guidelines

Manually maintained — a high-level architecture brief only. Feature-level detail lives in `specs/NNN-*/` and git history.

<!-- MANUAL ADDITIONS START -->

## Stack

- **Backend**: Java 21, Spring Boot 3.3.5 — web, data-mongodb, security (+ method security), actuator, aop, scheduling, oauth2-client, mail
- **Frontend**: TypeScript 5.4, Angular 17.3 — standalone components, Angular Material/CDK, `$localize` i18n
- **Database**: MongoDB 7.x — Atlas (prod), `mongo:7` Docker (local dev), Testcontainers (tests); Mongock migrations
- **Logging**: Logback + logstash-logback-encoder — structured JSON, zero PII at any level
- **Deployment**: Fly.io single Machine (backend) + Cloudflare Pages (frontend)
- **CI**: GitHub Actions — Gradle + Angular build/test, Lighthouse (mobile >= 85), PII log scan, SEO artifact scan

## Structure

```text
backend/src/main/java/com/cadence/
  api/           # REST controllers
  config/        # Spring config; migration/ holds Mongock changesets
  domain/        # MongoDB @Document POJOs
  repository/    # Spring Data repositories
  scheduler/     # @Scheduled sweeps
  service/       # business logic
  integration/   # provider adapters (calendar / ATS / email)
  security/      # Spring Security config
frontend/src/app/
  core/          # auth, HTTP interceptors, route guards
  features/      # feature directories
  shared/        # shared standalone components
scripts/         # build/deploy scripts (.mjs, .ps1)
specs/           # feature specs, plans, task lists
```

## Commands

```bash
./gradlew bootRun         # backend (requires docker run mongo:7 first)
ng serve                  # frontend (from frontend/)
./gradlew test            # backend tests (Testcontainers, no cloud creds)
ng test --watch=false     # frontend tests (EdgeHeadless)
scripts\deploy-all.ps1    # full release
```

Local backend tests need `JAVA_HOME=C:/jdk-24.0.1`, the cached gradle-9.4.0 wrapper, and `DOCKER_HOST=npipe:////./pipe/docker_engine`. Never download toolchains or browsers (zero-download rule).

## Architecture decisions

- Single-instance monolith: no queue broker, no Redis, no multi-document transactions — in-memory state (rate limits, throttles) is authoritative by topology.
- SPA and API are served same-origin (Cloudflare proxies `/api`, `/oauth2` to Fly); the SameSite session cookie depends on it. Dev mirrors this with `proxy.conf.json`.
- Sessions: self-issued HS256 JWT in an HttpOnly cookie, backed by a server-side sessions registry so revocation/deactivation applies on the next request. Authorization always reads the persisted member role, never the JWT claim.
- Deny-by-default endpoint security: `@PreAuthorize` on every internal handler, enforced by a build-time endpoint-inventory test.
- PII: application-level AES-256-GCM field encryption via Spring Data property converters; lookups/uniqueness via separate HMAC hash fields (ciphertext is never queried). Logs and audits carry ObjectIds only; CI sentinel scans enforce it.
- Candidate/interviewer surfaces are no-login tokenized links: tokens HMAC-hashed at rest, error responses byte-identical across unknown/erased/expired states (no existence or GDPR oracles).
- GDPR: candidate-keyed append-only audit; erasure wipes or supersedes all candidate state; external references are retained as non-PII anchors so provider re-syncs cannot resurrect an erased subject.
- Providers (Google/Microsoft calendar, Greenhouse/Lever ATS, SMTP) sit behind seams — `CalendarProvider`, `AtsConnector`, `EmailSender`/`MailTransport`. Adapters use raw `RestClient` (no SDKs) with explicit-field JSON parsing; provider free-text is never bound. The service layer never references a concrete transport type.
- Cross-document invariants use `findAndModify` CAS + unique (partial) indexes. Outbound side effects (email, ATS write-backs) go through CAS-claimed outbox collections keyed by deterministic idempotency keys.
- Background work: `@Scheduled` sweeps with checkpoint/replay (`SchedulerCheckpointService`); per-row CAS makes overlap or double-fire a no-op.
- Time: `java.time.Clock` injected everywhere; absolute `Instant`s stored; tests drive a mutable clock, never wall-clock sleeps.
- Mongock changesets are append-only: never rename or delete an applied changeset.
- Testing: JUnit 5 with a JVM-singleton Testcontainers Mongo; external providers stubbed by in-test JDK `HttpServer` singletons (WireMock is banned); Jasmine/Karma EdgeHeadless + axe-core on the frontend; Playwright specs are CI-only.
- Frontend: standalone components only; `$localize` on all user-facing strings; no SSR/prerender — SEO is static `index.html` metadata + a runtime `SeoService` with deny-by-default indexing.
- Build-time env injection (API URL, public origin) via Node `.mjs` scripts over the emitted `dist/`; values are sanitised and fail closed.
- Dependencies are reuse-first: a new runtime dependency requires constitution justification (beyond the scaffold, only oauth2-client, mail, commons-csv exist).
- Secrets never in source or `fly.toml` — only `fly secrets set` (UPPER_SNAKE names).
- Encoding: `.ps1` pure ASCII with CRLF; LF for Dockerfile/yml/conf; Java sources NUL/BOM-free.

<!-- MANUAL ADDITIONS END -->
