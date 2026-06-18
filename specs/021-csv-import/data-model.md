# Phase 1 Data Model — Standalone CSV Import Mode (F42)

Two new collections (`csvImportJobs`, `csvImportFiles`) + an additive extension of the existing `candidates`. One new Mongock changeset `ChangeUnit020_CsvImportIndexes` (order **"020"** off the highest applied **"019"**). All times are absolute `Instant`. PII / PII-adjacent fields are converter-encrypted at rest (registered in `MongoPiiConfig`); job/result metadata is ids/enums/counts/instants only (no plaintext PII).

---

## 1. Collection `csvImportJobs` (NEW)

One document per upload — the lifecycle, counters, and per-row results that drive the status surface and audit. **No plaintext candidate PII.**

| Field | Type | Notes |
|---|---|---|
| `_id` | String (ObjectId) | Job id (returned to the client). |
| `workspaceId` | String | Isolation key; every read is workspace-scoped. |
| `actorMemberId` | String | The uploading member (internal id; non-PII). |
| `status` | `CsvImportJobStatus` | State machine below. |
| `originalFilename` | String | For the recruiter's reference. **Not PII per se but treated as low-sensitivity**; never logged. (A filename is recruiter-chosen, not candidate data.) |
| `fileId` | String | FK → `csvImportFiles._id` while the blob exists; null after disposal. |
| `totalRows` | int | Data rows parsed (N); 0 until parsed. |
| `importedCount` | int | Rows committed as new candidates. |
| `mergedCount` | int | Duplicate rows resolved as merge. |
| `skippedCount` | int | Duplicate rows resolved/expired as skip. |
| `rejectedCount` | int | Rows failing validation. |
| `duplicatePendingCount` | int | Duplicate rows awaiting decision. |
| `rowResults` | List\<`CsvImportRowResult`\> | Per-row outcomes (embedded; value-free). Bounded by max-row-count. |
| `rejectionReason` | `CsvImportRejectReason`? | Whole-file reject cause (SCHEMA_INVALID / TOO_MANY_INVALID / OVER_LIMIT); null otherwise. |
| `expiresAt` | Instant | `createdAt + job-ttl`; the reaper's expiry boundary. |
| `createdAt` | Instant | Upload accepted. |
| `updatedAt` | Instant | Last state change (claim/processing/terminal). |
| `completedAt` | Instant? | Terminal timestamp. |

**Embedded `CsvImportRowResult`** (no raw values): `rowNumber` (int, logical/record number), `status` (`CsvImportRowStatus`), `failingField` (String?, e.g. `"email"`), `reason` (`CsvRowFailureReason`? enum — MISSING_REQUIRED / INVALID_EMAIL / MALFORMED_ROW / FIELD_TOO_LONG), `existingCandidateId` (String?, set for DUPLICATE_PENDING/MERGED/SKIPPED — internal id only), `candidateId` (String?, set for IMPORTED/MERGED).

> The duplicate dedup key needed to resolve a row is the row's `emailHash` (keyed, non-PII-recoverable) — held transiently during processing; if a `DUPLICATE_PENDING` result must persist a matcher across the await window it stores the `emailHash` and/or `existingCandidateId`, **never plaintext email** (FR-021).

### `CsvImportJobStatus` state machine

```
ACCEPTED ──claim CAS──▶ PROCESSING ──┬─ all rows terminal, no pending dup ──▶ COMPLETED
                                     ├─ schema invalid / >80% invalid / over-limit ─▶ REJECTED
                                     ├─ pending duplicates remain ──▶ AWAITING_DUPLICATE_DECISION
                                     └─ uncaught error (dead-letter) ──▶ FAILED
AWAITING_DUPLICATE_DECISION ──resolve (all pending decided)──▶ COMPLETED
AWAITING_DUPLICATE_DECISION ──TTL reaper (default remaining→skip)──▶ EXPIRED
PROCESSING ──orphan reaper (stuck past threshold)──▶ FAILED (or re-claimed)
(every terminal: COMPLETED/REJECTED/FAILED/EXPIRED ⇒ dispose csvImportFiles blob, fileId→null)
```

