# Phase 1 Data Model — Email Template Library (F21)

**Branch**: `010-email-template-library` | **Date**: 2026-06-15

One **new** persisted collection (`emailTemplates`); code-shipped catalogues (built-in defaults, tone presets, merge tokens); transient (non-persisted) render types. **No candidate PII and no secret is stored** — only recruiter-authored content + internal id references — so **no encryption converter** is needed (asserted by a raw-driver test, mirroring `interviewTemplates`/`managedCalendarEvents`). Template content is *workspace authoring data*, not candidate PII (see plan §VIII posture).

---

## 1. Persisted: `EmailTemplate` (`@Document("emailTemplates")`)

A per-workspace **override** of one message type, optionally scoped to one F12 interview stage. An un-overridden type has **no document** and renders the built-in default (D1).

| Field | Type | Notes |
|---|---|---|
| `id` | `String` (`@Id`) | Mongo ObjectId hex. |
| `workspaceId` | `String` | Owning workspace. Every read/write filters on it (FR-009). |
| `messageType` | `EmailMessageType` enum | INVITATION / CONFIRMATION / REMINDER_24H / REMINDER_1H / HOLD_UPDATE / REJECTION / FEEDBACK_REQUEST / SLA_HOLDING. |
| `stageKey` | `String` | **NEVER null** (D2). `"BASE"` for the base override, or an F12 `interviewTemplates._id` for a per-stage variant. |
| `subject` | `String` | Plain text + `{{tokens}}`; non-empty; ≤ `max-subject-length`. Authoring data, not PII; never logged/audited. |
| `body` | `String` | Plain text + `{{tokens}}`; non-empty; ≤ `max-body-length`; ≤ `max-tokens-per-template` tokens. Never logged/audited. |
| `locked` | `boolean` | Admin-only governance flag (FR-010). Default false. |
| `version` | `Long` (`@Version`) | Optimistic concurrency (D8). |
| `createdByMemberId` / `updatedByMemberId` | `String` | Actor ids only — never an email/display-name snapshot. |
| `createdAt` / `updatedAt` | `Instant` | From the injected `Clock`. |

**`EmailMessageType`** (new enum): the 8 types above.

**`toString()`**: explicit. MUST omit `subject` and `body` (authoring content — never leak via logs). MAY include `id`, `workspaceId`, `messageType`, `stageKey`, `locked`, `version` (all non-PII).

### Validation rules (service layer, FR-002/FR-004/FR-020) — all value-free messages

- `subject` non-empty and ≤ `max-subject-length`; `body` non-empty and ≤ `max-body-length`.
- Every `\{\{[^}]*\}\}` occurrence in `subject`+`body` is a `MergeToken` **permitted for `messageType`** (D4/D5); else reject (which token / which rule — never the value). Token lexis is exactly `\{\{[a-z_]+\}\}`.
- Total token occurrences ≤ `max-tokens-per-template`.
- For a variant (`stageKey != "BASE"`): `stageKey` resolves via `InterviewTemplateRepository.findByWorkspaceIdAndId(workspaceId, stageKey)` → else `ScopedNotFoundException` (404, oracle-free, FR-006-style). Per type, count of existing variants ≤ `max-variants-per-type`. The literal `"BASE"` is a **reserved** stageKey: the variant-create path rejects a client-supplied `stageKey == "BASE"` (it is set only by the base path). Collision is structurally impossible regardless (F12 ids are 24-char ObjectId hex; `"BASE"` is not valid hex) — the reserved-word guard is defence-in-depth against a future non-ObjectId id scheme (Backend finding).
- Lock/edit interaction: if the target doc exists and `locked == true` and the actor is **not** ADMIN → `TemplateLockedException` (403).
- `expectedVersion` (edit/tone/lock/reset) must match the persisted `version` (or the type must be un-overridden for a first edit) → else `StaleTemplateException` (409).

### State / lifecycle

- **Un-overridden** (no doc) → **Override** (first edit/tone/lock — insert, `version=0`) → **Override** (subsequent edit/tone/lock/unlock — `version++`) → **Un-overridden** (reset — delete the override doc so resolution falls back to the built-in default; D1).
- **Reset** of an already-un-overridden type, or of a base when only built-in exists, is an idempotent no-op (no version bump, no misleading audit — spec Edge Case).
- A **variant** reset deletes only that variant doc; rendering for its stage falls back to the base override (or built-in default).
- No hard-delete of audit history; the audit trail retains the prior change record (FR-007).

---

## 2. Code-shipped catalogues (NOT persisted)

**`BuiltInEmailTemplates`** (D1): `Map<EmailMessageType, {subject, body}>` loaded from `resources/email-templates/builtin/*.txt` (EN). Non-empty for every type (SC-001). The render fall-back floor.

**`TonePresetCatalogue`** (D10): `Map<(EmailMessageType, TonePreset), {subject, body}>` from `resources/email-templates/tone/*`. `TonePreset` enum = `FORMAL`, `FRIENDLY`, `CONCISE`. Apply replaces the override's subject+body.

**`MergeTokenCatalogue`** (D5): the allow-list + the per-type permitted subset + the URL-typed set. See §3.

---

