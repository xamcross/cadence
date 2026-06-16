# Implementation Plan: Email Template Library (F21)

**Branch**: `010-email-template-library` | **Date**: 2026-06-15 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/010-email-template-library/spec.md`

## Summary

Deliver the **email template library** + the **safe rendering/preview layer** (backlog F21) — the content foundation every outbound-email feature composes. Two capabilities: (1) **template management** — a workspace-scoped library covering the eight message types (invitation, confirmation, reminder-24h, reminder-1h, hold/update, rejection, feedback-request, SLA-holding), each with a code-shipped **built-in default**, editable subject + body (allow-listed `{{merge_token}}`s), optional **tone-preset** apply (Formal/Friendly/Concise starter wording), optional **per-stage variants** keyed to an F12 interview template, **Admin locking**, optimistic-concurrency versioning, and append-only audit; (2) the **renderer** — given a (type, optional stage) + merge values (sample data or a workspace-scoped candidate), it resolves variant → base override → built-in default, substitutes only catalogue tokens, replaces missing/empty values with a fixed detectable warning marker, and neutralises every value with **channel-appropriate output encoding** (HTML-entity-encode in the body, strip CR/LF/control chars in the subject), producing a side-effect-free Rendered Message that F22 will dispatch.

F21 is **new in-stack business logic, zero new infrastructure and zero new dependency**. It reuses the F02 RBAC method-security + 403 envelope + scoped-not-found + endpoint-inventory test, the F03 controller/exception-handler pattern, the F04 `Candidate` (decrypted on read for preview merge values, scoped lookup), the F12 `InterviewTemplate` repository (to validate a variant's stage reference), the existing `Clock` bean, and the `AuthAuditService`. The genuinely new code is: one `EmailTemplate` domain + repo, an `EmailTemplateService` (resolve/edit/lock/reset + validation), a `MergeRenderer` (in-house substitution + neutralisation), a code-shipped `BuiltInEmailTemplates` + `TonePresetCatalogue` + `MergeTokenCatalogue`, DTOs + one controller + a scoped exception handler, a Mongock `ChangeUnit009` (one unique index), four append-only audit event types, and a light Angular `email-templates` feature (editor + preview — the §II demonstrable leg). F21 **does not** send any email, check consent/erasure, draft SLA messages, or render candidate pages — those are F22 / F31 / F13–F14.

Load-bearing engineering decisions (full detail in [research.md](./research.md)):
1. **Built-in default + per-workspace override, resolution by reference** (D1) — defaults are code-shipped constants; a workspace edit persists an override doc; an un-edited type renders the built-in default; a future release's default reaches un-edited workspaces with no migration.
2. **One collection `emailTemplates`, keyed `{workspaceId, messageType, stageKey}`** (D2) — `stageKey` is a NON-NULL discriminator: the literal `"BASE"` for the base template or the F12 interview-template id for a variant. Non-null avoids the F01 partial-unique-index / `write=NON_NULL` footgun; one plain unique index discriminates base vs every variant (the F11 provider-discriminator precedent).
3. **Plain-text authoring → escaped HTML + plain-text render** (D3) — recruiters author plain-text subject + body; the renderer emits a `subject` (control-chars stripped), a `bodyText`, and a `bodyHtml` (whole template HTML-escaped, newlines→`<br>`, merge values HTML-entity-encoded). This makes injection neutralisation structural (FR-015) and **eliminates link-token spoofing** (FR-016): a recruiter cannot author a raw `<a href>`; URL-typed tokens render as an anchor whose href and visible text are the **same** system-produced URL. Rich-HTML authoring is deferred.
4. **In-house `{{token}}` substitution, no templating engine** (D4) — token lexis is exactly `\{\{[a-z_]+\}\}`; save-time validation rejects any `\{\{…\}\}` occurrence that is not a catalogue token permitted for that type (value-free) so a broken token can never persist; render replaces **every** occurrence; absent **or empty** value → a fixed detectable marker `[[missing:<token>]]` + flagged missing (never a raw token, never a silent blank).
5. **Merge-token catalogue + per-type permitted subset** (D5) — a fixed, add-only allow-list (13 tokens) with four URL-typed tokens; validation at save and substitution at render both key off it.
6. **RBAC: class-level `hasAnyRole('ADMIN','RECRUITER')`; lock/unlock method-level `hasRole('ADMIN')`; locked-edit is a service 403** (D6) — view/preview/edit gated to Recruiter+Admin; lock is Admin-only; a Recruiter editing a *locked* template is a data-dependent business refusal → `TemplateLockedException` → 403 `template_locked`. Endpoint-inventory-test covered.
7. **Preview candidate is workspace-scoped** (D7) — a preview using a real candidate resolves via `findByWorkspaceIdAndId` → `ScopedNotFoundException` → 404 (no cross-workspace candidate oracle / PII-exfil); merge values are the decrypted name/email, never logged, response `no-store`.
8. **Optimistic-concurrency versioning** (D8) — Spring Data `@Version Long`; the edit/lock/reset request carries `expectedVersion`; a stale write (or a concurrent first-edit unique-index collision) → 409 `stale_template`. A monotonic version + a per-change-kind append-only audit entry satisfies "version-tracked" (no body-diff history in MVP).
9. **Four append-only `AuthEventType`s + ids-only audit** (D9) — `EMAIL_TEMPLATE_EDITED` (the change-kind in `outcome`: create-override/edit/tone-apply/variant-edit), `EMAIL_TEMPLATE_LOCKED`, `EMAIL_TEMPLATE_UNLOCKED`, `EMAIL_TEMPLATE_RESET`; audit carries only ids + messageType + stageKey + kind — never subject/body/rendered content/PII.
10. **Tone presets are code-shipped starter wording per (type, tone)** (D10) — `TonePreset` enum FORMAL/FRIENDLY/CONCISE; apply replaces subject+body then remains editable; exact wording in a classpath resource.
11. **Bounds on size + fan-out** (D11) — `email.template.*`: max subject/body length, max tokens/template (also bounds render-time substitution work — DoS), max variants/type; empty subject/body rejected at save.
12. **Value-free diagnostics on all render/preview paths** (D12) — every error/exception path is value-free (no merge value, subject, body, or PII in messages/logs), so an error path cannot become the leak the happy-path scan misses.
13. **§II demonstrable leg** (D13) = an Angular `email-templates` editor + preview (sample or candidate data), full browser→DB, ADMIN/RECRUITER guarded; sending is F22.

## Technical Context

**Language/Version**: Java 21 (backend, toolchain pinned); TypeScript 5.4 / Angular 17.3 (frontend)
**Primary Dependencies**: Spring Boot 3.3.5 (web, data-mongodb, security w/ method security, actuator, aop); Mongock 5.4.4; logstash-logback-encoder 9.0. **No new backend or frontend runtime dependency** — merge rendering is in-house `{{token}}` substitution + JDK HTML-entity encoding (`org.springframework.web.util.HtmlUtils.htmlEscape`, already on the classpath via spring-web); persistence via Spring Data Mongo; no templating engine (Handlebars/Mustache/Thymeleaf — also the SSTI mitigation), no SDK, broker, cache, or scheduling library. Test-only: `spring-security-test` (already present) for per-role post-processors.
**Storage**: MongoDB 7.x (Atlas prod, Testcontainers `mongo:7` tests). **One new collection** `emailTemplates` (authoring content + ids only — no candidate PII, no secret → un-encrypted by design, like `interviewTemplates`/`managedCalendarEvents`) and **one new unique index** `{workspaceId, messageType, stageKey}` — created by Mongock `ChangeUnit009` (order **"009"**, off the highest *applied* changeset `008`, NOT the branch number `010`). Reuses `candidates` (F04, decrypted preview merge values, scoped read), `interviewTemplates` (F12, variant stage-reference validation), `members`/`sessions` (actor), `authAuditLog` (extended with four append-only event types).
**Testing**: JUnit 5 + Testcontainers (integration: library lists built-in defaults; edit persists override + default untouched; variant resolution + fallback; reset→fallback + idempotent no-op; lock blocks Recruiter edit; optimistic stale→409; per-change-kind audit ids-only; raw-driver content-free-of-PII doc; foreign-stage 404; foreign-candidate-preview 404), MockMvc (5-role × {view, preview, edit, edit-when-locked, lock} contract matrix; Rendered Message shape `{subject, bodyText, bodyHtml, missingFields}`; preview `no-store`; error envelopes; SC-007), Mockito/plain unit (`MergeRenderer` truth tables: substitute-all-occurrences, absent/empty→marker, HTML-escape of a `<script>` body payload, subject CRLF/`\r\nBcc:` stripping, URL-token bare-anchor no-spoof, determinism/byte-identical, malformed-token save rejection, token-count cap), Jasmine (editor + preview component: missing-field warning render, locked disables the edit action, preview renders with sample data — SC-011), Playwright (E2E: edit a template → preview → rendered output, browser→DB).
**Target Platform**: Fly.io single Machine (backend JAR), Cloudflare Pages (Angular SPA), MongoDB Atlas.
**Project Type**: Web application (Angular frontend + Spring Boot backend).
**Performance Goals**: rendering is pure in-memory string work (no network, no DB beyond one indexed template read + one optional scoped candidate read); render latency negligible (< 5 ms typical), bounded by the per-template token cap. No scheduled job; no hot-path scan.
**Constraints**: single instance + MongoDB only — no Redis/queue/cache/broker (§IV/C2); no new dependency (§III/C4); never log template subject/body, rendered content, tone-preset content, or candidate name/email incl. TRACE and error paths (FR-019/FR-012/SC-009); channel-appropriate neutralisation incl. subject CRLF (FR-015, SC-003); link tokens system-produced, un-spoofable (FR-016); preview candidate workspace-scoped (FR-017); value-free validation + diagnostics (FR-004/FR-018); deterministic render (FR-013); workspace-isolated incl. variant stage refs (FR-009); zero tool downloads (§X); any new `.ps1` pure ASCII (§V — none expected).
**Scale/Scope**: MVP single workspace (8 message types × {base + ≤ 20 variants}); 4 user stories, 22 FRs, 11 SCs.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Gate | Question | Result |
|---|---|---|
| **C1** | Within MVP scope (spec §11)? | ✅ PASS — the **template library** (invitations, confirmations, reminders, holds, rejections) is an explicit §11 MVP capability and a Tier-1 backlog item (F21). No deferred capability pulled in: auto-send is excluded (no-auto-send contract only); SLA detection/drafting = F31; dispatch = F22; candidate pages = F13/F14; localisation deferred (single-language EN). |
| **C2** | New service, queue, or replica? | ✅ PASS — **no** broker/cache/replica/object-store; **no** `@Scheduled` task (rendering is request-scoped, in-memory). One new collection + one unique index, both via Mongock — no new infra service. |
| **C3** | Exposes candidate PII to unauthorized roles? | ✅ PASS — template content is workspace authoring data (no candidate PII at rest, un-encrypted by design); the only PII surface is **preview** merge values, which are Recruiter/Admin-gated, workspace-scoped (`findByWorkspaceIdAndId` → 404 oracle-free), `no-store`, and never logged (incl. error paths); audit is ids + type/stage/kind only. |
| **C4** | Dependency outside the fixed stack? | ✅ PASS — **zero** new runtime dependencies; in-house `{{token}}` substitution + JDK/spring-web HTML escaping; no templating engine (also the SSTI control); Angular standalone only. |
| **C5** | New/modified Windows scripts contain non-ASCII? | ✅ PLANNED — no new `.ps1`; the `ci.yml` change is YAML (LF). Any script change byte-scanned before done. |
| **C6** | Multi-role sub-agent review (≥3) scheduled? | ✅ DONE+SCHEDULED — spec reviewed by 3 roles (BA/QA/Security, findings applied); this plan reviewed by ≥3 roles (user-requested "review with appropriate sub-agents") before tasks; final implementation review at task close. |
| **C7** | Downloads any build tool/runtime/CLI? | ✅ PASS — cached Gradle 9.4.0, installed JDK 21, existing Angular CLI; no downloads (§X). |

**Initial gate: PASS.** No §VIII OAuth-scope expansion (F21 requests no scope). No Complexity Tracking entries required (see below).

### §VIII privacy posture (no scope expansion, load-bearing controls)

F21 requests no OAuth scope. Its §VIII obligations are: (a) **template content** (subject/body/tone-preset) is recruiter-authored workspace data — not candidate PII — but is still never logged or audited (it could be pasted PII; FR-019, SC-009 with a content sentinel, audit ids-only); (b) **preview** decrypts a candidate's name/email to build merge values → Recruiter/Admin-gated, workspace-scoped (no cross-workspace oracle), `no-store`, never logged on success **or** error (FR-017/FR-018); (c) **rendered content** (which contains PII once merged) is transient, never persisted, never logged; (d) the variant **stage reference** is workspace-validated (FR-009) so it can't address a foreign template; (e) no candidate PII is persisted by F21, so member/candidate erasure (F04) needs no F21 cleanup hook (template content is authoring data, internal ids only).

### Post-Design Re-Check (after Phase 1 + §VI plan review) — COMPLETED

Multi-role plan review completed by three roles (Backend/DevOps, Security/GDPR, QA), each verifying claims against the **actual** F02/F03/F04/F12 source + the spring-web bytecode. **Result: PASS, unchanged gate status** — all accepted findings folded into `research.md`/`data-model.md`/`contracts/`/`spec.md`/this plan; none added a dependency, service, or topology, and none moved a gate to FAIL.

Key gate confirmations (verified against code):
- **Mongock order** — the highest *applied* changeset is `ChangeUnit008` (order "008"); `ChangeUnit009` (order "009") is correct, **NOT** the branch number "010". Native `createIndex` + targeted `dropIndex` idioms confirmed against `ChangeUnit008`.
- **C4 / C7 unchanged** — `HtmlUtils.htmlEscape` confirmed present in `spring-web` (no new dependency); in-house substitution; zero downloads.
- **C3 holds** — preview is Recruiter/Admin-gated, candidate workspace-scoped (`findByWorkspaceIdAndId` confirmed), `no-store`, never logged (incl. error paths); audit ids-only.

Accepted findings folded in (the load-bearing ones): subject neutralisation strips the full control + Unicode-line-separator set (`htmlEscape` passes `U+2028`/CR/LF through — verified) and is a transform **distinct** from the body's HTML escaping; URL-typed tokens are `http(s)`-scheme-restricted before anchoring (defeats a preview `javascript:` value); the `bodyHtml` algorithm + `missingFields` ordering are order-pinned for byte-identical determinism; `@Version` engages only via `save(...)` and the service dual-catches `OptimisticLockingFailureException`/`DuplicateKeyException`; built-in defaults/tone presets get a startup completeness check; SC-010 is a structural "no transport reachable" test (not a vacuous no-dispatch); the TRACE PII scan drives a failing render path; the malformed-token outcome is a pinned truth table; `TonePreset` typo corrected.

## Project Structure

### Documentation (this feature)

```text
specs/010-email-template-library/
├── plan.md              # This file
├── research.md          # Phase 0 — decisions D1..D13
├── data-model.md        # Phase 1 — EmailTemplate + built-in/tone/token catalogues + transient render I/O
├── quickstart.md        # Phase 1 — local run + manual + acceptance→test map
├── contracts/
│   └── email-template-api.md   # Phase 1 — template CRUD + render/preview REST + RBAC matrix + render contract
├── checklists/
│   └── requirements.md  # Spec quality + spec/plan review logs
└── tasks.md             # Phase 2 (/speckit.tasks — NOT created here)
```

### Source Code (repository root)

```text
backend/src/main/java/com/cadence/
├── domain/
│   ├── EmailTemplate.java                  # NEW @Document("emailTemplates") — content + ids; @Version; toString omits subject/body
│   ├── EmailMessageType.java               # NEW enum INVITATION/CONFIRMATION/REMINDER_24H/REMINDER_1H/HOLD_UPDATE/REJECTION/FEEDBACK_REQUEST/SLA_HOLDING
│   ├── TonePreset.java                     # NEW enum FORMAL/FRIENDLY/CONCISE
│   ├── MergeToken.java                     # NEW enum — the catalogue (13 tokens) + URL-typed flag
│   ├── RenderedMessage.java                # NEW transient — { subject, bodyText, bodyHtml, List<String> missingFields }
│   └── AuthEventType.java                  # MODIFIED — append EMAIL_TEMPLATE_EDITED/LOCKED/UNLOCKED/RESET (never reorder)
├── repository/
│   └── EmailTemplateRepository.java        # NEW — findByWorkspaceIdAndMessageTypeAndStageKey, findByWorkspaceId(list), etc.
├── service/
│   ├── EmailTemplateService.java           # NEW — resolve/edit/applyTone/lock/unlock/reset + validation (FR-002/004/020) + audit (D9); @Version CAS (D8)
│   ├── MergeRenderer.java                  # NEW — in-house substitution + channel-appropriate neutralisation (D3/D4/D12); deterministic
│   ├── BuiltInEmailTemplates.java          # NEW — code-shipped default subject/body per type (resolution-by-reference, D1)
│   ├── TonePresetCatalogue.java            # NEW — code-shipped starter wording per (type, tone) (D10)
│   └── MergeTokenCatalogue.java            # NEW — allow-list + per-type permitted subset + URL-typed set (D5)
├── api/
│   ├── EmailTemplateController.java         # NEW — /api/internal/email-templates; class @PreAuthorize hasAnyRole(ADMIN,RECRUITER); lock/unlock method-level hasRole(ADMIN) (D6)
│   ├── EmailTemplateDtos.java               # NEW — EditRequest(expectedVersion), ApplyToneRequest, PreviewRequest, TemplateResponse, RenderedMessageResponse (value-free errors)
│   └── EmailTemplateExceptionHandler.java   # NEW (@RestControllerAdvice assignableTypes=EmailTemplateController) — InvalidTemplate→400, TemplateLocked→403, StaleTemplate→409; ScopedNotFound→global 404
├── config/
│   ├── EmailTemplateProperties.java         # NEW — email.template.* (max subject/body length, max tokens, max variants/type)
│   └── migration/
│       └── ChangeUnit009_EmailTemplateIndexes.java  # NEW order "009" — emailTemplates unique {workspaceId, messageType, stageKey}
backend/src/main/resources/
├── application.yml                          # MODIFIED — add email.template.* bounds
└── email-templates/                         # NEW — built-in default + tone-preset wording (classpath resources, EN)
.github/workflows/ci.yml                     # MODIFIED — extend PII scan with an emailTemplates content sentinel (reuse candidate-PII sentinels for preview)

