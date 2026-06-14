# Feature Specification: Role-Based Access Control (RBAC)

**Feature Branch**: `003-rbac`
**Created**: 2026-06-14
**Status**: Draft
**Input**: User description: "update main branch from origin, checkout new feature branch from main. take the first unimplemented task from backlog. review the prepared spec with appropriate sub-agents" → resolved to **F02 — Role-Based Access Control (RBAC)** (first unimplemented item after F00 scaffold and F01 authentication in the delivery sequence; CLAUDE.md notes F01 attached the role to the session with "enforcement is F02").

## Overview

F01 established **who** a workspace member is and attached their assigned role to the session. F02 decides **what each role is allowed to do**. It turns the five roles already carried on the session — **Admin, Recruiter, Hiring Manager, Interviewer, Read-only** — into enforced permissions on every internal endpoint, so that a member can only reach the actions and data their role permits.

F02 delivers three things as one complete, demonstrable increment:

1. **Role administration** — an Administrator can assign and change a member's role; the change takes effect on that member's next request.
2. **Deny-by-default enforcement** — every internal endpoint declares a minimum required role; any request from a member below that role is refused (HTTP 403), distinct from the unauthenticated refusal (HTTP 401) F01 owns. A new internal endpoint is protected unless it is positively granted to a role.
3. **Server-side data scoping** — for roles whose access is limited to "their own" data (Hiring Manager → own requisitions; Interviewer → own interviews), the filtering is enforced on the server, never by hiding data in the browser.

The **canonical permission matrix** (which role may perform which action) is the contract of this feature. Where the surface that an action lives on does not yet exist (scheduling, templates, pipeline, scorecards arrive in later features), F02 defines the binding permission rule that the owning feature MUST enforce when it lands, and enforces the matrix today on every internal endpoint that already exists (member administration, invitation, and role management).

Candidates are never workspace members and have no role; the public candidate surface (private per-candidate links) is outside RBAC entirely and is unaffected by this feature.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Administrator manages member roles (Priority: P1)

An Administrator opens member administration, sees each workspace member's current role, and assigns or changes a member's role (for example, promoting a Recruiter to Admin or moving a member to Read-only), so that access rights match each person's responsibilities and can be corrected as the team changes.

**Why this priority**: Role assignment is the control surface for the entire feature — without it, roles are static and access cannot be governed. It is fully demonstrable end to end today because Members already exist from F01. It is the foundation every other story depends on.

**Independent Test**: Sign in as an Administrator, change a member's role from Recruiter to Read-only, and confirm the member's role is updated and persisted; confirm a non-Administrator cannot reach the role-management action at all. Fully demonstrable on its own against seeded members.

**Acceptance Scenarios**:

