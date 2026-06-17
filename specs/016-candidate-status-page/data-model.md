# Phase 1 Data Model: Candidate Status Page (F30)

No new collection. Additive fields on `candidates`; one idempotency index on `erasureRequests`; one new Mongock changeset. All instants are absolute `Instant`; the one date is a `LocalDate` interpreted in the workspace zone.

## 1. `candidates` — additive status fields (extends F04 `Candidate`)

| Field | Type | Encrypted | Notes |
|---|---|---|---|
| `statusStage` | String | **Yes** (PiiStringConverter) | Recruiter free text — short stage label. `@Field(write=NON_NULL)` + `@JsonIgnore`. Null until first publish. |
| `statusNextStep` | String | **Yes** | Recruiter free text — plain-English next step / terminal message. `@Field(write=NON_NULL)` + `@JsonIgnore`. |
| `statusExpectedDate` | LocalDate | No | Required for `IN_PROGRESS`; nullable for terminal. Compared in the **workspace** zone (D5). |
| `statusOutcome` | `CandidateStatusOutcome` | No | `IN_PROGRESS` / `COMPLETE_OFFER` / `COMPLETE_REJECTED`. Null until first publish. |
| `statusPublishedAt` | Instant | No | Null ⇒ never published ⇒ `displayState=UNDER_REVIEW` (FR-006). |
| `statusPublishedByMemberId` | String | No | Internal member id (non-PII). |
| `statusToken` | String | **Yes** (reversible) | The raw access token, AES-256-GCM at rest, decrypted only to build the link (D2/D9). `@Field(write=NON_NULL)` + `@JsonIgnore`. Never logged. |
| `statusTokenHash` | String | No (already a hash) | `TokenHasher.hashToken(raw)`. `@Field(write=NON_NULL)` + `@JsonIgnore`. Partial-unique indexed; resolves inbound requests. |

`toString()` extended to omit `statusStage`, `statusNextStep`, `statusToken`, `statusTokenHash` (the F04/F13 discipline — never leak PII/credential via logs).

**MongoPiiConfig** (`configurePropertyConversions`) gains three registrations: `Candidate.class "statusStage"`, `"statusNextStep"`, `"statusToken"` → `PiiStringConverter` (the `SchedulingRequest.locationText` precedent; `statusToken` follows the F01.1 reversible-secret precedent).

## 2. `CandidateStatusOutcome` (new enum, append-only)

```
IN_PROGRESS         # the live, dated status
COMPLETE_OFFER      # terminal — honest concluded message, no date required
COMPLETE_REJECTED   # terminal — honest concluded message, no date required
```

## 3. `displayState` (derived, server-computed — not persisted)

`CandidateStatusService` injects `java.time.Clock` (the F01 `MutableClock`/`AuthTestConfig` `@Primary` pattern; `ClockConfig.clock()` backs off via `@ConditionalOnMissingBean`). "Today" = `LocalDate.ofInstant(Instant.now(clock), workspaceZone)` where `workspaceZone` is the `WorkspaceConfig` zone — never `LocalDate.now()`/`Instant.now()`. This makes the PAST_DATE boundary (SC-013) and the precedence matrix (SC-016) deterministic under a controlled clock. Resolved on the candidate view in strict precedence (D5):

| Order | `displayState` | Condition | Renders |
|---|---|---|---|
| 1 | `TERMINAL` | `statusOutcome ∈ {COMPLETE_OFFER, COMPLETE_REJECTED}` | concluded message (`statusNextStep`) + outcome |
| 2 | `PAST_DATE` | `IN_PROGRESS` & `statusExpectedDate < today@workspaceZone` | "past the expected date" framing + `statusStage` (FR-017) |
| 3 | `PUBLISHED` | `IN_PROGRESS` & `statusExpectedDate ≥ today@workspaceZone` | `statusStage` + `statusNextStep` + `statusExpectedDate` |
| 4 | `UNDER_REVIEW` | `statusPublishedAt == null` | neutral "under review" default (FR-006) |

## 4. Publish validation (D4) — enforced before the atomic `$set`

| Outcome | Required | Rejected (400 `invalid_status`, value-free) if |
|---|---|---|
| `IN_PROGRESS` | `statusStage` non-blank, `statusNextStep` non-blank, `statusExpectedDate` non-null | any missing/blank (the dateless/contentless ban, FR-011/FR-012) |
| `COMPLETE_OFFER` / `COMPLETE_REJECTED` | `statusOutcome` + `statusNextStep` non-blank | message blank |

## 5. Publish / rotate / view / erasure — write & read paths

