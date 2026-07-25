# Contract — Interview Template & Rule Engine API (F12)

**Branch**: `009-interview-rule-engine` | **Date**: 2026-06-15

All endpoints are mounted under the internal, non-allow-listed prefix `/api/internal/interview-templates` on a single `InterviewTemplateController` with **class-level** `@PreAuthorize("hasAnyRole('ADMIN','RECRUITER')")` (the single source of truth; satisfies `RbacEndpointInventoryTest` for every handler — D9). Workspace + actor come from `@AuthenticationPrincipal SessionService.Principal` (`workspaceId()`, `memberId()`, `role()`). Error envelope reuses the F02/F03 shape `{ "error": "<code>", "details": { "<field>": "<value-free message>" } }`.

---

## A. Template management (US1)

### `POST /api/internal/interview-templates` — create
- **Roles**: ADMIN, RECRUITER. Others → `403 forbidden`.
- **Body** (`CreateTemplateRequest`):
  ```json
  {
    "name": "On-site Panel",
    "durationMinutes": 60,
    "slotCadenceMinutes": 15,
    "bufferBeforeMinutes": 15,
    "bufferAfterMinutes": 15,
    "dailyCapPerInterviewer": 3,
    "requiredMemberIds": ["<memberId>"],
    "optionalMemberIds": [],
    "pools": [ { "memberIds": ["<id1>","<id2>","<id3>"], "n": 2 } ],
    "blackouts": [ { "start": "2026-07-01T00:00:00Z", "end": "2026-07-08T00:00:00Z" } ],
    "timeZoneOverride": null,
    "workingHoursOverride": null
  }
  ```
- **200**: `TemplateResponse` (the persisted template, see §C). 
- **400 `invalid_template`**: any FR-002/FR-024 validation failure; `details` carries per-field **value-free** messages (field + rule, never the submitted value — D10). Nothing persisted (SC-008).
- **Audit**: `INTERVIEW_TEMPLATE_CREATED` (workspaceId, templateId, actor memberId — no name/PII).

### `GET /api/internal/interview-templates` — list
- **Roles**: ADMIN, RECRUITER.
- **Query**: `?status=ACTIVE` (default ACTIVE) | `RETIRED` | `ALL`.
- **200**: `{ "templates": [TemplateResponse, ...] }` — scoped to the caller's workspace (FR-006).

### `GET /api/internal/interview-templates/{id}` — read one
- **200**: `TemplateResponse`. **404 `not_found`**: missing OR belongs to another workspace (indistinguishable scoped-not-found, FR-006 — not an existence oracle).

### `PUT /api/internal/interview-templates/{id}` — edit
- Body = `CreateTemplateRequest`. Same validation as create (FR-003). **200** / **400 `invalid_template`** / **404 `not_found`**.
- **Audit**: `INTERVIEW_TEMPLATE_UPDATED`.

### `POST /api/internal/interview-templates/{id}/retire` — retire (soft)
- **200**: `TemplateResponse` with `status: "RETIRED"`. Idempotent (retiring a retired template is a no-op 200). Not hard-deleted (FR-004).
- **Audit**: `INTERVIEW_TEMPLATE_RETIRED`.

### `GET /api/internal/interview-templates/presets` — code-shipped preset gallery (2026-07-26 spec)

- **200**: `{ "presets": [PresetDto, ...] }` — six static presets (`PHONE_SCREEN`, `HM_INTRO`, `TECH_DEEP_DIVE`, `PANEL_LOOP`, `HR_CULTURE`, `FINAL_ROUND`) with structural values, panel hints (`requiredCount`, `optionalShadow`, `poolN`), and `starterEmailTypes`. No workspace state; applying a preset is client-side pre-fill through the normal create path.
- **Roles**: Admin, Recruiter (class-level gate). 401 unauthenticated / 403 other roles.

---

## B. Slot computation / preview (US2, the §II demonstrable leg)

