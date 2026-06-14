# Phase 0 Research: Role-Based Access Control (RBAC)

**Feature**: 003-rbac | **Date**: 2026-06-14

Resolves the technical unknowns implied by the spec and records the decisions Phase 1 builds on. Every decision is checked against the constitution (single-instance + MongoDB only, fixed stack, security-by-default, zero downloads). Grounded in the **actual F01 source** (`SecurityConfig`, `SessionService`, `SessionCookieAuthFilter`, `InvitationController`) read during planning.

---

## D1 — Enforcement mechanism: method security as the source of truth *(C4)*

**Decision**: Use Spring Security **method security** (`@PreAuthorize`) on every `/api/internal/**` controller handler as the per-endpoint minimum-role declaration. `@EnableMethodSecurity` is **already present** in `SecurityConfig` (F01), and `InvitationController.create` already carries `@PreAuthorize("hasRole('ADMIN')")` — F02 generalises this pattern across the matrix. Authorities arrive as `ROLE_<role>` (set per request by `SessionCookieAuthFilter` from `principal.role()`), so `hasRole('ADMIN')` / `hasAnyRole(...)` work directly.

**Rationale**: zero new dependency (C4 PASS); one source of truth per endpoint; reuses the authority already on the SecurityContext. The matrix is explicit per-action grants (spec Assumption — roles are not a numeric hierarchy), which maps naturally to `hasAnyRole(...)` per handler rather than a global ordering.

**Alternatives**: encoding the matrix as `authorizeHttpRequests` path matchers (duplicates the matrix in two places, drifts); a custom `AuthorizationManager` registry (the registry *is* the matrix duplicated). Rejected.

---

## D2 — Deny-by-default: fail-closed via mandatory method security + a build-time inventory test *(FR-011, FR-022, SC-010; review BE-5/QA-1)*

**Decision**: Two layers.
1. **HTTP layer (authn fail-closed)**: the `@Order(3)` main chain keeps `anyRequest().authenticated()` — no internal path is reachable unauthenticated (existing F01 behaviour, FR-010). At runtime a handler that somehow lacked a role declaration is reachable by *any authenticated member* (not anonymous); the build-time test below is what makes role-omission fail closed.
2. **Role fail-closed (build-time)**: a reflection test **`RbacEndpointInventoryTest`** that gates every `./gradlew test` / CI build.

**"Authenticated-any-role" is a first-class declaration** *(review BE-1, BLOCKER)*: some internal endpoints are legitimately reachable by **every** role — F01 already ships `GET /api/internal/auth/me` and `POST /api/internal/auth/logout` (verified in `AuthController`, **no** method-security annotation today). The inventory rule "every internal handler must declare a minimum role" must therefore accept `@PreAuthorize("isAuthenticated()")` as a valid, explicit declaration. **F02 MODIFIES `AuthController` to add `@PreAuthorize("isAuthenticated()")` to `me()` and `logout()`** — otherwise the inventory test reds the build on its first run. (Allow-listing those two paths instead is rejected: an allow-list rots, the exact failure mode this test exists to prevent.)

