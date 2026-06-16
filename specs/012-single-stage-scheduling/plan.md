# Implementation Plan: Flow A1 — Single-Stage Scheduling (F13)

**Branch**: `012-single-stage-scheduling` | **Date**: 2026-06-16 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/012-single-stage-scheduling/spec.md`

## Summary

F13 is the first end-to-end scheduling journey: a recruiter picks a candidate + interview template and sends one branded self-scheduling link; the candidate opens it (no login), picks a rule-compliant slot, and Cadence atomically reserves it, books calendar events for every participant, and confirms to everyone.

F13 is almost entirely **orchestration of existing seams** — it adds no provider SDK, no broker, and no new runtime dependency:

- **Compute** offered slots via the F12 `RuleEngine.compute(...)` (which already reads F10/F11 `AvailabilityService`), and **snapshot** them onto a new `schedulingRequests` document.
- **Authorize** candidate access by an opaque 256-bit token, hashed at rest with the F01 `TokenHasher` (HMAC + `TOKEN_PEPPER`), on the existing `/api/candidate/**` public-by-token security chain.
- **Reserve atomically** with two `findAndModify`/unique-index CAS layers: a request-level status CAS (same-candidate double-submit) plus a per-participant `{workspaceId, memberId, startAt}` unique-index claim (cross-request interviewer double-booking — the load-bearing correctness guard, the F10/F22 atomic-claim precedent).
- **Book** calendar events via the F10/F11 `CalendarEventService.createPanelEvents(...)` (provider-first, idempotent, compensating-delete rollback, `CLEANUP_INCOMPLETE` honest bound — reused unchanged).
- **Confirm** by enqueueing the candidate email through the F22 consent-gated `EmailDispatchService.enqueue(...)` and the participant emails through the F01 non-consent-gated member-mail path.
- **Recover** stuck reservations with a `@Scheduled` reaper on the F00.2 `SchedulerCheckpoint` pattern.

The §II demonstrable leg: a recruiter clicks "Send scheduling link" on a candidate (Angular → Spring → Mongo → email), the candidate opens the link in a functional Angular slot-picker, picks a slot, and calendar events + confirmation emails are produced — browser to database. Candidate-page **polish** (formal WCAG 2.2 AA axe sign-off, Lighthouse ≥ 85, full localization) is owned by **F14**; F13 ships the functional, no-login, time-zone-correct, `$localize`-marked page that F14 hardens.

## Technical Context

**Language/Version**: Java 21 (backend); TypeScript 5.4 / Angular 17.3 (frontend)
**Primary Dependencies**: Spring Boot 3.3.5 (web, data-mongodb, security w/ method security, actuator, aop, scheduling) — **no new runtime dependency**. Reuses F12 `RuleEngine`/`AvailabilityService`, F10/F11 `CalendarEventService`/`CalendarProviderClient`, F22 `EmailDispatchService`/`ContactPermissionGate`, F01 `TokenHasher`/`SecureTokens`/member-mail `EmailSender.sendEmail`, F00.2 `SchedulerCheckpointService`/`DeadLetterService`, F03 `WorkspaceConfig`, F04 `Candidate`. Per-IP rate limiting is an in-memory JDK component (single-instance topology — no Redis, constitution §IV). Mongock 5.4.4; logstash-logback-encoder 9.0.
**Storage**: MongoDB 7.x (Atlas prod, Testcontainers `mongo:7` tests). **Two new collections**: `schedulingRequests` (request lifecycle + offered-slot snapshot + hashed token + expiry; ids/instants/enums only — **no candidate PII**. The recruiter-provided interview location/dial-in is the single free-text field; because the candidate confirms asynchronously without the recruiter present, it is persisted on the request but **encrypted at rest** via the existing `PiiStringConverter` and excluded from all candidate/log/audit output — D2) and `interviewSlotClaims` (the per-participant atomic reservation guard; ids/instants only). Reuses `managedCalendarEvents` (F10, incl. its `{workspaceId,memberId,startAt}` index from ChangeUnit008 for re-validation reads), `candidates` (F04 read + erasure interaction), `emailDispatches` (F22), `schedulerCheckpoints`/`deadLetters` (F00.2), `interviewTemplates` (F12), `workspaceConfig` (F03), `authAuditLog`, `members`, `sessions`.
**Testing**: JUnit 5 + Mockito (unit: reservation state machine, slot snapshot/re-validation, token TTL/expiry classification, idempotency); Testcontainers MongoDB (integration: gated concurrent same-slot/cross-request claim CAS, rollback + CLEANUP_INCOMPLETE, reaper replay, expiry 410 vs 400, audit, PII-scan); MockMvc (contract: recruiter initiate/status endpoints + candidate slot/confirm endpoints); Jasmine (recruiter send action, candidate picker logic); Playwright (E2E: recruiter initiate → candidate books → events + emails → status). `spring-security-test` (already present).
**Target Platform**: Fly.io single Machine (backend), Cloudflare Pages (frontend) — topology unchanged.
**Project Type**: Web application (Spring Boot backend + Angular SPA).
**Performance Goals**: Initiate (compute + snapshot + send) under 30 s for a 5-person panel (SC-001, measured against the test provider harness); calendar events on all participants within 30 s of confirm (SC-004); candidate book under 2 min (SC-002).
**Constraints**: Exactly-one-winner under genuinely simultaneous confirms, zero double-booking (FR-012/SC-003) via the per-participant unique-index claim + gated concurrency test. **Documented honest bound (D3)**: the exact-`(member,startAt)` unique key fully serializes the dominant same-template case (slots share one cadence grid) and the committed-event case (via confirm-time re-validation); a sub-second TOCTOU between two *different-template*, partially-overlapping, different-start confirms for the same interviewer remains theoretically possible and is an accepted MVP limitation (closing it needs an interval-overlap reservation, deferred). The SC-003 gated test is scoped to the same-template guarantee it actually provides. Zero candidate PII and zero token values in logs/audit/persisted docs at any level (FR-024/SC-006). No queue broker; the reaper is `@Scheduled` + checkpoint (constitution §IV). Expired-but-extant token → 410; used/invalid/unknown → indistinguishable 400 (FR-008/SC-007). Candidate slot payload exposes times only, never participant identities (FR-011).
**Scale/Scope**: Two new collections + one Mongock changeset (`ChangeUnit012`, order **"012"** off the highest applied `011`); new initiation/status controllers (`/api/internal/**`, RBAC-gated) + candidate slot/confirm controller (`/api/candidate/**`, token-gated); a `SchedulingService` orchestrator + `SlotReservationService`; a `@Scheduled` reaper; a minimal Angular candidate `schedule` feature + a recruiter "Send scheduling link" action and per-candidate status chip.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Gate | Question | Result |
|---|---|---|
| **C1** | Within MVP scope (spec §11)? | ✅ Flow A1 single-stage scheduling is the constitution §11 core MVP flow (Principle I, first listed). |
| **C2** | New service, queue, or replica? | ✅ No. Two Mongo collections + a `@Scheduled` reaper on the F00.2 `SchedulerCheckpoint`; reservation is single-document `findAndModify`/unique-index CAS. No broker, no transaction-coordinator, no cache tier (constitution §IV async-work rule honoured). |
| **C3** | Exposes candidate PII to unauthorized roles? | ✅ No. Initiation/status endpoints are `@PreAuthorize` ADMIN/RECRUITER and workspace-scoped; candidate endpoints are public-by-token only and return **times, not participant identities** (FR-011); the new collections store no PII; logs/audit carry ids/enums only. |
| **C4** | Dependency outside the fixed stack? | ✅ No new dependency. The per-IP rate limiter is in-memory JDK (single-instance topology); everything else reuses existing seams. |
| **C5** | New/modified Windows scripts with non-ASCII? | ✅ No new/modified `.ps1`/`.cmd`/`.bat`. CI PII-scan extended (ASCII only). |
| **C6** | Multi-role sub-agent review (≥3) scheduled? | ✅ Spec already reviewed (4 roles); this plan is reviewed in this command (below); implementation review at task close. |
| **C7** | Downloads a build tool/runtime/CLI? | ✅ No. No new tool/runtime; reuses the cached gradle-9.4.0 + installed JDK. |

**Initial gate: PASS.** No Complexity Tracking entries required.

**Post-Phase-1 re-check: PASS** — design adds two collections, one changeset, zero dependencies, and reuses every existing seam (compute, calendar book/rollback, email enqueue, consent gate, checkpoint, token hash, audit). See Phase 1 artifacts. §IX candidate-page note recorded under "DoD note" below.

## Project Structure

### Documentation (this feature)

```text
specs/012-single-stage-scheduling/
├── plan.md              # This file
├── research.md          # Phase 0 — decisions (snapshot model, two-layer CAS, token/TTL/410, reaper, rate-limit, confirmation paths, §II)
├── data-model.md        # Phase 1 — SchedulingRequest + InterviewSlotClaim entities, indexes, state machine, candidate/dispatch interactions
├── quickstart.md        # Phase 1 — run/test/demo walkthrough
├── contracts/
│   └── scheduling-api.md # recruiter initiate/status endpoints, candidate slot/confirm endpoints, SchedulingService SPI
├── checklists/
│   └── requirements.md  # spec quality + multi-role spec-review notes (already present)
└── tasks.md             # Phase 2 (/speckit.tasks — NOT created here)
```

### Source Code (repository root)

```text
backend/src/main/java/com/cadence/
├── api/
│   ├── SchedulingController.java            # NEW POST /api/internal/candidates/{id}/scheduling (initiate), GET .../scheduling (status) — @PreAuthorize ADMIN/RECRUITER
│   ├── CandidateSchedulingController.java   # NEW GET  /api/candidate/scheduling/{token} (offered slots), POST .../{token}/confirm (book) — public-by-token
│   ├── SchedulingDtos.java                  # NEW request/response records (slot = times only, no participant identity)
│   └── SchedulingExceptionHandler.java      # NEW 400/403/404/409/410 envelopes (slot_taken=409, expired=410, invalid=400)
├── config/
│   ├── SchedulingProperties.java            # NEW token TTL default (72h), search-window default, reaper threshold, rate-limit (10/min/IP)
│   ├── MongoPiiConfig.java                  # MODIFIED register PiiStringConverter for SchedulingRequest.locationText (the one encrypted field)
│   └── migration/
│       └── ChangeUnit012_SchedulingIndexes.java  # NEW order "012"
├── domain/
│   ├── SchedulingRequest.java               # NEW request doc (NO @Version — status transitions are findAndModify CAS)
│   ├── OfferedSlot.java                      # NEW embedded snapshot slot (start/end/zone/requiredMemberIds/qualifyingByPoolIndex)
│   ├── SchedulingStatus.java                 # NEW PENDING_SELECTION/BOOKING/BOOKED/EXPIRED/SUPERSEDED/CLEANUP_INCOMPLETE
│   ├── InterviewSlotClaim.java               # NEW per-participant reservation doc (unique {workspaceId,memberId,startAt} partial active)
│   └── AuthEventType.java                    # MODIFIED + SCHEDULING_* append-only events
├── repository/
│   ├── SchedulingRequestRepository.java      # NEW findByWorkspaceIdAndId, findByTokenHash, @Query reaper finder (status+updatedAt, Pageable)
│   └── InterviewSlotClaimRepository.java      # NEW findByWorkspaceIdAndSchedulingRequestId (release set)
├── service/
│   ├── SchedulingService.java               # NEW orchestrator (initiate: gate→compute→snapshot→token→send; status read)
│   ├── SlotReservationService.java          # NEW confirm path: contactability re-check → request CAS → re-validate → claim → book → confirm → audit
│   └── CandidateErasureService.java         # MODIFIED erasure also supersedes live scheduling requests + releases claims (FR-014)
├── security/
│   └── SecurityConfig.java                   # (No change to chains — /api/candidate/** already on the @Order(2) permitAll/STATELESS chain.)
└── scheduler/
    └── SchedulingReaper.java                # NEW @Scheduled sweep on SchedulerCheckpoint (release stuck BOOKING + expire links)

backend/src/test/java/com/cadence/scheduling/      # NEW test package
frontend/src/app/features/
├── schedule/                                 # NEW candidate-facing standalone slot-picker (public route, no guard) — functional; F14 hardens WCAG/perf/i18n
└── candidates/ (or pipeline placeholder)     # MODIFIED add "Send scheduling link" action + scheduling-status chip
```

**Structure Decision**: Standard Cadence layout (constitution Reference Source Layout). The orchestrators live in `service/`, reservation CAS in `service/SlotReservationService`, the reaper in `scheduler/`, controllers split internal (`/api/internal`) vs candidate (`/api/candidate`). No new top-level module. Reuses `CalendarEventService`/`EmailDispatchService` unchanged (no edits to F10/F11/F22 service logic).

## Multi-role plan review (2026-06-16) — verdict: APPROVE-WITH-NITS

Reviewers: Backend/Architecture, Security/GDPR, DevOps/QA — each verified claims against the real source. All findings folded into the artifacts before task generation.

- **Backend (1 BLOCKING, fixed)**: D7's proposed new `EmailMessageType.SCHEDULING_INVITATION`/`INTERVIEW_CONFIRMATION` would fail the F21 `@PostConstruct` completeness checks at startup (closed enum iterated by three catalogues). **Fixed**: reuse the existing `EmailMessageType.INVITATION` (already mapped to the `SCHEDULING_LINK` token) and `CONFIRMATION` (interview date/time tokens) — F21 was built for exactly these two candidate sends; participant mail uses the F01 member-mail `sendEmail(memberId, "<operational-template>", model)` path. Confirmed two-layer CAS, partial-unique-index release-via-status-flip, reaper safety, reuse signatures, and Mongock order "012" all correct against source.
- **Security/GDPR (nits, fixed)**: pinned the ordered 410/400/200 token-response precedence in the contract (BOOKED→200, extant-and-expired-and-non-terminal→410, everything else→400); made the confirm refusal **byte-identical across all deny reasons** (no GDPR-status oracle); reconciled D8 to a new `CandidateRateLimiter` that **hashes** the IP key (the reused `LoginAttemptService` keys raw) and is explicitly advisory (correctness rests on the DB claim, not the limiter); confirmed `CandidateErasureService.wipe` supersession + claim-release is net-new behaviour (data-model §5) and `MongoPiiConfig` must register the new `locationText` converter property.
- **DevOps/QA (nits, fixed)**: softened the §IX DoD note (dropped the unverifiable F50/F51 citation; F13 runs axe **advisory**, F14 owns the blocking gate); pinned the concrete reaper inequality `reaperThreshold > (perCallReadTimeout + maxBackoff) × maxPanelSize`; carried the D3 cross-template double-book honest-bound into the Constraints body and scoped the SC-003 test to its same-template guarantee; confirmed C1–C7 judged correctly and the §II browser→DB leg is real.

No remaining blocking items. Residual items intentionally left to `tasks.md`: the concrete `partialFilterExpression` syntax + a RELEASED-claim-non-collision integration assertion; the `MongoPiiConfig` `locationText` registration task; the no-double-encryption guard on the converter field (F03 footgun).

DoD note: F13 **does** add a candidate-facing page, so it honours the substantive §IX obligations at ship — no-login access, time-zone-correct rendering, all candidate strings `$localize`-marked, no PII/token in URL or logs, and a real browser→DB E2E. Per the spec's explicit F13/F14 scope split (spec Assumptions), the **blocking** CI gates for WCAG 2.2 AA (axe-core 0 violations) and Lighthouse ≥ 85 on the scheduling page are owned and asserted by **F14**; F13 runs the axe scan in **advisory (non-blocking)** mode so the page is not shipped with an unknown accessibility state, but the formal gate lives in F14. This split follows the spec's authority, not a cross-feature precedent.

## Complexity Tracking

No constitution violations — table intentionally empty.

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| (none) | — | — |
