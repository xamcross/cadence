# Quickstart — GDPR Baseline (F04)

**Branch**: `005-gdpr-baseline` | **Date**: 2026-06-15

## Run locally

```powershell
# 1. MongoDB (local dev container)
docker run -d --rm -p 27017:27017 --name cadence-mongo mongo:7

# 2. Backend (Mongock applies ChangeUnit005 indexes on startup)
#    Use the installed JDK 21 + cached Gradle (zero downloads, constitution X)
cd backend; ..\gradlew.bat bootRun

# 3. Frontend
cd frontend; ng serve
```

## Backend tests (the F04 acceptance gates)

```powershell
cd backend
$env:JAVA_HOME = "C:\jdk-24.0.1"
..\gradlew.bat test -Dapi.version=1.41 --tests "com.cadence.gdpr.*"
# Full suite (F01+F02+F03+F04, incl. RbacEndpointInventoryTest):
..\gradlew.bat test -Dapi.version=1.41
```

> First multi-class Testcontainers run after a recompile may throw a one-time `GenericContainer` class-init error — **re-run** (CLAUDE.md F02 note). `DOCKER_HOST=npipe:////./pipe/docker_engine` on Windows.

## Frontend tests

```powershell
cd frontend
ng test --watch=false      # Jasmine — GDPR route guards per role (SC-011)
ng build                   # must be clean
```

## Manual verification (maps to user stories)

Seed a candidate via the canonical-create contract (an integration-test helper or a transient `@Profile("test")` seeder — F04 ships no create endpoint). Then, signed in as roles from F01:

1. **US1 — lawful basis / gate**: As Admin/Recruiter, `PUT /api/internal/candidates/{id}/basis {lawfulBasis:"LEGITIMATE_INTEREST"}` → gate permits. `DELETE .../basis` → gate denies `withdrawn`. (Gate is consulted by F22 later; verify via the gate test / a debug read.)
2. **US2 — operator erasure**: As Admin (then Recruiter), `POST /api/internal/candidates/{id}/erasure` → `200 {status:"erased"}`; confirm via raw `mongosh` that `name/email/phone == "[ERASED]"`, `emailHash` absent, and the `auditLog` still holds the candidate's entries. As HM/Interviewer/Read-only → **403**.
3. **US3 — audit view**: As Admin, `GET /api/internal/candidates/{id}/audit` → ordered non-PII entries. As non-Admin → **403**.
4. **US4 — candidate-initiated request**: Drive `ErasureRequestService.requestErasure(id, "candidate_request")` (F30 will call this); as Admin, `GET /api/internal/erasure-requests?status=PENDING` then `POST .../{reqId}/confirm` → wipe runs; second confirm → **409**.
5. **US5 — retention**: Set a short retention period (F03 settings); seed an over-age candidate (`lastContactAt` in the past); run `RetentionScanTask` → candidate flagged; gate denies `over_retention`; as Admin `POST /api/internal/retention/{id}/delete` → wiped. Lengthen the period, re-scan → flag cleared, gate permits.
6. **US6 — no PII in logs**: Set root logger to `TRACE`, drive 1–5, then scan the captured log for the seeded name/email/phone → **zero matches** (the CI scan automates this).
7. **US7 — frontend**: As Admin, the GDPR area (audit view, request queue, retention) is reachable; as non-authorized roles the nav is hidden and direct navigation → `/not-authorized`.

## Raw-driver ciphertext check (SC-006)

```javascript
// mongosh — confirm candidate PII is ciphertext at rest (bypasses the decrypting converter)
use cadence
db.candidates.findOne({}, { name:1, email:1, emailHash:1 })
// name/email are Base64 ciphertext (not readable); after erasure both == "[ERASED]" and emailHash is absent
```

## Deploy (after merge to main)

```powershell
scripts\db-migrate.ps1        # verify Atlas reachable (ChangeUnit005 applies on backend startup)
scripts\deploy-backend.ps1    # backend + Mongock migration
scripts\deploy-frontend.ps1   # Angular GDPR surface
```
