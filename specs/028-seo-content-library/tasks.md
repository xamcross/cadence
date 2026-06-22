---
description: "Task list for SEO/AEO Content Article Library (028)"
---

# Tasks: SEO/AEO Content Article Library

**Input**: Design documents from `/specs/028-seo-content-library/`
**Prerequisites**: plan.md, spec.md, research.md (D1-D10), data-model.md, contracts/ (3), quickstart.md

**Tests**: INCLUDED — Constitution §VII mandates test-first for non-trivial logic, and research.md D7 specifies the test strategy. Write each story's tests first and confirm they FAIL before implementing.

**Organization**: Tasks are grouped by the three user stories from spec.md (US1 search discovery = P1/MVP, US2 AEO extraction = P2, US3 indexing-safe maintenance = P3).

> ⚠️ **GATE BEFORE IMPLEMENTATION (Constitution C1)**: A content article library is outside spec §11 (MVP scope). The plan marks C1 **CONDITIONAL — needs explicit owner sign-off** (a marketing/discoverability waiver, like 026-seo-aeo; no constitution amendment needed). **Do not start Phase 1 until the owner confirms.**

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependency on an incomplete task)
- **[Story]**: US1 / US2 / US3 (user-story phases only)
- All paths are repo-root-relative.

## Architecture recap (from plan.md / research.md)

- Frontend/static + build-tooling ONLY. No backend, no MongoDB, no new dependency.
- Generator is split: **pure lib** `frontend/src/app/core/seo/article-build.lib.ts` (node:fs-free, Karma-testable) + thin **CLI** `scripts/seo-build-articles.mjs` (fs/scan, Node-tested).
- Build pipeline: `ng build` → `node scripts/seo-build-articles.mjs <dist>` → `node scripts/seo-inject-origin.mjs <dist>`. Output `dist/cadence/browser`.
- Articles are static `dist/resources/<slug>/index.html` (directory-index form), NOT Angular routes.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Scaffolding all stories build on.

- [X] T001 Create the article source scaffold `frontend/src/content/articles/` (a `.gitkeep` + an `AUTHORING.md` summarizing the `meta.json` + `body.html` contract from `specs/028-seo-content-library/contracts/article-source.contract.md`)
- [X] T002 [P] Create the pure-lib skeleton `frontend/src/app/core/seo/article-build.lib.ts` — exported, `node:fs`-free function signatures (`validateMeta`, `lintBody`, `selectRelated`, `assembleArticlePage`, `assembleIndexPage`, `buildSitemap`, `buildLlms`, `buildFeed`, `faqDedupCheck`) + shared types (`ArticleMeta`, `ThemeKey`) per data-model.md
- [X] T003 [P] Create the CLI skeleton `scripts/seo-build-articles.mjs` — directory scan + fs I/O wrapper that imports the lib and writes `dist/`; accepts a build-date arg (never calls `Date.now()` itself, for deterministic tests); pure ASCII
- [X] T004 [P] Create the Node end-to-end test harness skeleton `scripts/seo-build-articles.node.test.mjs` (temp fixture `content/articles/`, run the CLI, assert emitted files) using `node:test`

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Core generator + build wiring + the two source edits that EVERY story needs.

**⚠️ No user-story work can begin until this phase is complete.**