backend/src/test/java/com/cadence/emailtemplate/   # NEW package
├── MergeRendererTest.java                   # SC-002/003/006: substitute-all-occurrences; absent+empty→marker; byte-identical determinism + missingFields first-occurrence order; HTML-escape <script> body (UTF-8, non-Latin name preserved); subject strip of CR/LF AND U+2028/U+0085/control; URL-token href==text anchor + javascript:-scheme→[[invalid_url]]; hostile authored markup surrounding a URL token escaped to inert text (FR-016 spoof)
├── BuiltInTemplateCompletenessTest.java     # SC-001: @PostConstruct/startup completeness — non-empty default for every EmailMessageType and every (type × TonePreset) preset (fail-fast)
├── EmailTemplateValidationTest.java         # SC-004: malformed-token truth table (D4: unknown/disallowed/empty/padded reject; single-brace/stray literal accept), empty subject/body, over-cap length/tokens/variants → value-free 400, 0 persisted
├── EmailTemplateCrudIntegrationTest.java    # SC-001/006: list built-in defaults; un-overridden type renders the live in-code constant with ZERO persisted rows (raw-driver count==0 — no seeding regression); edit→read-back + default untouched; tone-apply; variant resolution + fallback; reset→fallback + idempotent no-op; raw-driver doc no candidate PII (Testcontainers)
├── EmailTemplateLockingTest.java            # SC-005: lock blocks Recruiter edit/tone/variant/reset (403 template_locked) but not view/preview; Admin edits+unlocks; lock Admin-only; concurrent same-expectedVersion edit via save() → exactly one wins, other → 409 (OptimisticLockingFailure AND first-edit DuplicateKey both mapped)
├── EmailTemplateContractTest.java           # SC-007: 5-role × {view,preview,edit,edit-locked,lock} per-cell outcome matrix; RenderedMessage shape incl. [[missing:<token>]] literal IN body (not just missingFields); preview no-store; error envelopes; RbacEndpointInventoryTest green; foreign-stage 404; foreign-candidate-preview 404
├── EmailTemplateNoTransportTest.java        # SC-010: structural assertion — no EmailSender/SMTP/mail type is a field/constructor dependency of MergeRenderer or EmailTemplateService (and no EmailSender bean exists yet); render path has no dispatch side effect
├── EmailTemplateAuditTest.java              # SC-008: each change-kind → exactly one append-only audit row tagged with its kind (outcome=type/stage/kind), ids only (no body/PII)
└── EmailTemplateLogPiiScanTest.java         # SC-009: TRACE scan — content sentinel fed through edit→read-back→preview (non-vacuous, success path) AND a PII sentinel driven through a FAILING render/preview (forced exception / foreign-candidate 404) — assert neither reaches logs incl. error paths

