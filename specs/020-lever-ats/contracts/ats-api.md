# Contracts: ATS Integration — Lever (F41)

F41 reuses the **existing `AtsConnector` interface unchanged** (FR-026/FR-030) and **provider-parameterizes** the F40 internal REST endpoints so the Admin surface manages both Greenhouse and Lever. No new candidate-facing or unauthenticated endpoint (FR-011).

## 1. `AtsConnector` — UNCHANGED (the swap boundary, FR-026/SC-009)

```java
public interface AtsConnector {
    AtsProvider provider();                                  // GREENHOUSE | LEVER
    void verifyCredential(String workspaceId, String apiKey);
    AtsFetchResult fetchCandidates(String workspaceId, String apiKey, String cursor);
    String pushActivity(String workspaceId, String apiKey, String externalRef, AtsActivity activity);
}
```

- `LeverAtsClient` is a new `@Component implements AtsConnector` with `provider() == LEVER`; it auto-joins the existing `Map<AtsProvider,AtsConnector>` (built from the injected `List<AtsConnector>` in `AtsSyncService`/`AtsWriteBackService`/`AtsConnectionService`) **with zero change to those services' wiring** — the constitution Dependency-Policy payoff (SC-009). A structural test asserts no `com.cadence.service`/`scheduler` class references a Lever literal (the F22 `MailTransportSwapTest` constant-pool precedent, extended).

### Lever provider mapping (the only class that references Lever URLs/JSON)

| `AtsConnector` method | Lever Data API call | Notes |
|---|---|---|
| `verifyCredential` | `GET /v1/opportunities?limit=1` | 200 ok; 401/403 → `AtsApiException(needsReauth)`. HTTP Basic, key as username. |
| `fetchCandidates` | `GET /v1/opportunities?limit=<page>&expand=stage&expand=applications[&updated_at_start=<cursor>]` | `next`/`hasNext` pagination → `syncCursor`. Parse ONLY id/name/emails[0]/phones[0]/posting/stage.text. |
| `pushActivity` | `POST /v1/opportunities/{externalRef}/notes` body `{ "value": "<non-PII note>" }` | Returns the note id (opaque). |

- **Data minimization (FR-029)**: explicit `JsonNode.path` reads; never `links`/`tags`/`sources`/`origin`/`headline`/`archived`/EEO. Provider error bodies reduced to a status/category (FR-003), never logged. Base URL guarded by CI grep (`api.lever.co` only on `LeverAtsClient.java`).

## 2. Internal REST endpoints — provider-parameterized (Admin; Recruiter read-only)

Base `/api/internal/ats`, class-level `@PreAuthorize("hasRole('ADMIN')")` with `hasAnyRole('ADMIN','RECRUITER')` on the read paths (F40 policy, unchanged). All under `/api/internal/ats/**` → already in the `RbacEndpointInventory` allow-list; `AtsExceptionHandler` `@Order(HIGHEST_PRECEDENCE)` no-oracle envelope unchanged.

**`{provider}` validation (review fix)**: Spring binds an unknown enum path-variable by raising `MethodArgumentTypeMismatchException`, which is **NOT** an `IllegalArgumentException`, so it would fall through `AtsExceptionHandler`'s catch-all to **500** (a mild oracle). F41 therefore either (a) adds `@ExceptionHandler(MethodArgumentTypeMismatchException.class) → 400`, or (b) binds `{provider}` as `String` and resolves it to the enum in the controller, throwing `InvalidRequestException` (already → 400). Option (b) is preferred (explicit, no reliance on binder internals). Unknown value → **400 `invalid_request`** (no oracle).

| Method | Path | Role | Body / Result |
|---|---|---|---|
| GET | `/connections` | ADMIN, RECRUITER | **NEW** — `List<HealthResponse>` for **all** providers (each: provider, status, credentialSet, lastVerifiedAt, lastSyncAt, degraded, deadLetterCount). Providers with no row return `INTEGRATION_PENDING`. |
| GET | `/{provider}/connection` | ADMIN, RECRUITER | `HealthResponse` for one provider. |
| POST | `/{provider}/connection` | ADMIN | `{ apiKey }` → verify+store; `HealthResponse`. Invalid key → **409 `verification_failed`** (the real F40 `AtsExceptionHandler` status; no key echo, SC-010). |
| DELETE | `/{provider}/connection` | ADMIN | Disconnect that provider; 204. Cancels only that provider's pending write-backs (SC-015). |
| GET | `/{provider}/sync-status` | ADMIN, RECRUITER | Latest `AtsSyncRun` for that provider (finishedAt, outcome, processed/created/updated/skipped). |
| GET | `/{provider}/dead-letters` | ADMIN | That provider's `DEAD_LETTER` rows (ids/type/attempt/category/updatedAt — no PII). |

- **Migration note**: F40's un-parameterized `/connection`, `/sync-status`, `/dead-letters` (hardcoded GREENHOUSE at controller line ~54) are replaced by the `{provider}` forms; the Angular `ats.service.ts` is updated to the new paths. **No F40 MockMvc controller contract test exists today** (the F40 honest-residual) — so the provider-parameterized 5-role RBAC matrix is **net-new** work (`AtsConnectionContractTest`), not a migration. Error envelopes byte-identical to F40 (`AtsExceptionHandler`).

## 3. Error envelope (UNCHANGED, F40 `AtsExceptionHandler`, no-oracle)

| Condition | Status | Body |
|---|---|---|
| Blank key / unknown `{provider}` | 400 | `{"error":"invalid_request"}` |
| Credential rejected by provider | 409 | `{"error":"verification_failed"}` (the real F40 handler status; never echoes the key or provider body) |
| Non-Admin mutation | 403 | `{"error":"forbidden"}` (RBAC) |
| Rate-limited (candidate-path N/A here) | 429 | n/a — internal only |

## 4. Frontend contract (internal Admin screen — no §IX gate)

`ats-integration.component.ts` lists both providers from `GET /connections`, each with connect (apiKey field) / disconnect / health badge / last-sync / dead-letter count. `ats.service.ts` methods take a `provider` arg. Internal screen → Lighthouse/WCAG N/A (the F40/F50/F51 internal-screen precedent). Jasmine covers: list shows both providers, connect posts to the provider path, disconnect calls the provider DELETE, degraded badge renders.
