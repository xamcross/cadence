# Authoring Cadence resource articles (F61 / 028-seo-content-library)

Each article is one directory `frontend/src/content/articles/<slug>/` containing two files. The
directory scan of this folder is the **source of truth** for the library, the sitemap, llms.txt, and
the Atom feed (never a route or `dist/` scan). Add a directory -> it appears at `/resources/<slug>`
on the next build; remove it -> the page 404s and disappears from every artifact together.

## `meta.json`

```json
{
  "slug": "my-article-slug",
  "title": "A unique, human title",
  "summary": "A short lead that answers the article's core question (<= 60 words).",
  "datePublished": "2026-06-22",
  "dateUpdated": "2026-07-01",
  "theme": "no-shows",
  "related": ["another-slug"],
  "faq": [{ "q": "A question distinct from the home FAQ", "a": "A concise answer." }]
}
```

- `slug` MUST equal the directory name and match `^[a-z0-9]+(-[a-z0-9]+)*$`; it must be unique.
- `summary` is required and MUST be <= 60 words (it is the lead paragraph and meta description).
- `datePublished` / `dateUpdated` are `YYYY-MM-DD`; `dateUpdated` (optional) must be >= `datePublished`.
- `theme` is one of: `no-shows`, `candidate-experience`, `scheduling`, `privacy`.
- `related` (optional) lists other slugs; the generator completes reciprocal back-links and, if empty,
  auto-fills from the same theme.
- `faq` (optional) becomes a per-article `FAQPage`. Questions MUST NOT duplicate the home FAQ in
  `index.html` (the build fails on a near-duplicate, FR-021).

## `body.html`

- A safe HTML **fragment** (no `<html>/<head>/<body>` wrapper, no `<h1>` -- the page `<h1>` is the title).
- Start headings at `<h2>`.
- Links/images may only target the public allow-list: `/resources/...`, `/`, an in-page `#anchor`, or an
  external `https://` URL. No `<script>/<iframe>/<style>`, no inline `on*=` handlers, no `javascript:`/
  `data:` URLs, and no candidate token/email (the build fails otherwise -- FR-011/FR-020).

## Build / preview

```bash
cd frontend && ng build --configuration production
node ../scripts/seo-build-articles.mjs dist/cadence/browser
CADENCE_PUBLIC_ORIGIN=app.example.com CADENCE_PUBLIC_ENV=production node ../scripts/seo-inject-origin.mjs dist/cadence/browser
```

Then open `dist/cadence/browser/resources/` to preview.