frontend/src/app/features/email-templates/   # NEW standalone feature
├── email-templates.component.ts             # NEW — list types, edit subject/body, apply tone, lock/unlock (Admin), manage variants; Angular Material; $localize
├── email-templates.component.spec.ts        # NEW — Jasmine (SC-011): missing-field warning, locked disables edit, preview with sample data
├── template-preview.component.ts            # NEW — sample/candidate merge → POST preview → render subject/body + missing-field warnings
└── email-templates.service.ts               # NEW — typed HTTP client for the F21 endpoints
frontend/e2e/email-templates.spec.ts         # NEW — Playwright: edit template → preview → rendered output
frontend/src/app/app.routes.ts               # MODIFIED — add the ADMIN/RECRUITER-guarded route
```

**Test isolation note (shared singleton container)**: every new test class MUST clean `emailTemplates`, `candidates`, `interviewTemplates`, and `authAuditLog` in `@BeforeEach` with `mongoTemplate.remove(new Query(), Type.class)` — never `dropCollection` (drops the Mongock indexes; CLAUDE.md F00.1 lesson). Preview-candidate fixtures insert a `Candidate` via the F04 production path (so name/email are converter-encrypted and the decrypt path is exercised). Any timestamp assertion uses the injected `Clock`/`MutableClock`. The first multi-class Testcontainers run after a recompile may throw the one-time `GenericContainer` class-init error — re-run.

**Wiring notes** (from plan review): `EmailTemplateProperties` is bound via `@ConfigurationProperties(prefix="email.template")` + `@EnableConfigurationProperties` (the F03 `AuthProperties` precedent). `BuiltInEmailTemplates`/`TonePresetCatalogue` run a `@PostConstruct` completeness check over the `EmailMessageType` (× `TonePreset`) matrix (fail-fast on a missing/empty resource — SC-001). All edit/tone/lock/unlock/reset writes go through `MongoRepository.save(...)` (load → mutate → `save`) so `@Version` engages — never `updateFirst`/`upsert` (a partial update silently bypasses `@Version`); the service catches both `OptimisticLockingFailureException` and `DuplicateKeyException` → `StaleTemplateException` (409).

**Structure Decision**: Web-application layout. F21 adds one bounded backend slice (`domain`/`repository`/`service`/`api`/`config` + one Mongock changeset + classpath default/tone resources) consuming the unchanged F04/F12 reads, plus a light Angular feature for the §II leg. No F04/F12 code is modified (only additive reads via existing repository methods). No new dependency, no new infra, no new top-level structure.

## Complexity Tracking

*No entries.* No architectural pattern beyond the minimum is introduced: the renderer is a single `@Service` of pure string substitution + JDK escaping; persistence is a plain Spring Data repository with `@Version`; defaults/tones/catalogue are code-shipped constants. No templating engine, event bus, cache, or strategy framework. The constitution gates all pass without justification.
