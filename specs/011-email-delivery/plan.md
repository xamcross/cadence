# Implementation Plan: Email Delivery Channel (F22)

**Branch**: `011-email-delivery` | **Date**: 2026-06-16 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/011-email-delivery/spec.md`

## Summary

Replace the no-op `EmailSender` with a real, reliable, consent-safe transactional-email channel and a reusable idempotent scheduled-dispatch mechanism.

Two layers:

1. **Transport** — a real `EmailSender` backed by Spring Mail (`JavaMailSender`/SMTP, constitution §III), wrapped behind a thin `MailTransport` SPI so the provider is swappable by bean replacement (FR-003) and tests use a recording transport (the F10/F11 JDK-stub precedent — zero new test dependency). Making the transport real immediately turns the **existing** F01 member-invitation / password-reset emails and the F00.2 dead-letter system alert into genuine browser→backend→email flows (the §II demonstrable leg).
2. **Candidate dispatch pipeline** — a new `EmailDispatchService` + `emailDispatches` outbox collection that runs every candidate-addressed send through: the F04 `ContactPermissionGate` (consent/erasure, fail-closed, at dispatch time) → an idempotent unique-key claim (no duplicates, restart-safe) → F21 render → transport → status/audit, with bounded retry, dead-letter + recruiter notification on terminal failure, an authenticated inbound provider **bounce/delivery webhook** (hard-bounce → candidate undeliverable flag + recruiter notification; soft-bounce → no flag), and a `@Scheduled` due-row worker on the F00.2 `SchedulerCheckpoint` pattern that later features (F13/F23/F31/F32) enqueue future-dated sends through.

No queue broker, no new infrastructure service, no provider SDK — Spring Mail starter only.

## Technical Context

**Language/Version**: Java 21 (backend); TypeScript 5.4 / Angular 17.3 (frontend)
**Primary Dependencies**: Spring Boot 3.3.5 (web, data-mongodb, security w/ method security, actuator, aop, **scheduling**) + **`spring-boot-starter-mail`** (NEW — provides `JavaMailSender`; constitution §III mandates Spring Mail for email delivery). Mongock 5.4.4; logstash-logback-encoder 9.0. Reuses F04 `ContactPermissionGate`, F21 `EmailTemplateService`/`MergeRenderer`, F00.2 `SchedulerCheckpointService`/`DeadLetterService`, F03 `WorkspaceConfig`. No provider SDK. No new frontend dependency.
**Storage**: MongoDB 7.x (Atlas prod, Testcontainers `mongo:7` tests). **One new collection** `emailDispatches` (outbox — candidate internal ID + message type + idempotency key + status + opaque provider ref; **no recipient address, no subject, no body, no merge PII** → un-encrypted by design). **Modifies** `candidates` (F04): adds `undeliverable` flag + bounce metadata (purged on erasure). Reuses `schedulerCheckpoints`, `deadLetters`, `authAuditLog`, `workspaceConfig`, `emailTemplates`, `members`, `sessions`.
**Testing**: JUnit 5 + Mockito (unit: dispatch state machine, gate integration, retry classification, idempotency-key derivation); Testcontainers MongoDB (integration: outbox CRUD/claim CAS, concurrent dedup, scheduled replay, bounce webhook, audit, PII-scan); MockMvc (contract: recruiter send endpoint + webhook). Recording `MailTransport` test double (zero new test dep). `spring-security-test` (already present) for per-role post-processors.
**Target Platform**: Fly.io single Machine (backend), Cloudflare Pages (frontend) — single-instance topology unchanged.
**Project Type**: Web application (Spring Boot backend + Angular SPA).
**Performance Goals**: A triggered transactional email delivered within 60 s of trigger (SC-001, measured at the test sink). Single-workspace transactional/reminder volume fits one instance with the `@Scheduled` + checkpoint pattern, no broker.
**Constraints**: Zero candidate PII in logs at any level (FR-023/SC-006). Exactly-once per logical message under duplicate/concurrent/restart (FR-009/FR-010/SC-003) via the unique outbox key; the rare provider-accepted-but-uncommitted crash window degrades to no-resend (presumed-sent) and is recorded (honest bound, documented in research.md). No queue broker (constitution §IV).
**Scale/Scope**: One new backend service + outbox collection + scheduled worker + webhook controller + recruiter send endpoint; the real SMTP transport; existing member-email flows go live. One Mongock changeset (`ChangeUnit010`, order **"010"** off the highest applied `009`). Minimal frontend (member path already wired; a thin recruiter "send to candidate" action surfaced on the existing email-templates preview).

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Gate | Question | Result |
|---|---|---|
| **C1** | Within MVP scope (spec §11)? | ✅ §11 "Email channel only" — F22 is the email delivery channel. |
| **C2** | New service, queue, or replica? | ✅ No. Spring `@Scheduled` + Mongo `emailDispatches` outbox + `SchedulerCheckpoint`; the bounce webhook is an inbound controller, not a service. No broker (constitution §IV async-work rule honoured). |
| **C3** | Exposes candidate PII to unauthorized roles? | ✅ No. Candidate sends are consent-gated; the recruiter send endpoint is `@PreAuthorize` ADMIN/RECRUITER; the outbox stores no PII; the public bounce webhook only flips flags and exposes nothing. |
| **C4** | Dependency outside the fixed stack? | ✅ `spring-boot-starter-mail` is a Spring Boot starter and is the constitution §III-named email-delivery technology (Spring Mail). Recorded in research.md with the one-line justification. No provider SDK; transport wrapped behind `EmailSender`/`MailTransport`. |
| **C5** | New/modified Windows scripts with non-ASCII? | ✅ No new `.ps1`/`.cmd`/`.bat`. CI PII-scan extended (ASCII). |
| **C6** | Multi-role sub-agent review (≥3) scheduled? | ✅ Spec already reviewed (3 roles); plan reviewed this command; implementation review at task close. |
| **C7** | Downloads a build tool/runtime/CLI? | ✅ No. `spring-boot-starter-mail` is a library dependency fetched by Gradle, not a tool/runtime download (Principle X concerns tool binaries). |

**Initial gate: PASS.** No Complexity Tracking entries required.

**Post-Phase-1 re-check: PASS** — the design adds one collection, one changeset, one starter dependency, and reuses every existing seam (gate, render, checkpoint, dead-letter, audit). No topology, no broker, no SDK. See Phase 1 artifacts.

## Project Structure

### Documentation (this feature)

```text
specs/011-email-delivery/
├── plan.md              # This file
├── research.md          # Phase 0 — decisions (transport, idempotency/claim, bounce, webhook auth, §II)
├── data-model.md        # Phase 1 — EmailDispatch entity, Candidate additions, indexes, state machine
├── quickstart.md        # Phase 1 — run/test/demo walkthrough
├── contracts/
│   └── email-delivery-api.md   # recruiter send endpoint, bounce webhook, EmailDispatchService SPI
├── checklists/
│   └── requirements.md  # spec quality + multi-role spec-review notes
└── tasks.md             # Phase 2 (/speckit.tasks — NOT created here)
```

### Source Code (repository root)

```text
backend/src/main/java/com/cadence/
├── api/
│   ├── CandidateEmailController.java        # NEW POST /api/internal/candidates/{id}/emails (recruiter send)
│   ├── EmailWebhookController.java          # NEW POST /api/webhooks/email/events (provider bounce/delivery)
│   ├── EmailDeliveryDtos.java               # NEW request/response records
│   └── EmailDeliveryExceptionHandler.java   # NEW 400/403/404/409 envelopes
├── config/
│   ├── MailConfig.java                      # NEW JavaMailSender wiring from workspace F03 config / app props
│   └── EmailDeliveryProperties.java         # NEW retry cap/backoff, webhook secret, ops-alert address
│   └── migration/
│       └── ChangeUnit010_EmailDispatchIndexes.java  # NEW order "010"
├── domain/
│   ├── EmailDispatch.java                   # NEW outbox document (NO @Version — all transitions are findAndModify CAS)
│   ├── DispatchStatus.java                  # NEW PENDING/SENDING/SENT/SENT_UNCONFIRMED/FAILED/BOUNCED/REFUSED
│   ├── DispatchOutcomeReason.java           # NEW value-free reason enum
│   └── AuthEventType.java                   # MODIFIED + EMAIL_DISPATCH_* append-only events
├── integration/
│   ├── EmailSender.java                     # MODIFIED widen w/ send(OutboundEmail) (kept legacy methods)
│   ├── NoOpEmailSender.java                 # MODIFIED remove @Primary (SmtpEmailSender is the sole primary)
│   ├── SmtpEmailSender.java                 # NEW @Primary real transport (delegates to MailTransport)
│   ├── MailTransport.java                   # NEW thin SPI (the actual SMTP send) — swappable/testable
│   ├── SmtpMailTransport.java               # NEW JavaMailSender-backed MailTransport
│   └── OutboundEmail.java                   # NEW {workspaceId, toAddress, subject, htmlBody, messageId}
├── repository/
│   └── EmailDispatchRepository.java         # NEW (@Query due-row finder w/ batch limit — F12 lesson)
├── service/
│   ├── EmailDispatchService.java            # NEW candidate dispatch orchestrator (gate→claim→render→send→record; all findAndModify CAS, no @Version)
│   ├── EmailBounceService.java              # NEW idempotent webhook intake → candidate flag + notify
│   ├── EmailDispatchMetrics.java            # NEW Micrometer counters/gauges on actuator (FR-024/D11)
│   ├── RecruiterNotificationService.java    # NEW in-app notification seam (FR-008/FR-012/FR-017)
│   ├── EmailTemplateService.java            # MODIFIED + public renderForSend(...) (resolveForRender is private)
│   ├── CandidateErasureService.java         # MODIFIED erasure wipe Update resets undeliverable* fields (FR-017)
│   └── ContactPermissionGate.java           # MODIFIED + UNDELIVERABLE reason (last deny before allow())
├── security/
│   └── SecurityConfig.java                  # MODIFIED + @Order chain for /api/webhooks/email/** (permitAll, CSRF-exempt, STATELESS)
└── scheduler/
    └── EmailDispatchScheduler.java          # NEW @Scheduled sweep on SchedulerCheckpoint (registerReplayAction)

