# Authoring Cadence legal documents (Terms & Privacy)

This directory holds the first-party legal documents that the static build publishes
to `/terms/` and `/privacy/`. Each document is a content source folder that mirrors the
article pattern in `frontend/src/content/articles/<slug>/`:

```
frontend/src/content/legal/
  AUTHORING.md        <- this file
  terms/
    meta.json
    body.html
  privacy/
    meta.json
    body.html
```

The build assembler (`assembleLegalPage` in `article-build.lib.mjs`) wraps the authored
fragment in a complete HTML document (single `<h1>`, canonical, robots, JSON-LD, draft
banner, cross-links). Authors only edit `meta.json` and `body.html`.

## meta.json schema

A single JSON object. All fields are required.

| Field         | Type             | Rules                                                                                         |
|---------------|------------------|-----------------------------------------------------------------------------------------------|
| `slug`        | string           | Exactly `terms` or `privacy`. MUST equal the directory name (the build fails on a mismatch).   |
| `type`        | string (enum)    | `TERMS` or `PRIVACY`. Drives the page title context and the cross-link label.                  |
| `title`       | string           | Non-empty. Becomes `<title>`, the single `<h1>`, and the OG/Twitter title.                     |
| `description` | string           | Non-empty, short. Becomes `<meta name="description">` and the OG description.                  |
| `version`     | string           | Published-revision identifier, e.g. `1.0` or an ISO date. Keep consistent with `lastUpdated`.  |
| `lastUpdated` | string (date)    | ISO `YYYY-MM-DD`. Rendered as a human-readable "last updated" date on the page.                |
| `draft`       | boolean          | `true` renders a prominent "draft pending legal review" banner. Flip to `false` to publish.    |

Example (`privacy/meta.json`):

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

Validation notes:
- Pure ASCII. No curly quotes, em-dashes, or other non-ASCII punctuation in any value
  (Principle V; these files are byte-scanned in CI).
- Valid JSON only (no trailing commas, no comments).

## body.html rules

`body.html` is an HTML fragment (NOT a full document). The assembler supplies
`<!doctype html>`, `<head>`, the single `<h1>`, the draft banner, the date/version line,
and the cross-links. Therefore:

1. **Start at `<h2>`.** Use `<h2>` for the top-level sections and `<h3>` for sub-points.
   NEVER include an `<h1>` (the assembled page owns the one and only `<h1>`).
2. **Allowed markup.** Prose elements only: `<h2>`, `<h3>`, `<p>`, `<ul>`/`<ol>`/`<li>`,
   `<a>`, `<strong>`, `<em>`. NO `<script>`, `<style>`, `<iframe>`, `<object>`, `<embed>`,
   and NO `<h1>` (all rejected by `lintBody`).
3. **No inline event handlers.** No `on*=` attributes (`onclick`, `onload`, ...).
4. **No dangerous URLs.** No `javascript:` or `data:` URLs in any `href`/`src`.
5. **Links: public allow-list only.** Every `href`/`src` MUST be one of:
   - a root-relative path beginning with `/` (e.g. `/privacy`, `/terms`, `/`);
   - a same-page anchor `#section-id`;
   - an absolute `https://host/...` URL.
   No `http://`, no protocol-relative `//host`, no relative `../` paths.
6. **No token or email sentinels.** The fragment MUST NOT contain `token=` anywhere, and
   MUST NOT contain a literal email address (`name@host.tld`). Use a placeholder route or a
   labelled "contact us" phrase instead of an email address.
7. **ASCII punctuation only.** Plain hyphens `-` (never em/en dashes), straight quotes
   `"` and `'` (never curly quotes), `...` for an ellipsis (never the single-glyph form).

## Cross-links (required)

- `terms/body.html` MUST reference and link to the Privacy Notice via a root-relative
  `/privacy` link.
- `privacy/body.html` MUST reference and link to the Terms & Conditions via a root-relative
  `/terms` link.

(The assembler additionally renders cross-links + a home link in the page chrome; the
in-body reference is the human-readable cross-reference within the legal prose.)

## FR-003: the 12 mandatory Privacy Notice sections

`privacy/body.html` MUST contain a clearly-labelled `<h2>` section (heading + prose) for
EACH of the following transparency elements. The content-completeness test (SC-009)
asserts every section is present:

1. Controller identity and contact (plus DPO / EU representative where appointed).
2. Categories of personal data collected (candidate data AND member/user data).
3. Purposes and lawful basis per purpose (including the legitimate-interest description and
   the right to withdraw consent where consent is the basis).
4. Recipients / third parties (the calendar and ATS integrations the operator connects).
5. International transfers and the safeguard relied upon.
6. Retention periods (or the criteria used to set them).
7. Data-subject rights (access, rectification, erasure, restriction, portability, objection)
   and how to exercise them / the contact route.
8. The right to lodge a complaint with a supervisory authority.
9. The existence or absence of automated decision-making / profiling (state that NONE occurs).
10. For indirectly-obtained candidate data: the source and the categories obtained.
11. Whether providing the data is a statutory or contractual requirement and the consequences.
12. Cookies / tracking disclosure (first-party session cookie only; a section, NOT a banner).

`terms/body.html` MUST cover the FR-004 elements: who may use the service and acceptable
use; the operator-to-user relationship; disclaimers and limitations as applicable; and a
reference + link to the Privacy Notice.

## Publishing workflow (placeholder -> counsel-final)

The committed copy is **placeholder/template text** so the pages render and are testable
before legal review. To publish counsel-final copy:

1. Replace the placeholder prose in `body.html` with the reviewed, final wording (keep the
   structure: still starts at `<h2>`, all 12 Privacy sections / FR-004 Terms elements still
   present, same allow-list and ASCII rules).
2. In `meta.json`, set `"draft": false` (removes the draft banner).
3. Bump `"version"` (e.g. `1.0` -> `1.1`) and update `"lastUpdated"` to the publish date.
4. Re-run the build/generate/inject steps and the lib + a11y tests.

There is no version history, archival, or per-workspace variant: a new published revision
overwrites the single current document and bumps `version` + `lastUpdated`.
