# Phase 1 Data Model: Join / Express-Interest Request Form (F70)

**Feature**: 029-join-interest-form | **Date**: 2026-06-23

## New collection: `interestRequests`

One document per access-interest submission. PII fields are encrypted at rest via the existing per-field converter; lookup/dedup uses keyed HMAC hashes that are stored as-is (never the plaintext or ciphertext email).

| Field | Type | Notes |
|---|---|---|
| `id` | ObjectId | `_id` |
| `workspaceId` | String | **server-resolved** from `cadence.interest.default-workspace-id` (FR-019); never from submitter input |
| `name` | String | **encrypted** (MongoPiiConfig converter). Submitter-claimed. ≤ 200 chars |
| `email` | String | **encrypted**. Submitter-claimed, **unverified**. ≤ 254 chars, validated format |
| `emailHash` | String | keyed HMAC `PiiCrypto.emailHash(email)`; stored as-is (NOT encrypted). Used for admin lookup + erasure discovery. `@Field(write=NON_NULL)` |
| `openEmailHash` | String | mirrors `emailHash` **only while open** (status NEW/REVIEWED); `$unset` on terminal/erasure. Backs the open-dedup unique index. `@Field(write=NON_NULL)` |
| `organization` | String? | **encrypted**, optional, `@Field(write=NON_NULL)`. ≤ 200 chars |
| `message` | String? | **encrypted**, optional, `@Field(write=NON_NULL)`. ≤ 2000 chars. Purpose-limited free text |
| `status` | String enum | `NEW` / `REVIEWED` / `INVITED` / `DISMISSED` |
| `submittedAt` | Instant | first submission time; retention clock origin |
| `updatedAt` | Instant | last coalesced-resubmit or status change |
| `lastActorMemberId` | String? | admin who last transitioned; `@Field(write=NON_NULL)` |
| `actionedAt` | Instant? | when last transitioned; `@Field(write=NON_NULL)` |
| `invitationId` | String? | back-link to the resulting `invitations._id` when `INVITED`; `@Field(write=NON_NULL)` |

**Not stored**: raw IP (only the in-memory hashed-IP rate-limit key), any verification token, any candidate linkage. No `LawfulBasis`/consent record (distinct data category from candidates; lawful basis is legitimate interest, disclosed in the privacy notice — FR-006).

### Status lifecycle (FR-013)

```
NEW ──review──► REVIEWED ──┐
 │                          ├──invite──► INVITED   (terminal; openEmailHash unset; invitationId set)
 ├──invite──────────────────┘
 │
 ├──dismiss─────────────────► DISMISSED            (terminal; openEmailHash unset)
 └──(reviewed)──dismiss/invite as above
```

- `NEW` and `REVIEWED` are **open** (carry `openEmailHash`, counted by SC-007 "active").
- `INVITED` and `DISMISSED` are **terminal** and not re-actionable; both `$unset openEmailHash` so a later submission from the same email creates a fresh `NEW` request (edge case "resubmission after a terminal request").
- All transitions use a status-guarded `findAndModify({_id, workspaceId, status:<from>} -> <to>)`; a concurrent/duplicate action on an already-terminal request matches nothing → no-op + conflict signal (FR-016, no double-invite).
- `REVIEWED` keeps the row in the queue but drops it from the default "needs triage" filter.

### Erasure (FR-022, admin-triggered only)

Single guarded `updateFirst`/`findAndModify`: `$set` the encrypted fields `name`/`email`/`organization`/`message` to `"[ERASED]"` (the converter encrypts the non-null marker fine — do NOT `$unset` an encrypted field; that hits the F03 ClassCastException trap), and `$unset emailHash` + `openEmailHash` (plain hashes; record no longer discoverable by email). Prefer this `$set`/`$unset` wipe over a row delete so the idempotent no-oracle 200 stays uniform. The `CandidateErasureService.wipe` precedent exactly. No public erasure endpoint.

### Retention purge (FR-021)

Hard-delete where `submittedAt < now(clock) - retentionPeriod(workspace)`. `WorkspaceConfig.retentionPeriodDays` is a **primitive `int`** (default `0`, never null), so `retentionPeriod = retentionPeriodDays <= 0 ? fallback : retentionPeriodDays` (the `0` case means "unset" → 180-day fallback, NOT "delete immediately"). Only scan workspaces where `cfg.isConfigured()` (mirror `RetentionScanTask.runScan`). Driven by `InterestRetentionScheduler`.

## Indexes — `ChangeUnit023_InterestRequestIndexes` (order "023" off applied "022")

1. **Unique partial** `{workspaceId: 1, openEmailHash: 1}` over `partialFilterExpression: {openEmailHash: {$exists: true}}` — one open request per email per workspace (dedup, FR-008/SC-007).
2. `{workspaceId: 1, status: 1, submittedAt: -1}` — admin queue list + status filter (FR-011), recent-first.
3. `{workspaceId: 1, submittedAt: 1}` — retention purge age scan (FR-021) AND the per-workspace flood-ceiling count gate (`count({workspaceId, submittedAt > now-window})`, FR-018/R6).
4. `{workspaceId: 1, emailHash: 1}` (non-unique) — admin lookup / erasure-by-email discovery (FR-022). (Distinct key pattern from #1, so no F42 same-pattern collision; and this is a new collection regardless.)

Native `createIndex` + targeted `dropIndex` rollback (never `dropIndexes()`). Source file pure ASCII (F30 lesson).

## MongoPiiConfig registration

Register `InterestRequest.class` properties `name`, `email`, `organization`, `message` → `PiiStringConverter` (the `Candidate`/`Invitation` precedent). `emailHash` and `openEmailHash` are **NOT** registered (keyed HMAC, stored as-is for lookup).

## Touched existing artifacts (additive)

- `RecruiterNotificationType` — add `INTEREST_REQUEST` (append-only enum value). Notification call is `RecruiterNotificationService.notify(workspaceId, null, INTEREST_REQUEST)` (3-arg, null candidateId — the `ATS_SYNC_FAILED` precedent); it persists a value-free in-app row and sends NO email.
- `SmtpEmailSender` / `OperationalEmailTemplates` — **NOT touched** (the in-app notification row needs no email; admin-email is out of scope for this version — R2).
- `WorkspaceConfig` — **unchanged** (retention reuses the existing primitive-`int` `retentionPeriodDays` with the `<=0` fallback above).
- SEO — `/request-access` is **`noindex`** (`seo: PRIVATE`); the `route-seo-inventory.spec` "exactly one indexable route" assertion, `robots.txt`, `sitemap.xml`, `_headers`, and `ci.yml:526` robots allow-set are **NOT touched** (R4).
- `lighthouserc.json` — add the `/request-access` url + matching `assertMatrix` pattern (the page renders its form shell with no backend call, so the F14 stub needs no new canned route).
- `RbacEndpointInventoryTest` — the internal admin endpoints carry `@PreAuthorize("hasRole('ADMIN')")`; the public endpoint rides the allow-listed `/api/public/` prefix (no change needed beyond the new controller existing).
