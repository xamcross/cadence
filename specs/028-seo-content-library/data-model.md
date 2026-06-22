# Phase 1 Data Model: SEO/AEO Content Article Library

**No database.** There is no MongoDB collection, no Mongock changeset, and no runtime entity. The "entities" below are the **build-time content model** — first-party source files in the repo and the in-memory shapes the generator works with. Validation rules are enforced by the generator at build time and fail the build on violation.

---

## Entity: Article

The unit of public, educational/marketing content. Source = one directory under `frontend/src/content/articles/<slug>/` containing `meta.json` + `body.html`.

### `meta.json` fields

| Field | Type | Required | Validation |
|---|---|---|---|
| `slug` | string | yes | Lowercase kebab-case `^[a-z0-9]+(-[a-z0-9]+)*$`; MUST match the directory name; MUST be unique across all articles (collision → build fails, FR-016/SC-012); becomes the URL `/resources/<slug>`. |
| `title` | string | yes | Non-empty; unique across articles (FR-021); used as the page `<title>`, the single `<h1>`, og/twitter title, and the JSON-LD `headline`. |
| `summary` | string | yes | Non-empty; <= ~60 words (FR-009 lead bound); used as `<meta name="description">`, the library-index card text, the JSON-LD `description`, and rendered as the article lead paragraph; MUST be unique enough not to near-duplicate another article (FR-021). |
| `datePublished` | string (ISO-8601 date) | yes | Valid `YYYY-MM-DD`; rendered to readers (FR-014); JSON-LD `datePublished`; sitemap `lastmod` fallback. |
| `dateUpdated` | string (ISO-8601 date) | no | Valid `YYYY-MM-DD` >= `datePublished` when present; rendered as "last updated" (FR-014); JSON-LD `dateModified`; preferred sitemap `lastmod`. |
| `theme` | string (enum) | yes | One of the known themes (see **Theme** below); groups the article and drives related-article suggestions. |
| `related` | string[] | no | Slugs of other articles; MUST resolve to existing slugs; used for the "related articles" links. If empty/absent, the generator auto-selects >=1 other article sharing the `theme` (FR-006/SC-009 requires >=1 topically related link). Related links are **reciprocal**: if A lists B, the generator auto-completes B→A. The single-article-in-a-theme degenerate case emits no self-link and no zero-related page. |

### `body.html` rules

- A **safe HTML fragment** (no `<html>/<head>/<body>` wrapper — the generator supplies the page chrome).
- Allowed structure: headings (`<h2>`-`<h4>`; the page `<h1>` is generated from `title`, so the body MUST NOT contain an `<h1>`), `<p>`, `<ul>/<ol>/<li>`, `<a href>`, `<strong>/<em>`, `<blockquote>`, `<code>/<pre>`, `<figure>/<figcaption>`, `<img src alt>`.
- **Rejected at build** (safety lint, defense-in-depth, FR-011/FR-020): `<script>`, `<iframe>`, `<object>`, `<embed>`, `<style>`, any `on*=` event-handler attribute, any `javascript:`/`data:` URL, and any `<a href>` or `<img src>` pointing at a non-public/authenticated/token route (must be relative within the public site or an absolute `http(s)` public URL).
- MUST contain no candidate token, email, phone, or other personal data (FR-011/SC-005 — asserted by the artifact scan).

### State / lifecycle

- **Published**: a source directory exists with valid `meta.json` + `body.html` → the generator emits the page and includes it in the index, sitemap, and `llms.txt`.
- **Retired**: the source directory is removed (or moved out of `content/articles/`) → on the next build the page is not emitted and the URL 404s (served by the SPA `**` NotFound, itself noindex), and it disappears from the index/sitemap/`llms.txt` together (FR-012/SC-007). No redirect to home/another article.
- **Draft** (optional, NICE-TO-HAVE): a `meta.json` `draft: true` flag may exclude an article from emission; not required for MVP.

---

## Entity: Article Library Index

The collection hub at `/resources`. Not authored — fully derived by the generator from the set of published Articles.

| Aspect | Rule |
|---|---|
| URL | `/resources` (clean URL; the generated file is `dist/resources/index.html`). |
| Content | One card per published article: title (link to `/resources/<slug>`), summary, published date. Sorted by `dateUpdated ?? datePublished` descending. |
| Links | Links to every published article (FR-003) + a link back to the home `/` (FR-017). |
| Structured data | `CollectionPage` containing an `ItemList` of the articles (FR-008/SC-004). |
| Indexability | Indexable only when >= 1 article is published (avoids a thin indexable empty page — spec edge case); otherwise reachable but `noindex`. |
| Canonical | Self-referential `/resources` (FR-018). |

---

## Entity: Theme

A controlled vocabulary grouping articles and driving related-article cross-links. Not a separate file — an enum the generator knows and `meta.json.theme` must match.

| Theme key | Label |
|---|---|
| `no-shows` | Reducing no-shows |
| `candidate-experience` | Candidate experience |
| `scheduling` | Interview scheduling & calendar coordination |
| `privacy` | Privacy-safe / GDPR-conscious recruiting |

- Adding a theme = adding an enum entry in the generator (and using it in a `meta.json`). The set is intentionally small and on-product (D9).
- Used to: (a) validate `meta.json.theme`; (b) auto-pick a related article when `related` is empty (FR-006).

---

## Derived build artifacts (generator outputs, not authored)

| Artifact | Built from | Key rules |
|---|---|---|
| `dist/resources/<slug>/index.html` | Article source | **Directory-index form** (Cloudflare Pages serves it at the **trailing-slash** URL `/resources/<slug>/` and 308-redirects the no-slash form; canonical/sitemap/links use the trailing-slash form to match — verified post-deploy). Full page: `<html lang>`, single `<h1>`=title, lead=summary, body fragment, breadcrumb nav, reciprocal same-theme related + home links, self-canonical, hreflang, `__CADENCE_ROBOTS__` placeholder, Article + BreadcrumbList (+ FAQPage/HowTo + speakable) JSON-LD with shared Organization `@id`, per-article og/twitter tags. |
| `dist/resources/index.html` | All published articles | Library index (see entity above) + Atom feed link. |
| `dist/resources/feed.xml` | All published articles | Atom feed, one entry per article (Research D10). |
| `dist/sitemap.xml` | Allow-list `{/, /resources, /resources/<slug>...}` | Each `<url>` has `<loc>` (origin placeholder) + `<lastmod>`; built from the `content/articles/` directory scan, NEVER from the route table or a `dist/` page scan (FR-007/SC-010). |
| `dist/llms.txt` | All published article URLs | Lists each article URL (FR-013/SC-011). |
| `frontend/src/index.html` (edited, not generated) | — | `Organization` node gains `@id` for the shared graph (Research D6). |
| `frontend/src/robots.txt` (edited, not generated) | — | Scoped `Allow: /resources/` only (FR-019). |

**Source of truth**: the published-article set is the **directory scan of `frontend/src/content/articles/*/`** (dirs with valid `meta.json` + `body.html`). There is no separate manifest file. This is the content allow-list — distinct from the prohibited route/page scan (Research D3).