backend/src/test/java/com/cadence/emaildelivery/   # NEW test package (mirrors com.cadence.emailtemplate)
frontend/src/app/features/email-templates/         # MODIFIED add "Send to candidate" action to preview
```

**Structure Decision**: Standard Cadence layout (constitution Reference Source Layout). The dispatch orchestrator lives in `service/`, the transport in `integration/` (behind `EmailSender`, per the Dependency Policy interface rule), the scheduled worker in `scheduler/`, controllers in `api/`. No new top-level module.

## Multi-role plan review (2026-06-16) — verdict: APPROVE-WITH-NITS

Reviewers: Backend/architecture, Security/GDPR, DevOps/QA. No blockers. All findings folded into the artifacts:

- **Backend**: dropped `@Version` from `EmailDispatch` (engages only via `save()`, ignored by `findAndModify`) — all transitions are CAS, unique index is the durable guard (D5/data-model §1,§3); due-row finder is an explicit `@Query` with a batch limit (F12 lesson); `EmailTemplateService.renderForSend(...)` added because `resolveForRender` is private (D9/contract C); `@Primary` removed from `NoOpEmailSender`; reaper threshold invariant stated.
- **Security/GDPR**: webhook security chain is **required** (`/api/webhooks/email/**` not covered by the existing `@Order(2)` matcher → would 401) — CSRF-exempt/STATELESS + inventory allow-list (D4/contract B); `CandidateErasureService.wipe` Update must reset the `undeliverable*` fields (FR-017, explicit task — D7/data-model §2); `nonPiiContext`/`sampleValues` kept transient, `renderContextRef` shape-guarded (FR-013); `UNDELIVERABLE` is the last gate deny; webhook secret is an app-level env Fly secret.
- **DevOps/QA**: Fly secret names corrected to `UPPER_SNAKE_CASE` + app-level `CADENCE_EMAIL_SMTP_PASSWORD` required for member mail; SMTP-wire labelled manual-verify-only (RecordingMailTransport in CI); MailHog labelled dev-only (not prod topology); FR-024 metrics/observability added (D11); dead-letter-over-dead-SMTP limitation documented (D12); C2/C4 gate judgments confirmed correct.

DoD note: F22 adds **no new candidate-facing page** (the candidate sees the email, not a Cadence screen), so the WCAG/axe and Lighthouse DoD items are **N/A for F22** (documented to avoid an ambiguous CI gate, per the F50/F51 precedent).

## Complexity Tracking

No constitution violations — table intentionally empty.
