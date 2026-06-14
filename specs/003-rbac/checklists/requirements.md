# Specification Quality Checklist: Role-Based Access Control (RBAC)

**Purpose**: Validate specification completeness and quality before proceeding to planning
**Created**: 2026-06-14
**Feature**: [spec.md](../spec.md)

## Content Quality

- [x] No implementation details (languages, frameworks, APIs)
- [x] Focused on user value and business needs
- [x] Written for non-technical stakeholders
- [x] All mandatory sections completed

## Requirement Completeness

- [x] No [NEEDS CLARIFICATION] markers remain
- [x] Requirements are testable and unambiguous
- [x] Success criteria are measurable
- [x] Success criteria are technology-agnostic (no implementation details)
- [x] All acceptance scenarios are defined
- [x] Edge cases are identified
- [x] Scope is clearly bounded
- [x] Dependencies and assumptions identified

## Feature Readiness

- [x] All functional requirements have clear acceptance criteria
- [x] User scenarios cover primary flows
- [x] Feature meets measurable outcomes defined in Success Criteria
- [x] No implementation details leak into specification

## Notes

- **No [NEEDS CLARIFICATION] markers**: The one genuinely scope-shaping ambiguity (single vs. multiple roles per member) was resolved with a documented reasonable default in Assumptions ("one role per member") rather than blocking on the user, because F01 already attached a single role to the session and the constitution frames "Five roles" as a fixed set. Reverse if the stakeholder wants multi-role.
- "HTTP 401/403" appear as observable contract outcomes a tester verifies (the distinction between unauthenticated and unauthorized), not as implementation prescriptions — HTTP/REST is already fixed by the constitution and backlog. Treated as testable behaviour, consistent with the F01 spec's handling.
- **No-stubs framing (constitution §II)**: The spec explicitly carves the demonstrable-today increment (role administration, deny-by-default enforcement, server-side scoping primitive against existing endpoints) from the forward contract bound by later features (F12-F51). The Permission Matrix marks "Later" rows so nothing reads as shipped that is not. This avoids the trap of a foundational feature appearing to gate endpoints that do not yet exist.
- Scope is bounded against neighbours: identity/session (F01), workspace config + first-admin bootstrap (F03), and the rich requisition/interview entities (later features) are referenced but explicitly out of scope.
- All items pass on initial authoring. Multi-role sub-agent review (below) ran per the user's explicit request and constitution §VI before finalisation.

## Multi-Role Sub-Agent Review (constitution §VI)

**Conducted**: 2026-06-14 | **Roles (4)**: Security/GDPR Lead, Backend/DevOps Lead, QA Lead, Business Analyst | **Outcome**: all four **APPROVE-WITH-CHANGES**, zero blockers. All findings applied to the spec (or routed to the backlog/plan as noted).

Findings applied to the spec:

- **Security/GDPR**: validate role-write against the closed set (FR-031, SC-014); tightened self-elevation vs. self-demotion (FR-006 + edge case); scoping extended to state-changing writes / IDOR on mutation (FR-032, SC-005); bounded "security-relevant" refusal audit, anti-amplification (FR-028); atomic last-Admin guard + deactivation-before-revocation ordering (FR-005, SC-013); 403/404 indistinguishability to kill the existence oracle (FR-025, SC-015); authorization decided from persisted role not the session claim (FR-002/FR-007); no candidate ids in authz logs (FR-029).
- **Backend/DevOps**: preserve F01's scoped 401 entry point + F00 actuator-404 contract — no blanket access-denied handler (planning note); authority from the per-request member doc not the JWT claim (FR-007); last-Admin guard atomic-not-count-then-write (FR-005 + planning note); deactivation guard co-located with the mutating write (FR-005); two-layer SC-010 enforcement = runtime deny-by-default + build-time inventory test (planning note); F02 owns the assignment-side index, resource-side index bound to the matrix contract (planning note); `@Field(write = NON_NULL)` lesson for nullable denormalised index fields (planning note); explicit no-in-process-TTL-cache assumption.
- **QA**: objective automated inventory test for SC-010; route-guard per-role Jasmine unit tests as acceptance criteria (US5-AC5, SC-011 — closes the backlog-called-out gap); rebound Recruiter "operational action" to a today-testable negative + forward contract (US3-AC1, SC-002); concurrent role-change torn-state (US1-AC6, SC-012); concurrent last-Admin race (US1-AC7, SC-013); pinned the testable property of FR-014; softened the unfalsifiable mid-session edge bullet.
- **Business Analyst**: confirmed all six backlog stories (US-F02-1..6) are represented and the matrix matches backlog F50/F51 and the constitution; relabelled the "Any state-changing action" meta row so it cannot be mis-mapped (FR-016/SC-010); added the Read-only-unscoped-PII stakeholder-flagged assumption (C3); clarified Interviewer own-interview candidate context (FR-020); FR-005 deactivation-ownership wording; one-role reversibility note; export-rule footnote tying F02 to the F50 matrix.

