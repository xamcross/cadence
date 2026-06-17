# Phase 0 Research: SLA Nudge Engine (F31)

All decisions verified against real source under `backend/src/main/java/com/cadence`. No `NEEDS CLARIFICATION` remained in the spec; this records the design choices and their grounding.

## D1 — The canonical "last meaningful activity" instant = the existing `lastContactAt` (NOT a new field)

**Decision**: Realise spec FR-005 by **advancing the pre-existing `Candidate.lastContactAt`** at each qualifying write site, rather than adding a new `lastMeaningfulActivityAt` field.

**Rationale**:
- `Candidate.lastContactAt` is documented as "Retention age basis (GDPR last-activity) and the F00.1 `{workspaceId,lastContactAt}` index field," and `RetentionService` explicitly notes "the activity-refresh path that moves `lastContactAt` forward is a forward concern of F13/F22." F31 *is* that forward concern. The field exists for this purpose; it was simply never wired.
- Confirmed dormant: the only writer today is `CandidateService.create` (set to `now` at creation). No other path advances it.
- Reuse needs **no new field, no new index, no backfill**. The `{workspaceId, lastContactAt}` index (created by **`ChangeUnit001`** — `ChangeUnit005` explicitly does not recreate it) already backs the breach range-scan (SC-013). `RetentionService` uses `CandidateRepository.findByWorkspaceIdAndErasureStateAndLastContactAtBefore` (a 3-arg, unbounded `List` finder) — the scan does NOT reuse it as-is (it would be an unbounded read); F31 adds an **overloaded `(…, Pageable)`** variant and pages with `scanBatchLimit`, leaving the 3-arg method untouched for `RetentionService`.
- Conflating SLA-silence with retention-age on one "last activity" instant is **correct**: an actively-progressing candidate (just emailed, just booked, status just updated) is neither in silence nor stale, so a single advance resets both clocks consistently. This is a feature, not a coupling defect.

**Qualifying write sites that advance `lastContactAt = now`** (centralised in `CandidateActivityService.advanceLastContact`, a value-free `$set`):
1. **Outbound candidate email SENT** — `EmailDispatchService.dispatch` on the `SENDING → SENT` transition (covers INVITATION/CONFIRMATION/CANCELLATION/REMINDER_24H **and** the F31 SLA_HOLDING). Member/operational mail is excluded (it carries no `candidateId`).
2. **Candidate status published** — `CandidateStatusService.publish`, folded into the same atomic `$set`.
3. **Interview booked** — `SlotReservationService.book` on the `BOOKING → BOOKED` CAS.
4. **Interview rescheduled** — `SlotReservationService.forwardCommitParent` (new child booked + parent → RESCHEDULED).
5. **SLA-draft approved** — `SlaNudgeService.approve` advances it **synchronously** (in addition to the eventual SEND), so the breach clears immediately and the next sweep cannot re-draft in the approve→send window (closes a re-draft race).

**Alternatives considered**:
- *New `lastMeaningfulActivityAt` field + index + backfill* — rejected: more schema surface, a new changeset index, a backfill migration, and a second "activity" timestamp that would diverge from retention — all to avoid reusing a field that already means exactly this. Violates §I (YAGNI).
- *Compute the instant on-the-fly as max across `emailDispatches`/`statusPublishedAt`/bookings* — rejected: non-sargable (no single indexed field to range-scan), breaks SC-013's index-backed guarantee, and multiplies per-candidate reads in the scan.

**Spec reconciliation**: FR-005's "single canonical last-meaningful-activity instant, advanced at each qualifying write site" is satisfied exactly; the storage identity (`lastContactAt`) is an implementation choice. FR-005's "no candidate-originated action may advance the instant" holds — a candidate slot-pick commits via `SlotReservationService.book`, a **meaningful interview milestone both parties committed to** (distinct from a passive page-view / erasure-submit / inbound bounce, none of which call `advanceLastContact`). The intent — a candidate cannot *self-suppress* an SLA breach by mere presence — is preserved.

