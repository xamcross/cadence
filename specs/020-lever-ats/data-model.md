# Phase 1 Data Model: ATS Integration — Lever (F41)

F41 reuses F40's three ATS collections (`atsConnections`, `atsWriteBacks`, `atsSyncRuns`) and the additive `candidates` ATS fields **unchanged in shape** — it makes them **provider-aware** so two connectors coexist. The deltas below are the *only* schema/field changes. One Mongock changeset `ChangeUnit019_AtsLeverMultiConnector` (order **"019"** off the highest applied **"018"**).

## Δ1. `AtsProvider` enum (EXTEND)

Add `LEVER`:

```java
public enum AtsProvider { GREENHOUSE, LEVER }
```

The value remains the always-non-null discriminator on `Candidate` and now on `AtsWriteBack`/`AtsSyncRun`.

## Δ2. `atsConnections` — uniqueness becomes per (workspace, provider)

| Field | Change |
|---|---|
| `workspaceId` | No longer unique alone. |
| `provider` (`AtsProvider`) | **Now part of the uniqueness key.** Field already exists; only the index changes. |

- **Index migration (ChangeUnit019)**: drop unique `{workspaceId}` → create unique **`{workspaceId, provider}`**. No data back-fill (every row already has `provider`). The non-unique `{status}` index (poll iteration) is unchanged.
- **State machine unchanged**: `INTEGRATION_PENDING → CONNECTED → {NEEDS_REAUTH | ERROR} → CONNECTED`; `* → DISCONNECTED`. Now per (workspace, provider).
- A workspace may hold one `GREENHOUSE` row **and** one `LEVER` row simultaneously; they never collide and operate independently.

## Δ3. `atsWriteBacks` — add the routing provider (NEW field)

| Field | Type | Notes |
|---|---|---|
| `provider` | String (`AtsProvider`) | **NEW.** Set at `enqueue` from `candidate.getAtsProvider()`. The routing key: `claimAndDeliver` loads `findByWorkspaceIdAndProvider(workspaceId, provider)` and delivers via that provider's connector. Makes mis-routing structurally impossible (FR-016, SC-013c). `@Field(write=NON_NULL)` (rows are always provider-bound; defensive). |

- All other fields unchanged (no PII; `idempotencyKey` formula unchanged — a candidate holds one provider, so the `{workspaceId,candidateId,type,eventMillis}` key cannot collide across providers).
- Enqueue / claim / outcomes / reaper semantics unchanged (the F40 `EmailDispatch` outbox precedent). **Two explicit `{workspaceId}`-only filters in `AtsWriteBackService` MUST become provider-scoped (review — confused-deputy fix):**
  - `claimAndDeliver` connection lookup: `connections.findByWorkspaceId(...)` → `findByWorkspaceIdAndProvider(row.workspaceId, row.provider)` (the Optional→List change alone would throw `IncorrectResultSizeDataAccessException` with two rows).
  - The NEEDS_REAUTH flip on an auth failure currently filters `Criteria.where("workspaceId")` only — it MUST add `.and("provider").is(row.provider)`, else a **Lever** auth failure could flip the **Greenhouse** connection to NEEDS_REAUTH (a real FR-022/SC-014 isolation violation).
- **Dead-letter count is now read per provider**: `count({workspaceId, provider, status:DEAD_LETTER})`. `AtsConnectionService.health` currently counts `{workspaceId, status:DEAD_LETTER}` with NO provider filter — it MUST add `provider`, else each per-provider health card shows the **sum of both** providers' dead-letters and a false `degraded` badge (SC-011).
- **Migration sequencing**: the provider-scoped disconnect cancel (`cancelPendingForWorkspaceAndProvider`) relies on `provider` being populated on every PENDING row; at integration-pending scale there are no pre-existing live PENDING rows, so no back-fill of `atsWriteBacks.provider` is needed (documented).

## Δ4. `atsSyncRuns` — add provider (NEW field)

| Field | Type | Notes |
|---|---|---|
| `provider` | String (`AtsProvider`) | **NEW.** Set in `recordRun(...)` from `conn.getProvider()`. Enables per-provider "last successful sync" (SC-011). |

- New finder `findFirstByWorkspaceIdAndProviderOrderByStartedAtDesc`. Counts/instants/category only — still no PII.

## Δ5. `candidates` (F40 ATS fields) — UNCHANGED in shape, now genuinely multi-provider

