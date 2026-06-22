# Phase 0 Research: F51 Pipeline View

All Technical-Context unknowns are resolved below. Each decision is grounded in existing Cadence code/patterns (cited) so the build is reuse-first.

---

## D1 — How to introduce the requisition concept (the load-bearing new piece)

**Decision**: A new PII-free `requisitions` collection (`workspaceId`, `title`, `status` OPEN/CLOSED, optional `externalLabel`, `createdAt`, `createdByMemberId`) + one additive nullable `Candidate.requisitionId` field. Hiring-Manager scoping reuses the **existing** `assignments` collection with the already-defined `ResourceType.REQUISITION` and `AssignmentService.assignedResourceIds(...)`.

**Rationale**: The `Assignment` domain already declares `ResourceType.REQUISITION` and `AssignmentService` already exposes `assignedResourceIds(ws, member, type)` / `create(...)` / `getScopedOrNotFound(...)` — the assignment half of HM scoping is pre-built for exactly this feature. The requisition itself carries no candidate PII, so it is un-encrypted by design (the `interviewTemplates` / `managedCalendarEvents` precedent). Keeping the requisition minimal (title + open/closed + assignment) satisfies the backlog's "F51 owns the requisition linkage" mandate without a full requisition-management subsystem (§I YAGNI; FR-008..FR-011).

**Alternatives considered**:
- *Store the link as another `Assignment` (candidate↔requisition)*: rejected — `Assignment` scopes a **member** to a resource; a candidate is not a member, and overloading it would corrupt the RBAC semantics.
- *Reuse ATS `atsExternalJobId` / CSV `importRequisitionLabel` as the requisition key directly*: rejected as the primary model — those are provider-side strings with no workspace-level identity, lifecycle, or HM-assignability. They are instead **surfaced to assist manual linking** (FR-011), not promoted to the canonical key.

---

## D2 — `Candidate.requisitionId` field shape and erasure interaction

**Decision**: `requisitionId` is a nullable `String` annotated `@Field(write = Field.Write.NON_NULL)`, **not encrypted**, and **retained on erasure** (no change to `CandidateErasureService.wipe`).

**Rationale**: `requisitionId` is an internal identifier, not personal data — exactly like the retained `origin` / `importJobId` / `atsExternalRef` / `atsExternalJobId` fields. `@Field(write=NON_NULL)` omits it from the BSON when unassigned, so it never participates in a "present-as-null" index collision (the F01 footgun) and an HM `requisitionId ∈ [ids]` filter can never match an unassigned candidate (FR-014, by construction). Erased candidates are excluded from every view by the **`erasureState == ACTIVE`** query predicate (the F31/F50 precedent), so retaining the link leaks nothing (the erased doc carries no name/stage anyway). `wipe` needs no new line.

**Alternatives considered**:
- *Null `requisitionId` on erasure*: rejected — unnecessary (non-PII) and would lose the historical link with no privacy benefit; the active-state predicate is the authoritative exclusion.
- *Encrypt `requisitionId`*: rejected — it's an ObjectId-class internal reference, not PII; encrypting it would block the `$in` index used for HM scoping.

---

## D3 — Composing the pipeline row without an N+1 (SLA + stage + scheduling status)

**Decision**: Per page, exactly **two** index-backed reads + in-memory compose:
1. One candidate read (workspace-active page, or HM-scoped `requisitionId ∈ assignedIds`), bounded by `cadence.pipeline.scan-cap`.
2. One **batch** scheduling read `findByWorkspaceIdAndCandidateIdIn(ws, candidateIds)` projected to `{candidateId, status, sentAt, bookedStartAt, noShowAt, createdAt}`.
Then: **SLA colour** via a new thin `SlaNudgeService.classifyCandidate(cfg, candidate, now)` (pure in-memory over the already-loaded doc — **no query**), **stage** via the best decrypted label, **scheduling status** via a pure mapping over the per-candidate resolved request.

