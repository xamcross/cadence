# API Contract: F51 Pipeline View

All endpoints are internal (authenticated staff), under `/api/internal/**`, subject to the session-cookie auth chain + method security. All error envelopes are produced by the dedicated `PipelineExceptionHandler` (`@Order(HIGHEST_PRECEDENCE)`): `{"error": "<code>"}` with **no message** for `not_found` (no existence oracle). Every handler is covered by `RbacEndpointInventoryTest`.

Role legend: ADMIN (A), RECRUITER (R), READ_ONLY (RO), HIRING_MANAGER (HM), INTERVIEWER (I).

---

## 1. List the pipeline

```
GET /api/internal/pipeline
    ?status=ACTIVE|INCLUDE_CLOSED            (default ACTIVE)
    &requisitionId=<id>                       (optional filter)
    &sla=GREEN|AMBER|RED                       (optional filter)
    &scheduling=<PipelineSchedulingStatus>    (optional filter)
    &stage=<substring>                         (optional filter)
    &sort=STAGE|SLA|SCHEDULING|RECENT          (default RECENT)
    &page=<int, default 0>&size=<int, default page-size>
```

**Auth**: A, R, RO, HM → 200; I → 403.
**Scope**: A/R/RO → workspace-active; HM → only candidates whose `requisitionId ∈ assignedResourceIds(REQUISITION)`; HM with no assignments → empty page (not error, not unfiltered).

**200 response**:
```json
{
  "rows": [
    {
      "candidateId": "string",
      "name": "string",
      "stage": "string",
      "slaState": "GREEN|AMBER|RED",
      "schedulingStatus": "NO_LINK_SENT|LINK_SENT|SLOT_PICKED|CONFIRMED|NO_SHOW|RESCHEDULED|CANCELLED|EXPIRED",
      "requisitionId": "string|null",
      "requisitionTitle": "string|null",
      "lastActivityAt": "2026-06-18T10:00:00Z"
    }
  ],
  "page": 0,
  "size": 50,
  "totalInScope": 137,
  "filteredCount": 90,
  "truncated": false
}
```

**Errors**: `403` (Interviewer); `400 {"error":"invalid_request"}` (bad enum/param); out-of-scope `requisitionId` filter for an HM → treated as not-in-scope (empty rows), never a `404` that discloses existence.

**Notes**: rows are composed in memory (SLA via `SlaNudgeService`, scheduling via the batch read); `truncated:true` when the in-scope active count exceeds `cadence.pipeline.scan-cap`. `Cache-Control: no-store`.

---

## 2. Bulk action

```
POST /api/internal/pipeline/bulk
Content-Type: application/json
{
  "action": "SEND_SCHEDULING_LINK | SEND_UPDATE_EMAIL",
  "candidateIds": ["id1", "id2", ...],
  "templateId": "string (required for SEND_SCHEDULING_LINK)",
  "locationText": "string (optional, SEND_SCHEDULING_LINK)",
  "rangeStart": "2026-07-01", "rangeEnd": "2026-07-08"  (SEND_SCHEDULING_LINK),
  "messageType": "HOLD_UPDATE"  (SEND_UPDATE_EMAIL; from the permitted set)
}
```

**Auth**: A, R → 200; RO, HM, I → 403.

**200 response**:
```json
{
  "results": [
    { "candidateId": "id1", "outcome": "ENQUEUED" },
    { "candidateId": "id2", "outcome": "SENT" },
    { "candidateId": "id3", "outcome": "SKIPPED", "reason": "not_contactable" },
    { "candidateId": "id4", "outcome": "SKIPPED", "reason": "not_contactable" }
  ]
}
```