- **Publish** (D3): `updateFirst({_id:id, workspaceId, erasureState:ACTIVE}, $set{statusStage, statusNextStep, statusExpectedDate, statusOutcome, statusPublishedAt=now, statusPublishedByMemberId=actor})`; `matchedCount==0` ⇒ scoped 404. Converter encrypts the free-text `$set` values (F03 precedent). Audit `STATUS_PUBLISHED/RECORDED`.
- **Provision/rotate** (D8/D9): `updateFirst({_id, workspaceId[, erasureState:ACTIVE]}, $set{statusToken=enc(raw), statusTokenHash=hash(raw)})`. Provisioning is folded into the first **publish** (no read mutates); a lazy `statusLinkFor` mint (token absent) audits `STATUS_LINK_ISSUED`; rotate always re-mints and audits `STATUS_LINK_ROTATED`.
- **View** (D6): `findByStatusTokenHash(hash)`; empty OR `erasureState!=ACTIVE` ⇒ single `StatusNotFoundException` (indistinguishable 404).
- **Erasure clear** (D7, in `CandidateErasureService.wipe`): add to the existing wipe update — `statusStage/statusNextStep` → `$set null`, `statusExpectedDate/statusOutcome/statusPublishedAt` → `$set null`, **`statusToken` → `$set null`** (NOT `$unset` — `statusToken` is converter-managed; `$unset` on a converter field feeds the unset marker to the `String` converter → `ClassCastException`, the F03 `WorkspaceConfigService.unsetCredential` lesson; `PiiCrypto.encrypt(null)==null` makes `$set null` correct & null-safe), **`statusTokenHash` → `$unset`** (plain hash, not converter-managed — `$unset` is safe and keeps it out of the partial index). Atomic within the existing wipe flip (no resurrection window). A test erases a candidate with a provisioned token and asserts the wipe succeeds AND the old token then 404s.

## 6. `erasureRequests` — idempotency (D7) — no schema change, one index

- F30 adds **no field**. Adds a unique partial index `{workspaceId:1, candidateId:1}` over `partialFilterExpression {status:"PENDING"}` (ChangeUnit015). `ErasureRequestService.requestErasure` is hardened: insert + catch `DuplicateKeyException` ⇒ return the existing open request (idempotent; FR-022 — "no second **PENDING** request"; hardens the operator path too). (A new PENDING after a prior RESOLVED is still permitted by design; the rate-limit bounds churn.)
- The record is already **id + `ErasureReasonCode` enum only** (no free text) ⇒ FR-021 holds by construction. Reuse the **existing** `ErasureReasonCode.CANDIDATE_REQUEST` (confirmed present in source) — no enum change.

## 7. `CandidateEventType` (append-only, +2)

```
STATUS_PUBLISHED        # outcome RECORDED — every publish
STATUS_LINK_ISSUED      # outcome RECORDED — first provisioning of a status token
STATUS_LINK_ROTATED     # outcome RECORDED — every rotation
```
Token provisioning is normally folded into the first **publish** (so no read mutates). If `statusLinkFor` must lazily mint (token still absent), it audits `STATUS_LINK_ISSUED` — so no credential is ever minted without an audit trail (FR-034). The recruiter `GET .../status` returns the link if present and only triggers an audited lazy-provision otherwise.
(Reuse `CandidateAuditOutcome.RECORDED`; reuse `ERASURE_REQUESTED/REQUESTED` for the candidate intake, already wired in `ErasureRequestService.requestErasure`.)

## 8. `ChangeUnit015_CandidateStatusIndexes` (order "015", off applied "014")

```
@Execution
  candidates.createIndex({statusTokenHash:1},
      unique=true, partialFilterExpression={statusTokenHash:{$exists:true}})
  erasureRequests.createIndex({workspaceId:1, candidateId:1},
      unique=true, partialFilterExpression={status:"PENDING"})
@RollbackExecution
  candidates.dropIndex({statusTokenHash:1})
  erasureRequests.dropIndex({workspaceId:1, candidateId:1})
```
Native `createIndex` + `IndexOptions`; targeted `dropIndex` (never `dropIndexes()`). Partial (NOT sparse) — the F23 `confirmTokenHash` lesson (present-as-null collision). Both indexed fields are `@Field(write=NON_NULL)` so absent values are omitted from BSON.

> **REQUIRED dedupe step in `ChangeUnit015.@Execution` (NOT optional)**: a pre-F30 workspace can already hold ≥2 `PENDING` requests for one candidate (`requestErasure` `save()`s unconditionally today — confirmed in source). A unique partial `{workspaceId,candidateId}` over `status:PENDING` would then **fail to build → Mongock aborts on startup → `deploy-all` fails mid-migration**. So the changeset MUST, **before** `createIndex`: group `erasureRequests` by `{workspaceId,candidateId}` where `status:PENDING`, keep the earliest `createdAt`, and flip the rest to a terminal non-PENDING status (or delete). A Testcontainers test seeds 2 PENDING duplicates and asserts the changeset builds the index cleanly. This is a hard task, not a caveat.

## 9. Migration / backfill

No backfill of status fields (a candidate with no published status correctly reads `UNDER_REVIEW`). Tokens are provisioned lazily on first publish / first `statusLinkFor` call, so no bulk token mint is needed. The only migration concern is the `erasureRequests` pre-existing-duplicate dedupe in §8.
