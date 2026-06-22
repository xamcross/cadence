# Quickstart: SEO & AEO Discoverability

How to build, run, and verify the feature end-to-end (frontend-only; no backend change).

## Build & serve

```bash
cd frontend
ng build --configuration production
# Built artifacts land in dist/cadence/browser/ — verify the static SEO files:
ls dist/cadence/browser/robots.txt dist/cadence/browser/sitemap.xml dist/cadence/browser/llms.txt dist/cadence/browser/assets/og-cadence.png
```

Local dev: `ng serve`, then open `http://localhost:4200/` (public home) and `http://localhost:4200/status?token=demo` (token page — must be `noindex`).

## Verify — User Story 1 (public discoverability)

1. `GET /` → home page renders product content, a sign-in CTA, an H1.
2. View source of `/` (no JS) → `<title>`, `<meta name="description">`, `<link rel="canonical">`, OG tags, and 4 JSON-LD blocks are present in the raw HTML (SC-005).
3. `GET /robots.txt` → deny-by-default body + `Sitemap:` line (origin substituted).
4. `GET /sitemap.xml` → exactly the home URL; well-formed XML.
5. Paste `/` into a link-preview tool → title/description/image render (SC-006).

## Verify — User Story 2 (private pages never indexed)

1. Open `/status?token=demo`, `/schedule?token=demo`, `/booking`, `/confirm`, `/feedback`, `/app`, `/admin/dashboard` → each has `<meta name="robots" content="noindex,nofollow">` and **no** canonical.
2. `robots.txt` disallows all of them (only `/$` + assets allowed).
3. Search the built `sitemap.xml`, `llms.txt`, and `index.html` JSON-LD for `token`, `/status`, `/admin`, `?` → **zero** matches (SC-002).
4. Navigate from `/` to `/status?token=SENTINEL` → confirm no DOM meta/canonical/OG contains `SENTINEL` (no token leak).
5. Navigate away from a token page → no `Referer` header carries the token (existing `Referrer-Policy: no-referrer` intact — SC-010).

## Verify — User Story 3 (AEO)

1. `GET /llms.txt` → accurate product summary; `## Links` lists only the home URL.
2. Run the JSON-LD on `/` through a structured-data validator → 0 errors (SC-004), 4 types present.
3. Confirm none of llms.txt / JSON-LD references a private or token URL.

## Verify — User Story 4 (environment control)

1. Build with the non-production flag → `robots.txt` is `Disallow: /`, `_headers` adds `X-Robots-Tag: noindex`, every route emits `noindex` (SC-008).
2. Build production → `/` is `index,follow`; everything else `noindex`.

## Automated checks

```bash
cd frontend
ng test --watch=false            # SeoService, route-seo-inventory, home a11y (axe), no-token assertions
npx @lhci/cli autorun --config=../lighthouserc.json   # home route Performance >= 85
ng build --configuration production && \
  node ../scripts/seo-artifact-scan.mjs dist/cadence/browser   # (or the CI step) — no token/auth URL in any artifact
```

CI (`.github/workflows/ci.yml`) runs the SEO artifact scan over `dist/` and fails the build on any token/auth URL in `robots.txt`/`sitemap.xml`/`llms.txt`/`index.html`, and on a missing required directive (the F13/F22 sentinel-scan precedent).

## Definition-of-Done checklist (this feature)

- [ ] Home page renders + is the indexable `/`; shell relocated to `/app`; login lands on `/app`.
- [ ] `robots.txt` / `sitemap.xml` / `llms.txt` built to the served root with origin substituted.
- [ ] JSON-LD (4 types) + OG/Twitter + canonical + description static in `index.html`.
- [ ] Every token/auth route is `noindex,nofollow` (deny-by-default, inventory test green).
- [ ] No token/PII in any artifact (CI scan green).
- [ ] Home WCAG 2.2 AA (axe 0 violations) + Lighthouse >= 85.
- [ ] `Referrer-Policy: no-referrer` + CSP + header contract unchanged (SC-010).
- [ ] Multi-role review (Security, Frontend, QA) completed; findings applied/reported.