- [X] T005 Implement `validateMeta()` + the `ThemeKey` enum in `frontend/src/app/core/seo/article-build.lib.ts` (slug shape, required fields, ≤60-word summary, date formats, theme membership, `related` resolves — per `contracts/article-source.contract.md`; slug-collision is US3)
- [X] T006 Implement the minimal no-JS page skeleton `assembleArticlePage()` in `article-build.lib.ts` — `<!doctype html>`, `<html lang="en">`, single `<h1>`=escaped title, lead=escaped summary, body-fragment insertion, self-canonical `/resources/<slug>`, `__CADENCE_ROBOTS__` meta placeholder; HTML-escape all `meta.json` values on emit (research D2). **FR-018 classification**: the `__CADENCE_ROBOTS__` placeholder IS the explicit "indexable content page" marker (prod → `index,follow`; non-prod → `noindex`) — distinct from the SPA's `route-seo.model` deny-by-default and home-only `PUBLIC_HOME` markers. Because articles are static files OUTSIDE the Angular route table, the `route-seo-inventory` "exactly one indexable Angular route" assertion is unaffected (document this in a code comment)
- [X] T007 [P] Extend `scripts/seo-inject-origin.mjs` to also walk and patch the generated `dist/resources/**/index.html` (substitute `__CADENCE_PUBLIC_ORIGIN__` + `__CADENCE_ROBOTS__`), keeping the existing index.html/sitemap/llms/robots patches and the non-prod blanket-disallow behavior
- [X] T008 [P] Edit `frontend/src/robots.txt` — add ONLY the scoped `Allow: /resources/` and `Allow: /resources$` (library prefix, no broad wildcard), keeping `Disallow: /` and the narrow asset/home allows (FR-019)
- [X] T009 [P] Edit `frontend/src/index.html` — add `"@id": "https://__CADENCE_PUBLIC_ORIGIN__/#organization"` to the `Organization` JSON-LD and align the `WebSite` node's publisher to that `@id` (research D6 shared graph)
- [X] T010 Wire the generator into the build pipeline in `.github/workflows/ci.yml` and `scripts/deploy-frontend.ps1`. Both CI jobs run with `working-directory: frontend`, so the generator MUST use the `../scripts/` prefix (matching the existing `node ../scripts/seo-inject-origin.mjs dist/cadence/browser` invocation), NOT the repo-root form. Concretely:
  - **`lighthouse` job**: insert a new step `node ../scripts/seo-build-articles.mjs dist/cadence/browser` immediately after the `Build Angular app` step (`ci.yml` ~419-425) and BEFORE the `Verify NON-PRODUCTION SEO injection` step's `cp -r dist/cadence/browser /tmp/seo-nonprod` (~ci.yml:435) — so both the `/tmp/seo-nonprod` copy and the prod dist contain `/resources` (then run the generator on the `/tmp/seo-nonprod` copy too before its non-prod inject).
  - **`deploy-frontend` job**: insert the same generator step between the `Inject API URL and build` step (~ci.yml:615-621) and the prod `Inject SEO origin` step (~ci.yml:623-628) — this job is asymmetric with `lighthouse` (different build step), so anchor it separately.
  - **`scripts/deploy-frontend.ps1`**: add `$GenScript = Join-Path $RepoRoot "scripts\seo-build-articles.mjs"; & node $GenScript $DistDir` BEFORE the existing `seo-inject-origin.mjs` invocation block (keep pure ASCII + CRLF).
- [X] T010a Wire the Node generator unit test into CI: add a step running `node --test scripts/seo-build-articles.node.test.mjs` (e.g. in the `frontend-test` or `lighthouse` job) so the CLI/fs end-to-end test (T004/T035) actually executes in CI (Build review N3 — otherwise it exists but never runs)

**Checkpoint**: A single hand-made fixture article now builds to `dist/resources/<slug>/index.html` and is served locally. Story work can begin.

---

## Phase 3: User Story 1 - Topical search discovery (Priority: P1) 🎯 MVP

**Goal**: A prospective customer finds Cadence via a topical search — articles have their own crawlable URL, are in the sitemap, are no-JS readable, and link home + ≥1 related article; the home links into the library.

**Independent Test**: Build, then confirm `/resources` lists every article, each `/resources/<slug>` shows full title+body with JS disabled, the home has a working link to `/resources`, and `sitemap.xml` contains `/`, `/resources`, and each article with a `<lastmod>`.

### Tests for User Story 1 (write first, MUST fail)

- [X] T011 [US1] Unit test in `frontend/src/app/core/seo/article-build.lib.spec.ts`: `assembleArticlePage()` yields one `<h1>`=title, lead=summary, full body text, `<html lang>`, self-canonical `/resources/<slug>`, and the CLI emits it at the `dist/resources/<slug>/index.html` path form (FR-002/FR-005/SC-003)
- [X] T012 [US1] Unit test in `article-build.lib.spec.ts`: `buildSitemap()` URL set == exactly `{"/","/resources","/resources/<slug>"...}` from the article array, contains 0 private/token paths, and every `<url>` has a `<lastmod>`; home keeps `priority 1.0`; **assert the launch set yields ≥6 indexable URLs (home + index + ≥4 articles) — SC-001**; and the **empty-library** case (zero articles) marks the index `noindex` (spec edge case / data-model) (FR-007/SC-001/SC-002/SC-010/SC-011)
- [X] T013 [US1] Unit test in `article-build.lib.spec.ts`: `assembleIndexPage()` lists every article (linked title + summary + date) and links home; `selectRelated()` returns ≥1 same-theme article (incl. the `related:[]` auto-select) with reciprocal back-link; single-article-theme emits no self/zero-related link (FR-003/FR-006/SC-009)
- [X] T014 [P] [US1] Component test in `frontend/src/app/features/home/home.component.spec.ts`: the home renders a plain `<a href="/resources">` (full-page nav, not `routerLink`) (FR-004/FR-017)

