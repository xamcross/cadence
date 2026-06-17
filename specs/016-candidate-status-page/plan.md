# Implementation Plan: Candidate Status Page (F30)

**Branch**: `016-candidate-status-page` | **Date**: 2026-06-17 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/016-candidate-status-page/spec.md`

## Summary

F30 gives every active candidate a private, no-login status page that honestly shows their current stage, a plain-English next step, an expected date, and a contact route — the anti-ghosting surface named in constitution Principle I / §11 MVP (backlog F30, Tier 2 P2). Recruiters keep that status current from their internal candidate view, and the system refuses to publish a dateless/contentless "we'll be in touch". The page is also the candidate's self-service GDPR erasure entry point.

Like F13/F20/F23, F30 is **almost entirely orchestration of existing seams**: **no new collection, no new runtime dependency, no broker.** The candidate-visible status is stored as additive fields on the existing `candidates` document (the established "later features extend `Candidate`" pattern), and every supporting primitive already exists — `SecureTokens`/`TokenHasher` (token mint/hash), the `@Order(2)` permitAll/STATELESS candidate chain, `CandidateRateLimiter` (per-IP 429), `MongoPiiConfig`/`PiiStringConverter` (free-text at-rest encryption — the `SchedulingRequest.locationText` precedent), `PublicBrandingController`/`BrandingService` (logo + brand colour), `ErasureRequestService.requestErasure` (already labelled "the F30 forward contract"), `CandidateAuditService`, and the F14 candidate-page front-end harness (`_headers`/CSP, `axe.ts`, the Lighthouse stub).

Three load-bearing design decisions, all forced by the real code:

- **The status access token is dual-stored**: a deterministic `statusTokenHash` (HMAC, partial-unique indexed) for resolving an inbound request, **plus** a reversibly-**encrypted** `statusToken` for re-deriving the `{{status_link}}` URL at any later email-render time (`MergeToken.STATUS_LINK` already exists and is referenced by the `HOLD_UPDATE`/`REJECTION`/`SLA_HOLDING` built-ins). This is the F01.1 OAuth-refresh-token precedent (a bearer credential that must be re-presented), not the F23 confirm-token model (single delivery → hash-only). Rotation (FR-029) regenerates both; erasure (FR-024) clears both **inside** the F04 wipe.
- **The candidate-visible page state is computed server-side** to a single `displayState` enum (`TERMINAL > PAST_DATE > PUBLISHED > UNDER_REVIEW`, FR-008) against the **workspace** time zone (FR-017), so the candidate payload stays minimal (text + one state, no client branching) and the precedence can't diverge between clients.
- **Status publish is one atomic `findAndModify`/`$set`** of all status fields guarded on `erasureState:ACTIVE` (last-valid-write-wins, FR-016; no `@Version`, no partial state; converter encrypts the free-text `$set` value — the F03 `EmailConfig` precedent). The recruiter free-text (`statusStage`, `statusNextStep`) is encrypted at rest and **output-escaped on render** (Angular interpolation, never `innerHTML` — FR-009).

The §II/§IX demonstrable leg: a recruiter publishes a status on the per-candidate view and copies the status link (and the post-booking `CONFIRMATION` email carries `{{status_link}}`); the candidate opens it with no login → an Angular status page (branded, time-zone-correct, WCAG 2.2 AA, `$localize`) showing stage + next step + expected date + contact route; the candidate taps "Request data deletion" → an Admin-confirmable erasure request is recorded (no immediate wipe) — browser to database.

## Technical Context

**Language/Version**: Java 21 (backend); TypeScript 5.4 / Angular 17.3 (frontend)
**Primary Dependencies**: Spring Boot 3.3.5 (web, data-mongodb, security w/ method security, actuator, aop, scheduling) — **no new runtime dependency**. Reuses F01 `SecureTokens`/`TokenHasher`/`PiiCrypto`/`PiiStringConverter`/`MongoPiiConfig`, F04 `Candidate`/`CandidateRepository`/`CandidateErasureService`/`CandidateAuditService`/`ErasureRequestService`/`ErasureRequest`, F03 `WorkspaceConfig`/`BrandingService`/`PublicBrandingController`, F13 `CandidateRateLimiter`/`SchedulingProperties`/the `@Order(2)` candidate chain, F21 `MergeToken.STATUS_LINK`/`MergeTokenCatalogue`/`BuiltInEmailTemplates`/`MergeRenderer`. Frontend: Angular standalone + Angular CDK a11y + `axe-core`/`@lhci/cli` (already F14 devDependencies). Mongock 5.4.4; logstash-logback-encoder 9.0.
**Storage**: MongoDB 7.x (Atlas prod, Testcontainers `mongo:7` tests). **No new collection.** Extends `candidates` with additive status fields (`statusStage`/`statusNextStep` — encrypted free text; `statusExpectedDate`; `statusOutcome` enum; `statusPublishedAt`/`statusPublishedByMemberId`; `statusToken` — encrypted reversible; `statusTokenHash` — HMAC). Reuses `erasureRequests` (F04 — the candidate-initiated intake) and adds an idempotency index there. Reads `workspaceConfig`/`workspaceLogo` (branding + workspace zone). **One new Mongock changeset** `ChangeUnit015` (order **"015"** off the highest applied **"014"**): partial-unique `{statusTokenHash}` on `candidates` + unique partial `{workspaceId,candidateId}` over `status:PENDING` on `erasureRequests`.
**Testing**: JUnit 5 + Mockito (unit: `displayState` precedence incl. past-date-in-workspace-zone, publish validation IN_PROGRESS vs TERMINAL, last-write-wins `$set`); Testcontainers MongoDB (integration: raw-driver ciphertext on `statusStage`/`statusNextStep`/`statusToken`, cold-converter reload decrypt, token rotate invalidates old + resolves new, erasure clears status+token atomically, erasure-request idempotency single-PENDING, partial-unique `statusTokenHash` no-null-collision, audit append, `SENTINELF30*` PII scan across logs/doc); MockMvc (contract: candidate view 200/404-indistinguishable/429/no-store; candidate erasure-submit 202-ack-indistinguishable/405-on-GET/429; recruiter publish + rotate 5-role matrix + scoped-404 + validation 400 + 409/404 on erased; rotate returns new link). Jasmine + axe-core (status page: 0 WCAG 2.2 AA violations across all states, no-login, time-zone, branding, escaped free-text, no token in storage/console, long/RTL no h-scroll). **E2E (backlog-required: status page via token → displays stage+expected date → recruiter updates → page reflects on reload) runs as a Testcontainers + Jasmine pairing against the real controller — NOT Playwright (no Chromium download, C7/Principle X).** `spring-security-test` (present).
**Target Platform**: Fly.io single Machine (backend), Cloudflare Pages (frontend) — topology unchanged.
**Project Type**: Web application (Spring Boot backend + Angular SPA) — both change.
**Performance Goals**: Status page < 2 s on 4G / Lighthouse ≥ 85 mobile (SC-002); candidate locates stage/next-step/date within 10 s (SC-001). All reads are single-document by indexed hash; no scan.
**Constraints**: No-existence-oracle on BOTH the view and the erasure-submit paths — byte-identical response across {unknown, malformed, erased} (FR-023/FR-031, SC-007/SC-010). Long-lived bearer token hardened by: page transport controls (no-store + `Referrer-Policy: no-referrer` + CSP — the F14 `_headers` leg, FR-032/SC-012), recruiter/admin rotation/revocation (FR-029/SC-011), per-IP 10/min 429 (FR-030/SC-009). Dateless/contentless publish refused (FR-011/FR-012, SC-004). Last-valid-write-wins atomic publish, no partial state (FR-016). Recruiter free-text encrypted at rest + escaped on render (FR-009), never logged (FR-033); token value never logged (FR-034). Candidate status page WCAG 2.2 AA axe **blocking** (F30 owns its candidate surface — no successor polish feature), no login, all strings `$localize`, time-zone-correct (§IX). Erasure routed to Admin confirm, never immediate, id-only record, idempotent (FR-019..023, SC-008).
**Scale/Scope**: Zero new collections; one Mongock changeset (`ChangeUnit015`); additive fields on `Candidate`; one new service (`CandidateStatusService`) + the candidate-facing intake wiring on `ErasureRequestService` (idempotency hardening); new candidate controller(s) (`CandidateStatusController` — view + erasure-submit) and recruiter routes (`CandidateStatusController` internal — publish + rotate, or methods on the existing GDPR/candidate controller); extends `CandidateErasureService.wipe` (clear status + token), `MongoPiiConfig` (3 fields), `MergeTokenCatalogue`/`BuiltInEmailTemplates` (`STATUS_LINK` for `CONFIRMATION`); enums (`CandidateStatusOutcome`, `CandidateEventType.STATUS_PUBLISHED`/`STATUS_LINK_ROTATED`); one new Angular candidate status page + a recruiter status panel on the existing per-candidate view; `lighthouserc.json` + `serve-with-stub.mjs` extended.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Gate | Question | Result |
|---|---|---|
| **C1** | Within MVP scope (spec §11)? | ✅ Candidate Status Page is named explicitly in constitution Principle I and §11 MVP (backlog F30, Tier 2 P2). The "we'll be in touch with no date is not acceptable" rule is in Principle IX. SLA breach/nudges (F31), the pipeline view (F51), and auto-status-from-bookings are correctly fenced out. |
| **C2** | New service, queue, or replica? | ✅ No. No new collection (status is additive fields on `candidates`), no broker/queue/cache. The candidate-initiated erasure reuses the existing F04 `ErasureRequestService` + the Admin confirm path; no `@Scheduled` work is introduced. |
| **C3** | Exposes candidate PII to unauthorized roles? | ✅ No. The candidate view/erasure endpoints are public-by-token, resolve **solely** from the credential (no IDOR), return a minimal server-computed `displayState` + escaped text, and are byte-identical on not-found (no existence oracle). Recruiter publish/rotate are `@PreAuthorize("hasAnyRole('ADMIN','RECRUITER')")` + workspace-scoped (oracle-free 404); HM is **not** granted status write (spec FR-010). Free text is encrypted at rest; the erasure record is id-only. |
| **C4** | Dependency outside the fixed stack? | ✅ No new dependency. `axe-core`/`@lhci/cli` already F14 devDependencies; everything else reuses existing seams. |
| **C5** | New/modified Windows scripts with non-ASCII? | ✅ No new/modified `.ps1`/`.cmd`/`.bat`. CI PII-scan extended (ASCII only). |
| **C6** | Multi-role sub-agent review (≥3) scheduled? | ✅ Spec reviewed (3 roles, applied). This plan is reviewed in this command (below, against real source). Implementation review at task close. |
| **C7** | Downloads a build tool/runtime/CLI? | ✅ No. Reuses cached `gradle-9.4.0` + installed JDK; `npm ci` installs already-declared F14 devDeps. **The E2E uses the existing Karma/EdgeHeadless + Testcontainers harness — `playwright install` (Chromium download) is NOT run** (the F14/F20/F23 decision, carried forward). |

**Initial gate: PASS.** No Complexity Tracking entries required.

**Post-Phase-1 re-check: PASS** — design adds zero collections, one changeset, zero dependencies, reuses every F01/F04/F03/F13/F21 seam; the only schema changes are additive fields on `candidates` + two indexes. The §IX obligation (F30 owns the blocking axe/Lighthouse gate on its new candidate status page) is recorded in the DoD note. The one F04-service change (idempotency on `ErasureRequestService.requestErasure`) is additive and hardens the existing operator path too. See Phase 1 artifacts (research.md, data-model.md, contracts/, quickstart.md).

## Project Structure

### Documentation (this feature)

```text
specs/016-candidate-status-page/
├── plan.md              # This file
├── research.md          # Phase 0 — D1 status-on-Candidate, D2 dual token store, D3 atomic publish, D4 validation, D5 displayState precedence, D6 view+oracle, D7 erasure intake+idempotency, D8 rotation, D9 link delivery/merge, D10 transport controls, D11 frontend, D12 RBAC, D13 ChangeUnit015, D14 audit, D15 PII
├── data-model.md        # Phase 1 — Candidate status fields + enums, erasureRequests idempotency, ChangeUnit015, validation rules, displayState transitions
├── quickstart.md        # Phase 1 — run/test/demo walkthrough (publish → view → reflect-on-reload → erasure-request → rotate)
├── contracts/
│   └── status-page-api.md  # candidate view + candidate erasure-submit + recruiter publish/rotate + link-derivation SPI
├── checklists/
│   └── requirements.md  # spec quality + multi-role spec-review notes (already present)
└── tasks.md             # Phase 2 (/speckit.tasks — NOT created here)
```

### Source Code (repository root)

```text
backend/src/main/java/com/cadence/
├── api/
│   ├── CandidateStatusController.java          # NEW (candidate-facing): GET /api/candidate/status/{token} (view, no-store, 429, indistinguishable 404); POST /api/candidate/status/{token}/erasure-request (affirmative POST, 405-on-GET, 429, indistinguishable ack)
│   ├── CandidateStatusAdminController.java      # NEW (internal): PUT /api/internal/candidates/{id}/status (publish; ADMIN|RECRUITER); POST /api/internal/candidates/{id}/status/rotate-link (rotate; ADMIN|RECRUITER); GET .../status (recruiter read incl. current link)
│   ├── CandidateStatusDtos.java                 # NEW PublishStatusRequest, CandidateStatusView (displayState + escaped fields + branding ref), RecruiterStatusResponse (incl. statusLink), RotateLinkResponse, ErasureAckResponse
│   ├── CandidateStatusExceptions.java           # NEW StatusNotFound (-> indistinguishable 404), InvalidStatusPublish (-> 400); reuse RateLimited (429), ScopedNotFound (404)
│   └── CandidateStatusExceptionHandler.java      # NEW @RestControllerAdvice bound to the F30 controllers (the SchedulingExceptionHandler is assignableTypes-scoped & NOT inherited) — byte-identical 404/202/400/429 envelopes (the load-bearing no-oracle piece)
├── config/
│   ├── StatusPageProperties.java               # NEW cadence.status.* (spaStatusBasePath default /status; rate-limit reuse SchedulingProperties)
│   ├── MongoPiiConfig.java                      # MODIFIED register Candidate.statusStage, Candidate.statusNextStep, Candidate.statusToken (PiiStringConverter)
│   └── migration/
│       └── ChangeUnit015_CandidateStatusIndexes.java  # NEW order "015": partial-unique {statusTokenHash} on candidates; unique partial {workspaceId,candidateId} over status:PENDING on erasureRequests
├── domain/
│   ├── Candidate.java                           # MODIFIED + statusStage/statusNextStep (@Field write=NON_NULL, @JsonIgnore, encrypted), statusExpectedDate (LocalDate), statusOutcome (enum), statusPublishedAt, statusPublishedByMemberId, statusToken (@Field write=NON_NULL,@JsonIgnore, encrypted), statusTokenHash (@Field write=NON_NULL,@JsonIgnore); toString omits all PII/token
│   ├── CandidateStatusOutcome.java              # NEW enum IN_PROGRESS, COMPLETE_OFFER, COMPLETE_REJECTED
│   └── CandidateEventType.java                  # MODIFIED + STATUS_PUBLISHED, STATUS_LINK_ISSUED, STATUS_LINK_ROTATED (append-only)
├── repository/
│   ├── CandidateRepository.java                # MODIFIED + Optional<Candidate> findByStatusTokenHash(String)
│   └── ErasureRequestRepository.java           # MODIFIED + existsByWorkspaceIdAndCandidateIdAndStatus(...) (idempotency read; the unique index is the real guard)
├── service/
│   ├── CandidateStatusService.java             # NEW publish (atomic $set, validate, audit), view (resolve+displayState+oracle), rotateLink, ensureProvisioned, statusLinkFor (decrypt token -> URL), requestErasureByToken (idempotent intake)
│   ├── ErasureRequestService.java              # MODIFIED requestErasure idempotent (no 2nd PENDING; catch DuplicateKey -> existing) — hardens the operator path too
│   ├── CandidateErasureService.java            # MODIFIED wipe(): $set null statusStage/statusNextStep/statusExpectedDate/statusOutcome/statusPublishedAt + $set null statusToken (converter-managed -> NEVER $unset, the F03 ClassCastException trap) + $unset statusTokenHash (plain) — atomic in the existing wipe update
│   ├── BuiltInEmailTemplates.java              # MODIFIED CONFIRMATION body gains the {{status_link}} CTA (candidate tracks an ongoing process)
│   └── MergeTokenCatalogue.java                # MODIFIED permit STATUS_LINK for CONFIRMATION
backend/src/test/java/com/cadence/status/       # NEW package (unit + Testcontainers + MockMvc + PII scan)
frontend/src/app/features/
├── status/                                      # NEW standalone candidate status page (public route /status?token=) — WCAG 2.2 AA, $localize, <2s, branding, escaped free-text, contact route, "Request data deletion" -> ack
│   ├── candidate-status.component.ts / .spec.ts
│   └── status.service.ts
└── (recruiter per-candidate view)               # MODIFIED add status panel (stage/next-step/expected-date form with validation + "Copy status link" + "Rotate link")
frontend/src/app/app.routes.ts                   # MODIFIED + public /status route (lazy)
frontend/lighthouse/serve-with-stub.mjs          # MODIFIED + canned GET /api/candidate/status/<demo> (open + terminal + under-review states) + SPA fallback
lighthouserc.json                                # MODIFIED + /status?token=lighthouse-demo (+ state variants) in ci.collect.url[]
.github/workflows/ci.yml                         # MODIFIED PII scan + SENTINELF30* (statusStage/statusNextStep) + token sentinel
```

**Structure Decision**: Standard Cadence layout. F30 adds **one new service** (`CandidateStatusService`) and **two thin controllers** (candidate-facing view+erasure; internal publish+rotate) — the rest extends F04/F03/F21 seams. Candidate-visible status lives as additive encrypted fields on `candidates` (the documented "later features extend `Candidate`" pattern, F13/F40/F42 precedent), so erasure clears it in the existing wipe and there is no new collection (C2). The new candidate page lives under `features/status/`, mirroring the F14 `features/booking/` candidate-page harness.

## Multi-role plan review (2026-06-17) — verdict: APPROVE-WITH-FIXES (applied)

Reviewers: Backend/Architecture, Security/GDPR, QA/DevOps — each verified claims against the **real source**. Two BLOCKERs (one found independently by two reviewers) and several SHOULD-FIX items were folded into the artifacts before `tasks.md`:

- **`$unset statusToken` → `ClassCastException` (Backend + Security, BLOCKER — would break the very erasure-atomicity the plan promises)**: `statusToken` is converter-managed (`MongoPiiConfig`), and `$unset` on a converter field throws (the F03 `WorkspaceConfigService.unsetCredential` lesson). **Fixed** → wipe `$set null` for `statusToken`, `$unset` only the plain `statusTokenHash` (plan source-tree line, data-model §5); added a "erase-with-provisioned-token succeeds + old token 404s" test.
- **Rate-limit threshold off-by-five (QA, BLOCKER — a literal "11th call" test would fail)**: the **test profile** sets `rate-limit-per-minute: 5`, so the 6th call 429s (prod 10 → 11th). **Fixed** → quickstart states "(`rateLimitPerMinute`+1)th call", not a hard-coded number.
- **No-oracle depends on a controller-bound exception handler (Security, SHOULD-FIX)**: the existing `SchedulingExceptionHandler` is `assignableTypes`-scoped and NOT inherited by the F30 controllers → the default `/error` body would become the oracle. **Fixed** → added `CandidateStatusExceptionHandler` (`@RestControllerAdvice`) as an explicit artifact + contract assertion (byte-identical 404/202/400/429).
- **`displayState` needs an injected `Clock` for SC-013/SC-016 determinism (QA, SHOULD-FIX)**: **Fixed** → `CandidateStatusService` injects `Clock` (the F01 `MutableClock`/`AuthTestConfig` pattern); `today = LocalDate.ofInstant(now(clock), workspaceZone)`, never `LocalDate.now()` (research D5, data-model §3).
- **Idempotency unique index is an untested prod-startup-failure path (QA + Backend, SHOULD-FIX)**: a pre-F30 workspace can hold ≥2 PENDING rows for one candidate (`requestErasure` `save()`s unconditionally), failing the unique-index build → Mongock aborts. **Fixed** → `ChangeUnit015` dedupe-before-`createIndex` promoted to a **required** step with a Testcontainers test (data-model §8).
- **SC-005 update-reflect leg under-specified (QA, SHOULD-FIX)**: **Fixed** → contract/quickstart now require publish v1 → view → publish v2 → view (prove update reflects, not just first write).
- **FR-016 concurrent-edit needs an explicit 2-writer test (QA, SHOULD-FIX)**: **Fixed** → added the F21/F13 race-precedent integration test to contracts/quickstart (not just a single-thread `$set` unit).
- **`STATUS_LINK` into `CONFIRMATION` is one ATOMIC change (Backend, SHOULD-FIX)**: the built-in body + `MergeTokenCatalogue` permission + tone-preset propagation move together or `BuiltInTemplateCompletenessTest`/`@PostConstruct` crash. **Fixed** → research D9 pins it as one task.
- **Decrypted-link leak path (Security, SHOULD-FIX)**: the `statusLinkFor` decrypt output must never reach a logger/audit/dead-letter; **Fixed** → SENTINELF30 token sentinel driven through publish→email-render→dispatch (research D9/D15).
- **FR-027 "constant-time" reframed (Security, SHOULD-FIX)**: satisfied structurally — the token is HMAC-hashed and index-resolved, never byte-compared (research D2/D6). **Lazy-provision-on-GET now audited** `STATUS_LINK_ISSUED` (Security NIT) — no silent credential mint (data-model §7, contract D).
- **Dropped dead hedges (all three, NIT)**: `ErasureReasonCode.CANDIDATE_REQUEST` already exists — reuse it, no enum change.

**Post-Phase-1 re-check after fixes: PASS.** No remaining blocking items. Residual mechanics left to `tasks.md`: the exact `ChangeUnit015` dedupe loop; the `serve-with-stub.mjs` distinct-demo-token branches + which `lighthouserc.json` URL is the perf gate (PUBLISHED only); the `CandidateStatusExceptionHandler` envelope wiring.

DoD note (§IX): **F30 owns the blocking accessibility/performance gate on its new candidate status page** (the F14/F20/F23 precedent — no successor polish feature). The status page MUST pass axe-core 0 WCAG 2.2 AA violations (per `displayState`) and Lighthouse ≥ 85 (mobile, the PUBLISHED state) as blocking CI gates, all strings `$localize`, no-login, time-zone-correct, branded, free-text escaped, no PII/token in URL or logs. Recruiter status panel is an internal screen (Lighthouse/WCAG N/A, the F50/F51 precedent).

## Complexity Tracking

No constitution violations — table intentionally empty.

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| (none) | — | — |
