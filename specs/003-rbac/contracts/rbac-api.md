# Phase 1 API Contract: Role-Based Access Control (RBAC)

**Feature**: 003-rbac | **Date**: 2026-06-14

All endpoints are **internal** (`/api/internal/**`) and require an authenticated `cad_session` (F01). Authorization is enforced by method security (`@PreAuthorize`); a denial returns **403** with the `{error,message}` envelope (research D5). An unauthenticated call returns **401** (F01, FR-010). State-changing calls require the `X-XSRF-TOKEN` header (F01 CSRF). Error envelope: `{ "error": "<code>", "message": "<human text>" }` — never a resource id, content, or existence signal (FR-014).

Roles abbreviated: A=Admin, R=Recruiter, H=Hiring Manager, I=Interviewer, RO=Read-only.

---

## GET /api/internal/members

List workspace members and their roles for administration.

- **Min role**: A (`hasRole('ADMIN')`).
- **200**: `{ "members": [ { "memberId": "...", "displayName": "...", "role": "RECRUITER", "status": "ACTIVE" } ] }` — scoped to the caller's `workspaceId`. `displayName` is PII returned to the authorized Admin over TLS; never logged (FR-029).
- **403**: any non-Admin (R/H/I/RO) → `{ "error": "forbidden", "message": "You do not have access to this action." }`.
- **401**: no/expired session.

---

## PATCH /api/internal/members/{memberId}/role

Assign or change a member's role.

- **Min role**: A (`hasRole('ADMIN')`).
- **Request**: `{ "role": "READ_ONLY" }` — bound to the `Role` enum (exact, case-sensitive; FR-031).
- **200**: `{ "memberId": "...", "role": "READ_ONLY" }`. Persisted; effective on the target's **next request** (FR-007, research D3). Writes a `ROLE_CHANGED` audit entry (FR-028).
- **400** `invalid_role`: body role is not one of the five canonical values (no change persisted; SC-014).
- **403** `forbidden`: caller is not Admin (FR-004), **or** caller attempts to raise their own privilege (FR-006).
- **409** `last_admin`: the change would remove/downgrade the **last active Administrator** (FR-005); refused atomically with no change (SC-004). Same code returned for the concurrent double-demotion loser (SC-013).
- **404** `not_found`: `memberId` is not a member of the caller's workspace (indistinguishable from a cross-workspace id; no existence leak).

**Self-change note**: an Admin demoting themselves is allowed **only** if another active Admin remains (else `409 last_admin`). Self-elevation by any role is `403`.

---

## POST /api/internal/members/{memberId}/assignments

Assign a scoped resource (requisition/interview) to a Hiring Manager / Interviewer.

- **Min role**: A (`hasRole('ADMIN')`).
- **Request**: `{ "resourceType": "REQUISITION", "resourceId": "req-123" }`.
- **201**: `{ "assignmentId": "..." }`.
- **403**: non-Admin.
- **409** `duplicate_assignment`: the (resourceType, resourceId, memberId) tuple already exists (unique index).

---

## DELETE /api/internal/members/{memberId}/assignments/{assignmentId}

Remove an assignment.

- **Min role**: A. **204** on success; **404** if the assignment is not in the caller's workspace.

---

## GET /api/internal/assignments

List the caller's own scoped assignments (the demonstrable scoping surface).

- **Min role**: A, R, H, I (RO excluded — RO is an unscoped pipeline viewer, not an assignee).
- **Scoping** (FR-024/FR-026):
  - **H/I**: returns only rows where `memberId == caller` (server-side filter). With no assignments → `{ "assignments": [] }` (empty set, never the full workspace set — SC-006).
  - **A/R**: may pass `?memberId=` to view another member's assignments (operational visibility); default returns all in the workspace. The `memberId` filter is **always AND-ed with the caller's `workspaceId`** — a `memberId` outside the caller's workspace yields an empty set, never cross-workspace rows.
- **200**: `{ "assignments": [ { "assignmentId": "...", "resourceType": "INTERVIEW", "resourceId": "int-9" } ] }`.
- **403**: RO.

---

## GET /api/internal/assignments/{assignmentId}

Fetch one assignment, enforcing scoping.

- **Min role**: A, R, H, I.
- **H/I**: the fetch runs the **scoped** query `findOne({ workspaceId, _id, memberId: caller })`, which returns empty for **both** "id missing" and "id exists but not yours" — so the handler takes one shared not-found path: `404 not_found`, **byte-identical status and body** for both cases (FR-025/SC-015). It MUST NOT return the record or reveal it exists. (Timing parity is a property of the shared query path, not a separately asserted outcome.)
- **200**: the assignment (when owned by the caller, or caller is A/R).

---

## Scoped-write primitive (consumed by later features — no F02 endpoint)

`AssignmentService.requireAssigned(workspaceId, memberId, resourceType, resourceId)` throws `NotAssignedException` (→ `403 forbidden` or `404 not_found`, consistent with FR-025) when a scoped member attempts a state-changing action on a resource outside their assignment (FR-032). F13's confirm/decline-slot and F32's submit-feedback handlers MUST call this before mutating. Not an HTTP endpoint in F02; shipped and unit-tested as a reusable component.

---

## Endpoint inventory guarantee (SC-010, not an endpoint)

`RbacEndpointInventoryTest` enumerates all `RequestMappingHandlerMapping` handlers, **allow-lists by exclusion** (`/api/public/**`, `/api/candidate/**`, `/actuator/**`, `/oauth2/**`, `/login/oauth2/code/**`), and **fails the build** if any remaining handler lacks a method-security annotation (checked at method **or** class level). This makes "every internal endpoint maps to a matrix entry with a declared minimum role" enforceable for F02 **and** every later feature (FR-022). **Authenticated-any-role endpoints** (reachable by every role) declare `@PreAuthorize("isAuthenticated()")` — F01's `GET /api/internal/auth/me` and `POST /api/internal/auth/logout` are MODIFIED to add it (without it the inventory test would red-fail on existing F01 endpoints). The test carries a self-test fixture (a deliberately-undeclared dummy internal handler) so its own failure path is proven.

---

## Status-code summary

| Code | Meaning |
|---|---|
| 200/201/204 | success |
| 400 `invalid_role` | non-canonical role value (FR-031) |
| 401 | unauthenticated (F01, FR-010) |
| 403 `forbidden` | authenticated but role/scope not permitted (FR-009/FR-014) |
| 404 `not_found` | resource absent **or** outside caller's assignment, indistinguishably (FR-025) |
| 409 `last_admin` / `duplicate_assignment` | last-Admin guard trip (FR-005) / duplicate assignment |
| 429 | (F01 rate-limit on public auth endpoints; not introduced here) |