Routed to the **backlog** (not the spec): **B1** — backlog US-F02-1 says "assign and **revoke** roles," but under one-role-per-member there is no revoke-to-null state; the spec models this as "assign or change." Backlog annotated to read "assign and change roles" to avoid implying a revoke-to-no-role workflow.

Routed to **plan.md** (correctly deferred, set up by Planning Notes): exact `@PreAuthorize` vs. authority-matcher vs. `AuthorizationManager` encoding; the concrete `findAndModify` last-Admin filter; the inventory-test reflection mechanism; assignment data-model shape and exact index keys; audit-collection reuse-vs-sibling decision; final 403-vs-404 status choice; canonical error-envelope shape.

All checklist items remain ✅ after the review. Spec is ready for `/speckit.plan` (or `/speckit.clarify` if the two stakeholder-flagged decisions — one-role-per-member and Read-only PII scope — should be confirmed first).

## Multi-Role Sub-Agent Review — PLAN (constitution §VI)

**Conducted**: 2026-06-14 | **Roles (4)**: Security/GDPR, Backend/DevOps, QA, Front-End | **Outcome**: all four **APPROVE-WITH-CHANGES**, no REJECT. All findings reviewers verified against the **real F01 source**; all applied to `plan.md` / `research.md` / `data-model.md` / `contracts/rbac-api.md`.

Two **BLOCKERs** (each found independently by ≥1 reviewer) — resolved:
- **Last-Admin guard was not implementable** (Security F-2 + Backend BLOCKER-2): a single-document MongoDB update filter cannot reference *other* documents, and a read-count transaction is write-skew-vulnerable. **research D4 replaced** with a broker-free **flip → recount → conditional-rollback** sequence proven to never reach zero Admins under concurrency (tested over N≥20 latch iterations on final DB state).
- **Inventory test would red-fail CI on F01** (Backend BLOCKER-1): `/api/internal/auth/me` + `/logout` carry no method-security annotation. **`AuthController` MODIFIED** to add `@PreAuthorize("isAuthenticated()")`; the inventory test accepts authenticated-any-role as a valid declaration (research D2).

**Security/GDPR** (verified D3 bug, actuator-chain isolation, zero-dep against `build.gradle`): make the D3 role-source change structural + neutralise the JWT/session role snapshots as authorization inputs, with a gating `StaleRoleClaimIntegrationTest` (FR-002); pin the deactivation guard→revoke ordering as an F03-binding contract (D4); replace SC-015 "timing" with a unified-query shared not-found path (D6); concrete refusal-audit throttle rule (1/min per actor+type, bounded in-memory) (D8); cross-workspace `?memberId=` IDOR test (contract); bound the Read-only unscoped-PII exception as an **F51 forward contract** (no Read-only PII endpoint ships in F02).

