# Phase 1 Data Model: Interviewer Feedback Forms & Reminder Escalation (F32)

## 1. New collection: `feedbackRequests`

`FeedbackRequest` (`@Document("feedbackRequests")`) — the ask for one interviewer to score one interview occurrence, and (on submission) the captured scorecard. The only PII at rest is the **encrypted `scorecardPayload`**; everything else is ids/enums/instants + the hashed token.

| Field | Type | Notes |
|---|---|---|
| `id` | `String` (`@Id`) | ObjectId hex. |
| `workspaceId` | `String` | Tenant scope. |
| `candidateId` | `String` | Internal id (non-PII) — the data subject the scorecard concerns; the erasure target. |
| `interviewEventId` | `String` | = `SchedulingRequest.id` (the booking ref / interview occurrence). |
| `interviewerMemberId` | `String` | The interviewer (Member) who must submit; the request-email recipient. |
| `status` | `FeedbackRequestStatus` | `PENDING` → `SUBMITTED` / `INVALIDATED` / `UNCOLLECTIBLE` / `EXPIRED`. Always non-null. |
| `tokenHash` | `String` | `@Field(write=NON_NULL)`; `HMAC-SHA-256(rawToken, TOKEN_PEPPER)` (`TokenHasher.hashToken`). Unique **partial** `{$exists:true}` index. Raw token never persisted. Dropped (`$unset`) on invalidate/erasure so the link 404s. |
| `expiresAt` | `Instant` | Token TTL (default `now + PT72H`). STATUS-before-TIME: only a `PENDING` row past this returns "expired". |
| `reminderLevelSent` | `int` | 0 at generation; CAS-incremented per reminder (L1/L2/L3). The per-`{request, level}` guard. |
| `nextReminderDueAt` | `Instant` | When the next reminder is due; `null` once `maxReminders` reached or terminal. Backs the reminder scan (index §4). Initial value = `generatedAt + submissionDeadline` (no reminder before deadline). |
| `lastReminderAt` | `Instant` | Last reminder send instant; null until L1. |
| `scorecardPayload` | `String` | `@JsonIgnore @Field(value="scorecardPayload", write=NON_NULL)` — **encrypted** (converter, §7) JSON of the submitted scorecard `{recommendation, ratings[], comment}`. Null until submitted; `$set null` on erasure. The ONLY PII at rest. |
| `submittedAt` | `Instant` | Set on submission; null = pending (the `{interviewEventId, submittedAt}` index semantics). |
| `createdAt` / `updatedAt` | `Instant` | Lifecycle stamps. |

`toString()` is ids/status/instants only — never `tokenHash` or `scorecardPayload`.

**Invariants**:
- At most **one** request per `{interviewEventId, interviewerMemberId}` — unique index (§4), insert-catch-`DuplicateKeyException` (not read-then-write).
- Status transitions are one-way out of `PENDING`: `PENDING → {SUBMITTED, INVALIDATED, UNCOLLECTIBLE, EXPIRED}` via `findAndModify` CAS `{_id, status:PENDING}`. No transition back into `PENDING`.
- `scorecardPayload` is set **only** on the `PENDING → SUBMITTED` CAS.

## 2. New / modified enums

