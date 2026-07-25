# Contract — Email Template Library API (F21)

**Branch**: `010-email-template-library` | **Date**: 2026-06-15

All endpoints are mounted under the internal, non-allow-listed prefix `/api/internal/email-templates` on a single `EmailTemplateController` with **class-level** `@PreAuthorize("hasAnyRole('ADMIN','RECRUITER')")` (satisfies `RbacEndpointInventoryTest` for every handler). Lock/unlock handlers carry method-level `@PreAuthorize("hasRole('ADMIN')")` (most-specific wins — D6). Workspace + actor come from `@AuthenticationPrincipal SessionService.Principal` (`workspaceId()`, `memberId()`, `role()`). Error envelope reuses the F02/F03/F12 shape `{ "error": "<code>", "message": "...", "fields": { "<field>": "<value-free message>" } }` (`fields` only on `invalid_template`).

The library is keyed by `messageType` + `stageKey` (`"BASE"` or an F12 interview-template id). An un-overridden type returns its built-in default; reads are never a not-found for a known type.

---

## A. Library management (US1, US3, US4)

### `GET /api/internal/email-templates` — list the library
- **Roles**: ADMIN, RECRUITER. Others → `403 forbidden`.
- **Query**: `?stageKey=BASE` (default) lists the 8 base templates; `?stageKey=<interviewTemplateId>` lists the effective set for that stage (variant where present, else base).
- **200**: `{ "templates": [TemplateResponse, ...] }` — one per message type, scoped to the workspace. A type with no override returns its built-in default (`source: "BUILTIN"`).

### `GET /api/internal/email-templates/{messageType}` — read one (with optional stage)
- **Query**: `?stageKey=BASE|<id>`.
- **200**: `TemplateResponse`. A known `messageType` is never a 404 (built-in default if un-overridden). An **invalid** `messageType` → `404 not_found`. A variant whose `stageKey` is a foreign/unknown interview-template id → `404 not_found` (oracle-free).

### `PUT /api/internal/email-templates/{messageType}` — edit (create/replace override)
- **Body** (`EditRequest`):
  ```json
  { "stageKey": "BASE", "subject": "Your interview with {{workspace_name}}", "body": "Hi {{candidate_name}},\n\nPick a time: {{scheduling_link}}", "expectedVersion": 3 }
  ```
  `expectedVersion` is omitted/null for a first edit of an un-overridden type.
- **200**: `TemplateResponse` (the persisted override, `version` incremented).
- **400 `invalid_template`**: empty subject/body; an unknown/disallowed/malformed `{{token}}` for this type; over-cap length/token-count/variant-count. `fields` carries per-field **value-free** messages. Nothing persisted (SC-004).
- **403 `template_locked`**: the target is locked and the actor is RECRUITER (Admins may edit a locked template — D6).
- **404 `not_found`**: invalid `messageType`, or a variant `stageKey` not in this workspace.
- **409 `stale_template`**: `expectedVersion` ≠ persisted version, or a concurrent first-edit lost the unique-index race (D8).
- **Audit**: `EMAIL_TEMPLATE_EDITED`, `outcome="<messageType>/<stageKey>/<edit|create_override|variant_edit>"` (ids only).

### `POST /api/internal/email-templates/{messageType}/apply-tone` — apply a tone preset
- **Body**: `{ "stageKey": "BASE", "tone": "FRIENDLY", "expectedVersion": 3 }`.
- **200**: `TemplateResponse` with subject+body replaced by the (type, tone) starter wording (then editable). Same 400/403/409 as edit.
- **Audit**: `EMAIL_TEMPLATE_EDITED`, `outcome="<messageType>/<stageKey>/tone_apply"`.

### `POST /api/internal/email-templates/{messageType}/apply-preset-starter` — apply a preset starter variant (2026-07-26 spec)

