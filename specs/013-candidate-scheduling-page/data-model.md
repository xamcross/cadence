# Phase 1 Data Model: Candidate Scheduling Page (UX) (F14)

F14 introduces **no new persisted collection, no Mongock changeset, and no new MongoDB index**. It is a frontend/UX + verification feature that consumes the existing F13 candidate read/confirm contract. The only "data" it owns is client-side **view state** derived from the F13 responses. This document records (1) the consumed contract, (2) the client view-state machine, and (3) the explicit non-changes, so the plan's Constitution Check (C2/C3) is auditable.

## 1. Consumed F13 contract (read-only, unchanged)

From `GET /api/candidate/scheduling/{token}` and `POST /api/candidate/scheduling/{token}/confirm` (see `specs/012-single-stage-scheduling/contracts/scheduling-api.md`, contract B). The page renders **times only** — the payload carries no participant identity, no `locationText`, and no internal ids beyond the opaque slot id.

| Field (response) | Type | F14 use |
|---|---|---|
| `status` | `"open"` \| `"booked"` | Selects the open vs booked view. |
| `zoneHint` / `zoneId` | IANA zone string | Time-zone label shown to the candidate (FR-009). |
| `slots[].slotId` | opaque string | Confirm target; never displayed as meaningful text. |
| `slots[].start` / `end` | instant (UTC) | Rendered in the candidate's **local** zone with DST-correct labels (FR-009). |
| `bookedStart` | instant \| null | Shown on the booked view (FR-015). |
| HTTP `410` | — | Expired state (FR-013). |
| HTTP `400` | — | Invalid state — indistinguishable across used/superseded/unknown (FR-014). |
| HTTP `429` | — | Rate-limited state (FR-016). |
| HTTP `409` (`slot_taken` / `slot_no_longer_available`) | error code | Conflict → re-present remaining slots (FR-017). |

**Candidate-safe-field note (FR-015 escape hatch)**: if rendering the booked view needs a candidate-safe field not already returned (none identified — `bookedStart` + `zoneId` suffice), it would be a small **additive** read-contract extension requiring the F13 owner's sign-off and **no** change to the token-security or reservation invariants. Default assumption: **no backend change**.

## 2. Client view-state machine (component state, not persisted)

```
                         view(token) on ngOnInit
                                  |
        +----------+----------+---+-----+-----------+-----------+
        | 200 open | 200 open | 200 booked | 410     | 400/0*   | 429       |
        | w/ slots | w/ none  |            |         |          |           |
        v          v          v            v         v          v
     [OPEN]     [EMPTY]    [BOOKED]    [EXPIRED]  [INVALID]  [RATE_LIMITED]
        |
        | confirm(slotId)
        v
   200 -> [BOOKED]      (success; focus to confirmation heading, polite/assertive announce)
   409 slot_taken / slot_no_longer_available -> [OPEN or EMPTY] + inline "just taken" (re-load remaining; if none -> EMPTY)
   409 cleanup_incomplete -> [INVALID-like "we hit a problem, your recruiter will follow up"]
   409 not_available      -> [INVALID] (byte-identical refusal; no GDPR oracle)
   410 -> [EXPIRED]
   400 -> [INVALID]
   429 -> [RATE_LIMITED]
   network error (no HTTP status) -> [RETRYABLE_ERROR] (distinct from token states; offers retry)
```

`*0` = network failure (no HTTP response) → a distinct **retryable error** state, NOT mapped to invalid (edge case: slow/flaky network).

**State → presentation requirements**:
- Every state has: a heading that receives focus on entry (FR-024), an `aria-live` region with correct politeness (assertive for error/conflict, polite for informational), a consistent "contact your recruiter" help affordance where applicable (FR-023, same text/placement), and zero axe violations (FR-005).
- `OPEN`/`EMPTY` distinction (FR-011): zero slots renders the calm "no times available — recruiter will follow up" message, never an empty list.
- `BOOKED` shows `bookedStart` in local zone (FR-015); reachable only via the legitimate token (not an oracle).
- `INVALID` is one shared rendering for used/superseded/unknown/`not_available`/`cleanup`-style refusals so it never reveals token existence or GDPR status (FR-014).

## 3. Explicit non-changes (auditable for C2/C3)

- **No new collection / index / changeset.** Reservation, token hash, snapshot, audit all remain F13-owned.
- **No PII added anywhere.** The page shows times only; no candidate name/email/phone, no participant identity, no internal id beyond the opaque slot id and the URL token (FR-010, FR-019). C3 unaffected.
- **No role/RBAC change.** Candidate route stays public-by-token (top-level un-guarded Angular route `schedule`, already present); internal recruiter routes unchanged.
- **No new server endpoint.** Default: zero backend edits; the only possible backend touch is the gated additive field of §1, not anticipated.
</content>