**Rationale**: `SlaNudgeService.classify(lastContactAt, createdAt, statusOutcome, erasureState, windowDays, amberMargin, now)` takes only fields already on the `Candidate` document, so SLA classification is free of extra DB calls — and reusing the exact function guarantees the pipeline and dashboard never disagree (FR-004). Scheduling status is the only fan-out risk: there is **no** batch finder today (`SchedulingRequestRepository` finders are all single-candidate or status-window), so a new candidate-id-set finder + a `{workspaceId, candidateId}` index turns 2×N round-trips into one read. Resolving "live BOOKED else newest" in memory mirrors the existing `SchedulingService.status(...)` resolution.

**Alternatives considered**:
- *Call `SlaNudgeService.candidateSla(id)` + `SchedulingService.status(id)` per row*: rejected — that's `candidateSla` 1 read + `status` 2 reads = **3×N** queries (≈600 round-trips at 200 candidates); fails SC-002.
- *Mongo aggregation `$lookup` join candidates→schedulingRequests*: rejected — adds query complexity, the computed SLA/stage still can't be expressed in the pipeline, and the two-read approach already meets < 3 s.

---

## D4 — Sorting/filtering on computed fields + pagination (the bounded-scan decision)

**Decision**: Compose → filter → sort → paginate **in memory** over a single bounded scan (`scan-cap`, default 1000). When the active (or HM-scoped) count exceeds the cap, return only the cap's worth, set a `truncated: true` flag in the response, and `log()` the truncation (ids/counts only). Sort offers stage / SLA / scheduling-status / most-recent-activity with a fixed stable tie-breaker (`lastActivityAt desc, then candidateId`).

**Rationale**: SLA colour and scheduling status are **computed**, not stored, so they cannot be a DB sort/filter key; an honest list that sorts by them must materialise the candidate set in memory. The F50 silence-list already reads a paginated batch and classifies in memory — same pattern. The cap bounds resource use for pathological workspaces; the explicit `truncated` flag honours the "no silent caps" discipline. For the MVP SMB scale (hundreds of active candidates) the cap is never hit. True server-side pagination on computed fields is an accepted deferred limitation (documented).

**Alternatives considered**:
- *DB-side pagination + sort only on stored fields*: rejected — would forbid "sort by SLA/scheduling status" (FR-002), a primary recruiter need.
- *Materialise a denormalised pipeline-projection collection updated by a scheduler*: rejected — new collection + new `@Scheduled` writer + staleness/consistency surface, for a read a two-query compose already serves in < 3 s (§I, §IV).

---

## D5 — Bulk actions: fan-out model, idempotency, and the no-GDPR-oracle skip reason

**Decision**: `POST /api/internal/pipeline/bulk` (Admin/Recruiter only) iterates the selection and, per candidate, (a) runs a **synchronous** `ContactPermissionGate` pre-check over the loaded doc → on deny, record a **single coarse `not_contactable`** outcome (byte-identical across all causes); (b) otherwise fan-out the existing seam — `EmailDispatchService.enqueue(... HOLD_UPDATE ...)` for the update verb, `SchedulingService.initiate(...)` for the scheduling-link verb — and record `enqueued`/`sent`. Returns a per-candidate result list. Max selection enforced (`bulk-max`, default 100) **before** any candidate is touched.

