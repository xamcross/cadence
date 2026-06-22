# Implementation Plan: SEO/AEO Content Article Library

**Branch**: `028-seo-content-library` | **Date**: 2026-06-22 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/028-seo-content-library/spec.md`

## Summary

Turn the site's single thin indexable page (the marketing home shipped by 026-seo-aeo) into a topical, crawler-readable **article library** to raise the SEO/AEO indexing surface. The library is a set of public, indexable static pages — a library index plus 4-6 themed articles (reducing no-shows, candidate experience, scheduling/calendar coordination, GDPR-safe recruiting) — each with its own clean URL, full no-JS-readable body, Article + BreadcrumbList structured data, a self-canonical, and internal cross-links (home ⇄ library ⇄ article).

**Technical approach**: an in-house, zero-dependency Node build-time **generator** (a `.mjs` script, mirroring the existing `scripts/seo-inject-origin.mjs` precedent) that reads first-party article source files and emits static `/resources/` HTML pages into the Angular `dist/` output, then regenerates `sitemap.xml` and `llms.txt` from the **published-article allow-list only**. The articles are NOT Angular routes (so no `@angular/ssr`/prerender dependency is needed — 026 research D1 rejected SSR), which makes no-JS readability true by construction. The Angular SPA is untouched except for one plain anchor from the home to `/resources`. The existing `seo-inject-origin.mjs` origin/robots/indexability injection and the deny-by-default robots/`_headers`/non-prod-noindex controls are reused and extended to cover the new pages. No backend, no MongoDB, no Mongock changeset, no new runtime or build dependency.

## Technical Context

**Language/Version**: TypeScript 5.4 / Angular 17.3 (frontend, untouched for the app); Node (build-time generator script, ESM `.mjs`, same Node already used by `seo-inject-origin.mjs` and the Angular build). No backend change (Java 21 / Spring Boot 3.3.5 untouched).
**Primary Dependencies**: None new. Reuses the 026-seo-aeo machinery (`seo-inject-origin.mjs`, `robots.txt`/`sitemap.xml`/`llms.txt`/`_headers`/`index.html` JSON-LD, `SeoService`/`route-seo.model`) and the F14 dev/audit tools `axe-core` + `@lhci/cli` (already devDependencies). **Explicitly NOT added**: `@angular/ssr`, `@angular/platform-server`, any prerender package, `marked`/`markdown-it`/any Markdown or templating library.
**Storage**: N/A — no database interaction. Article source is first-party static files in the repo; output is static HTML served by Cloudflare Pages.
**Testing**: the generator is split into a **pure-functions lib under `src/`** (`article-build.lib.ts`, `node:fs`-free — string in/out) + a **thin CLI `.mjs`** in `scripts/` that does file I/O. Jasmine/Karma (existing `ng test` harness) tests the pure lib (structure, sitemap allow-list, lint, JSON-LD shape, FAQ-dedup, related auto-select, date-in-body, retirement) **and** runs the existing **axe-core WCAG 2.2 AA in-Karma harness** (`src/testing/axe.ts`, `attachToBody`) over the generated index + >=1 article HTML string (the §IX/DoD accessibility gate — zero new dependency, since `axe-core` is already a devDependency). A **Node-side end-to-end test** exercises the CLI's fs/scan behavior against a temp fixture. `@lhci/cli` audits the library index + one article (performance >=0.85 hard + accessibility + SEO categories — a supplement to the axe gate, not the SC-006 authority). CI artifact scans (the 026 precedent) cover token/PII/private-route leakage, the robots allow-list, and the non-prod noindex on a `/resources/` file. See research.md D7. (Rationale for the split: a `.mjs` in `scripts/` is outside the `tsconfig.spec` `src/**` include and uses `node:fs`/`node:path`, which do not resolve in the Karma browser bundle — so the fs-free lib must live under `src/`.)
**Target Platform**: Static site on Cloudflare Pages (the existing frontend deploy target); pages must render fully without JavaScript.
**Project Type**: Web — frontend/static-site + build tooling only. No API, no service layer.
**Performance Goals**: Library index and each article page Lighthouse Performance >= 85 (mobile), matching the bar the public marketing page already meets; pages are static HTML so this is comfortably achievable.
**Constraints**: No-JS-readable body and structured data on every page (FR-005/FR-008); deny-by-default privacy preserved (no token/private route/PII in any artifact, FR-011/FR-019); sitemap built only from the article allow-list, never a route scan (FR-007); non-production builds stay fully non-indexable (FR-010/SC-008); zero new dependency (C4) and zero tool download (C7).
**Scale/Scope**: 4-6 launch articles + 1 library index page; designed to grow to dozens without code change (add a source file → it appears in the library, sitemap, and `llms.txt` automatically).

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Gate | Question | Result |
|---|---|---|
| **C1** | Within MVP scope (spec §11)? | **CONDITIONAL — needs explicit owner acknowledgement.** §11 does not enumerate a content library; this is a go-to-market/SEO/discoverability concern supporting the constitution's stated mandate of a "public, working MVP," directly extending the already-merged 026-seo-aeo SEO surface. It adds **no product capability** and touches **no deferred capability** (Flow A2, SMS, auto-send SLA, etc.). Because a content library is genuinely outside §11, the honest disposition is an **explicit owner waiver** (as 026-seo-aeo had), not a self-certified PASS. **Action: confirm owner sign-off before /tasks.** No constitution amendment is needed (it is a marketing surface, not a product/topology/stack change). |
| **C2** | New service, queue, or replica? | **PASS.** None. Static pages + a build-time script. No backend, no broker, no @Scheduled, no DB. |
| **C3** | Exposes candidate personal data to unauthorized roles? | **PASS.** The opposite risk — leaking PII *into* public pages — is the concern; addressed by FR-011/FR-020/SC-005 (automated artifact scan over articles/index/sitemap/`llms.txt`/structured data) and by the content being strictly first-party marketing material with no candidate data. |
| **C4** | Adds a dependency outside the fixed stack? | **PASS.** No new runtime or build dependency. In-house Node generator (the `seo-inject-origin.mjs` precedent); `axe-core`/`@lhci/cli` already present. `@angular/ssr` and any Markdown lib are explicitly rejected (see Research D1/D2). |
| **C5** | New/modified Windows scripts contain non-ASCII? | **GATE NOTED.** The generator is a cross-platform `.mjs` (not `.ps1/.cmd/.bat`), so Principle V's ASCII rule does not bind it — but any edit to `scripts/deploy-frontend.ps1` (to invoke the generator) MUST stay pure ASCII + CRLF, and all new `.mjs`/`.ts`/HTML sources MUST be scanned for NUL/non-ASCII (the F30 binary-detection lesson). A byte-scan is part of the Definition of Done. |
| **C6** | Multi-role sub-agent review (>=3 roles) scheduled? | **PASS.** Two-loop multi-role review (SEO/AEO, Security/Privacy, Frontend/Build, QA) is scheduled at plan close (this run, per the user request) and again at implementation close. |
| **C7** | Downloads any build tool/runtime/CLI? | **PASS.** Reuses the already-installed Node, Angular CLI, and the cached `@lhci/cli`/`axe-core`. No fetch. |

**Result: PASS.** No gate fails. No Complexity Tracking entries required (no architectural pattern beyond the minimum; the generator is a single procedural script).

## Project Structure

### Documentation (this feature)

```text
specs/028-seo-content-library/
├── plan.md              # This file
├── research.md          # Phase 0 output — key decisions (no-SSR delivery, authoring format, etc.)
├── data-model.md        # Phase 1 output — Article / Library / Theme entities + manifest schema
├── quickstart.md        # Phase 1 output — how to add an article and verify locally
├── contracts/           # Phase 1 output — generator I/O contract + page-output contract
│   ├── article-source.contract.md
│   ├── generated-page.contract.md
│   └── crawl-artifacts.contract.md
├── checklists/
│   └── requirements.md  # Spec-quality checklist (already validated)
└── tasks.md             # Phase 2 output (/speckit.tasks — NOT created here)
```

### Source Code (repository root)

```text
frontend/
├── src/
│   ├── content/
│   │   └── articles/                      # NEW — first-party article SOURCE (authored, not built)
│   │       ├── reducing-interview-no-shows/
│   │       │   ├── meta.json              # slug, title, summary, datePublished, dateUpdated, theme, related
│   │       │   └── body.html              # safe HTML body fragment (no <script>/<iframe>/on*=)
│   │       ├── candidate-experience-best-practices/
│   │       ├── interview-scheduling-and-calendar-coordination/
│   │       └── gdpr-safe-recruiting/      # (4 at launch — SC-001 floor; directory scan = source of truth)
│   ├── app/
│   │   ├── core/seo/
│   │   │   ├── article-build.lib.ts       # NEW — PURE functions (node:fs-free): page/sitemap/llms/feed
│   │   │   │                              #       assembly, safety lint, validation, FAQ-dedup
│   │   │   └── article-build.lib.spec.ts  # NEW — Jasmine unit + axe-in-Karma a11y gate over emitted HTML
│   │   └── features/home/home.component.ts  # MODIFIED — add a plain <a href="/resources"> link
│   ├── index.html                         # MODIFIED — add Organization @id (shared structured-data graph)
│   ├── sitemap.xml                        # MODIFIED — becomes generator-owned (home + library + articles)
│   ├── llms.txt                           # MODIFIED — generator lists each published article URL
│   ├── robots.txt                         # MODIFIED — add scoped `Allow: /resources/` (library prefix only)
│   └── _headers                           # (unchanged for prod; non-prod /* noindex appended by inject)
│
scripts/
├── seo-build-articles.mjs                 # NEW — thin CLI: dir-scan + fs I/O, calls article-build.lib
├── seo-build-articles.node.test.mjs       # NEW — Node end-to-end test of the CLI against a temp fixture
├── seo-inject-origin.mjs                  # MODIFIED — also patch generated /resources/**/index.html (origin+robots)
└── deploy-frontend.ps1                    # MODIFIED — invoke the generator between ng build and inject (ASCII+CRLF)

.github/workflows/ci.yml                   # MODIFIED — insert the generator step in the lighthouse + deploy jobs
                                           #            (between ng build and seo-inject-origin); run it before the
                                           #            non-prod fresh-dist copy; extend the SEO artifact scan;
                                           #            add LHCI targets for /resources + one article
lighthouserc.json                          # MODIFIED — audit the library index + one article URL
```

**Structure Decision**: Frontend/static-site + build-tooling only (the 026-seo-aeo shape). Article *source* lives under `frontend/src/content/articles/` (authored HTML fragments + JSON metadata; the directory scan is the source of truth — no manifest). The pure assembly/validation logic lives in `frontend/src/app/core/seo/article-build.lib.ts` (so Karma compiles and tests it, and so the axe-in-Karma a11y gate can render the emitted HTML); a thin `scripts/seo-build-articles.mjs` CLI does the file I/O and emits the static `/resources/` pages + crawl artifacts into `dist/` from the article allow-list. The Angular application code is untouched except for one anchor on the home page and the `index.html` Organization `@id`. No backend directory is involved. **4 articles at launch (SC-001 floor); 4-6 target.**

## Complexity Tracking

> No Constitution Check violations — this section is intentionally empty.

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| (none) | — | — |