## 3. Merge token catalogue (`MergeToken` enum) — fixed, add-only

| Token | URL-typed | Typical source (filled by F13/F20/F22/F32 at render) |
|---|---|---|
| `candidate_name` | no | Candidate (decrypted, preview/dispatch) |
| `recruiter_name` | no | Member display name |
| `workspace_name` | no | Workspace config |
| `stage_name` | no | F12 interview template name |
| `interview_date` | no | Booking (F13) |
| `interview_time` | no | Booking (F13) |
| `time_zone` | no | Booking / workspace |
| `location` | no | Recruiter-provided location/dial-in text |
| `scheduling_link` | **yes** | F13/F14 single-use token URL |
| `status_link` | **yes** | F30 status-page token URL |
| `reschedule_link` | **yes** | F20 reschedule token URL |
| `feedback_link` | **yes** | F32 scorecard token URL |
| `expected_date` | no | F30 expected date |

**Per-type permitted subset** (illustrative — pinned exactly in `MergeTokenCatalogue`): every type permits `candidate_name`, `recruiter_name`, `workspace_name`. `INVITATION` adds `stage_name`, `scheduling_link`, `time_zone`, `expected_date`. `CONFIRMATION`/`REMINDER_24H`/`REMINDER_1H` add `stage_name`, `interview_date`, `interview_time`, `time_zone`, `location`, `reschedule_link`. `HOLD_UPDATE` adds `status_link`, `expected_date`. `REJECTION` adds `status_link` (no scheduling/reschedule). `FEEDBACK_REQUEST` adds `stage_name`, `feedback_link`. `SLA_HOLDING` adds `status_link`, `expected_date`. URL-typed tokens are **system-produced** (never recruiter free-text) and render as an anchor with `href == visible text` (D3, FR-016).

---

## 4. Transient (NOT persisted) — render I/O

**`PreviewRequest`** (DTO): `{ EmailMessageType messageType; String stageKey?; TonePreset tone?; String candidateId?; Map<String,String> sampleValues? }` — preview uses `candidateId` (workspace-scoped, D7) or `sampleValues`; absent values exercise the missing-field path.

**`RenderedMessage`** (transient model + response): `{ String subject; String bodyText; String bodyHtml; List<String> missingFields }`. Subject and body are **distinct transforms** (D3), never sharing an encoder:
- `subject`: token-substituted plain text, then strip `[U+0000-U+001F, U+007F-U+009F, U+2028, U+2029]` (control + line separators). **Not** HTML-escaped (a subject is plain text).
- `bodyText`: token-substituted authored text, verbatim (newlines preserved).
- `bodyHtml`: the **order-pinned** algorithm (so byte-identical determinism is reproducible — the QA finding): (1) normalise `\r\n`/`\r`→`\n`; (2) `HtmlUtils.htmlEscape(authoredBody, "UTF-8")` (UTF-8 overload — not the ISO-8859-1 default); (3) substitute each token with `htmlEscape(value,"UTF-8")` for plain tokens, or `<a href="ESC">ESC</a>` for URL-typed tokens whose value passes the `http(s)` scheme allow-list (else `[[invalid_url:<token>]]`); (4) `\n`→`<br>`. Tokens survive step 2 because `{{`/`}}` are not escaped.
- `missingFields`: tokens whose value was absent **or** empty — each rendered in-place as `[[missing:<token>]]` (D4, FR-014). **Ordered by first occurrence in the body then subject** (stable across runs/JVMs — not a `Set`/`Map` iteration order). Empty list on a complete render.
- Deterministic: identical (template, values) → byte-identical `subject`/`bodyText`/`bodyHtml` and identical `missingFields` order (FR-013).

---

## 5. Relationships

```
BuiltInEmailTemplates (code) ──fallback floor──▶ EmailTemplate override (workspace edit) ──resolved by reference──▶ MergeRenderer
InterviewTemplate (F12) ──id referenced by──▶ EmailTemplate.stageKey (variant; workspace-validated, D7)
Candidate (F04) ──decrypted name/email (preview, scoped read)──▶ merge values ──▶ MergeRenderer ──▶ RenderedMessage (consumed by F22 dispatch / preview UI)
EmailTemplateService ──audits──▶ authAuditLog (ids + type/stage/kind only, D9)
```

Resolution order for `(messageType, stageKey)`: variant override (`stageKey=<id>`) → base override (`stageKey="BASE"`) → built-in default.

---

## 6. Index summary (ChangeUnit009, order "009")

| Collection | Index | Unique | Purpose |
|---|---|---|---|
| `emailTemplates` | `{workspaceId, messageType, stageKey}` | **yes** | One override per type+stage; discriminates base (`"BASE"`) vs variants (D2). Prefix `{workspaceId}` / `{workspaceId, messageType}` also backs library/variant listing. |

All three fields non-null (`stageKey` sentinel `"BASE"`) → no `@Field(write=NON_NULL)` partial-index footgun (CLAUDE.md F01 lesson). Native `createIndex` with `IndexOptions().unique(true)`; targeted `dropIndex` rollback (never `dropIndexes()`). Order `"009"` off the highest **applied** changeset `008`, NOT the branch number.
