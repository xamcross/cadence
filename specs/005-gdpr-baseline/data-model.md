# Phase 1 Data Model — GDPR Baseline (F04)

**Branch**: `005-gdpr-baseline` | **Date**: 2026-06-15 | **Spec**: [spec.md](./spec.md) | **Research**: [research.md](./research.md)

Collections added: **`candidates`**, **`auditLog`** (candidate-keyed), **`erasureRequests`**. Reuses F01 `members`/`sessions` (actor context), `WorkspaceConfig` (retention period, F03), `schedulerCheckpoints` (F00.2), and `authAuditLog` is **unchanged** (member-keyed; the candidate audit log is separate, D3).

---

## 1. `Candidate` — `@Document("candidates")`

The data-subject record. F04 owns the GDPR-critical subset; later features extend the same document with non-GDPR fields (stage, requisition, etc.).

| Field | Type | Notes |
|---|---|---|
| `id` | `String` (`@Id`) | Internal non-PII id (Mongo ObjectId hex). The only candidate identifier in logs/audit. |
| `workspaceId` | `String` | Tenant key (single workspace in MVP). |
| `name` | `String` | **PII — encrypted at rest** via `PiiStringConverter` (`MongoPiiConfig`). After erasure: `"[ERASED]"`. |
| `email` | `String` | **PII — encrypted at rest**. After erasure: `"[ERASED]"`. Never queried directly. |
| `phone` | `String` | **PII — encrypted at rest**. After erasure: `"[ERASED]"`. |
| `emailHash` | `String` | `HMAC-SHA-256(lowercased email, PII_PEPPER)` via `PiiCrypto.emailHash`. Lookup/equality key. `@Field(write = NON_NULL)`. After erasure: **null** (omitted from BSON) → non-findable by former email. |
| `lawfulBasis` | `LawfulBasis` (enum, nullable) | Email-contact lawful basis. Null until recorded (fail-closed default, D5). |
| `basisRecordedAt` | `Instant` (nullable) | When the basis was established. |
| `basisActorMemberId` | `String` (nullable) | Who recorded it — **member internal id only** (non-PII; retainable as Art. 7(1) evidence through erasure). |
| `basisWithdrawn` | `boolean` | Opt-out flag (Art. 7(3)). Default `false`. |
| `basisWithdrawnAt` | `Instant` (nullable) | When withdrawn. |
| `erasureState` | `ErasureState` (enum) | `ACTIVE` \| `ERASED`. Default `ACTIVE`. |
| `erasedAt` | `Instant` (nullable) | Set on wipe. |
| `retentionFlagged` | `boolean` | Set by the retention scan when over-age. Default `false`. Cleared when no longer over-age (D8). |
| `retentionFlaggedAt` | `Instant` (nullable) | When flagged. |
| `lastContactAt` | `Instant` | **Retention age basis** (D8) and the F00.1 `{workspaceId, lastContactAt}` index field. Set to `now` on create; later features refresh on each candidate contact. |
| `createdAt` | `Instant` | Record creation. |

**`toString()`**: hand-written, includes `id`/`workspaceId`/`erasureState` only — **never** name/email/phone (D10, F03 precedent). **Binding note for future extenders**: F13/F51 extend this document; any field added to `Candidate` MUST stay out of `toString()` unless non-PII (a regenerated/Lombok `toString` would leak via the decrypting converter). A unit test asserts `toString()` contains none of the seeded PII sentinels; the CI sentinel scan (D10) is the backstop.

**Erasure marker note**: on wipe, name/email/phone are `$set` to `"[ERASED]"`; because these are converter-managed, the value is **stored encrypted** (ciphertext of `[ERASED]`) and **decrypts back to `[ERASED]`** on read — consistent with encryption-at-rest. The raw-driver test asserts ciphertext (not the literal) + `emailHash` key **absent** (see research D2).

**Serialization**: name/email/phone appear only on DTOs whose role is authorized to see candidate PII (F04 exposes none such on a candidate-list; the GDPR surfaces operate on internal id + non-PII state). The decrypting converter means a careless DTO would leak plaintext — so GDPR responses carry codes/booleans, not the PII fields.

**Enums**:
- `LawfulBasis` = `{ CONSENT, LEGITIMATE_INTEREST, CONTRACT }` (closed; the recorded GDPR basis for email contact).
- `ErasureState` = `{ ACTIVE, ERASED }`.

---

## 2. `CandidateAuditEvent` — `@Document("auditLog")`

Append-only, candidate-keyed, **non-PII** accountability log (FR-14/FR-18). Distinct from `authAuditLog`.

