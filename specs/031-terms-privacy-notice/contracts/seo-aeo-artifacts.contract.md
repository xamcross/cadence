# Contract: SEO / AEO Artifacts

Covers FR-015, FR-016, FR-017, FR-021, FR-022, SC-005, SC-008, SC-012. Preserves the deny-by-default posture and the "exactly one indexable SPA route" invariant.

## C-SEO-1: robots.txt (`frontend/src/robots.txt`)

Add, in the allow block (before `Disallow: /`), anchored lines covering both served forms:

```
Allow: /terms$
Allow: /terms/$
Allow: /privacy$
Allow: /privacy/$
```

- MUST be anchored (`$`) so no broader path (e.g. `/terms-of-x`) is freed.
- MUST NOT alter the existing allow lines or the `Disallow: /` / `Sitemap:` lines.

## C-SEO-2: Site-map generator (`article-build.lib.mjs` `buildSitemap` + `buildArtifacts`, CLI `seo-build-articles.mjs`)

- `buildSitemap`/`buildLlms` signatures widen to accept the legal-pages list (e.g. `buildSitemap(articles, legalPages, ctx)`); `buildArtifacts` returns a `legalPages` array; the CLI gains `loadLegalPages(legalDir)` (mirrors `loadArticles`, `CADENCE_LEGAL_DIR` env override) + a `writeFileEnsured(distDir/<slug>/index.html, …)` loop.
- The generator MUST emit `<url>` entries for `<origin>/terms/` and `<origin>/privacy/` (trailing slash), with `<lastmod>` mapped from each doc's **`lastUpdated`** key — do NOT route legal docs through the article `lastmodOf` helper (which reads `datePublished`/`dateUpdated` and would emit `undefined`).
- Entries are produced from the fixed legal-pages list (allow-list emit, never a dist/route scan), consistent with the article precedent.
- The static `frontend/src/sitemap.xml` MUST NOT be hand-edited (it is overwritten in dist by the generator).
- Legal pages are **NOT** emitted into `resources/feed.xml` (`buildFeed`) — that Atom feed is the resources/article feed only.

## C-SEO-3: llms.txt generator (`article-build.lib.mjs` `buildLlms`)

- The generator MUST append a `## Legal` section listing `Terms of Service: <origin>/terms/` and `Privacy Notice: <origin>/privacy/` to the built `llms.txt` (AEO discovery), without disturbing the existing `## Articles` append.

## C-SEO-4: `_headers` (`frontend/src/_headers`)

- MUST contain **no** `X-Robots-Tag` rule whose path matches `/terms` or `/privacy` (they inherit the indexable default).
- The global `/*` CSP + `Referrer-Policy: no-referrer` block is unchanged (preserves FR-011).
- Non-prod build still appends the blanket `/* X-Robots-Tag: noindex` (FR-017/SC-008) via the existing inject step.

## C-SEO-5: Environment gate (`scripts/seo-inject-origin.mjs` — REQUIRES EDIT)

- `seo-inject-origin.mjs` processes a **hardcoded** file set (`index.html`, `sitemap.xml`, `llms.txt`, `robots.txt` at `:47-60`) + a `resources/`-scoped loop (`:71-81`); it does NOT walk arbitrary emitted HTML. It MUST be **extended** with a fixed legal-page loop: for `slug of ['terms','privacy']`, apply the existing origin + robots substitution to `dist/<slug>/index.html` (mirroring the `resources/` loop / `patchOptionalHtml` pattern).
- After the edit: legal pages' `__CADENCE_ROBOTS__` → `index,follow` (prod) / `noindex,nofollow` (non-prod) and `__CADENCE_PUBLIC_ORIGIN__` → real origin (FR-015/FR-017/SC-008). Without the edit they ship literal placeholders (broken canonical, non-functional indexing) — this is the load-bearing fix.

## C-SEO-6: CI guards (`.github/workflows/ci.yml`)

- **Add** (not replace) the 4 anchored legal lines to the existing 8-entry robots `ok` allow-set (`ci.yml:539`, → 12 total), byte-exact: `Allow: /terms$`, `Allow: /terms/$`, `Allow: /privacy$`, `Allow: /privacy/$`. The set is closed and fails the build on any unexpected line, so this addition is mandatory.
- **Add** a step modeled on the per-article scan (`ci.yml:575-625`, NOT the home `index.html` 4-type scan at `:507-527`) asserting `dist/terms/index.html` + `dist/privacy/index.html`: exist (fail-closed if absent — the SPA fallback would otherwise silently serve the noindex NotFound shell), contain the legal `<h1>`/canonical (not SPA-shell markup), appear in `dist/sitemap.xml` + `dist/llms.txt`, emit `WebPage`+`BreadcrumbList`, and contain **no** `BlogPosting`/`Article`/`FAQPage` (FR-022(f)).
- **Add** a token/PII deny-grep over `dist/terms/index.html` + `dist/privacy/index.html` (nothing scans them today) with a positive-control sentinel (the repo's anti-vacuous-scan precedent, `ci.yml:487/:590`).
- **Add** a non-prod verify step asserting a legal page is `noindex` + placeholder-free (mirror `ci.yml:465-468`).
- **Add** a guard that `_headers` contains no `/terms`/`/privacy` `X-Robots-Tag` line.
- The `route-seo-inventory` spec MUST stay green (no new indexable SPA route).

## C-SEO-7: Invariants (SC-005)

- Count of indexable SPA routes unchanged (still exactly `''`).
- Production indexable set = home + `/resources` library/articles + `/terms` + `/privacy`, and nothing else; zero token/authenticated routes indexable.
