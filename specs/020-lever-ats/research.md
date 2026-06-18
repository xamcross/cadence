# Phase 0 Research: ATS Integration — Lever (F41)

F41 is the **second** ATS connector. The connector contract, sync orchestration, write-back outbox, retry/dead-letter, role model, PII protections, and the integration-pending stub harness all already exist (F40, verified against the real source). This research resolves only what is genuinely new: the Lever Data API shape, and the connection/routing model changes required for **two providers to coexist in one workspace**. No NEEDS CLARIFICATION remain (the clarify session closed credential model, coexistence cardinality, write-back set, poll-only, and stage representation).

## D1 — Lever credential & API shape (FR-001/FR-002/FR-006)

- **Decision**: Workspace-scoped **Lever Data API key**, authenticated **HTTP Basic with the key as the username and an empty password** — byte-identical to the F40 Greenhouse `basic(apiKey)` helper (`Base64(key + ":")`). No OAuth, no token refresh.
- **Rationale**: Mirrors the F40 decision so the two connectors stay structurally identical (one write-only encrypted secret, no OAuth app registration). Lever's Data API supports key-based Basic auth exactly like Greenhouse Harvest.
- **Endpoints the `LeverAtsClient` uses** (base `https://api.lever.co`, stubbed by `StubLever`):
  - **verify** → `GET /v1/opportunities?limit=1` (200 = live; 401/403 = needs-reauth).
  - **fetch** → `GET /v1/opportunities?limit=<page>&expand=stage&expand=applications` — Lever paginates with an opaque `next` offset token + `hasNext`; the `next` value is stored as the `syncCursor`. Incremental polls add `updated_at_start=<cursor-instant>` where supported.
  - **pushActivity** → `POST /v1/opportunities/{id}/notes` with body `{ "value": "<non-PII note>" }`.
- **Alternatives considered**: Lever OAuth (rejected — token-refresh machinery, out of scope per spec); webhooks for inbound (rejected — FR-011 no unauthenticated inbound endpoint, matches F40).

## D2 — External reference, job/posting & stage mapping (FR-006/FR-008/FR-010)

- **Decision**: `externalRef` = the **Lever opportunity id** (`data[].id`). The associated requisition is the first posting (`applications[0].posting` id; title resolved via `expand` or a bounded `/v1/postings/{id}` read). The **stage** is the raw `stage.text` obtained via `expand=stage` (Lever returns `stage` as an id otherwise), stored verbatim as the free-text label.
- **Rationale**: The opportunity is the candidate-on-a-job unit Cadence schedules against (the F40 "Greenhouse application id" precedent — keeps multi-posting pipelines distinct). Raw stage label, no internal taxonomy (FR-010, identical to F40).
- **Data minimization (FR-029)**: parse ONLY name/email/phone/opportunity-id/posting/stage via explicit `JsonNode.path` reads. **Never** read `links`, `tags`, `sources`, `origin`, `headline`, `archived` reason text, or call any EEO endpoint. (Lever exposes these on the opportunity payload, so the exclusion is parse-discipline + a non-circular `SENTINEL` stub test, the F40 precedent.)

## D3 — Multi-connector coexistence: the connection key change (FR-001/FR-030/FR-031)

- **Finding (the load-bearing one, grounded in real code)**: F40's `atsConnections` carries a **unique `{workspaceId}` index** (`ChangeUnit018`) and the whole connection path is **workspace-keyed**: `AtsConnectionRepository.findByWorkspaceId` returns `Optional`, `connect`/`disconnect`/`health` filter on `{workspaceId}` only, and `AtsConnectionService.connect` upserts on `{workspaceId}`. A second (Lever) connection in the same workspace would collide on the unique index, and `findByWorkspaceId` (Optional) would throw `IncorrectResultSizeDataAccessException` with two rows.
- **Decision**: Migrate the uniqueness to **`{workspaceId, provider}`** (`ChangeUnit019`, order "019" off applied "018"): drop the unique `{workspaceId}` index, create unique `{workspaceId, provider}`. **No back-fill of data is needed** — every existing `atsConnections` row already stores `provider` (the field exists; only the *index* changes). The repository's `Optional findByWorkspaceId` becomes `List findByWorkspaceId` (for the "list both providers" surface) + a new `Optional findByWorkspaceIdAndProvider`.
- **Rationale**: This is the single unavoidable schema change FR-031 calls out; it is *data*, not topology (C2 still PASS). The `{workspaceId, provider}` shape is the F11 calendar provider-discriminator precedent.

## D4 — Write-back provider routing (FR-016/FR-016 routing/SC-013c)