**Backend/DevOps** (verified zero new deps, `@EnableMethodSecurity` already on, `validate()` line-86 snapshot bug, F01 footguns): inventory-test enumeration via `RequestMappingHandlerMapping` + method-or-class resolution + allow-list-by-exclusion + self-test fixture (D2); confirmed D3 "zero added queries" and no consumer breakage; `roleChanged(...)` needs a new `AuthAuditService` overload (`record(...)` can't carry target/old/new); new RBAC integration tests `@Import` the `MutableClock` config and clean `Assignment.class` in `@BeforeEach`.

**QA** (built SC→test traceability, all 15 SCs mapped): added gating `StaleRoleClaimIntegrationTest` (FR-002) and an FR-008 null/unknown-role case; made RO-write coverage **inventory-derived** (every write handler → RO 403); pinned SC-003 to same-cookie-no-relogin; pinned SC-013/SC-012 to **final-DB-state** asserts over a shared latch (not observed-200-count); scoped SC-015 to status+body byte-equality; inventory-test self-test fixture; explicit `RoleServiceTest`/`AssignmentServiceTest` unit tests (Principle VII); corrected the `@Order(2)`→`@Order(3)` factual error in spec/data-model.

**Front-End** (verified `/me` returns role, `currentMember$` null-seed, nav inline in `shell.component.ts`, 401-only interceptor, `i18n="@@id"` convention): guard sources role via `auth.me()` Observable with `catchError→/login`, not a nullable snapshot (no cold-load false redirect); **`auth.interceptor.ts` MODIFIED** to add a 403 branch → `/not-authorized` + invalidate the cached member (role-change-mid-session); fixed the phantom `core/layout/nav.component.ts` → role-gate nav inside `shell.component.ts`; `/not-authorized` as a top-level un-guarded sibling; corrected i18n mechanism (template `i18n="@@id"` vs TS `$localize`).

Deferred to **tasks.md** (implementation/test authoring, set up by the artifacts): the `AuthAuditService.roleChanged` overload + `AuthAuditEvent` fields; `Assignment` POJO non-null construction; CI PII-grep traceability for the new authz log lines; writing the Jasmine/Playwright cases; the members-admin + not-authorized components.

**Net**: constitution gates C1–C7 remain PASS (zero new deps, no topology change, broker-free guard). Plan + Phase-1 artifacts are ready for `/speckit.tasks`.

## Multi-Role Sub-Agent Review — TASKS (constitution §VI)

**Conducted**: 2026-06-14 | **Roles (3)**: Backend/DevOps, QA, Tech-Lead/Planner | **Outcome**: all three **APPROVE-WITH-CHANGES**. Reviewers verified every MODIFIED task against the real source. 46 tasks (T001–T046), all applied/fixed.

**BLOCKER (Backend)** — resolved: the inventory test (T029) would have **red-failed the build** on `HealthTestController.slow()` (`/api/internal/slow`, `@Profile("test")`, un-annotated) — a real handler registered under the test profile the inventory test runs in. **T012** now also annotates it `@PreAuthorize("isAuthenticated()")`; **T029** excludes framework handlers (`/error`/`BasicErrorController`, `org.springframework.*`); **T032** names it in the closure sweep.

**MAJOR (QA)** — resolved: (1) **T036** scoped-WRITE assertion was under-specified → now pins the POST/DELETE verb, the same indistinguishable not-found masking as the read, a zero-mutation post-call DB re-read, and exercises **both** REQUISITION (HM) and INTERVIEW (Interviewer) enum paths; (2) **SC-007** (0 escalations) had no task naming its four vectors → now cross-referenced in T021 (self-promotion), T031 (stale/tampered claim), T029 (undeclared endpoint), T036 (scoped-id bypass), with an SC-007 checklist added to the §VI closer T046.

**MINOR** — applied: refusal-audit anti-amplification negative test (T030); MutableClock fixed-instant determinism + remove() between byte-equality sub-cases (T021/T031/T036); exact generated index-name assertions (T014); `eventCode→outcome` mapping note (T010); T032 depends-on-T029/T030-red-first; T043 made conjunctive (unit test AND CI grep are both SC-009 gates); concrete file paths in T032/T034.

**Format (Planner)** — FAIL→PASS: added missing `[P]` markers to **T024/T037/T041** (independent single-file tasks). Verified: sequential unique IDs T001–T046, phase-label discipline clean (no `[US#]` on Setup/Foundational/Polish; present on all story tasks), **no two `[P]` tasks edit the same file**, plan↔tasks file map complete and bidirectional, MVP=Setup+Foundational+US1 is genuinely demonstrable, frontend-core correctly foundational (US1's `/admin/members` route consumes `roleGuard`), and the §VI closer (T046) is present.

**Net**: tasks.md is execution-ready. Constitution gates unaffected (zero new deps, broker-free guard, tests-first per §VII). MVP path: Setup → Foundational → US1.

## Multi-Role Sub-Agent Review — IMPLEMENTATION (constitution §VI, T046)

**Conducted**: 2026-06-14 | **2 loops** | Reviewers verified the actual code against the spec.

**Verification status before review**: backend full suite GREEN (F01 + 62 F02 RBAC tests, incl. the 20-iteration concurrent last-Admin latch and scoping indistinguishability); frontend `ng test` 6/6 + `ng build` clean. Docker/Testcontainers available locally (note: first multi-class container run can throw a transient `GenericContainer` class-init error — re-run).

**Loop 1** (Security/GDPR, Backend, QA+Frontend) — all **APPROVE-WITH-CHANGES**, no BLOCK. Findings applied:
- **MAJOR (Security/Backend)**: `AssignmentService.create` did not validate the target member belonged to the caller's workspace — an Admin could stamp a foreign-workspace member id into an assignment. Fixed: same-workspace guard → indistinguishable 404; added regression test `adminCannotAssignToAForeignWorkspaceMember`.
- **MAJOR (Backend)**: `DELETE .../{memberId}/assignments/{id}` ignored `{memberId}`. Fixed: `delete(workspaceId, memberId, assignmentId)` scopes by member (404 on mismatch).
- **MAJOR (QA)**: concurrent-role-change audit asserted `>=1` → tightened to `isEqualTo(2)` (SC-012 both-audited).
- **MINOR**: `RbacExceptionHandler` returned `invalid_role` for any unparseable body → now only for a `Role` enum bind failure (else `bad_request`); `RoleService` admin-branch sets `updatedAt` for object/DB consistency; `AssignmentScopingIntegrationTest` now covers the INTERVIEW resource-type indistinguishable-404 (SC-015 both paths); `RbacLogPiiScanTest` broadened to scan message + args + MDC + throwable; refusal-audit "routine 403 → zero rows" overclaim corrected (in F02 every internal-endpoint authz denial is security-relevant; the bounded blanket audit satisfies FR-028).

**Loop 2** (Security+Backend, QA) — both **APPROVE**, no further changes. The cross-workspace fix is complete (no remaining foreign-stamp path; `MemberRepository` injection is non-circular), delete scoping correct, exception discriminator correct (`ResourceType` bind failure → `bad_request`), the `isEqualTo(2)` assertion correct and non-flaky, and the broadened PII scan sound. Review converged in 2 of the allowed 3 loops.

**SC-007 escalation checklist** (all four vectors present, red-before-green): self-promotion (`RoleServiceTest.selfElevation_isRejected` + `DenyByDefaultContractTest` non-admin 403), stale/tampered claim (`StaleRoleClaimIntegrationTest`), undeclared endpoint (`RbacEndpointInventoryTest` self-test fixture), scoped-id bypass (`AssignmentScopingIntegrationTest`).

**Honesty note**: T040 (Playwright E2E) is authored but NOT executed — Playwright is not installed (constitution §X zero-download), mirroring F01's deferred E2E. Its automated equivalents (the deny-by-default contract tests + the role-guard Jasmine spec) pass. The manual browser quickstart steps were not run; their automated equivalents are green.
