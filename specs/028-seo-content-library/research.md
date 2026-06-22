# Phase 0 Research: SEO/AEO Content Article Library

All Technical Context items are resolved below. There are no remaining NEEDS CLARIFICATION markers.

---

## D1. No-JS-readable article delivery WITHOUT an SSR/prerender dependency

**Decision**: Deliver the library index and each article as **static HTML files** generated at build time by an in-house, zero-dependency Node script (`scripts/seo-build-articles.mjs`), written into the Angular `dist/` output under `/resources/`. The articles are **not** Angular routes.

**Rationale**:
- FR-005/SC-003 require the full title and body to be readable by a no-JavaScript crawler. The existing home satisfies this by hand-authoring its content into `index.html` inside `<app-root>`; that does not scale to 4-6 articles, and there is only one `index.html`.
- 026-seo-aeo research D1 already rejected `@angular/ssr`/`@angular/platform-server`/prerendering: each is a new build dependency and triggers a network fetch (Principle X / C7) and introduces a server model (Principle IV). That rejection still holds.
- A build-time static generator mirrors the **already-approved** `seo-inject-origin.mjs` precedent (pure Node, no dependency, runs on the emitted `dist/`). Static files are no-JS-readable *by construction* — there is no client render step to bypass.
- Cloudflare Pages serves a real static file at `/resources/<slug>` directly and only falls back to the SPA `index.html` for paths with no matching file, so the static article pages are not shadowed by the Angular wildcard `**` route. (The home's link to the library MUST be a plain `<a href="/resources">` full-page navigation, not a `routerLink`, so it leaves the SPA and loads the static page.)

**On-disk layout (pinned — review fix)**: each article is emitted as `dist/resources/<slug>/index.html` (directory-index form), and the library index as `dist/resources/index.html`. The earlier "`<slug>/index.html` or `<slug>.html`" ambiguity is resolved to the directory form ONLY.

**Trailing-slash correction (post-deploy, verified on cadenceapp.cc)**: the directory-index form is served by Cloudflare Pages at the **trailing-slash** URL `/resources/<slug>/`, and a request to the no-slash `/resources/<slug>` is **308-redirected** to add the slash. The initial assumption that the directory form serves a clean *no-slash* URL was wrong. So the canonical, sitemap `<loc>`, `llms.txt`, Atom feed `<link>`/`<id>`, breadcrumb, related links, index cards, and the home -> library link all use the **trailing-slash form** (`/resources/` and `/resources/<slug>/`) to match exactly what is served — eliminating the self-canonical-points-at-a-redirect issue. (The `resources/feed.xml` href is a real file and keeps no trailing slash.)

**Alternatives considered**:
- *Add `@angular/ssr` + prerender the article routes* — rejected: new dependency + network fetch (C4/C7) + server model (P-IV); explicitly rejected already in 026 D1.
- *Hand-author each article into `index.html`/per-route static content* — rejected: does not scale, single `index.html`, and couples content to the SPA shell.
- *Render articles only in the Angular SPA (client-side)* — rejected: fails FR-005/SC-003 (no-JS crawlers see an empty shell).
- *`<slug>.html` flat files* — rejected: relies on Cloudflare's `.html`-extension-drop and risks a canonical (`/resources/<slug>`) vs served-URL (`/resources/<slug>.html`) mismatch; the directory-index form is unambiguous.

---

## D2. Article authoring format (no Markdown/templating dependency)

**Decision**: Author each article as a **first-party safe HTML body fragment** (`body.html`) plus a small **`meta.json`** (slug, title, summary, datePublished, dateUpdated, theme, related[]). The generator wraps the fragment in the full page template (head metadata, canonical, JSON-LD, breadcrumb nav, header/footer, internal links). The generator runs a **safety lint** on each fragment (see D-link below) and **escapes `meta.json` fields when emitting**: values placed into HTML are HTML-entity-escaped and values placed into JSON-LD are JSON-string-escaped (a stray `"` or `<` in a title/summary must not break the structured data or the markup — Security NICE-TO-HAVE). Content is first-party, so this is correctness hardening, not untrusted-input sanitization.

**Link-target safety is an ALLOW-LIST, not a blocklist (Security SHOULD-FIX)**: because the site is deny-by-default (every route except the home is PRIVATE, and new private routes are added over time), a hand-maintained blocklist of private prefixes would silently miss a future private route. Instead, every in-content `<a href>`/`<img src>` MUST match an **allow-list**: a relative URL under `^/(resources/|$|#)` (i.e. another article, the library, the home, or an in-page anchor) OR a vetted absolute `https://` URL to an external public site. Anything else (including any `/app`, `/admin`, `/schedule`, `/status`, … path, any `?token=`, any `javascript:`/`data:` scheme) is rejected at build (FR-011/FR-020). This needs no maintenance when a new private route is added.

**Rationale**:
- Avoids adding `marked`/`markdown-it`/any parser (C4) and avoids shipping an error-prone in-house Markdown converter. Content is first-party (authored by the team), so HTML fragments are safe to author directly; the lint is defense-in-depth, not untrusted-input sanitization.
- `meta.json` parses with `JSON.parse` (zero dependency, no YAML library) and is injection-safe.
- Keeps body and metadata separate so the generator can build both the article page and the library-index card from the same source.

**Alternatives considered**:
- *Markdown + in-house subset converter* — rejected: regex Markdown conversion is fiddly and risks malformed HTML/escaping bugs for marginal authoring convenience on a 4-6 item set.
- *Markdown + `marked` library* — rejected: new build dependency (C4) and a runtime fetch risk (C7).
- *Single combined file with a `---` frontmatter header* — viable, but a separate `meta.json` is simpler to parse with zero deps and clearer to validate.

---

## D3. Crawl-artifact generation: allow-list, never a route scan (Security BLOCKER #2)

**Decision**: The generator **owns** `sitemap.xml` and `llms.txt` generation. It builds the sitemap URL set from exactly `{home '/', library index '/resources', each published article '/resources/<slug>'}` — derived from the article set **only** — and never from the Angular route table or a `dist/` page scan. Each sitemap `<url>` carries a `<lastmod>` from the article's `dateUpdated ?? datePublished`; the home `/` entry keeps its existing `changefreq monthly` + `priority 1.0` and takes its `<lastmod>` from the most recent article date (or the build date passed in as an arg — the generator never calls `Date.now()` itself, to stay deterministic/testable). `llms.txt` is regenerated to list every published article URL. Both keep the `__CADENCE_PUBLIC_ORIGIN__` placeholder so `seo-inject-origin.mjs` substitutes the origin (single source of truth for prod/non-prod origin + robots).

**Article source-of-truth (review clarification)**: the published-article set is the **directory scan of `frontend/src/content/articles/*/`** (each dir with a valid `meta.json` + `body.html`). This is NOT the "route/page scan" the security review prohibits — that prohibition is about never deriving the sitemap from the Angular route table or the built `dist/` pages (which could sweep in private routes). Scanning the dedicated, public-only `content/articles/` source directory is the allow-list. There is no separate `articles.index.json` manifest (dropped to avoid two sources of truth); article ordering on the index is by `dateUpdated ?? datePublished` descending.

**Rationale**:
- Directly resolves the security review's sitemap-population BLOCKER: a route/page scan could sweep in `/schedule`, `/status`, `/feedback`, `/app`, etc. An explicit allow-list from the article manifest makes private/token routes structurally unrepresentable in the sitemap (FR-007/SC-010).
- Satisfies FR-007 (automatic inclusion, no manual edit), FR-013/SC-011 (`llms.txt` per-article URLs + sitemap `lastmod`), and SC-007 (add/retire reflected automatically).

**Alternatives considered**:
- *Angular `@angular/sitemap`-style route enumeration* — rejected: route-derived sitemaps are exactly the leakage risk the security review flagged.
- *Hand-maintained sitemap* — rejected: violates FR-007 (no manual editing) and drifts from the article set.

---

## D4. robots.txt allow-rule scoped to the library prefix (Security BLOCKER #1)

**Decision**: Add exactly one tight rule to `robots.txt`: `Allow: /resources/` (and `Allow: /resources$` for the index), scoped to the library path prefix. No broad pattern (no `/*.html$`, no extension wildcards). The deny-by-default `Disallow: /` stays; every other path remains disallowed. A CI assertion verifies the robots `Allow:` set is a subset of `{home, asset rules, /resources...}` and that no private/token prefix becomes crawlable (the 026 robots-Allow-subset guard, extended).

**Rationale**:
- Resolves the security review's robots BLOCKER: 026 deliberately narrowed its allow-list because longest-match-wins could let a broad rule out-match `Disallow: /` for a private path. A prefix-scoped `/resources/` rule cannot match any token/auth route (none live under `/resources/`).
- Satisfies FR-019/SC-010.

**Alternatives considered**:
- *Broad `Allow: /*.html$`* — rejected: could expose any private path that resolves to an `.html`, the exact 026 footgun.
- *No robots change (rely on sitemap)* — rejected: `Disallow: /` would block `/resources/`, so the articles would never be crawled.

---

## D5. Non-production stays non-indexable (two layers, reused)

**Decision**: The generated article pages carry the same `__CADENCE_ROBOTS__` placeholder in their `<meta name="robots">` as `index.html`. `seo-inject-origin.mjs` is extended to also walk the emitted `/resources/*.html` and substitute origin + robots there. In non-production (`CADENCE_PUBLIC_ENV !== 'production'`), `seo-inject-origin.mjs` already (a) overwrites `robots.txt` with an all-disallow body and (b) appends a global `X-Robots-Tag: noindex` to `_headers`; both now also cover `/resources/` (the `_headers` `/*` rule already matches all paths). So every article page is noindex in non-prod via both the crawl-control file and a per-page signal that holds on a direct fetch.

**Verification (Security SHOULD-FIX)**: the load-bearing non-prod control for a direct fetch of an article is the per-page `__CADENCE_ROBOTS__ → noindex,nofollow` substitution into `/resources/<slug>/index.html`. The CI non-prod check MUST therefore assert `noindex` on an actual generated `/resources/<slug>/index.html` (not only on `index.html`), AND that the non-prod `_headers` ends with the appended global `/*` `X-Robots-Tag: noindex` block (Cloudflare applies the most-specific match; `/*` is the correct fallback for `/resources/...`, which has no more-specific `_headers` entry).

**Rationale**: FR-010/SC-008. Reuses the existing prod-opt-in / deny-by-default switch with no new mechanism.

**Alternatives considered**: *A separate non-prod gate for articles* — rejected: duplicates logic; extending the single `seo-inject-origin.mjs` switch keeps one source of truth.

---

## D6. Structured data for SEO + AEO (Article + Breadcrumb + Collection + a coherent entity graph)

**Decision**: The generator emits, per article:
- a `BlogPosting`/`Article` JSON-LD block (`headline`, `description`, `datePublished`, `dateModified`, `mainEntityOfPage` = the article canonical, `publisher` and `author` both referencing the shared Organization node by `@id`);
- a `BreadcrumbList` (Home → Resources → Article).

The library index emits a `CollectionPage` with an `ItemList` of the articles. Publisher/author is the **Organization**; **no individual person's name, email, or contact detail** appears in any JSON-LD (Security SHOULD-FIX).

**Coherent entity graph via a shared `@id` (SEO SHOULD-FIX)**: `index.html`'s existing `Organization` block currently has no `@id`, so articles cannot reference "the same Cadence org" — engines would see N anonymous orgs. Fix: add `"@id": "https://__CADENCE_PUBLIC_ORIGIN__/#organization"` to the `index.html` `Organization` node (and align the `WebSite` node's `publisher`/`@id` similarly), and have every article's `publisher`/`author` be `{"@id": "…/#organization"}`. This makes one identity that the home, the website, and every article share.

