# Phase 1 Data Model: F51 Pipeline View

Scope: **one new collection** (`requisitions`), **one additive field** on `candidates`, **reuse** of `assignments` / `schedulingRequests` / `auditLog` / `authAuditLog` / `workspaceConfig`. Two new enum values sets. No PII is added at rest.

---

## New collection: `requisitions`

A workspace-scoped job opening. **No candidate PII, no secret** → un-encrypted by design (the `interviewTemplates` / `managedCalendarEvents` precedent).

| Field | Type | Notes |
|---|---|---|
| `id` | `String` (`@Id`) | Mongo ObjectId hex. |
| `workspaceId` | `String` | Tenant scope. |
| `title` | `String` | Human-readable job title. Recruiter/Admin-authored, not candidate PII. Required, non-blank, bounded length (e.g. ≤ 200). |
| `status` | `RequisitionStatus` | `OPEN` \| `CLOSED`. New requisitions default `OPEN`. |
| `externalLabel` | `String` (`@Field(write=NON_NULL)`, nullable) | Optional ATS job id/title or CSV requisition label captured to assist manual linking (FR-011). Display/reference only — not a uniqueness key. |
| `createdAt` | `Instant` | Stamp (injected `Clock`). |
| `createdByMemberId` | `String` | Actor. |

**Indexes** (ChangeUnit022): `{workspaceId, status}` (list + filter dropdown + the requisition-management surface).

**Lifecycle**: `OPEN → CLOSED` (and back, by Admin). Closing does not delete candidates' links; it removes the requisition (and its candidates) from the default active pipeline view and is reflected in HM visibility on next load.

**Validation**:
- `title` required, trimmed, non-empty, length-bounded.
- `status` transitions are unrestricted between OPEN/CLOSED (Admin only).
- Create/update/close audited via `authAuditLog` (`REQUISITION_CREATED` / `REQUISITION_UPDATED`).

---

## Modified collection: `candidates` (additive)

| Field | Type | Notes |
|---|---|---|
| `requisitionId` | `String` (`@Field(value="requisitionId", write=Field.Write.NON_NULL)`, nullable) | The candidate's single requisition link (0..1). **Not PII** → un-encrypted. Omitted from BSON when unassigned (so the HM `$in` filter never matches an unassigned candidate, FR-014; avoids the present-as-null index footgun). **Retained on erasure** (non-PII anchor; `CandidateErasureService.wipe` is unchanged). |

**Validation**:
- Set/changed only by Admin/Recruiter; the target requisition MUST exist in the workspace (`RequisitionRepository.findByWorkspaceIdAndId` → else `ScopedNotFoundException` 404, no oracle).
- Set/change writes `CandidateEventType.REQUISITION_LINKED` to the candidate `auditLog` (FR-009; actor + ids only).
- A candidate may be linked to a `CLOSED` requisition (treated as closed for default-view filtering).

**Indexes** (ChangeUnit022):
- `{workspaceId, erasureState, createdAt}` — workspace-active list read + stable pagination/sort key.
- `{workspaceId, requisitionId}` — HM-scoped read (`requisitionId ∈ assignedIds`).

---

## Reused: `assignments` (F02 RBAC) — Hiring-Manager → requisition

No schema change. F51 writes/reads assignments with `resourceType = REQUISITION`:
- **Assign HM**: `AssignmentService.create(workspaceId, adminMemberId, hmMemberId, ResourceType.REQUISITION, requisitionId)` (validates the HM is a workspace member; `DuplicateKeyException` → 409 idempotent).
- **HM scope**: `AssignmentService.assignedResourceIds(workspaceId, hmMemberId, ResourceType.REQUISITION)` → the requisition-id set used as the candidate selection predicate.
- **Unassign**: `AssignmentService.delete(workspaceId, hmMemberId, assignmentId)`.

Existing unique index `{workspaceId, resourceType, resourceId, memberId}` (ChangeUnit003) prevents duplicate assignments.

---

## Reused (read-only): `schedulingRequests` (F13/F20/F23)