- **Finding**: F40's `AtsWriteBackService.claimAndDeliver` resolves the connection via `connections.findByWorkspaceId(...)` and selects the connector by `conn.getProvider()`. With two connections per workspace that lookup is ambiguous, and the write-back row carries **no provider** of its own.
- **Decision**: Add a **`provider` field to `AtsWriteBack`**, set at `enqueue` from `candidate.getAtsProvider()` (the candidate's single provider of record). `claimAndDeliver` loads the connection via `findByWorkspaceIdAndProvider(row.workspaceId, row.provider)` and the NEEDS_REAUTH flip filters `{workspaceId, provider}`. This makes a mis-route structurally impossible — the recorded row names its target provider (FR-016, FR-026 auditable-routing, SC-013c).
- **Idempotency key**: stays `sha256(workspaceId, candidateId, type, eventMillis)` — a candidate holds exactly one `atsProvider`, so the same `candidateId` cannot belong to two providers and the key cannot collide across providers. (Documented; no change needed.)

## D5 — Provider isolation comes (mostly) for free (FR-012/FR-022/FR-020a/SC-014)

- **Finding**: `AtsSyncScheduler.sweep` already iterates `connections.findByStatus(CONNECTED)` and calls `syncWorkspace(conn)` per connection inside a **per-connection try/catch**; a failure flips only that connection and records its own `AtsSyncRun`. The write-back drain is per-row. So a Lever outage flips only the Lever connection and cannot stall the Greenhouse iteration.
- **Decision**: Keep the **single `"ats-sync-scan"` checkpoint** that iterates all connected connections; do **not** introduce per-provider checkpoint documents. The spec's FR-012 "checkpoint independent of the Greenhouse sync" intent is satisfied by per-connection iteration + per-connection isolation, which is *stronger* than two schedules (one scan, no cross-provider blocking). Adding separate checkpoints would be unjustified complexity (constitution §I simplicity). `syncWorkspace` must change its connection-status `updateFirst` filters from `{workspaceId}` to `{workspaceId, provider}` and use `conn.getProvider()` in `reconcile` (today hardcoded `GREENHOUSE`).
- **Rationale**: Honest reconciliation of the spec wording with the simplest correct design; surfaced explicitly in the plan so it is a decision, not an omission.

## D6 — Per-provider status surface (FR-019/SC-011)

- **Decision**: Add a **`provider` field to `AtsSyncRun`** so "last successful sync" is per-provider; add `findFirstByWorkspaceIdAndProviderOrderByStartedAtDesc` and a `{workspaceId, provider, startedAt:-1}` index. Dead-letter counts become per-provider via the new `AtsWriteBack.provider` (`count({workspaceId, provider, status:DEAD_LETTER})`). The Admin controller exposes a **list of both providers' health** (`GET /api/internal/ats/connections`) plus provider-scoped `POST`/`DELETE`/`sync-status`/`dead-letters` at `/api/internal/ats/{provider}/...`.
- **Rationale**: SC-011 requires per-provider state, last-sync, and dead-letters. The F40 single-provider endpoints are migrated to provider-parameterized paths (the F40 controller hardcodes `AtsProvider.GREENHOUSE` at line 54 — this is replaced by a validated `{provider}` path variable; an unknown value → 400 via the existing `AtsExceptionHandler`).

## D7 — Erasure & disconnect become provider-scoped (FR-005/FR-015/SC-015)

- **Decision**: `disconnect(workspaceId, provider)` cancels pending write-backs for **that provider only** via a new `AtsWriteBackInvalidator.cancelPendingForWorkspaceAndProvider`; a coexisting Greenhouse queue is untouched (SC-015 second clause). Candidate erasure keeps the existing `cancelPendingForCandidate` (already provider-agnostic — an erased candidate has one provider, so its pending rows are swept regardless). The resolve-then-active-state-guarded-write resurrection defense is **unchanged** and already correct (verified in `AtsSyncService.reconcile`); F41 only swaps the hardcoded `GREENHOUSE` for `conn.getProvider()`. The FR-008 cross-provider non-merge is **already enforced** by the existing `c.getAtsExternalRef() == null` adopt-guard (a candidate keyed to any provider has a non-null ref, so it is never email-adopted by a second provider).
- **Rationale**: SC-013b/SC-015 are mostly satisfied by the existing guards once routing carries provider; the only new code is the provider-scoped disconnect sweep.

## D8 — Stub & CI (FR-032/SC-005)

- **Decision**: `StubLever` is a JDK `com.sun.net.httpserver.HttpServer` **sibling of `StubGreenhouse`**, a **JVM-lifetime singleton** (do NOT `@AfterAll stop()` — the F40 dead-port footgun), seeding opportunities with `SENTINEL`-marked minimization fields (links/tags/sources/eeo) and matching `POST /v1/opportunities/{id}/notes`. `ci.yml` gains Lever candidate-name/credential sentinels + an `api.lever.co` base-URL guard on `LeverAtsClient.java` (the F11 `graph.microsoft.com` / F40 `harvest.greenhouse.io` precedent). The F40 honest-residual `AtsLogPiiScanTest` + per-feature sentinel block is **added now** (closes the F40 follow-up).
- **Live-promotion gap (carried to the mandatory security re-review, FR-032)**: Lever's notes endpoint may require a `perform_as` user id (the analogue of F40's Harvest candidate-id-vs-application-id gap); the adapter addresses `externalRef` directly (the stub matches any id), so before live credentials the real `perform_as`/note-addressing must be confirmed.

## Buildability confirmation

All four clarified decisions (API key, one-per-(workspace,provider), expanded write-back set routed by provider, raw stage label) are buildable on the fixed stack with **no new runtime dependency** (Lever via `RestClient` on `JdkClientHttpRequestFactory`, the F10/F40 pattern). The only schema change is the `ChangeUnit019` index migration. No broker, no second service, no tool download.
