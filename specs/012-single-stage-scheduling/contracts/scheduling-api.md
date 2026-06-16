# API Contracts: Flow A1 — Single-Stage Scheduling (F13)

Error envelope is the existing Cadence shape `{ "error": "<code>", "message": "<safe>" }` — no PII, no token values. Status codes per spec.

---

## A. Recruiter initiation & status — `/api/internal/**` (RBAC: ADMIN or RECRUITER, workspace-scoped)

`SchedulingController`, class-level `@PreAuthorize("hasAnyRole('ADMIN','RECRUITER')")`. Allow-listed as internal in `RbacEndpointInventoryTest` only by virtue of the role gate (it is NOT a public prefix).

### A1. Initiate scheduling — `POST /api/internal/candidates/{candidateId}/scheduling`

Request:
```json
{ "templateId": "string", "locationText": "string (optional, recruiter free-text; encrypted at rest)",
  "rangeStart": "2026-06-20", "rangeEnd": "2026-07-04" }
```
`rangeStart`/`rangeEnd` optional → default workspace search window.

Responses:
- `201 Created` — `{ "schedulingRequestId": "...", "status": "PENDING_SELECTION", "offeredSlotCount": 7, "sentAt": "<instant>", "expiresAt": "<instant>" }`. Side effects: snapshot persisted, invitation email enqueued (consent-gated, `EmailMessageType.INVITATION` — the existing F21 type mapped to the `SCHEDULING_LINK` token), `SCHEDULING_LINK_SENT` audit. Re-invoking supersedes the prior live request (FR-022).
- `409 already_contacted` is NOT used; a re-send is allowed and supersedes.
- `422 no_slots` — zero compliant slots in window (FR-003); no email, no link.
- `409 not_contactable` — `ContactPermissionGate` refused (reason category only, no detail that is an oracle) (FR-004).
- `409 unschedulable_required_member` — a required participant's calendar is unavailable; body names the member id(s) (FR-005).
- `404 not_found` — candidate or template not in workspace (oracle-free, shared shape).
- `403 forbidden` — wrong role.

### A2. Scheduling status — `GET /api/internal/candidates/{candidateId}/scheduling`

`200 OK` — latest request summary: `{ "status": "PENDING_SELECTION|BOOKED|EXPIRED|...", "sentAt": "...", "expiresAt": "...", "chosenStart": "<instant|null>" }`. No token, no participant PII. `404` if none.

---

## B. Candidate self-scheduling — `/api/candidate/**` (public-by-token, rate-limited 10/min/IP → 429)

`CandidateSchedulingController`, on the existing `@Order(2)` permitAll/STATELESS chain. Allow-listed in `RbacEndpointInventoryTest` (the `/api/candidate/` prefix already is). `@PreAuthorize` not used; auth = the token. CSRF-exempt (STATELESS chain).

### B1. View offered slots — `GET /api/candidate/scheduling/{token}`

**Response precedence (ordered — the implementer MUST evaluate top-down so a reaper-set EXPIRED or a SUPERSEDED row never returns 410):**
1. token hashes to a `BOOKED` request → `200` booked confirmation (the candidate legitimately holds it).
2. token hashes to a `PENDING_SELECTION`/`BOOKING` request that is **past `expiresAt`** → `410 Gone`.
3. token hashes to a `PENDING_SELECTION` request within TTL → `200` open with slots.
4. **everything else** (unknown hash / `SUPERSEDED` / reaper-set `EXPIRED` / `CLEANUP_INCOMPLETE`) → `400 invalid`, byte-identical for all (no existence oracle, FR-008/FR-010).

- `200 OK` (open) — `{ "status": "open", "zoneHint": "America/New_York",
    "slots": [ { "slotId": "...", "start": "<instant>", "end": "<instant>", "zoneId": "..." } ] }`.
  **Times only** — no participant names/emails and no `locationText` (FR-011). `Cache-Control: no-store`.
