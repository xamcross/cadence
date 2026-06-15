# Implementation Plan: Interview Template & Rule Engine (F12)

**Branch**: `009-interview-rule-engine` | **Date**: 2026-06-15 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/009-interview-rule-engine/spec.md`

## Summary

Deliver the **interview stage template** + the **rule engine** (backlog F12) — the layer that turns "raw free time" into "slots we are allowed to offer this candidate". Two capabilities: (1) **template management** — a workspace-scoped, Recruiter/Admin-owned definition of an interview type (duration, required/optional participants, "any N of pool" panels, buffers, daily interviewer cap, blackouts, working-hours/time-zone basis inheriting F03 unless overridden), with create/list/edit/retire and full validation; (2) the **rule engine** — given a template + a target date range, it reads the panel's availability through the **existing, unchanged** `AvailabilityService` (F10/F11) and computes only the slots that satisfy every rule (duration, required-free + buffers, per-pool quorum on distinct positively-free members, per-interviewer daily cap, blackout, working hours, DST-correct), returning a content-free, provider-agnostic slot list annotated **per pool** with qualifying members.

F12 is **mostly new in-stack business logic, zero new infrastructure**. It reuses `AvailabilityService` (no contract change — adding nothing to it), the `MemberAvailability`/`BusyInterval`/`AvailabilityStatus` model, the F03 `WorkspaceConfig`/`WorkingHours`, the F02 RBAC method-security + endpoint-inventory test, the F03 controller pattern, and the F10/F11 stub harness for tests. The genuinely new code is: one `InterviewTemplate` domain + repo, an `InterviewTemplateService` (CRUD + validation), a `RuleEngine` service (the slot computation), DTOs + one controller, a Mongock `ChangeUnit008` (two indexes), three append-only audit event types, one new `managedCalendarEvents` count query, and a light Angular template-management feature + slot-preview (the §II demonstrable leg). It does **not** reserve slots, send email, finalise a concrete panel, or render the candidate slot-picker — those are F13/F14.

Load-bearing engineering decisions (full detail in [research.md](./research.md)):
1. **Engine reads `AvailabilityService.query` unchanged** (D1) — `MemberAvailability` already supplies busy intervals + a *distinguishable* status; `status==DATA` + empty list is the only "free" signal (the FR-014 fail-safe input). One panel-wide read per computation.
2. **Deterministic interval algorithm over `java.time`** (D2) — day-at-a-time, cadence walk on `ZonedDateTime`, half-open `Instant` intersection; no solver, no dependency.
3. **Cadence default 15 min, anchored to working-day start** (D3) — resolves the "unanchored cadence is ambiguous" review finding.
4. **DST via zone-resolved wall-clock + Instant comparison** (D4) — spring-forward gap → no slot; fall-back hour → once; cap per civil day on 23h/25h days.
5. **Daily cap = a NEW index + count query on `managedCalendarEvents`** (D5/D7) — `{workspaceId,memberId,startAt}` + `countBy…StatusInAndStartAtBetween` (status `CREATED` only); enforced for **required** participants at compute time, pool-member cap deferred to F13's atomic re-validation.
6. **"Any N of pool" = distinct positively-free members + per-pool annotation** (D6) — unknown pool member not counted; dual-role member rejected at validation.
7. **New collection `interviewTemplates` + ChangeUnit008 order "008"** (D7) — order off the highest *applied* changeset (`007`), NOT the branch number; one changeset creates BOTH new indexes (templates + the managedCalendarEvents cap index).
8. **Member-reference workspace-membership validation** (D8) — every required/optional/pool member id must belong to the workspace (closes the cross-workspace availability-leak vector); per-template member/pool/blackout caps bound the fan-out (DoS/probe, FR-024).
9. **RBAC: class-level `@PreAuthorize("hasAnyRole('ADMIN','RECRUITER')")` on CRUD AND compute** (D9) — the compute path reaches the privileged `AvailabilityService` only behind this gate; inventory-test-covered.
10. **Logging/audit discipline + 3 new append-only `AuthEventType`s** (D10) — template name/free-text never logged or audited; value-free validation messages; enums logged as `.name()` (the F01.1 Jackson-3 footgun); CI scan extended with a template-name sentinel.
11. **§II demonstrable leg** (D11) = template-management UI + a Recruiter slot-preview (engine dry-run), full browser→DB; booking is F13.
12. **Injected `Clock` + stable ordering** (D14) — determinism over a single snapshot; slots by start instant, member ids ascending.

## Technical Context

**Language/Version**: Java 21 (backend, toolchain pinned); TypeScript 5.4 / Angular 17.3 (frontend)
**Primary Dependencies**: Spring Boot 3.3.5 (web, data-mongodb, security w/ method security, actuator, aop); Mongock 5.4.4; logstash-logback-encoder 9.0. **No new backend or frontend runtime dependency** — the rule engine is pure `java.time` interval arithmetic; persistence via Spring Data Mongo; availability via the existing `AvailabilityService`; no SDK, broker, cache, or scheduling library. Test-only: `spring-security-test` (already present) for per-role post-processors; the F10/F11 JDK `HttpServer` stubs (`StubGoogleCalendar`/`StubGraphCalendar`) seed availability. **WireMock is NOT used** (F01.1 Jackson conflict).
**Storage**: MongoDB 7.x (Atlas prod, Testcontainers `mongo:7` tests). **One new collection** `interviewTemplates` (no PII/secret → un-encrypted by design, like `managedCalendarEvents`) and **one new index on the existing `managedCalendarEvents`** (`{workspaceId,memberId,startAt}`) — both created by Mongock `ChangeUnit008` (order "008"). Reuses `members` (membership validation), `workspaceConfig` (WH/zone, F03), `managedCalendarEvents` (cap count, F10), `sessions` (actor), `authAuditLog` (extended with four append-only event types).
**Testing**: JUnit 5 + Testcontainers (integration: template CRUD + soft-retire + audit; daily-cap with seeded `managedCalendarEvents`; inheritance/override; compute against stub-seeded availability), MockMvc (6-endpoint × 5-role contract + `/slots` response shape + retired-409 + cross-workspace 404 + `no-store` + TRACE PII scan with a template-name sentinel), Mockito/plain unit (the `RuleEngine` per-rule truth tables: duration, cadence anchor, buffers, pool quorum on distinct positively-free members, DST gap/overlap, fail-safe status mapping, determinism/ordering, the < 50 ms compute budget on a seeded snapshot), Jasmine (template-management component + preview render states), Playwright (E2E: Recruiter creates a template → previews slots against the stub).
**Target Platform**: Fly.io single Machine (backend JAR), Cloudflare Pages (Angular SPA), MongoDB Atlas.
**Project Type**: Web application (Angular frontend + Spring Boot backend).
**Performance Goals**: one `AvailabilityService.query` per computation (panel bounded-parallel, < 5 s for 5 members per F10 SC-001) + one indexed cap-count per required member per civil day; rule evaluation itself < 50 ms on a seeded snapshot (SC-007, deterministic, no network). No scheduled job; no hot-path scan (the cap query is index-covered).
**Constraints**: single instance + MongoDB only — no Redis/queue/cache/broker (§IV/C2; the panel read reuses the existing bounded executor); no new dependency (§III/C4); never log the template name, member email/name, or any calendar content incl. TRACE (FR-022, SC-010); DST-correct (FR-015, SC-003); fail-safe — an unknown required member yields no slot and an unknown pool member doesn't count (FR-014, SC-004); deterministic (FR-016); workspace-isolated incl. member references (FR-006, D8); zero tool downloads (§X); any new `.ps1` pure ASCII (§V — none expected).
**Scale/Scope**: MVP single workspace (tens–hundreds of members; panels ≤ ~8, per-template member cap 25). 3 user stories, 24 FRs, 10 SCs.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Gate | Question | Result |
|---|---|---|
| **C1** | Within MVP scope (spec §11)? | ✅ PASS — the interview template + rule engine is the core of §11 "Flow A1 single-stage scheduling"; F12 is its rule layer (slot computation), an explicit Tier-1 backlog item. No deferred capability pulled in (multi-stage Flow A2 excluded; load-balancing analytics FR-8 excluded; booking/email/UI = F13/F14/F22). |
| **C2** | New service, queue, or replica? | ✅ PASS — **no** broker/cache/replica/object-store; **no** `@Scheduled` task (computation is request-scoped); the panel read reuses the **existing** bounded in-process `calendarFanoutExecutor`. One new collection + one added index, both via Mongock — no new infra service. |
| **C3** | Exposes candidate PII to unauthorized roles? | ✅ PASS — templates hold only internal member-id references (no candidate/participant PII); the compute path is gated to Recruiter/Admin and returns only times + ids + a coarse reason (no event content, no email/name); `AvailabilityService` stays a privileged internal primitive reached only behind the role gate (D9); the template name (a possible PII vector) is never logged or audited (D10). |
| **C4** | Dependency outside the fixed stack? | ✅ PASS — **zero** new runtime dependencies; pure `java.time` + Spring Data Mongo + Angular standalone. No SDK. |
| **C5** | New/modified Windows scripts contain non-ASCII? | ✅ PLANNED — no new `.ps1`; the `ci.yml` change is YAML (LF). Any script change byte-scanned before done. |
| **C6** | Multi-role sub-agent review (≥3) scheduled? | ✅ DONE+SCHEDULED — spec reviewed by 4 roles; this plan reviewed by ≥3 roles (user-requested "review with appropriate sub-agents") before tasks; final implementation review at task close. |
| **C7** | Downloads any build tool/runtime/CLI? | ✅ PASS — cached Gradle 9.4.0, installed JDK 21, existing Angular CLI; no downloads (§X). |

**Initial gate: PASS.** No §VIII scope-expansion item (F12 touches no OAuth scope — it consumes F10/F11's existing availability). No Complexity Tracking entries required (see below).

### §VIII privacy posture (no scope expansion, but load-bearing controls)

F12 does not request any OAuth scope. Its §VIII obligations are: (a) the template `name`/free-text is recruiter input that can contain candidate PII → never logged or audited, returned only on the management read model (D10, FR-022/023, SC-010); (b) the compute path reads member calendar-derived availability → Recruiter/Admin-gated, content-free output, `no-store` (D9); (c) member references are validated to the workspace so the engine can't be tricked into reading a foreign member's availability (D8); (d) no candidate PII is persisted, so member-erasure (F04) needs no F12 cleanup hook (internal ids only).

### Post-Design Re-Check (after Phase 1 + §VI plan review) — COMPLETED

Multi-role plan review completed by three roles (Backend/DevOps, Security/GDPR, QA — full log + dispositions in `checklists/requirements.md`). Reviewers verified the plan's claims against the **actual F03/F10/F11 source**. **Result: PASS, unchanged gate status** — all accepted findings were folded into `research.md`/`data-model.md`/`contracts/`/`spec.md`/this plan; none added a dependency, service, or topology, and none moved a gate to FAIL.

Key gate confirmations (verified against code):
- **C2 holds** — `AvailabilityService` already owns the bounded executor; F12 adds no scheduler, broker, or replica; the cap query is index-covered (no hot-path scan).
- **C4 / C7 unchanged** — zero new runtime deps; zero downloads.
- **C3 holds** — compute is Recruiter/Admin-gated and content-free; the template name never leaks to logs/audit; member references workspace-validated.
- **Mongock order** — `ChangeUnit008` order "008" derived off the highest applied changeset `007` (NOT the branch number `009`); one changeset creates both indexes.

## Project Structure

### Documentation (this feature)

```text
specs/009-interview-rule-engine/
├── plan.md              # This file
├── research.md          # Phase 0 — decisions D1..D14
├── data-model.md        # Phase 1 — InterviewTemplate + the managedCalendarEvents index/query + transient engine I/O
├── quickstart.md        # Phase 1 — local run + manual + acceptance→test map
├── contracts/
│   └── interview-template-api.md  # Phase 1 — template CRUD + slot-compute REST + RBAC matrix + internal engine contract
├── checklists/
│   └── requirements.md  # Spec quality + spec/plan review logs
└── tasks.md             # Phase 2 (/speckit.tasks — NOT created here)
```

### Source Code (repository root)

```text
backend/src/main/java/com/cadence/
├── domain/
│   ├── InterviewTemplate.java                # NEW @Document("interviewTemplates") — ids+instants only, no PII; toString omits name
│   ├── TemplateStatus.java                   # NEW enum ACTIVE/RETIRED
│   ├── PoolRule.java                         # NEW embedded { List<String> memberIds; int n; }
│   ├── BlackoutPeriod.java                   # NEW embedded { Instant start; Instant end; }
│   ├── ComputedSlot.java                     # NEW transient — start/end Instant + zoneId + required + per-pool qualifying map
│   ├── SlotComputationResult.java            # NEW transient — slots + windowClamped + unschedulable[]
│   ├── MemberUnschedulable.java              # NEW transient — { memberId, UnschedulableReason }
│   ├── UnschedulableReason.java              # NEW enum mirroring non-DATA AvailabilityStatus
│   └── AuthEventType.java                    # MODIFIED — append INTERVIEW_TEMPLATE_CREATED/UPDATED/RETIRED/COMPUTE_REFUSED (never reorder)
├── repository/
│   ├── InterviewTemplateRepository.java      # NEW — findByWorkspaceIdAndStatus, findByWorkspaceIdAndId, etc.
│   └── ManagedCalendarEventRepository.java   # MODIFIED — add countByWorkspaceIdAndMemberIdAndStatusNotInAndStartAtGreaterThanEqualAndStartAtLessThan (exclusion + half-open, D5)
├── service/
│   ├── InterviewTemplateService.java         # NEW — CRUD + validation (FR-002/024, value-free msgs, membership+dual-role checks, D8); audit (D10)
│   ├── RuleEngine.java                       # NEW — the slot computation (D2/D4/D6); reads AvailabilityService + cap count; injected Clock (D14)
│   └── AvailabilityService.java              # UNCHANGED (D1) — the engine consumes its existing query(...)
├── api/
│   ├── InterviewTemplateController.java      # NEW — /api/internal/interview-templates; class-level @PreAuthorize hasAnyRole(ADMIN,RECRUITER) (D9)
│   ├── InterviewTemplateDtos.java            # NEW — Create/Update request, TemplateResponse, SlotPreviewRequest (value-free error mapping)
│   └── InterviewTemplateExceptions.java      # NEW — InvalidTemplateException(400), TemplateRetiredException(409); scoped-not-found reuse (404)
├── config/
│   ├── InterviewTemplateProperties.java      # NEW — interview.template.* (default cadence 15; member/pool/blackout caps; compute window cap reuse)
│   └── migration/
│       └── ChangeUnit008_InterviewTemplateIndexes.java  # NEW order "008" — interviewTemplates {workspaceId,status} + managedCalendarEvents {workspaceId,memberId,startAt}
backend/src/main/resources/application.yml     # MODIFIED — add interview.template.* (cadence default, per-template caps)
.github/workflows/ci.yml                       # MODIFIED — extend the PII/log scan with an interviewTemplates template-name sentinel