**Rationale**:
- **Idempotency for free (update verb)**: `EmailDispatchService.enqueue` is idempotent on the unique `{workspaceId, idempotencyKey}` index (`IdempotencyKeys.dispatchKey(ws, candidate, type, scheduledForMillis)`), so a concurrent/retried bulk submit produces at most one send per candidate (FR-019, the F22 outbox guarantee — proven by a gated 2-thread test).
- **Scheduling-link verb**: `SchedulingService.initiate(...)` is supersede-semantics — a re-submit mints a fresh link that **supersedes** the prior live request (one live link, no second concurrent booking path); FR-019's real hazard (double-booking) is structurally excluded. The accepted residual (a second invitation email on a true double-submit) is documented.
- **No GDPR oracle (FR-018/SC-006)**: the recruiter-visible skip is **exactly one** coarse value `not_contactable` for EVERY ineligible candidate — gate-deny (erased/withdrawn/over-retention/no-consent/undeliverable) AND not-actionable-at-execution — the F13 confirm-time / F23 escalation precedent. There is **no** distinct `not_found` skip for a real candidate (a second outcome would itself be an erasure oracle — Security review #1); all `SchedulingService.initiate(...)` exceptions (incl. the member-id-bearing `UnschedulableRequiredException`) collapse to the coarse skip with the payload discarded. The specific cause is recorded only in value-free internal audit keyed by id.
- **Authoritative backstop**: the existing **asynchronous** send-time gate (the F22 outbox claim re-evaluates `ContactPermissionGate`) remains the source of truth, so a candidate erased in the TOCTOU window between selection and dispatch is fail-closed (no message), and the synchronous pre-check is purely for immediate, honest recruiter feedback. "0 messages to non-contactable" (SC-006) holds even if the synchronous pre-check is stale.

**Alternatives considered**:
- *A new bulk-outbox collection / batch job*: rejected — the per-candidate outbox already gives idempotency, retry, and the consent gate; a bulk wrapper would duplicate it and risk a gate bypass (C3).
- *Synchronous send (block the request until all sent)*: rejected — the existing send path is the `@Scheduled` outbox; bulk enqueues and returns, consistent with §IV and the F22 model. The bulk result reports enqueue/skip, not delivery (honest contract, the F31 lesson).
- *Per-row distinct reason ("erased" vs "withdrawn")*: rejected — re-introduces the GDPR oracle the project bans.

---

## D6 — Candidate timeline source (PII-safe, chronological)

**Decision**: The timeline is a read-only projection over the candidate-keyed append-only `auditLog` (`CandidateAuditEvent` — ids/enums/instants only: `MESSAGE_SENT`, `BOOKING_CHANGED`, `STAGE_CHANGED`, `STATUS_PUBLISHED`, `SCORECARD_SUBMITTED`, `REQUISITION_LINKED`, erasure/retention events, …), ordered by `occurredAt`, each event type mapped to a human-readable label; enriched with feedback-pending status read from `feedbackRequests` for the candidate's interviews. HM access is scoped by resolving the candidate's `requisitionId` against `assignedResourceIds` (else no-oracle 404). Scorecard free-text content is **not** included (Recruiter/Admin read it only via the existing F32 path).

**Rationale**: `CandidateAuditEvent` is already a per-candidate, append-only, **PII-free** stream backed by the F00.1 `auditLog {candidateId, occurredAt:-1}` index — a ready-made timeline with zero new storage and zero PII risk (FR-021/023/024). Mapping enum→label keeps it human-readable without persisting any free text. Protected content (scorecard payload) stays behind its existing role gate (FR-022, the F32 deferral).

**Alternatives considered**:
- *Merge raw `emailDispatches` + `schedulingRequests` + status fields + `feedbackRequests` directly*: rejected as the primary source — more reads, more PII-handling surface, and the audit stream already records the same events PII-free. Feedback-pending is the one enrichment genuinely not in the audit stream, so it is the single extra read.
- *Persist a dedicated timeline collection*: rejected — pure duplication of the audit log (§I).

---

## D7 — Role/visibility enforcement and the no-existence-oracle contract

**Decision**: `PipelineController` is `@PreAuthorize("hasAnyRole('ADMIN','RECRUITER','READ_ONLY','HIRING_MANAGER')")` at class level (Interviewer → 403 by exclusion); the bulk endpoint adds method-level `@PreAuthorize("hasAnyRole('ADMIN','RECRUITER')")` (Read-only/HM/Interviewer → 403). The service picks the visibility predicate from the actor's persisted role (`principal.role()`): Admin/Recruiter/Read-only → workspace-active; Hiring Manager → `requisitionId ∈ assignedResourceIds(ws, member, REQUISITION)` with an empty assignment set short-circuiting to an empty page. A dedicated `PipelineExceptionHandler` (`@Order(HIGHEST_PRECEDENCE)`, `assignableTypes={PipelineController, RequisitionController}`) maps `ScopedNotFoundException` → `{"error":"not_found"}` and re-throws `AccessDeniedException`/`AuthenticationException` from its catch-all.

**Rationale**: This is the established F31/F32/F40 pattern verbatim (the global `RbacExceptionHandler` emits a divergent `{"error":"not_found","message":...}`, so a dedicated highest-precedence handler is required for byte-identical 404s — SC-004). Building the HM result *from* `assignedResourceIds` as the selection predicate (never fetch-all-then-filter) is the C3-safe construction; the empty-`$in` short-circuit prevents the "no assignments → accidentally unfiltered" footgun. Every new internal endpoint is added to / satisfied by `RbacEndpointInventoryTest` (method security present on all handlers).

**Alternatives considered**:
- *Filter rows client-side for HM*: rejected — violates C3 (PII would reach the browser); the spec mandates server-side (FR-012/FR-013).
- *Reuse the global RBAC handler*: rejected — its message field is an existence oracle (the F31 bug).

---

## D8 — Mongock changeset ordering and indexes

**Decision**: `ChangeUnit022_PipelineIndexes` (`order = "022"`, off the highest **applied** `"021"` = F50 `ChangeUnit021_DashboardIndexes`, **not** the branch number `025`). **Three** indexes:
- `requisitions {workspaceId, status}` (list + filter).
- `candidates {workspaceId, erasureState, createdAt}` (active-list read + stable pagination/sort key).
- `candidates {workspaceId, requisitionId}` (HM-scoped read; `requisitionId` NON_NULL so unassigned omitted).

The batch scheduling read reuses the **existing** `schedulingRequests {workspaceId, candidateId, createdAt:-1}` (`ChangeUnit012`) via its `{workspaceId, candidateId}` prefix — **no new scheduling index** (Backend review #2; a separate 2-field index would be redundant write cost). The timeline reuses the existing `auditLog {candidateId, occurredAt:-1}` (F00.1) — no new index.

**Rationale**: The CLAUDE.md rule is explicit — order off the highest *applied* changeset, never the branch number; F50 took "021". Native `createIndex` + targeted `dropIndex` rollback (never `dropIndexes()`). Distinct key patterns avoid the "two indexes, identical key" Mongo rejection (the F42 lesson — confirm none of the new patterns duplicate an existing one: the existing candidate index is `{workspaceId, lastContactAt}` and the existing scheduling indexes are status-leading, so all four new patterns are distinct).

**Alternatives considered**:
- *No new index, rely on `{workspaceId, lastContactAt}`*: rejected — that index doesn't cover an active-state-leading scan or a `requisitionId`/`candidateId` lookup; would force COLLSCANs failing SC-002.

---

## D9 — Frontend shape and the §IX gate question

**Decision**: One lazy Angular `pipeline` feature (`pipeline-list` + `candidate-timeline` standalone components + `pipeline.service`) behind `roleGuard('ADMIN','RECRUITER','READ_ONLY','HIRING_MANAGER')`, plus an Admin `features/admin/requisitions` surface behind `roleGuard('ADMIN')` (candidate-linking action also available to Recruiter). Polling refresh on a 60 s timer. **No Lighthouse/WCAG blocking gate** — these are internal staff screens (FR-025, the F50/F51 documented precedent).

**Rationale**: Internal screens are out of the candidate-experience §IX gate (constitution Principle IX targets candidate-facing pages; F50 dashboard set this precedent explicitly). The frontend follows the F42/F50 internal-feature shape (lazy standalone + service + role-guarded route + role-based nav). Backend `@PreAuthorize` is the authoritative boundary; the role guard is defence-in-depth/redirect-UX.

**Alternatives considered**:
- *Apply the candidate axe/Lighthouse harness*: rejected — N/A for internal screens; would add CI cost with no candidate-experience benefit (FR-025).
- *WebSocket/SSE live updates*: rejected — §IV (no broker / push infra); 60 s polling meets FR-006/SC-003.
