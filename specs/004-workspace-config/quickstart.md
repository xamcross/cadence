# Quickstart: Workspace Setup & Configuration (F03)

**Feature**: `004-workspace-config` | **Prereqs**: F00/F01/F02 in place; Docker (MongoDB), JDK 21, cached Gradle 9.4.0, Node/Angular CLI.

## Run locally

```powershell
# 1. MongoDB for manual dev
docker run -d --name cadence-mongo -p 27017:27017 mongo:7

# 2. Backend (from backend/) — JAVA_HOME = installed JDK 21, no tool downloads
./gradlew bootRun

# 3. Frontend (from frontend/)
ng serve   # proxy.conf.json forwards /api, /oauth2 to the backend (same-origin cookie)
```

## Tests

```powershell
# Backend — JUnit 5 + Testcontainers (Windows/Docker flags per CLAUDE.md)
#   JAVA_HOME=C:/jdk-24.0.1, cached gradle-9.4.0, -Dapi.version=1.41, DOCKER_HOST=npipe:////./pipe/docker_engine
./gradlew test --tests "com.cadence.workspace.*"

# Frontend
ng test --watch=false      # workspace settings/wizard guard specs (SC-012)
```
> First multi-class Testcontainers run may throw a one-time `GenericContainer` class-init error — re-run (CLAUDE.md).

## Manual verification (maps to acceptance scenarios)

Sign in as an **Admin** (seeded via F01 invite) on a fresh (unconfigured) workspace.

1. **First-run wizard (US1)** — `/me` returns `workspaceConfigured: false`; the SPA routes to `/workspace/setup`.
   - Submit valid name/time zone/working hours/SLA window/retention **with** the acknowledgment ticked → workspace becomes configured; the wizard is not shown again. (US1 AS-1)
   - Try to finish **without** acknowledging retention → refused; stays unconfigured. (US1 AS-2/SC-003)
   - Submit an invalid value (e.g. `timeZone=Mars/Phobos`, end-before-start, SLA `0`, retention `10`) → per-field error, nothing persisted. (US1 AS-3/SC-008)
   - `POST /api/internal/workspace/setup` again directly → **409 already_configured**. (US1 AS-6/FR-006)
   - Restart the backend → settings read back unchanged. (US1 AS-5/SC-004)

2. **Ongoing settings (US2)** — `PATCH /config` SLA 5→7 → persists. (US2 AS-1)
   - As **Recruiter/HM/Interviewer/Read-only**, `GET` and `PATCH /config` → **403**, no change. (US2 AS-2/SC-001/SC-002)
   - Two concurrent PATCHes to different fields → both preserved. (US2 AS-5/SC-009)

3. **Branding (US3)** — upload a PNG ≤ 1 MB + colour `#1F2937` → persists; `GET /api/public/workspace/branding` (no session) returns them.
   - Upload an **SVG** or a renamed `.png` that is really an SVG → **400 invalid_logo** (magic-byte). (SC-008)
   - With nothing set, the public branding read returns the default colour + placeholder logo. (US3 AS-4/SC-011)
   - Unset the logo → public read returns the placeholder. (US3 AS-6)

4. **Email config (US4)** — `PUT /email` with domain + credential → `credentialSet: true`, credential **not** echoed.
   - `GET /config` (even as Admin) → `credentialSet: true`, no value. (SC-006)
   - `mongosh`: `db.workspaceConfig.findOne()` → `emailProviderCredential` is ciphertext, not plaintext. (SC-007)
   - Rotate (re-PUT) → old value unrecoverable; unset (`DELETE /email/credential`) → `credentialSet: false`. (US4 AS-6/7)
   - `grep` the run logs (DEBUG enabled) → 0 occurrences of the credential / `api[_-]?key` value. (SC-005)

5. **Template lock (US5)** — `PUT /templates/interview_invite/lock {locked:true}` → persists; visible in `GET /config`. Non-Admin → 403. (US5 AS-1..3)

6. **Frontend authorization (US6)** — as a non-Admin, the settings nav link is hidden; navigating to `/admin/workspace` directly → `/not-authorized`; the API still 403s. On an unconfigured workspace a non-Admin sees "setup pending", not the wizard. (US6)

## Done checklist (F03)

- [ ] `./gradlew test --tests "com.cadence.workspace.*"` green (incl. raw-driver ciphertext, restart-persistence, concurrent first-run, 5×4 RBAC matrix, secret log scan)
- [ ] `RbacEndpointInventoryTest` still green (new internal endpoints carry the Admin role)
- [ ] `ng test` green (settings/wizard guard per-role specs)
- [ ] Playwright `workspace-config.spec.ts` green (wizard + non-admin redirect + public branding)
- [ ] No new `.ps1`; if any script touched, pure-ASCII byte scan passes (C5)
- [ ] Final multi-role sub-agent review at task close (C6)