**Inventory-test enumeration mechanism** *(review BE-3/QA-6)*: the test enumerates **all** mappings via `RequestMappingHandlerMapping.getHandlerMethods()` (which resolves the actual composed class+method patterns), then **allow-lists by exclusion** — it removes handlers whose pattern starts with `/api/public/`, `/api/candidate/`, `/actuator/`, `/oauth2/`, or `/login/oauth2/code/` — and **fails on any remaining handler** lacking method security, checking **both** method- and class-level annotations via `AnnotatedElementUtils.findMergedAnnotation(handler.getMethod()/.getBeanType(), PreAuthorize.class)` (so a class-level `@PreAuthorize` counts, and `InvitationController`'s mix of internal `create` + public `validate/accept` is resolved per-handler by pattern, not per-class). Allow-list-by-exclusion (not include-only `/api/internal/**`) means a later feature adding an internal endpoint under a *new* prefix is still caught. The test includes a **self-test fixture** — a deliberately-undeclared dummy internal handler asserted to be flagged — so the test's own failure path is proven (it cannot be vacuously green).

**Rationale**: keeps a single source of truth (D1) while making omission fail closed at the cheapest moment. The review accepted "both layers exist" (authn at HTTP layer + mandatory method security verified by the inventory test) as satisfying FR-011, given the authenticated-any-role declaration and the exclusion-based sweep.

**Alternatives**: relying on runtime `authenticated()` only (not role-deny-by-default); a per-feature manual checklist or a path allow-list (rots silently); include-only `/api/internal/**` enumeration (misses a new prefix). Rejected.

---

## D3 — Role change effective on next request: principal role from the persisted member *(FR-002/FR-007; grounded bug found in F01 source)*

**Decision**: Modify `SessionService.validate(...)` so the returned `Principal` carries the **persisted member's current role** (`member.get().getRole()`), not the session snapshot `s.getRole()`. The live `Member` is **already loaded** on the very next line for the active-status check (`members.findById(s.getMemberId())`), so this is **zero added queries** — a one-field change.

**Why this is necessary**: read during planning, F01's `validate()` builds `new Principal(s.getMemberId(), s.getWorkspaceId(), s.getRole(), s.getId())` — the role is the snapshot taken when the session was *issued*. Without this change, an Admin's role change would not take effect until the member re-logs in, silently failing FR-007/SC-003.

**No external cache** *(C2)*: because the authoritative role is read from the member document already fetched each request, role changes are effective next request **without** any in-process TTL cache or invalidation step (spec Assumption "No external state").

**Make the change structural, not a swapped field** *(review SEC-1/QA-1)*: the `Principal` is constructed from `member.get().getRole()`, and the two residual role snapshots are explicitly neutralised as authorization inputs:
- `Session.role` (registry snapshot, set once at issue) is **diagnostic-only** — commented/renamed so no future code reads it for a decision.
- `JwtSupport.ParsedToken.role` (the signed claim) is **advisory-only** — never the authorization basis (defense-in-depth beyond F01 token integrity).
No current consumer breaks: `SessionCookieAuthFilter` already maps `v.principal().role()`→`ROLE_` authority (so it picks up the live role automatically), and the `@AuthenticationPrincipal` consumers (`AuthController.me`, `InvitationController.create`) read `memberId/workspaceId/sessionId`, not the snapshot role.

**Gating adversarial test (FR-002, BLOCKER-class)** — `StaleRoleClaimIntegrationTest` (or a named case in `RoleChangeIntegrationTest`): (1) issue a session while the member is ADMIN — the cookie's signed `role` claim and the session snapshot are both ADMIN; (2) demote the member to READ_ONLY directly in the DB; (3) replay the **same** cookie (real signing key, signature valid — so this tests claim-vs-DB precedence, *not* signature rejection) and assert the next request is governed by READ_ONLY (403 on an admin-only endpoint); (4) assert `Session.role` still reads ADMIN (snapshot is non-authoritative). This is the FR-002 enforcement and gates the feature.

**Rationale**: satisfies FR-002 (persisted role authoritative, never the credential claim) and FR-007 (next-request effect) at no runtime cost, and closes a real latent defect verified in F01's `SessionService.validate()` (`Principal` built from `s.getRole()` while `member` is already loaded).

---

## D4 — Last-Admin guard: conditional flip → recount → conditional rollback (broker-free, race-safe) *(FR-005, SC-004/SC-013; review SEC-2/BE-2/QA-4)*

**Correction (plan review)**: an earlier draft proposed "a single atomic update that demotes only if another active Admin exists." That is **not implementable** — a MongoDB single-document `updateOne`/`findAndModify` filter matches only the document being modified, so it cannot express "≥1 *other* active Admin exists in the workspace." A naive count-then-write is a lost-update; and a multi-document *transaction* that reads the count then flips is still vulnerable to **write-skew** (two concurrent transactions each read count=2, each demote a different Admin, both commit → zero Admins) because snapshot isolation only conflicts on a shared write. The mechanism below is the genuinely race-safe, broker-free design.

**Decision — flip → recount → conditional rollback** (in `RoleService.changeRole()`, when the change removes ADMIN from the target or deactivates an ADMIN):
1. **Conditional flip** — `findAndModify({ _id: target, workspaceId: ws, role: ADMIN, status: ACTIVE } → set new role/status)`. Atomic on the single target document; a no-match (already demoted) short-circuits to a no-op.
2. **Recount** — `count({ workspaceId: ws, role: ADMIN, status: ACTIVE })`. Because step 1 is immediately durable/visible, a concurrent demotion's flip is seen here.
3. **Conditional rollback** — if the count is **0**, atomically roll the target back to `ADMIN`/`ACTIVE` and return **409 `last_admin`** (no net change). Otherwise commit the demotion.

**Why this is safe under concurrency (SC-013)**: For two simultaneous last-two-Admin demotions, the only way both flips precede both recounts is the exact-simultaneity interleaving — in which *both* recount to 0 and *both* roll back, leaving **two** Admins (invariant `≥1 active Admin` holds; both demotions are refused and retryable). Every other interleaving has one demotion's recount see the other's committed flip → exactly one success, one rollback. **The workspace can never reach zero active Admins.** No distributed lock, no broker, no transaction (C2 holds); works on the Testcontainers single-node `MongoDBContainer` and Atlas alike.

**Deactivation ordering** *(spec FR-005; review SEC-3)*: deactivation is owned by F01/F03 — flipping `status=DEACTIVATED` (member doc) **and** `SessionService.revokeAllForMember()` (separate sessions write) cannot be one atomic write. F02 exposes a guarded primitive `RoleService.guardedDeactivate(workspaceId, memberId)` whose contract — **binding on F03** (a forward contract like FR-022) — is: **(1)** run the guarded status flip (steps 1–3 above on `status`); **(2)** call `revokeAllForMember()` **only on success**. If the guard trips, step 2 never runs, so a refused deactivation leaves the member `ACTIVE` and all sessions un-revoked (no partial state). `LastAdminGuardIntegrationTest` asserts exactly this no-partial-state property.

**Index**: `members { workspaceId, role, status }` (D10) backs both the conditional flip predicate and the recount.

**Alternatives**: distributed/advisory lock service (new service, C2 fail); read-then-write (lost update); a multi-doc transaction reading the count (write-skew, as above); a per-workspace `activeAdminCount` doc with a guarded `$inc` (works, but adds a counter doc to keep consistent and is F03-workspace territory) — rejected in favour of the self-contained flip-recount-rollback.

---

## D5 — 403 envelope + 401/403 separation, preserving the F00 actuator contract *(FR-010/FR-014; review BE-1)*

**Decision**: Add a **`RestAccessDeniedHandler`** that renders the existing `{error,message}` envelope (matching `AuthExceptionHandler`) with HTTP 403, registered via `exceptionHandling(e -> e.accessDeniedHandler(...))` on the **`@Order(3)` main chain only**. The `@Order(1)` actuator chain and `@Order(2)` public chain are untouched, so:
- F01's scoped `HttpStatusEntryPoint(401)` for `/api/**` (authentication failure) stays — 401 vs 403 remain distinct (FR-010).
- The F00 `ActuatorPortTest` contract (`/actuator/**` → 404 on the public port, never 403) is preserved because the actuator chain is a separate `permitAll` filter chain and never reaches the access-denied handler.

The 403 body carries `{"error":"forbidden","message":"You do not have access to this action."}` — **no resource identifier, content, or existence signal** (FR-014), and is shape-identical across all 403s (verified by `DenyByDefaultContractTest`).

A `@PreAuthorize` denial throws `AccessDeniedException` from the method interceptor, which propagates to the `ExceptionTranslationFilter` **of the chain that handled the request** — the `@Order(3)` chain for `/api/internal/**` — so the scoped handler fires for method-security denials. `DenyByDefaultContractTest` asserts both: (a) a `@PreAuthorize`-triggered 403 renders the JSON `{error,message}` envelope byte-identically to other 403s; (b) an **anonymous** call to `/api/internal/**` still returns **401** (the F01 `HttpStatusEntryPoint`) and never reaches the access-denied handler — the 401/403 separation (FR-010) is the regression risk when adding the handler.

**Rationale**: a *scoped* access-denied handler is the safe way to get a JSON 403 envelope without the blanket handler CLAUDE.md warns breaks the actuator-on-public-port contract.

**Alternatives**: a global `@RestControllerAdvice` for `AccessDeniedException` — fragile because method-security denials propagate to the `ExceptionTranslationFilter`, not always the advice; and a blanket handler across chains breaks the actuator 404. Rejected.

---

## D6 — Server-side scoping primitive: the `assignments` collection + `AssignmentService` *(FR-023/FR-024/FR-025/FR-026/FR-027/FR-032; review BE-6)*

**Decision**: A new MongoDB collection **`assignments`** (`{ workspaceId, memberId, resourceType, resourceId }`) and an **`AssignmentService`** exposing:
- `assignedResourceIds(workspaceId, memberId, resourceType)` → the id set for scoping a **collection read** (FR-024);
- `isAssigned(workspaceId, memberId, resourceType, resourceId)` → the check for a **single-record read** (FR-025);
- `requireAssigned(...)` → throws `NotAssignedException` for a **scoped write** outside assignment (FR-032, the primitive later features call before confirm-slot / submit-feedback).

F02 demonstrates this end-to-end against a **real** `GET /api/internal/assignments` resource: an Admin creates assignments; a Hiring Manager/Interviewer sees only their own (FR-024) and gets an **empty set** with no assignments (FR-026).

**Indistinguishable not-found via a unified query path** *(FR-025/SC-015; review SEC-5/QA-5)*: a single-record fetch for H/I runs the **scoped** query `findOne({ workspaceId: ws, _id: id, memberId: caller })`. This returns empty for **both** "id does not exist" and "id exists but belongs to another member," so the handler takes the **identical** code path — one `throw notFound()` — for both cases. The response is therefore byte-identical in status (`404`) and body. The test (`AssignmentScopingIntegrationTest`) asserts **status + body byte-equality** for the two cases and that they share the single return path; **timing-indistinguishability is a design property of the shared query path, not a separately asserted (and flaky) test outcome** — the earlier "and timing" wording is corrected accordingly (contract + data-model).

**Cross-workspace scoping for A/R `?memberId=`** *(review SEC-7)*: the Admin/Recruiter `?memberId=` filter is always AND-ed with the caller's `workspaceId`; a `memberId` outside the caller's workspace yields an empty set, never cross-workspace rows (tested).

Later features reuse `AssignmentService` to scope requisitions/interviews; the **resource-side covering index** for those is each feature's plan obligation (FR-022), while F02 owns the `assignments` index now.

**`@Field(write = NON_NULL)` lesson** *(CLAUDE.md F01)*: `assignments` fields are always present (no nullable denormalised indexed field), so the F01 partial-index null-collision footgun does not apply here; noted so a later feature adding a nullable assignment field on `members` would follow the NON_NULL pattern.

**Rationale**: makes scoping real and testable today (constitution §II, no stubs) without waiting for requisitions/interviews; the same primitive binds the matrix contract for later features.

**Alternatives**: denormalised `assignedResourceIds[]` on `members` (re-touches the encrypted member doc, mixes concerns); deferring scoping entirely to later features (would make F02's US4 a stub). Rejected.

---

## D7 — Role-write validation against the closed set *(FR-031, SC-014; review SEC-1)*

**Decision**: `RoleService.changeRole()` accepts the target role as the `Role` enum; the controller binds the request body to `Role` so a non-canonical value (`SUPERADMIN`, `null`, case variant, array) fails Jackson enum binding → `400` via `RbacExceptionHandler` with no persisted change. Matching is exact (Jackson is case-sensitive for enums by default; we do **not** enable `ACCEPT_CASE_INSENSITIVE_ENUMS`). This guarantees FR-008's "unknown role" safety never has to rely on bad data already being stored.

**Rationale**: closes the write-path injection the spec review flagged; uses the framework's type system, no custom validator needed.

---

## D8 — Audit & bounded refusal logging *(FR-028/FR-029; review SEC-4/QA-4)*

**Decision**: Reuse F01's **`authAuditLog`** collection (member-keyed, non-PII). Extend `AuthEventType` with `ROLE_CHANGED` and `AUTHORIZATION_DENIED`, and add nullable `targetMemberId`, `oldRole`, `newRole` to `AuthAuditEvent`. `AuthAuditService.roleChanged(actor, target, old, new)` writes on every role change (FR-028).

**Bounded refusal audit** *(FR-028; review SEC-6)*: only **security-relevant** refusals are audited — role-management denials, last-Admin guard trips, and scoped cross-assignment attempts — **not** every routine 403. Concrete throttle rule: **at most one `AUTHORIZATION_DENIED` audit per `(memberId, eventType)` per fixed window (default 1/min)**, tracked by a **bounded in-memory structure keyed by the authenticated actor** (cardinality ≤ workspace member count, so not itself an amplification vector). The counter is **not persisted** (no DB write amplification) and resets on restart (acceptable — it is an anti-amplification heuristic, not a security control), mirroring F01's in-process `LoginAttemptService` limiter (single instance, C2-legal). The refusal audit never records the existence/identity of a not-found-masked resource (consistent with D5/D6).

**Logging hygiene** *(FR-029)*: authorization-decision logs carry only the actor's internal member id, the role, and the endpoint identifier — **never** an email, display name, candidate id, or scoped-record content (gate C3). Verified by `RbacLogPiiScanTest` (Logback `ListAppender` capture, asserts zero matches for the PII/candidate-id set), complementing the CI PII grep.

**Rationale**: keeps the audit erasure-friendly (non-PII ids survive F04 erasure, FR-036 pattern) and prevents the refusal-audit DoS the review raised.

---

## D9 — Frontend authorization: role guard + not-authorized, server remains the boundary *(US5, SC-008/SC-011; review QA-2)*

**Decision**: A `requireRole(...roles)` **`CanActivateFn`** factory authorizes the route and redirects to **`/not-authorized`** when the role is not permitted — never a 404 or blank. The guard is **defense-in-depth only**; the server is the boundary.

**Role source must be the async `me()` Observable, not a synchronous nullable snapshot** *(review FE-1, BLOCKER)*: F01's `AuthService.currentMember$` is a `BehaviorSubject` seeded `null` and populated only as a side effect of `me()`. On a cold direct-navigation to a guarded URL the snapshot can still be `null`, which would wrongly redirect a legitimate Admin. The guard therefore sources role via `auth.me()` (the established `auth.guard.ts` pattern, self-caching into `currentMember$`) and returns an `Observable<boolean | UrlTree>`:
`auth.me().pipe(map(m => roles.includes(m.role) ? true : router.createUrlTree(['/not-authorized'])), catchError(() => of(router.createUrlTree(['/login']))))`.
The `catchError → /login` branch is mandatory (a 401/no-session must go to login, not not-authorized). `roleGuard` runs **after** `authGuard` in the route's `canActivate` array for deterministic session-failure ordering; `me()` may `shareReplay` an in-flight request to avoid a double round-trip.

**403 interceptor + stale-role refetch (role-change-mid-session)** *(review FE-2, BLOCKER)*: F01's `authErrorInterceptor` handles **only 401**. After an Admin demotes a member, that member's stale `/me` role can still pass the guard while the API returns **403**. F02 **MODIFIES `auth.interceptor.ts`** to add a 403 branch that (a) navigates to `/not-authorized` (with the same public-route loop-guard as the 401 branch) and (b) **invalidates the cached member** (`currentMember$.next(null)`) so the next `me()` refetches the now-current role and the nav corrects itself. This is the graceful-degradation answer to role-change-mid-session.

**Nav role-gating lives in the real shell, not a phantom file** *(review FE-3)*: there is no `core/layout/` directory — the nav is **inline in `shell.component.ts`**. F02 gates nav links there via a `hasRole()` helper on `AuthService`. Nav hiding is **UX only**, never enforcement (the server + guard are the gates).

**`/not-authorized` is a top-level un-guarded sibling route** *(review FE-5)*, placed alongside `/login` in `app.routes.ts` (not under the guarded shell) so a redirected user cannot loop through `authGuard`.

**i18n mechanism** *(review FE-4)*: the codebase uses the **`i18n="@@id"` template-attribute** form (matching the existing `@@shell.*` ids) for component-template strings — the not-authorized page, nav links, and members-admin labels use that; **`$localize`** (tagged template) is used only for any strings constructed in TypeScript (e.g. a dynamic message in `members.service.ts`). RBAC screens are internal, so WCAG/Lighthouse gates are N/A (backlog F50/F51 note) — but i18n still applies.

**Testability**: the functional `CanActivateFn` + `inject(AuthService)` is unit-testable with a `TestBed` stub whose `me()` returns `of({role})`; every Jasmine test asserts the permitted role passes and **each** disallowed role redirects to `/not-authorized` (SC-011); the Playwright E2E asserts the underlying API still returns 403 when the guard is bypassed (SC-008, FR-013).

**Rationale**: matches the backlog's explicit route-guard unit-test requirement; keeps the security boundary on the server; closes the cold-load false-redirect and the mid-session-403 gaps the review found.

**Alternatives**: reading `currentMember$.value` synchronously (false redirect on cold load); server-rendered authorization only (worse UX, backlog requires the guard + not-authorized page). Rejected.

---

## D10 — MongoDB indexes (F00.1 pattern) *(review BE-2)*

**Decision**: New Mongock changeset **`ChangeUnit003_RbacIndexes`** (`order = "003"`, never renamed; native `createIndex`; targeted `dropIndex` rollback per CLAUDE.md):

| Collection | Index | Options |
|---|---|---|
| `members` | `{ workspaceId: 1, role: 1, status: 1 }` | non-unique — backs the last-Admin guard predicate (D4) and admin-count reads |
| `assignments` | `{ workspaceId: 1, memberId: 1, resourceType: 1 }` | scoped collection reads (D6) |
| `assignments` | `{ workspaceId: 1, resourceType: 1, resourceId: 1, memberId: 1 }` | **unique** — prevents duplicate assignment of the same resource to the same member |

The resource-side covering index (requisition/interview filtered by assigned member) is each later feature's plan obligation (FR-022) — an un-indexed scoped read is a deny-by-default-passing but F00.1-failing ship and must be caught in that feature's plan.

**Rationale**: follows F00.1; both new indexes are non-PII and the role/status index is non-unique (no partial-index null-collision risk). No TTL needed (assignments are durable).

---

## D11 — Tooling & versions (zero-download, gate C7)

**Decision**: cached **Gradle 9.4.0**, installed **JDK 21**, existing **Angular CLI 17.3**. No new backend or frontend dependency resolves at build time. No downloads.

---

## D12 — Test strategy *(constitution §VII; review QA-1..QA-6)*

Covers every §VII required test type and maps each SC to a concrete test. All integration tests extend **`BaseIntegrationTest`** (shared `@ServiceConnection` singleton `mongo:7`) and clean `members`/`assignments`/`authAuditLog` via `mongoTemplate.remove(new Query(), Type.class)` in `@BeforeEach` — **never `dropCollection`** (CLAUDE.md). Spring Boot 3.3 mocks use `@MockBean`. Authority is injected in slice tests via `spring-security-test` authority post-processors.

Pinned per the plan review (each "hard" claim has a deterministic, honest assertion):

| SC / FR | Test |
|---|---|
| SC-001/SC-002, FR-012 | `DenyByDefaultContractTest` — role × endpoint matrix → expected 200/403. **RO-write coverage is inventory-derived** (QA-2): the test reads the same reflection sweep as the inventory test and asserts every POST/PUT/PATCH/DELETE handler returns 403 for READ_ONLY, so a mistaken `hasAnyRole(...,'READ_ONLY')` on a write is caught and coverage self-extends. Also asserts anonymous `/api/internal/**` → 401 (not 403) and `@PreAuthorize`-403 → JSON envelope (D5). |
| SC-003, FR-007 | `RoleChangeIntegrationTest` — capture the `cad_session` cookie once, change role, **reuse the identical cookie** (no re-login, no clock advance past expiry) and assert the new role governs. Re-login is explicitly forbidden in the test. |
| FR-002 | `StaleRoleClaimIntegrationTest` — issue session as ADMIN, demote in DB, replay the **same** signed cookie → 403 on an admin-only endpoint; assert `Session.role` snapshot still ADMIN (D3). The signature is valid (real key) so this tests claim-vs-DB precedence, not signature rejection. |
| SC-004/SC-012/SC-013, FR-005 | `LastAdminGuardIntegrationTest` — single-actor refusal; **concurrent** double-demotion via a shared `CountDownLatch` (no `Thread.sleep`), looped N≥20 with `remove()` cleanup between iterations. Assertions on **final DB state**: `count(role:ADMIN,status:ACTIVE) ≥ 1` (never zero) AND exactly one PATCH 2xx + one `409 last_admin` (no torn state). Concurrent role-change to one member (SC-012) → exactly one submitted value persists, both audited. Refused **deactivation** leaves member ACTIVE + sessions un-revoked (D4 ordering). |
| SC-005, FR-025/FR-032 | `AssignmentScopingIntegrationTest` — scoped read AND write blocked outside assignment (direct API); cross-workspace `?memberId=` → empty set (SEC-7). |
| SC-006 | same — scoped member with no assignments → empty set (not full workspace). |
| SC-007, FR-006/FR-008 | `RoleChangeIntegrationTest` named cases — **self-elevation** by an Admin on their own record → 403; self-demotion allowed only if another Admin remains (else 409). **FR-008**: a member with `role == null`/unmapped is denied on every role-gated endpoint, never treated as Admin. Plus the stale-claim (above), undeclared-endpoint (inventory self-test), and scoped-id bypass (above). |
| SC-008/SC-011 | Jasmine `role.guard.spec.ts` — stubs `auth.me()` as `of({role})`; permitted role passes, **each** disallowed role → `/not-authorized`. Playwright `rbac.spec.ts` — API still 403 when the guard is bypassed. |
| SC-009, FR-029 | `RbacLogPiiScanTest` — `ListAppender` capture, zero PII/candidate-id/resource-content across role-change + denial paths; the existing CI PII grep is the CI gate of record (DoD). |
| SC-010, FR-022 | `RbacEndpointInventoryTest` — `RequestMappingHandlerMapping` sweep, **allow-list by exclusion**, method-or-class `@PreAuthorize` resolution, with a **self-test fixture** (a deliberately-undeclared dummy internal handler asserted flagged) so the test's own failure path is proven. |
| SC-014, FR-031 | `RoleValidationContractTest` — non-canonical role value → 400, no persist. |
| SC-015, FR-025 | `AssignmentScopingIntegrationTest` — out-of-assignment existing id vs non-existent id → **status + body byte-equal** (shared single not-found return path, D6); timing is a design property of the unified query, not an asserted outcome. |
| Unit (Principle VII) | `RoleServiceTest` (Mockito) — last-Admin flip/recount/rollback branches, self-elevation guard, role-validation; `AssignmentServiceTest` — `assignedResourceIds`/`isAssigned`/`requireAssigned` (the reusable scoped-write primitive the contract claims is unit-tested). |

E2E: Playwright `rbac.spec.ts` — Admin changes a member's role in the directory; the member (or a non-admin) is redirected to `/not-authorized` on a guarded route and the API independently returns 403.

---

## Resolved unknowns summary

| Unknown | Resolution |
|---|---|
| Enforcement mechanism / new dependency | Spring method security already on classpath; zero new deps (D1) |
| Deny-by-default fail-closed | Mandatory `@PreAuthorize` + build-time inventory test (D2) |
| Role change effective next request | Principal role from persisted member, not session snapshot — one-line F01 fix, zero added queries (D3) |
| Last-Admin lockout under concurrency | Flip → recount → conditional-rollback (broker-free, never zero admins); guard before deactivation revoke (D4) |
| 403 envelope without breaking actuator contract | Scoped `RestAccessDeniedHandler` on the main chain only (D5) |
| Server-side scoping made real today | `assignments` collection + `AssignmentService`, demonstrated against a real resource (D6) |
| Role-write injection | Enum binding, exact match, no case-insensitive enums (D7) |
| Audit + refusal-volume DoS | Reuse `authAuditLog`, bounded security-relevant refusals, no PII/candidate-id in logs (D8) |
| Frontend guard + not-authorized | `requireRole` CanActivateFn + real not-authorized page; server stays the boundary (D9) |
| Indexes | ChangeUnit003: members{workspaceId,role,status} + assignments indexes (D10) |
| Tooling | Cached Gradle 9.4.0 / JDK 21 / Angular 17.3, zero downloads (D11) |
| Test strategy | Inventory reflection, concurrency latch, scoping indistinguishability, PII scan, guard units, E2E (D12) |

All NEEDS CLARIFICATION resolved. Proceed to Phase 1 design artifacts.