- **Body**: `{ "stageKey": "<interviewTemplateId>", "presetKey": "PHONE_SCREEN|HM_INTRO|TECH_DEEP_DIVE|PANEL_LOOP|HR_CULTURE|FINAL_ROUND", "expectedVersion": null|N }`. `stageKey` is REQUIRED to be a stage variant — `"BASE"`/blank → 400 `invalid_template` (a starter is inherently per-stage).
- **200**: `TemplateResponse` — variant materialised (version 0) or overwritten (version++) with the (preset, type) starter wording, then freely editable. Same lock (403 `template_locked`), stage (oracle-free 404), variant-cap (400) and version (409 `stale_template`) semantics as `apply-tone`; guard ordering identical.
- **400 `invalid_template`**: unknown `presetKey`, or the preset declares no starter for this `messageType` (value-free `fields`).
- **Audit**: one `EMAIL_TEMPLATE_EDITED` row, outcome `<TYPE>/<stageKey>/preset_starter_apply`. Content never audited/logged.

### `POST /api/internal/email-templates/{messageType}/reset` — reset to default / remove variant
- **Body**: `{ "stageKey": "BASE", "expectedVersion": 3 }`.
- **200**: deletes the override (base reset → built-in default; variant reset → base) and returns the now-effective `TemplateResponse` (`source: "BUILTIN"` or the base). **Idempotent**: resetting an already-un-overridden type is a 200 no-op (no version bump, no audit — spec Edge Case).
- **403 `template_locked`** if locked and actor is RECRUITER.
- **Audit** (only when a doc was actually deleted): `EMAIL_TEMPLATE_RESET`, `outcome="<messageType>/<stageKey>/reset"`.

### `POST /api/internal/email-templates/{messageType}/lock` and `/unlock` — Admin-only
- **Roles**: **ADMIN only** (method-level `hasRole('ADMIN')`); RECRUITER → `403 forbidden` (RBAC envelope).
- **Body**: `{ "stageKey": "BASE", "expectedVersion": 3 }`.
- **200**: `TemplateResponse` with `locked` flipped, `version++`. Locking an un-overridden type first materialises the override from the built-in default, then locks it.
- **409 `stale_template`** on version mismatch.
- **Audit**: `EMAIL_TEMPLATE_LOCKED` / `EMAIL_TEMPLATE_UNLOCKED`, `outcome="<messageType>/<stageKey>"`.

---

## B. Render / preview (US2, the §II demonstrable leg)

### `POST /api/internal/email-templates/{messageType}/preview` — render with merge values
- **Roles**: ADMIN, RECRUITER (same as viewing — FR-016).
- **Body** (`PreviewRequest`):
  ```json
  { "stageKey": "BASE", "tone": null, "candidateId": "<id>", "sampleValues": null }
  ```
  Either `candidateId` (workspace-scoped — D7) **or** `sampleValues` (a `{token: value}` map; absent tokens exercise the missing-field path). `tone` previews an un-applied preset without persisting.
- **200** (`RenderedMessageResponse`), `Cache-Control: no-store`. Example for body `"Hi {{candidate_name}},\n\nYour interview is on {{interview_date}}. Pick a time: {{scheduling_link}}"` with `candidate_name="Dana"`, `scheduling_link="https://cadence.app/s/abc123"`, and `interview_date` **absent** (illustrating the missing-field marker in the body — the marker MUST appear in `bodyText` *and* `bodyHtml`, not only in `missingFields`):
  ```json
  {
    "subject": "Your interview with Acme",
    "bodyText": "Hi Dana,\n\nYour interview is on [[missing:interview_date]]. Pick a time: https://cadence.app/s/abc123",
    "bodyHtml": "Hi Dana,<br><br>Your interview is on [[missing:interview_date]]. Pick a time: <a href=\"https://cadence.app/s/abc123\">https://cadence.app/s/abc123</a>",
    "missingFields": ["interview_date"]
  }
  ```
  - `subject`: single line; control + line-separator chars (`U+0000-U+001F, U+007F-U+009F, U+2028, U+2029`) stripped — a distinct transform, **not** HTML-escaped (FR-015, SC-003).
  - `bodyHtml`: order-pinned `htmlEscape(body,"UTF-8")` → substitute (values escaped) → `\n`→`<br>`; a `<script>` value renders inert; URL-typed tokens as `href==text` anchors, scheme-restricted to `http(s)` (a `javascript:` value → `[[invalid_url:<token>]]`) — no spoofing, no active scheme (FR-016, SC-003/SC-006).
  - `missingFields`: tokens whose value was **absent or empty**, ordered by first occurrence; each appears in `bodyText`/`bodyHtml` as `[[missing:<token>]]` — never a raw `{{token}}`, never a silent blank (FR-014, SC-002). Empty list on a complete render.
  - Deterministic: identical request → byte-identical `subject`/`bodyText`/`bodyHtml` + identical `missingFields` order (FR-013).