**Behaviour**:
- Each candidate processed independently (FR-017); one failure never aborts the batch.
- **Single coarse skip reason (no oracle)**: EVERY ineligible candidate — gate-deny (consent/withdrawn/over-retention/undeliverable), erased, OR not-actionable for any reason — reports `SKIPPED / not_contactable`, **byte-identical** regardless of cause (FR-018/SC-006). There is **no** distinct `not_found` skip outcome for a real candidate (that would be an erasure/existence oracle — Security review #1). The specific cause is never returned; it is recorded only in value-free internal audit keyed by id.
- **Synchronous** `ContactPermissionGate` pre-check provides the immediate skip; the existing **asynchronous** send-time gate is the authoritative backstop (see below). (Note: `ContactPermissionGate.evaluate(workspaceId, candidateId)` reads the candidate itself — the bulk pre-check is one bounded read per selected candidate, acceptable within `bulk-max`; an optional `evaluate(Candidate)` overload can reuse the already-loaded row — Backend review #1.)
- Eligible candidate ⇒ fan-out the existing seam (`EmailDispatchService.enqueue` / `SchedulingService.initiate`), each **individually merge-rendered** (FR-015). Send is async via the existing outbox; the result reports enqueue/skip, not delivery.
- **All `SchedulingService.initiate(...)` exceptions are caught per-candidate and collapsed to the coarse skip** — including `UnschedulableRequiredException`, whose message carries member ids; that payload is discarded and never reaches the result body or logs (Security review #2).
- **Idempotent / safe to repeat** (FR-019): the update verb inherits the `{workspaceId, idempotencyKey}` outbox uniqueness (no duplicate send under concurrent/retried submit); the scheduling-link verb supersedes the prior live request (one live link, no double-booking).
- **TOCTOU backstop**: the asynchronous send-time gate is authoritative — a candidate erased between selection and dispatch is fail-closed (0 messages, SC-006) even if the synchronous pre-check was stale.

**Errors**:
- `400 {"error":"selection_too_large"}` if `candidateIds.size > cadence.pipeline.bulk-max` (checked before any candidate is touched — FR-020).
- `400 {"error":"invalid_request"}` (missing templateId for scheduling-link / bad message type / empty selection).
- `403` (RO/HM/I).

---

## 3. Candidate timeline

```
GET /api/internal/pipeline/candidates/{candidateId}/timeline?page=0&size=<n>
```

**Auth**: A, R, RO → any in-workspace candidate; HM → only candidates on assigned requisitions; I → 403.

**200 response**:
```json
{
  "candidateId": "string",
  "events": [
    { "occurredAt": "2026-06-01T09:00:00Z", "type": "MESSAGE_SENT",      "label": "Invitation email sent" },
    { "occurredAt": "2026-06-02T10:30:00Z", "type": "BOOKING_CHANGED",   "label": "Interview booked" },
    { "occurredAt": "2026-06-05T08:00:00Z", "type": "STATUS_PUBLISHED",  "label": "Status updated" },
    { "occurredAt": "2026-06-06T12:00:00Z", "type": "SCORECARD_SUBMITTED","label": "Feedback submitted" }
  ],
  "feedbackPending": true
}
```

**Errors**: `404 {"error":"not_found"}` for unknown/erased/out-of-scope-HM candidate (byte-identical, no oracle — FR-022); `403` (Interviewer). Empty timeline → `200` with `events: []`. No free-text/scorecard content is ever included.

---

## 4. Requisitions (minimal management)

```
POST   /api/internal/requisitions                         (A)        create
GET    /api/internal/requisitions?status=OPEN|CLOSED|ALL  (A,R,RO)   list
PATCH  /api/internal/requisitions/{id}                     (A)        retitle / open|close
POST   /api/internal/requisitions/{id}/assignments        (A)        assign HM { memberId }
DELETE /api/internal/requisitions/{id}/assignments/{aid}  (A)        unassign HM
PUT    /api/internal/candidates/{candidateId}/requisition  (A,R)      set/clear link { requisitionId|null }
```

**Create** `POST /requisitions` (A):
```json
{ "title": "Senior Backend Engineer", "externalLabel": "GH-4821" }
→ 201 { "id": "...", "title": "...", "status": "OPEN", "externalLabel": "GH-4821", "createdAt": "..." }
```
Audited `REQUISITION_CREATED`.

**List** `GET /requisitions` (A/R/RO): `200 [{ id, title, status, externalLabel, createdAt }]`. PII-free.

**Patch** `PATCH /requisitions/{id}` (A): `{ "title"?: "...", "status"?: "OPEN|CLOSED" }` → `200`. Unknown id → `404 {"error":"not_found"}`. Audited `REQUISITION_UPDATED`.

**Assign HM** `POST /requisitions/{id}/assignments` (A): `{ "memberId": "..." }` → `201` (reuses `AssignmentService.create(..., REQUISITION, requisitionId)`; duplicate → idempotent `409`/existing). Audited `REQUISITION_HM_ASSIGNED`. Member not in workspace → no-oracle `404`.

**Unassign HM** `DELETE /requisitions/{id}/assignments/{assignmentId}` (A) → `204`. `AssignmentService.delete(ws, memberId, assignmentId)` requires the member id, so resolve the assignment first via `AssignmentService.getOrNotFound(ws, assignmentId)` (no-oracle 404 if unknown) to obtain its `memberId` (Backend review #6). Audited `REQUISITION_HM_UNASSIGNED`.

**Link candidate** `PUT /candidates/{candidateId}/requisition` (A,R): `{ "requisitionId": "..."|null }` → `200`. Validates candidate + requisition belong to workspace (else no-oracle `404`). Writes `CandidateEventType.REQUISITION_LINKED` to the candidate `auditLog` (FR-009). Changing the link flips HM visibility on next load (FR / US2-6).

**Auth on all requisition writes**: A only (except candidate-link which is A,R). RO/HM/I → 403 (verified by `RequisitionContractIT` 5-role matrix).

---

## Cross-cutting contract guarantees

- **No-oracle 404**: byte-identical `{"error":"not_found"}` across unknown / erased / out-of-scope for every read+timeline (SC-004).
- **No PII in logs**: pipeline/bulk/timeline log ids/enums/counts only; bulk skip reason and timeline carry no name/stage/content (FR-024/SC-008).
- **No-store**: pipeline list, bulk, and timeline responses set `Cache-Control: no-store`.
- **Five-role matrix** asserted by contract tests on every endpoint group with the exact outcomes above (SC-004).
