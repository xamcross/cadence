# Quickstart: Calendar Integration — Google Calendar (F10)

**Feature**: 007-google-calendar | **Date**: 2026-06-15

How to run, manually verify, and test-verify the Google Calendar adapter locally. Builds on the F01.1 connect flow.

## Prerequisites

- Local MongoDB (`docker run -p 27017:27017 mongo:7`) for `./gradlew bootRun`.
- Tests need Docker (Testcontainers `mongo:7`) — no Google cloud credentials (the JDK `HttpServer` stub serves Google, research D12).
- Run flags (CLAUDE.md): `JAVA_HOME=C:/jdk-24.0.1`, cached `gradle-9.4.0` binary, `-Dapi.version=1.41`, `DOCKER_HOST=npipe:////./pipe/docker_engine`, `-Dorg.gradle.java.installations.auto-download=false` (Principle X — zero downloads).

## Local config

`application.yml` already carries `calendar.oauth.*` (F01.1). F10 adds `calendar.api.*` (Google API base, retry, parallelism, preview window — see data-model §5) and **changes the Google scope** to include event-write (research D1):
```
GOOGLE_CAL_SCOPE="https://www.googleapis.com/auth/calendar.events https://www.googleapis.com/auth/calendar.freebusy"
GOOGLE_CAL_API_BASE=...   # prod: https://www.googleapis.com ; tests: the stub URL
```
Prod secrets via `fly secrets set` (client id/secret already from F01.1); never commit secrets.

## Manual verification (the §II end-to-end leg, D11)

1. `docker run ... mongo:7`, then `./gradlew bootRun` (backend) and `ng serve` (frontend).
2. Sign in, go to **Calendar connections**, connect Google (F01.1 flow against the stub).
3. Click **"Preview my availability"** → the SPA calls `GET /api/internal/calendar/availability/preview` and renders your busy blocks for the next 7 days (or "you appear free"). This is a real Angular → Spring → Google(stub) → back round-trip exercising the free/busy adapter.
4. Disconnect (or revoke at the provider) → preview shows the **Needs reconnection / Not connected** prompt, not an error.

## Test verification

`./gradlew test` (backend) covers, against the stub:
- **Availability**: free/busy returns only intervals even when the stub event has a title/attendees (SC-004); bounded-parallel 5-member panel (SC-001); not-connected/needs-reconnection/transient → correct `AvailabilityStatus`, never silently free (FR-004).
- **Event write**: idempotent create (double create → one event); idempotent update/delete (404→ok); DST-crossing fixture → correct wall-clock (SC-005).
- **Resilience**: `429,429,200` → succeeds; persistent `503` → transient exception after max-3 retries, no orphan; `401` → needs-reconnection, no retry (SC-006). Tests set `calendar.api.retry-base-backoff: PT0S`.
- **Rollback**: partial-create → compensating delete, zero orphans; forced delete failure → `CLEANUP_INCOMPLETE` audited (SC-007/FR-016a).
- **PII/log scan**: TRACE run asserts zero token / secret / **event-title / location / attendee-email** sentinels (SC-003); raw-driver read of `managedCalendarEvents` shows references + instants only, no content.
- **RBAC/contract**: preview self-scoped for all 5 roles; 401 unauth; `Cache-Control: no-store`; two-member isolation (FR-018).

`ng test` (frontend) covers the preview render (busy blocks / free / reconnect-prompt states) and the service call.

## Definition-of-Done checks (constitution §Dev Workflow)

- End-to-end (Angular → Spring → Mongo → Google stub) works for the preview slice; event-write path verified end-to-end against the stub by integration/contract tests (its UI trigger lands in F13).
- Backend unit + integration + contract green; `ng test` + `ng build` clean; `RbacEndpointInventoryTest` still green (preview endpoint is `isAuthenticated()`).
- `ci.yml` PII scan extended with calendar event-content sentinels (title/location/attendee-email) on top of the F01.1 token sentinels.
- No new `.ps1`; no new runtime dependency; no tool downloads.
- Multi-role sub-agent review (≥3) completed; findings applied/reported.