1. **Given** an Administrator and an existing member, **When** the Administrator assigns a different role to that member, **Then** the member's role is updated, persisted, and reflected on the next read of that member.
2. **Given** a member with an active session whose role is changed by an Administrator, **When** that member next makes a request, **Then** the new role governs the request (the change takes effect on the next request, consistent with F01's per-request server-side check).
3. **Given** a non-Administrator (Recruiter, Hiring Manager, Interviewer, or Read-only), **When** they attempt to assign or change any member's role, **Then** the request is refused (HTTP 403) and no role is changed.
4. **Given** the workspace has exactly one Administrator, **When** an attempt is made to remove or downgrade that last Administrator's role, **Then** the change is refused with a clear message so the workspace cannot be locked out of administration.
5. **Given** any role change, **When** it is applied, **Then** an audit entry is recorded (actor, target member, old role, new role, timestamp) using non-PII identifiers only.
6. **Given** two Administrators concurrently assigning different roles to the same member, **When** both requests are processed, **Then** the member ends in exactly one of the two assigned roles (no torn/merged state), the persisted role is internally consistent, and **both** attempts are recorded in the audit log (verified by a concurrent integration test).
7. **Given** a workspace with exactly two Administrators, **When** two demotions (one per Admin) are submitted concurrently, **Then** at least one is refused so the workspace retains at least one active Administrator (0 zero-Admin outcomes), verified by a concurrent integration test.

---

### User Story 2 - Deny-by-default enforcement on internal endpoints (Priority: P1)

Every internal/workspace endpoint enforces the minimum role required for the action; a member whose role is below that minimum is refused, so that an authenticated-but-unauthorized member can never perform an action or read data outside their role.

**Why this priority**: Authentication (F01) only proves identity; without per-role enforcement, every signed-in member could do anything. This is the core security guarantee of F02 and the constitution's §VIII RBAC mandate. It is P1 alongside Story 1 because role administration is meaningless unless the assigned role is actually enforced.

**Independent Test**: With seeded members of each role, call a write endpoint (e.g. invite a member, change a role) as a disallowed role and observe a 403 with no state change; call it as the allowed role and observe success. Verifiable with automated API/contract tests without any UI.

**Acceptance Scenarios**:

1. **Given** an authenticated member whose role is below the minimum required by an internal endpoint, **When** they call that endpoint, **Then** the system refuses with HTTP 403 and performs no state change and returns no protected data.
2. **Given** an authenticated member whose role meets or exceeds the endpoint's minimum, **When** they call that endpoint, **Then** the request is processed normally.
3. **Given** a newly added internal endpoint that has not been positively granted to any role, **When** any non-Administrator calls it, **Then** it is refused by default (deny-by-default), so omission cannot accidentally expose an action.
4. **Given** an unauthenticated request to an internal endpoint, **When** it is made, **Then** the existing F01 behaviour applies (HTTP 401), and the 403 authorization refusal is reserved for authenticated-but-unauthorized members (the two are distinct and not conflated).
5. **Given** a Read-only member, **When** they attempt any state-changing action (create, update, delete), **Then** the request is refused (HTTP 403) while permitted read actions succeed.

---

### User Story 3 - Least-privilege access per role (Priority: P2)

Each non-Administrator role is limited to exactly the actions its responsibilities require — a Recruiter can run operational work (scheduling, templates, dashboard) but not workspace-level configuration; an Interviewer can only see their own interviews and submit feedback; a Read-only user can view but never act — so that the principle of least privilege holds across the product.

**Why this priority**: This operationalizes the permission matrix for the day-to-day roles. It is P2 because Stories 1 and 2 already deliver the enforcement mechanism and the highest-value gate (Admin-only actions); per-role tuning builds on that mechanism and partly binds to surfaces delivered by later features.

**Independent Test**: For each role, attempt one in-scope action (succeeds) and one out-of-scope action (refused 403) against the endpoints that exist today, and confirm the documented matrix rule for every role/action pair via contract tests; later features inherit the same matrix.

**Acceptance Scenarios**:

1. **Given** a Recruiter, **When** they attempt a member-administration or role-management action (the workspace-level actions that exist today), **Then** it is refused (HTTP 403); **When** an operational endpoint delivered by a later feature is added, its Recruiter-permitted matrix rule is verified by that feature's contract test against this matrix (forward contract, FR-022).
2. **Given** an Interviewer, **When** they attempt to read pipeline or workspace data beyond their own interviews/feedback, **Then** it is refused; **When** they read their own assigned interviews, **Then** it succeeds.
3. **Given** a Read-only member, **When** they read permitted views (pipeline, analytics), **Then** it succeeds; **When** they attempt any action or export reserved to higher roles, **Then** it is refused (the export/action endpoints are delivered by F50/F51; this rule is verified by those features' contract tests against this matrix, FR-022).
4. **Given** the canonical permission matrix, **When** any internal endpoint is added by a later feature, **Then** its required role is declared against this matrix and verified by a contract test (no endpoint ships without a declared minimum role).

---

### User Story 4 - Server-side scoping to a member's own data (Priority: P2)

A Hiring Manager sees only the requisitions (and their candidates and interviews) assigned to them, and an Interviewer sees only their own interviews — enforced on the server — so that even a direct API call cannot retrieve another team's candidate data.

**Why this priority**: Least privilege for these roles is not just "which endpoint" but "which rows": a Hiring Manager reaching the right endpoint must still be confined to their own assignments. Client-side hiding is not security. It is P2 because the scoped resources (requisitions, interviews) are delivered by later features; F02 establishes the assignment relationship and the binding server-side scoping rule, demonstrable today against the member surface and enforced per-resource as each arrives.

**Independent Test**: As a Hiring Manager, request a collection of scoped resources and confirm only assigned items return; attempt to fetch a specific item outside the assignment by direct ID and confirm it is refused (not silently included), proving the filter is server-side, not a UI convenience.

**Acceptance Scenarios**:

1. **Given** a Hiring Manager with an assigned set of requisitions, **When** they list candidates/interviews, **Then** only items within their assigned requisitions are returned (server-side filtered).
2. **Given** a Hiring Manager, **When** they request a specific record outside their assignment by direct identifier, **Then** the request is refused (HTTP 403 or not-found semantics) rather than returning the record.
3. **Given** an Interviewer, **When** they list interviews, **Then** only interviews on which they are a participant are returned; requests for others' interviews are refused.
4. **Given** a Hiring Manager or Interviewer with no current assignments, **When** they list scoped resources, **Then** an empty set is returned (never the full workspace set as a fallback).
5. **Given** any scoped read, **When** it executes, **Then** the scoping is applied in the data query on the server (verified by a direct-API test that bypasses the UI), not by filtering already-returned data in the client.

---

### User Story 5 - Frontend authorization experience (Priority: P3)

A signed-in member sees only the navigation and routes their role permits, and when they reach a page they may not access they are sent to a clear "not authorized" page rather than a broken or missing one, so that the interface matches their permissions and unauthorized access fails gracefully.

**Why this priority**: The server is the security boundary (Stories 2-4); the frontend guard is a usability and defense-in-depth layer. It is P3 polish that the Definition of Done still requires, but it is never the sole gate.

**Independent Test**: Sign in as a role without access to a protected route, navigate to it directly, and confirm a redirect to `/not-authorized` (not a 404 or blank screen); confirm navigation entries for disallowed areas are hidden; confirm the server still refuses the underlying API call independently of the guard.

**Acceptance Scenarios**:

1. **Given** a member whose role lacks access to a route, **When** they navigate to it (including by typing the URL directly), **Then** they are redirected to a dedicated `/not-authorized` page, not a 404 or error page.
2. **Given** a member, **When** the application renders navigation, **Then** entries for areas their role cannot access are not shown.
3. **Given** the frontend route guard is bypassed or disabled, **When** the corresponding API call is made, **Then** the server still refuses it (the guard is defense-in-depth, not the security boundary).
4. **Given** all `/not-authorized` and role-related UI strings, **When** they render, **Then** they use the project's localization mechanism (no hard-coded user-facing text).
5. **Given** a route guard for a role-restricted route, **When** it is exercised in a frontend unit test, **Then** the permitted role(s) pass and **each** disallowed role is redirected to `/not-authorized` (one assertion per role/route pair), independent of any backend call.

---

### Edge Cases

- **Last-Administrator lockout**: An attempt to delete, deactivate (via F01), or downgrade the final Administrator is refused so a workspace cannot lose all administrative access.
- **Self-role-change**: No member can raise their own privilege (self-elevation is forbidden); an Administrator may demote/laterally-change their own role only if they are not the last Administrator (FR-005/FR-006). Non-Administrators cannot change any role, including their own.
- **Role change mid-session**: A role change takes effect on the member's **next request** (FR-007/SC-003); a request already authorized and in flight is not retroactively re-evaluated. No separate mid-flight cancellation guarantee is made or tested.
- **Unknown or missing role**: A member record with no role or an unrecognized role is treated as the least-privileged (effectively denied all role-gated actions), never as Admin or as a wildcard.
- **Hiring Manager / Interviewer with no assignments**: Scoped queries return an empty set, never the full workspace set.
- **Direct API access bypassing the UI**: Authorization and scoping are enforced server-side, so a crafted direct request cannot exceed the role or its data scope even if the UI would have hidden the control.
- **Privilege via tampered session**: A member cannot alter the role claim in their session to gain a higher role — the server treats the persisted member record (not client-supplied role) as authoritative, and F01's token-integrity protections still apply.
- **Candidate surface unaffected**: Public candidate endpoints carry no role and are never subject to RBAC; adding RBAC must not gate any declared candidate path.
- **Deactivated member**: A deactivated member (F01) is already refused at authentication; RBAC does not need to (and must not) become a second, weaker gate that lets them through.
- **Concurrent role changes**: Two Administrators changing the same member's role concurrently resolve to a single consistent final role with both attempts audited.

## Requirements *(mandatory)*

### Functional Requirements

#### Role model & administration

- **FR-001**: System MUST recognize exactly five workspace member roles — **Admin, Recruiter, Hiring Manager, Interviewer, Read-only** — as the complete role set; no other role value is valid.
- **FR-002**: System MUST store each member's assigned role on the member record (persisted in MongoDB) as the authoritative source of the member's permissions; the role carried on the session (F01) is derived from it and re-validated server-side per request. A request's authorization decision MUST be made from the **persisted member role**, not the role value carried in the session credential, so a stale or mismatched session-role claim never grants access beyond the current persisted role (verified by an adversarial test that mutates the claim independent of the database).
- **FR-003**: System MUST allow an Administrator to assign or change the role of any member in the workspace.
- **FR-004**: System MUST restrict role assignment/change to Administrators only; any non-Administrator attempt MUST be refused (HTTP 403) with no change applied.
- **FR-005**: System MUST prevent the removal, downgrade, or deactivation of the **last remaining active Administrator** of a workspace, so the workspace cannot be locked out of administration; the attempt MUST be refused with a clear, non-technical message. This guard MUST be evaluated **atomically within the same write** that changes role or status (a conditional/compare-and-set that succeeds only if at least one *other* active Administrator remains), NOT a read-count-then-write, so concurrent demotions/deactivations of distinct Administrators cannot both succeed and strand the workspace with zero active Administrators. When the action is deactivation, the guard MUST be evaluated before any F01 deactivation/session-revocation effect is applied, so a refused deactivation produces no partial state change. (F02 contributes only this last-Admin guard to the deactivation action owned by F01/F03; it does not itself perform deactivation.)
- **FR-006**: System MUST prevent any member from raising their own privilege (no self-elevation); all role *increases* originate solely from a **different** Administrator acting on the target's record. An Administrator MAY change their own role only to a non-higher role and only subject to the last-Administrator guard (FR-005). Non-Administrators MUST NOT change any role, including their own (FR-004).
- **FR-007**: System MUST make a role change effective on the affected member's **next request** without requiring an external session store, cache, or broker (single-instance + MongoDB only), consistent with F01's per-request server-side evaluation. The authority used for the authorization decision MUST be derived from the **persisted member record** loaded during F01's existing per-request active-status check, NOT from the role claim embedded in the session credential — so a stale (un-tampered) credential carrying a now-outdated role cannot grant the old privilege.
- **FR-008**: System MUST treat a member record with a missing or unrecognized role as the least-privileged (denied all role-gated actions), never defaulting to Admin or to an allow-all behaviour.
- **FR-031**: System MUST reject a role-assignment request whose target role is not exactly one of the five canonical values (FR-001), returning a validation error and persisting no change; the role field MUST NOT be settable to any value outside the closed set, and matching MUST be exact (no case-insensitive or partial acceptance), so FR-008's safety does not depend on bad data already being stored.

#### Enforcement (deny-by-default)

- **FR-009**: System MUST enforce, on every internal/workspace endpoint, a declared **minimum required role**, refusing any authenticated member below that minimum with **HTTP 403**, performing no state change and returning no protected data.
- **FR-010**: System MUST keep the authorization refusal (HTTP 403, authenticated-but-unauthorized) distinct from the authentication refusal (HTTP 401, unauthenticated) owned by F01; the two MUST NOT be conflated.
- **FR-011**: System MUST be **deny-by-default**: an internal endpoint with no positively-declared role grant is refused for all non-Administrator members, so that adding an endpoint without declaring its permission cannot accidentally expose it.
- **FR-012**: System MUST enforce that **Read-only** members can perform no state-changing action (create/update/delete) on any internal endpoint; permitted read actions still succeed.
- **FR-013**: System MUST enforce authorization on the **server** for every internal endpoint regardless of what the frontend exposes, so a direct API request cannot exceed the caller's role.
- **FR-014**: System MUST return a consistent, non-PII error envelope for a 403 refusal (matching the project's existing error-envelope shape) that does not leak the existence, identity, or content of the protected resource. The 403 response body MUST NOT contain the protected resource's identifier, content, or any existence signal, and MUST be identical in shape across all 403 refusals (verified by contract test); the canonical envelope shape is fixed in plan.md.
- **FR-015**: System MUST NOT apply RBAC to the public candidate-facing allow-list (F01 FR-029/FR-011); candidate endpoints carry no role and MUST remain reachable without a member session.

#### Permission matrix (least privilege)

- **FR-016**: System MUST treat the canonical **role-permission matrix** (this spec's Permission Matrix section) as the single source of truth for which role may perform which action; every internal endpoint MUST map to a matrix entry.
- **FR-017**: System MUST grant **Admin** full access to all workspace actions including member administration, role management, and workspace configuration.
- **FR-018**: System MUST grant **Recruiter** access to operational actions (e.g. scheduling, template use, pipeline, dashboard) and MUST deny Recruiters workspace-level configuration and member/role administration.
- **FR-019**: System MUST grant **Hiring Manager** the ability to view and act on **only their own assigned** requisitions, candidates, and interviews (including confirming/declining proposed interview slots, owned by the scheduling feature but bound to this role rule), and MUST deny access to the full pipeline or other managers' data.
- **FR-020**: System MUST grant **Interviewer** the ability to view **only their own** assigned interviews and to submit their own interview feedback (including the candidate context attached to that interview), and MUST deny all other internal reads/actions not tied to one of their own assigned interviews.
- **FR-021**: System MUST grant **Read-only** the ability to view pipeline and analytics data permitted to it and MUST deny every state-changing action and any export reserved to higher roles.
- **FR-022**: System MUST require that any internal endpoint introduced by a **later feature** declares its minimum required role (and scoping rule, if applicable) against this matrix before it ships; an endpoint without a declared role MUST fail the deny-by-default rule (FR-011) and a contract test.

#### Server-side data scoping

- **FR-023**: System MUST persist the assignment relationship that scopes a Hiring Manager to their requisitions and an Interviewer to their interviews, so scoping is evaluated against stored data, not client input.
- **FR-024**: System MUST apply data scoping **in the server-side query** for scoped roles, returning only the records within the member's assignment for collection reads.
- **FR-025**: System MUST refuse (HTTP 403 or not-found semantics, without disclosing the resource) a scoped member's direct request for a specific record outside their assignment; it MUST NOT return that record. The response for "record exists but is outside the caller's assignment" MUST be **indistinguishable in status and body** from "record does not exist" (achieved by a shared not-found code path so no branch differs; timing parity follows from that shared path), so the response cannot be used to confirm a record's existence.
- **FR-026**: System MUST return an **empty result set** (never the full workspace set) when a scoped member has no current assignments.
- **FR-027**: System MUST ensure scoping cannot be bypassed by client-supplied parameters (e.g. a requisition or interview identifier in the request) — the server validates the requested identifier against the member's assignment before returning data.
- **FR-032**: System MUST apply the same assignment check to scoped **state-changing** actions (e.g. confirm/decline slot, submit feedback): a scoped member's write against a record outside their assignment MUST be refused with no mutation, using the same server-side assignment validation as scoped reads (FR-027), so the scoping is not a read-only convenience.

#### Privacy, audit & logging (constitution §VIII)

- **FR-028**: System MUST record an audit entry for every role change (actor member, target member, old role, new role, timestamp) and for **security-relevant** authorization refusals — role-management denials, last-Admin guard trips, and scoped cross-assignment access attempts — **not** every routine 403, using **non-PII internal identifiers only** (no email or name). Refusal-audit writes MUST be bounded so audit volume cannot be amplified by repeated probing, and MUST never record the existence/identity of a not-found-masked resource (consistent with FR-014/FR-025).
- **FR-029**: System MUST NOT write any plaintext personal data (member email or name) or the content of a protected resource to application logs when logging an authorization decision or refusal; only anonymised identifiers, the role, and the endpoint involved may be logged. It MUST NOT log any candidate identifier or scoped-record content in an authorization/scoping decision (keeping gate C3 airtight on the logging path).
- **FR-030**: System MUST ensure a Hiring Manager's scoping confines candidate PII exposure to that manager's own assignments (constitution gate C3 — candidate personal data is never exposed to a role outside the minimum required).

### Key Entities *(include if feature involves data)*

- **Role**: One of the five fixed values (Admin, Recruiter, Hiring Manager, Interviewer, Read-only). Carries an implied set of permitted actions defined by the permission matrix. Exactly one role is assigned per member (see Assumptions).
- **Member (workspace user)**: Existing entity from F01, extended in meaning here so that its persisted role is the authoritative input to every authorization decision and re-validated per request.
- **Permission matrix**: The canonical mapping of role → permitted action (and, where relevant, the scoping rule). Source of truth referenced by every internal endpoint; consumed by later features.
- **Assignment**: The relationship binding a Hiring Manager to their requisitions and an Interviewer to their interviews, used to scope data reads server-side. (The requisition/interview entities themselves are owned by later features; F02 owns the assignment relationship and the scoping rule.)
- **Authorization audit event**: An append-only record of a role change or security-relevant authorization refusal, referencing only non-PII identifiers (reuses the F01 authentication audit pattern/collection or a sibling collection — to be confirmed in plan.md).

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 100% of internal state-changing endpoints (create/update/delete) refuse a disallowed role with HTTP 403 and apply no change (verified by a contract test for every such endpoint).
- **SC-002**: 0 internal endpoints are reachable by a member whose role is below the endpoint's declared minimum (deny-by-default holds across the full endpoint inventory) — the *endpoint inventory* means every internal endpoint that exists at the time of measurement; later-feature endpoints inherit this via FR-022/SC-010.
- **SC-003**: A role change applied by an Administrator governs the affected member's access within **one request** (the next request after the change), measured 100% of the time.
- **SC-004**: 100% of attempts to remove, downgrade, or deactivate the last active Administrator are blocked (0 successful workspace lockouts).
- **SC-005**: A Hiring Manager or Interviewer cannot retrieve **or modify** any record outside their assignment, including by direct identifier — 0 cross-assignment data leaks or writes across adversarial direct-API tests.
- **SC-006**: A scoped member with no assignments receives an empty set on every scoped collection read (0 cases of full-workspace fallback).
- **SC-007**: 0 successful privilege escalations across adversarial tests (self-promotion attempt, client-tampered role claim, direct call to an undeclared endpoint, scoped-ID bypass).
- **SC-008**: 100% of unauthorized route navigations in the frontend redirect to `/not-authorized` (0 cases of 404, blank, or error page), while the underlying API independently refuses the same access.
- **SC-009**: Zero occurrences of plaintext personal data or protected-resource content in application logs across role-change and authorization-refusal paths (verified by automated log scan).
- **SC-010**: Every internal endpoint maps to exactly one permission-matrix entry with a declared minimum role (0 endpoints without a declared role), verified by an **automated inventory test that enumerates all registered internal request mappings (excluding the candidate allow-list) and fails the build if any mapping has no declared minimum-role/scoping rule** (so a later feature adding an endpoint without a declaration breaks CI, not only at runtime).
- **SC-011**: 100% of role-restricted frontend routes have a unit test asserting the permitted role passes and **every** disallowed role redirects to `/not-authorized` (0 guarded routes without a per-role unit test) — closing the backlog-flagged route-guard test gap.
- **SC-012**: Under concurrent role changes to the same member, the final persisted role is exactly one of the submitted values with no lost-update inconsistency (0 torn states across concurrent integration tests), and every attempt is audited.
- **SC-013**: Under concurrent demotion/deactivation of the last two Administrators, at most one succeeds (0 zero-Admin states), verified by a concurrency test.
- **SC-014**: 100% of role-assignment requests carrying a non-canonical role value are rejected with no persisted change (verified by an adversarial contract test).
- **SC-015**: For a scoped member, a request for an out-of-assignment existing identifier and a request for a non-existent identifier return identical responses (0 distinguishable cases), so existence cannot be probed.

## Assumptions

- **One role per member**: Each member holds exactly one of the five roles at a time (matching F01, which attached a single role to the session, and the constitution's "Five roles" framing). Multi-role or custom-role support is out of scope for the MVP. Changing responsibilities is handled by reassigning the single role. This is a **stakeholder-reversible** MVP decision; the per-action-grant model (next assumption) would absorb multi-role later without re-architecting enforcement.
- **Read-only sees the full pipeline, unscoped**: Read-only is a workspace-wide viewer (per backlog F50/F51), so unlike Hiring Manager it is NOT assignment-scoped and may view candidate PII across the workspace in read-only mode. This is the one unscoped candidate-PII read role; **if the stakeholder intends Read-only to also be assignment-scoped, this must be reversed before F51 lands.**
- **Role hierarchy vs. explicit grants**: The matrix is treated as explicit per-action grants rather than a strict numeric hierarchy, because the roles are not cleanly nested (a Hiring Manager is not a "more powerful Recruiter"). Admin is a superset; the other four are siblings with distinct permitted-action sets. The plan MAY implement common cases via authority checks but MUST honour the matrix, not an assumed ordering.
- **Enforcement scope today vs. future bindings**: F02 ships a complete, demonstrable increment — role administration, deny-by-default enforcement, and the server-side scoping mechanism — exercised against the internal endpoints that exist after F01 (member administration, invitation, role management). Endpoints for scheduling, templates, pipeline, scorecards, and dashboards are delivered by later features (F12-F51); those features MUST declare and enforce their minimum role and scoping against this matrix when they land. This is a forward contract, not stubbed code (constitution §II): no placeholder endpoints or fake screens are shipped.
- **Assignment data source**: The requisition→Hiring-Manager and interview→Interviewer assignment relationships are minimally modelled by F02 so scoping is real and testable; the rich requisition/interview entities and their UIs are owned by later features. Where no scoped resource yet exists, the scoping rule is specified and unit/contract-tested against the assignment primitive and the member surface.
- **No external state**: Authorization decisions read the persisted member role and assignment from MongoDB and/or the per-request session derived from it; no Redis/cache/broker is introduced (constitution §IV / gate C2). No in-process role/permission cache with a time-based TTL is introduced either: the authoritative role is read per request from the member document already loaded by F01's active-status check, so role changes are effective on the next request (FR-007) without a cache-invalidation step. (An external cache tier remains prohibited by §IV / C2.)
- **HTTP semantics**: 401 = unauthenticated (F01), 403 = authenticated-but-unauthorized (F02). A scoped not-found may be expressed as 403 or 404-style not-found to avoid disclosing a resource's existence; the plan picks one consistently.
- **Candidate surface untouched**: RBAC applies only to workspace member roles; the public candidate allow-list (F01 FR-029) is explicitly excluded and must remain reachable without a session.
- **First Administrator bootstrap**: The first Admin of a workspace is provisioned by F01 invitation / F03 workspace setup; F02 assumes at least one Admin exists and protects against losing the last one.
- **Deactivation ownership**: Member deactivation is owned by F01 (session revocation) and surfaced by F03; F02 only adds the last-Admin protection to that action and never weakens F01's deactivation gate.

## Notes for Planning (backend / topology — to be confirmed in plan.md)

These are flagged so the plan's Constitution Check passes cleanly; exact mechanisms belong in `plan.md`, not this spec.

- **Enforcement mechanism (C4 / dependency policy)**: Prefer Spring Security's built-in method security (`@PreAuthorize` / authority checks) and the existing `SecurityConfig` filter-chain ordering; no new third-party authorization library should be needed. Any addition MUST be recorded with a one-line justification in `plan.md`.
- **`SecurityConfig` interaction**: F02 builds on F01's chains — the actuator `@Order(1)` permitAll chain and the candidate `permitAll` chain are untouched; the main authenticated chain gains role/authority enforcement. Authorities are granted from the persisted member role (not a client claim) as the source of truth.
- **Entry-point vs. access-denied separation (F01/F00 contract)**: F02 adds the *authorization* 403 via Spring Security's `AccessDeniedHandler` (authenticated-but-unauthorized), which is distinct from F01's *authentication* entry point. The plan MUST preserve F01's scoped `HttpStatusEntryPoint(401)` for `/api/**` and the F00 actuator-on-public-port behaviour (`/actuator/**` falls through to **404, NOT 403**). It MUST NOT install a blanket 403/401 handler across all chains — doing so breaks the documented `ActuatorPortTest` contract (CLAUDE.md). Only the **`@Order(3)`** main authenticated chain gains authority enforcement and an access-denied handler scoped to it (F01 ships three chains: `@Order(1)` actuator permitAll, `@Order(2)` public/candidate permitAll, `@Order(3)` main authenticated).
- **MongoDB indexes (F00.1 pattern)** the plan SHOULD declare: an index supporting the last-Admin guard (e.g. `members { workspaceId: 1, role: 1, status: 1 }` — covers both the guard predicate and any admin-count read). Because F02 owns the assignment relationship, F02's plan MUST declare the index on the assignment collection/field it introduces (e.g. assignment keyed by `{ memberId: 1, resourceType: 1 }` or the equivalent denormalised field on `members`). The resource-side covering index (requisition/interview filtered by assigned member) is part of the binding matrix contract each later feature MUST declare when it lands (FR-022) — an un-indexed scoped collection read is a deny-by-default-passing but F00.1-failing ship and MUST be caught in that feature's plan.
- **Nullable denormalised index fields (F01 lesson)**: Any new index F02 introduces over a field that is absent/null for some members (e.g. an assignment field carried only by Hiring Managers/Interviewers, or a partial unique index) MUST follow the F01 pattern: annotate the field `@Field(write = Field.Write.NON_NULL)` so null values are omitted from the BSON and do not collide on a partial/unique index (CLAUDE.md F01 note). The role/status count index is non-unique and unaffected.
- **Audit collection**: Decide whether role-change/authorization audit reuses F01's auth audit collection (with a member-keyed index) or a sibling collection; declare the index either way. Also bound/throttle the refusal-audit write path (FR-028) so probing cannot amplify audit volume.
- **Last-Admin guard (atomic, not count-then-write)**: The guard MUST be a single atomic conditional update, NOT a read-count-then-write (which is a lost-update under concurrent demotions — the same class fixed in F01's lockout path). The demotion/deactivation of an Admin MUST succeed only if at least one *other* active Admin exists, evaluated atomically in the same write. Two simultaneous last-Admin demotions MUST resolve to at most one success with the workspace never reaching zero active Admins. The `members { workspaceId: 1, role: 1, status: 1 }` index covers the guard predicate.
- **Endpoint-inventory enforcement (SC-010)**: The *primary* guarantee is runtime deny-by-default in `SecurityConfig` — any `/api/**` path not positively granted to a role is refused (FR-011), so an undeclared endpoint fails closed, not open. The plan MUST additionally define a build-time contract/reflection test that enumerates every internal request-mapped handler (excluding the candidate allow-list) and asserts each carries an explicit minimum-role declaration, so later features (F12-F51) inherit the check automatically and a missing declaration fails CI rather than only failing at runtime. The plan picks the annotation/registry mechanism; the spec mandates only that both layers exist.

## Dependencies

- **F01 (Authentication & Session Management)**: complete — provides the authenticated session, the member identity, the role attached to the session, the per-request server-side revocation/active-status check, the audit baseline, and the `SecurityConfig` two-chain skeleton this feature extends. F02 must land on top of F01.
- **F00 / F00.1 / F00.2 (scaffold)**: complete — provides MongoDB index bootstrap, structured no-PII logging, and the audit/observability baseline.
- **F03 (Workspace Setup)**: provides the first-Administrator bootstrap and the member-deactivation UI surface; F02 protects the last-Admin invariant that F03's deactivation must respect.
- **Later features (F10-F51)**: consume this feature's permission matrix and server-side scoping contract; each declares and enforces its own endpoints' minimum role and scoping against this matrix when it lands.

## Permission Matrix (canonical — source of truth)

Legend: ✓ = permitted; ✓ (own) = permitted but server-side scoped to the member's own assignments; ✗ = denied. "Later" = the action's endpoint is delivered by a later feature, which MUST enforce this rule when it ships.

| Action area | Admin | Recruiter | Hiring Manager | Interviewer | Read-only |
|---|---|---|---|---|---|
| Member administration (invite, deactivate) | ✓ | ✗ | ✗ | ✗ | ✗ |
| Role assignment / change | ✓ | ✗ | ✗ | ✗ | ✗ |
| Workspace configuration (F03, Later) | ✓ | ✗ | ✗ | ✗ | ✗ |
| Scheduling / templates / send links (F12-F22, Later) | ✓ | ✓ | ✗ | ✗ | ✗ |
| Confirm/decline proposed interview slot (F13, Later) | ✓ | ✓ | ✓ (own) | ✗ | ✗ |
| View pipeline / candidate data (F51, Later) | ✓ | ✓ | ✓ (own) | ✗ | ✓ (view-only) |
| View own assigned interviews (F32, Later) | ✓ | ✓ | ✓ (own) | ✓ (own) | ✗ |
| Submit interview feedback (F32, Later) | ✓ | ✗ | ✗ | ✓ (own) | ✗ |
| View dashboard / analytics (F50, Later) | ✓ | ✓ | ✓ (own) | ✗ | ✓ (view-only) |
| Export dashboard data (F50, Later) | ✓ | ✓ | ✗ | ✗ | ✗ |
| _Summary: state-changing actions (informational, not a mappable entry)_ | ✓ | per above | per above (own) | own feedback/interviews | ✗ |

> The "Later" rows are the binding contract for the owning feature; the non-Later rows (member administration, role assignment) are enforced by F02 today on the endpoints that exist after F01.
>
> The summary row is descriptive only; every endpoint maps to exactly one of the specific action rows above (FR-016/SC-010), never to the summary.
>
> Export (F50) follows the F50 role-access matrix exactly: Admin and Recruiter may export; Hiring Manager, Interviewer, and Read-only may not. Read-only is view-only (FR-021).

## Constitution Alignment (informational)

- **C1 — MVP scope**: In scope. F02 is an MVP backlog item (Tier 0 Foundation).
- **C2 — no new service/queue/replica**: Satisfied. Authorization decisions use the persisted member role + per-request session on the single instance + MongoDB only; no broker, cache tier, or replica added.
- **C3 — candidate PII exposure to unauthorized roles**: Directly strengthened. Deny-by-default + server-side Hiring-Manager/Interviewer scoping confine candidate PII to the minimum required role (FR-030).
- **C4 — fixed stack**: Satisfied. Uses Spring Security's built-in method security; any authorization library addition is recorded with justification in `plan.md`.
- **C5 — script encoding**: Any new/changed `.ps1`/`.cmd`/`.bat` will be byte-scanned for non-ASCII before done.
- **C6 — multi-role sub-agent review**: Done at spec stage — four role perspectives (Security/GDPR, Backend/DevOps, QA, Business Analyst) reviewed (2026-06-14), all APPROVE-WITH-CHANGES, findings applied (see `checklists/requirements.md`). A further review runs at implementation task close.
- **C7 — zero tool downloads**: No build tool/runtime/CLI will be downloaded; highest already-installed versions used.
- **§VIII Security & Privacy**: Five-role RBAC enforced on every internal endpoint (FR-009), deny-by-default (FR-011), server-side scoping (FR-023..FR-027), non-PII audit/logging (FR-028/FR-029).
