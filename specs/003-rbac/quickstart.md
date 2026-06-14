# Quickstart: Role-Based Access Control (RBAC)

**Feature**: 003-rbac | **Date**: 2026-06-14

Local run + manual verification of F02 on top of the F01 auth scaffold. Assumes the F01 quickstart already works (MongoDB container, OIDC/password sign-in, seeded Admin).

## Prerequisites

- Backend deps from F01 (no new dependency added by F02).
- A local MongoDB 7 container: `docker run -p 27017:27017 mongo:7` (manual dev) — tests use Testcontainers.
- Backend env (same as F01): `JWT_SECRET`, `PII_ENC_KEY`, `PII_PEPPER`, `TOKEN_PEPPER`, `IP_PEPPER` set as local dev values.
- At least two seeded members in one workspace (e.g. one Admin, one Recruiter) — reuse the F01 invite/accept flow or a seed.

## Run

```bash
# Backend (from backend/), uses cached Gradle 9.4.0 + JDK 21 — no downloads
./gradlew bootRun

# Frontend (from frontend/), same-origin proxy as F01
ng serve
```

## Manual verification (maps to user stories)

### US1 — Admin manages roles
1. Sign in as the **Admin**, open **Admin → Members**. The directory lists members with their role and status.
2. Change the Recruiter's role to **Read-only**, save. The list reflects the new role.
3. In a second browser signed in as that member, perform any state-changing action **after** the change → it is refused (403) on the member's **next request** (proves role-from-persisted-member, research D3).
4. Try to demote the **only** Admin (yourself) → refused with a clear "cannot remove the last administrator" message (FR-005).
5. As a **non-Admin**, navigate directly to `/admin/members` → redirected to **/not-authorized**; and call `PATCH /api/internal/members/{id}/role` directly → **403** (server is the boundary).

### US2/US3 — deny-by-default + least privilege
6. As a Recruiter, call `POST /api/internal/invitations` (member admin) → **403**; as Admin → **201**.
7. As Read-only, attempt any internal write → **403**; a permitted read → **200**.
8. Confirm an unauthenticated call to any `/api/internal/**` endpoint → **401** (distinct from the 403 above).

### US4 — server-side scoping
9. As Admin, assign a requisition to a Hiring Manager: `POST /api/internal/members/{hmId}/assignments` `{ "resourceType":"REQUISITION","resourceId":"req-1" }`.
10. As that Hiring Manager, `GET /api/internal/assignments` → returns only `req-1`. A second HM with no assignments → **empty list** (not the full set).
11. As the Hiring Manager, `GET /api/internal/assignments/{idOfAnotherHmAssignment}` → **404**, identical to a genuinely missing id (no existence leak).

### US5 — frontend authorization
12. Signed in as Read-only, confirm the **Admin** nav entry is hidden; typing `/admin/members` redirects to **/not-authorized** (not a 404/blank).

## Automated checks (CI parity)

```bash
# Backend — includes the deny-by-default inventory test (SC-010), last-Admin concurrency (SC-013),
# scoping indistinguishability (SC-015), role validation (SC-014), and the PII log scan (SC-009)
./gradlew test

# Frontend — role-guard unit tests (each disallowed role → /not-authorized, SC-011)
ng test --watch=false

# E2E — admin role change + not-authorized redirect + API-still-403 (SC-008)
npx playwright test rbac.spec.ts
```

## Acceptance gate before "done"

- [ ] `RbacEndpointInventoryTest` green — no `/api/internal/**` handler without a declared minimum role (SC-010).
- [ ] `LastAdminGuardIntegrationTest` green incl. the concurrent double-demotion case (SC-004/SC-013).
- [ ] `AssignmentScopingIntegrationTest` green incl. indistinguishable not-found (SC-015) and scoped-write refusal (FR-032).
- [ ] `RbacLogPiiScanTest` green + CI PII grep clean (SC-009).
- [ ] Role guard Jasmine tests cover every role/route pair (SC-011).
- [ ] Multi-role sub-agent review completed at task close; findings applied (constitution §VI).