- **ACCEPTED→PROCESSING** is a single-winner `findAndModify({_id,status:ACCEPTED}→PROCESSING, returnNew)`; a double-pick matches nothing (no-op).
- **Resolve** is allowed only from `AWAITING_DUPLICATE_DECISION` (else 409); idempotent; per-row or whole-set.
- Blob disposal is idempotent (delete-by-jobId; already-gone = no-op).

---

## 2. Collection `csvImportFiles` (NEW)

The raw uploaded bytes, **encrypted at rest**, separate from the hot job doc (the `workspaceLogo` precedent). Disposed on every terminal path / TTL.

| Field | Type | Notes |
|---|---|---|
| `_id` | String (ObjectId) | File id. |
| `jobId` | String | FK → `csvImportJobs._id`; **unique**. |
| `workspaceId` | String | Isolation. |
| `dataBase64` | String | base64 of the raw uploaded bytes — **converter-encrypted at rest** (registered in `MongoPiiConfig`; the `emailProviderCredential`/`statusToken` String-converter precedent — NOT `WorkspaceLogo`, which stores `byte[]` cleartext). Stored as a `String` because `PiiStringConverter` cannot encrypt a `byte[]`. `@JsonIgnore`, omitted from `toString()`. |
| `contentType` | String | As received (informational; parsing is content-agnostic CSV text). |
| `sizeBytes` | long | The validated size (≤ in-service limit). |
| `createdAt` | Instant | For an optional TTL-index backstop. |

> Encrypting `dataBase64` means a raw-driver read of `csvImportFiles` shows ciphertext (FR-021). The collection is deleted per-job on terminal; an optional Mongo TTL index on `createdAt` (e.g. 48 h) is a defense-in-depth backstop in case a terminal path is missed (the application reaper is the primary disposer — D8).

---

## 3. Extension of `candidates` (F04) — additive provenance

| Field | Type | Notes |
|---|---|---|
| `origin` | `CandidateOrigin` | `{NATIVE, ATS, CSV_IMPORT}`. `@Field(write=NON_NULL)` (a legacy/native doc omits it; read as NATIVE). Non-PII; **retained on erasure**. |
| `importJobId` | String? | The `csvImportJobs._id` that created/last-merged this candidate (CSV provenance link). `@Field(write=NON_NULL)`. Non-PII; retained on erasure. |
| `importStageLabel` | String? | CSV `stage` free-text label. **Encrypted at rest** (registered in `MongoPiiConfig`, the `atsStageLabel` precedent). `@JsonIgnore` + `@Field(write=NON_NULL)`; omitted from `toString()`. PII-adjacent; **`$set null` on erasure** (NEVER `$unset` — the F03 converter trap). |
| `importRequisitionLabel` | String? | CSV `requisition` free-text reference. Plaintext (a requisition attribute, not candidate PII — the `atsExternalJobTitle` precedent), kept out of logs by discipline. `@Field(write=NON_NULL)`; cleared `$set null` on erasure. |

**Decision (was deferred — now resolved per Backend/Security review)**: the CSV `stage` and `requisition` are **candidate content**, NOT ATS-provider data, so F42 stores them in the two **new additive** candidate fields above — distinct from the ATS `atsStageLabel`/`atsExternalJobTitle`/`atsExternalJobId` fields, which stay ATS-only. This guarantees an `origin=CSV_IMPORT` record never populates an ATS reconcile field (SC-014), and (because a CSV candidate has no `atsProvider`) the ATS reconcile lookup can never match it regardless. No other candidate field changes; the existing encrypted `name`/`email`/`phone` + `emailHash` and the consent/erasure/retention fields are reused unchanged.

### Erasure interaction
`CandidateErasureService.wipe` is **EXTENDED** for F42 to clear the two new content fields: `importStageLabel` via **`$set null`** (converter-managed — NEVER `$unset`, the F03 `ClassCastException` trap, the `atsStageLabel` precedent) and `importRequisitionLabel` via `$set null`. `origin` and `importJobId` are non-PII and **retained** (like the ATS reconcile anchor). Both clears fold into the existing single guarded `updateFirst({_id, workspaceId, erasureState:ACTIVE} …)` wipe — atomic, no resurrection window. `importStageLabel` MUST be registered in `MongoPiiConfig` (a missing registration would store it cleartext — the obligation moves together with adding the field). No new invalidator is needed — an in-flight import holds no candidate-keyed *sender* record (a duplicate-pending row only references an existing candidate and is resolved/expired); erasure of an existing candidate simply makes a later merge no-op via the active-state guard (FR-014).

