# Phase 1 Data Model: Flow A1 — Single-Stage Scheduling (F13)

Two new collections, one modified collection, one modified enum, and the new Mongock changeset. All new persisted fields are ids / instants / enums (no candidate PII) except the single encrypted `locationText` on `SchedulingRequest` (D2).

---

## 1. `schedulingRequests` (NEW)

One document per recruiter-initiated scheduling attempt. The booking aggregate. **No `@Version`** — every status transition is a `findAndModify` CAS (the F22 `EmailDispatch` precedent).

| Field | Type | Notes |
|---|---|---|
| `id` | String (ObjectId) | Also the calendar `bookingRef` (D9). |
| `workspaceId` | String | Scope. |
| `candidateId` | String | Internal id only. |
| `templateId` | String | F12 interview template. |
| `status` | `SchedulingStatus` | State machine §4. |
| `tokenHash` | String | `HMAC-SHA-256(rawToken, TOKEN_PEPPER)`; unique index. Raw token never stored. |
| `sentAt` | Instant | When the invitation was dispatched. |
| `expiresAt` | Instant | `sentAt + workspace TTL` (default 72h). Expiry computed at read; **no TTL index** (D5). |
| `searchRangeStart` / `searchRangeEnd` | LocalDate | Window used for compute. |
| `offeredSlots` | `List<OfferedSlot>` | Embedded snapshot (§2). |
| `locationText` | String (ENCRYPTED) | Recruiter-provided location/dial-in; encrypted at rest via `PiiStringConverter` (D2); `@JsonIgnore`, omitted from all candidate/audit/log output; `@Field(write=NON_NULL)`. |
| `chosenSlotId` | String | Set on the request-status CAS at confirm. |
| `bookedAt` | Instant | Set on `BOOKED`. |
| `supersededByRequestId` | String | Set when a re-send supersedes this request (FR-022). |
| `lastOutcomeReason` | `SchedulingOutcomeReason` | Value-free enum (e.g. `NO_SLOTS`, `SLOT_TAKEN`, `STALE_SLOT`, `CLEANUP_INCOMPLETE`, `NOT_CONTACTABLE`). |
| `createdAt` / `updatedAt` | Instant | |

### 2. `OfferedSlot` (embedded record)

| Field | Type | Notes |
|---|---|---|
| `slotId` | String | Stable id within the request (e.g. UUID or index-derived). |
| `start` / `end` | Instant | Absolute. |
| `zoneId` | String | IANA zone for display. |
| `requiredMemberIds` | `List<String>` | From `ComputedSlot`. |
| `qualifyingByPoolIndex` | `Map<Integer,List<String>>` | Pool candidates per pool (re-selected at confirm). |

> **Candidate-facing projection** exposes only `slotId`, `start`, `end`, `zoneId` — never `requiredMemberIds`/`qualifyingByPoolIndex` (FR-011, no participant-identity leak).

---

## 3. `interviewSlotClaims` (NEW) — the atomic reservation guard

One document per claimed (participant, start) for a booking-in-progress/booked request. The cross-request double-booking guard (D3).

| Field | Type | Notes |
|---|---|---|
| `id` | String (ObjectId) | |
| `workspaceId` | String | Scope. |
| `memberId` | String | Claimed interviewer. |
| `startAt` | Instant | Slot start (exact-grid key). |
| `schedulingRequestId` | String | Owning request (for release set). |
| `status` | `ClaimStatus` | `ACTIVE` / `RELEASED`. |
| `createdAt` | Instant | |

**Unique partial index** `{workspaceId, memberId, startAt}` where `status = ACTIVE` → a second `ACTIVE` claim for the same interviewer-time fails with `DuplicateKeyException` (= slot taken). Release = CAS `status: ACTIVE → RELEASED` (not delete — keeps the partial index honoring uniqueness only over live claims while preserving an audit trail; a released claim no longer collides).

---

## 4. State machine — `SchedulingStatus`