| Field | Type | Notes |
|---|---|---|
| `id` | `String` (`@Id`, ObjectId hex) | The **order tiebreaker** (D3/FR-016): ObjectIds are generated with a per-process monotonic counter, so within the single instance, insertion order == `_id` order. No separate `seq` field needed (removes the unspecified-counter hazard — QA-MAJOR). |
| `workspaceId` | `String` | |
| `candidateId` | `String` | Subject (internal id). Survives erasure. |
| `eventType` | `CandidateEventType` (enum) | Closed enum — see below. |
| `outcome` | `CandidateAuditOutcome` (enum) | **Closed enum** (`CREATED`, `RECORDED`, `WITHDRAWN`, `REQUESTED`, `CONFIRMED`, `REJECTED`, `FLAGGED`, `CLEARED`, `DELETED`, and the wipe reasons `OPERATOR`/`CANDIDATE_REQUEST`/`RETENTION`). **Enum-typed, not `String`** — so "non-PII by construction" is structural: no caller can inject candidate-derived text (SEC-MAJOR). |
| `actorMemberId` | `String` (nullable) | Member internal id, or null = system (scan). |
| `occurredAt` | `Instant` | Pinned name to match the pre-built ChangeUnit001 index `{candidateId, occurredAt:-1}`. |

**Read order**: `(occurredAt ASC, _id ASC)` — deterministic even when two events in one flow share a clock tick (the injected `Clock` can return identical instants); a test asserts same-`occurredAt` events return in distinct, stable `_id` order.

**`CandidateEventType`** (closed): `RECORD_CREATED`, `BASIS_RECORDED`, `BASIS_WITHDRAWN`, `ERASURE_REQUESTED`, `ERASURE_REQUEST_CONFIRMED`, `ERASURE_REQUEST_REJECTED`, `ERASURE_COMPLETED`, `RETENTION_FLAGGED`, `RETENTION_FLAG_CLEARED`, `RETENTION_DELETED`. *(Forward-contract types appended by later features: `MESSAGE_SENT` (F22), `BOOKING_CHANGED` (F13), `STAGE_CHANGED` (F51) — declared but not emitted by F04.)*

**The single append primitive takes enum params only** (`append(candidateId, eventType, outcome, actorMemberId)`) — never a free `String` — so no future caller can inject PII into the audit log.

**No update/delete path** (FR-015, concrete — BE/QA-MAJOR): `CandidateAuditEventRepository` extends a **narrow `org.springframework.data.repository.Repository<CandidateAuditEvent, String>`** (NOT `CrudRepository`/`MongoRepository`, which expose `delete*`), declaring only `insert`/finder methods. A self-test (a) reflectively asserts the repository declares no `delete*`/`update*`/`remove*` method, and (b) asserts no controller maps `DELETE`/`PUT`/`PATCH` to an audit path (via `RequestMappingHandlerMapping`). There is no service-layer `mongoTemplate.remove` on `auditLog`.

---

## 3. `ErasureRequest` — `@Document("erasureRequests")`

Data-subject (candidate-initiated) erasure request, routed to an Administrator (US4).

| Field | Type | Notes |
|---|---|---|
| `id` | `String` (`@Id`) | |
| `workspaceId` | `String` | |
| `candidateId` | `String` | Subject (internal id). |
| `status` | `RequestStatus` (enum) | `PENDING` \| `RESOLVED_CONFIRMED` \| `RESOLVED_REJECTED`. |
| `reasonCode` | `ErasureReasonCode` (enum, nullable) | **Enum-typed, server-validated — never free text** (S3/SEC-MINOR). Used for BOTH the candidate intake reason and the Admin reject reason; any non-enum value → 400 `invalid_reason`, no state change. |
| `createdAt` | `Instant` | |
| `decidedByMemberId` | `String` (nullable) | Admin who decided. |
| `decidedAt` | `Instant` (nullable) | |

**Transitions** (guarded `findAndModify(status: PENDING → …)`, D7): `PENDING → RESOLVED_CONFIRMED` (runs the shared wipe) or `PENDING → RESOLVED_REJECTED`. Confirming/rejecting a non-`PENDING` request is a benign no-op/409; concurrent confirms resolve to one wipe.

---

## 4. Indexes — `ChangeUnit005_GdprIndexes` (`order = "005"`)

New indexes only (the others pre-exist from ChangeUnit001):

| Collection | Index | Unique | Purpose |
|---|---|---|---|
| `candidates` | `{ workspaceId: 1, emailHash: 1 }` | **No** | Email lookup (non-unique — D1/B3, avoids erasure null-collision). |
| `erasureRequests` | `{ workspaceId: 1, status: 1 }` | No | Pending-queue read (Admin). |

