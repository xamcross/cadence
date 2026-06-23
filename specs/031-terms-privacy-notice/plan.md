# Implementation Plan: Terms & Conditions and Privacy Notice

**Branch**: `031-terms-privacy-notice` | **Date**: 2026-06-23 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/031-terms-privacy-notice/spec.md`

## Summary

Publish two indexable, accessible legal documents — **Terms & Conditions** at `/terms` and **Privacy Notice** at `/privacy` — as pre-rendered static HTML (outside the Angular SPA route table), authored as first-party content and emitted by extending the existing F61 static-content build path. Surface clearly-labelled links to them from a new shared public footer (home + public pages), from every candidate-facing personal-data surface (a single inline Privacy link on token pages), and from candidate-facing outbound email templates (GDPR Art. 14 first-contact reach). Coordinate all SEO/AEO artifacts (robots.txt, the site-map/`llms.txt` generator, `_headers`, the CI guards, structured data) so the two pages are crawlable/indexable/answer-engine-discoverable while the deny-by-default posture and the "exactly one indexable SPA route" invariant are preserved.

**Technical approach**: net-new code is concentrated in (1) a new pure assembler + fixed legal-pages list in the static-content lib, (2) extensions to the generator/CLI and crawl artifacts, (3) a tiny shared footer + per-surface link in Angular, and (4) a content-only edit to the built-in candidate email templates. No new runtime dependency, service, collection, or framework.

## Technical Context

**Language/Version**: TypeScript 5.4 / Angular 17.3 (SPA); Node ESM `.mjs` (build-time static-content generator, Node 20); Java 21 / Spring Boot 3.3.5 (content-only edit to existing built-in email templates for FR-020 only)
**Primary Dependencies**: None new. Reuses the F61 `article-build.lib.mjs` + `seo-build-articles.mjs` generator, the F60 SEO machinery (`robots.txt`, `_headers`, `seo-inject-origin.mjs`, `SeoService`/`route-seo.model`), the PR-34 design-system primitives in `styles.scss`, and the F14 dev/audit tools `axe-core` + `@lhci/cli` (already devDependencies). Backend reuses F21 `BuiltInEmailTemplates`/`MergeTokenCatalogue`/`MergeRenderer`.
**Storage**: N/A — legal content is first-party static source files in the repo (`frontend/src/content/legal/<slug>/`); no database, no new collection, no Mongock changeset.
**Testing**: Jasmine/Karma (Angular component + footer + per-surface link specs, axe WCAG 2.2 AA, 44 px target, no-token/no-storage); `node:test` (pure lib unit tests for the legal-page assembler, sitemap/llms emit, robots/structured-data shape); `@lhci/cli` (Lighthouse on `/terms`, `/privacy`); JUnit 5 + MockMvc (one contract test asserting candidate email templates render the Privacy Notice link with no token/PII — FR-020/SC-010).
**Target Platform**: Cloudflare Pages (static SPA + static legal pages + crawl artifacts); Fly.io single Machine (backend, unchanged except email-template content).
**Project Type**: Web application (Angular frontend + Spring Boot backend), but this feature is overwhelmingly frontend/build-time + static content.
**Performance Goals**: Legal pages are lightweight system-font static HTML (no Fraunces, no third-party assets); candidate-page Lighthouse ≥ 85 (mobile) preserved; legal pages add no JS.
**Constraints**: Preserve deny-by-default indexing; preserve "exactly one indexable SPA route" (`route-seo-inventory.spec.ts`); preserve the CI-locked CSP / `Referrer-Policy: no-referrer`; no token/PII in any artifact or email link; WCAG 2.2 AA on legal pages and every modified candidate surface; static legal HTML must be served ahead of the SPA catch-all.
**Scale/Scope**: Exactly two legal documents; ~6 candidate surfaces + 1 home footer + the candidate email templates touched; ~5 crawl/SEO artifacts coordinated.

## Constitution Check

*GATE: evaluated against constitution v1.3.0 (gates C1–C7). Re-checked after Phase 1 below.*

| Gate | Question | Result |
|---|---|---|
| **C1** | Within MVP scope (spec §11)? | **PASS (compliance enabler).** Not a new product capability — it discharges the GDPR transparency obligation (Principle VIII "GDPR by default", §IX candidate-experience) for the *existing* MVP flows (status page, scheduling, interest form, email channel). No deferred-capability surface is started. |
| **C2** | New service, queue, or replica? | **PASS.** None. Static files + build-time generator + existing Angular/Spring. No broker, no `@Async`, no new scheduler. |
| **C3** | Exposes candidate PII to unauthorized roles? | **PASS.** Legal pages are public and contain zero PII. Token-leak controls (no token in URL, global `no-referrer`, no third-party asset) are preserved and tested (FR-010/FR-011/SC-006). The email Privacy link carries no token/PII (FR-020/SC-010). |
| **C4** | Dependency outside the fixed stack? | **PASS.** No new runtime or build dependency (Dependency-Policy clean). |
| **C5** | New/modified Windows scripts contain non-ASCII? | **PASS (with gate).** No `.ps1/.cmd/.bat` is added. Modified build code is `.mjs`; new content is `.html`/`.json`/Angular/Java. The F30/F42 lesson is honoured: a byte-level non-ASCII / NUL scan of every new/modified source file (lib, content, Java) is a Definition-of-Done step (legal prose uses ASCII punctuation or documented UTF-8). |
| **C6** | Multi-role sub-agent review (≥3 roles) scheduled? | **PASS.** Plan reviewed by 3 sub-agents in this command (below); implementation will get the mandatory ≥3-role review at task close (security, frontend/a11y, SEO/build). |
| **C7** | Downloads any build tool/runtime/CLI? | **PASS.** Uses already-installed Node/Angular/`@lhci/cli`/cached Gradle; zero downloads. |

**Initial gate: PASS.** No Complexity Tracking entries required (see table at end — empty).

**Scope note (resolved):** FR-020 requires the Privacy Notice link in candidate-facing built-in email templates — a *content-only* edit to existing F21 templates (no new service/collection/dependency). The spec's Out-of-Scope wording was tightened accordingly ("no new backend services, collections, or dependencies"). This is the only backend touch and is GDPR-Art.-14-mandated.

**Post-design re-check (after Phase 1 + multi-role review): PASS.** No gate regressed. The design adds no service/queue/replica (C2), no dependency (C4), exposes no PII (C3 — token-leak controls verified against the real components), and downloads nothing (C7). One BLOCKER from review (the `seo-inject-origin.mjs` placeholder substitution) is a correctness fix folded into the design, not a constitution violation.

**Multi-role sub-agent review (Constitution VI / C6) — 3 roles, all findings reconciled:**
- *SEO/build*: 1 BLOCKER — `seo-inject-origin.mjs` processes only a hardcoded file set + `resources/`, so it must be **extended** with a legal-page loop or `/terms` ships unsubstituted placeholders (folded into research D6, C-SEO-5, plan source list). SHOULD-FIX folded: CI robots `ok`-set is additive (12 lines, byte-exact); new non-Article-JSON-LD + fail-closed + token-deny-grep CI assertions; `buildSitemap`/`buildLlms` signature widening + `loadLegalPages`/`CADENCE_LEGAL_DIR`; legal `lastUpdated` mapped (not the article `lastmodOf`); legal excluded from `feed.xml`.
- *Security/privacy*: no BLOCKER. Token-leak control set verified correct against the real token components. SHOULD-FIX folded: email `privacy_link` value injected **centrally** in `renderForSend` (a literal anchor fails the F21 HTML-escape) and permitted for **every** candidate-facing type, with the contract test asserting a real `<a href>` (not literal/missing); fail-closed on a missing emitted page; `rel="noopener noreferrer"` mandatory; anti-`routerLink` assertion.
- *Frontend/a11y*: no BLOCKER. SHOULD-FIX folded: root-relative `href` (leading slash; never relative — `<base href>` trap); `target="_blank" rel="noopener noreferrer"` as the token-page default; footer mounted per-public-component (not `AppComponent`); `request-access` Fraunces re-audit; reuse the F61 axe-on-static-HTML `render()` helper; legal cross-links carry the `.home-link` 44 px class; static body out of `$localize` scope.

Implementation will get the mandatory ≥3-role review again at task close.

## Project Structure

### Documentation (this feature)

```text
specs/031-terms-privacy-notice/
├── plan.md              # This file
├── research.md          # Phase 0 output — decisions (URL/trailing-slash, schema type, footer, email-link, draft notice)
├── data-model.md        # Phase 1 output — LegalDocument content model + content-source schema
├── quickstart.md        # Phase 1 output — author/build/verify steps
├── contracts/           # Phase 1 output — 3 contracts (legal pages, SEO/AEO artifacts, in-app links)
│   ├── legal-pages.contract.md
│   ├── seo-aeo-artifacts.contract.md
│   └── in-app-links.contract.md
├── checklists/
│   └── requirements.md  # spec-quality checklist (from /speckit.specify + /speckit.clarify)
└── tasks.md             # Phase 2 output (/speckit.tasks — NOT created here)
```

### Source Code (repository root)

```text
frontend/
├── src/
│   ├── content/
│   │   └── legal/                         # NEW — first-party legal content source (mirrors content/articles/)
│   │       ├── terms/{meta.json, body.html}
│   │       ├── privacy/{meta.json, body.html}
│   │       └── AUTHORING.md               # NEW — how to edit legal copy + the draft-notice rule
│   ├── app/
│   │   ├── core/seo/
│   │   │   ├── article-build.lib.mjs      # EDIT — add assembleLegalPage(), LEGAL page emit into sitemap/llms; OR
│   │   │   └── legal-build.lib.mjs        # (alt) NEW sibling pure lib if article-build.lib grows unwieldy
│   │   ├── shared/
│   │   │   └── public-footer.component.ts # NEW — shared footer primitive (Terms + Privacy + home links)
│   │   └── features/
│   │       ├── home/home.component.ts                     # EDIT — mount public footer
│   │       ├── request-access/request-access.component.ts # EDIT — add Privacy link to existing notice (FR-009)
│   │       ├── schedule/…, status/…, feedback/…,          # EDIT — single inline Privacy link (FR-008)
│   │       └── booking/(manage|cancel|confirm).…
│   ├── robots.txt                         # EDIT — add anchored Allow lines for /terms,/privacy
│   ├── _headers                           # (no change to add; CI guard ensures no noindex rule matches)
│   └── llms.txt                           # (generator appends a Legal section at build; source unchanged)
├── lighthouserc.json                      # EDIT — audit /terms and /privacy
scripts/
├── seo-build-articles.mjs                 # EDIT — loadLegalPages(content/legal/), emit dist/terms|privacy/index.html, feed legal list into widened buildSitemap/buildLlms
└── seo-inject-origin.mjs                   # EDIT (REQUIRED) — add a fixed legal-page loop so __CADENCE_ROBOTS__/__CADENCE_PUBLIC_ORIGIN__ are substituted in dist/terms|privacy/index.html (it only processes a hardcoded file set + resources/ today)
backend/
└── src/main/java/com/cadence/…            # EDIT (content-only) — BuiltInEmailTemplates + MergeTokenCatalogue: Privacy link in candidate template footers (FR-020)
.github/workflows/ci.yml                   # EDIT — robots ok-set + new legal-page sitemap/llms/structured-data assertions; _headers no-legal-noindex guard
```

**Structure Decision**: Frontend + build-time + static-content feature with one content-only backend edit. New code clusters in `frontend/src/content/legal/` (authored content), the static-content generator lib + CLI (emit + crawl artifacts), a shared Angular footer + per-surface links, and the built-in email templates. No backend service/repository/domain/scheduler is added.

## Complexity Tracking

> No Constitution Check violations. No entries.

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| — | — | — |
