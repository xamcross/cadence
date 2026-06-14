# Phase 1 Data Model: Role-Based Access Control (RBAC)

**Feature**: 003-rbac | **Date**: 2026-06-14

F02 adds **one** collection (`assignments`), **extends** F01's `authAuditLog`, and changes **no** field on `members` (the `role` and `status` fields already exist from F01). POJOs are `@Document` classes under `com.cadence.domain`.

---

## Collection: `members` (existing — no schema change)

F02 reads `role` and `status` (both already present, F01 data-model) and **changes how `role` flows into a request**: the per-request `Principal` now derives its role from this persisted document, not the session snapshot (research D3). No new field.

**New index (ChangeUnit003)**: `{ workspaceId: 1, role: 1, status: 1 }` (non-unique) — backs the atomic last-Admin guard predicate and admin-count reads (research D4/D10).

**Role administration rules**:
- `role` is settable only to one of the five `Role` enum values; a non-canonical value is rejected at binding (FR-031/SC-014, research D7).
- A downgrade of (or deactivation flipping `status` on) the **last active ADMIN** in a workspace is refused by the broker-free flip→recount→conditional-rollback guard (FR-005, research D4) — never zero active Admins under concurrency.
- No self-elevation: a member cannot raise their own privilege; an Admin may self-demote only if not the last Admin (FR-006).

---

## Collection: `assignments` (new)

Binds a Hiring Manager to their requisitions and an Interviewer to their interviews, used to scope reads/writes server-side. The requisition/interview *entities* are owned by later features; F02 owns this relationship and the scoping rule.

| Field | Type | Notes |
|---|---|---|
| `id` | String (ObjectId) | `@Id` |
| `workspaceId` | String | tenant scope; every query includes it |
| `memberId` | String | the assigned member (HM or Interviewer); non-PII internal id |
| `resourceType` | `ResourceType` enum | REQUISITION (HM) / INTERVIEW (Interviewer) |
| `resourceId` | String | opaque id of the scoped resource (a requisition or interview owned by a later feature) |
| `createdAt` | Instant | |
| `createdByMemberId` | String | the Admin who created the assignment |

**Indexes (ChangeUnit003)**:
- `{ workspaceId: 1, memberId: 1, resourceType: 1 }` — scoped collection reads (FR-024).
- `{ workspaceId: 1, resourceType: 1, resourceId: 1, memberId: 1 }` **unique** — prevents duplicate assignment of the same resource to the same member.

All fields are always present (no nullable denormalised indexed field), so the F01 partial-index null-collision footgun (`@Field(write = NON_NULL)`) does not apply here.

**Validation / rules**:
- Created only by an Admin (FR-017); `POST /api/internal/members/{memberId}/assignments` (research D6, contracts).
- Scoped read of the collection returns only rows where `memberId == caller` for HM/Interviewer; Admin sees all (FR-024/FR-026).
- A single-record fetch for H/I runs the **scoped** query `findOne({ workspaceId, _id, memberId: caller })`, which returns empty for both "missing" and "exists-but-not-yours", so one shared not-found path yields a **byte-identical 404 status + body** (FR-025/SC-015) — decided at the service layer, not by the access-denied handler. (Timing parity follows from the shared query path; it is not separately asserted.)
- `requireAssigned(...)` is the scoped-**write** primitive later features call before a confirm-slot/submit-feedback mutation (FR-032); it throws `NotAssignedException` for an out-of-assignment write.

**State transitions**: none (an assignment exists or is deleted by an Admin).

---

## Collection: `authAuditLog` (existing — extended)

Reuses F01's member-keyed, **non-PII**, append-only collection. F02 adds two event types and three nullable fields; the index is unchanged (`{ memberId: 1, occurredAt: -1 }`).

| Field | Type | Notes |
|---|---|---|
| *(existing F01 fields)* | | `id`, `workspaceId`, `memberId`, `eventType`, `occurredAt`, `sourceIpHash`, `outcome` |
| `targetMemberId` | String (nullable) | the member whose role changed (ROLE_CHANGED); non-PII id |
| `oldRole` | `Role` (nullable) | prior role (ROLE_CHANGED) |
| `newRole` | `Role` (nullable) | new role (ROLE_CHANGED) |

**`AuthEventType` additions**: `ROLE_CHANGED`, `AUTHORIZATION_DENIED`.

**Validation / rules**:
- `ROLE_CHANGED` written on every role change (actor `memberId`, `targetMemberId`, `oldRole`, `newRole`, timestamp) — FR-028.
- `AUTHORIZATION_DENIED` written only for **security-relevant** refusals (role-management denials, last-Admin trips, scoped cross-assignment attempts), bounded/throttled so probing cannot amplify volume (FR-028, research D8).
- References members by internal id only; survives a member's PII erasure (FR-036 pattern). No email/name/candidate-id/resource-content ever written (FR-029).

---

## Enums

- `Role` (existing): ADMIN, RECRUITER, HIRING_MANAGER, INTERVIEWER, READ_ONLY — the complete, closed set (FR-001).
- `ResourceType` (new): REQUISITION, INTERVIEW.
- `MemberStatus` (existing): ACTIVE, DEACTIVATED.
- `AuthEventType` (extended): … existing F01 values … + ROLE_CHANGED, AUTHORIZATION_DENIED.

---

## Permission matrix → enforcement mapping

The canonical matrix (spec) maps to enforcement as follows. **Today** = an endpoint exists in F02; **Later** = the owning feature applies the rule via the same mechanism (FR-022).

| Action | Endpoint (Today) | Enforcement |
|---|---|---|
| List members / change role | `GET /api/internal/members`, `PATCH /api/internal/members/{id}/role` | `@PreAuthorize("hasRole('ADMIN')")` + last-Admin guard + self-elevation guard |
| Invite / member admin | `POST /api/internal/invitations` (F01) | `@PreAuthorize("hasRole('ADMIN')")` (already present) |
| Create/list/fetch assignments | `POST /api/internal/members/{id}/assignments` (ADMIN); `GET /api/internal/assignments` (scoped) | method security + `AssignmentService` scoping |
| Scheduling / templates / pipeline / scorecards / dashboard | *(Later: F12–F51)* | each handler declares its minimum role via `@PreAuthorize`; HM/Interviewer reads/writes call `AssignmentService` (FR-022/FR-032) |

---

## Entity relationships

```text
Workspace (F03) 1───* Member
Member 1───* Assignment            (HM → REQUISITION, Interviewer → INTERVIEW)
Assignment *───1 (resourceId of a later-feature Requisition/Interview)
Member 1───* AuthAuditEvent        (incl. ROLE_CHANGED targetMemberId, AUTHORIZATION_DENIED)
```

All collections are workspace-scoped; every query includes `workspaceId`, so cross-workspace access is impossible by construction. Scoping for HM/Interviewer is an additional `memberId` filter on top of the workspace filter.