New **batch** projection finder (no schema change):
`findByWorkspaceIdAndCandidateIdIn(workspaceId, Collection<candidateId>)` → projected to `{candidateId, status, sentAt, bookedStartAt, noShowAt, createdAt}`. **No new index needed** — the existing `schedulingRequests {workspaceId, candidateId, createdAt:-1}` (`ChangeUnit012`) serves this query via its `{workspaceId, candidateId}` prefix (Backend review #2; a separate 2-field index would be redundant write cost).

In-memory per-candidate resolution (mirrors `SchedulingService.status`): the **live `BOOKED`** request if any, else the **newest by `createdAt`**.

---

## Reused (read-only): `auditLog` (F04 `CandidateAuditEvent`) — the timeline source

No schema change. Read via the existing `CandidateAuditEventRepository.findByWorkspaceIdAndCandidateIdOrderByOccurredAtAscIdAsc(workspaceId, candidateId)` (the real method name — note the `IdAsc` tie-break suffix and **no `Pageable` param**; Backend review #3), backed by the existing `auditLog {candidateId, occurredAt:-1}` index (F00.1, candidateId-leading — workspaceId is a residual equality filter). If timeline pagination is required at scale, a new `Pageable` finder must be added; for the MVP the full per-candidate stream is returned. Each `CandidateEventType` maps to a human-readable label. PII-free by construction.

> **SC-007 honest scope (Backend review #4)**: the timeline shows whatever the `auditLog` contains. `STATUS_PUBLISHED`, `SCORECARD_SUBMITTED`, `SLA_DRAFT_*`, `REQUISITION_LINKED`, and erasure/retention events are emitted today; `MESSAGE_SENT` / `BOOKING_CHANGED` / `STAGE_CHANGED` must be confirmed as actually emitted by their owning features (F22/F13/F30) at implementation time — if an emission site is missing, F51 adds the `CandidateAuditService.append(...)` call at that site (it does not invent new event types). SC-007's "100% of email/scheduling/status/feedback events" is bounded by these emission sites being present.

---

## Extended enums (append-only)

**`AuthEventType`** (`authAuditLog`), append at the bottom:
- `REQUISITION_CREATED`
- `REQUISITION_UPDATED` (title/status change incl. close/reopen)
- `REQUISITION_HM_ASSIGNED`
- `REQUISITION_HM_UNASSIGNED`

**`CandidateEventType`** (`auditLog`), append at the bottom:
- `REQUISITION_LINKED` (candidate→requisition set/change; also surfaces on the timeline)

> Append-only: never reorder/remove existing enum values (persisted in audit docs).

---

## View projections (computed, NOT stored)

### `PipelineRow`
Composed per candidate for the list:

| Field | Source |
|---|---|
| `candidateId` | candidate `_id` |
| `name` | candidate `name` (decrypted; authorized roles only; never logged) |
| `stage` | best of `statusStage` → `atsStageLabel` → `importStageLabel` (decrypted) → `"Not started"` |
| `slaState` | `SlaNudgeService.classifyCandidate(cfg, candidate, now)` → GREEN/AMBER/RED (in-memory, no query) |
| `schedulingStatus` | pure mapping over the resolved scheduling request (see below) |
| `requisitionId` / `requisitionTitle` | candidate `requisitionId` + requisition `title` (batch-resolved) |
| `lastActivityAt` | candidate `lastContactAt` (tie-breaker + sort key) |

### `PipelineSchedulingStatus` mapping (FR-005, pure/unit-tested)

| Resolved scheduling request | Displayed status |
|---|---|
| none | `NO_LINK_SENT` |
| `PENDING_SELECTION` (not past `expiresAt`) | `LINK_SENT` |
| `PENDING_SELECTION` (past `expiresAt`) or `EXPIRED` | `EXPIRED` |
| `BOOKING` | `SLOT_PICKED` |
| `BOOKED`, `noShowAt == null` | `CONFIRMED` |
| `BOOKED`, `noShowAt != null` | `NO_SHOW` |
| `RESCHEDULED` (no live BOOKED) | `RESCHEDULED` |
| `CANCELLING` / `CANCELLED` | `CANCELLED` |
| `SUPERSEDED` / `CLEANUP_INCOMPLETE` (no live row) | `NO_LINK_SENT` (no actionable live link) |

### `BulkActionResult`
Per candidate: `{ candidateId, outcome }` where `outcome ∈ { ENQUEUED, SENT, SKIPPED }`. A `SKIPPED` carries **exactly one coarse reason `not_contactable`** for **every** ineligible candidate — gate-deny (consent/withdrawn/over-retention/undeliverable), erased, AND unknown-at-execution-time — all collapse to the single byte-identical value (FR-018/SC-006: byte-identical across all underlying causes). There is **no** distinct `not_found` skip outcome (a distinct value would be an erasure/existence oracle — Security review #1). The only `not_found`-class signal permitted is for an input candidateId that was never in the workspace at request time (a malformed/cross-workspace selection by the staff member about a non-existent candidate, not a privacy fact about a real one); the implementation MAY also fold this into `not_contactable` for simplicity. All `SchedulingService.initiate(...)` exceptions (incl. `UnschedulableRequiredException`, which carries member ids) are caught per-candidate and collapsed to the coarse skip — the exception payload is **discarded**, never surfaced in the result or logs (Security review #2). The specific GDPR/consent cause is recorded only in value-free internal audit/diagnostics keyed by id.

### `TimelineEvent`
Per audit entry: `{ occurredAt, type, label }` (+ optional non-PII detail such as `schedulingStatus` or `feedbackPending`). No free text, no name/email/phone.
