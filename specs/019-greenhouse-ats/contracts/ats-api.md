# Contracts: ATS Integration — Greenhouse (F40)

Three contract surfaces: (A) the provider-agnostic `AtsConnector` Java interface (the swap boundary, FR-026), (B) the internal REST endpoints the Angular Admin screen calls, and (C) the Greenhouse Harvest HTTP shape the `GreenhouseAtsClient` and `StubGreenhouse` honor.

## A. `AtsConnector` interface (the abstraction boundary, FR-026 / SC-009)

```java
public interface AtsConnector {
    AtsProvider provider();                         // GREENHOUSE

    /** Authenticated liveness/credential check. Throws AtsApiException(AUTH) on bad key. */
    void verifyCredential(String workspaceId, String apiKey);

    /** Pull candidates/applications updated since the cursor (null = full). Normalized; no provider types leak. */
    AtsFetchResult fetchCandidates(String workspaceId, String apiKey, String cursor);

    /** Write one activity to the candidate's ATS timeline. Returns an opaque provider ref. */
    String pushActivity(String workspaceId, String apiKey, String externalRef, AtsActivity activity);
}

record AtsFetchResult(List<AtsCandidateRecord> records, String nextCursor) {}
record AtsCandidateRecord(String externalRef, String name, String email, String phone,
                          String externalJobId, String externalJobTitle, String stageLabel) {}
record AtsActivity(AtsWriteBackType type, Instant occurredAt, String note) {}  // note = non-PII scheduling fact
```

**Contract guarantees**: implementations reference only their provider's wire format; services select via `Map<AtsProvider,AtsConnector>`. A constant-pool structural test asserts no `com.cadence.service`/`com.cadence.scheduler` class references a Greenhouse literal (the F22 `MailTransportSwapTest` precedent). `fetchCandidates` returns ONLY the minimized field set (FR-029) — attachments/notes/custom/EEOC are never parsed.

## B. Internal REST endpoints — `/api/internal/ats/**` (Admin only)

Class-level `@PreAuthorize("hasRole('ADMIN')")` (connection management is Admin-only, FR-004). Registered (not allow-listed) so `RbacEndpointInventoryTest` enforces the gate. Errors via `AtsExceptionHandler` (`@Order(HIGHEST_PRECEDENCE)`, no-oracle envelope `{ "error": "..." }`, re-throwing `AccessDeniedException`/`AuthenticationException` — the F31 lesson).

| Method | Path | Body | 200/2xx | Errors |
|---|---|---|---|---|
| `GET` | `/api/internal/ats/connection` | — | `{ provider, status, credentialSet, lastVerifiedAt, lastSyncAt, degraded, deadLetterCount }` (never the key) | 403 non-Admin |
| `POST` | `/api/internal/ats/connection` | `{ apiKey }` | `200` `{ status: CONNECTED, lastVerifiedAt }` after live verify | `400 invalid_request` (blank key); `409 verification_failed` (bad/revoked key — no key/body echo); 403 |
| `DELETE` | `/api/internal/ats/connection` | — | `204` (key destroyed, sync/write-back stop, pending write-backs cancelled) | 403 |
| `GET` | `/api/internal/ats/sync-status` | — | `{ lastSyncAt, lastOutcome, processed, created, updated, skipped, status }` | 403 |
| `GET` | `/api/internal/ats/dead-letters` | — | `[ { writeBackId, candidateId, type, attemptCount, lastOutcomeCategory, updatedAt } ]` (no PII) | 403 |

**Recruiter health view (FR-004)**: a read-only `GET /api/internal/ats/connection` health projection MAY be exposed to Recruiter (state + timestamps, never the key). HM/Interviewer/Read-only: no access. (Decision for the plan: gate the mutating endpoints `hasRole('ADMIN')`; the health GET `hasAnyRole('ADMIN','RECRUITER')`.)

**Note**: there is **no inbound ingestion endpoint** (poll-only, FR-011). The only `/api/internal/ats` endpoints are the Admin management/status reads above.

## C. Greenhouse Harvest HTTP shape (honored by `GreenhouseAtsClient` + `StubGreenhouse`)

- **Auth**: HTTP Basic, `Authorization: Basic base64(apiKey + ":")` (key as username, empty password).
- **Verify**: `GET {base}/v1/jobs?per_page=1` → 200 ok / 401 bad key.
- **Pull candidates**: `GET {base}/v1/candidates?per_page=N&page=P[&updated_after=<cursor>]` → array of candidate objects, each with nested `applications[]` (each carries `id`, `jobs[].{id,name}`, `current_stage.name`). The client flattens to one `AtsCandidateRecord` per application; `externalRef = "gh_app:" + application.id`. Pagination via `Link` header / `page`. Rate limit via `Retry-After` on 429.
- **Write-back**: `POST {base}/v1/candidates/{candidateId}/activity_feed/notes` with `{ user_id, body, visibility }` (a note on the candidate's activity feed). `On-Behalf-Of` header per Harvest. Returns the created note id (the opaque `providerActivityRef`).
- **Field discipline**: the client parses ONLY `id`, `first_name`/`last_name`, `email_addresses[].value`, `phone_numbers[].value`, and the application `job`/`current_stage.name` via explicit reads — never `attachments`, `custom_fields`, `tags`, `eeoc`, `notes` (FR-029). Provider error bodies are reduced to status/category (FR-003).

### `StubGreenhouse` (integration-pending) behaviors the tests drive

- Method+path matching; per-(method,path) status SEQUENCES (e.g. `503,503,200`).
- Seeded candidate/application/job store; `addCandidate(externalRef, name, email, jobId, jobTitle, stage)`, `updateStage(...)`.
- Records activity POSTs (`notes(candidateId)`) for write-back assertions.
- Injectable `Retry-After`; `gate(n)` latch for non-vacuous concurrency.
- Returns 200 with a seeded id_token-free body; `reset()` per `@BeforeEach`.
- Seeds attachment/custom/EEOC fields into candidate objects so the SC-005/FR-029 test asserts they never reach the model/log (non-circular).
