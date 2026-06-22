# Quickstart: SEO/AEO Content Article Library

This feature is **frontend/static + build-tooling only** — no backend, no database, no new dependency.

## Add a new article

1. Create `frontend/src/content/articles/<slug>/` (the directory name IS the URL slug, e.g. `reducing-interview-no-shows`).
2. Add `meta.json` (see `contracts/article-source.contract.md`):
   ```json
   {
     "slug": "reducing-interview-no-shows",
     "title": "How to reduce interview no-shows",
     "summary": "A short, direct answer (<= ~60 words) that leads the article.",
     "datePublished": "2026-06-22",
     "theme": "no-shows",
     "related": []
   }
   ```
3. Add `body.html` — a safe HTML fragment (no `<h1>`, no `<script>/<iframe>/on*=`, links to public pages only). Headings start at `<h2>`.
4. Build (below). The article appears automatically at `/resources/<slug>`, in the `/resources` index, in `sitemap.xml`, and in `llms.txt`. No manual edits to those files (FR-007/FR-013/SC-007).

## Build locally (zero downloads — use the already-installed tools)

```bash
cd frontend
ng build --configuration production            # SPA -> dist/cadence/browser
cd ..
node scripts/seo-build-articles.mjs frontend/dist/cadence/browser   # emit /resources/*, regen sitemap+llms
CADENCE_PUBLIC_ORIGIN=app.example.com CADENCE_PUBLIC_ENV=production \
  node scripts/seo-inject-origin.mjs frontend/dist/cadence/browser  # substitute origin + index,follow
```

Serve the `dist` directory with any static server and open:
- `/resources` — the library index (lists every article)
- `/resources/<slug>` — an article (view source: full body, canonical, JSON-LD all present with JS disabled)
- `/sitemap.xml` — contains `/`, `/resources`, and each `/resources/<slug>` with `<lastmod>`
- `/llms.txt` — lists each article URL
- `/` — the home now links to `/resources` (a plain `<a href>`, full-page navigation)

## Verify (the gates this feature must pass)

```bash
# Pure-lib unit tests + axe WCAG 2.2 AA a11y gate (Jasmine/Karma, existing harness)
cd frontend && ng test --watch=false      # article-build.lib.spec.ts: slug-collision, sitemap allow-list,
                                          # lastmod, llms per-article, safety lint (allow-list links), JSON-LD
                                          # shape + shared Org @id, FAQ-dedup, related auto-select, date-in-body,
                                          # retirement, no-token; + axe.run over the index + >=1 article (0 violations)

# Node end-to-end test of the CLI (fs/scan) — uses the already-installed Node
node scripts/seo-build-articles.node.test.mjs

# Lighthouse on the new pages (real CLI already a devDependency)
npx @lhci/cli autorun --config=../lighthouserc.json   # audits /resources + one article: perf>=85, a11y, seo

# Non-production stays noindex (deny-by-default)
CADENCE_PUBLIC_ORIGIN=app.example.com CADENCE_PUBLIC_ENV=preview \
  node scripts/seo-inject-origin.mjs <fresh-dist-copy>   # robots.txt all-disallow + noindex on every article
```

CI (`.github/workflows/ci.yml`, `lighthouse` job which gates `deploy-frontend`) additionally runs the **artifact scan**: 0 tokens/PII/private routes across `dist/resources/**`, `sitemap.xml`, `llms.txt`, and the JSON-LD; robots `Allow:`-subset; no leftover placeholders (see `contracts/crawl-artifacts.contract.md`).

## Retire an article

Delete its `frontend/src/content/articles/<slug>/` directory. On the next build the page is gone, `/resources/<slug>` 404s (noindex NotFound — no redirect), and it disappears from the index, sitemap, and `llms.txt` together (FR-012/SC-007).

## Guardrails (do NOT)

- Do NOT add `@angular/ssr`, a prerender package, or a Markdown library (`marked`/`markdown-it`) — Research D1/D2, Constitution C4/C7.
- Do NOT widen the robots `Allow:` beyond `/resources/` — Research D4, FR-019.
- Do NOT generate the sitemap from the Angular route table or a page scan — Research D3, FR-007/SC-010.
- Do NOT put any person's name/email/phone in article bodies or JSON-LD — FR-011, Security review.
- Keep any edited `.ps1` pure ASCII + CRLF; scan new `.mjs`/HTML sources for NUL/non-ASCII — Constitution C5 (the F30 lesson).