- **`FeedbackRequestStatus`** (new): `PENDING`, `SUBMITTED`, `INVALIDATED` (erasure), `UNCOLLECTIBLE` (interviewer deactivated), `EXPIRED` (TTL). Append-only.
- **`Recommendation`** (new): `STRONG_YES`, `YES`, `NO`, `STRONG_NO` — the required fixed-scale overall recommendation, stored **inside** `scorecardPayload` JSON (not a top-level queryable field — it's candidate-assessment PII).
- **`RecruiterNotificationType`** (MODIFIED, append-only): `+ FEEDBACK_UNCOLLECTIBLE` (deactivated interviewer / cannot collect — the workspace-scoped fallback alert).
- **`CandidateEventType`** (MODIFIED, append-only): `+ SCORECARD_SUBMITTED`, `FEEDBACK_INVALIDATED`. (Request *generation* and *reminders* are value-free scheduler logs, not candidate-audited; the spec audits submission + invalidation + the settings change.)

Current values verified — `RecruiterNotificationType`: `DISPATCH_REFUSED, DISPATCH_FAILED, DISPATCH_BOUNCED, INTERVIEW_CANCELLED_BY_CANDIDATE, RESCHEDULE_NO_SLOTS, RESCHEDULE_CAP_REACHED, CALENDAR_CLEANUP_INCOMPLETE, INTERVIEW_UNCONFIRMED, SLA_DRAFT_PENDING`. `CandidateEventType`: `…, STATUS_LINK_ROTATED, SLA_DRAFT_APPROVED, SLA_DRAFT_DISMISSED`.

## 3. `SchedulingRequest` — one new stamp (the F23 pattern)

Add `@Field(value="feedbackGeneratedAt", write=NON_NULL) private Instant feedbackGeneratedAt;` — null until generation fires; CAS-set once (§5). No other change. (Verified F23 added `bookedStartAt`/`confirmationRequestedAt`/`escalatedAt`/`noShowAt` the same way.) The denormalized `bookedStartAt` (existing) is the occurrence-time field the generation scan ranges on. No `bookedEndAt` exists — see research D2 for the `generationDelay` grounding.

## 4. Mongock `ChangeUnit017_FeedbackIndexes` (order "017")

Off the highest **applied** order "016" (`ChangeUnit016_SlaNudgeIndexes`). Native `createIndex`; targeted `dropIndex` rollback (never `dropIndexes()`); pure-ASCII comments.

```text
feedbackRequests:
  unique { interviewEventId: 1, interviewerMemberId: 1 }        # de-dup one request per {occurrence, interviewer} (FR-003)
  unique partial { tokenHash: 1 }
     partialFilterExpression { tokenHash: { $exists: true } }   # token lookup; partial avoids null-collision (F23 precedent)
  (non-unique) { status: 1, nextReminderDueAt: 1 }              # reminder scan (FR-015)
```

**Already present (NOT recreated)**: `feedbackRequests {interviewEventId, submittedAt}` (`ChangeUnit001`/F00.1) — backs the recruiter per-interview read (pending = submittedAt null). **No `schedulingRequests` index** — the generation scan reuses `{status, bookedStartAt}` (`ChangeUnit014`). No `@Field(write=NON_NULL)` footgun on the partial index: it keys on `tokenHash` `$exists`, and the field is `write=NON_NULL` so a null is omitted (present-as-null can't occur).

## 5. Generation → reminder → submit state machine

```
[generation scan] read SchedulingRequest: status=BOOKED, bookedStartAt <= now - generationDelay, feedbackGeneratedAt=null  (Pageable cap)
  # index {status,bookedStartAt} covers status+bookedStartAt; feedbackGeneratedAt=null is an IN-MEMORY RESIDUAL filter
  # (the F23 confirmationRequestedAt=null precedent) bounded by generationQueryLowerBound + the Pageable cap. Not a third index key.
  per booking:
    CAS {_id, status:BOOKED, feedbackGeneratedAt:null} -> set feedbackGeneratedAt=now   (fire once; loser no-op)   [matched only]
    participants = feedbackRequests fan-out over InterviewSlotClaimRepository.findByWorkspaceIdAndSchedulingRequestId(ws, reqId) filtered to ClaimStatus.ACTIVE
    # NOTE: SlotReservationService.participantsFromClaims is PRIVATE; FeedbackService queries the repository directly (the reusable seam)
    per interviewer:
      if member inactive/removed -> notify FEEDBACK_UNCOLLECTIBLE (workspace fallback); skip          (FR-009)
      else: mint token; insert FeedbackRequest{PENDING, tokenHash, expiresAt=now+tokenTtl, reminderLevelSent=0, nextReminderDueAt=now+submissionDeadline}
            catch DuplicateKeyException -> no-op (already generated)                                    (FR-003)
            sendEmail(interviewerMemberId, FEEDBACK_REQUEST_ID, {link, stage, ...})                     (FR-004)

[reminder scan] read FeedbackRequest: status=PENDING, nextReminderDueAt <= now   (index {status,nextReminderDueAt}, Pageable cap)
  per request:
    if interviewer inactive -> CAS PENDING->UNCOLLECTIBLE ; notify FEEDBACK_UNCOLLECTIBLE ; (no send)   (FR-009/FR-014)
    elif now >= expiresAt    -> CAS PENDING->EXPIRED ; (no send)                                        (FR-014/FR-018)
    else CAS {_id, status:PENDING, reminderLevelSent:L} -> reminderLevelSent=L+1, lastReminderAt=now,
             nextReminderDueAt = (L+1 < maxReminders ? now+reminderInterval : null)
         then sendEmail(interviewerMemberId, FEEDBACK_REMINDER_ID, {link, urgency:L+1, ...})            (FR-012/FR-015)

[submit]   GET resolve by tokenHash (STATUS-before-TIME) -> blank form (no content);  POST validate ->
           CAS {_id, status:PENDING} -> SUBMITTED, set scorecardPayload(enc), submittedAt=now, nextReminderDueAt=null
           audit SCORECARD_SUBMITTED ; idempotent re-submit -> "already submitted" (matched==0)         (FR-010/FR-019)
[invalidate] (erasure) BOTH non-cleared states (FR-023 — content of ANY scorecard for the candidate is wiped, not just PENDING):
   updateMulti {workspaceId, candidateId, status:PENDING}   -> $set status=INVALIDATED, $set scorecardPayload=null, $unset tokenHash
   updateMulti {workspaceId, candidateId, status:SUBMITTED} -> $set scorecardPayload=null, $unset tokenHash   (keep status=SUBMITTED for the "who responded" trail; the CONTENT is gone)
[recruiter read] resolve SchedulingRequest.findByWorkspaceIdAndId(reqId) first (cross-workspace/unknown -> ScopedNotFoundException -> 404, no oracle),
   THEN findByWorkspaceIdAndInterviewEventId -> per-interviewer status + decrypt scorecardPayload (ADMIN/RECRUITER) (FR-024/FR-026)
```

Concurrency: generation `feedbackGeneratedAt` CAS + the unique `{interviewEventId, interviewerMemberId}` index ⇒ exactly one request per participant across overlapping/replayed sweeps (SC-003/SC-006). Submit/invalidate/each-reminder CAS on `{status:PENDING[, reminderLevelSent:L]}` ⇒ single winner (SC-009/SC-020). No transaction, no broker.

## 6. Token resolution precedence (FR-030 / SC-023) — STATUS-before-TIME

```
resolve(rawToken):
  if blank -> invalid/used response
  req = findByTokenHash(hash(rawToken))           # tokenHash dropped on invalidate/erasure -> not found
  if req == null                       -> invalid/used   (byte-identical: unknown / erased / invalidated)
  if req.status != PENDING             -> invalid/used   (SUBMITTED / UNCOLLECTIBLE / EXPIRED — byte-identical)   [STATUS first]
  if now >= req.expiresAt              -> "expired"      (distinct message)                                       [TIME second]
  else                                 -> PENDING (serve blank form / accept submit)
```

The endpoint never reveals "candidate erased" / "interview cancelled" — those collapse to the same invalid/used body (no state oracle). Only a genuinely past-TTL `PENDING` request is "expired".

## 7. Scorecard PII at rest (FR-028) — one encrypted field

`MongoPiiConfig.mongoCustomConversions` gains `registrar.registerConverter(FeedbackRequest.class, "scorecardPayload", converter);` (the verified F13 `SchedulingRequest.locationText` / F30 `Candidate.statusStage` precedent). `scorecardPayload` holds the JSON `{recommendation, ratings:[{dimension,score}], comment}`. Because it is converter-managed: clear with `$set null` on erasure (NEVER `$unset` — the F03 `ClassCastException` trap), `@JsonIgnore`, `@Field(write=NON_NULL)`, excluded from `toString()`. The recruiter read decrypts (repo load) + parses + returns under the role gate + `Cache-Control: no-store`; never logged. Structured ratings + recommendation are inside the blob (candidate-assessment PII), so there is a single erasure/encryption target.

## 8. `WorkspaceConfig` settings (FR-013) — the F23 pattern

Add nullable `Duration feedbackSubmissionDeadline`, `Duration feedbackReminderInterval` (+ getters), exactly as F23 added `confirmationLeadTime`/`unconfirmedEscalationDeadline` (verified). Set via the existing `PATCH /api/internal/workspace/settings` (Admin, audited). `SettingsPatch` and `WorkspaceConfigResponse` records gain the two fields **at the end** (positional — fix the existing constructor call sites, the F23 test-breakage lesson). `WorkspaceConfigService.updateSettings` cross-field validates the **effective** values (`patch ?? current ?? global`): `feedbackSubmissionDeadline > 0`, `feedbackReminderInterval > 0` (the F23 validator precedent). `maxReminders`, `generationDelay`, `tokenTtl` stay global (`FeedbackProperties`).

## 9. `FeedbackProperties` (new global config)

`cadence.feedback.*`: `generationDelay` (default `PT3H` — post-interview offset, research D2), `submissionDeadline` (default `PT24H`, fallback when workspace unset), `reminderInterval` (default `PT24H`, fallback), `maxReminders` (default 3), `tokenTtl` (default `PT72H`), `scanInterval` (e.g. `PT5M` fixedDelay), `scanBatchLimit` (Pageable cap), `generationQueryLowerBound` (a window floor so the generation scan doesn't re-range ancient bookings unboundedly — e.g. only bookings in the last `PT720H`). Cross-field bounds validated at startup.

## 10. Erasure folding (FR-023) — with the cycle-break

`CandidateErasureService.wipe`: after the winning guarded `updateFirst`, call `feedbackInvalidator.invalidateForCandidate(ws, candidateId)` (best-effort) alongside `supersedeLiveScheduling` + `slaDraftInvalidator.invalidateOpenDraft` (verified winner branch at `CandidateErasureService:88-92`). The invalidator clears **every** scorecard for the candidate, not just pending ones (the review BLOCKER — a SUBMITTED row's `scorecardPayload` is candidate-assessment PII and MUST be wiped under FR-023/SC-013; encryption with a retained workspace key is not erasure): `updateMulti({workspaceId, candidateId, status:PENDING}, $set status=INVALIDATED, $set scorecardPayload=null, $unset tokenHash)` **AND** `updateMulti({workspaceId, candidateId, status:SUBMITTED}, $set scorecardPayload=null, $unset tokenHash)` (the SUBMITTED row keeps its status for the "who responded" trail, but its content is gone). No new `candidates` field. **Cycle-break**: `CandidateErasureService` depends on the narrow `FeedbackInvalidator` interface (the `SlaDraftInvalidator` precedent), not the concrete `FeedbackService`. F32's `FeedbackService` does not transitively pull `CandidateStatusService`/`ErasureRequestService`, so no `@Lazy` is expected to be needed (verify with `@SpringBootTest` startup; add `@Lazy` only if a cycle appears). The authoritative no-leak guarantee is the wipe of `scorecardPayload` + the token drop + STATUS-before-TIME resolution + the booking supersede (D8).