- `200 OK` (booked) — `{ "status": "booked", "bookedStart": "<instant>", "zoneId": "..." }` (reopening a consumed link shows the confirmation, FR-009).
- `410 Gone` — `{ "error": "expired", "message": "This link has expired — contact your recruiter." }` (token existed, not terminal, past TTL — D5).
- `400 invalid` — byte-identical body across unknown / superseded / reaper-expired / cleanup-incomplete (no oracle, FR-008/FR-010).
- `429 rate_limited` — per-IP limit exceeded.

### B2. Confirm a slot — `POST /api/candidate/scheduling/{token}/confirm`

Request: `{ "slotId": "string" }`.

Responses:
- `200 OK` — `{ "status": "booked", "bookedStart": "<instant>", "zoneId": "..." }`. Side effects (in order): request-status CAS `PENDING_SELECTION→BOOKING` → contactability re-check → re-validate + pool re-select → per-participant claim CAS → `CalendarEventService.createPanelEvents` → `BOOKED` → enqueue candidate confirmation (`EmailMessageType.CONFIRMATION`) + member-path participant confirmations → `SCHEDULING_BOOKED` audit.
- `200 OK` (idempotent replay) — already `BOOKED` for this token → returns the same confirmation, no duplicate events/emails (FR-019).
- `409 slot_taken` — claim `DuplicateKeyException` (another booking won this interviewer-time) → returns remaining valid slots (FR-012/SC-003).
- `409 slot_no_longer_available` — confirm-time re-validation failed (stale slot / pool quorum unmet) → returns remaining valid slots (FR-013).
- `409 cleanup_incomplete` — booking partially failed and rollback could not complete; candidate sees a "we hit a problem, your recruiter will follow up" message; request `CLEANUP_INCOMPLETE` + recruiter alert + `SCHEDULING_CLEANUP_INCOMPLETE` audit (FR-016 honest bound).
- `410 Gone` — token expired between view and confirm.
- `409 not_available` — candidate became not-contactable/erased since send (FR-014); booking refused (not just email-suppressed). The body is **byte-identical across every deny reason** (erased / withdrawn / over-retention / undeliverable / no-basis) so it is not a GDPR-status oracle to the unauthenticated candidate.
- `400 invalid` — unknown/superseded token or unknown `slotId`.
- `429 rate_limited`.

---

## C. Internal SPI (service-layer, no HTTP) — for F20/F23 reuse

```java
// SchedulingService
SchedulingInitiateResult initiate(String workspaceId, String actorMemberId, String candidateId,
                                  String templateId, String locationText,
                                  LocalDate rangeStart, LocalDate rangeEnd, String ip);
SchedulingStatusView status(String workspaceId, String candidateId);

// SlotReservationService (called by the candidate confirm controller)
ConfirmResult confirm(String rawToken, String slotId, String ip);   // performs the full CAS→book→confirm saga
OfferedSlotsView view(String rawToken, String ip);                  // token→slots projection (times only)
```

Both services are privileged internal primitives: `initiate`/`status` are reached only via the RBAC-gated controller; `confirm`/`view` only via the token-gated candidate controller. Neither is wired to any other endpoint without its own gate (the F10 `AvailabilityService` precedent).

---

## D. Contract test coverage (MockMvc)

- A1: 201 happy path (asserts email enqueued + audit + snapshot); 422 no_slots; 409 not_contactable; 409 unschedulable_required_member; 404 scoped-not-found; 403 each disallowed role (5-role matrix).
- A2: 200 status transitions; 404 none.
- B1: 200 open (asserts **no participant identity** in body — non-circular: seed member ids, assert absent); 200 booked; 410 expired; 400 invalid (byte-identical across unknown/used/superseded); 429.
- B2: 200 book (asserts `createPanelEvents` called + confirmations enqueued); 200 idempotent replay (no 2nd event/email); 409 slot_taken (gated concurrent test, SC-003); 409 stale; 409 cleanup_incomplete; 410; 409 not_available (erased); 400; 429.
