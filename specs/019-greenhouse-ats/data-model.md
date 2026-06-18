# Phase 1 Data Model: ATS Integration — Greenhouse (F40)

Three new collections + an additive `candidates` extension. All ATS rows except the encrypted credential and the candidate PII hold **ids / enums / instants / counts only** (no PII), consistent with `emailDispatches`/`slaNudgeDrafts`. One Mongock changeset `ChangeUnit018_AtsConnectorIndexes` (order **"018"** off the highest applied **"017"**).

## 1. `atsConnections` (NEW) — one document per workspace

| Field | Type | Notes |
|---|---|---|
| `_id` | ObjectId | |
| `workspaceId` | String | Unique. One connection per workspace. |
| `provider` | String (`AtsProvider`) | `GREENHOUSE`. |
| `apiKey` | String (encrypted) | **Write-only secret.** Registered in `MongoPiiConfig` (`PiiStringConverter`). `@JsonIgnore` + `@Field(write=NON_NULL)` + omitted from `toString()`. Written via `$set` (converter encrypts); cleared on disconnect via `$set null` (never `$unset`). |
| `status` | String (`AtsConnectionStatus`) | `INTEGRATION_PENDING` / `CONNECTED` / `NEEDS_REAUTH` / `ERROR` / `DISCONNECTED`. |
| `lastVerifiedAt` | Instant | Set on successful verify. |
| `lastSyncAt` | Instant | Set on successful sync run. |
| `lastErrorCategory` | String | Value-free category only (never a provider body). Drives the degraded indicator. |
| `syncCursor` | String | Opaque "updated-after" cursor for incremental polls (nullable). |
| `createdAt` / `updatedAt` | Instant | |

**Derived (DTO only)**: `credentialSet` boolean — never the key.
**State machine**: `INTEGRATION_PENDING → CONNECTED` (verify ok) ; `CONNECTED → NEEDS_REAUTH` (AUTH classify) ; `CONNECTED → ERROR` (transient/degraded) ; `* → DISCONNECTED` (Admin disconnect, key destroyed) ; `NEEDS_REAUTH → CONNECTED` (re-verify).

## 2. `atsWriteBacks` (NEW) — the outbound outbox (no PII)

| Field | Type | Notes |
|---|---|---|
| `_id` | ObjectId | |
| `workspaceId` | String | |
| `candidateId` | String | Internal id (non-PII). |
| `atsExternalRef` | String | The application id the activity targets. |
| `type` | String (`AtsWriteBackType`) | `LINK_SENT`/`CONFIRMED`/`RESCHEDULED`/`CANCELLED`/`NO_SHOW`/`FEEDBACK_SUBMITTED`. |
| `idempotencyKey` | String | Length-prefixed sha256 of `{workspaceId,candidateId,type,eventInstantMillis}`. Unique with workspaceId. |
| `status` | String (`AtsWriteBackStatus`) | `PENDING`/`SENDING`/`DELIVERED`/`DEAD_LETTER`/`CANCELLED`. **No `@Version`.** |
| `eventAt` | Instant | The originating scheduling event instant (drives the note text; the workspace zone is applied at render). |
| `nextAttemptAt` | Instant | Backoff gate. |
| `attemptCount` | int | Incremented per claim; vs `retry-max-attempts`. |
| `providerActivityRef` | String | Opaque provider id on DELIVERED (best-effort; nullable). |
| `lastOutcomeCategory` | String | Value-free. |
| `createdAt` / `updatedAt` | Instant | |

**Enqueue**: `repo.insert` + catch `DuplicateKeyException` → return existing (idempotent).
**Claim**: `findAndModify({_id,status:PENDING,nextAttemptAt<=now} → SENDING, inc attemptCount)`; lost claim = no-op.
**Outcomes**: DELIVERED (provider accept) ; re-queue SENDING→PENDING + backoff (TRANSIENT, attempt<cap) ; DEAD_LETTER (FATAL or cap) + `DeadLetterService` + notify ; CANCELLED (disconnect or candidate erasure sweep).
**Reaper**: SENDING older than `reaper-threshold` → reconciled in-flight (the F22 `SENT_UNCONFIRMED` honest bound), never blind re-send.

## 3. `atsSyncRuns` (NEW) — status surface + audit (no PII)

| Field | Type | Notes |
|---|---|---|
| `_id` | ObjectId | |
| `workspaceId` | String | |
| `startedAt` / `finishedAt` | Instant | |
| `outcome` | String | `SUCCESS` / `PARTIAL` / `FAILED`. |
| `processed` / `created` / `updated` / `skipped` | int | Counts only. |
| `errorCategory` | String | Value-free (nullable). |

Drives "last successful sync" + the degraded indicator; bounded read `findByWorkspaceId` newest-first. (Optional retention cap deferred — low volume.)