- **404 `not_found`**: invalid `messageType`, foreign/unknown variant `stageKey`, or a `candidateId` not in this workspace (oracle-free — D7).
- **Privacy**: rendered content (may contain candidate PII) is **never persisted and never logged** on success **or** error (FR-018/FR-019); response `no-store`. F21 dispatches nothing (rendering is side-effect-free — SC-010).

---

## C. `TemplateResponse` shape (read model)

```json
{
  "messageType": "INVITATION",
  "stageKey": "BASE",
  "subject": "Your interview with {{workspace_name}}",
  "body": "Hi {{candidate_name}},\n\nPick a time: {{scheduling_link}}",
  "locked": false,
  "version": 3,
  "source": "OVERRIDE",
  "permittedTokens": ["candidate_name","recruiter_name","workspace_name","stage_name","scheduling_link","time_zone","expected_date"],
  "updatedByMemberId": "<id>",
  "updatedAt": "..."
}
```
`source` ∈ `{ "BUILTIN", "OVERRIDE" }` (BUILTIN = no stored doc yet; subject/body are the default). `permittedTokens` lets the editor offer only valid tokens for the type. `subject`/`body` are returned on the management read model but are **never logged or audited**. No member email/display-name is ever included (ids only).

---

## D. RBAC matrix (contract test — SC-005/SC-007)

| Endpoint | ADMIN | RECRUITER (unlocked) | RECRUITER (locked) | HIRING_MANAGER | INTERVIEWER | READ_ONLY |
|---|---|---|---|---|---|---|
| `GET /email-templates` (list) | 200 | 200 | 200 | 403 | 403 | 403 |
| `GET /email-templates/{type}` | 200 | 200 | 200 | 403 | 403 | 403 |
| `POST .../preview` | 200 | 200 | 200 | 403 | 403 | 403 |
| `PUT /email-templates/{type}` | 200 | 200 | **403 `template_locked`** | 403 | 403 | 403 |
| `POST .../apply-tone` | 200 | 200 | **403 `template_locked`** | 403 | 403 | 403 |
| `POST .../reset` | 200 | 200 | **403 `template_locked`** | 403 | 403 | 403 |
| `POST .../lock` and `/unlock` | 200 | **403 forbidden** | **403 forbidden** | 403 | 403 | 403 |

The HM/Interviewer/Read-only `403`s are the RBAC `@PreAuthorize` envelope (`{error:"forbidden"}`); the locked-edit `403`s are the service envelope (`{error:"template_locked"}`); the Recruiter-lock `403` is the method-level RBAC envelope. Cross-workspace: a foreign variant `stageKey` → 404; a foreign `candidateId` in preview → 404 (oracle-free).

---

## E. Internal render contract (not an endpoint)

`MergeRenderer.render(EmailTemplate effective, Map<String,String> values)` → `RenderedMessage`:
- Resolves the effective template (variant → base → built-in) **before** calling the renderer (the service does resolution; the renderer is pure).
- Substitutes **only** catalogue tokens permitted for the type; **every** occurrence (D4).
- Absent **or** empty value → `[[missing:<token>]]` + adds to `missingFields` (first-occurrence order) (FR-014).
- Neutralises per channel — **two distinct transforms** (D3): the **body** is `htmlEscape(…,"UTF-8")` (escape-then-substitute, order-pinned) with values escaped and URL tokens as `http(s)`-scheme-checked `href==text` anchors; the **subject** is control + line-separator stripped (`U+0000-U+001F, U+007F-U+009F, U+2028, U+2029`), **not** HTML-escaped (FR-015/FR-016).
- Deterministic + **side-effect-free** — no dispatch, no persistence, and **no message-transport type (`EmailSender`/SMTP) is a field/constructor dependency of `MergeRenderer` or `EmailTemplateService`**, asserted structurally (SC-010), not by a vacuous "render returned without sending". F22 owns dispatch under the no-auto-send contract (FR-020).
- Never logs subject/body/values; value-free on every error path (FR-018/FR-019).
