# Phase 0 Research — Email Template Library (F21)

**Branch**: `010-email-template-library` | **Date**: 2026-06-15

The spec carried **no `NEEDS CLARIFICATION` markers** (all open choices were resolved with informed defaults in the spec's Assumptions and the multi-role spec review). This document records the load-bearing engineering decisions, each grounded in the existing F02/F03/F04/F12 source.

---

## D1 — Built-in default + per-workspace override, resolution by reference

**Decision**: Ship a neutral built-in default (subject + body) for each of the 8 message types as **code-shipped constants** (`BuiltInEmailTemplates`, backed by classpath resources under `resources/email-templates/`). A workspace edit persists an **override** document; reads/renders resolve **variant override → base override → built-in default**. No default rows are seeded into MongoDB.

**Rationale**: (a) A brand-new workspace can produce sensible messages with zero rows (SC-001) — no seeding migration, no per-workspace fan-out at setup. (b) Resolution-by-reference means a future release's improved default reaches every un-edited workspace automatically, while overrides are preserved (spec Edge Case "built-in default changes in a future release"). (c) Mirrors the F03 "documented default applies until set" posture and the F12 inherit-by-reference pattern.

**Startup completeness check (the Backend-review finding)**: `BuiltInEmailTemplates` (and `TonePresetCatalogue`, D10) validate **eagerly at startup** (`@PostConstruct`) that a non-empty subject+body exists for **every** `EmailMessageType` (and every `EmailMessageType × TonePreset`); a missing/empty resource fails the context fast, so a packaging gap can never silently defeat SC-001 ("a non-empty default for every type") only at first render.

**Alternatives rejected**: Seeding a full default row-set per workspace on setup (couples F21 to the F03 setup transaction, fans out 8+ rows per workspace, and freezes the default at creation time — defeats Edge Case fall-back). A single shared "global defaults" collection editable by anyone (no workspace isolation). Lazy resource loading with no completeness check (SC-001 could fail only at first render of an un-edited type).

---

## D2 — One collection `emailTemplates`, NON-NULL `stageKey` discriminator

**Decision**: One collection. Each override doc is keyed `{workspaceId, messageType, stageKey}` with a **plain unique index**. `stageKey` is **never null**: the literal `"BASE"` for the base override, or the F12 interview-template id for a per-stage variant.

**Rationale**: The recurring CLAUDE.md F01 footgun — a partial unique index `{field:{$exists:true}}` matches present-but-null, and Spring Data writes nulls by default, so a nullable `stageId` would either collide base rows or require the `@Field(write=NON_NULL)` partial-index dance. Using a **non-null sentinel** (`"BASE"`) makes all index fields non-null → a clean plain unique index that discriminates base from every variant — exactly the F11 `provider`-as-always-non-null-discriminator precedent. Listing the library = `findByWorkspaceIdAndStageKey(ws, "BASE")`; listing a type's variants = `findByWorkspaceIdAndMessageType` — both covered by the unique index prefix `{workspaceId}` / `{workspaceId, messageType}`, so **one index suffices**.

**Alternatives rejected**: Nullable `stageTemplateId` + partial unique index (the footgun). Two collections (base + variants) — needless split, two indexes, duplicated resolution.

---

## D3 — Plain-text authoring → escaped HTML + plain-text render (the injection + link-spoof control)

**Decision**: Recruiters author a **plain-text** subject and body (with `{{tokens}}`). The renderer applies **two distinct, separately-tested transforms** — the subject and the body are different sinks and never share an encoder:

- **Subject** (`subject`): substitute tokens into the authored plain text, then **strip** the full control + line-separator set `[U+0000-U+001F, U+007F-U+009F, U+2028, U+2029]` from the result (NOT HTML-escape it — a subject is plain text). This defeats SMTP-header / CRLF injection *and* the Unicode line separators (`U+2028`/`U+2029`/`U+0085`/NEL) that `HtmlUtils.htmlEscape` passes through unchanged (verified against spring-web 6.1.14 bytecode — it escapes only `< > " & '`).
- **Body plain part** (`bodyText`): token-substituted authored text, verbatim (newlines preserved).
- **Body HTML part** (`bodyHtml`): the **canonical, order-pinned** algorithm (so the byte-identical determinism assertion is reproducible): (1) normalise newlines `\r\n`/`\r` → `\n`; (2) `HtmlUtils.htmlEscape(authoredBody, "UTF-8")` (the **UTF-8 overload** — the default ISO-8859-1 numeric-escapes legitimate non-Latin names); (3) substitute each token, replacing it with `HtmlUtils.htmlEscape(value, "UTF-8")` for plain tokens and, for **URL-typed** tokens, with `<a href="ESC_URL">ESC_URL</a>` where `ESC_URL` is the escaped value and href == visible text; (4) `\n` → `<br>`. Because `{{` / `}}` are not escaped by `htmlEscape`, the tokens survive step 2 intact and are substituted in step 3 (substitute-into-already-escaped — pinned ordering).
- **URL-typed token scheme guard**: before anchoring, a URL-typed token value MUST match an `http`/`https`-only allow-list; a value with any other scheme (e.g. `javascript:`) is rendered as an inert escaped `[[invalid_url:<token>]]` marker, never a clickable anchor. (`htmlEscape` does NOT neutralise the `javascript:` scheme — escaping only handles markup chars — so the scheme allow-list is a separate, required control even though production URLs are system-produced, because **preview** accepts arbitrary `sampleValues`.)

Rich-HTML authoring (recruiter-authored markup, images, styling) is **deferred** to a later phase.

**Rationale**: This makes the spec's hardest guarantees *structural*, not best-effort, and survives the spring-web-bytecode reality the Security review surfaced:
- **FR-015 / SC-003 (no active-content injection)**: the entire authored body is escaped, so neither a candidate merge value (`<script>`) nor recruiter-authored markup produces active content; `<script>` survives as inert encoded text.
- **FR-016 / SC-006 (no link-token spoofing)**: recruiters cannot author a raw `<a href>` (it escapes to literal text); URL tokens are system-produced, scheme-restricted to `http(s)`, and render with `href == visible text` — no misleading-anchor and no `javascript:` payload, even in preview.
- **Subject SMTP-header / line-break injection**: the subject is control-stripped (full range incl. Unicode separators), not HTML-escaped.

`HtmlUtils.htmlEscape` is part of `spring-web` (already on the classpath) — **no new dependency**.

**Alternatives rejected**: Recruiter-authored HTML bodies + an HTML sanitiser (needs a new dependency — OWASP Java HTML Sanitizer / jsoup — violating C4, and a far larger injection surface). A templating engine (Thymeleaf/Handlebars) — new dependency + server-side-template-injection risk; the constitution and the spec explicitly forbid it. HTML-escaping the subject (wrong sink — it would encode plain-text `&`/`<` yet still pass `U+2028`).

---

## D4 — In-house `{{token}}` substitution; broken token can never be saved

**Decision**: A valid token is **exactly** `\{\{[a-z_]+\}\}` (no inner spaces, snake_case). **Save-time** validation scans the subject+body for every `\{\{[^{}]*\}\}` occurrence ("looks like a token") and rejects (value-free 400) any that is not a catalogue token *permitted for that message type* — so a malformed/unknown/disallowed token can never persist (FR-004). **Render-time** substitution replaces **every** occurrence of each permitted token; a value that is **absent or empty** is replaced with the fixed detectable marker `[[missing:<token>]]` and added to `missingFields` (FR-014) — never a raw `{{token}}`, never a silent blank.

**Malformed-token truth table** (pinned so the validation test is a table, not a judgement — the QA finding):

| Authored form | Save-time outcome | Rationale |
|---|---|---|
| `{{candidate_name}}` (catalogue token, permitted for type) | accept | valid |
| `{{candidate_name}}` (catalogue token, NOT permitted for type) | reject `invalid_template` | D5 per-type subset |
| `{{not_a_token}}` (well-formed, unknown) | reject `invalid_template` | matches `\{\{[^{}]*\}\}`, not in catalogue |
| `{{}}` (empty) | reject `invalid_template` | matches scan, empty ≠ catalogue token |
| `{{ candidate_name }}` (whitespace padding) | reject `invalid_template` | matches scan, padded ≠ catalogue token (lexis has no spaces) |
| `{{candidate_name}` (single closing brace) | accept → **literal text** | does NOT match `\{\{[^{}]*\}\}` (no `}}`); inert literal, never a substituted token |
| stray `{{` with no closing | accept → literal text | no complete `{{…}}` |

The two "literal text" rows are the *defined* outcome the spec's malformed-token Edge Case demands (a stray brace is harmless inert text; it can never become a broken substituted token). The validation test asserts every row.

**Rationale**: Authoring-time validation (the strong guarantee — a broken template never exists) + a render-time safety net (for genuinely absent data) is exactly the spec's two-layer model. Pinning the token regex and the `[[missing:…]]` sentinel makes SC-002/SC-004 deterministic tests rather than human judgement (the QA review BLOCKERs). Treating absent and empty identically closes the QA-flagged empty-string ambiguity.

**Alternatives rejected**: Regex `{{ name }}` with flexible whitespace (ambiguous lexis); render-time-only validation (a broken token could be saved and only fail at send-time, in F22, after F21 reported success).

---

## D5 — Merge-token catalogue + per-type permitted subset

**Decision**: A fixed, **add-only** catalogue (`MergeToken` enum) of 13 tokens, with a per-message-type permitted subset (`MergeTokenCatalogue`). URL-typed tokens (`scheduling_link`, `status_link`, `reschedule_link`, `feedback_link`) are flagged for the D3 anchor rendering, are **system-produced** (never recruiter free-text in production), and their values are **scheme-restricted to `http`/`https`** before anchoring (D3 — a non-allow-listed scheme such as `javascript:` renders as an inert `[[invalid_url:<token>]]` marker, never a clickable anchor). This guard is required even though production URLs are system-produced, because **preview** accepts arbitrary `sampleValues` for any token. See data-model §3 for the catalogue and the type→tokens matrix.

**Rationale**: A closed allow-list is what makes save-time validation possible (D4) and prevents a recruiter referencing arbitrary candidate fields. Add-only (BA review finding) means a later feature (F13/F20/F23/F32) needing a new token extends the catalogue without re-opening F21 scope.

**Alternatives rejected**: Free-form merge fields resolved reflectively against the candidate object (PII over-exposure + injection surface); a single global token set ignoring per-type relevance (e.g. a `scheduling_link` in a rejection email).

---

## D6 — RBAC: class gate + Admin-only lock + service-level locked-edit 403

**Decision**: `EmailTemplateController` mounted at `/api/internal/email-templates` with **class-level** `@PreAuthorize("hasAnyRole('ADMIN','RECRUITER')")` (view/preview/edit/tone/variant/reset). The **lock/unlock** handlers carry method-level `@PreAuthorize("hasRole('ADMIN')")` (most-specific wins). A Recruiter editing a **locked** template is a data-dependent refusal the principal alone can't express → the service throws `TemplateLockedException` → mapped to **403 `template_locked`** by the controller-scoped handler.

**Rationale**: Mirrors the F12 class-level-gate-as-single-source-of-truth that satisfies `RbacEndpointInventoryTest` for every handler. Lock is genuinely Admin-only (governance). The locked-edit refusal cannot be a `@PreAuthorize` (it depends on the target document's `locked` flag, not the role), so it is a service check rendered as 403 — distinct envelope (`template_locked`) from the RBAC 403 but the same status, satisfying the spec's "HTTP 403".

**Alternatives rejected**: A SpEL `@PreAuthorize` referencing the loaded entity (couples method security to a DB read, brittle); making lock a Recruiter-permitted action (violates US3/FR-010).

---

## D7 — Preview candidate is workspace-scoped (oracle-free)

**Decision**: A preview may supply `candidateId`. The service resolves it via the existing `CandidateRepository.findByWorkspaceIdAndId(workspaceId, id)`; empty → `RbacExceptions.ScopedNotFoundException` → global **404** (indistinguishable from "missing"). On success, the decrypted `name`/`email` (the F04 converter decrypts on read) become merge values; they are never logged (success **or** error), and the response is `Cache-Control: no-store`. A preview with **sample data** (no candidateId) is always available and PII-free.

**Rationale**: Closes the Security-review finding — without workspace-scoping, preview would be a cross-workspace candidate-existence oracle and a PII-exfil path. Reuses the exact F04/F02 scoped-not-found pattern (no new mechanism). An erased candidate resolves with `"[ERASED]"` markers (display-only; no dispatch, so no consent/erasure gate fires here — that is F22).

**Alternatives rejected**: A global `findById` (cross-workspace leak); requiring a candidate for every preview (sample-data preview is the common authoring case and is PII-free).

---

## D8 — Optimistic-concurrency versioning

**Decision**: `EmailTemplate` carries Spring Data `@Version Long version`. The edit/tone/lock/reset request includes `expectedVersion`; the service loads the doc (or treats absent-as-version-0 for a first edit), and on a mismatch — or a Spring `OptimisticLockingFailureException` (concurrent versioned save) **or** `DuplicateKeyException` (concurrent first-edit racing the unique index) — throws `StaleTemplateException` → **409 `stale_template`**. "Version-tracked in the audit log" (backlog AC) = the monotonic `@Version` + a per-change-kind append-only audit entry (D9); no full body-diff history in MVP.

**Write-path constraint (the Backend-review finding — load-bearing)**: `@Version` is honoured **only** by `MongoRepository.save(...)` / `MongoTemplate.save(...)`; it is silently ignored by `updateFirst`/`upsert`/`findAndModify`. Therefore **every** F21 edit/tone/lock/unlock/reset write MUST go through `save(...)` (load → mutate → `save`), never a partial update — otherwise a stale write slips through. This is a code-review checklist item and is asserted by a non-vacuous concurrent-edit integration test (two edits at the same `expectedVersion`; exactly one succeeds, the other → 409). The service catches **both** `OptimisticLockingFailureException` and `DuplicateKeyException` and maps each to `StaleTemplateException`.

**Rationale**: Spec Edge Case "concurrent edits to the same template" + FR-011. `@Version` is the standard, dependency-free Spring Data mechanism (first use in this codebase — F02/F03 used `findAndModify`; this is the more natural fit for a load-mutate-save document). The unique index (D2) turns a concurrent first-create into a detectable `DuplicateKeyException` rather than a duplicate row. No multi-doc transaction exists (insert-or-versioned-save is per-document atomic; reset is a single delete) → no lost-update or partial-write hole.

**Alternatives rejected**: Last-write-wins (silent lost edit — spec forbids); a separate version-history collection (full diff rollback is explicitly out of MVP scope).

---

## D9 — Four append-only `AuthEventType`s; ids-only audit

**Decision**: Append (never reorder) `EMAIL_TEMPLATE_EDITED`, `EMAIL_TEMPLATE_LOCKED`, `EMAIL_TEMPLATE_UNLOCKED`, `EMAIL_TEMPLATE_RESET` to `AuthEventType` (after the F12 block). Audit via the existing `AuthAuditService.record(type, workspaceId, memberId, outcome, sourceIp)` where `outcome` is a compact **non-PII** descriptor `"<messageType>/<stageKey>/<kind>"` (e.g. `INVITATION/BASE/tone_apply`). The four content-write kinds (create-override, edit, tone-apply, variant-edit) share `EMAIL_TEMPLATE_EDITED` discriminated by `outcome`; lock/unlock/reset get their own enum.

**Rationale**: Mirrors the F12 four-event append + the F02/F03 audit discipline (ids only). `messageType` and `stageKey` are non-PII (an enum + `"BASE"`/internal template id), so they are safe in `outcome`. SC-008 (each change-kind → exactly one entry, tagged) is satisfied by the enum + `outcome` discriminator. No new audit method needed.

**Alternatives rejected**: One event for all kinds (loses the lock/unlock governance distinction); putting subject/body in the audit (PII/secrecy violation).

---

## D10 — Tone presets are code-shipped starter wording per (type, tone)

**Decision**: `TonePreset` enum `FORMAL`/`FRIENDLY`/`CONCISE`. The starter wording is per **(messageType, tone)** (a Friendly invitation ≠ a Friendly rejection), shipped as classpath resources (`TonePresetCatalogue`). Applying a preset **replaces** the override's subject+body with that wording (then freely editable); it is a versioned, audited write (`outcome` `tone_apply`), not a runtime toggle.

**Rationale**: Matches the spec's "starting point, not a runtime toggle". Per-(type,tone) is the only coherent interpretation. Code-shipped wording keeps it dependency-free and deterministic (the apply test asserts the known preset content).

**Alternatives rejected**: A single tone wording reused across types (incoherent — tone is type-specific); storing presets in the DB (needless — they are fixed product content).

---

## D11 — Bounds on size + render fan-out

**Decision**: `email.template.*` config: `max-subject-length` (default 200), `max-body-length` (default 10000), `max-tokens-per-template` (default 50), `max-variants-per-type` (default 20). Enforced at save (value-free 400); empty subject/body rejected. The token-count cap also bounds **render-time** substitution work.

**Rationale**: FR-020 + the Security-review note that the token cap must bound render amplification, not just storage. Config-driven so prod can tune without code change.

**Alternatives rejected**: Unbounded content (storage-exhaustion + an adversarial repeated-token render-amplification vector).

---

## D12 — Value-free diagnostics on every render/preview path

**Decision**: All validation messages, exception messages, and any log statement on the render/preview/edit paths are **value-free** — they may name the field, token, rule, message type, or stage, but never the submitted subject/body value, a merge value, or candidate PII. New enums (`EmailMessageType`, `TonePreset`, `MergeToken`) are logged as `.name()` Strings, never via `StructuredArguments.kv(enum)` (the F01.1 logstash Jackson-3 footgun).

**Rationale**: Closes the Security-review finding that an unhandled exception echoing a merge value is the leak the happy-path scan (SC-009) won't catch. Matches the F12 value-free-validation discipline. SC-009's scan covers error paths explicitly.

**Alternatives rejected**: Standard exception messages that interpolate the offending value (the classic PII-in-stack-trace leak).

---

## D13 — §II demonstrable leg

**Decision**: An Angular `email-templates` standalone feature (ADMIN/RECRUITER route guard): list the 8 types, edit subject/body, apply a tone preset, lock/unlock (Admin-only UI affordance), manage per-stage variants, and a **preview** pane (sample data or a selected candidate) showing the rendered subject/body + missing-field warnings. Full browser → Spring Boot → MongoDB. Jasmine unit tests (SC-011) cover the three backlog-flagged cases; Playwright E2E covers edit → preview. Sending is F22 (out of scope).

**Rationale**: §II requires a real end-to-end flow, not a backend-only capability. The template editor + preview is the demonstrable slice; it exercises every backend endpoint browser-to-DB. Closes the backlog's explicit "frontend unit tests were missing" gap.

---

## Cross-cutting confirmations (verified against source)

- **Mongock order** — F12's `ChangeUnit008` is merged/applied; the next changeset is **`ChangeUnit009`** (order `"009"`), off the highest *applied* changeset, **NOT** the branch number `010` (the recurring CLAUDE.md lesson). One changeset, one unique index.
- **`AuthEventType`** is a flat append-only enum (verified) — appending four values at the end is safe; never reorder (persisted by name? — values stored as the enum; appending at end is the established pattern, F10/F12 did the same).
- **`AuthAuditService.record(...)`** signature confirmed `(AuthEventType, workspaceId, memberId, outcome, sourceIp)` — reused as-is.
- **`CandidateRepository.findByWorkspaceIdAndId`** confirmed present (the scoped read for preview); `name`/`email` decrypt on read via the registered converter.
- **`InterviewTemplateRepository`** (F12) provides the workspace-scoped `findByWorkspaceIdAndId` used to validate a variant's `stageKey` references a real in-workspace interview template.
- **`HtmlUtils.htmlEscape`** ships with `spring-web` (already a dependency) — no new library for HTML escaping.
- **No `@Scheduled`, no broker, no cache, no SDK** — rendering is request-scoped in-memory; the only I/O is one indexed template read + one optional scoped candidate read.
