# Quickstart — Interview Template & Rule Engine (F12)

**Branch**: `009-interview-rule-engine` | **Date**: 2026-06-15

How to run, manually exercise, and verify F12 locally. No live calendar credentials needed — availability is served by the F10/F11 JDK `HttpServer` stub in tests; for manual dev a real Google/Microsoft connection (F01.1) supplies availability.

## Prerequisites (already on this machine — never download, §X)

- JDK 21 (`JAVA_HOME=C:/jdk-24.0.1`), cached Gradle 9.4.0, Node/Angular CLI.
- Local MongoDB for manual dev: `docker run -p 27017:27017 mongo:7`.
- Run flags (CLAUDE.md): `-Dapi.version=1.41`, `DOCKER_HOST=npipe:////./pipe/docker_engine`, `-Dorg.gradle.java.installations.auto-download=false`.

## Run locally

```powershell
# Backend (Mongock applies ChangeUnit008 on startup — creates the two new indexes)
./gradlew bootRun
# Frontend
cd frontend; ng serve
```

## Manual end-to-end (the §II demonstrable leg, D11)

1. Sign in as an **Admin** or **Recruiter** (F01).
2. Open **Interview Templates** → **New template**: name, duration 60, cadence 15, buffers 15/15, daily cap 3, pick a required interviewer, add an "any 2 of {A,B,C}" pool, optionally a blackout. Save → it persists and appears in the list.
3. Edit it (change duration), then **Retire** it → it leaves the active list but is still resolvable.
4. On an active template, **Preview slots** for a date range → the page renders the computed compliant slots; each slot shows the qualifying pool members. A required member who hasn't connected a calendar appears in the "unschedulable" panel with a distinguishable reason — never silently dropped.
5. Sign in as **Interviewer** / **Read-only** → the Interview Templates area is not available; a direct API call returns 403.

## Verify (tests)

```powershell
./gradlew test                 # backend: unit + integration + contract
cd frontend; ng test --watch=false   # frontend Jasmine
```

### What the tests assert (acceptance → test map)

| Spec | Test | Asserts |
|---|---|---|
| US1 / SC-008 | `InterviewTemplateValidationTest` (unit) | every invalid field (duration ≤ 0, cap < 1, pool n=0/>size, blackout end ≤ start, negative buffer, no required-and-pool, foreign-workspace member, dual-role member, over-cap counts, bad WH override) → value-free 400, 0 persisted. |
| US1 / SC-009 | `InterviewTemplateContractTest` (MockMvc) | 6-endpoint × 5-role matrix (Recruiter/Admin 200, others 403); cross-workspace 404; `RbacEndpointInventoryTest` stays green. |
| US1 | `InterviewTemplateCrudIntegrationTest` (Testcontainers) | create→read-back all fields; edit persists; retire soft (status RETIRED, not deleted); audit `INTERVIEW_TEMPLATE_*` ids-only. |
| US2 / SC-001 | `RuleEngineTest` (unit, seeded `MemberAvailability`) | each rule independently: duration, cadence anchor, required-free + buffers, blackout, working-hours fit, pool quorum on distinct positively-free members. 0 violating slots. |
| US2 / SC-002 | `RuleEngineDailyCapTest` (integration) | cap=2 with two seeded same-day `managedCalendarEvents` (status CREATED) → 0 more; within-computation never offers a 3rd; DELETED/CLEANUP_INCOMPLETE rows don't consume cap. |
| US2 / SC-003 | `RuleEngineDstTest` (unit) | spring-forward gap hour → 0 slots; fall-back repeated hour → offered once; cap counted per civil day on a 23h/25h day; correct wall-clock instants. |
| US2 / SC-004 | `RuleEngineFailSafeTest` (unit) | required member status `NOT_CONNECTED`/`NEEDS_RECONNECTION`/`TEMPORARILY_UNAVAILABLE` → 0 slots + reason in `unschedulable`; unknown pool member excluded from quorum (never counted free). |
| US2 / SC-005 | `RuleEnginePoolTest` (unit) | two pools, each quorum binds → per-pool `qualifyingByPool` annotation lists exactly the qualifying members. |
| US2 / SC-006 | `InterviewTemplateContractTest` | `/slots` response shape (slots + `qualifyingByPool` + `unschedulable` + `windowClamped`); retired template → 409 `template_retired`; empty range → `[]` not error; `Cache-Control: no-store`. |
| US2 / SC-007 | `RuleEnginePerfTest` (unit) | rule evaluation over a fixed 5-member / 2-pool / 14-day seeded snapshot < 50 ms (no network); exactly one availability read issued. |
| US3 | `RuleEngineInheritanceTest` (integration) | no override → workspace WH/zone used; override → template WH/zone used; later workspace WH change reflected (by-reference). |
| SC-010 | `InterviewTemplateLogPiiScanTest` | TRACE scoped to `com.cadence`; drive CRUD + compute; assert a seeded template-name sentinel + member email never appear in logs. |
| US1 (frontend) | `interview-templates.component.spec.ts` (Jasmine) | list/create/edit/retire render + form validation; preview render states (slots / empty / unschedulable). |
| §II E2E | `interview-templates.spec.ts` (Playwright) | Recruiter creates a template → previews slots against the stub → sees computed slots. |

### Test isolation (shared singleton container — CLAUDE.md F00.1)

Every new test class cleans `interviewTemplates`, `managedCalendarEvents`, `calendarConnections`, `authAuditLog` in `@BeforeEach` with `mongoTemplate.remove(new Query(), Type.class)` — **never** `dropCollection` (drops Mongock-created indexes). Seed availability via `StubGoogleCalendar`/`StubGraphCalendar` (the F10/F11 harness); seed cap fixtures by inserting `managedCalendarEvents` rows directly. Use an injected `MutableClock` for any "future-only" assertion (reproducible).

## Deploy (after merge)

Backend + new Mongock changeset → `scripts\db-migrate.ps1` then `scripts\deploy-backend.ps1`; frontend → `scripts\deploy-frontend.ps1`; or `scripts\deploy-all.ps1`. ChangeUnit008 applies on backend startup (creates both new indexes). No new secret.
