# Quickstart: ATS Integration — Greenhouse (F40)

End-to-end demo of the integration-pending flow: connect → import → write-back → degraded mode. All against the locally-runnable `StubGreenhouse` (no live Greenhouse account, $0).

## Prerequisites

- Local MongoDB (`docker run -p 27017:27017 mongo:7`) for manual `bootRun`; Testcontainers for the test suite (no cloud creds).
- Backend run flags (the project standard): `JAVA_HOME=C:/jdk-24.0.1`, the cached `gradle-9.4.0` binary, `-Dapi.version=1.41`, `DOCKER_HOST=npipe:////./pipe/docker_engine`, `-Dorg.gradle.java.installations.auto-download=false`.
- Config: `cadence.ats.greenhouse.base-url` points at the stub in tests; locally it defaults to a no-op `INTEGRATION_PENDING` until set.

## Demonstrable flow (§II end-to-end)

1. **Connect (US1)** — As an Admin, open `admin/ats`, paste a Greenhouse API key. The backend `POST /api/internal/ats/connection` verifies against the stub (`GET /v1/jobs?per_page=1` → 200), stores the key **encrypted**, and the screen shows `CONNECTED` + last-verified time. A bad key → `409 verification_failed`, no stored connection, no key in the response/logs.
2. **Import (US2)** — Seed the stub with a candidate/application/job + stage. Within one poll cycle (≤5 min; tests trigger `AtsSyncScheduler.sweep()` directly), the candidate appears in Cadence (`candidates` row with encrypted name/email, `atsExternalRef`, job id/title, raw stage label). Change the stage in the stub → next sweep updates the same row (no duplicate). The `admin/ats` sync-status shows processed/created/updated counts.
3. **Write-back (US3)** — Trigger a scheduling event for the imported candidate (send link / book / reschedule / cancel / no-show / feedback). `AtsWriteBackService.enqueue` inserts an outbox row; `AtsWriteBackScheduler` drains it; the stub records a `POST .../activity_feed/notes`. Assert exactly one note per event (idempotent across a re-drain).
4. **Degraded mode (US4)** — Program the stub to return `503` for the notes POST. The write-back stays queued, retries with backoff, and the `admin/ats` screen shows a degraded indicator; on stub recovery the note is delivered within the retry window. Exhaust the retries → the row moves to DEAD_LETTER, an operator notification fires, and it appears in `GET /api/internal/ats/dead-letters`.

## Key acceptance tests (Phase 2 will enumerate)

- `AtsConnectionContractTest` (MockMvc): connect/verify/disconnect, 5-role RBAC matrix, `credentialSet`-only response (SC-006/SC-010), no-oracle envelope.
- `AtsSyncIT` (Testcontainers + StubGreenhouse): import, stage update (no dup), reconcile precedence (external-ref authoritative; email adopt; distinct-refs-shared-email NOT merged — SC-002), idempotent overlapping sweeps, **erasure-vs-sync resurrection guard** (erase then re-poll → no PII re-write, no new record).
- `AtsWriteBackIT`: the six event seams enqueue exactly one row each (linked→1 / **non-linked→0**); gated concurrent drain → one note (SC-003); reaper honest bound; dead-letter + notify (SC-004); pending write-backs cancelled on disconnect + on candidate erasure (FR-015).
- `AtsSyncRateLimitIT`: a `429,429,200` sequence on the **sync (inbound)** path with `Retry-After` → the sync backs off and recovers (not FAILED) — FR-020 end-to-end, not just the unit retry.
- `AtsRestartIT`: SC-007 both directions — a sync restart (overlapping sweeps) produces no duplicate import; a write-back restart (reaper double-sweep proxy) produces no duplicate note. Honest residual (F31/F32 label): the write-back side is a double-sweep/`updatedAt`-stamp proxy, not a true JVM restart.
- `AtsPropertiesBoundsTest`: config-invariant `retry-base-backoff × 2^retry-max-attempts < 15min` (SC-004 budget) and `reaper-threshold > read-timeout + max-backoff` (the F23 precedent).
- `AtsCredentialCryptoTest`: raw-driver read shows `apiKey` ciphertext (SC-006); cold-converter reload decrypts.
- `AtsImportedCandidateCryptoTest`: raw-driver read shows imported name/email/phone + `atsStageLabel` as ciphertext (SC-012).
- `AtsConsentGateIT`: an imported candidate cannot be emailed until lawful basis recorded (SC-008) — reuses `ContactPermissionGate`.
- `AtsLogPiiScanTest`: drive connect→sync→write-back→failure with name/email/key sentinels; assert absence across logs/rows/audit/dead-letter (SC-005). `ci.yml` extended with ATS sentinels + a `greenhouse` base-URL literal guard on `GreenhouseAtsClient.java`.
- `AtsNoSdkStructuralTest`: constant-pool scan — no `com.cadence.service`/`scheduler` references a Greenhouse literal (SC-009).
- `AtsIndexTest`: `ChangeUnit018` indexes present incl. the partial-unique `{workspaceId,atsProvider,atsExternalRef}`; the native-candidate (null ref) does not collide.
- `AtsApiRetryTest` / `AtsApiClassifierTest` (pure unit): TRANSIENT/AUTH/FATAL + `nextWaitMillis(attempt, retryAfter)` (no wall-clock).
- Frontend `ats-integration.component.spec.ts` (Jasmine): connect form (write-only key), status/degraded rendering, dead-letter list. Internal screen → no WCAG/Lighthouse gate.

## Test-run reminders

- The first multi-class Testcontainers run after a recompile throws the one-time `GenericContainer` class-init error — re-run.
- Seed distinct `atsExternalRef` per row (the F23 `{tokenHash}` plain-unique seeding lesson analogue) and distinct candidate ids.
- Set `cadence.ats.retry-base-backoff: PT0S` in tests; drive crash windows by stamping `updatedAt` into the past with the test `MutableClock` (never wall-clock sleeps).