## 4. `candidates` (EXTEND, F04) — additive ATS-link fields

| Field | Type | Notes |
|---|---|---|
| `atsProvider` | String (`AtsProvider`) | `@Field(write=NON_NULL)`. Null for native candidates. |
| `atsExternalRef` | String | Authoritative reconcile key (Greenhouse application id). `@Field(write=NON_NULL)`. **Retained on erasure** (resurrection guard) — see below. |
| `atsExternalJobId` | String | External job/requisition id. `@Field(write=NON_NULL)`. (F51 will scope HM visibility over this.) |
| `atsExternalJobTitle` | String | Requisition title (not candidate PII; kept out of logs by discipline). `@Field(write=NON_NULL)`. |
| `atsStageLabel` | String (**encrypted**) | Raw external stage label (free text, PII-adjacent — FR-022 no-log). Registered in `MongoPiiConfig`. `@JsonIgnore` + `@Field(write=NON_NULL)` + omitted from `toString()`. Cleared on erasure via `$set null`. |
| `atsSyncedAt` | Instant | Last successful inbound update for this candidate. |

**Reconcile (explicit RESOLVE-then-guarded-WRITE — NOT a single `upsert` with `erasureState:ACTIVE` in the filter)**: (1) resolve via `findByWorkspaceIdAndAtsProviderAndAtsExternalRef` with **no** erasure filter; if absent, try `findByWorkspaceIdAndEmailHash` to adopt a native candidate that has **no** `atsExternalRef` (never adopt one that already has a *different* ref). (2) If a row is found → guarded `updateFirst({_id, erasureState:ACTIVE} → $set ATS fields)`; an ERASED row no-ops. (3) Only when genuinely absent → `repo.insert` + catch `DuplicateKeyException` (idempotent on the partial-unique index). **Rationale**: a single `upsert=true` whose filter includes `erasureState:ACTIVE` would miss an ERASED row and *insert a fresh PII-populated doc* — a resurrection. The resolve-then-guard shape closes that hole.
**Erasure (extends `CandidateErasureService.wipe`)**: `$set atsStageLabel = null`, `$set atsExternalJobTitle = null`; **retain** `atsProvider`/`atsExternalRef`/`atsExternalJobId` (non-PII anchor so a later poll is a guarded no-op, not a resurrection); cancel pending `atsWriteBacks` for the candidate (`status PENDING → CANCELLED`) via the narrow `AtsWriteBackInvalidator` lazy seam (the F31 `SlaDraftInvalidator` cycle-break). `toString()` continues to omit all PII.

## 5. `ChangeUnit018_AtsConnectorIndexes` (order "018", pure ASCII)

Native `createIndex` + targeted `dropIndex` rollback (never `dropIndexes()`).

| Collection | Index | Type |
|---|---|---|
| `atsConnections` | `{workspaceId: 1}` | **unique** (one connection/workspace; concurrent first-connect → `DuplicateKeyException` = idempotent) |
| `atsConnections` | `{status: 1}` | non-unique (poll iterates CONNECTED) |
| `atsWriteBacks` | `{workspaceId: 1, idempotencyKey: 1}` | **unique** (exactly-once enqueue) |
| `atsWriteBacks` | `{status: 1, nextAttemptAt: 1}` | non-unique (drain scan) |
| `atsWriteBacks` | `{workspaceId: 1, candidateId: 1, status: 1}` | non-unique (erasure sweep) |
| `atsSyncRuns` | `{workspaceId: 1, startedAt: -1}` | non-unique (status read) |
| `candidates` | `{workspaceId: 1, atsProvider: 1, atsExternalRef: 1}` | **unique PARTIAL** over `{atsExternalRef: {$exists: true}}` |

**Partial-index footgun (F01 lesson)**: `atsExternalRef`/`atsProvider` are `@Field(write=NON_NULL)` so a native candidate (null ref) is omitted from BSON and does NOT collide on the partial unique index. The partial filter keys on `atsExternalRef` present.

## Index usage by access path

- Poll workspace iteration → `atsConnections {status}`.
- Reconcile read/upsert → `candidates {workspaceId,atsProvider,atsExternalRef}` (partial unique) + the existing `{workspaceId,emailHash}` (ChangeUnit005) for the adopt path.
- Write-back enqueue/claim → `atsWriteBacks {workspaceId,idempotencyKey}` + `{status,nextAttemptAt}`.
- Erasure sweep → `atsWriteBacks {workspaceId,candidateId,status}`.
- Status surface → `atsConnections {workspaceId}` + `atsSyncRuns {workspaceId,startedAt:-1}` + a `count` on `atsWriteBacks {status:DEAD_LETTER}`.