**Per-article AEO markup (SEO SHOULD-FIX, backs US2)**: each article additionally carries a small `FAQPage` (2-4 Q&A distilled from its own content) OR, for how-to-style articles (no-shows, scheduling), a `HowTo` block, plus a `speakable` `SpeakableSpecification` pointing at the lead-summary selector. These are the highest-value answer-engine signals and directly serve the P2 "extractable, attributable answer" story.

**FAQ anti-duplication is ENFORCED, not just asserted (SEO SHOULD-FIX, FR-021)**: `index.html` already ships a `FAQPage` (incl. "Do candidates need to create an account?") and D9 launches a closely-related article. The generator MUST compare each article's headline + any article-level FAQ questions against the `index.html` home-FAQ question set and **fail the build** on a near-duplicate (normalized string match), so an article never competes with / duplicates the home FAQ rich result. The "account?" launch article must be framed distinctly (e.g. a broader candidate-experience angle) to pass this gate.

**Per-article Open Graph + Twitter (promoted to MUST, SEO SHOULD-FIX)**: each article page carries its own `og:*`/`twitter:*` (title, description, url, image — falling back to the existing `og-cadence.png` when no per-article image), since social/Slack/LLM-citation unfurls drive AEO discovery.

**`hreflang` self-reference now (cheap retrofit-avoidance)**: each indexable page emits `<link rel="alternate" hreflang="en" href="self">` + `hreflang="x-default"` so adding locales later does not require revisiting every page (localization itself stays out of scope).

