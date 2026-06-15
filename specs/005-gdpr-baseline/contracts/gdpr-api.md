# Phase 1 Contracts — GDPR Baseline (F04)

**Branch**: `005-gdpr-baseline` | **Date**: 2026-06-15

All authenticated endpoints sit under `/api/internal/**` (F02 `@Order(3)` chain + `RbacEndpointInventoryTest`). Error envelope reuses the F01/F02 shape `{ "error": <code>, "message": <human> }`. HTTP semantics: **401** unauthenticated (F01), **403** authenticated-but-unauthorized (F02). **No `/api/candidate/**` controller ships in F04** — the candidate-facing erasure-request submission (F30) is a forward contract bound to a service primitive (§ Service-only contracts).

**CSRF (BE-MAJOR)**: every mutating endpoint below (POST/PUT/DELETE) rides the F02 `@Order(3)` chain, which has CSRF enabled (`CookieCsrfTokenRepository`), so each requires the `X-XSRF-TOKEN` header (same as F02/F03). Every MockMvc mutating call in the F04 tests MUST use `.with(csrf())`, or the RBAC-matrix assertions get 403 for the *wrong reason* (CSRF, not role) and pass/fail vacuously.

---

## Erasure & lawful basis — `CandidateGdprController`

### `POST /api/internal/candidates/{id}/erasure` — operator-triggered erasure
- **Auth**: `hasAnyRole('ADMIN','RECRUITER')` (FR-007).
- **Body**: none (reason defaults to `operator`).
- **200 OK** `{ "status": "erased" }` — **byte-identical response** (status line + body) for: just-erased, already-erased, **and unknown id** (FR-009 indistinguishable; erasure does **NOT** return 404 / use `ScopedNotFoundException`). The wipe is synchronous and **O(1)** — one `$set` + one append regardless of audit-history size (SC-003 asserts this structurally, not by wall-clock).
- **Effect (CAS winner only)**: name/email/phone → `[ERASED]`, `emailHash` → null, `erasureState` → `ERASED`; one `ERASURE_COMPLETED` audit. Idempotent; concurrent triggers → single wipe (SC-005).
- **403**: HM / Interviewer / Read-only — no state change (SC-004).

### `PUT /api/internal/candidates/{id}/basis` — record lawful basis
- **Auth**: `hasAnyRole('ADMIN','RECRUITER')` (FR-003/FR-021).
- **Body**: `{ "lawfulBasis": "CONSENT" | "LEGITIMATE_INTEREST" | "CONTRACT" }`.
- **200 OK** `{ "basisRecorded": true }` → gate permits (if not erased/flagged/withdrawn). `BASIS_RECORDED` audited.
- **400** `invalid_basis` — unknown enum, nothing persisted.
- **403**: other roles.

### `DELETE /api/internal/candidates/{id}/basis` — withdraw lawful basis (opt-out)
- **Auth**: `hasAnyRole('ADMIN','RECRUITER')`.
- **200 OK** `{ "basisWithdrawn": true }` → gate denies `withdrawn`. `BASIS_WITHDRAWN` audited.
- **403**: other roles.

### `GET /api/internal/candidates/{id}/audit` — read candidate audit log
- **Auth**: `hasRole('ADMIN')` (FR-016).
- **200 OK** `{ "entries": [ { "eventType", "outcome", "actorMemberId"|null, "occurredAt" }, ... ] }` ordered by `(occurredAt, _id)` (the ObjectId is the deterministic tiebreaker); **non-PII** (no name/email/phone, FR-017). Empty list for a candidate with no entries; unknown id is non-oracle.
- **403**: every non-Admin role (SC-004/SC-007).

---

## Candidate-initiated erasure requests — `ErasureRequestController`

### `GET /api/internal/erasure-requests?status=PENDING` — list requests
- **Auth**: `hasRole('ADMIN')` (FR-012).
- **200 OK** `{ "requests": [ { "id", "candidateId", "status", "reasonCode"|null, "createdAt" }, ... ] }` — non-PII ids only.
- **403**: non-Admin.

