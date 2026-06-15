# Quickstart — OAuth Token Store (Calendar Connections)

**Feature**: F01.1 | **Branch**: `006-oauth-token-store`

How to run and manually verify the feature locally. No live Google/Microsoft credentials are needed for tests (the provider is stubbed); a real consent flow needs real OAuth client credentials configured as below.

---

## Prerequisites

- JDK 21 (`JAVA_HOME=C:/jdk-24.0.1` toolchain pins 21), cached Gradle 9.4.0 (never trigger a wrapper download — constitution §X).
- A local MongoDB: `docker run -d -p 27017:27017 mongo:7`.
- Node + Angular CLI already installed (no downloads).

---

## Run the backend + frontend

```powershell
# Backend (from backend/)
$env:JAVA_HOME = "C:/jdk-24.0.1"
..\gradlew.bat bootRun            # uses cached gradle-9.4.0

# Frontend (from frontend/)
ng serve                          # proxies /api to the backend (proxy.conf.json)
```

The dev defaults in `application.yml` (`calendar.oauth.*`) point at a local stub. For a real Google/Microsoft consent run, set:

```powershell
fly secrets set GOOGLE_CAL_CLIENT_ID="…"  GOOGLE_CAL_CLIENT_SECRET="…"
fly secrets set MS_CAL_CLIENT_ID="…"      MS_CAL_CLIENT_SECRET="…"
```

and register the redirect URI `https://<host>/api/internal/calendar/connections/{google|microsoft}/callback` in the provider console (Google Cloud / Entra app registration), free/busy scope only.

---

## Manual verification (real provider)

1. Sign in to Cadence (any role) → open **Calendar connections** in the nav.
2. Click **Connect Google** → you are taken to Google's consent screen requesting **free/busy** access only.
3. Approve → you land back on **Calendar connections**, now showing **Connected as &lt;your account&gt;**.
4. Inspect the stored row directly — confirm it is ciphertext, not your token:
   ```js
   // mongosh
   db.calendarConnections.findOne({ provider: "GOOGLE" })
   // refreshToken / accessToken / providerAccountId are base64 ciphertext, NOT readable values
   ```
5. Click **Disconnect** → the row is gone; status returns to **Not connected**.

---

## Verify automatic refresh (stubbed, deterministic)

Run the integration suite — the WireMock provider stub returns a short-lived access token, the test advances the injected `Clock` past expiry, then calls `CalendarProvider.validAccessToken(...)` and asserts a transparent refresh occurred with **no** re-consent:

```powershell
$env:JAVA_HOME = "C:/jdk-24.0.1"
..\gradlew.bat test --tests "com.cadence.calendar.*"  `
  -Dapi.version=1.41
# (DOCKER_HOST npipe is set by the test task; first multi-class Testcontainers run
#  may throw a one-time GenericContainer class-init error — re-run.)
```

Key assertions exercised:
- **SC-002**: raw-driver read of `calendarConnections` shows only ciphertext for `refreshToken`/`accessToken`/`providerAccountId`.
- **SC-004**: expired-access + valid-grant → `validAccessToken` refreshes transparently (stub hit once), returns a fresh token.
- **Rotated refresh token** persisted; **concurrent** `validAccessToken` (CountDownLatch, N≥20) → exactly one refresh exchange (tokenVersion CAS, D5).
- **SC-006**: stub returns `invalid_grant` → status flips to `NEEDS_RECONNECTION`, `CalendarReconnectRequiredException` thrown, no infinite retry; transient `503` → bounded retry, status stays `CONNECTED`.
- **SC-007**: 5-role contract test; member A never sees member B's connection; unauthenticated → 401.
- **SC-003**: TRACE-level log scan over connect→refresh→disconnect→error finds zero token/code/secret/account sentinels.
- **FR-007**: `RoleService.guardedDeactivate` deletes the member's connections.

---

## Frontend unit tests

```powershell
# from frontend/
ng test --watch=false
```
Covers the calendar-connections component status rendering (Not connected / Connected / Needs reconnection) and that the route is reachable by every authenticated role (`authGuard` only, no role gate).