---

## 4. Mongock `ChangeUnit020_CsvImportIndexes` (order "020")

Native `createIndex` + targeted `dropIndex` rollback (never `dropIndexes()` — the F00.1 lesson). Pure-ASCII source (the F30 lesson).

**`csvImportJobs`**
- `{ workspaceId: 1, _id: 1 }` — workspace-scoped status read (no-oracle resolve).
- `{ status: 1, createdAt: 1 }` — due-sweep finder (ACCEPTED, oldest first).
- `{ status: 1, expiresAt: 1 }` — TTL/expiry reaper finder.
- `{ status: 1, updatedAt: 1 }` — orphan-PROCESSING reaper finder.

**`csvImportFiles`**
- `{ jobId: 1 }` **unique** — one blob per job; the read/dispose key.
- *(optional defense-in-depth)* TTL index on `{ createdAt: 1 }` `expireAfterSeconds` (e.g. 48 h) — backstop disposal (D8); the application reaper is primary.

**`candidates`**
- `{ workspaceId: 1, origin: 1 }` — non-unique; supports later pipeline reads by provenance (F50/F51). Both fields non-null on CSV/ATS rows; legacy/native rows omit `origin` (`write=NON_NULL`) so they simply don't appear under a `origin=` filter — acceptable (a null-origin candidate is NATIVE by definition).
- `{ workspaceId: 1, emailHash: 1 }` **unique, PARTIAL** over `{ emailHash: {$exists:true}, origin: "CSV_IMPORT" }` — makes the CSV-create path collide deterministically so SC-013 is **provable**, not best-effort. The existing `{workspaceId, emailHash}` (ChangeUnit005) is **non-unique** by design (an erased row `$unset`s `emailHash`, and a hard-unique full index would collide on the present-as-null footgun — the F01 lesson). The partial filter keys only on rows that have an `emailHash` AND `origin:"CSV_IMPORT"`, so: an erased CSV candidate (`emailHash` unset) drops out (re-import can re-create — correct); a NATIVE/ATS candidate (`origin != CSV_IMPORT`) is excluded (it is caught by the duplicate-flag lookup path before insert). Two concurrent CSV jobs inserting the same new email → one wins, the other catches `DuplicateKeyException` → re-reads and flags/merges (the F40 `insert`-catch-`DuplicateKeyException` precedent). **Honest residual**: a CSV-vs-NATIVE concurrent create of the same brand-new email is still the best-effort lookup-then-insert convergence (no cross-origin unique constraint), but SC-013 (two concurrent *import* jobs) is now a hard guarantee.

> The general duplicate-lookup index `{workspaceId, emailHash}` (non-unique, ChangeUnit005) is reused for the existing-candidate dedup read; the new partial-unique index above is additive and scoped to CSV rows.

---

## 5. Config (`cadence.import.*` → `ImportProperties`)

| Key | Default | Purpose |
|---|---|---|
| `max-file-size` | `5MB` | In-service size gate (FR-004); **both** `spring.servlet.multipart.max-file-size` AND `max-request-size` set above it (e.g. 6 MB) (D9). |
| `max-row-count` | `10000` | Row-count DoS bound (FR-004). |
| `max-field-length` | `4096` | Per-cell length bound (FR-004 memory guard). |
| `reject-ratio` | `0.80` | >80% whole-file reject threshold (FR-008/D7). |
| `sweep-fixed-delay` | `PT5S` | Worker poll interval (D1). |
| `sweep-batch-limit` | `20` | Jobs claimed per sweep (`Pageable` cap). |
| `job-ttl` | `PT24H` | Unresolved-duplicate/orphan expiry (FR-021a/D8). |
| `processing-threshold` | `PT15M` | Orphan-PROCESSING reaper bound (invariant `> sweep-delay + max per-job time`). |

Test profile (`application-test.yml`) overrides: tiny `max-file-size`/`max-row-count`, `job-ttl: PT2S`, `sweep-fixed-delay` short, for deterministic tests (stamp `expiresAt`/`updatedAt` into the past rather than wall-clock sleeps — the F23 lesson).
