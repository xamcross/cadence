# Implementation Plan: Role-Based Access Control (RBAC)

**Branch**: `003-rbac` | **Date**: 2026-06-14 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/003-rbac/spec.md`

## Summary

Turn the five workspace roles F01 already attaches to the session into **enforced permissions on every internal endpoint**. Three deliverables ship as one demonstrable increment: (1) **role administration** — an Admin lists members and changes a member's role, guarded against last-Admin lockout and self-elevation, audited; (2) **deny-by-default enforcement** — every `/api/internal/**` handler declares a minimum role via Spring Security method security (`@PreAuthorize`), a JSON `AccessDeniedHandler` renders the 403 envelope, and a build-time **endpoint-inventory test** fails CI if any internal handler ships without a declared role; (3) **server-side data scoping** — a reusable `AssignmentService` + an `assignments` collection scope Hiring-Manager/Interviewer reads and writes to their own assignments, demonstrated end-to-end against a real `assignments` resource today and reused by later features (F13/F32/F51) for requisitions/interviews.

The single most important backend change is a **one-line fix in `SessionService.validate()`**: it currently builds the request `Principal` from the session's role *snapshot* (`s.getRole()`) while the live `Member` is already loaded for the active-status check — so a role change would not take effect until re-login. F02 derives the principal's role from the **persisted member** (FR-002/FR-007), making role changes effective on the next request with zero added queries. No new infrastructure, no new runtime dependency — single Spring Boot instance + MongoDB only.

## Technical Context

**Language/Version**: Java 21 (backend, toolchain pinned); TypeScript 5.4 / Angular 17.3 (frontend)
**Primary Dependencies**: Spring Boot 3.3.5 (web, data-mongodb, **security with method security already enabled**, actuator, aop); Mongock 5.4.4; logstash-logback-encoder 9.0. **No new backend or frontend runtime dependency** (RBAC uses `@EnableMethodSecurity`, already present in `SecurityConfig`). Test-only: `spring-security-test` (already used by F01) for `@WithMockUser`/authority post-processors.
**Storage**: MongoDB 7.x (Atlas in prod, Testcontainers `mongo:7` in tests). Reuses `members` and `authAuditLog`; adds one collection **`assignments`**.
**Testing**: JUnit 5 + Testcontainers (integration), MockMvc (API contract + the inventory reflection test), Mockito (unit); Jasmine (frontend unit — route guards per role); Playwright (E2E — admin role change + not-authorized redirect)
**Target Platform**: Fly.io single Machine (backend JAR), Cloudflare Pages (Angular SPA), MongoDB Atlas
**Project Type**: Web application (Angular frontend + Spring Boot backend)
**Performance Goals**: No added per-request cost — authorization reuses the `Member` already loaded by `SessionCookieAuthFilter`→`SessionService.validate()`; method-security check is in-memory. Role change effective within one request (SC-003).
**Constraints**: Single instance + MongoDB only, no Redis/queue/cache and **no in-process role cache** (constitution §IV / C2); persisted role is authoritative, never the session/JWT claim (FR-002/FR-007); zero PII/candidate-id/protected-content in logs (§VIII, FR-029); deny-by-default fail-closed (FR-011); zero tool downloads (§X); any new `.ps1` pure ASCII (§V)
**Scale/Scope**: MVP workspace scale (tens–hundreds of members/workspace); 5 user stories, 32 FRs, 15 SCs

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Gate | Question | Result |
|---|---|---|
| **C1** | Within MVP scope (spec §11)? | ✅ PASS — F02 is Tier-0 Foundation MVP; the constitution's §VIII mandates this exact five-role RBAC. |
| **C2** | New service, queue, or replica? | ✅ PASS — authorization reads the persisted member role + a new `assignments` collection on the existing MongoDB; no Redis/broker/cache tier and explicitly no in-process role/permission cache. |
| **C3** | Exposes candidate PII to unauthorized roles? | ✅ STRENGTHENED — this feature *is* the enforcement that confines candidate PII; deny-by-default + server-side Hiring-Manager scoping (FR-030). One documented exception: Read-only is an intentional unscoped workspace-wide viewer (spec Assumption, stakeholder-flagged). |
| **C4** | Dependency outside the fixed stack? | ✅ PASS — **zero new dependencies**; uses Spring Security method security already on the classpath. |
| **C5** | New/modified Windows scripts contain non-ASCII? | ✅ PLANNED — no new `.ps1` expected; any change byte-scanned before done. |
| **C6** | Multi-role sub-agent review (≥3) scheduled? | ✅ DONE+SCHEDULED — spec already reviewed by 4 roles; this plan is reviewed by ≥3 roles (user-requested) before tasks; a final review runs at task close. |
| **C7** | Downloads any build tool/runtime/CLI? | ✅ PASS — cached Gradle 9.4.0, installed JDK 21, existing Angular CLI; no downloads. |

**Initial gate: PASS** (no new dependency, no topology change, no stack change).

### Post-Design Re-Check (after Phase 1 + §VI plan review)

Re-evaluated after the multi-role plan review (4 roles: Security/GDPR, Backend/DevOps, QA, Front-End — all APPROVE-WITH-CHANGES). **Result: PASS, unchanged gate status.**
- **C2 still holds**: the last-Admin guard is the broker-free **flip → recount → conditional-rollback** sequence (research D4 — the earlier "single conditional update" was proven not implementable and is corrected; no lock service, no transaction, no counter doc); scoping is a Mongo query filter; the refusal-audit throttle is an in-process bounded counter (mirrors F01's `LoginAttemptService`); no cache/broker/replica added.
- **C3 strengthened**: HM/Interviewer reads and writes are server-side assignment-filtered (FR-024/FR-027/FR-032); the only unscoped candidate-PII read is the intentionally-flagged Read-only viewer — and F02 ships **no** Read-only candidate-PII endpoint, so that exception is a forward contract F51 must re-confirm with the stakeholder (review SEC-8).
- **C4 unchanged**: still zero new runtime dependencies.
- **C7 unchanged**: zero downloads.
- Two review **BLOCKERs** are resolved in the artifacts: (1) the inventory test would have red-failed CI on F01's un-annotated `/api/internal/auth/me` + `/logout` → `AuthController` now gets `@PreAuthorize("isAuthenticated()")` and the test accepts it (research D2); (2) the last-Admin guard mechanism was not atomically implementable → replaced (research D4). Frontend BLOCKERs (guard async-role source; 403 interceptor + stale-role refetch) and the phantom `core/layout/nav.component.ts` (nav is inline in `shell.component.ts`) are corrected. Full disposition logged in `checklists/requirements.md`. No constitution gate moved to FAIL.

## Project Structure

### Documentation (this feature)

```text
specs/003-rbac/
├── plan.md              # This file
├── research.md          # Phase 0 — decisions & rationale
├── data-model.md        # Phase 1 — entities, indexes, audit extension
├── quickstart.md        # Phase 1 — local run + manual verification
├── contracts/
│   └── rbac-api.md      # Phase 1 — REST endpoint contracts
├── checklists/
│   └── requirements.md  # Spec quality + review log
└── tasks.md             # Phase 2 (/speckit.tasks — NOT created here)
```

### Source Code (repository root)

```text
backend/src/main/java/com/cadence/
├── api/
│   ├── MemberAdminController.java      # NEW — GET /api/internal/members (ADMIN); PATCH .../{id}/role (ADMIN)
│   ├── AssignmentController.java       # NEW — GET /api/internal/assignments (scoped); GET/{id}; admin POST/DELETE
│   ├── AuthController.java             # MODIFIED — add @PreAuthorize("isAuthenticated()") to /me + /logout (inventory test, BE-1)
│   ├── InvitationController.java       # MODIFIED — already @PreAuthorize ADMIN; confirm against matrix
│   ├── RbacExceptionHandler.java       # NEW — maps RBAC exceptions (last-admin, invalid-role, not-found) to {error,message}
│   └── AuthExceptionHandler.java       # unchanged (F01 envelope reused)
├── domain/
│   ├── Role.java                       # unchanged enum (5 roles)
│   ├── Assignment.java                 # NEW — @Document("assignments")
│   ├── ResourceType.java               # NEW — enum: REQUISITION, INTERVIEW
│   ├── AuthEventType.java              # MODIFIED — add ROLE_CHANGED, AUTHORIZATION_DENIED
│   └── AuthAuditEvent.java             # MODIFIED — add nullable targetMemberId, oldRole, newRole
├── repository/
│   └── AssignmentRepository.java       # NEW
├── service/
│   ├── RoleService.java                # NEW — change role: validate canonical (FR-031), self-elevation (FR-006),
│   │                                   #       last-admin guard flip→recount→rollback (FR-005, D4); guardedDeactivate(...) for F03;
│   │                                   #       audit (FR-028)
│   ├── AssignmentService.java          # NEW — assignedResourceIds(ws,memberId,type), isAssigned(...),
│   │                                   #       requireAssigned(...) [scoped-write primitive, FR-032]; scoped findOne unified path (D6)
│   ├── SessionService.java             # MODIFIED — Principal role from persisted member, not session snapshot (FR-002/FR-007, D3);
│   │                                   #       Session.role/JWT claim marked diagnostic-only
│   └── AuthAuditService.java           # MODIFIED — roleChanged(actor,target,old,new) overload + bounded authorizationDenied(...)
├── security/
│   ├── SecurityConfig.java             # MODIFIED — add scoped JSON AccessDeniedHandler to the @Order(3) main chain only
│   ├── RestAccessDeniedHandler.java    # NEW — 403 {error,message} envelope for /api/** (FR-014); preserves actuator 404
│   └── SessionCookieAuthFilter.java    # unchanged (already maps principal.role()→ROLE_ authority)
└── config/migration/
    └── ChangeUnit003_RbacIndexes.java  # NEW — assignments indexes + members{workspaceId,role,status} guard index

backend/src/test/java/com/cadence/
└── rbac/
    ├── RoleServiceTest.java                 # Unit (Mockito): last-admin flip/recount/rollback branches, self-elevation, role validation (Principle VII)
    ├── AssignmentServiceTest.java           # Unit (Mockito): assignedResourceIds/isAssigned/requireAssigned (Principle VII)
    ├── RoleChangeIntegrationTest.java       # US1: assign/change persists; effective NEXT request via SAME cookie (SC-003); SC-012 concurrent; self-elevation 403 (FR-006); null/unknown role denied (FR-008); audit; non-admin 403
    ├── StaleRoleClaimIntegrationTest.java   # FR-002: same signed cookie, DB role changed → persisted role wins (not the claim)
    ├── LastAdminGuardIntegrationTest.java   # SC-004/SC-013: single + CONCURRENT double-demotion (CountDownLatch, N≥20, final-DB-state asserts, never zero admins); refused deactivation = no partial state (D4)
    ├── DenyByDefaultContractTest.java       # SC-001/SC-002: role×endpoint matrix; RO-write coverage inventory-derived; anonymous→401 not 403; @PreAuthorize-403→JSON envelope (D5)
    ├── RbacEndpointInventoryTest.java       # SC-010: RequestMappingHandlerMapping sweep, allow-list-by-exclusion, method-or-class @PreAuthorize, self-test fixture
    ├── AssignmentScopingIntegrationTest.java# US4/SC-005/SC-006/SC-015: scoped list; empty set; out-of-assignment id == missing id (status+body); scoped write refused (FR-032); cross-workspace ?memberId= empty
    ├── RoleValidationContractTest.java      # SC-014: non-canonical role value rejected, no persist
    └── RbacLogPiiScanTest.java              # SC-009: no PII/candidate-id/resource-content in authz logs

frontend/src/app/
├── core/auth/
│   ├── role.guard.ts                   # NEW — requireRole(...roles) CanActivateFn; sources role via auth.me() Observable; →/not-authorized, catchError→/login (D9, FE-1)
│   ├── auth.service.ts                  # MODIFIED — hasRole() helper; me() shareReplay; currentMember$.next(null) invalidation hook
│   ├── auth.interceptor.ts             # MODIFIED — add 403 branch → /not-authorized + invalidate cached member (D9, FE-2)
│   └── auth.models.ts                   # unchanged (Role already present)
├── features/admin/members/
│   ├── members.component.ts             # NEW — admin member directory + role change (ADMIN-guarded route); i18n="@@..." strings
│   └── members.service.ts               # NEW — GET members, PATCH role
├── shared/not-authorized/
│   └── not-authorized.component.ts      # NEW — /not-authorized page (i18n="@@notauthorized.*" template strings)
├── features/shell/shell.component.ts    # MODIFIED — role-gate nav links via hasRole() (nav is inline here; no core/layout/ exists, FE-3)
└── app.routes.ts                        # MODIFIED — /not-authorized as TOP-LEVEL un-guarded sibling (FE-5); /admin/members canActivate [authGuard, roleGuard('ADMIN')]

frontend/src/app/core/auth/role.guard.spec.ts   # Jasmine: permitted role passes, EACH disallowed role → /not-authorized (SC-011)
frontend/e2e/rbac.spec.ts                        # Playwright: admin changes role; non-admin hits /not-authorized; API still 403
```

**Structure Decision**: Web-application layout (constitution Reference Source Layout). F02 *extends* the F01 scaffold — it adds two controllers, two services, one domain collection, one Mongock changeset (`003`), and a scoped access-denied handler, plus a small frontend admin feature and a role guard. It modifies exactly one F01 line of behaviour (the principal's role source in `SessionService`). No new top-level structure, no new dependency.

## Complexity Tracking

| Decision | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| New `assignments` collection + `AssignmentService` shipped now | FR-023/FR-024/FR-032 require *server-side* scoping to be real and testable today; the spec mandates a non-stub increment (constitution §II). Exposing a real `assignments` resource lets HM/Interviewer scoping be demonstrated end-to-end before requisitions/interviews exist. | A pure interface/stub with no data would be a §II violation (work presented as done but not demonstrable). A denormalised array on `members` was considered but a separate collection cleanly carries the later requisition/interview links and avoids re-touching the encrypted `members` doc. |
| Method security (`@PreAuthorize`) as the per-endpoint source of truth + a build-time inventory test, rather than duplicating the matrix in `authorizeHttpRequests` | Keeps one source of truth for each endpoint's minimum role; the inventory test makes deny-by-default *fail-closed at build time* so a later feature cannot ship an internal endpoint without a declared role (FR-011/FR-022/SC-010). | Encoding the whole matrix a second time as HTTP path matchers duplicates it and drifts; relying only on runtime `authenticated()` is not role-deny-by-default. The two-layer approach (authn at HTTP layer + mandatory method security verified by the inventory test) is the Spring-idiomatic fail-closed design the review endorsed. |