backend/src/test/java/com/cadence/interview/    # NEW package (sibling of calendar/)
├── InterviewTemplateValidationTest.java       # SC-008: every invalid field → value-free 400, 0 persisted
├── InterviewTemplateCrudIntegrationTest.java  # US1: create→read-back, edit, soft-retire, audit ids-only (Testcontainers)
├── InterviewTemplateContractTest.java         # SC-009/006: 6×5 RBAC matrix; /slots full shape (qualifyingByPool + unschedulable + windowClamped); retired→409 + COMPUTE_REFUSED audit row;
│                                              #   cross-workspace 404 + foreign-member create→400; no-store; RbacEndpointInventoryTest green
├── RuleEngineTest.java                        # SC-001: per-rule truth tables (duration/cadence/buffer/pool/working-hours), 0 violating slots;
│                                              #   + duration>working-window→0; blackout∩WH precedence (blackout wins); pool-of-1 "any 1"==required;
│                                              #   optional member busy/unknown NEVER gates a slot (silent-bug guard); windowClamped==true on a >max-window range (FR-017)
├── RuleEngineDailyCapTest.java                # SC-002: cap binds (pre-existing + within-computation, both asserted); StatusNotIn excludes DELETED/CLEANUP_INCOMPLETE;
│                                              #   zone-relative civil-day boundary (event at 23:30 local = next UTC day counts on the local civil day) (integration)
├── RuleEngineDstTest.java                     # SC-003: pinned zone America/New_York 2026-03-08/2026-11-01 + MutableClock; spring-forward gap (start AND buffer-after) → 0;
│                                              #   fall-back → once; cap per 23h/25h civil day
├── RuleEngineFailSafeTest.java                # SC-004: required unknown → 0 + each status maps to its OWN distinguishable reason; a BUSY required member is NOT in unschedulable;
│                                              #   pool unknown excluded from quorum (never free); member-left-workspace → no availability → fail-safe (required→0 / pool quorum on rest)
├── RuleEnginePoolTest.java                    # SC-005: multi-pool per-pool qualifying annotation exact
├── RuleEngineInheritanceTest.java             # US3: inherit vs override WH/zone; later workspace change reflected (integration)
├── RuleEngineRangeTest.java                   # range wholly in the past (via MutableClock) → []; range end ≤ start → []; both no-error (spec Edge Cases)
├── RuleEnginePerfTest.java                    # SC-007: CI gate = Mockito.verify(availabilityService, times(1)).query(...) + cap-read multiplicity; latency is a JIT-warmed
│                                              #   median logged informationally with a generous hard cap (NOT a bare <50ms wall-clock gate — QA de-flake)
└── InterviewTemplateLogPiiScanTest.java       # SC-010: TRACE scan — template-name sentinel fed create→compute (non-vacuous) + member email absent across CRUD + compute