### `POST /api/internal/erasure-requests/{id}/confirm`
- **Auth**: `hasRole('ADMIN')`.
- **200 OK** `{ "status": "RESOLVED_CONFIRMED" }` — runs the shared wipe (D2); `ERASURE_REQUEST_CONFIRMED` + `ERASURE_COMPLETED(reason=candidate_request)` audited.
- **409** `already_resolved` — confirming/rejecting a non-`PENDING` request (guarded transition, SC-015); concurrent confirms → single wipe.
- **403**: non-Admin.

### `POST /api/internal/erasure-requests/{id}/reject`
- **Auth**: `hasRole('ADMIN')`.
- **Body**: `{ "reasonCode": "<ErasureReasonCode enum value>" }` — server-validated against the enum (not a trusting String); absent/empty/unknown → **400** `invalid_reason`, request stays `PENDING` (no state change).
- **200 OK** `{ "status": "RESOLVED_REJECTED" }` — no wipe; `ERASURE_REQUEST_REJECTED` audited.
- **409** `already_resolved`; **403**: non-Admin.

---

## Retention enforcement — `RetentionController`

### `GET /api/internal/retention/flagged` — list over-retention candidates
- **Auth**: `hasRole('ADMIN')` (FR-019).
- **200 OK** `{ "flagged": [ { "candidateId", "retentionFlaggedAt", "lastContactAt" }, ... ] }` — non-PII.
- **403**: non-Admin.

### `POST /api/internal/retention/{candidateId}/delete` — Admin-confirmed deletion
- **Auth**: `hasRole('ADMIN')`.
- **Effect**: runs the shared wipe **only via a guarded update on `retentionFlagged == true`** (BE-MAJOR — an unflagged ACTIVE candidate is NEVER wiped by this path; deletion requires a prior scan flag + Admin confirmation, FR-019). Audits `ERASURE_COMPLETED(reason=RETENTION)` + `RETENTION_DELETED`.
- **200 OK** `{ "status": "erased" }` — byte-identical for flagged/not-flagged/unknown (no oracle); a not-flagged/unknown id is a no-op (no wipe) returning the same shape.
- **403**: non-Admin.

*(The retention **scan** that sets/clears flags is the `@Scheduled` `RetentionScanTask`, not an HTTP endpoint — F00.2 checkpointed, daily, age basis `lastContactAt`, strict `<` boundary, self-clearing.)*

---

## Service-only contracts (no HTTP in F04 — forward contracts)

These are public service-bean methods exercised production-path by F04 integration tests and called by later features:

| Primitive | Signature (conceptual) | Consumer (forward) | Spec |
|---|---|---|---|
| **Canonical create** | `CandidateService.create(workspaceId, name, email, phone, Optional<LawfulBasis>, actor)` → `Candidate` | F13/F40/F41/F42 create surfaces | FR-005, SC-016 |
| **Contact-permission gate** | `ContactPermissionGate.evaluate(candidateId)` → `Decision{permit | deny(reason)}`, **fail-closed** | F22 EmailSender (before every dispatch) | FR-004, SC-001 |
| **Erasure-request intake** | `ErasureRequestService.requestErasure(candidateId, reasonCode)` (PII-free) | F30 status-page token surface (rate-limited per F14/F30) | FR-013 |
| **Shared wipe** | `CandidateErasureService.wipe(candidateId, reason, actor)` (idempotent) | all 3 erasure paths; FR-006a participants (F22/F40 PII stores) | FR-006, FR-006a |

---

## RBAC contract matrix (SC-004) — surface × role

| Surface (method) | ADMIN | RECRUITER | HM | INTERVIEWER | READ_ONLY |
|---|---|---|---|---|---|
| erasure trigger | ✅ | ✅ | 403 | 403 | 403 |
| record/withdraw basis | ✅ | ✅ | 403 | 403 | 403 |
| audit read | ✅ | 403 | 403 | 403 | 403 |
| erasure-request list/confirm/reject | ✅ | 403 | 403 | 403 | 403 |
| retention list/confirm-delete | ✅ | 403 | 403 | 403 | 403 |

Every 403 path asserts **no state change** on re-read. A new `/api/internal/**` handler without `@PreAuthorize` fails `RbacEndpointInventoryTest` by design (F02).
