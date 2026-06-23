# Implementation Plan: Join / Express-Interest Request Form (F70)

**Branch**: `029-join-interest-form` | **Date**: 2026-06-23 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/029-join-interest-form/spec.md`

## Summary

A public, no-login "Request access / Express interest" form for prospective **workspace members** (not candidates), feeding an Admin-only review queue that converts a request into an invitation via the existing invitation flow. The form captures intent only; it never creates accounts or grants access (invitation-only preserved). The feature is overwhelmingly a **reuse** of existing seams — PII encryption, keyed email-hash lookup, the public security chain, the no-oracle handler, the hashed-IP rate limiter, the scheduled-checkpoint purge, the operational member-notification channel, and `InvitationService` — plus **one new collection** (`interestRequests`), **one Mongock changeset** (`ChangeUnit023`), and **no new runtime dependency**. Technical approach and all open decisions are resolved in [research.md](./research.md); the entity/index design is in [data-model.md](./data-model.md); the wire shapes are in [contracts/interest-api.md](./contracts/interest-api.md).

## Technical Context

**Language/Version**: Java 21 (backend, toolchain pinned); TypeScript 5.4 / Angular 17.3 (frontend)  
**Primary Dependencies**: Spring Boot 3.3.5 (web, data-mongodb, security w/ method security, actuator, aop, scheduling); Mongock 5.4.4; logstash-logback-encoder 9.0. **No new backend or frontend runtime dependency.** Reuses F01 `InvitationService`/`PiiCrypto`/`PiiStringConverter`/`MongoPiiConfig`/`TokenHasher`, F02 RBAC/`RbacEndpointInventoryTest`, F03 `WorkspaceConfig.retentionPeriodDays`, F13 `CandidateRateLimiter`/the `@Order(2)` public chain, F22/F31 `RecruiterNotificationService`/`SchedulerCheckpointService`, F42 `CsvInjectionEscaper`, F60 SEO machinery, F14 `axe-core`/`@lhci/cli`.  
**Storage**: MongoDB 7.x (Atlas prod, Testcontainers `mongo:7` tests). One new collection `interestRequests` (PII fields encrypted; ids/hashes/enums/instants otherwise). Reads `workspaceConfig` (retention) and reuses `invitations`/`members` via `InvitationService`.  
**Testing**: JUnit 5 + Testcontainers (integration), MockMvc (contract), Jasmine + `axe-core` + `@lhci/cli` (frontend/a11y/perf), `MutableClock` (deterministic time).  
**Target Platform**: Fly.io single Machine (backend), Cloudflare Pages (frontend) — single-instance topology unchanged.  
**Project Type**: Web application (Angular SPA + Spring Boot API + MongoDB).  
**Performance Goals**: public form Lighthouse ≥ 85 mobile, < 2s on 4G (Principle IX); submit p95 well within standard web expectations (single insert + 2 hashed lookups).  
**Constraints**: zero PII in logs/dead-letter/notification; no-oracle public responses (byte-identical body+status+headers; **structurally** constant-time — same code path, side effects deferred off the response path; not a wall-clock assertion — R8); WCAG 2.2 AA on the public page; `@Scheduled`-only async (no broker); pure-ASCII Mongock/script sources.  
**Scale/Scope**: 1 new collection, 1 Mongock changeset, ~6 endpoints (1 public + 5 internal), 1 scheduler, 1 public Angular page + 1 internal admin screen, ~1 new config-properties class.

## Constitution Check

*GATE: must pass before Phase 0. Re-checked after Phase 1 (below).*

| Gate | Question | Result |
|---|---|---|
| **C1** | Within MVP scope (spec §11)? | **FLAGGED — requires owner ratification.** This is a member-onboarding on-ramp, NOT in the §11 enumerated list. Proceeding under the **F60/F61 precedent** (SEO features shipped outside §11 as accepted supporting capabilities). See research R7 and the spec Governance Note. If the owner declines → defer (C1 fail action). **No implementation task starts until the owner confirms.** Recorded in Complexity Tracking. |
| **C2** | New service / queue / replica? | **PASS.** One new collection; async is `@Scheduled` + `SchedulerCheckpointService` (Mongo job state), no broker. No new process/replica. |
| **C3** | Exposes personal data to unauthorized roles? | **PASS (by design).** Interest PII is encrypted at rest; the review queue is Admin-only (`@PreAuthorize("hasRole('ADMIN')")`, `/api/internal/**`, workspace-scoped, scoped-404 no-oracle); the public response is byte-identical (body+status+headers) + structurally constant-time (no existence oracle); admin notifications are value-free. |
| **C4** | Dependency outside the fixed stack? | **PASS.** No new runtime dependency (reuse only). |
| **C5** | New/modified Windows scripts contain non-ASCII? | **PASS (planned).** No new `.ps1`. The Mongock `ChangeUnit023` Java source is held pure-ASCII (F30 lesson); CI `SENTINELF70*` grep additions are ASCII. Byte-scan at task close (Principle V). |
| **C6** | Multi-role sub-agent review (≥3 roles) scheduled? | **PASS.** Spec already reviewed by 3 roles; this plan is being reviewed by 3 roles; implementation will run the two-loop multi-role review (Backend/Security/QA) before close. |
| **C7** | Downloads a build tool/runtime/CLI? | **PASS.** None. Uses already-installed Gradle/Node/JDK/Angular CLI per Principle X. |

**Gate outcome**: All gates pass except **C1, which is FLAGGED pending owner ratification** (governance decision, not a technical defect). Proceeding with planning under the F60/F61 precedent; implementation is blocked on the owner's confirmation.

## Project Structure

### Documentation (this feature)

```text
specs/029-join-interest-form/
├── plan.md              # This file
├── research.md          # Phase 0 — decisions R1–R7 + reuse inventory
├── data-model.md        # Phase 1 — interestRequests entity, lifecycle, indexes
├── quickstart.md        # Phase 1 — config, manual E2E, test list
├── contracts/
│   └── interest-api.md  # Phase 1 — public + internal endpoint contracts
└── tasks.md             # Phase 2 — /speckit.tasks (NOT created here)
```

### Source Code (repository root)

```text
backend/src/main/java/com/cadence/
├── api/
│   ├── PublicInterestController.java          # POST /api/public/interest (permitAll chain)
│   ├── InterestRequestController.java         # /api/internal/interest-requests/** (ADMIN)
│   └── InterestExceptionHandler.java          # @Order(HIGHEST_PRECEDENCE), no-oracle envelopes
├── domain/
│   └── InterestRequest.java                   # @Document; status enum; PII fields
├── repository/
│   └── InterestRequestRepository.java
├── service/
│   ├── InterestRequestService.java            # submit/coalesce/transition/invite/erase
│   └── InterestProperties.java                # @ConfigurationProperties cadence.interest.*
├── scheduler/
│   └── InterestRetentionScheduler.java        # @Scheduled purge + checkpoint (retentionPeriodDays<=0 → fallback)
├── service/
│   └── InterestRateLimiter.java               # dedicated per-source limiter (real client IP); NOT CandidateRateLimiter (R6)
└── config/migration/
    └── ChangeUnit023_InterestRequestIndexes.java   # order "023" (pure ASCII)
# Modified (additive): MongoPiiConfig (register 4 fields), RecruiterNotificationType (+INTEREST_REQUEST,
#   called as notify(ws, null, type) — the ATS_SYNC_FAILED precedent). SmtpEmailSender/OperationalEmailTemplates
#   NOT touched (in-app notification row, no email — R2). WorkspaceConfig unchanged.

frontend/src/app/
├── features/request-access/                   # PUBLIC lazy route /request-access
│   ├── request-access.component.ts            # form + privacy notice + confirmation; WCAG/axe/$localize
│   └── interest.service.ts                    # POST /api/public/interest
└── features/admin/interest-requests/          # INTERNAL admin queue (no §IX gate)
    ├── interest-requests.component.ts
    └── interest-requests.service.ts           # GET/list + review/dismiss/invite/erase
# Modified: app.routes.ts (+ public /request-access [seo: PRIVATE/noindex], + admin route); login + public-home
#   entry links; lighthouserc.json (+ /request-access url + matrix). SEO inventory/robots/sitemap/_headers/ci.yml
#   NOT touched — /request-access is noindex (R4), preserving the "exactly one indexable route" guard.
```

**Structure Decision**: Web-application layout (Constitution Reference Source Layout). New code lands in the standard `api`/`domain`/`repository`/`service`/`scheduler`/`config/migration` backend packages and `features/` frontend directories. No structural deviation.

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
|---|---|---|
| **C1 — feature outside §11 MVP scope** | Prospective users currently dead-end at the sign-in screen with no way to request access; this directly serves the onboarding funnel of the MVP. | Doing nothing leaves the dead-end. Deferring is the C1 fail action and remains the owner's call. Precedent: F60/F61 (SEO) were accepted outside §11 as supporting capabilities; this plan proceeds on that basis pending **explicit owner ratification**, and writes no implementation code until confirmed. |

No other complexity beyond the minimum: one collection, one changeset, reuse of all cross-cutting seams, `@Scheduled` (not a broker), no new dependency, no new pattern.

## Post-Design Constitution Re-Check

After Phase 1 design (data-model + contracts + research), all gates hold:

- **C2/C4/C7** unchanged — still one collection, `@Scheduled` only, no new dependency, no downloads.
- **C3** reinforced by the contract: public 202 byte-identical (body+status+headers) + structurally constant-time (same code path, side effects deferred — R8); internal endpoints Admin-only + scoped-404; value-free notification; PII encrypted at rest with keyed-HMAC lookup (no plaintext query oracle).
- **C5** the only new Windows-executed surface is CI grep additions (ASCII) and the Java changeset (ASCII) — byte-scanned at close.
- **C6** three-role review of this plan in progress; two-loop implementation review committed.
- **C1** remains the single FLAGGED gate — governance, surfaced to the owner; no code until ratified.

**Design is ready for `/speckit.tasks`** once the C1 owner decision is recorded.
