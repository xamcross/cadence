# Feature Specification: Email Template Library

**Feature Branch**: `010-email-template-library`
**Created**: 2026-06-15
**Status**: Draft
**Backlog ID**: F21 (Tier 1 — Critical Path, P1; Email Infrastructure)
**Input**: User description: "F21 Email Template Library - default templates, merge fields, tone presets, per-stage variants, and admin template locking"

## Overview

Every outbound message Cadence sends to a candidate or interviewer — the self-scheduling invitation, the booking confirmation, the 24-hour and 1-hour reminders, a hold/update notice, a rejection, a feedback-request, an SLA holding message — needs wording that matches the workspace's brand voice, the candidate's actual details merged in, and a guarantee that nothing is ever sent that a human or a system event did not authorise. Today none of that content exists: the calendar adapters (F10/F11) can write events and the rule engine (F12) can compute slots, but there is no library of *what the emails say* and no safe way to fill a template with a specific candidate's name, date, and link.

F21 delivers that library and the rendering layer that turns a template plus a candidate's data into a finished, safe-to-send message. It does **not** send anything — transactional delivery, the consent/erasure dispatch gate, and scheduled reminders are F22; SLA breach detection and SLA-draft creation are F31; the candidate-facing pages are F13/F14. F21 is the content and rendering foundation those features compose.

This feature delivers:

1. **A managed template library** — for each defined message type the workspace has an editable subject + body built from merge tokens (`{{candidate_name}}`, `{{interview_date}}`, `{{scheduling_link}}`, …). The system ships a built-in default for every type so a workspace can send sensible messages before anyone edits a word; an Admin (and, for unlocked templates, a Recruiter) can edit the wording, optionally starting from a **tone preset** (a small fixed set of starter wordings such as Formal / Friendly / Concise). A template may carry an optional **per-stage variant** keyed to an F12 interview stage (e.g. a confirmation worded differently for "Onsite" than for "Phone Screen"); resolution prefers the stage variant and falls back to the base. An **Admin can lock** a template so Recruiters cannot edit it. Every change is versioned and recorded in the audit trail.

