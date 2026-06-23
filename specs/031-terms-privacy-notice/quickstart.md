# Quickstart: Terms & Conditions and Privacy Notice

Frontend + build-time + static-content feature (one content-only backend edit). Worktree: `C:/Users/xamcr/Cadence-terms-privacy` (branch `031-terms-privacy-notice`, based on `030-sota-design-system`). **Zero new dependency.** Use already-installed Node/Angular/Gradle (Principle X — never download tools).

## Author / edit the legal copy

1. Edit content under `frontend/src/content/legal/<slug>/`:
   - `terms/meta.json` + `terms/body.html`
   - `privacy/meta.json` + `privacy/body.html`
   - Keep `body.html` starting at `<h2>` (no `<h1>`), links only on the public allow-list, no token/email text (the `lintBody` gate fails the build otherwise).
   - Privacy body MUST include all 12 sections in `data-model.md` (SC-009).
2. To publish counsel-final wording: set `"draft": false` in `meta.json` (removes the draft banner) and bump `version` + `lastUpdated`.
3. See `frontend/src/content/legal/AUTHORING.md`.

## Build & generate (mirrors the F61 article flow)

```bash
# from frontend/ — produces dist/cadence/browser
ng build --configuration production

# from repo root — emits dist/terms/index.html, dist/privacy/index.html and
# regenerates sitemap.xml + llms.txt (now incl. /terms/ + /privacy/)
node scripts/seo-build-articles.mjs frontend/dist/cadence/browser

# origin + robots placeholder substitution (prod = index,follow; non-prod = noindex)
node scripts/seo-inject-origin.mjs frontend/dist/cadence/browser
```

## Verify (acceptance-aligned)

```bash
# Pure lib unit tests (assembler + sitemap/llms emit + structured-data shape)
node --test frontend/src/app/core/seo/   # (the legal-build tests)

# Angular unit + axe + 44px (footer, candidate-surface links, token-leak, page render)
cd frontend && ng test --watch=false

# Lighthouse on the real legal routes
npx @lhci/cli autorun --config=../lighthouserc.json

# Backend: candidate email templates carry the Privacy link, no token/PII; F21 completeness green
cd backend && ./gradlew test --tests "*EmailTemplate*" --tests "*BuiltInTemplate*"
```

Manual spot-checks:
- Open `dist/.../terms/index.html` and `privacy/index.html` in a browser at 320 px width — no horizontal scroll, single h1, draft banner visible, "last updated"+version visible, cross-links + home link resolve, print preview legible.
- Confirm `dist/sitemap.xml` lists `/terms/` + `/privacy/`; `dist/llms.txt` has the `## Legal` section; `dist/robots.txt` allows them; `dist/_headers` has no `/terms`/`/privacy` noindex line.
- From a token page (e.g. `/status?token=…`), click the Privacy link → lands on `/privacy` with no token in the URL and the original page recoverable.

## Encoding gate (Principle V / F30 lesson)

Before marking done, byte-scan every new/modified source file for non-ASCII / NUL:

```bash
git -C C:/Users/xamcr/Cadence-terms-privacy diff --numstat   # no binary (-/-) rows for text files
# grep new content/lib/Java for non-ASCII; legal prose ASCII or documented UTF-8
```

## CI

The `lighthouse` job (which gates `deploy-frontend`) runs the SEO artifact scan. After implementation it MUST: include `/terms`,`/privacy` in the robots `ok`-set, assert they appear in sitemap+llms with valid non-Article JSON-LD, assert `_headers` has no legal noindex line, and find no token/PII in artifacts. `route-seo-inventory` stays green (no new indexable SPA route).

## Out of scope (do not build)

Acceptance gating / consent records, cookie-consent banner, admin CMS / per-workspace legal docs, version history/archival, translated legal *body* text, any new backend service/collection/dependency.
