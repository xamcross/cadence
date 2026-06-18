# Quickstart: ATS Integration — Lever (F41)

How to run and demo the **integration-pending** Lever connector and prove Greenhouse + Lever coexistence. No live Lever credentials are required — everything runs against the in-test `StubLever` (and `StubGreenhouse`) JDK `HttpServer` stubs.

## Run the tests (the primary acceptance surface)

```powershell
# From repo root. Same flags as every prior feature (CLAUDE.md):
$env:JAVA_HOME = "C:\jdk-24.0.1"
$env:DOCKER_HOST = "npipe:////./pipe/docker_engine"
& "$env:USERPROFILE\.gradle\wrapper\dists\gradle-9.4.0-bin\*\gradle-9.4.0\bin\gradle.bat" `
  -p backend test `
  -Dapi.version=1.41 `
  -Dorg.gradle.java.installations.auto-download=false
```

- The first multi-class Testcontainers run after a recompile throws the one-time `GenericContainer` class-init error — **re-run**.
- `StubLever` and `StubGreenhouse` are JVM-lifetime singletons — they are NOT stopped in `@AfterAll` (the F40 dead-port footgun).

Frontend:

```powershell
cd frontend
ng test --watch=false      # Jasmine — the two-provider ATS admin component
ng build --configuration production
```

## Demo the flow manually (both providers in one workspace)

1. **Start the backend** with both stub base URLs pointed at local `StubLever`/`StubGreenhouse` (test profile via `@DynamicPropertySource`; for a manual run set `CADENCE_ATS_LEVER_BASE_URL` / `CADENCE_ATS_GREENHOUSE_BASE_URL`).
2. **Sign in as an Admin**, open the **ATS Integration** admin screen. Both providers show `INTEGRATION_PENDING`.
3. **Connect Greenhouse** (enter the stub key) → `CONNECTED`, last-verified stamped. **Connect Lever** (enter the stub key) → `CONNECTED`. Both rows now coexist (proves FR-031 / SC-013 the index migration).
4. **Trigger a sync** (or wait one poll). Greenhouse-seeded and Lever-seeded candidates both import, each tagged with its own `atsProvider`; a candidate sharing an email across the two providers stays **two distinct records** (SC-013b).
5. **Run a scheduling action** (send a link / book) for a Lever candidate → a note lands on the **Lever** opportunity in `StubLever`; for a Greenhouse candidate → on **Greenhouse** only (SC-013c, no cross-provider leak).
6. **Degrade Lever** (point `StubLever` at error responses) → the Lever connection shows `degraded`; queued Lever write-backs hold and drain on recovery (SC-004); the **Greenhouse** connection keeps syncing throughout (SC-014, isolation).
7. **Disconnect Lever** → its key is destroyed and only its pending write-backs cancel; Greenhouse is untouched (SC-015).

## What "done" looks like

- `com.cadence.ats.*` suite green incl. the new `LeverAtsClientTest`, `AtsMultiConnectorIT` (both connectors in one workspace: no double-import, no cross-merge, no mis-route — SC-013a/b/c), `AtsProviderIsolationIT` (SC-014), `AtsLeverErasureIT`/disconnect-scope (SC-015), `AtsIndexTest` (ChangeUnit019 unique `{workspaceId,provider}`), `AtsLogPiiScanTest` (SC-005, closes the F40 residual), the structural no-Lever-literal-in-service test (SC-009).
- `RbacEndpointInventoryTest` green (provider-parameterized paths still gated).
- All F01–F40 suites green **after** migrating the F40 ATS fixtures to the new signatures — the connection/write-back refactors are behaviour-preserving for the single-Greenhouse *runtime* path, but they are **source-compat breaks**: `AtsItBase` (`findByWorkspaceId(ws).orElseThrow()`), `AtsConnectionIT` (`health(WS)`/`disconnect(WS)` single-arg), and the controller paths are edited as part of F41 (not "untouched"). This migration is an explicit F41 task.
- `ng test` + `ng build` clean.
- `ci.yml` PII scan extended with Lever candidate-name/credential sentinels + the `api.lever.co` base-URL guard.