frontend/src/app/features/interview-templates/  # NEW standalone feature
├── interview-templates.component.ts            # NEW — list/create/edit/retire (Recruiter/Admin), Angular Material, $localize
├── interview-templates.component.spec.ts       # NEW — Jasmine: CRUD render + form validation + preview render states
├── slot-preview.component.ts                   # NEW — date-range → POST /slots → render slots + unschedulable panel
└── interview-templates.service.ts              # NEW — typed HTTP client for the F12 endpoints
frontend/e2e/interview-templates.spec.ts        # NEW — Playwright: create template → preview slots against the stub
frontend/src/app/app.routes.ts                  # MODIFIED — add the Recruiter/Admin-guarded route
```

**Test isolation note (shared singleton container)**: every new test class MUST clean `interviewTemplates`, `managedCalendarEvents`, `calendarConnections`, and `authAuditLog` in `@BeforeEach` with `mongoTemplate.remove(new Query(), Type.class)` — never `dropCollection` (drops the Mongock-created indexes; CLAUDE.md F00.1 lesson). Availability is seeded via `StubGoogleCalendar`/`StubGraphCalendar` (F10/F11 harness, connections seeded via the F01.1 production path); cap fixtures by inserting `managedCalendarEvents` rows directly. Any "future-only" assertion uses the injected `MutableClock` (reproducible). The first multi-class Testcontainers run after a recompile may throw the one-time `GenericContainer` class-init error — re-run.

**Structure Decision**: Web-application layout. F12 adds one bounded backend slice (`domain`/`repository`/`service`/`api`/`config` + one Mongock changeset) consuming the unchanged `AvailabilityService`, plus a light Angular feature for the §II leg. `AvailabilityService` and the F10/F11 calendar code are untouched except the one additive repository count method + index. No new dependency, no new infra, no new top-level structure.

## Complexity Tracking

*No entries.* No architectural pattern beyond the minimum is introduced: the rule engine is a single `@Service` of pure interval arithmetic; persistence is a plain Spring Data repository; the one added index + count query is the minimal data path for the daily cap. No solver, strategy framework, event bus, or cache. The constitution gates all pass without justification.