## D2 — Breach scan = one `@Scheduled` task on the F00.2 checkpoint pattern (the F23 shape)

**Decision**: `SlaNudgeScheduler` mirrors `NoShowDefenseScheduler`: `TASK_NAME = "sla-nudge-scan"`, `@PostConstruct registerReplayAction(TASK_NAME, this::sweep)`, `@Scheduled(fixedDelayString=...)`, `checkpoints.start/complete`, an injected `Clock`. The sweep **iterates `workspaceConfigRepository.findAll()`** (bounded by the small config count, not candidate volume — single-instance MVP), **skipping unconfigured workspaces** (`configuredAt==null`, the `RetentionService` precedent). Per configured workspace it computes `breachCutoff = now − Duration.ofDays(slaSilenceWindowDays)` and reads the **paginated** `candidates.findByWorkspaceIdAndErasureStateAndLastContactAtBefore(ws, ACTIVE, breachCutoff, PageRequest.of(0, scanBatchLimit))` (index-backed, bounded). Each returned candidate is filtered (gate + terminal guardrail), then a draft is de-dup-inserted. (The silence-list READ — contract §A — uses the wider `amberCutoff` and classifies in Java; the scan/drafting uses `breachCutoff` only.)

**Rationale**: Per-workspace iteration is simpler than F23's global-bound-then-Java-filter because the SLA window is a per-workspace **day** count and `findByWorkspaceIdAndErasureStateAndLastContactAtBefore` already exists and is index-backed. Correctness rests on the per-row de-dup (D3) + checkpoint replay, not single-threading (constitution §IV single-instance; overlapping fire safe). SC-006 (missed-fire) and SC-013 (index-backed, 1,000 candidates) verify.

**Alternatives**: a global bound like F23 — unnecessary here since the per-workspace derived query is index-backed and the workspace count is small (single-instance MVP).

## D3 — SLA draft = new `slaNudgeDrafts` collection, unique-partial de-dup, CAS approve

**Decision**: `SlaNudgeDraft` (`workspaceId`, `candidateId`, `status: SlaDraftStatus`, `messageType = SLA_HOLDING`, `detectedAt`, `actionedAt`, `actorMemberId`) — **no PII**. De-dup is a **unique partial index** `{workspaceId, candidateId}` with `partialFilterExpression {status: "OPEN"}`: the scan does `repo.insert(...)` and treats `DuplicateKeyException` as the idempotent no-op (the F22 `emailDispatches`/F23 precedent). On approve/dismiss/invalidate, `status` flips off `OPEN` → the row leaves the partial index → a future breach can create a new OPEN draft. Approve/dismiss are `findAndModify` CAS `{_id, status:OPEN} → APPROVED|DISMISSED` (loser = `matchedCount==0` no-op).

**Rationale**: One open draft per candidate per ongoing breach (FR-014/FR-015), idempotent across overlapping sweeps, concurrent-approve-safe (FR-022/SC-010), all with primitives already in heavy use — no broker, no transaction. Because `status` is always non-null, the partial filter is on the value `OPEN` (not an `$exists` null-collision case), so the F01 present-as-null footgun does not apply; nonetheless the field is plain (not converter-managed), so no `$unset`/`ClassCastException` trap.

**Alternatives**: a boolean `open` flag with a unique partial `{open:true}` — equivalent; the enum status is clearer and carries the audit lifecycle. A read-then-write de-dup — rejected (lost-update under overlapping sweeps).

## D4 — Approve routes through `EmailDispatchService.enqueue`; the send-time gate is authoritative

