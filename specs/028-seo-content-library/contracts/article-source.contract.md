# Contract: Article Source Input

The generator's input contract. A source directory `frontend/src/content/articles/<slug>/` is **valid** iff it satisfies all of the following; otherwise the build **fails loudly** (no partial/silent emission).

## `meta.json` schema (parsed with `JSON.parse`, zero deps)

```json
{
  "slug": "reducing-interview-no-shows",
  "title": "How to reduce interview no-shows",
  "summary": "A short, direct answer (<= ~60 words) that leads the article.",
  "datePublished": "2026-06-22",
  "dateUpdated": "2026-06-22",
  "theme": "no-shows",
  "related": ["interview-scheduling-without-an-account"]
}
```

| Rule | Failure mode |
|---|---|
| `slug` matches `^[a-z0-9]+(-[a-z0-9]+)*$` and equals the directory name | build fails: `invalid_slug` |
| `slug` is unique across all articles | build fails: `duplicate_slug` (FR-016/SC-012) |
| `title` non-empty and unique | build fails: `missing_title` / `duplicate_title` |
| `summary` non-empty and <= 60 words | build fails: `summary_missing` / `summary_too_long` (FR-009) |
| `datePublished` is `YYYY-MM-DD` and parses | build fails: `invalid_date` |
| `dateUpdated` (if present) is `YYYY-MM-DD` and >= `datePublished` | build fails: `invalid_update_date` |
| `theme` is a known Theme key | build fails: `unknown_theme` |
| every `related` slug resolves to an existing article | build fails: `unresolved_related` |
| `body.html` exists and passes the safety lint (below) | build fails: `unsafe_body` |

## `body.html` safety lint (FR-011/FR-020, deny-by-default)

The fragment is **rejected** at build if it contains any of:
- `<script`, `<iframe`, `<object`, `<embed`, `<style`
- any `on[a-z]+=` event-handler attribute
- any `href`/`src` with a `javascript:` or `data:` scheme
- any `<h1>` (the page `<h1>` is generated from `title`)
- **any link/image target NOT on the public allow-list** — every `<a href>`/`<img src>` MUST match either a relative URL under `^/(resources/|$|#)` (another article, the library, the home, or an in-page anchor) **or** a vetted absolute `https://` URL to an external public site. Anything else is rejected. This is an **allow-list, not a blocklist** (Security SHOULD-FIX): the site is deny-by-default and gains new private routes over time, so enumerating private prefixes would silently miss a future one. (Matching is anchored so a public `/resources/scheduling-tips` is NOT confused with the private `/scheduling` route.)

The lint MUST also confirm the fragment contains **no candidate token / email / phone** sentinel patterns (FR-011/SC-005).

## Emit-time escaping (Security NICE-TO-HAVE)

When the generator places `meta.json` values into output, it MUST escape them for the target context: HTML-entity-escape values written into HTML (title, summary, dates), and JSON-string-escape values written into JSON-LD. A stray `"` or `<` in a first-party title/summary must not break the structured data or the markup.