### Implementation for User Story 1

- [X] T015 [US1] Implement `selectRelated()` (same-theme pick + reciprocal completion + degenerate single-article case) in `frontend/src/app/core/seo/article-build.lib.ts`
- [X] T016 [US1] Implement `buildSitemap()` (allow-list URL set, per-URL `<lastmod>` = `dateUpdated ?? datePublished`, home `priority 1.0` + lastmod from newest article/build-date arg, `__CADENCE_PUBLIC_ORIGIN__` placeholder) in `article-build.lib.ts`
- [X] T017 [US1] Extend `assembleArticlePage()` in `article-build.lib.ts` with visible breadcrumb nav (Home→Resources→Title, real `<a>`), the home link, related-article links, and the published date (and last-updated when set) rendered in the body (FR-014/FR-017)
- [X] T018 [US1] Implement `assembleIndexPage()` in `article-build.lib.ts` (library index: one card per article sorted by date desc, links to each article + home). Index is **indexable only when ≥1 article exists** (zero articles → emit `noindex` to avoid a thin indexable page — spec edge case / data-model)
- [X] T019 [US1] Implement CLI emission in `scripts/seo-build-articles.mjs`: scan `frontend/src/content/articles/*/`, call the lib, write `dist/resources/<slug>/index.html`, `dist/resources/index.html`, and overwrite `dist/sitemap.xml`
- [X] T020 [US1] Add the home→library anchor in `frontend/src/app/features/home/home.component.ts` (plain `<a href="/resources">`) and mirror it in the static `<app-root>` no-JS block in `frontend/src/index.html`
- [X] T021 [P] [US1] Author article 1 `frontend/src/content/articles/reducing-interview-no-shows/` (`meta.json` + `body.html`)
- [X] T022 [P] [US1] Author article 2 `frontend/src/content/articles/candidate-experience-best-practices/`
- [X] T023 [P] [US1] Author article 3 `frontend/src/content/articles/interview-scheduling-and-calendar-coordination/`
- [X] T024 [P] [US1] Author article 4 `frontend/src/content/articles/gdpr-safe-recruiting/`

**Checkpoint**: MVP — a real, indexable, no-JS-readable article library reachable from the home and present in the sitemap.

---

## Phase 4: User Story 2 - Answer-engine extraction (Priority: P2)

**Goal**: An answer engine extracts a concise, attributable answer — each article carries Article + Breadcrumb + per-article FAQ/HowTo + speakable structured data tied to the shared Organization identity; `llms.txt` and an Atom feed list every article.

**Independent Test**: Inspect an article: its JSON-LD `JSON.parse`s with `@type` Article + BreadcrumbList + FAQPage/HowTo, `publisher`/`author` = the shared Org `@id`, the lead summary answers the core question; `llms.txt` lists each article URL and `/resources/feed.xml` has an entry per article.

### Tests for User Story 2 (write first, MUST fail)

- [X] T025 [US2] Unit test in `article-build.lib.spec.ts`: each article's Article/BlogPosting + BreadcrumbList JSON-LD `JSON.parse`s with required fields, `mainEntityOfPage`=canonical, and `publisher`+`author` reference `…/#organization`; **a title containing a `"` still `JSON.parse`s** (escaping, vs T028); and a **negative** assertion that no JSON-LD carries `"@type":"Person"` / `author.name` / an email (D6 org-only) (FR-008/SC-004)
- [X] T026 [US2] Unit test in `article-build.lib.spec.ts`: per-article `FAQPage`/`HowTo` + `speakable` present and lead-summary-first; `faqDedupCheck()` FAILS the build when an article headline/FAQ near-duplicates the `index.html` home-FAQ set (FR-009/FR-021)
- [X] T027 [US2] Unit test in `article-build.lib.spec.ts`: `buildLlms()` lists every article URL; `buildFeed()` emits one Atom `<entry>` per article (FR-013/SC-011, research D10)

### Implementation for User Story 2