**Pre-existing (do NOT recreate)**: `auditLog { candidateId: 1, occurredAt: -1 }` (ChangeUnit001) backs the audit read; `candidates { workspaceId: 1, lastContactAt: 1 }` (ChangeUnit001) backs the retention scan with an `erasureState`/`retentionFlagged` residual filter.

Native driver API for create; targeted `dropIndex(...)` in `@RollbackExecution` (never `dropIndexes()`). `id`/`order` immutable once applied (CLAUDE.md F00.1).

---

## 5. Validation rules

| Input | Rule | On violation |
|---|---|---|
| `lawfulBasis` (record) | Must be a known `LawfulBasis` enum value | 400 `invalid_basis`, no write |
| `email` (create, internal) | Non-blank; normalized lowercased for `emailHash` | 400 (create surfaces own this; F04 contract) |
| erasure `reasonCode` (intake **and** Admin reject) | Must parse to an `ErasureReasonCode` enum value (server-side, not a trusting `String`); absent/empty/unknown rejected | 400 `invalid_reason`, request stays `PENDING`, no state change |
| retention period (read) | Sourced from `WorkspaceConfig` (F03, already 30–3650 validated) | n/a (consumed, not re-validated) |
| candidate id — **erasure** path | Unknown / already-erased / fresh all return **identical `200 {"status":"erased"}`** (NOT 404 — erasure does not use `ScopedNotFoundException`); no existence oracle (FR-009) | same response entity (status+body) across all three |
| candidate id — audit/retention reads | Non-oracle: empty list / same-shape response for unknown id | empty/defined, never a revealing 404 |

---

## 6. Contact-permission gate truth table (FR-004 / SC-001)

`evaluate(candidate)` — **positive evaluation: `permit` ONLY on the explicitly-good row; everything else denies** (a corrupt/unknown enum value can never fall through to permit — SEC-MAJOR). Precedence **erased > over_retention > withdrawn > no_basis**:

| erasureState | retentionFlagged | basisWithdrawn | lawfulBasis | Result |
|---|---|---|---|---|
| `== ACTIVE` | `== false` | `== false` | non-null | **`permit`** (the only permit row) |
| ERASED | * | * | * | `deny: erased` |
| ACTIVE | true | * | * | `deny: over_retention` |
| ACTIVE | false | true | * | `deny: withdrawn` |
| ACTIVE | false | false | null | `deny: no_basis` |
| any read error / missing candidate / null / unrecognized state | | | | `deny: unavailable` (fail-closed) |

---

## 7. Lifecycle / state transitions

**Candidate erasureState**: `ACTIVE --wipe--> ERASED` (one-way, idempotent; guarded on `ACTIVE`). No `ERASED → ACTIVE`; re-application creates a fresh `ACTIVE` record (D2).

**Lawful basis**: `(none) --record--> recorded --withdraw--> withdrawn --record--> recorded` (re-record after withdrawal supported, SC-013). Erasure retains the basis values (evidence) but the candidate is `ERASED` so the gate denies regardless.

**Retention flag**: `unflagged --scan(over-age)--> flagged --scan(no longer over-age)--> unflagged`; `flagged --Admin-confirm--> wipe (ERASED)`. Flag never deletes alone.

**Erasure request**: `PENDING --confirm--> RESOLVED_CONFIRMED (+wipe)` | `PENDING --reject--> RESOLVED_REJECTED`.

---

## 8. Audit event mapping (every material change → one entry)

| Action | `eventType` | `outcome` | old/new |
|---|---|---|---|
| Create (contract) | `RECORD_CREATED` | `created` | — |
| Record basis | `BASIS_RECORDED` | `recorded` | — |
| Withdraw basis | `BASIS_WITHDRAWN` | `withdrawn` | — |
| Request erasure | `ERASURE_REQUESTED` | `requested` | — |
| Confirm request | `ERASURE_REQUEST_CONFIRMED` | `confirmed` | — |
| Reject request | `ERASURE_REQUEST_REJECTED` | `rejected` | — |
| Wipe complete (CAS winner only) | `ERASURE_COMPLETED` | reason: `operator`/`candidate_request`/`retention` | — |
| Retention flag | `RETENTION_FLAGGED` | `flagged` | — |
| Retention clear | `RETENTION_FLAG_CLEARED` | `cleared` | — |
| Retention delete confirm | `RETENTION_DELETED` | `deleted` | — |

All entries carry `candidateId` + `actorMemberId` (or null=system) only; **no name/email/phone, no message content** (FR-017, SC-012).