No field change. The existing fields already discriminate by provider:

| Field | Multi-provider behaviour |
|---|---|
| `atsProvider` (`AtsProvider`) | Now also takes `LEVER`. One provider of record per candidate. |
| `atsExternalRef` | Authoritative within its provider. The partial-unique index already includes `atsProvider`, so a `LEVER` opportunity id and a `GREENHOUSE` application id never collide. |
| `atsExternalJobId` / `atsExternalJobTitle` | Lever posting id/title (plaintext requisition attribute; kept out of logs by discipline). |
| `atsStageLabel` (**encrypted**) | Raw Lever stage label. Cleared `$set null` on erasure. |
| `atsSyncedAt` | Last inbound update. |

- **Reconcile (UNCHANGED resolve-then-guarded-write)** — `AtsSyncService.reconcile` swaps the hardcoded `AtsProvider.GREENHOUSE` for `conn.getProvider()` (4 sites: the two `findByWorkspaceIdAndAtsProviderAndAtsExternalRef` resolves, and the two `$set("atsProvider", …)` / insert writes). The cross-provider non-merge (FR-008) is **already enforced** by the existing email-adopt guard `c.getAtsExternalRef() == null` — a candidate keyed to any provider has a non-null ref and is never adopted by a second provider on a shared email.
- **Erasure (UNCHANGED)** — wipe `$set null`s `atsStageLabel`/`atsExternalJobTitle`, retains `atsProvider`/`atsExternalRef`/`atsExternalJobId` (resurrection anchor), and cancels the candidate's pending write-backs (provider-agnostic — the candidate has one provider).

## Δ6. `ChangeUnit019_AtsLeverMultiConnector` (order "019", pure ASCII)

Native `createIndex` + targeted `dropIndex` rollback (never `dropIndexes()`; CLAUDE.md Mongock rules). Drops one index, creates two.

| Collection | Operation | Index | Type |
|---|---|---|---|
| `atsConnections` | DROP | `{workspaceId: 1}` | (was unique) |
| `atsConnections` | CREATE | `{workspaceId: 1, provider: 1}` | **unique** (one connection per workspace+provider) |
| `atsSyncRuns` | CREATE | `{workspaceId: 1, provider: 1, startedAt: -1}` | non-unique (per-provider status read) |

- **No new index on `atsWriteBacks`** — the new `provider` field is read inside already-indexed scans (`{workspaceId,idempotencyKey}` enqueue, `{status,nextAttemptAt}` drain, `{workspaceId,candidateId,status}` sweep); the per-provider dead-letter `count` is a bounded read (the F40 dead-letter-list precedent, low volume). Documented; acceptable at MVP scale.
- **No `candidates` index change** — the partial-unique `{workspaceId, atsProvider, atsExternalRef}` already discriminates by provider (created in ChangeUnit018).
- **Rollback**: drop `{workspaceId, provider}` + `{workspaceId, provider, startedAt:-1}`, recreate unique `{workspaceId}`. (Rollback only safe before a second provider connects; documented — the standard Mongock caveat.)
- **Migration safety**: dropping the unique `{workspaceId}` and creating unique `{workspaceId, provider}` is non-destructive for existing single-provider data (each workspace has ≤1 Greenhouse row; `{workspaceId, provider}` is trivially satisfied). Pure-ASCII Java source (the F30 binary-detection lesson).

## Index usage by access path (post-F41)

- Poll workspace iteration → `atsConnections {status}` (unchanged; iterates both providers' CONNECTED rows).
- Connection resolve (connect/disconnect/health/deliver) → `atsConnections {workspaceId, provider}` (unique).
- Reconcile read/upsert → `candidates {workspaceId, atsProvider, atsExternalRef}` (partial unique) + `{workspaceId, emailHash}` (adopt path).
- Write-back enqueue/claim/route → `atsWriteBacks {workspaceId, idempotencyKey}` + `{status, nextAttemptAt}`; routing reads the row's `provider`.
- Erasure / disconnect sweep → `atsWriteBacks {workspaceId, candidateId, status}` (erasure) and a `{workspaceId, status}` filtered by `provider` in memory (disconnect; bounded).
- Per-provider status surface → `atsConnections {workspaceId, provider}` + `atsSyncRuns {workspaceId, provider, startedAt:-1}` + a `count` on `atsWriteBacks {workspaceId, provider, status:DEAD_LETTER}`.
