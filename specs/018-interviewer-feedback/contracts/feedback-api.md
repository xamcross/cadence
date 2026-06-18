# Contract: Interviewer Feedback API (F32)

Two surfaces: the **public no-login token endpoints** (interviewer scorecard form — on the `@Order(2)` `permitAll`/STATELESS/CSRF-disabled chain, rate-limited per hashed IP) and the **internal recruiter read** (`@PreAuthorize("hasAnyRole('ADMIN','RECRUITER')")`, workspace-scoped). All errors render through `FeedbackExceptionHandler` (`@Order(Ordered.HIGHEST_PRECEDENCE)`, `@RestControllerAdvice(assignableTypes={ScorecardTokenController, InterviewFeedbackController})`) as a value-free `{ "error": "..." }` envelope. The handler **MUST itself `@ExceptionHandler(ScopedNotFoundException)`** rendering `{"error":"not_found"}` (the global `RbacExceptionHandler` adds a `"message"` → byte-divergent oracle, the F31 fix), and its catch-all `@ExceptionHandler(RuntimeException)` **MUST re-throw `AccessDeniedException`/`AuthenticationException`** (else `@PreAuthorize` 403s become 500s — the F31 fix).

## A. Load the scorecard form (US1, FR-007/FR-017/FR-030) — PUBLIC, no login

`GET /api/feedback/{token}`

200 (PENDING, not expired) →
```json
{ "state": "FORM",
  "interviewLabel": "Backend Interview — 2026-06-15",
  "recommendationOptions": ["STRONG_YES","YES","NO","STRONG_NO"],
  "ratingDimensions": ["Technical","Communication"] }
```
- **Write-only**: the response carries the BLANK form + a coarse interview label only — **never** any previously submitted content for this or any interviewer (FR-017/SC-008). The interview label is a non-PII descriptor (stage/date); it carries no candidate name.
- **STATUS-before-TIME resolution** (data-model §6): unknown / invalidated (erasure) / submitted / uncollectible token → `200 {"state":"USED"}` (or the same invalid/used body) — **byte-identical** across these cases (no state oracle); a genuinely past-TTL `PENDING` token → `200 {"state":"EXPIRED"}` (distinct, helpful message). Implemented as a 200 state envelope (not 4xx) so a prefetch/scanner can't distinguish via status code; the body `state` drives the UI. (Alternatively 410 for expired / 404 for used — but the 200-state-envelope keeps invalid/used/expired byte-shape-controlled; the chosen form is pinned here and asserted in SC-023.)
- Rate-limited per hashed IP (`CandidateRateLimiter`) → `429 {"error":"rate_limited"}` (FR-020/SC-021). `Cache-Control: no-store`.

## B. Submit the scorecard (US1, FR-008/FR-010/FR-019/FR-028) — PUBLIC, no login

`POST /api/feedback/{token}`
```json
{ "recommendation": "YES",
  "ratings": [ {"dimension":"Technical","score":3}, {"dimension":"Communication","score":4} ],
  "comment": "Solid system-design answers; some gaps on concurrency." }
```

200 → `{ "state": "SUBMITTED" }` (idempotent: a second submit with the same token also returns `SUBMITTED`/already-submitted, never a duplicate record).
- Validation (FR-008): `recommendation` ∈ the four-point scale (required); each `ratings[].score` ∈ 1..4; `comment` ≤ bounded max. Invalid → `400 {"error":"invalid_scorecard"}` (field-level message, value-free) and **nothing persisted** (US1 scenario 4).
- CAS `{_id, status:PENDING} → SUBMITTED`, set encrypted `scorecardPayload` (JSON of the body) + `submittedAt=now`, `nextReminderDueAt=null` (stops reminders, FR-014). Concurrent double-submit → exactly one persisted (the loser is `matchedCount==0` → already-submitted, SC-009).
- Not `PENDING` / unknown / expired token → the same STATUS-before-TIME envelope as §A (no oracle). Audited `SCORECARD_SUBMITTED` (actor = the token-bound `interviewerMemberId`, never the raw token; SC-018).
- Rate-limited (429). The submit response **never echoes** prior content (FR-017).

## C. Recruiter per-interview feedback (US3, FR-024) — INTERNAL, ADMIN|RECRUITER

`GET /api/internal/interviews/{schedulingRequestId}/feedback`

200 →
```json
{ "interviewEventId": "65...",
  "items": [
    { "interviewerMemberId": "60a...", "status": "SUBMITTED",
      "scorecard": { "recommendation":"YES", "ratings":[{"dimension":"Technical","score":3}], "comment":"..." },
      "submittedAt": "2026-06-15T18:00:00Z" },
    { "interviewerMemberId": "60b...", "status": "PENDING", "scorecard": null, "submittedAt": null }
  ] }
```
- **Resolution (no empty-list oracle)**: first resolve the interview's existence via `SchedulingRequest.findByWorkspaceIdAndId(schedulingRequestId)` → `ScopedNotFoundException` (→ 404) if absent/cross-workspace; THEN `findByWorkspaceIdAndInterviewEventId` for the rows (reuses the pre-declared `{interviewEventId, submittedAt}` index). A returning-empty-list query alone cannot distinguish cross-workspace from no-feedback-yet, so the explicit booking resolution is required for the indistinguishable 404. Decrypts `scorecardPayload` for SUBMITTED rows under the role gate. `Cache-Control: no-store` (PII read); never logged.
- Cross-workspace / unknown `schedulingRequestId` → indistinguishable **404 `not_found`** (scoped, no oracle, SC-011); a real in-workspace interview with no feedback generated yet → **200 `{items:[]}`** (distinct from 404 by construction). 5-role: ADMIN/RECRUITER 200; **HM/Interviewer/Read-only → 403 `forbidden`** (HM deferred to F51, SC-010).