**Decision**: `SlaNudgeService.approve` does: (1) CAS `OPEN → APPROVED` (the **primary** single-winner guard — a concurrent loser is `matchedCount==0` and returns `ALREADY_ACTIONED`, never a second send; the F22 idempotency key is only a backstop since two approves with different `now` derive different keys); (2) `advanceLastContact(ws, candidateId, now)` (D1, clears breach immediately, asserted independently by SC-014 even if the send is later refused); (3) **in ONE try/catch (the F30 `sendConfirmations` precedent)** resolve `status_link` via `statusLinkFor` and `dispatch.enqueue(ws, candidateId, SLA_HOLDING, "BASE", now, nonPiiContext{status_link, expected_date}, renderContextRef=candidateId)` — `statusLinkFor` throws `ScopedNotFoundException` for an erased/inactive candidate, which is caught as a **no-send** approve outcome (fail-safe; the candidate is gone, nothing leaves); (4) `audit.append(..., SLA_DRAFT_APPROVED, ...)`. It **never** constructs an `OutboundEmail` or calls a transport directly. The bean injects `CandidateStatusService` via `@Lazy`/`ObjectProvider` to avoid the erasure→SLA→status constructor cycle (see D8).

**Rationale**: `EmailDispatchService.dispatch` re-evaluates `ContactPermissionGate.evaluate` **after** the dispatch claim, on every send, never cached (verified, `EmailDispatchService:187`). Routing approval through `enqueue` makes that the single authoritative suppression point (FR-023): a draft that slipped past the scan-time gate or went stale (candidate erased/withdrawn/undeliverable between draft and approve) is **REFUSED** at dispatch, not sent. The F22 dispatch idempotency key (`workspaceId|candidateId|SLA_HOLDING|scheduledForMillis`) makes even a double-claim a single send (FR-022). `status_link` is derived via `CandidateStatusService.statusLinkFor(ws, candidateId)` (provisioning the F30 token if needed) and rides `nonPiiContext` (the F30 `CONFIRMATION` precedent); `candidate_name`/`recruiter_name`/`workspace_name` resolve at render from refs (never passed as PII). Dismiss is CAS `OPEN → DISMISSED` + `SLA_DRAFT_DISMISSED` audit; sends nothing.

**Approval-on-recruiter-judgement** (spec Assumption): approve is permitted even if a later activity already cleared the breach (the recruiter chose to send the holding note); only **eligibility** (consent/erasure/undeliverable/terminal) is re-checked, at send time.

## D5 — Classification: GREEN/AMBER/RED under an injected `Clock` in the workspace zone

**Decision**: `classify(candidate, windowDays, amberMarginDays, clock, zone)`:
- `RED` if `lastContactAt < now − windowDays`.
- `AMBER` if `lastContactAt < now − (windowDays − amberMarginDays)` (within the nearing margin, not yet breached).
- else `GREEN`.
`amberMarginDays` is a global `SlaProperties` default (1 day) — **not** separately Admin-configurable in the MVP (spec FR-006). Terminal-outcome and erased candidates are never RED/AMBER (D6). The comparison uses `Instant.now(clock)` minus a `Duration.ofDays(...)`; day-granular display ("X days") uses `LocalDate.ofInstant(now, zone)` consistent with the F30 `displayState`.

**Rationale**: Injected `Clock` (the F01 `MutableClock`/`AuthTestConfig` pattern) makes the boundary and DST-crossing deterministic (FR-007/SC-009/SC-016). Using the indexed `lastContactAt` range for RED matches the scan query exactly (no divergence between the badge and the scan).

**DST note**: "N days" is evaluated as `Duration.ofDays(N)` against absolute `Instant`s, so a 23h/25h civil day does not flap the classification; the workspace zone affects only the human-readable day count, computed via `LocalDate` like F30. SC-009 asserts determinism across a DST boundary under the test clock.

## D6 — Suppression + stage-aware guardrail

**Decision**: Before creating a draft the scan checks, in order: (a) `ContactPermissionGate.evaluate(ws, candidateId).permit()` — deny (erased/withdrawn/over-retention/no-basis/undeliverable) → skip (FR-019); (b) **terminal guardrail** — `statusOutcome ∈ {COMPLETE_OFFER, COMPLETE_REJECTED}` → skip (FR-020) — these are also excluded from being a breach at all (FR-008), so a terminal candidate is GREEN-equivalent (not surfaced as silence). A candidate with **no** published status is the canonical silent case and **is** drafted.