- [X] T028 [US2] Implement JSON-LD assembly (Article/BlogPosting + BreadcrumbList, shared Org `@id` refs) in `article-build.lib.ts`, emitted by `assembleArticlePage()`. **JSON-string-escape every `meta.json` value written into a JSON-LD field** (a stray `"`/`<`/`\` in a first-party title/summary must not break the structured data) — distinct from T006's HTML-escaping (Security SHOULD-FIX)
- [X] T029 [US2] Add per-article `FAQPage`/`HowTo` + `speakable`, per-article Open Graph/Twitter tags, and `hreflang` (en + x-default) to `assembleArticlePage()` in `article-build.lib.ts` (research D6)
- [X] T030 [US2] Implement `faqDedupCheck()` in `article-build.lib.ts` (normalize + compare article headline/FAQ questions against the `index.html` home-FAQ question set; throw/fail on near-duplicate) and call it in the CLI build (FR-021). The home-FAQ question set is read from `frontend/src/index.html`'s `FAQPage` JSON-LD at build time — pass it in as a function arg (keep the lib `node:fs`-free; the CLI reads the file)
- [X] T031 [US2] Add `CollectionPage` + `ItemList` JSON-LD and the `<link rel="alternate" type="application/atom+xml">` to `assembleIndexPage()` in `article-build.lib.ts`
- [X] T032 [US2] Implement `buildLlms()` (per-article URLs section) + `buildFeed()` (Atom) in `article-build.lib.ts`; have the CLI write `dist/llms.txt` and `dist/resources/feed.xml` (`scripts/seo-build-articles.mjs`)

**Checkpoint**: Articles are extractable and attributable by answer engines; feed + llms.txt expose the full set.

---

## Phase 5: User Story 3 - Indexing-safe maintenance (Priority: P3)

**Goal**: Add/update/retire articles over time without breaking crawl-control or leaking anything private — slug collisions fail the build, retirement removes the page everywhere together, non-prod stays noindex, and nothing private/PII reaches an artifact.

**Independent Test**: Add an article → it auto-appears in index+sitemap+llms+feed; remove one → it 404s (noindex) and is gone from all artifacts together; a duplicate slug fails the build; a non-prod build is noindex on a `/resources/<slug>` page; the artifact scan finds 0 tokens/PII/private routes.

### Tests for User Story 3 (write first, MUST fail)

- [X] T033 [US3] Unit test in `article-build.lib.spec.ts`: a duplicate slug throws/fails; an article absent from the input array is absent from page emission, sitemap, llms, feed, and index together (FR-012/FR-016/SC-007/SC-012)
- [X] T034 [US3] Unit test in `article-build.lib.spec.ts`: `lintBody()` rejects `<script>`/`<iframe>`/`on*=`/`javascript:`/`data:` and any link NOT matching the public allow-list `^/(resources/|$|#)` or vetted `https://`; accepts a public `/resources/...` link; rejects an embedded token/email sentinel (FR-011/FR-020/SC-005)
- [X] T035 [P] [US3] Node test in `scripts/seo-build-articles.node.test.mjs`: after a non-prod (`CADENCE_PUBLIC_ENV=preview`) inject, an actual `dist/resources/<slug>/index.html` contains `noindex,nofollow` AND `_headers` ends with the `/*` `X-Robots-Tag: noindex` block; **symmetric positive: after a `CADENCE_PUBLIC_ENV=production` inject, the same file is `index,follow`** (so a bug that forces blanket-noindex even in prod — silently killing the feature — is caught) (FR-010/SC-008, Security review)

### Implementation for User Story 3

- [X] T036 [US3] Implement slug-collision detection (fail build) and confirm retirement semantics (all artifacts derived solely from the current directory scan) in `article-build.lib.ts` + `scripts/seo-build-articles.mjs`
- [X] T037 [US3] Implement `lintBody()` in `article-build.lib.ts` — allow-list link safety, script/handler/scheme rejection, no-`<h1>`, and token/email/phone sentinel rejection — and invoke it per article in the CLI build (FR-011/FR-020)
- [X] T038 [US3] Extend the SEO artifact scan in `.github/workflows/ci.yml` over `dist/resources/**/index.html` + `sitemap.xml` + `llms.txt` + `feed.xml` + JSON-LD. Specifics (Build + Security review):
  - **Update the existing hard-coded robots `Allow:` `ok` set** (currently `{"/$","/favicon.ico","/assets/","/*.js$","/*.css$","/*.woff2$"}`) to also permit `"Allow: /resources/"` and `"Allow: /resources$"` — WITHOUT this edit, T008's robots change reds the existing scan (guaranteed build break).
  - **Pin the private-route deny set**: scan the artifacts for any path token NOT under `/resources`, `/assets`, the home, or an allowed asset — enumerate the actual private prefixes (`/app`, `/admin`, `/login`, `/not-authorized`, `/accept-invite`, `/reset`, `/calendar`, `/interview-templates`, `/email-templates`, `/schedule`, `/booking`, `/confirm`, `/status`, `/feedback`, `/scheduling`, `/pipeline`) and fail on any. Use **path-anchored** patterns (e.g. `href="/scheduling"`, not the bare word "scheduling") so legitimate marketing prose ("interview scheduling") does not false-positive.
  - 0 candidate tokens / personal data (positive-control sentinel proves the deny-grep is live); **negative assertion: no `"@type":"Person"` / `author.name` / email in any JSON-LD** (D6 org-only).
  - No leftover `__CADENCE_*__`; every article URL present in BOTH `sitemap.xml` and `llms.txt` with a `<lastmod>`; add `feed.xml` + the generated article files to the existing non-vacuity existence/non-empty loop (currently only `robots/sitemap/llms/index.html`).
  - The non-prod-noindex-on-a-`/resources/<slug>`-file check (research D7, crawl-artifacts.contract.md).

**Checkpoint**: The library is safe to maintain — additions/retirements are automatic and nothing private can leak.

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: Accessibility/performance gates and release — these apply to ALL generated pages.

- [X] T039 [P] Add the axe-core WCAG 2.2 AA gate in `frontend/src/app/core/seo/article-build.lib.spec.ts` (or a sibling a11y spec) using the EXISTING `frontend/src/testing/axe.ts` API — `attachToBody(el)` then `await axeViolations(el)` and assert `[]` (the WCAG 2.2 AA tag set is internal to that helper; do NOT re-invoke `axe.run` with your own tags). The lib returns a full `<!doctype html>…</html>` document, so the spec MUST inject only the `<body>`/`<main>` content into the test element (not the whole nested `<html>`). Cover the library-index + ≥1 article; add explicit `getBoundingClientRect()` ≥44 px and a long/non-Latin-title `scrollWidth <= clientWidth` overflow check (FR-015/SC-006, the 026 RTL precedent)
- [X] T040 [P] Extend `lighthouserc.json` (add `/resources` + one `/resources/<slug>` to the audited URL set) AND **add directory-index resolution to `frontend/lighthouse/serve-with-stub.mjs`** (Build BLOCKER): its static branch only serves `existsSync(candidate) && isFile()`, so a request to `/resources` resolves to the directory and falls through to the SPA `index.html` — Lighthouse would audit the Angular shell, not the generated library index. Map `/resources` → `/resources/index.html` and `/resources/<slug>` → `/resources/<slug>/index.html` (try `<path>/index.html` when `<path>` is a directory) so LHCI measures the real pages. Assert `categories:performance` ≥0.85 (hard) + `categories:accessibility` + `categories:seo` (supplement to the axe gate) (FR-015/SC-006)
- [X] T041 [P] Run the `specs/028-seo-content-library/quickstart.md` walkthrough end-to-end (build → generator → inject → serve → verify `/resources`, an article, sitemap, llms, feed) and fix any drift
- [X] T042 Byte-scan all new/edited `.mjs`/`.ts`/`.html` sources for NUL/non-ASCII and confirm `scripts/deploy-frontend.ps1` stays pure ASCII + CRLF (Constitution C5 / the F30 binary-detection lesson)
- [X] T043 Run the mandatory multi-role sub-agent review (≥3 roles: Frontend/Build, Security/Privacy, SEO/AEO, QA) over the implementation diff; apply or report every finding before closing (Constitution §VI). DONE: Loop 1 (4 roles) found 0 BLOCKERs + SHOULD-FIX (rich-results @id/logo, Atom author, home feed link, duplicate_title, FAQ substring test, categories:seo) — all applied; Loop 2 (build/CI + SEO) found 1 BLOCKER (orphan top-level `aggregationMethod` illegal with `assertMatrix`) — fixed + verified against the real `@lhci/utils` guard. ng test 288/288, node --test 3/3.
- [ ] T044 After merge to `main`, deploy the frontend with `scripts\deploy-frontend.ps1` (frontend-only change) and spot-check `/resources` + structured data on the live origin

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: after the C1 owner sign-off — no other dependency
- **Foundational (Phase 2)**: depends on Setup — **BLOCKS all user stories**
- **User Stories (Phase 3-5)**: all depend on Foundational; then proceed in priority order P1 → P2 → P3 (or in parallel if staffed)
- **Polish (Phase 6)**: depends on the user stories whose pages it gates (axe/Lighthouse need real generated pages — so after US1 at minimum; the full scan after US3)

### User Story Dependencies

- **US1 (P1)**: starts after Foundational — no dependency on US2/US3. Independently testable (the MVP library).
- **US2 (P2)**: starts after Foundational — extends the SAME `assembleArticlePage()`/CLI; logically builds on US1's pages but its JSON-LD/feed/llms are independently testable. If US1 and US2 are worked in parallel, they touch `article-build.lib.ts` together — coordinate (not `[P]` across the two stories).
- **US3 (P3)**: starts after Foundational — adds lifecycle/safety guards around the same lib/CLI; independently testable. Same-file coordination with US1/US2.

### Within Each User Story

- Tests first (write, confirm FAIL) → lib functions → CLI wiring → article content.
- `article-build.lib.ts` is the shared file across stories: `[P]` applies WITHIN a story to tasks touching *different* files (tests vs content vs CLI), not to two tasks editing the lib at once.

### Parallel Opportunities

`[P]` means safe to run concurrently because the tasks touch **different files**. Tasks editing the shared `article-build.lib.ts` or `article-build.lib.spec.ts` are NOT `[P]` relative to each other (sequence them), even within one story.

- Setup: T002, T003, T004 in parallel (different files).
- Foundational: T007, T008, T009 in parallel (different files); T005/T006 are the same lib (sequential); T010/T010a are CI/script wiring.
- US1: the 4 article-authoring tasks T021-T024 are fully parallel (separate dirs); T014 (home component spec) is parallel to the lib specs. T011/T012/T013 share the lib spec file (sequential among themselves); T015-T019 share the lib/CLI (sequential).
- US2: T025/T026/T027 share the lib spec (sequential); impl T028-T032 share the lib/CLI (sequential).
- US3: T033/T034 share the lib spec (sequential); T035 is the Node test (different file, `[P]`).
- Polish: T039/T040/T041 in parallel (different files).
- **Cross-story note**: T038 (full artifact scan) and T039/T040 (axe/Lighthouse) need real generated pages — run them after US1 at minimum; T038's feed/JSON-LD checks require US2's outputs, so if US2 and US3 run in parallel, T038 lands after US2.

---

## Parallel Example: User Story 1

```bash
# Genuinely parallel (different files):
Task: "T014 home.component.spec.ts — home links to /resources"   # different spec file from the lib specs

# Article authoring (fully parallel — separate directories):
Task: "T021 reducing-interview-no-shows/"
Task: "T022 candidate-experience-best-practices/"
Task: "T023 interview-scheduling-and-calendar-coordination/"
Task: "T024 gdpr-safe-recruiting/"

# Sequential within the shared lib spec (NOT parallel — same file):
#   T011 (page shape) -> T012 (sitemap + SC-001 count) -> T013 (index + related)
```

---

## Implementation Strategy

### MVP First (User Story 1 only)

1. Owner signs off C1. 2. Phase 1 Setup. 3. Phase 2 Foundational. 4. Phase 3 US1. 5. **STOP & VALIDATE**: build → serve → confirm `/resources` + 4 articles + sitemap + home link, no-JS readable. 6. Add the axe/Lighthouse polish gates (T039/T040) for the §IX bar. Deploy as the MVP indexing lift.

### Incremental Delivery

1. Setup + Foundational → foundation ready.
2. US1 → test → **MVP** (6+ indexable URLs, SC-001).
3. US2 → test → AEO extractability + feed.
4. US3 → test → safe maintenance + full artifact scan.
5. Polish (axe/Lighthouse/review/deploy).

### Notes

- `[P]` = different files, no incomplete-task dependency.
- `article-build.lib.ts` and `scripts/seo-build-articles.mjs` are shared across stories — sequence edits to them.
- Verify each story's tests FAIL before implementing (Constitution §VII).
- Commit after each task or logical group; `git add -A` immediately before commit (the stale-index rule in CLAUDE.md).
- Do NOT add `@angular/ssr`, a prerender package, or a Markdown library; do NOT widen the robots `Allow:` beyond `/resources/`; do NOT generate the sitemap from the route table (research D1/D3/D4).
