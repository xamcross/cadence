# Contract: Crawl-Control Artifacts

The generator-owned crawl artifacts. Asserted by generator unit tests + the CI artifact scan (the 026-seo-aeo precedent, extended).

## `sitemap.xml` (generator-owned)

- URL set is **exactly** `{ "/", "/resources", "/resources/<slug>" for each published article }` — built from the article allow-list, **never** a route-table or `dist/`-page scan (FR-007/SC-010).
- 0 private/authenticated/per-candidate token routes present (SC-010) — structurally guaranteed by the allow-list.
- Each `<url>` has a `<loc>` (using `__CADENCE_PUBLIC_ORIGIN__`, substituted at build) and a `<lastmod>` (W3C `YYYY-MM-DD`) = `dateUpdated ?? datePublished` for articles; the home `/` entry keeps `changefreq monthly` + `priority 1.0` and takes `<lastmod>` from the most recent article date (or a build-date arg — the generator never calls `Date.now()` itself).
- Well-formed XML (parses).

## `feed.xml` (generator-owned, Atom — Research D10)

- `/resources/feed.xml`: one `<entry>` per published article (title, link, summary, updated), `__CADENCE_PUBLIC_ORIGIN__` for URLs.
- Referenced by `<link rel="alternate" type="application/atom+xml">` on the library index + home.
- Included in the artifact scan (no token/PII).

## `llms.txt` (generator-owned)

- Lists each published article's full URL (not only a single library link) under a "Resources/Articles" section (FR-013/SC-011).
- Retains the existing product summary; uses `__CADENCE_PUBLIC_ORIGIN__` for URLs.

## `robots.txt` (edited, not generated)

- Keeps `Disallow: /` (deny-by-default) and the existing narrow asset/home allows.
- Adds exactly: `Allow: /resources/` (+ `Allow: /resources$` for the index) — scoped to the library prefix, **no broad pattern** (FR-019/SC-010).
- CI assertion: the `Allow:` set is a subset of `{ "/$", "/favicon.ico", "/assets/", "/*.js$", "/*.css$", "/*.woff2$", "/resources/", "/resources$" }`; no `Allow:` matches any private/token prefix.

## Non-production (`CADENCE_PUBLIC_ENV !== 'production'`)

- `seo-inject-origin.mjs` overwrites `robots.txt` with an all-disallow body, appends global `X-Robots-Tag: noindex` to `_headers` (the `/*` rule covers `/resources/`), and substitutes `noindex,nofollow` into every page's `__CADENCE_ROBOTS__` — including the generated article pages (D5/FR-010/SC-008).

## CI artifact scan (extends the 026 SEO scan)

Over `dist/resources/**/*.html` + `dist/sitemap.xml` + `dist/llms.txt` + the JSON-LD blocks:
1. 0 candidate tokens, 0 authenticated/`PRIVATE` routes, 0 personal data (positive-control sentinel proves the deny-grep is live) — FR-011/SC-005.
2. 0 leftover `__CADENCE_*__` placeholders after injection.
3. robots `Allow:`-subset assertion (catches a re-added broad wildcard).
4. each article URL present in both `sitemap.xml` and `llms.txt`; each sitemap `<url>` has a `<lastmod>`.
5. non-prod injection produces blanket noindex on a fresh dist copy (verified before the prod injection, the 026 two-pass check) — **asserted on an actual `dist/resources/<slug>/index.html`** (`<meta name="robots" content="noindex,nofollow">`), not only on the home `index.html`, AND the non-prod `_headers` ends with the appended `/*` `X-Robots-Tag: noindex` block (Security SHOULD-FIX).
6. `feed.xml` contains an entry for every published article and no token/PII.
