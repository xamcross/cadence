# Contract: Generated Page Output

What every emitted page MUST contain. Asserted by generator unit tests + the CI artifact scan.

## Every article page (`/resources/<slug>`)

| Requirement | Source / value |
|---|---|
| `<!doctype html>` + `<html lang="en">` | static (FR-015) |
| Exactly one `<h1>` | = `meta.title` (FR-009) |
| Lead paragraph | = `meta.summary` directly under `<h1>` (FR-009) |
| `<title>` + `<meta name="description">` | title / summary |
| `<meta name="robots" content="__CADENCE_ROBOTS__">` | placeholder → `index,follow` (prod) / `noindex,nofollow` (non-prod) via `seo-inject-origin.mjs` (FR-010/SC-008) |
| On-disk path | `dist/resources/<slug>/index.html` (directory-index form — Research D1; serves cleanly at `/resources/<slug>`) |
| `<link rel="canonical" href="https://__CADENCE_PUBLIC_ORIGIN__/resources/<slug>">` | self-referential, no query/token, matches the served URL (FR-018) |
| `<link rel="alternate" hreflang="en" href="self">` + `hreflang="x-default"` | retrofit-avoidance for future locales (Research D6) |
| Open Graph + Twitter tags (**MUST**, per article) | title/description/url/image (image falls back to `og-cadence.png`) — share/LLM-unfurl discovery (Research D6) |
| Breadcrumb nav (visible) | Home → Resources → Title, with real `<a href>` (FR-017, two-hop) |
| Link to home `/` and to >= 1 **topically related** (same-theme) article | reciprocal back-link auto-completed by the generator (FR-006/SC-009/Research D9) |
| Published date (and last-updated when set) rendered **in the body HTML** | not only in JSON-LD (FR-014/SC-013) |
| JSON-LD `BlogPosting`/`Article` | headline, description, datePublished, dateModified?, `mainEntityOfPage`=canonical, `publisher`+`author` = `{"@id":"…/#organization"}` (the shared Organization node, Research D6) (FR-008/SC-004) |
| JSON-LD `BreadcrumbList` | Home → Resources → Article (FR-008/SC-004) |
| JSON-LD per-article `FAQPage` (distinct from the home FAQ) or `HowTo` + `speakable` | AEO extractability (Research D6, US2) |
| FAQ anti-duplication | article headline / article-FAQ questions MUST NOT near-duplicate the `index.html` home-FAQ set — build fails otherwise (FR-021/Research D6) |
| No person PII anywhere | publisher is the Organization; no name/email/phone (Security SHOULD-FIX, FR-011) |
| Full body readable with JS disabled | static file, no client render (FR-005/SC-003) |

## Library index page (`/resources`)

| Requirement | Value |
|---|---|
| `<html lang>`, single `<h1>` (e.g. "Resources") | (FR-015) |
| One card per published article: linked title + summary + date | (FR-003) |
| Link back to home `/` | (FR-017) |
| Self-canonical `/resources` | (FR-018) |
| `<meta name="robots">` placeholder | indexable only when >= 1 article (edge case) |
| `<link rel="alternate" type="application/atom+xml" href="/resources/feed.xml">` | feed discovery (Research D10) |
| JSON-LD `CollectionPage` + `ItemList` of articles | (FR-008/SC-004) |

## Shared Organization node (`index.html`, edited)

The existing `Organization` JSON-LD in `index.html` gains `"@id": "https://__CADENCE_PUBLIC_ORIGIN__/#organization"` so every article's `publisher`/`author` can reference one identity (Research D6). The `WebSite` node is aligned to the same publisher `@id`.