**Rationale**: FR-008/SC-004 and the AEO goal (US2). Breadcrumb + collection + per-article FAQ/HowTo/speakable is the rich-result/answer-engine set that separates "indexed" from "ranks and gets cited." The shared `@id` and FAQ-dedup are the two failure modes that silently kill rich-result eligibility while passing a naive JSON-valid gate — so both become automated structural checks (see D7).

**Alternatives considered**: *Article metadata only (no breadcrumb/collection/FAQ)* — rejected: leaves the highest-value AEO markup on the table. *Personal-byline author* — rejected: standing PII/GDPR liability in indexed data. *Assert-but-don't-gate FAQ dedup* — rejected: an ungated "MUST NOT duplicate" is not testable (SEO + QA review).

---

## D7. Testing and verification strategy (no new dependency)

The generator is split into **two modules** so the test mechanism actually runs (Build BLOCKER):
- `frontend/src/app/core/seo/article-build.lib.ts` — **pure functions only** (string in / string out): page assembly (HTML + JSON-LD), library-index assembly, sitemap/`llms.txt` building from an in-memory article array, the body safety lint, slug/meta validation, the FAQ-dedup check. **No `node:fs`/`node:path` at module load** — so it imports cleanly in BOTH Node and the Karma/Jasmine browser bundle, and lives under `src/` so `tsconfig.spec.json` (`src/**/*.spec.ts`) compiles its spec.
- `scripts/seo-build-articles.mjs` — a **thin CLI wrapper** that does the directory scan + file I/O (`node:fs`/`node:path`), calls the pure lib, and writes `dist/`. It is exercised by a Node-side end-to-end test, not by Karma.