```
                    initiate (gate ok + slots>0)
        (none) ─────────────────────────────────► PENDING_SELECTION
                                                        │
                 confirm: request-status CAS            │  re-send (FR-022)
                 {PENDING_SELECTION, expiresAt>now}     ▼
                          ┌───────────────────────► SUPERSEDED  (also via erasure, D10)
                          │
                          ▼
                       BOOKING ──── re-validate fail / claim DuplicateKey / panel ROLLED_BACK ──► PENDING_SELECTION (release claims, 409/stale)
                          │
              panel CREATED │              panel CLEANUP_INCOMPLETE
                          ▼                          ▼
                        BOOKED                 CLEANUP_INCOMPLETE  (recruiter alert + audit)
                          ▲
   reopen booked link ────┘ (idempotent: returns existing confirmation, FR-019)

        reaper: PENDING_SELECTION & expiresAt<now ──► EXPIRED  (410 on access)
        reaper: BOOKING older than threshold ──► release claims, back to PENDING_SELECTION (D6)
```

- `PENDING_SELECTION`: link live, awaiting candidate pick.
- `BOOKING`: a confirm won the request-status CAS and is mid-reserve/book (transient; reaper-recoverable).
- `BOOKED`: terminal success; link single-use consumed (FR-009).
- `EXPIRED`: past TTL with no booking → 410.
- `SUPERSEDED`: replaced by a re-send, or candidate erased → behaves as invalid (400).
- `CLEANUP_INCOMPLETE`: terminal; a known/surfaced calendar orphan (FR-016 honest bound).

---

## 5. Modified: `candidates` (F04)

No new field. `CandidateErasureService.wipe(...)` is **extended** (behavioural, not schema) to: set live `schedulingRequests` for the candidate to `SUPERSEDED` and CAS their `interviewSlotClaims` to `RELEASED` (D10/FR-014). The confirm path also re-reads the candidate via `CandidateRepository.findByWorkspaceIdAndId` for the `ContactPermissionGate`.

---

## 6. Modified: `AuthEventType` (append-only)

Add (never reorder existing): `SCHEDULING_LINK_SENT`, `SCHEDULING_BOOKED`, `SCHEDULING_ROLLED_BACK`, `SCHEDULING_CLEANUP_INCOMPLETE`, `SCHEDULING_LINK_EXPIRED`, `SCHEDULING_REFUSED`. Written via `AuthAuditService.record(type, workspaceId, actorMemberId-or-"candidate", outcomeLiteral, sourceIp)` — outcome is value-free (ids/enums); no token value, no PII (FR-021/FR-024). For candidate-initiated confirm, `memberId` is a constant literal (e.g. `"CANDIDATE"`), never the candidate id-as-actor in a way that leaks identity beyond the workspace-scoped candidate ref already carried in the outcome.

---

## 7. Mongock `ChangeUnit012_SchedulingIndexes` (order "012")

Native driver `createIndex`; targeted `dropIndex` rollback (never `dropIndexes()`).

**`schedulingRequests`**
- unique `{tokenHash: 1}` — token lookup + single live token per hash (satisfies the F00.1-reserved `schedulingTokens` intent, D12).
- `{workspaceId: 1, candidateId: 1, createdAt: -1}` — per-candidate status read (FR-020).
- `{status: 1, expiresAt: 1}` — reaper expiry scan.
- `{status: 1, updatedAt: 1}` — reaper stuck-`BOOKING` scan.

**`interviewSlotClaims`**
- unique **partial** `{workspaceId: 1, memberId: 1, startAt: 1}` where `{status: "ACTIVE"}` — the reservation guard (D3).
- `{workspaceId: 1, schedulingRequestId: 1}` — release set lookup.

> Reuses, no new index: `managedCalendarEvents {workspaceId, memberId, startAt}` (ChangeUnit008) for confirm-time re-validation reads; `candidates`, `emailDispatches`, `schedulerCheckpoints`, `interviewTemplates`, `workspaceConfig` indexes unchanged.

---

## 8. Validation rules (from spec FRs)

- Initiate: caller is ADMIN/RECRUITER (FR-001/FR-023); candidate + template are workspace-scoped (scoped-not-found → 404, oracle-free); `ContactPermissionGate` permits (FR-004); `RuleEngine` returns ≥1 slot else refuse `NO_SLOTS` (FR-003); any **required** member unschedulable → refuse and name them, **optional** unschedulable → proceed + flag (FR-005).
- Confirm: token resolves to a live `PENDING_SELECTION` request not past `expiresAt` (else 410/400 per D5); chosen `slotId` exists in the snapshot; contactability re-checked (FR-014); re-validation + pool re-selection pass (FR-013) else refuse-this-slot + return remaining; claim CAS succeeds else 409 `slot_taken` (FR-012); rate limit not exceeded (FR-010, 429).
- All outcomes audited exactly once (FR-021/SC-011); no PII/token in logs (FR-024/SC-006).
