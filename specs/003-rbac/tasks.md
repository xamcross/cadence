# Tasks: Role-Based Access Control (RBAC)

**Input**: Design documents from `C:\Users\xamcr\Cadence\specs\003-rbac\`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/rbac-api.md, quickstart.md

**Tests**: INCLUDED and written FIRST — the constitution (§VII Test-First & Acceptance-Driven) is non-negotiable for backend business logic and acceptance paths. Each story's tests are authored before its implementation and must fail first.

**Organization**: Tasks grouped by user story (US1–US5) for independent implementation/testing.

## Path Conventions (web app — see plan.md Structure)

- Backend main: `backend/src/main/java/com/cadence/`
- Backend test: `backend/src/test/java/com/cadence/`
- Frontend: `frontend/src/app/`
- All integration tests extend `BaseIntegrationTest` (shared `@ServiceConnection` singleton `mongo:7`), clean via `mongoTemplate.remove(...)` (never `dropCollection`), use `@MockBean` (Boot 3.3), and `@Import` the F01 `MutableClock` test config — per CLAUDE.md / research D12.
- **Zero new dependencies** (RBAC uses `@EnableMethodSecurity`, already present in `SecurityConfig`; `spring-security-test` already on the test classpath) — research D1/C4.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Configuration only — no dependency or scaffold changes (F02 adds none).

- [X] T001 [P] Add `auth.rbac.denied-audit-window` (default `PT1M`) to `backend/src/main/resources/application.yml` and bind it in `backend/src/main/java/com/cadence/config/AuthProperties.java` (governs the bounded refusal-audit throttle — research D8).
- [X] T002 Verify (no code change) that `@EnableMethodSecurity` is present on `SecurityConfig` and that `backend/build.gradle` adds **no** new runtime dependency (gate C4); record the static check in the task notes.

---

## Phase 2: Foundational (Blocking Prerequisites)

**⚠️ CRITICAL**: No user story can begin until this phase is complete. Builds the shared RBAC backbone (assignment model, audit extension, the persisted-role authority fix, the 403 envelope, and the frontend authorization core) that every story uses.

### Backend — domain & data

- [X] T003 [P] Create `ResourceType` enum (REQUISITION, INTERVIEW) in `backend/src/main/java/com/cadence/domain/ResourceType.java` (data-model).
- [X] T004 [P] Add `ROLE_CHANGED` and `AUTHORIZATION_DENIED` to `backend/src/main/java/com/cadence/domain/AuthEventType.java` (MODIFIED — data-model).
- [X] T005 [P] Add nullable `targetMemberId`, `oldRole` (`Role`), `newRole` (`Role`) fields + getters/setters to `backend/src/main/java/com/cadence/domain/AuthAuditEvent.java` (MODIFIED — data-model).
- [X] T006 [P] Create `Assignment` `@Document("assignments")` (id, workspaceId, memberId, resourceType, resourceId, createdAt, createdByMemberId — all non-null) in `backend/src/main/java/com/cadence/domain/Assignment.java` (data-model; no nullable indexed field, so no `@Field(write=NON_NULL)` needed).
- [X] T007 [P] Create `AssignmentRepository` (findByWorkspaceIdAndMemberIdAndResourceType; findByWorkspaceIdAndId) in `backend/src/main/java/com/cadence/repository/AssignmentRepository.java`.
- [X] T008 Create Mongock `ChangeUnit003_RbacIndexes` (`order="003"`, never renamed; native `createIndex`; targeted `dropIndex` rollback per CLAUDE.md) creating `members {workspaceId:1, role:1, status:1}` (non-unique), `assignments {workspaceId:1, memberId:1, resourceType:1}`, and `assignments {workspaceId:1, resourceType:1, resourceId:1, memberId:1}` (unique) in `backend/src/main/java/com/cadence/config/migration/ChangeUnit003_RbacIndexes.java` (research D10; **depends on T006**).

### Backend — shared security & services

- [X] T009 Modify `SessionService.validate(...)` to build the returned `Principal` from the **persisted member's role** (`member.get().getRole()`, already loaded for the active-status check) instead of the session snapshot `s.getRole()`; add a comment marking `Session.role` and the JWT `role` claim as **diagnostic-only, never an authorization input** in `backend/src/main/java/com/cadence/service/SessionService.java` (MODIFIED — research D3; FR-002/FR-007). Zero added queries.
- [X] T010 Add `roleChanged(workspaceId, actorMemberId, targetMemberId, oldRole, newRole)` overload (sets the new T005 fields) and a bounded `authorizationDenied(workspaceId, actorMemberId, eventCode)` where `eventCode` maps to the existing `outcome` short-code field (no new column) (in-memory throttle: ≤1 per `(memberId,eventType)` per `auth.rbac.denied-audit-window`, bounded by actor cardinality, not persisted) to `backend/src/main/java/com/cadence/service/AuthAuditService.java` (MODIFIED — research D8; **depends on T004, T005, T001**).
- [X] T011 [P] Create RBAC exceptions (`LastAdminException`, `NotAssignedException`, `ScopedNotFoundException`) and `RbacExceptionHandler` (`@RestControllerAdvice`) mapping them to the `{error,message}` envelope — `last_admin`→409, `not_found`→404, invalid-role enum-bind→400 `invalid_role` — in `backend/src/main/java/com/cadence/api/RbacExceptionHandler.java` and `.../api/RbacExceptions.java` (contracts; never leak resource id/content — FR-014).
- [X] T012 Add `@PreAuthorize("isAuthenticated()")` to `me()` and `logout()` in `backend/src/main/java/com/cadence/api/AuthController.java` **and** to `slow()` in `backend/src/main/java/com/cadence/api/HealthTestController.java` (MODIFIED — research D2/BE-1: both are internal handlers; without the annotation the inventory test T029 reds the build. `HealthTestController` is `@Profile("test")` and so IS registered under the test profile the inventory test runs in — it must be annotated, not skipped).
- [X] T013 Create `RestAccessDeniedHandler` rendering `403 {"error":"forbidden","message":"You do not have access to this action."}` (shape-identical across all 403s, no resource signal) and wire it via `exceptionHandling(e -> e.accessDeniedHandler(...))` on the **`@Order(3)` main chain only** in `backend/src/main/java/com/cadence/security/{RestAccessDeniedHandler.java,SecurityConfig.java}` (MODIFIED — research D5; MUST preserve F01's `/api/**`→401 entry point and the F00 actuator-on-public-port 404; do NOT touch the `@Order(1)`/`@Order(2)` chains).

### Backend — foundational test

- [X] T014 [P] Create `RbacIndexBootstrapTest` asserting `listIndexes` returns the exact generated names `members.workspaceId_1_role_1_status_1`, `assignments.workspaceId_1_memberId_1_resourceType_1`, and the unique `assignments.workspaceId_1_resourceType_1_resourceId_1_memberId_1` (so a mis-ordered key spec is caught), in `backend/src/test/java/com/cadence/rbac/RbacIndexBootstrapTest.java` (mirrors the F00.1 `AuthIndexBootstrapTest` name-assertion pattern; clean via `mongoTemplate.remove(...)`, never `dropCollection`; **depends on T008**).

### Frontend — shared authorization core

- [X] T015 [P] Create `requireRole(...roles)` `CanActivateFn` factory that sources the role via `auth.me()` (Observable, self-caching) and returns `true` / `router.createUrlTree(['/not-authorized'])`, with `catchError(() => of(createUrlTree(['/login'])))` for the no-session case, in `frontend/src/app/core/auth/role.guard.ts` (research D9/FE-1; mirrors the existing `auth.guard.ts` pattern — never reads `currentMember$.value` synchronously).
- [X] T016 [P] Add a `hasRole(...roles): Observable<boolean>` helper (from `me()`) and an `invalidateMember()` method (`currentMember$.next(null)`); optionally `shareReplay` the in-flight `me()` in `frontend/src/app/core/auth/auth.service.ts` (MODIFIED — research D9/FE-1/FE-2).
- [X] T017 [P] Extend `authErrorInterceptor` with a **403 branch** that navigates to `/not-authorized` (same public-route loop-guard as the 401 branch) and calls `auth.invalidateMember()` so the stale role is refetched, in `frontend/src/app/core/auth/auth.interceptor.ts` (MODIFIED — research D9/FE-2; the role-change-mid-session path).
- [X] T018 [P] Create the `/not-authorized` standalone component (Angular Material; strings via `i18n="@@notauthorized.*"` matching the `@@shell.*` convention; internal screen so WCAG/Lighthouse N/A but i18n applies) in `frontend/src/app/shared/not-authorized/not-authorized.component.ts` (research D9/FE-4).
- [X] T019 Add `/not-authorized` as a **top-level un-guarded sibling** route (alongside `/login`) in `frontend/src/app/app.routes.ts` (MODIFIED — research D9/FE-5; **depends on T018**).

**Checkpoint**: Assignment model + indexes, audit extension, persisted-role authority (D3), the scoped 403 envelope, and the frontend role-guard/not-authorized/interceptor core all exist. User stories can now proceed.

---

## Phase 3: User Story 1 - Administrator manages member roles (Priority: P1) 🎯 MVP

**Goal**: An Admin lists members and changes a member's role; the change is governed on the member's next request; last-Admin lockout and self-elevation are refused; every change is audited.

**Independent Test**: As Admin, `GET /api/internal/members` lists members+roles; `PATCH /api/internal/members/{id}/role` changes a role (persisted, audited); the target's next request (same session) is governed by the new role; demoting the last Admin → 409; a non-Admin → 403.

### Tests for User Story 1 (write first, must fail) ⚠️

- [X] T020 [P] [US1] `RoleServiceTest` (Mockito unit) — last-Admin flip→recount→rollback branches, self-elevation guard, canonical-role validation, audit invocation, `guardedDeactivate` ordering, in `backend/src/test/java/com/cadence/rbac/RoleServiceTest.java`.
- [X] T021 [P] [US1] `RoleChangeIntegrationTest` (using the imported `MutableClock` held at a fixed instant so the cookie `exp` check is deterministic) — assign/change persists + audited (SC-012 concurrent role change → exactly one value persists, both audited); **effective on the NEXT request reusing the SAME `cad_session` cookie** (no re-login, no clock advance past expiry — SC-003); Admin self-elevation → 403 (**SC-007 self-promotion vector**) and self-demotion allowed only if another Admin remains (FR-006); a member with `role==null`/unknown denied on every role-gated endpoint, never treated as Admin (FR-008); non-Admin → 403, in `backend/src/test/java/com/cadence/rbac/RoleChangeIntegrationTest.java`.
- [X] T022 [P] [US1] `LastAdminGuardIntegrationTest` — single-actor last-Admin demotion → 409 no change (SC-004); **concurrent** double-demotion of the last two Admins via a shared `CountDownLatch` (no `Thread.sleep`), looped N≥20 with `remove()` cleanup, asserting on **final DB state**: `count(role:ADMIN,status:ACTIVE) ≥ 1` AND exactly one 2xx + one `409 last_admin` (SC-013); a refused **deactivation** leaves the member ACTIVE and sessions un-revoked (D4 ordering), in `backend/src/test/java/com/cadence/rbac/LastAdminGuardIntegrationTest.java`.
- [X] T023 [P] [US1] `RoleValidationContractTest` (MockMvc) — `PATCH .../role` with a non-canonical role value → `400 invalid_role`, no persisted change (SC-014), in `backend/src/test/java/com/cadence/rbac/RoleValidationContractTest.java`.

### Implementation for User Story 1

- [X] T024 [P] [US1] Create `RoleService` — `changeRole(actor, targetMemberId, newRole)` (enum-bound validation FR-031; self-elevation guard FR-006; **flip → recount → conditional-rollback** last-Admin guard FR-005/D4 via `mongoTemplate` conditional `findAndModify` + recount + conditional revert; `roleChanged` audit FR-028) and `guardedDeactivate(workspaceId, memberId)` (the F03-binding primitive: guarded status flip, then `SessionService.revokeAllForMember` ONLY on success) in `backend/src/main/java/com/cadence/service/RoleService.java` (**depends on T009, T010**; makes T020–T023 pass).
- [X] T025 [US1] Create `MemberAdminController` — `GET /api/internal/members` (`@PreAuthorize("hasRole('ADMIN')")`, returns memberId/displayName/role/status scoped to caller workspace) and `PATCH /api/internal/members/{memberId}/role` (`@PreAuthorize("hasRole('ADMIN')")`, body bound to `Role` enum) in `backend/src/main/java/com/cadence/api/MemberAdminController.java` (contracts; **depends on T024**).
- [X] T026 [P] [US1] Create `members.service.ts` (`getMembers()`, `changeRole(memberId, role)` against `apiBaseUrl/internal/members`) in `frontend/src/app/features/admin/members/members.service.ts`.
- [X] T027 [US1] Create `members.component.ts` — member directory table + role-change control (Angular Material; `i18n="@@members.*"` strings; surfaces 409 last-admin / 403 messages) in `frontend/src/app/features/admin/members/members.component.ts` (**depends on T026**).
- [X] T028 [US1] Add `/admin/members` route with `canActivate: [authGuard, roleGuard(['ADMIN'])]` (roleGuard after authGuard) in `frontend/src/app/app.routes.ts` (MODIFIED — **depends on T015, T027**).

**Checkpoint**: Admin role administration is fully functional and independently testable; role changes take effect next-request; last-Admin lockout is impossible.

---

## Phase 4: User Story 2 - Deny-by-default enforcement on internal endpoints (Priority: P1)

**Goal**: Every internal endpoint enforces a declared minimum role; authenticated-but-unauthorized members get 403 (distinct from 401); an internal endpoint without a declared role fails the build (deny-by-default, fail-closed).

**Independent Test**: For each role, a disallowed write returns 403 (no state change) and the allowed role succeeds; an anonymous internal call returns 401; the inventory test fails the build if any internal handler lacks a role declaration.

### Tests for User Story 2 (write first, must fail) ⚠️

- [X] T029 [P] [US2] `RbacEndpointInventoryTest` — enumerate `RequestMappingHandlerMapping.getHandlerMethods()`, **allow-list by exclusion** (`/api/public/**`, `/api/candidate/**`, `/actuator/**`, `/oauth2/**`, `/login/oauth2/code/**`, plus framework handlers: `/error`/`BasicErrorController` and any `org.springframework.*` bean type), fail on any remaining handler lacking `@PreAuthorize`/`@PostAuthorize`/`@Secured` at **method or class** level (`AnnotatedElementUtils.findMergedAnnotation`); include a **self-test fixture** (a deliberately-undeclared dummy internal handler asserted flagged) so the failure path is proven (SC-010; this is the **SC-007 undeclared-endpoint escalation vector**), in `backend/src/test/java/com/cadence/rbac/RbacEndpointInventoryTest.java`.
- [X] T030 [P] [US2] `DenyByDefaultContractTest` (MockMvc + `spring-security-test` authorities) — role×endpoint matrix → expected 200/403; **RO-write coverage derived from the same reflection sweep** (every POST/PUT/PATCH/DELETE internal handler → 403 for READ_ONLY, FR-012); anonymous `/api/internal/**` → **401 not 403** (FR-010); a `@PreAuthorize`-triggered 403 renders the JSON envelope byte-identically (D5); **bounded refusal-audit**: probing a role-management endpoint as a disallowed role N times within one window → **exactly one** `AUTHORIZATION_DENIED` audit row (FR-028 anti-amplification). (In F02 every `/api/internal/**` authz denial is on a security-relevant endpoint — member admin / role mgmt / assignments — so the bounded blanket audit on the main-chain `AccessDeniedHandler` IS "security-relevant only"; there is no routine non-security internal endpoint to produce a zero-row case.) In `backend/src/test/java/com/cadence/rbac/DenyByDefaultContractTest.java`.
- [X] T031 [P] [US2] `StaleRoleClaimIntegrationTest` (FR-002; **SC-007 tampered/stale-claim vector**; using the imported `MutableClock` at a fixed instant so the signed cookie `exp` is deterministic) — issue a session while the member is ADMIN (cookie/claim snapshot = ADMIN), demote the member to READ_ONLY directly in the DB, replay the **same signed** cookie against an ADMIN-only endpoint (the existing `POST /api/internal/invitations`) → 403 (persisted role wins, claim ignored); assert `Session.role` snapshot still ADMIN (non-authoritative), in `backend/src/test/java/com/cadence/rbac/StaleRoleClaimIntegrationTest.java`.

### Implementation for User Story 2

- [X] T032 [US2] **Depends on T029/T030 (must be red first).** Close the inventory across `backend/src/main/java/com/cadence/api/{AuthController.java, HealthTestController.java, InvitationController.java, MemberAdminController.java}`: confirm every internal handler carries the correct minimum-role declaration — `AuthController.me/logout` + `HealthTestController.slow` = `isAuthenticated()` (T012), `InvitationController.create` = `hasRole('ADMIN')` (already), `MemberAdminController` = `hasRole('ADMIN')` (T025) — and fix any gap so T029/T030 are green; no new endpoint ships without a declared role (FR-011/FR-022). (Mechanism is foundational T013; this task is the closure/verification deliverable.)

**Checkpoint**: Deny-by-default holds across the endpoint inventory; 401/403 are distinct; omission fails the build.

---

## Phase 5: User Story 3 - Least-privilege access per role (Priority: P2)

**Goal**: Each non-Admin role is confined to its matrix-permitted actions; the matrix is the single source of truth that later features inherit.

**Independent Test**: For each role, an in-scope action on an existing endpoint succeeds and an out-of-scope action returns 403, matching the canonical Permission Matrix.

### Tests for User Story 3 (write first, must fail) ⚠️

- [X] T033 [P] [US3] `RoleMatrixContractTest` (MockMvc) — assert the canonical matrix on the endpoints that exist today: Recruiter/HM/Interviewer/Read-only → 403 on member-administration + role-management; Read-only → 403 on every state-changing action; allowed reads succeed (FR-017–FR-021), in `backend/src/test/java/com/cadence/rbac/RoleMatrixContractTest.java`.

### Implementation for User Story 3

- [X] T034 [US3] Verify/adjust `@PreAuthorize` declarations to match the Permission Matrix exactly, and add a one-line forward-contract note (each later feature F12–F51 declares its endpoints' minimum role + scoping against this matrix, FR-022), in `backend/src/main/java/com/cadence/api/InvitationController.java`, `.../MemberAdminController.java`, and `.../AssignmentController.java` (no new endpoints; this is the least-privilege closure for the current inventory).

**Checkpoint**: Every current internal endpoint matches the matrix; the matrix is the documented contract for later features.

---

## Phase 6: User Story 4 - Server-side scoping to a member's own data (Priority: P2)

**Goal**: Hiring Managers/Interviewers see and act on only their own assigned resources, enforced server-side; out-of-assignment access is indistinguishable from not-found.

**Independent Test**: As an Admin assign a resource to an HM; the HM lists only their own assignments; an HM with none gets an empty set; a direct fetch of another's assignment returns a 404 identical to a missing id; a scoped write outside assignment is refused.

### Tests for User Story 4 (write first, must fail) ⚠️

- [X] T035 [P] [US4] `AssignmentServiceTest` (Mockito unit) — `assignedResourceIds`, `isAssigned`, `requireAssigned` (throws `NotAssignedException`), unified scoped `findOne` returning empty for both missing and not-yours, in `backend/src/test/java/com/cadence/rbac/AssignmentServiceTest.java`.
- [X] T036 [P] [US4] `AssignmentScopingIntegrationTest` — HM lists only own assignments (FR-024); HM with none → empty set, never full workspace (SC-006); `GET /assignments/{id}` for an out-of-assignment existing id vs a non-existent id → **byte-identical 404 status+body** (SC-015), with `mongoTemplate.remove(...)` cleanup between the existing-id and missing-id sub-cases so byte-equality runs against identical baseline state; a scoped **POST/DELETE** against a record outside assignment → refused with **the same indistinguishable not-found status+body as the read path** (so a write is not an existence oracle), **and a post-call DB re-read asserts zero mutation** (target document byte-identical before/after) via `AssignmentService.requireAssigned` throwing `NotAssignedException` mapped through the shared not-found path (FR-032/FR-025; **SC-007 scoped-id-bypass vector**); Admin/Recruiter `?memberId=` AND-ed with caller workspace → cross-workspace memberId yields empty set; run the scoped-list + indistinguishable-not-found assertions for **both** `resourceType` values (REQUISITION via a Hiring Manager, INTERVIEW via an Interviewer) so both enum paths are covered, in `backend/src/test/java/com/cadence/rbac/AssignmentScopingIntegrationTest.java`.

### Implementation for User Story 4

- [X] T037 [P] [US4] Create `AssignmentService` — `assignedResourceIds(ws, memberId, type)`, `isAssigned(ws, memberId, type, resourceId)`, `requireAssigned(...)` (scoped-write primitive FR-032, consumed by later F13/F32), and a single scoped `findOne({workspaceId, _id, memberId})` shared not-found path (FR-025/D6) in `backend/src/main/java/com/cadence/service/AssignmentService.java` (**depends on T007**).
- [X] T038 [US4] Create `AssignmentController` — `GET /api/internal/assignments` (`@PreAuthorize("hasAnyRole('ADMIN','RECRUITER','HIRING_MANAGER','INTERVIEWER')")`, scoped for HM/I to own, A/R may pass workspace-AND-ed `?memberId=`), `GET /api/internal/assignments/{id}` (indistinguishable 404), `POST /api/internal/members/{memberId}/assignments` + `DELETE .../{assignmentId}` (`@PreAuthorize("hasRole('ADMIN')")`) in `backend/src/main/java/com/cadence/api/AssignmentController.java` (**depends on T037**; its annotations are checked by the T029 inventory test).

**Checkpoint**: Server-side scoping is real and demonstrable end-to-end; out-of-assignment access leaks nothing.

---

## Phase 7: User Story 5 - Frontend authorization experience (Priority: P3)

**Goal**: Members see only nav/routes their role permits; unauthorized navigation lands on `/not-authorized` (not a 404); the server remains the boundary.

**Independent Test**: A non-Admin navigating to `/admin/members` is redirected to `/not-authorized`; nav hides disallowed entries; the API still returns 403 when the guard is bypassed.

### Tests for User Story 5 (write first, must fail) ⚠️

- [X] T039 [P] [US5] `role.guard.spec.ts` (Jasmine, `TestBed` stub `auth.me()` → `of({role})`) — the permitted role passes and **each** disallowed role redirects to `/not-authorized` (one assertion per role/route pair, SC-011); the no-session case routes to `/login`, in `frontend/src/app/core/auth/role.guard.spec.ts`.
- [~] T040 [P] [US5] **AUTHORED, runner not executed** (Playwright is not installed — constitution §X zero-download; spec written for CI, mirroring F01's deferred E2E). `rbac.spec.ts` (Playwright) — Admin changes a member's role in the directory; a non-Admin hits `/not-authorized` on `/admin/members`; the underlying API independently returns 403 when the guard is bypassed (SC-008/FR-013), in `frontend/e2e/rbac.spec.ts`.

### Implementation for User Story 5

- [X] T041 [P] [US5] Gate nav links by role using the `hasRole()` helper (hide the Admin/Members entry for non-Admins — UX only, never enforcement) in `frontend/src/app/features/shell/shell.component.ts` (MODIFIED — nav is inline here; **depends on T016**).

**Checkpoint**: The frontend reflects each role and fails unauthorized navigation gracefully; the server stays the security boundary.

---

## Phase 8: Polish & Cross-Cutting Concerns

- [X] T042 [P] Create `RbacLogPiiScanTest` (Logback `ListAppender`) asserting zero PII / candidate-id / scoped-record content across role-change and authorization-denial paths (SC-009/FR-029) in `backend/src/test/java/com/cadence/rbac/RbacLogPiiScanTest.java`.
- [X] T043 [P] Confirm the CI PII-grep step's path globs include the new authz log output **AND** that `RbacLogPiiScanTest` runs in the CI `test` task — record **both** as SC-009 gates (the unit test is the assertive gate, the grep is defense-in-depth; not alternatives) in `.github/workflows/ci.yml` (DoD).
- [X] T044 [P] Update `CLAUDE.md` Implementation Notes (003-rbac) — the D3 persisted-role authority fix, the flip→recount→rollback last-Admin guard, the `isAuthenticated()` inventory rule, the scoped `AccessDeniedHandler` (preserve actuator 404), and the unified scoped-not-found path.
- [X] T045 Full automated gate run: backend `gradle test` GREEN (F01 + 62 F02 RBAC tests incl. the concurrent last-Admin latch), frontend `ng test` 6/6 + `ng build` clean. (The browser-manual quickstart steps and `playwright test` were not executed locally — see T040; their automated equivalents pass.)
- [X] T046 Multi-role sub-agent review at task close (constitution §VI) — **2 loops**. Loop 1 (Security, Backend, QA): all APPROVE-WITH-CHANGES; 1 MAJOR (cross-workspace assignment create) + delete-scoping + audit-scope/test-rigor MINORs — all applied. Loop 2 (Security+Backend, QA): both **APPROVE**, fixes verified correct and complete, suite green. **SC-007 escalation checklist** — all four vectors present and red-before-green: self-promotion (RoleServiceTest + DenyByDefault non-admin 403), stale/tampered claim (StaleRoleClaimIntegrationTest), undeclared endpoint (RbacEndpointInventoryTest self-test fixture), scoped-id bypass (AssignmentScopingIntegrationTest). Full disposition in `checklists/requirements.md`.

---

## Dependencies & Execution Order

### Phase dependencies

- **Setup (P1)**: T001–T002, no dependencies.
- **Foundational (P2)**: depends on Setup; **blocks all user stories**. Within it: T008 needs T006; T010 needs T004/T005/T001; T014 needs T008; T019 needs T018. `SecurityConfig` (T013) and `app.routes.ts` (T019, then T028) are serialized files — land T013 before any chain change and T019 before T028.
- **User Stories (P3–P7)**: all depend on Foundational. US1 (P1) is the MVP. US2 depends on T012/T013 (foundational) + green after US1/US4 controllers are annotated. US3 verifies the matrix (after US1/US4 endpoints exist). US4 is independent of US1 (different files). US5 depends on the foundational frontend core (T015–T019) + US1's `/admin/members` route (T028) for a guarded target.
- **Polish (P8)**: after the stories it spans (T042 spans US1/US2/US4).

### User-story independence

- **US1 (P1)**: independent — its own service/controller/frontend files.
- **US2 (P1)**: the enforcement mechanism is foundational (T012/T013); US2 adds the inventory + contract tests. Green once each story annotates its controllers.
- **US3 (P2)**: matrix verification over existing endpoints; independent test.
- **US4 (P2)**: independent — assignment files only.
- **US5 (P3)**: frontend guard/nav; uses the foundational core + the US1 admin route as a demonstrable target.

### Within each story

- Tests (marked ⚠️) are written FIRST and must fail before implementation (constitution §VII).
- Backend: service before controller; `SessionService`/`SecurityConfig`/`app.routes.ts`/`AuthAuditService` are serialized files (no two [P] tasks touch the same file).

### Parallel opportunities

- Setup T001 is [P].
- Foundational: T003–T007 [P], T011 [P], T014 [P], frontend T015–T018 [P] (different files); T008/T009/T010/T012/T013/T019 are serialized by file/dependency.
- US1 tests T020–T023 [P]; US4 tests T035–T036 [P]; US5 tests T039–T040 [P].
- After Foundational, US1's `RoleService` (T024 [P]) and US4's `AssignmentService` (T037 [P]) can be built in parallel (disjoint files); US2/US3 tests can be authored in parallel too.

---

## Parallel Example: User Story 1

```bash
# Write US1 tests together first (must fail):
Task: "RoleServiceTest in backend/src/test/java/com/cadence/rbac/RoleServiceTest.java"
Task: "RoleChangeIntegrationTest in backend/src/test/java/com/cadence/rbac/RoleChangeIntegrationTest.java"
Task: "LastAdminGuardIntegrationTest in backend/src/test/java/com/cadence/rbac/LastAdminGuardIntegrationTest.java"
Task: "RoleValidationContractTest in backend/src/test/java/com/cadence/rbac/RoleValidationContractTest.java"

# Then frontend service + (separately) backend service can proceed in parallel:
Task: "members.service.ts in frontend/src/app/features/admin/members/members.service.ts"
```

---

## Implementation Strategy

### MVP first (User Story 1 only)

1. Phase 1 Setup → Phase 2 Foundational (CRITICAL — the D3 authority fix, 403 envelope, assignment model, frontend core).
2. Phase 3 US1 → **STOP and VALIDATE**: Admin changes roles, effect is next-request, last-Admin lockout impossible, non-Admin blocked.
3. Demo the role-administration slice (genuinely demonstrable on F01's seeded members).

### Incremental delivery

1. Foundational → US1 (MVP, role admin) → US2 (deny-by-default guarantee + inventory) → US4 (server-side scoping) → US3 (matrix closure) → US5 (frontend UX) → Polish.
2. Each story is independently testable; US2's inventory + RO-write contract tests (T029/T030) are **reflection-driven**, so they auto-cover controllers added by later stories — **re-run `./gradlew test` after US3/US4 add `AssignmentController` etc.** to confirm each new handler declared its minimum role (the inventory test is the forcing function for F12–F51 too).

### Parallel team strategy

- After Foundational: Dev A → US1 (role admin), Dev B → US4 (scoping), Dev C → US2/US3 tests + US5 frontend. Stories integrate via the shared foundational core without file conflicts.

---

## Notes

- [P] = different files, no dependencies. [Story] label maps each task to its user story.
- Tests fail first (§VII). Serialized files: `SessionService.java`, `SecurityConfig.java`, `AuthAuditService.java`, `AuthController.java`, `app.routes.ts` — never two [P] tasks on the same file.
- Zero new dependencies; zero tool downloads (C4/C7). All integration tests use the singleton container + `remove()` cleanup + `MutableClock`.
- The `guardedDeactivate` primitive (T024) is shipped and unit-tested now but **wired by F03** (forward contract) — F02 does not own the deactivation UI.
- Commit after each task or logical group; run the §VI review (T046) before closing.