**Decision**:
- **Pure-lib unit tests** (`article-build.lib.spec.ts`, Jasmine in the existing `ng test` harness — imports the `node:fs`-free lib): slug-collision fails (FR-016/SC-012); the sitemap URL set equals exactly the allow-list and contains zero private/token paths (FR-007/SC-010); `lastmod` present per URL incl. the home entry (SC-011); `llms.txt` lists every article URL (SC-011); the safety lint rejects `<script>`/`on*=`/`javascript:`/`data:` and any link NOT matching the public allow-list (FR-011/FR-020); each emitted page string has a single `<h1>`, `<html lang>`, a self-canonical, the published/updated **date rendered in the body** (FR-014/SC-013), and JSON-LD blocks that `JSON.parse` with `@type` Article + BreadcrumbList (+ FAQPage/HowTo) and required fields, with `publisher`/`author` referencing the shared Organization `@id` (SC-004, SEO graph check); the FAQ-dedup check fails on a home-FAQ near-duplicate (FR-021); a sentinel token in a fragment is rejected/never emitted (FR-011/SC-005); **topically-related auto-select** returns ≥1 same-theme article when `related:[]`, and the single-article-in-a-theme degenerate case emits no self-link / no zero-related page (FR-006/SC-009); a **retired** article (absent from the input array) is absent from emission, sitemap, `llms.txt`, and the index together (FR-012/SC-007).
- **Accessibility gate (QA BLOCKER) — axe WCAG 2.2 AA in Karma, zero new dependency**: `axe-core` is already a devDependency and 026 already runs axe inside Karma via `frontend/src/testing/axe.ts` (`attachToBody` + `axe.run({runOnly: wcag2a/2aa/21a/21aa/22aa})`). The pure lib produces the page HTML as a string; the spec injects it into the Karma DOM (`document.body` attach — required or contrast/visibility rules no-op) and runs axe over the **library index + ≥1 article**, asserting 0 violations (FR-015/SC-006, Constitution DoD). An explicit `getBoundingClientRect()` ≥44 px check on interactive targets + a long/non-Latin-title `scrollWidth <= clientWidth` overflow check (the 026 RTL precedent) cover the legs axe's WCAG tag-set does not (SC-006 overflow leg).
- **Node end-to-end generator test** (`scripts` CLI): run `seo-build-articles.mjs` against a temp fixture `content/articles/` dir and assert the emitted `dist/resources/<slug>/index.html` files + regenerated `sitemap.xml`/`llms.txt` exist with the expected shape, and that a duplicate-slug fixture exits non-zero. Invoked in CI as a `node` step (not in Karma).
- **Component test**: the home renders a plain `<a href="/resources">` (FR-004/FR-017).
- **Lighthouse CI**: extend `lighthouserc.json` + the dist serve to audit `/resources` and one `/resources/<slug>` — `categories:performance` (>=0.85, hard) + `categories:accessibility` + `categories:seo` (FR-015/SC-006). Lighthouse-accessibility is a *supplement* to the axe gate, not the SC-006 authority.
- **CI artifact scan** (the 026 precedent, extended): over `dist/resources/**/index.html` + `sitemap.xml` + `llms.txt` + the JSON-LD blocks — 0 candidate tokens, 0 authenticated/`PRIVATE` routes, 0 personal data (positive-control sentinel proves the deny-grep matches); no leftover `__CADENCE_*__` placeholder; robots `Allow:` subset; each article URL present in BOTH `sitemap.xml` and `llms.txt` with a `<lastmod>`; and the **non-prod check asserts `noindex` on an actual `/resources/<slug>/index.html`** + the appended `_headers` `/*` block (Security SHOULD-FIX), on a fresh dist copy generated BEFORE the prod injection.

