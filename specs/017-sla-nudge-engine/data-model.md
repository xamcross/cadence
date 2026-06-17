# Phase 1 Data Model: SLA Nudge Engine (F31)

## 1. New collection: `slaNudgeDrafts`

`SlaNudgeDraft` (`@Document("slaNudgeDrafts")`) — a recruiter-actionable holding-message draft for a breaching candidate. **No candidate PII at rest** (ids/enums/instants only — the `emailDispatches` precedent).

| Field | Type | Notes |
|---|---|---|
| `id` | `String` (`@Id`) | ObjectId hex. |
| `workspaceId` | `String` | Tenant scope. |
| `candidateId` | `String` | Internal id (non-PII). |
| `status` | `SlaDraftStatus` | `OPEN` → `APPROVED` / `DISMISSED` / `INVALIDATED`. Always non-null. |
| `messageType` | `EmailMessageType` | Always `SLA_HOLDING` (MVP). |
| `detectedAt` | `Instant` | When the breach scan created the draft (injected `Clock`). |
| `actionedAt` | `Instant` | Set on approve/dismiss/invalidate; null while OPEN. |
| `actorMemberId` | `String` | Recruiter/Admin who approved/dismissed; null for OPEN or system INVALIDATED. |

`toString()` is id/status/ids only (no PII to leak — there is none, but keep the discipline).

**Validation / invariants**:
- At most **one** `OPEN` draft per `{workspaceId, candidateId}` — enforced by the unique partial index (§4), not application read-then-write.
- Status transitions are one-way out of `OPEN`: `OPEN → {APPROVED, DISMISSED, INVALIDATED}` via `findAndModify` CAS `{_id, status:OPEN}`. No transition back into `OPEN` (a new breach inserts a **new** row).

## 2. New enums

- **`SlaDraftStatus`**: `OPEN`, `APPROVED`, `DISMISSED`, `INVALIDATED` (erasure). Append-only.
- **`SlaState`** (server-computed, **not persisted**): `GREEN`, `AMBER`, `RED`.
- **`RecruiterNotificationType`** (MODIFIED, append-only): `+ SLA_DRAFT_PENDING`.
- **`CandidateEventType`** (MODIFIED, append-only): `+ SLA_DRAFT_APPROVED`, `SLA_DRAFT_DISMISSED`. (Draft *creation* by the system is not candidate-audited — value-free scheduler log only; the spec audits recruiter actions FR-017.)

## 3. `Candidate` — no schema change; `lastContactAt` becomes live

No new field. The existing `lastContactAt` (`Instant`, F04; backed by the `{workspaceId,lastContactAt}` index created by **`ChangeUnit001`**) is **advanced** at the qualifying write sites. Reads: `erasureState` (ACTIVE filter), `statusOutcome` (terminal guardrail), `statusExpectedDate` (merge value).

### `lastContactAt` write-site matrix (SC-014)

| # | Activity | Site (file:method) | Mechanism |
|---|---|---|---|
| 1 | Candidate email **sent** | `EmailDispatchService.dispatch` (SENDING→SENT, candidate messages only) | **separate** `advanceLastContact(claimed.getWorkspaceId(), claimed.getCandidateId(), sentAt)` AFTER the SENT CAS — NOT a fold: that `findAndModify` is on `EmailDispatch.class` (cannot set a `candidates` field) and is not ACTIVE-guarded |
| 2 | Status published | `CandidateStatusService.publish` | fold `.set("lastContactAt", now)` into the existing atomic status `$set` |
| 3 | Interview booked | `SlotReservationService.book` (BOOKING→BOOKED) | `advanceLastContact(ws, candidateId, now)` after the CAS |
| 4 | Interview rescheduled | `SlotReservationService.forwardCommitParent` | `advanceLastContact(...)` on the new booked round |
| 5 | SLA draft approved | `SlaNudgeService.approve` | `advanceLastContact(...)` synchronously (closes the re-draft window) |

`CandidateActivityService.advanceLastContact(ws, candidateId, now)` = one value-free `mongo.updateFirst({_id, workspaceId, erasureState:ACTIVE}, $set lastContactAt=now)`. Idempotent; guarded on ACTIVE so an erased candidate's instant is never moved.

## 4. Mongock `ChangeUnit016_SlaNudgeIndexes` (order "016")

Off the highest **applied** order "015". Native `createIndex`; targeted `dropIndex` rollback (never `dropIndexes()`); pure-ASCII comments.

```text
slaNudgeDrafts:
  unique partial { workspaceId: 1, candidateId: 1 }
    partialFilterExpression { status: "OPEN" }     # at most one open draft per candidate
  (non-unique) { workspaceId: 1, status: 1 }       # backs the workspace draft-queue / silence-list read
```

No `candidates` index (the breach range-scan reuses the existing `{workspaceId, lastContactAt}` from F00.1/`ChangeUnit005`). No dedupe-before-index step (new collection). No `@Field(write=NON_NULL)` footgun: the partial filter keys on `status == "OPEN"` (a present non-null value), not an `$exists` over a nullable field.

