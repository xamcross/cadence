# Authoring Cadence marketing pages (seo/audit-improvements)

Commercial static pages (`/features`, `/pricing`, `/integrations/<x>`, `/vs/<x>`) follow the same
pipeline as the F61 articles and the 031 legal pages: first-party source in the repo, prerendered to
static HTML by `scripts/seo-build-articles.mjs` after `ng build`, served by Cloudflare Pages ahead of
the SPA fallback, substituted by `scripts/seo-inject-origin.mjs` at deploy time. They join
`sitemap.xml` and `llms.txt` (a `## Product` section) automatically and are excluded from the Atom
feed.

Each page is one directory `frontend/src/content/pages/<slug>/` containing `meta.json` + `body.html`.
One level of nesting is allowed (`pages/integrations/greenhouse/` -> `/integrations/greenhouse/`),
and a directory may both be a page and hold child pages (the `/integrations/` hub does both).

## `meta.json`

```json
{
  "slug": "integrations/greenhouse",
  "title": "Greenhouse integration for interview scheduling",
  "description": "A compelling meta description, <= 160 characters (enforced by the build).",
  "lastUpdated": "2026-07-17",
  "ogImage": "/assets/og/integrations-greenhouse.png",
  "faq": [{ "q": "A question distinct from the home FAQ", "a": "A concise answer." }]
}
```

- `slug` MUST equal the directory path relative to `pages/` and match
  `^[a-z0-9-]+(/[a-z0-9-]+)?$`. The first segment must not shadow a private/robots-Disallowed route
  or a reserved path (`resources`, `terms`, `privacy`, `assets`, `api`, ...) - the build fails on it.
- `description` is the meta description AND the page lead paragraph; <= 160 characters (enforced).
- `title` becomes the `<h1>` and the `<title>` (with ` | Cadence` appended).
- `ogImage` (optional): `/assets/og/<name>.png`, 1200x630, committed under `frontend/src/assets/og/`
  (regenerate with `scripts/gen-og-images.ps1`); defaults to the brand card.
- `faq` (optional) becomes a `FAQPage` JSON-LD block; questions must not near-duplicate the home FAQ
  in `index.html` (the build fails, same FR-021 gate as articles).

## `body.html`

Same rules as articles (see `../articles/AUTHORING.md`): a safe fragment starting at `<h2>`, links
restricted to the public allow-list, internal links in the trailing-slash form, no scripts/handlers,
no emails or tokens. Schema emitted per page: Organization + BreadcrumbList + WebPage (+ FAQPage when
`faq` is present); never BlogPosting.

Do NOT link `/login` or `/request-access` from marketing bodies - the artifact deny-scans treat
private-route links in static pages as leaks. Point calls-to-action at `/` (the home page carries the
sign-in and request-access actions).