**Rationale**: Mirrors the 026 verification approach (Constitution §VII test-first + §IX axe DoD + the artifact-scan backstop) using only already-present tools (Jasmine, `axe-core`, `@lhci/cli`, CI grep). The pure-lib/CLI split is what makes the unit + a11y tests actually executable in Karma. Full schema.org Rich-Results validation stays a documented **manual** gate (the 026 precedent) — but the two eligibility-killers (shared `@id`, FAQ dedup) are now automated structural checks.

**Alternatives considered**: *axe-core against the static pages via jsdom* — rejected: jsdom is a new dependency; the existing axe-in-Karma `attachToBody` harness renders the generated HTML string for a real WCAG 2.2 AA gate with zero new dependency. *Jasmine importing the fs-touching `.mjs` directly* — rejected (Build BLOCKER): `scripts/*.mjs` is outside the `tsconfig.spec` `src/**` include and `node:fs`/`node:path` do not resolve in the Karma browser bundle; hence the pure-lib-under-`src/` + Node-CLI-test split.

---

## D8. Build pipeline ordering

**Decision**: The frontend build runs in this order: (1) `ng build` (SPA → `dist/`); (2) `node scripts/seo-build-articles.mjs <dist>` (emit `/resources/*.html`, regenerate `dist/sitemap.xml` + `dist/llms.txt` from the article allow-list, keeping origin/robots placeholders); (3) `node scripts/seo-inject-origin.mjs <dist>` (substitute origin + robots/indexability across `index.html`, `sitemap.xml`, `llms.txt`, `robots.txt`, **and** `/resources/*.html`; apply the non-prod deny-by-default rewrites). Wired into `ci.yml` (the `lighthouse` + `deploy-frontend` jobs) and `scripts/deploy-frontend.ps1`.