## 5. Classification rules (server-side, injected `Clock`, workspace zone) — D5

Given `windowDays = effective slaSilenceWindowDays` (workspace value, else global default), `amberMarginDays = SlaProperties.amberMarginDays` (default 1), `now = Instant.now(clock)`:

```
breachCutoff  = now - Duration.ofDays(windowDays)
amberCutoff   = now - Duration.ofDays(max(0, windowDays - amberMarginDays))
RED    if lastContactAt < breachCutoff
AMBER  if lastContactAt < amberCutoff   (and not RED)
GREEN  otherwise
```

Overrides (both → not surfaced as silence): `erasureState != ACTIVE` ⇒ never RED/AMBER; `statusOutcome ∈ {COMPLETE_OFFER, COMPLETE_REJECTED}` ⇒ never RED/AMBER (FR-008/FR-020). `lastContactAt == null` (legacy) treated as the candidate's `createdAt`; if both null, GREEN (fail-safe — never spuriously breach).

**Two distinct range reads** (Backend review): the **scan/drafting** read filters `lastContactAt < breachCutoff` (only RED candidates are drafted, FR-011). The **silence-list** read (contract §A, surfaces AMBER+RED) filters the WIDER `lastContactAt < amberCutoff` and classifies each row RED/AMBER in Java — AMBER rows are NOT past the breach cutoff, so the drafting query would miss them.

## 6. Scan → draft state machine (per workspace, per candidate)

```
[scan] read candidates: workspaceId, erasureState=ACTIVE, lastContactAt < breachCutoff (index-backed, Pageable cap)
  for each:
    if !gate.permit(ws, candidateId)          -> skip            (FR-019)
    if statusOutcome terminal                 -> skip            (FR-020)
    try insert SlaNudgeDraft{status:OPEN}      -> notify SLA_DRAFT_PENDING   (FR-010/FR-011/FR-012)
    catch DuplicateKeyException                -> no-op (open draft already exists)  (FR-014/FR-015)

[approve]  CAS {_id,status:OPEN}->APPROVED ; advanceLastContact ; enqueue(SLA_HOLDING) ; audit SLA_DRAFT_APPROVED
[dismiss]  CAS {_id,status:OPEN}->DISMISSED ; audit SLA_DRAFT_DISMISSED ; (no send)
[erasure]  CAS {_id,status:OPEN}->INVALIDATED (best-effort, inside wipe)   (FR-021)
[send]     EmailDispatchService.dispatch re-gates ContactPermissionGate; REFUSED if ineligible  (FR-023, authoritative)
```

Concurrency: approve/dismiss/invalidate all CAS on `{status:OPEN}`; the first wins, others are `matchedCount==0` no-ops. Even a double-approve yields one dispatch (the F22 `{workspaceId,candidateId,SLA_HOLDING,scheduledForMillis}` idempotency key). (SC-003/SC-010.)

## 7. Workspace SLA policy (reuse — no schema change)

`WorkspaceConfig.slaSilenceWindowDays` (F03, int, validated 1–30 by `WorkspaceConfigService.validateSla`) is read by the scan/classifier. Set via the **existing** `PATCH` settings endpoint (`SettingsPatch.slaSilenceWindowDays` → `WorkspaceConfigResponse`), already Admin-gated + audited. F31 adds **no** new config field (FR-001/002/003/004 already satisfied; F31 only consumes). The global default applies when the field is unset (FR-002). Workspace zone via `WorkspaceConfig.getTimeZone()`.

## 8. `SlaProperties` (new global config)

`cadence.sla.*`: `amberMarginDays` (default 1), `scanInterval` (e.g. `PT5M` fixedDelay), `scanBatchLimit` (Pageable cap per workspace). Bounds: `0 <= amberMarginDays < SLA_MIN`-respecting (amber margin can't exceed the smallest window meaningfully; clamp `windowDays - amberMarginDays >= 0`).

## 9. Erasure folding (FR-021) — with the cycle-break

`CandidateErasureService.wipe`: after the winning guarded `updateFirst` (which sets `erasureState=ERASED` and clears PII/status/token), call `slaDraftInvalidator.invalidateOpenDraft(ws, candidateId)` (best-effort, CAS `OPEN→INVALIDATED`) alongside `supersedeLiveScheduling`. No new field on `candidates`; the draft lives in its own collection. The authoritative no-message guarantee remains the send-time gate (D4/D8).

**Cycle-break**: `CandidateErasureService` depends on the narrow **`SlaDraftInvalidator`** interface, NOT the concrete `SlaNudgeService` — otherwise the constructor graph closes `CandidateErasureService → SlaNudgeService → CandidateStatusService → ErasureRequestService → CandidateErasureService` and Spring fails startup with `BeanCurrentlyInCreationException` (breaks every `@SpringBootTest`). `SlaNudgeService implements SlaDraftInvalidator`; it injects `CandidateStatusService` via `@Lazy`/`ObjectProvider` as a second, independent break.