## D. Recruiter feedback-pending list (US3, FR-027) — INTERNAL, ADMIN|RECRUITER

`GET /api/internal/feedback/pending`

200 → `{ "items": [ { "interviewEventId":"65...", "interviewerMemberId":"60b...", "candidateId":"64...", "reminderLevelSent": 1 } ] }`
- Workspace-scoped; lists `PENDING` feedback requests (the per-interview/per-interviewer outstanding signal — FR-027, satisfies US-F32-5 "compliance stats" for the Recruiter/Admin audience at MVP). No candidate PII (ids only; the recruiter resolves names via the existing candidate read). `Cache-Control: no-store`.
- The cross-workspace aggregate turnaround metric + the pipeline board column + HM visibility are F50/F51.

## E. Feedback settings (US2/FR-013 — reuse, no new endpoint)

Set via the **existing** `PATCH /api/internal/workspace/settings` (F03): `{ "feedbackSubmissionDeadline": "PT24H", "feedbackReminderInterval": "PT24H" }` (ISO-8601 Durations; null = unchanged). Admin-only, cross-field validated (`> 0`), audited. F32 adds no endpoint here; the scheduler consumes the values, defaulting to `FeedbackProperties` when unset.

## F. Internal contract: interviewer member mail (FR-004) — NOT candidate dispatch

`EmailSender.sendEmail(interviewerMemberId, OperationalEmailTemplates.FEEDBACK_REQUEST_ID, {"link": rawScorecardUrl, "stage": "...", ...})` and `…FEEDBACK_REMINDER_ID, {"link":..., "urgency": "2", ...}`. Resolves the **member** address (`members.findByIdOptional`), substitutes via `{key}` `String.replace` (`SmtpEmailSender.substitute`). **No `ContactPermissionGate`, no `EmailDispatchService`** — the recipient is a workspace member, not a candidate. Reminder idempotency is the per-`{request, level}` CAS (data-model §5), not a candidate outbox.

## G. Internal contract: generation + reminder scan (no-auto-read, FR-005/FR-015)

`FeedbackScheduler.sweep()` (checkpoint `"feedback-scan"`, `@PostConstruct registerReplayAction`, the F23 shape): **stage 1 generation** — `SchedulingRequest` `status=BOOKED, feedbackGeneratedAt=null, bookedStartAt <= now - generationDelay` (Pageable; reuse `{status,bookedStartAt}`), CAS-stamp `feedbackGeneratedAt`, fan out per ACTIVE claim, `insert` (DuplicateKey no-op), member mail; **stage 2 reminders** — `findReminderDue(PENDING, now, Pageable)` on `{status,nextReminderDueAt}`, per-`{request,level}` CAS + member mail. Both stages bounded/index-backed (no full scan). The scheduler/scan has **no token-read path** — the scorecard content read is reachable only from §C (the structural write-only guarantee, SC-008).

## Status-code / state matrix

| Case | HTTP | Body |
|---|---|---|
| Public load — PENDING, valid | 200 | `{"state":"FORM", ...}` (`no-store`) |
| Public load/submit — used / submitted / invalidated(erased) / uncollectible / unknown | 200 | `{"state":"USED"}` (byte-identical — no oracle) |
| Public load/submit — past-TTL PENDING | 200 | `{"state":"EXPIRED"}` (distinct message) |
| Public submit — valid | 200 | `{"state":"SUBMITTED"}` (idempotent) |
| Public submit — invalid scorecard | 400 | `{"error":"invalid_scorecard"}` (value-free, nothing persisted) |
| Public — rate-limited | 429 | `{"error":"rate_limited"}` |
| Recruiter read — ADMIN/RECRUITER | 200 | as §C/§D (`no-store`) |
| Recruiter read — cross-workspace / unknown id | 404 | `{"error":"not_found"}` (indistinguishable) |
| Recruiter read — HM / Interviewer / Read-only | 403 | `{"error":"forbidden"}` (RBAC envelope; HM deferred to F51) |

## Test surface (maps to SC)

