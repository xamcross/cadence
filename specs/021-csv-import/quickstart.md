# Quickstart — Standalone CSV Import Mode (F42)

How to run and demonstrate the F42 end-to-end flow (Angular upload → Spring async import → MongoDB), and the test-run flags. No external provider — there is no integration-pending stub.

## Prerequisites

- Local MongoDB 7 (`docker run -p 27017:27017 mongo:7`) for `./gradlew bootRun`.
- Backend env: the existing Cadence secrets/peppers (PII_PEPPER, etc.) as for any candidate-touching feature.
- Use the cached toolchain (no downloads — C7): `JAVA_HOME=C:/jdk-24.0.1`, cached `gradle-9.4.0`, `-Dapi.version=1.41`, `DOCKER_HOST=npipe:////./pipe/docker_engine` for the Testcontainers suite.

## Demo (browser → DB)

1. Start backend (`./gradlew bootRun`) and frontend (`ng serve` from `frontend/`).
2. Sign in as an **Admin** or **Recruiter**. Navigate to **Admin → CSV Import** (`/admin/csv-import`; guarded by `authGuard` + `roleGuard('ADMIN','RECRUITER')`).
3. Prepare a CSV (header row required; `name`,`email` mandatory; `stage`,`requisition`,`phone` optional):
   ```csv
   name,email,stage,requisition,phone
   Ada Lovelace,ada@example.com,Phone Screen,Backend Eng,+15550100
   Alan Turing,alan@example.com,Onsite,Backend Eng,
   ,broken@example.com,,,                # row fails: missing name
   Grace Hopper,not-an-email,,,          # row fails: invalid email
   Ada Lovelace,ada@example.com,,,       # intra-file duplicate of row 1 (collapses)
   ```
4. Upload. The UI gets `202 {jobId}` instantly and begins polling `GET /api/internal/import/{jobId}/status`.
5. Observe the result: 2 imported (Ada, Alan), 2 rejected (with row number + field/reason), the intra-file duplicate collapsed. If a row matches an **existing** workspace candidate, the job goes `AWAITING_DUPLICATE_DECISION`.
6. Resolve duplicates: pick **merge** or **skip** per row (or a default), `POST …/resolve`. The job completes; the raw uploaded blob is disposed.
7. Verify lifecycle parity: the imported candidates appear like native candidates; **try to send them an email** — it is blocked (`ContactPermissionGate` → `NO_BASIS`) until consent/lawful basis is recorded through the existing flow (import does not authorize contact).
8. Verify PII-at-rest: a raw `mongosh` read of `candidates` shows `name`/`email`/`phone` as ciphertext; a read of `csvImportFiles` (before disposal) shows `dataBase64` as ciphertext.

## Degraded / edge demos

- **Over-size file** (> `cadence.import.max-file-size`): refused with a clean `400 invalid_import` (the multipart cap sits above the in-service gate — D9), nothing imported.
- **>80% invalid rows**: whole file rejected (`rejectionReason: TOO_MANY_INVALID`), zero committed.
- **Abandoned duplicate decision**: leave the job in `AWAITING_DUPLICATE_DECISION` past `cadence.import.job-ttl` (test profile: `PT2S`) → the reaper defaults the remaining duplicates to skip, disposes the blob, sets `EXPIRED`.
- **Cross-workspace probe**: a Recruiter in workspace B requesting workspace A's `jobId` gets an indistinguishable `404`.

## Tests

```bash
# Backend (Testcontainers; first multi-class run may throw the one-time GenericContainer
# class-init error — re-run). Flags as above.
./gradlew test --tests "com.cadence.csvimport.*" --tests "com.cadence.rbac.RbacEndpointInventoryTest"

# Frontend
cd frontend && ng test --watch=false
```

Key tests to expect (per the spec SCs):
- Unit: `CsvRowValidator` (required/email/value-free reason), email normalization == `emailHash`, `>80%` ratio math + boundary + 0-row, intra-file dedup, `CsvInjectionEscaper` (leading `=,+,-,@,|`/tab/CR neutralized, legit `+`/`-` preserved), idempotency-marker.
- Integration (Testcontainers): async happy-path (202 → poll → imported, PII ciphertext), per-row validation mix, duplicate flag → merge (active-state-guarded, non-empty-only) / skip, erasure-races-merge no-op, restart idempotency (double-sweep proxy — honest bound label), TTL expiry → skip-default + blob disposed, index bootstrap, PII-log-scan (sentinels absent).
- Contract (MockMvc): upload 202 / status 200+no-oracle-404 / resolve 200/409, 5-role RBAC matrix, multipart-too-large → 400.
- Frontend (Jasmine): upload posts FormData, poll renders states + per-row results, duplicate merge/skip action.

## CI

`ci.yml` gains an F42 PII-scan block (sentinels `SENTINELF42NAME_zz9` / `SENTINELF42EMAIL_zz9` / `SENTINELF42PHONE_zz9` + a formula-injection sentinel `SENTINELF42FORMULA_zz9`) asserting none appear in captured test output, mirroring the F40/F41 ATS sentinel block.