**Rationale**: The generator must run after `ng build` (artifacts only exist post-build) and before `seo-inject-origin.mjs` (which finalizes origin/robots for all files including the newly generated ones). This is the established `seo-inject-origin.mjs` post-build pattern.

**Alternatives considered**: *Run the generator as an Angular builder/plugin* — rejected: a custom builder is more coupling than a post-build script and risks a dependency; the post-build `.mjs` is the proven pattern.

---

## D9. Editorial scope and themes (launch floor)

**Decision**: Launch with **4 articles at the SC-001 floor (4-6 target)**, each a substantive standalone answer with a unique title/summary, on: (1) reducing interview no-shows, (2) candidate-experience best practices, (3) interview scheduling & calendar coordination (framed around self-scheduling logistics — deliberately NOT restating the home FAQ's "do candidates need an account?" Q&A; the no-account differentiator is woven in as a sub-point with a distinct headline, to pass the D6 FAQ-dedup gate), (4) GDPR-safe / privacy-conscious recruiting. Articles MUST NOT restate the home `index.html` FAQ content (FR-021, gated in D6). The library is designed to grow beyond the floor with no code change.

**Related-link reciprocity (SEO SHOULD-FIX)**: related links are **theme-clustered and reciprocal** — if article A lists B as related, the generator ensures B lists A (auto-completing the back-link when absent), so link equity circulates among articles within a theme rather than every article funnelling only back to the home. The auto-select fallback (when `related:[]`) picks same-theme articles.

**Rationale**: FR-001/FR-006/FR-021/SC-001/SC-009 and the SEO review's theme + reciprocity guidance — these map directly to Cadence's actual capabilities (per `llms.txt`), so the content is on-product and not inflated. Four is the demonstrable floor; the mechanism scales.

**Alternatives considered**: *A larger launch set (10+)* — deferred: not needed to demonstrate the indexing lift; the generator makes growth free. *Topically thin/overlapping articles* — rejected: thin/duplicate content can stall indexing even with valid markup (FR-021). *One-directional related links* — rejected: weak internal-link equity distribution (SEO review).

---

## D10. RSS/Atom feed for answer-engine + aggregator discovery (SEO NICE-TO-HAVE, adopted)

**Decision**: The generator also emits a zero-dependency `/resources/feed.xml` (Atom) listing the published articles (title, link, summary, updated), referenced by a `<link rel="alternate" type="application/atom+xml">` on the library index and home. It is added to the sitemap-adjacent artifacts and the CI artifact scan (no token/PII), and uses the `__CADENCE_PUBLIC_ORIGIN__` placeholder like the other artifacts.

**Rationale**: A feed is a conventional discovery signal that LLM/answer-engine and aggregator crawlers look for alongside `llms.txt`; it is trivial for the generator (it already has the article array) and adds no dependency. Adopted because the AEO discovery upside is high for near-zero cost.

**Alternatives considered**: *No feed* — rejected: leaves a cheap, conventional AEO/aggregator discovery signal unused. *A dynamic feed endpoint* — rejected: no backend (P-IV); a static build-time `feed.xml` fits the architecture.