- **SC-001** generate → `GET /api/feedback/{token}` (no auth) → `POST` submit → persisted → recruiter `GET …/feedback` reads it.
- **SC-002** N-participant panel → N independent requests/tokens; one submit doesn't complete/expose another's.
- **SC-003** repeated/overlapping `sweep()` → exactly one request + one email per participant. **Gated (latch-based, ≥2-thread) concurrent generation test** racing the `{_id,status:BOOKED,feedbackGeneratedAt:null}` CAS + the unique `{interviewEventId, interviewerMemberId}` insert (the SC-020 `gate(n)` pattern — non-vacuous, not just a sequential double-sweep).
- **SC-004** unsubmitted: **no reminder strictly before the first-reminder instant** (= `bookedStartAt + generationDelay + effectiveSubmissionDeadline` — the concrete pinned anchor, NOT the spec prose "end+24h"); L1 at that instant, L2/L3 at `+reminderInterval`, stop at max 3 — exact count + distinct per-level markers under `MutableClock`.
- **SC-005** submit → no further reminders; assert the SUBMITTED row is absent from `findReminderDue` (terminal-status drop-out).
- **SC-006** double-`sweep()` / checkpoint replay → no duplicate request or reminder.
- **SC-007 / SC-023** expired (past-TTL PENDING) → `EXPIRED`; used/invalidated/unknown → byte-identical `USED`.
- **SC-008** write-only: load/submit responses carry no submitted content; token-routed surface bounded to load+submit (route-inventory/structural assertion).
- **SC-009** **gated** (latch-based, ≥2-thread) concurrent double-submit (same token) → exactly one persisted scorecard (the `PENDING→SUBMITTED` CAS; the `gate(n)` harness, non-vacuous).
- **SC-010** 5-role matrix on `GET …/feedback`: ADMIN/RECRUITER 200; HM/Interviewer/Read-only 403.
- **SC-011** cross-workspace interview id → indistinguishable 404.
- **SC-012** CANCELLED / RESCHEDULED-away / future booking → no generation.
- **SC-013** candidate erasure → **every** scorecard's content gone (PENDING rows → INVALIDATED + `scorecardPayload` null; **SUBMITTED rows → `scorecardPayload` null** too — the review BLOCKER), tokens dropped (links → `USED`), no further reminders. Asserts a *submitted* scorecard's content is absent after erasure (raw-driver read).
- **SC-014** `SENTINELF32*` (scorecard text/recommendation, interviewer email, candidate name) absent from logs / dead-letter / audit / row, incl. a forced render/send failure → dead-letter carries only the cause class.
- **SC-015** deactivated interviewer at generation AND at reminder → no send + `FEEDBACK_UNCOLLECTIBLE` fallback alert.
- **SC-016** deadline boundary + DST in the workspace zone deterministic under `MutableClock`.
- **SC-017** recruiter pending view shows per-interviewer outstanding.
- **SC-018** settings change + submission + invalidation audited (value-free; actor = interviewer id for submit).
- **SC-019** no voice-to-scorecard / configurable-template capability exists.
- **SC-020** gated concurrent per-level reminder → one send.
- **SC-021** public endpoint over the per-minute cap → 429.
- **SC-022** reschedule after submission retains the submitted scorecard; the new occurrence generates a fresh request. (Note: a *generated* occurrence is in the past, and F20 refuses rescheduling a past interview, so the occurred-and-generated-then-rescheduled case is unreachable — research D2; SC-022 covers the submitted-then-attempted-reschedule retention.)
- **SC-024** under `MutableClock`, advance past `expiresAt` on an unsubmitted PENDING request → the reminder scan flips it `PENDING→EXPIRED`, asserts **zero sends** and `nextReminderDueAt` cleared (the EXPIRED-stops-reminders leg, distinct from SC-005/SC-007).
- **SC-025** FR↔SC traceability — discharged by `checklists/requirements.md` (the SC inventory covers every FR; no untraced requirement).
- **FeedbackIndexTest** (the F23 `SchedulingIndexTest` / F31 `SlaNudgeIndexTest` precedent): asserts `ChangeUnit017` materializes the unique `{interviewEventId, interviewerMemberId}`, the partial-unique `{tokenHash}`, and `{status, nextReminderDueAt}` on startup; and that the pre-declared `{interviewEventId, submittedAt}` is present. (Resolves the prior "index-backed bounded scan SC-…" placeholder.)
- **Missing-merge-key discipline (FR-011)**: a test asserts every `{key}` in both new `OperationalEmailTemplates` (request + reminder, incl. `{urgency}`) is supplied at every `sendEmail` call site — the operational `substitute` leaves an unsupplied `{key}` as a literal (no F21 warning on this path), so this guards against a literal `{urgency}` shipping to the interviewer.
- **RbacEndpointInventoryTest**: add `"/api/feedback/"` to `ALLOWED_PREFIXES` (the public token endpoints carry no `@PreAuthorize`); the internal controllers keep `@PreAuthorize` and stay inventory-enforced. The build fails without this.
- **Public page §IX**: axe 0 WCAG 2.2 AA across states; Lighthouse Performance ≥85 (mobile) via the `/feedback` stub route; ≥44 px targets (explicit `getBoundingClientRect` test — `target-size` is not in the axe WCAG tag set); token never in storage. **F14 footgun**: the axe fixture MUST be `document.body.appendChild`-attached or colour-contrast/visibility rules silently no-op.
