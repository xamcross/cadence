# Contract: Legal Pages (static HTML output + content source)

Covers FR-001, FR-002, FR-003, FR-004, FR-005, FR-006, FR-013, FR-014, FR-018, FR-021, FR-022(f), SC-003, SC-007, SC-009, SC-013.

## C-LP-1: Content source schema

For each `slug ∈ {terms, privacy}`, `frontend/src/content/legal/<slug>/` MUST contain:

- `meta.json`:
  ```json
  {
    "slug": "privacy",
    "type": "PRIVACY",
    "title": "Privacy Notice",
    "description": "How Cadence collects, uses, and protects personal data.",
    "version": "1.0",
    "lastUpdated": "2026-06-23",
    "draft": true
  }
  ```
  - `slug` MUST equal the directory name (build fails otherwise).
  - `type ∈ {TERMS, PRIVACY}`; `lastUpdated` is `YYYY-MM-DD`; `draft` is boolean.
- `body.html`: an HTML fragment starting at `<h2>` (no `<h1>`), passing the existing `lintBody` allow-list (no `<script>/<iframe>/<object>/<embed>/<style>/<h1>`, no inline `on*=` handlers, no `javascript:`/`data:` URLs, no `token=`/email sentinels, links only on the public allow-list).

## C-LP-2: Assembled page output (`assembleLegalPage(doc, ctx)` — pure, string-out)

The emitted `<!doctype html>` document for each page MUST:

- Be a complete static HTML document with `<html lang="en">`, `<meta charset="utf-8">`, `<meta name="viewport" …>`, `<meta name="description">`, `<meta name="robots" content="__CADENCE_ROBOTS__">`, `<meta name="referrer" content="no-referrer">`, OG/Twitter tags, favicon — reusing `headCommon(...)`.
- Use `<link rel="canonical" href="<origin>/<slug>/">` — the **trailing-slash** served form.
- Contain exactly one `<h1>` (the document title).
- Render a visible **"last updated" date and version** (FR-005/SC-007).
- When `draft===true`, render a prominent, visually-distinct draft banner ("Draft — pending legal review; not yet binding") above the body (FR-018); omitted when `draft===false`.
- Render `bodyHtml` after the banner/title.
- Render cross-links: to the **other** legal document and to the **home** page (FR-006/SC-007); no dead links. These links MUST carry the `.home-link` class so the `PAGE_STYLE` ≥44 px min-height rule applies (it targets `.related a`/`.home-link`, not bare `<a>`).
- Emit JSON-LD: `Organization` (shared `@id`, inlined), `WebPage`, and `BreadcrumbList` (Home › Title). MUST NOT emit `Article`/`BlogPosting`/`FAQPage` (FR-022(f)). All JSON-LD MUST be valid JSON via `jsonLd(...)`.
- Use the system-font `PAGE_STYLE` (no Fraunces, no third-party asset, `overflow-wrap: anywhere`, `max-width: 44rem`), so it reflows at 320 px and prints legibly (FR-014/SC-003) and adds no external origin (FR-011).
- Contain no candidate token or PII anywhere.

## C-LP-3: Privacy content completeness (FR-003 / SC-009)

`privacy/body.html` MUST include a heading+prose section for each of the 12 elements listed in `data-model.md` §"Privacy Notice required content sections". `terms/body.html` MUST cover the FR-004 elements and reference the Privacy Notice.

## C-LP-4: Serving (FR-021 / SC-013)

The generator writes `dist/terms/index.html` and `dist/privacy/index.html`. A direct request to `/terms` (and `/privacy`) MUST return the legal page (via Cloudflare directory-index, with `/terms` → `/terms/` 308), never the SPA shell or `NotFoundComponent`. The canonical URL MUST equal the served (`/terms/`) form. **Fail-closed**: absence of either `dist/<slug>/index.html` MUST fail the CI build — otherwise the `_redirects` `/* → /index.html 200` catch-all silently serves the noindex `NotFoundComponent` (a 200, not a visible 404); the CI assertion verifies the emitted file contains the legal `<h1>`/canonical, not SPA-shell markup.

## C-LP-5: Tests

- `node:test`: assemble both docs; assert single `<h1>`, canonical trailing-slash, non-Article JSON-LD validity, draft banner toggling on `draft`, cross-links present, no `token=`/email in output.
- Karma + axe: 0 WCAG 2.2 AA violations and ≥44 px link targets on a mock render of each page.
- `@lhci/cli`: `/terms`, `/privacy` audited within budget.