**Rationale**: Reuses the exact F22 gate (precedence ERASED > OVER_RETENTION > WITHDRAWN > NO_BASIS > UNDELIVERABLE) so drafting can never target a candidate the send path would refuse, and the F30 `statusOutcome` for the sensitive-stage guardrail (matching the product's "auto-send off by default for sensitive stages" control). SC-005/SC-012 verify.

## D7 — No auto-send is structural (call-graph), not a flag

**Decision**: The scan creates drafts only; it has **no reference** to `EmailDispatchService`. The **only** caller that enqueues `SLA_HOLDING` is `SlaNudgeService.approve`, reachable only from the recruiter `POST .../approve` endpoint. SC-008 is a structural test: a reflection/source call-graph assertion that (i) `SlaNudgeScheduler` and `SlaNudgeService.scanWorkspace` never call `enqueue`, and (ii) every `enqueue(..., SLA_HOLDING, ...)` site is the approve path — the F22 `MailTransportSwapTest` constant-pool-scan precedent.

**Rationale**: "Structural absence, not a disabled flag" (FR-010) is a stronger guarantee and is non-vacuously testable.

## D8 — Erasure interaction (honest best-effort + authoritative gate)

**Decision**: `CandidateErasureService.wipe`, after its winning guarded `updateFirst`, calls a narrow `SlaDraftInvalidator.invalidateOpenDraft(ws, candidateId)` (CAS `OPEN → INVALIDATED`) alongside the existing `supersedeLiveScheduling`. The **authoritative** guarantee that an erased candidate is never messaged is the send-time gate (D4): even if a draft survives the wipe race, approving it sends nothing (the `statusLinkFor` 404 / the dispatch gate REFUSES ERASED). SC-015 asserts both the best-effort invalidation and the no-send.

**Cycle-break (the BLOCKER the plan review caught)**: `CandidateErasureService` must depend on the narrow `SlaDraftInvalidator` **interface**, NOT the concrete `SlaNudgeService` — because `SlaNudgeService` (via `@Lazy CandidateStatusService`) would otherwise transitively pull `CandidateStatusService → ErasureRequestService → CandidateErasureService`, a Spring constructor cycle that fails every `@SpringBootTest`. `SlaNudgeService implements SlaDraftInvalidator`; the interface edge carries no status-link dependency. (Belt-and-braces: `SlaNudgeService` also injects `CandidateStatusService` `@Lazy`.)

**Rationale**: Matches the F23 honest-bound (no false atomicity claim). The draft holds no PII, so a briefly-surviving INVALIDATED/OPEN row leaks nothing.

## D9 — Template reuse: zero new template artefacts

**Decision**: The holding draft uses `EmailMessageType.SLA_HOLDING` with the existing `BuiltInEmailTemplates` body and `MergeTokenCatalogue` permissions (`STATUS_LINK`, `EXPECTED_DATE`, + universals). Preview uses the F21 `EmailTemplateService` preview path (decrypts candidate merge fields, surfaces `[[missing:...]]` warnings) under the role gate + `no-store` (FR-013). **No** new `EmailMessageType`/`MergeToken`/built-in body/tone preset is added.

**Rationale**: All required artefacts exist (verified). Adding any would have to move atomically with the `@PostConstruct` completeness check + `BuiltInTemplateCompletenessTest` — avoided entirely.

## D10 — RBAC + no-existence-oracle

**Decision**: `SlaNudgeController` is `@PreAuthorize("hasAnyRole('ADMIN','RECRUITER')")`, workspace-scoped via the principal; a draft/candidate id from another workspace yields `RbacExceptions.ScopedNotFoundException` → an indistinguishable 404 (no oracle). A dedicated `SlaNudgeExceptionHandler` (`@RestControllerAdvice(assignableTypes=SlaNudgeController.class)`) renders the value-free envelope (404/400/429) — the existing scheduling/status handlers are `assignableTypes`-scoped and **not** inherited. **Critically, this handler must itself `@ExceptionHandler(ScopedNotFoundException)`** and emit the same body as its own `not_found`: the global `RbacExceptionHandler` maps `ScopedNotFoundException` to `{"error":"not_found","message":"Not found."}` (WITH a message), which is byte-divergent from a handler-local `{"error":"not_found"}` — so without the local override, a cross-workspace candidate (global handler) would be distinguishable from an unknown draftId (local handler), defeating SC-016. HM/Interviewer/Read-only are refused (5-role contract).

**Rationale**: The F30/F02 precedent; SC-016 asserts the no-oracle 404 and preview `no-store`.

## D11 — Notification scope (workspace, not per-recruiter — satisfies the fallback for free)

**Decision**: On draft creation the scan calls `RecruiterNotificationService.notify(ws, candidateId, SLA_DRAFT_PENDING)` (new append-only type). The existing notification model is **workspace+candidate scoped** (no per-candidate recruiter assignment exists in the MVP candidate model), so any Admin/Recruiter sees it — which inherently satisfies spec FR-012's "no assignable recruiter / deactivated assignee" fallback. The recruiter surface reads the workspace silence-list/draft queue rather than a per-recruiter inbox.

**Rationale**: No new assignment concept (the candidate→requisition→assignee join is F51's concern); the workspace-scoped notification is the existing, sufficient model and avoids inventing recruiter resolution (§I YAGNI). One notification per draft creation (de-dup'd because a draft is created at most once per breach).

## D12 — Mongock `ChangeUnit016` (order "016")

**Decision**: `ChangeUnit016_SlaNudgeIndexes` (order "016" off the highest applied "015") creates on `slaNudgeDrafts` a unique partial `{workspaceId, candidateId}` index with `partialFilterExpression {status: "OPEN"}` via native `createIndex` + targeted `dropIndex` rollback. **No `candidates` index** (the scan reuses the existing `{workspaceId, lastContactAt}`). No dedupe-before-index needed (new collection, no pre-existing rows). Comments pure-ASCII (the F30 NUL-byte/binary lesson).

## D13 — Frontend: recruiter-internal surface, no new candidate page

**Decision**: Extend the existing `/scheduling` recruiter component (already `roleGuard('ADMIN','RECRUITER')`) with a `.sla-nudge-panel`: a green/amber/red badge for the entered candidate, the pending draft (preview of rendered subject/body), and Approve/Dismiss buttons (signals + inline messages, the F30 status-panel pattern). New `sla-nudge.service.ts` (HttpClient, `apiBaseUrl`). **Internal screen → Lighthouse/WCAG N/A** (F50/F51 precedent); axe runs advisory. F31 adds **no candidate-facing page** — the SLA_HOLDING email links to the existing F30 status page (already WCAG-gated).

**Rationale**: There is no recruiter "candidates list" yet (manual candidate-id entry on `/scheduling`); riding that view is the minimal demonstrable §II leg. The richer board is F51.

## D14 — PII discipline

**Decision**: The draft stores ids/enums/instants only (no name/email/status text/token). Logs use ids + `.name()` strings + value-free outcome reasons (the enum→`kv` logstash Jackson-3 footgun avoided — never pass an enum to `StructuredArguments.kv`). `DeadLetterService.recordFailure` receives a PII-free summary (cause class only), never a raw render-exception message (the F22 dead-letter footgun). The decrypted `status_link`/preview output never reaches a logger/audit/dead-letter. `ci.yml` PII scan extended with `SENTINELF31*` driven through scan→draft→preview→approve→dispatch and asserted absent in logs, dead-letter, audit, and the draft doc (SC-007/FR-024/FR-025).