2. **A safe rendering + preview layer** — given a template and a set of merge values (sample data, or a selected real candidate's data), the engine produces the final subject and body by substituting only allow-listed merge tokens. A token whose value is missing renders a **visible, human-readable warning** — never a raw `{{token}}` and never a silent blank — and the render result flags which fields were missing. Merge values are neutralised so candidate-supplied text (e.g. a name containing markup) cannot inject active content. A permitted member can preview the rendered result before it is ever used. Rendering has no side effects: F21 never dispatches; it produces renderable content for F22.

F21 also establishes the **dispatch-authorisation contract** that F22 enforces: a message produced from a template is never auto-sent. Dispatch requires an explicit basis — either a system-event trigger (e.g. a calendar confirmation) or an explicit recruiter one-click approval. No unattended auto-send path exists in the MVP (SLA auto-send is deferred to v1.5). F21 supplies the renderable content and the lock/approval metadata; F31 generates SLA drafts; F22 enforces the gate at the moment of dispatch.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - View and edit the workspace template library (Priority: P1)

As an Admin (or, for unlocked templates, a Recruiter), I can view every message template in my workspace and edit its subject and body — optionally starting from a tone preset — so that all outgoing email matches our brand voice without an engineer touching code.

**Why this priority**: A library that cannot be viewed or edited has no value, and it is the foundational data the rendering layer and every downstream email feature operate on. It is independently demonstrable: a member opens the library, reads a default template, edits it, and reads the change back, with no email having been sent.

**Independent Test**: As an Admin, list the templates and confirm every defined message type is present with a sensible built-in default subject + body; edit the invitation template's wording and confirm the change persists and is read back; apply a tone preset to the confirmation template and confirm the body is replaced with that preset's starter wording (and is then freely editable). As an Interviewer or Read-only member, attempt to view or edit and confirm the result matches the role rules (edit refused with HTTP 403).

**Acceptance Scenarios**:

1. **Given** a workspace that has never edited its library, **When** an Admin or Recruiter lists the templates, **Then** every defined message type (invitation, confirmation, reminder-24h, reminder-1h, hold/update, rejection, feedback-request, SLA-holding) is returned with a non-empty built-in default subject and body.
2. **Given** a permitted member and a valid edit (subject + body using only allow-listed merge tokens), **When** they save it, **Then** the edit is persisted as the workspace's version of that template, the built-in default is left intact, the template's version number increments, and the change is recorded in the audit trail.
3. **Given** a template body containing an unknown or unsupported merge token (or one not permitted for that message type), **When** it is submitted, **Then** the whole save is refused with a **value-free** message (which token, which rule — never echoing candidate data) and nothing is persisted.
4. **Given** a tone preset, **When** a member applies it to a template, **Then** the template's subject and body are replaced with that preset's starter wording (a starting point, not a runtime toggle), the change is versioned and audited, and the wording remains freely editable afterward.
5. **Given** a member edits a template, **When** the edit succeeds, **Then** the action is recorded in the audit trail using internal identifiers + the template type/stage only — never the body text, the rendered content, or any candidate PII.

---

### User Story 2 - Preview a rendered template with merge fields (Priority: P1)

As a Recruiter or Admin, I can preview a template rendered with a specific candidate's details (or with sample data), so that I can verify exactly what the recipient will see — including that every merge field resolves and nothing is broken — before the message is ever used.

**Why this priority**: An unpreviewed template is a brand and correctness risk; a broken `{{merge_field}}` reaching a candidate is exactly the failure this feature exists to prevent. It is the core safety value of F21 and is independently demonstrable: with a template and a set of merge values, the renderer produces a finished subject + body, and a missing value produces a visible warning rather than a broken placeholder.

**Independent Test**: Render the invitation template with a complete set of sample merge values and confirm the output subject + body contain the substituted values and **no** residual `{{token}}`; render the same template with one merge value deliberately absent and confirm the output shows a visible, human-readable warning in place of that field (not a raw `{{token}}`, not a silent blank) and the render result flags that field as missing; render with a merge value containing markup/script-like text and confirm the rendered output neutralises it (no active content injected).

**Acceptance Scenarios**:

1. **Given** a template and a complete set of merge values, **When** it is rendered, **Then** every allow-listed token is replaced with its value and the output contains no residual `{{...}}` tokens.
2. **Given** a template and a set of merge values missing one required field, **When** it is rendered, **Then** the missing field is replaced with a visible, human-readable warning placeholder (never a raw token, never a silent blank) and the render result identifies which fields were missing.
3. **Given** a merge value that contains markup or active-content characters (e.g. a candidate name with HTML/script), **When** it is rendered, **Then** the value is neutralised so it appears as literal text and cannot inject active content into the message.
4. **Given** a Recruiter or Admin, **When** they request a preview using a selected candidate's data, **Then** the rendered content is returned for display only, is not persisted, is not written to logs at any level, and the preview path is role-gated (a role that cannot view the library cannot preview).
5. **Given** the same template and the same merge values, **When** rendering runs twice, **Then** the output is byte-identical (deterministic) and rendering has no side effect (no message is dispatched).

---

### User Story 3 - Admin template locking (Priority: P2)

As an Admin, I can lock specific templates so that Recruiters cannot edit them, ensuring legally- or brand-sensitive wording (e.g. rejection, GDPR-related notices) cannot be changed by a Recruiter.

**Why this priority**: Locking is template governance — a refinement on top of the editable library (Stories 1–2), independently testable, but the library is usable without it. It protects the highest-risk wording.

**Independent Test**: As an Admin, lock the rejection template; confirm a Recruiter's attempt to edit it, apply a tone preset to it, or reset it is refused (HTTP 403 / forbidden action) with no state change, while the Recruiter can still view and preview it; confirm the Admin can still edit it and can unlock it; confirm lock/unlock is versioned and audited.

**Acceptance Scenarios**:

1. **Given** a template, **When** an Admin marks it locked, **Then** the lock state is persisted, versioned, and audited.
2. **Given** a locked template, **When** a Recruiter attempts to edit it, apply a tone preset, change its per-stage variant, or reset it, **Then** the action is refused (HTTP 403) and no state changes; the Recruiter can still view and preview it.
3. **Given** a locked template, **When** an Admin edits or unlocks it, **Then** the action succeeds (lock governs Recruiters, not Admins) and is versioned and audited.
4. **Given** lock is an Admin-only control, **When** a Recruiter attempts to lock or unlock any template, **Then** the request is refused with HTTP 403 and no state change.

---

### User Story 4 - Per-stage template variants (Priority: P3)

As an Admin or Recruiter, I can create a per-stage variant of a message type (keyed to an F12 interview stage), so that, for example, the confirmation email for an "Onsite" reads differently from the "Phone Screen" confirmation, while every other stage falls back to the base template.

**Why this priority**: Variants are a polish refinement; the library is fully usable with base templates alone, and variant resolution simply prefers a stage-specific override when present. Independently testable.

**Independent Test**: Create a confirmation variant for a specific interview stage; render the confirmation for that stage and confirm the variant wording is used; render the confirmation for a stage with no variant and confirm the base template is used (fall-back); retire the variant and confirm rendering falls back to the base again; confirm a variant referencing a non-existent or foreign-workspace stage is refused.

**Acceptance Scenarios**:

1. **Given** a base template and a per-stage variant for stage X, **When** a message is rendered for stage X, **Then** the variant's wording is used; **When** rendered for a stage with no variant, **Then** the base template is used.
2. **Given** a variant keyed to a stage that does not exist in the workspace (or belongs to another workspace), **When** it is submitted, **Then** it is refused with an indistinguishable scoped not-found (no cross-workspace existence oracle) and nothing is persisted.
3. **Given** a per-stage variant, **When** it is retired/reset, **Then** rendering for that stage falls back to the base template, and the variant's prior existence remains resolvable in the audit trail.
4. **Given** a locked base template, **When** a Recruiter attempts to add or change a variant of it, **Then** the action is refused (lock governs the type, including its variants).

---

### Edge Cases

- **Missing or empty merge value at render time**: an absent value — and a present-but-empty (`""`) value — is replaced with a visible, machine-detectable warning placeholder (e.g. a clearly-marked `[missing: candidate name]`), never a raw `{{token}}` and never a silent empty string; the render result enumerates the missing fields so the caller can warn or block. (Absent and empty are treated identically — an empty value can never silently render as a blank.)
- **Repeated token in one body/subject**: every occurrence of a token is substituted, not just the first.
- **Unknown / unsupported / disallowed merge token at save time**: rejected at save with a value-free message — a template that *could* render a broken `{{token}}` can never be persisted (validation is at authoring time; the render-time warning is the safety net for genuinely absent data).
- **Malformed or partial token syntax at save time**: a single brace (`{{name}`), an empty token (`{{}}`), an unknown token with whitespace padding, or a stray literal `{{` must each have a defined outcome (rejected at save vs treated as literal text); the lexical rules for what constitutes a token are pinned in `plan.md` so a "broken `{{...}}`" can never survive to a candidate-facing render.
- **Merge value containing markup or active content** (candidate name = `<script>…`): neutralised by channel-appropriate output encoding on render (HTML-entity-encoded in the body); the rendered message can never carry injected active content (template/HTML-injection defence).
- **Merge value containing CR/LF or control characters routed into the subject line**: stripped/rejected so it cannot inject SMTP headers (`\r\nBcc:` …) — the subject is a distinct injection sink from the body.
- **Recruiter pastes a real candidate's details as literal text into the body** (not via a token): this makes stored template content carry PII outside the merge-token model; this is an author responsibility and is documented as out of the retention/erasure model (template content is workspace authoring data, not candidate PII — see Assumptions).
- **Idempotent reset / retire**: resetting an override that is already at its built-in default, or retiring an already-retired variant, is a no-op (defined outcome, not an error) and does not spuriously bump the version or emit a misleading audit entry; the outcome is specified so a test can assert it.
- **Recruiter edits a locked template**: refused with HTTP 403, no state change; view and preview remain allowed.
- **Recruiter attempts to lock/unlock**: refused with HTTP 403 (lock is Admin-only).
- **Template type with no override yet**: the built-in default is returned; reading a never-edited template is never a not-found.
- **Per-stage variant for a non-existent or foreign-workspace stage**: indistinguishable scoped not-found; never reveals whether a foreign stage exists.
- **Oversized template**: a subject/body beyond the configured length cap, a body exceeding the configured merge-token count, or more variants than the per-type cap, is rejected at save (bounds against abuse and oversized renders).
- **Empty subject or body on save**: rejected — a usable template must have a non-empty subject and body (the built-in default is always non-empty).
- **Preview against an erased / no-consent candidate**: preview renders for display only and never dispatches, so the consent/erasure gate (F04/F22) is not triggered here; but the preview path must not leak the rendered PII to logs. (The dispatch-time consent/erasure check is F22.)
- **Concurrent edits to the same template** (two Admins): the stale write is detected via the template's version (optimistic concurrency) and refused, so an edit is never silently lost.
- **Built-in default changes in a future release**: a workspace that has overridden a template keeps its override; a workspace that has not yet overridden inherits the new built-in default (resolution is by reference at render time, not a copy taken at first view).

## Requirements *(mandatory)*

### Functional Requirements

**Library & template management**

- **FR-001**: The system MUST provide, per workspace, a managed template for each defined message type — at minimum: scheduling **invitation**, booking **confirmation**, **reminder-24h**, **reminder-1h**, **hold/update**, **rejection**, **feedback-request**, and **SLA-holding** — each consisting of a subject and a body composed of allow-listed merge tokens, with a non-empty **built-in default** shipped for every type so a workspace can produce sensible messages before any edit.
- **FR-002**: The system MUST allow a permitted member to view any template in their workspace; a never-edited template MUST return its built-in default (reading an un-overridden template is never a not-found).
- **FR-003**: The system MUST allow a permitted member to edit a template's subject and body, persisting the edit as the workspace's version (an override) without mutating the built-in default, validating the edit before persisting, and persisting nothing on any validation failure (no partial write).
- **FR-004**: The system MUST validate, at save time, that every merge token in a subject/body is drawn from the allow-list **permitted for that message type**; any unknown, unsupported, or disallowed token MUST refuse the whole save with a **value-free** message (which token, which rule), so a template that could render a broken `{{token}}` can never be persisted.
- **FR-005**: The system MUST allow a member to apply a **tone preset** (a small fixed set of starter wordings) to a template as a starting point — replacing the subject + body with the preset's wording, after which it remains freely editable — and MUST version and audit the change.
- **FR-006**: The system MUST support an optional **per-stage variant** of a message type keyed to an F12 interview stage template; rendering MUST prefer the stage variant when present and fall back to the base template otherwise. A variant referencing a stage that does not exist in the workspace MUST be refused with an indistinguishable scoped not-found (no cross-workspace existence oracle).
- **FR-007**: The system MUST allow retiring/resetting a per-stage variant or a workspace override such that rendering falls back to the base template (or built-in default) WITHOUT hard-deleting the change history (the prior state remains resolvable in the audit trail).
- **FR-008**: The system MUST enforce role rules consistent with the F02 deny-by-default model: viewing and previewing the library MUST be restricted to **Recruiter and Admin**; editing, applying a tone preset, and changing a variant MUST be restricted to **Recruiter (on unlocked templates) and Admin**; locking/unlocking MUST be **Admin-only**. Any role outside the permitted set MUST be refused with HTTP 403 and no state change.
- **FR-009**: Templates and variants MUST be scoped to a single workspace; a member MUST NOT view, edit, lock, or render a template (or reference a stage) belonging to another workspace. Resolution MUST be by `{workspaceId, …}` so a foreign id is an indistinguishable not-found (per the F02 scoped-not-found pattern), never an existence oracle.

**Locking & governance**

- **FR-010**: The system MUST allow an Admin to lock and unlock a template; while locked, any Recruiter attempt to edit it, apply a tone preset, change its variant, or reset it MUST be refused with HTTP 403 and no state change, while a Recruiter MUST still be able to view and preview it. An Admin MUST be able to edit and unlock a locked template (lock governs Recruiters, not Admins). Lock applies to the message type including its variants.
- **FR-011**: The system MUST version every template change (override create, edit, tone-apply, lock, unlock, variant add/change, variant/override reset) with a monotonically increasing version, and MUST use that version for optimistic concurrency so a stale concurrent write is refused rather than silently overwriting a newer edit.
- **FR-012**: The system MUST record every template lifecycle action in an append-only audit trail using internal identifiers only — workspace id, actor member id, timestamp, the template type/stage, and the kind of change — and MUST NOT write the subject, body, rendered content, tone-preset content, or any candidate/participant PII to the audit trail.

**Rendering & preview**

- **FR-013**: The system MUST render a template by substituting **only** allow-listed merge tokens with the supplied merge values, replacing **every** occurrence of each token (not just the first), producing a final subject and body; rendering MUST be deterministic for identical inputs (byte-identical output) and MUST have no side effects (it MUST NOT dispatch any message, and no message-transport capability may be reachable from the render path — dispatch is F22).
- **FR-014**: At render time, a merge token whose value is **absent** MUST be replaced with a visible, machine-detectable warning placeholder carrying a fixed sentinel marker (never a raw `{{token}}`, never a silent blank); the render result MUST enumerate which fields were missing so the caller can warn or block. A token whose value is **present but empty** MUST be treated identically to absent (warning placeholder + flagged missing) so an empty value can never silently render as a blank. The exact placeholder string and sentinel are pinned in `plan.md`; tests assert the detectable marker, not human judgement.
- **FR-015**: The system MUST neutralise merge values with **channel-appropriate output encoding** so that candidate- or recruiter-supplied content cannot inject active content or break message structure: merge values placed in the HTML body MUST be HTML-entity-encoded (a value such as `<script>` renders as inert visible text, never executes); merge values placed in the **subject line** MUST have CR, LF, and other control characters stripped/rejected (SMTP-header / CRLF-injection defence — the subject is a distinct sink from the body). The render output channel (HTML body with a plain-text alternative) and the exact encoding strategy are pinned in `plan.md`.
- **FR-016**: The system MUST treat the URL-bearing tokens (`scheduling_link`, `status_link`, `reschedule_link`, `feedback_link`) as **system-produced** values, never recruiter free-text, and MUST ensure that recruiter-authored body content surrounding a link token cannot wrap, rewrite, or spoof the token's URL (no breaking out of a link context). Recruiter-supplied body markup is subject to the same neutralisation discipline as candidate values for the purpose of preventing a misleading/phishing link.
- **FR-017**: The system MUST allow a permitted member to preview a rendered template using either sample merge data or a selected candidate's data; the preview MUST be read-only (persist nothing), MUST be role-gated identically to library viewing, MUST resolve the candidate by `{workspaceId, candidateId}` so a foreign-workspace candidate id is an indistinguishable scoped not-found (no cross-workspace candidate-existence oracle, no PII-exfil path), and its rendered content MUST NOT be written to logs at any level.
- **FR-018**: All render and preview error/diagnostic paths (substitution errors, validation failures, unexpected exceptions) MUST produce **value-free** diagnostics — the offending merge value, subject, body, or candidate PII MUST NEVER appear in an exception message, stack-trace argument, or log line (so an error path cannot become the PII-leak vector the happy-path log scan does not exercise).
- **FR-019**: The rendered-message output shape (final subject, final body, the list of missing-field warnings) MUST be stable and documented so the email delivery channel (F22, the consumer) and the preview UI can depend on it — verified by a contract test (response body, status codes, error envelope, and the missing-field-warning case, not only the happy path).

**Dispatch-authorisation contract (enforced by F22, drafts by F31)**

- **FR-020**: The system MUST establish that a message produced from a template is never auto-dispatched: F22 MUST NOT dispatch such a message without an explicit authorisation basis — either a **system-event trigger** (e.g. a calendar confirmation) or an explicit **recruiter one-click approval**. No unattended auto-send code path may exist in the MVP (SLA auto-send is deferred to v1.5). F21's own verifiable contribution is twofold: (a) rendering is side-effect-free with no transport capability reachable from the render path (FR-013), and (b) F21 supplies the lock/approval metadata F22's gate reads. The cross-feature enforcement (a dispatch requires a trigger or approval) is owned and verified by F22; F31 generates SLA drafts.

**Secrecy & limits**

- **FR-021**: The system MUST NOT write template subject/body text, rendered message content, tone-preset content, or any candidate/participant/recipient PII (names, emails) to logs at any level during template management, rendering, or preview; only internal identifiers (workspace, template, member object ids) and type/stage enum values (as strings, never via structured-argument enum interpolation — the F01.1 logstash footgun) may be logged.
- **FR-022**: The system MUST enforce configured upper bounds on template size (subject length, body length, merge-token count per template) and on the number of per-stage variants per type, so that a single template can neither be used to exhaust storage **nor amplify render-time substitution work** (an adversarial template repeating a token to force an oversized render). A subject or body that is empty, or that exceeds a bound, MUST be refused at save.

### Key Entities *(include if feature involves data)*

- **Email Template**: A workspace-scoped, editable definition of one message type. Attributes: owning workspace, message type (invitation / confirmation / reminder-24h / reminder-1h / hold-update / rejection / feedback-request / SLA-holding), subject, body (allow-listed merge tokens), locked flag, version, and audit metadata (actor member id + timestamp for created/edited — never an email or display-name snapshot). The built-in default for each type is system-supplied and applies until the workspace overrides it. Holds no candidate PII and no rendered content — only authoring content + internal ids.
- **Per-Stage Variant**: An optional override of a message type keyed to an F12 interview stage template within the same workspace. Same content + lock + version semantics as the base; resolution prefers the variant and falls back to the base. Holds only an internal stage reference, never PII.
- **Tone Preset**: A small fixed set of system-supplied starter wordings (e.g. Formal / Friendly / Concise) a member can apply to a template as a starting point. Not a runtime toggle; applying it replaces the template's subject + body.
- **Merge Token Catalogue**: The fixed allow-list of supported merge tokens (e.g. `candidate_name`, `recruiter_name`, `workspace_name`, `stage_name`, `interview_date`, `interview_time`, `time_zone`, `location`, `scheduling_link`, `status_link`, `reschedule_link`, `feedback_link`, `expected_date`) and which tokens are permitted per message type. Validation at save and substitution at render both key off this catalogue.
- **Render Request (transient)**: A template reference (with optional stage) plus a set of merge values (sample or a selected candidate's data). Produces a Rendered Message; persists nothing.
- **Rendered Message (transient, internal model)**: The output of rendering — final subject, final body, and the list of fields that were missing (each rendered as a visible warning). Side-effect-free; the unit F22 dispatches and the preview UI displays. Carries no `{{token}}` residue.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Every defined message type has a non-empty built-in default subject and body in a brand-new workspace — verified by a test that lists the library and asserts all types present with non-empty content.
- **SC-002**: 100% of rendered messages with a complete merge-value set contain zero residual `{{token}}` strings and every occurrence of a repeated token is substituted; 100% of renders with an absent **or empty** value produce the machine-detectable warning marker (never a raw token, never a silent blank) and enumerate the missing fields; the same template + same merge values render byte-identically on repeat (determinism) — verified across all message types.
- **SC-003**: A merge value containing markup/script placed in the body is output-encoded so zero active content executes (e.g. `<script>` survives as inert encoded text), AND a merge value containing `\r\n`, a Unicode line separator (`U+2028`/`U+2029`/`U+0085`), or other control characters routed to the subject line is stripped so no SMTP header / line break can be injected — verified by a rendering test seeding an HTML/script payload (body), a `\r\nBcc:` payload AND a `U+2028` payload (subject), and a `javascript:`-scheme value for a URL token (which must render as an inert `[[invalid_url:...]]` marker, never a clickable anchor).
- **SC-004**: 100% of saves containing an unknown, unsupported, or type-disallowed merge token are refused with no persisted change (0 partial writes) and a value-free message — verified by validation tests across the boundary cases (unknown token, token disallowed for the type, empty subject, empty body, over-cap length, over-cap token count, over-cap variant count).
- **SC-005**: A locked template cannot be edited, tone-applied, variant-changed, or reset by a Recruiter (HTTP 403, no state change) while remaining viewable and previewable; an Admin can still edit and unlock it; lock/unlock is Admin-only — verified by a per-role contract test (Admin / Recruiter / Hiring Manager / Interviewer / Read-only) asserting the expected outcome per cell defined in FR-008/FR-010/FR-012 (view / preview / edit-when-unlocked / edit-when-locked / lock), not just a single allow/deny.
- **SC-006**: A per-stage variant is used for its stage and the base template is used for every other stage (fall-back), and a retired variant falls back to the base — verified by a rendering test across a stage with a variant and a stage without; a variant referencing a foreign-workspace or non-existent stage is refused with an indistinguishable not-found.
- **SC-007**: The rendered-message output shape (final subject, final body, missing-field warnings) matches the contract F22 consumes — verified by a MockMvc contract test (response body, status codes, error envelope, and the missing-field case).
- **SC-008**: Each of the seven lifecycle change-kinds (override create, edit, tone-apply, lock, unlock, variant add/change, variant/override reset) produces exactly one append-only audit entry tagged with its change-kind, using internal ids + type/stage only; the audit trail contains zero subject/body/rendered-content/PII — verified by a per-change-kind audit test, and a concurrent stale edit is refused via the version (optimistic concurrency).
- **SC-009**: An automated CI log scan across template management, rendering, and preview — including error/exception paths — finds zero occurrences of template subject/body text, rendered content, candidate/recipient names, or emails; only internal identifiers and type/stage strings appear (the scan is extended with a template-content sentinel and a merge-value-PII sentinel).
- **SC-010**: F21's own slice of the no-auto-send contract is independently verified here: rendering is side-effect-free (no message dispatched on any render/preview path) and no message-transport capability (`EmailSender`/SMTP) is reachable from the render path. The cross-feature guarantee — that a dispatch requires a system-event trigger or a recruiter approval — is owned and verified by F22's contract; this SC explicitly does not depend on F22 existing to close.
- **SC-011**: The frontend exercises the backlog-flagged Jasmine coverage (the original "frontend unit tests were missing" gap): missing-merge-field renders a visible warning (not a broken `{{variable}}`); a locked template disables/blocks the Recruiter edit action; and the preview renders correctly with sample data — verified by `ng test` (asserted green, with the case count noted at task close, per the §II demonstrable-leg practice).

## Assumptions

- **Builds on F02/F03/F04 — not re-implemented**: Roles, deny-by-default method security, the 403 envelope, and the scoped-not-found pattern come from F02; the workspace context and its template-governance surface from F03 (US-F03-5 "lock templates" binds here); the candidate record whose data a preview may merge comes from F04. F21 consumes these unchanged.
- **Workspace overrides by reference over built-in defaults**: The system ships a built-in default per message type. A workspace that never edits a template renders from the built-in default; a workspace edit persists an override that is rendered in its place. Defaults are resolved by reference at render time so a future release's improved default reaches un-overridden workspaces without a data migration, while overrides are preserved. The exact storage (one document per overridden template/variant, lazily materialised on first edit) is pinned in `plan.md`.
- **Merge substitution is in-house, no templating engine**: Merge rendering is a bounded, allow-listed `{{token}}` substitution implemented in-stack (no Handlebars/Mustache/Thymeleaf or other templating dependency), both to honour the dependency policy (C4) and to eliminate server-side template-injection risk. Tokens not on the catalogue are rejected at save; substitution only ever replaces catalogue tokens; everything else is literal text.
- **Rendering is content-only and side-effect-free; F22 sends**: F21 produces a Rendered Message (subject + body + missing-field warnings) and never performs delivery, never checks consent/erasure (that is F22's dispatch-time gate), and never schedules. The consent/erasure check, hard-bounce handling, provider abstraction, and `@Scheduled` reminders are all F22.
- **Backlog story mapping (US-F21-3 disposition)**: Backlog US-F21-3 ("approve a draft SLA message with one click") is **not** a standalone F21 story — the SLA draft and the one-click-approve workflow are delivered by F31 (which, per the delivery sequence, ships after F21 and is where SLA breaches are detected), and the dispatch gate is F22. F21 owns only the SLA-holding **template** + **rendering** + the **no-auto-send contract** (FR-020). Backlog AC-4 ("no email dispatched without approval or a system-event trigger") is preserved by FR-020 + SC-010. F21's four spec stories are: library view/edit (US-F21-1), preview/render (the F21 safety core), locking (US-F21-4 backlog), and per-stage variants (the backlog's "per-stage variants" scope line).
- **No SLA detection or drafting here**: F31 detects SLA breaches and creates the SLA draft for approval; F21 only supplies the SLA-holding template and the rendering. The dispatch-authorisation contract (no auto-send) is documented by F21 and enforced by F22; the SLA approval workflow surfaces in F31.
- **Merge-token catalogue is add-only and extensible**: The catalogue is a fixed allow-list at any point in time, but later features (F13/F20/F23/F32) MAY add new tokens governed by the same save-time allow-list validation; a future feature needing a new token does not re-open F21's scope. URL-bearing tokens are system-produced (FR-016).
- **Render output channel**: The rendered body targets an HTML email with a plain-text alternative; "neutralisation" means channel-appropriate output encoding (HTML-entity encoding in the body, control-character stripping in the subject — FR-015). The exact encoding strategy, the missing-field placeholder/sentinel string, the token lexical rules, and the fixed tone-preset ids + their starter wording are all pinned in `plan.md` so the rendering and apply-preset tests are deterministic.
- **One built-in default per type; no workspace-wide "house tone" in the MVP**: Each message type ships exactly one neutral built-in default; "brand voice" (US-F21-1) is achieved by per-template editing and the optional per-template tone-preset apply, not by a workspace-level tone toggle applied across all templates. A workspace-wide house tone is out of scope.
- **Template content is workspace authoring data, not candidate PII**: Stored template subject/body (and variants/overrides) hold only authoring text + merge tokens + internal ids, so template content is excluded from the candidate retention/erasure model (F04). A recruiter pasting a real candidate's literal details into a body is an author responsibility outside the merge-token model and is not tracked as candidate PII.
- **Single-language (EN) for the MVP**: Per the F03 scope note, the MVP is single-language English; per-workspace default-language selection and candidate-facing localisation are deferred and out of scope here. Tone presets are wording variants, not language variants.
- **Preview data**: A preview may use system-supplied sample merge values (always available) or, for a permitted member, a selected real candidate's data (F04). Rendered preview content may contain PII and is therefore display-only, never persisted, and never logged.
- **New collection + audit event types; reuses the audit log**: A new MongoDB collection holds template overrides and variants; its index (`{workspaceId, messageType}`, with stage discriminating variants) is created in the next Mongock changeset, whose `order` is derived from the highest **applied** ChangeUnit (`008`), not the branch number. Lifecycle actions add append-only audit event types (template created/edited/locked/unlocked/variant-changed/reset) on the existing audit trail; `plan.md` declares the collection, indexes, and event types.
- **No new infrastructure or dependency**: Template CRUD is Spring Data Mongo; rendering is in-house string substitution; no broker, cache, scheduling library, `@Scheduled` task, or SDK outside the fixed stack is added. New message-type/change-kind enums are logged as `.name()` strings, never via structured-argument enum interpolation (the F01.1 logstash Jackson-3 footgun).
- **No-cloud-credentials testing**: All library, validation, rendering, locking, variant, and audit behaviours are verified with Testcontainers `mongo:7` and MockMvc; no live email provider or cloud credentials are needed (delivery is F22). The CI PII/secret log scan is extended with a template-content sentinel and a merge-value-PII sentinel.
- **Versioning = monotonic version + audit, not full content history**: "Version-tracked in the audit log" (backlog AC) is satisfied by a monotonic version number for optimistic concurrency plus an append-only audit entry per change recording who/when/what-kind (type/stage), not a full retained-body diff history. Full body-diff rollback is not in MVP scope.

## Dependencies

- **F02 — RBAC** (complete): Recruiter/Admin roles, deny-by-default method security, the 403 envelope, the scoped-not-found pattern, and the build-time endpoint-inventory test every template-management endpoint must satisfy.
- **F03 — Workspace Setup & Configuration** (complete): the workspace context and the template-governance surface (US-F03-5 "lock templates") this feature realises; the workspace brand/voice these templates carry.
- **F04 — GDPR Baseline** (complete): the candidate record whose data a preview may merge; F21 never dispatches, so the consent/erasure dispatch gate (F04 × F22) is not exercised here, but the preview path must not leak rendered PII to logs.
- **F12 — Interview Template & Rule Engine** (complete): the interview stage that a per-stage variant is keyed to.
- **F00.1 — MongoDB Index Bootstrapping**: the new template-collection index is created via the next Mongock changeset in sequence (order off the highest applied `008`).
- **Consumed by F22 (Email Delivery), F31 (SLA Nudge Engine), F13 (Flow A1), F20 (Reschedule), F23 (No-Show), F32 (Feedback)**: F22 dispatches the Rendered Message under the authorisation contract; F31 drafts SLA messages for approval; F13/F20/F23/F32 select and render the appropriate template at their trigger points.

## Out of Scope

- Transactional email delivery, the provider abstraction (`EmailSender`), the consent/erasure dispatch gate, hard-bounce handling, and scheduled reminders (F22).
- SLA breach detection and SLA-draft creation/approval workflow (F31) — F21 supplies only the SLA-holding template + rendering.
- The candidate self-scheduling/status/feedback pages and their accessibility/performance targets (F13/F14/F30/F32).
- Candidate-facing localisation / multi-language template content (deferred; single-language EN MVP).
- Auto-send of any message (SLA auto-send and any unattended dispatch) — deferred to v1.5; the MVP requires a system-event trigger or recruiter approval.
- Full body-diff version history and rollback — MVP keeps a monotonic version + per-change audit entry only.
- Auto-generated meeting links inside template content (Google Meet/Teams/Zoom) — deferred to v1.5; templates carry recruiter-provided location/dial-in text via merge tokens only.
