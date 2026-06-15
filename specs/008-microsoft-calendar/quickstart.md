# Quickstart: Calendar Integration — Microsoft 365 / Outlook (F11)

**Feature**: 008-microsoft-calendar | **Date**: 2026-06-15

How to run, manually verify, and test-verify the Microsoft 365 / Outlook calendar adapter locally. Mirrors F10 (Google) and builds on the F01.1 connect flow (which already supports the Microsoft provider).

## Prerequisites

- Local MongoDB (`docker run -p 27017:27017 mongo:7`) for `./gradlew bootRun`.
- Tests need Docker (Testcontainers `mongo:7`) — **no Microsoft cloud credentials** (the JDK `HttpServer` stub `StubGraphCalendar` serves Graph, research D10).
- Run flags (CLAUDE.md): `JAVA_HOME=C:/jdk-24.0.1`, cached `gradle-9.4.0` binary, `-Dapi.version=1.41`, `DOCKER_HOST=npipe:////./pipe/docker_engine`, `-Dorg.gradle.java.installations.auto-download=false` (Principle X — zero downloads).

## Local config

`application.yml` already carries `calendar.oauth.microsoft.*` (F01.1). F11 **changes the Microsoft scope** to add event-write + the identity scopes getSchedule needs (research D1) and **adds `calendar.api.microsoft.base-url`** + the getSchedule interval (data-model §7):
```
MS_CAL_SCOPE="openid profile email offline_access Calendars.ReadWrite"
MS_GRAPH_API_BASE=...   # prod: https://graph.microsoft.com ; tests: the StubGraphCalendar URL
```
`Calendars.ReadWrite` is broader than F10's Google `calendar.events.owned` because Graph has no owned-events-only delegated scope (§VIII justification in plan.md). Prod secrets via `fly secrets set` (client id/secret already from F01.1); never commit secrets.

> **Re-consent note**: members who connected Microsoft under the old `Calendars.Read` scope must **reconnect** to gain write + the account email getSchedule needs — F11 surfaces this as "Needs reconnection" on the first write or on a preview with no stored SMTP (D1/D2a), rather than failing opaquely.

## Manual verification (the §II end-to-end leg, D11)

1. `docker run ... mongo:7`, then `./gradlew bootRun` (backend) and `ng serve` (frontend).
2. Sign in, go to **Calendar connections**, connect **Microsoft** (F01.1 flow against the stub).
3. Click **"Preview my availability"** → the SPA calls `GET /api/internal/calendar/availability/preview` and renders your Outlook busy blocks for the next 7 days (or "you appear free"). A real Angular → Spring → Graph(stub) → back round-trip exercising the getSchedule adapter, with `provider: "MICROSOFT"`.
4. Disconnect (or revoke at the provider) → preview shows the **Needs reconnection / Not connected** prompt, not an error.

## Test verification

`./gradlew test` (backend) covers, against `StubGraphCalendar` (and `StubGoogleCalendar` for the mixed test):
- **Availability**: getSchedule returns only `{start,end,status}` even when the stub item carries a sentinel subject/attendee (SC-004, non-circular — parse-discipline control); status `free`/`busy`/`tentative`/`oof`/`workingElsewhere`/`unknown` map correctly (only `free` schedulable, FR-002a); non-grid exact boundary (FR-003); not-connected / pre-F11-null-SMTP / needs-reconnection / transient → distinct `AvailabilityStatus`, never silently free (FR-004); bounded-parallel 5-member panel (SC-001).
- **Event write**: idempotent create via `transactionId` + **server-id read-back** (double create → one event, SC-008); in-place update/delete by the **stored** id (404→ok); DST-crossing fixture → recorded body has local `dateTime` + IANA `timeZone` (SC-005).
- **Resilience**: `429,429,201` → succeeds; `429`+`Retry-After` (delta-seconds AND HTTP-date) → honoured; persistent `5xx` → transient exception after max-3, no orphan; `401`/`403` → needs-reconnection, no retry (SC-006). Tests set `calendar.api.retry-base-backoff: PT0S`.
- **Mixed provider (US4/SC-009)**: one Google + one Microsoft member → one normalised availability set; panel booking on both; forced one-provider create-fail → the other provider's event rolled back — **both directions**, zero orphans via each stub's residual store.
- **Rollback**: MS partial-create → compensating delete, zero orphans; forced delete failure → `CLEANUP_INCOMPLETE` audited (SC-007/FR-019).
- **PII/log scan**: TRACE run asserts zero token / secret / subject / location / dial-in / attendee-email / **account-email (getSchedule SMTP)** sentinels (SC-003); raw-driver read of a Microsoft `managedCalendarEvents` row shows references + instants only.
- **RBAC/contract**: preview self-scoped for all 5 roles on a Microsoft connection; 401 unauth; `no-store`; two-member isolation (FR-024).
- **F10 regression**: existing Google update/delete tests, adapted to the `providerEventId` signature (D5), stay green.

`ng test` (frontend) covers the preview render for a Microsoft connection (busy / free / reconnect-prompt) and the service call.

## Definition-of-Done checks (constitution §Dev Workflow)

- End-to-end (Angular → Spring → Mongo → Graph stub) works for the preview slice with a Microsoft connection; event-write + mixed-panel paths verified end-to-end against the stubs (UI trigger lands in F13).
- Backend unit + integration + contract green; `ng test` + `ng build` clean; `RbacEndpointInventoryTest` still green; **all F10 tests still green** after the interface refactor.
- `ci.yml` base-URL guard extended to ban a `graph.microsoft.com` literal in `MicrosoftCalendarClient` (the five event-content sentinels already cover F11).
- No new `.ps1`; **no new runtime dependency**; no new collection or Mongock changeset; no tool downloads.
- Multi-role sub-agent review (≥3) completed; findings applied/reported.