### `POST /api/internal/interview-templates/{id}/slots` — compute compliant slots
- **Roles**: ADMIN, RECRUITER (D9 — the compute path reaches the privileged `AvailabilityService`; lower roles 403).
- **Body** (`SlotPreviewRequest`): `{ "rangeStart": "2026-06-16", "rangeEnd": "2026-06-30" }` (civil dates in the applicable zone).
- **200** (`SlotComputationResult`):
  ```json
  {
    "slots": [
      {
        "start": "2026-06-16T08:00:00Z",
        "end": "2026-06-16T09:00:00Z",
        "zoneId": "Europe/London",
        "requiredMemberIds": ["<id>"],
        "qualifyingByPool": { "0": ["<id1>","<id3>"] }
      }
    ],
    "windowClamped": false,
    "unschedulable": [ { "memberId": "<id>", "reason": "NEEDS_RECONNECTION" } ]
  }
  ```
  - `slots`: empty array when nothing complies (FR-007, **not** an error).
  - `qualifyingByPool`: per-pool qualifying members (FR-010) — the stable shape F13/F14 depend on (FR-021).
  - `unschedulable`: required members whose status ≠ `DATA`, with a reason distinguishable from "busy" (FR-014) — so a caller sees *why* slots were blocked, never a silent "free".
  - `windowClamped`: `true` if the requested range exceeded `max-window` and was clamped (FR-017).
  - Response sets `Cache-Control: no-store` (availability is sensitive, member calendar-derived).
- **409 `template_retired`**: computing against a retired template — a **distinguishable** error, never an empty slot list (FR-007/AS-2.9). Recorded in audit as `INTERVIEW_TEMPLATE_COMPUTE_REFUSED` (ids only, D10) — the contract test asserts this audit row is written.
- **404 `not_found`**: unknown/foreign template id.

**Privacy**: the response carries only times + internal member ids + a coarse reason — **no** event content, **no** member email/name. `Cache-Control: no-store`.

---

## C. `TemplateResponse` shape (read model)

```json
{
  "id": "<id>",
  "workspaceId": "<wsId>",
  "name": "On-site Panel",
  "status": "ACTIVE",
  "durationMinutes": 60,
  "slotCadenceMinutes": 15,
  "bufferBeforeMinutes": 15,
  "bufferAfterMinutes": 15,
  "dailyCapPerInterviewer": 3,
  "requiredMemberIds": ["<id>"],
  "optionalMemberIds": [],
  "pools": [ { "memberIds": ["<id1>","<id2>","<id3>"], "n": 2 } ],
  "blackouts": [ { "start": "...", "end": "..." } ],
  "timeZoneOverride": null,
  "workingHoursOverride": null,
  "createdByMemberId": "<id>",
  "createdAt": "...",
  "updatedAt": "..."
}
```
`name` is returned on the management read model (the recruiter who manages it may see it) but is **never logged or audited**. No member email/display-name is ever included (ids only).

---

## D. RBAC matrix (contract test — SC-009)

| Endpoint | ADMIN | RECRUITER | HIRING_MANAGER | INTERVIEWER | READ_ONLY |
|---|---|---|---|---|---|
| `POST /interview-templates` | 200 | 200 | 403 | 403 | 403 |
| `GET /interview-templates` | 200 | 200 | 403 | 403 | 403 |
| `GET /interview-templates/{id}` | 200 | 200 | 403 | 403 | 403 |
| `PUT /interview-templates/{id}` | 200 | 200 | 403 | 403 | 403 |
| `POST /interview-templates/{id}/retire` | 200 | 200 | 403 | 403 | 403 |
| `POST /interview-templates/{id}/slots` | 200 | 200 | 403 | 403 | 403 |
| `GET /interview-templates/presets` | 200 | 200 | 403 | 403 | 403 |

Cross-workspace: a template (or a pool member reference) from another workspace is never readable/applicable — verified by a two-workspace isolation test (a foreign template id → 404; a create with a foreign-workspace member id → 400 `invalid_template`).

---

## E. Internal service contract (not an endpoint)

`RuleEngine.compute(SlotComputationRequest)` → `SlotComputationResult`:
- The request carries **only** a template id + date range — **no member list** (the member set is read from the persisted, validation-passed template). The engine passes `AvailabilityService.query` exactly those persisted member ids (D8 — the primary compute-path isolation control; a `RuleEngine` test asserts this id set). The role gate (D9) authorizes the caller but does not scope members.
- Resolves the template by `{workspaceId, id}`; `RETIRED` → throws (mapped to 409 + `INTERVIEW_TEMPLATE_COMPUTE_REFUSED` audit).
- Resolves applicable zone + working hours (override else workspace, by reference — FR-018).
- One `AvailabilityService.query(workspaceId, clampedStart, clampedEnd, distinctMemberIds)` (D1/D12).
- One `managedCalendarEvents` cap-count per required member per civil day (D5).
- Deterministic (FR-016): single snapshot, injected `Clock`, stable ordering (D14).
- Never logs `name`/availability content; logs only ids + status `.name()` Strings (D10).

`AvailabilityService.query` stays the privileged internal primitive (no caller auth) — reached only behind the controller's role gate (D9).
