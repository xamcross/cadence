# Contract: `robots.txt`

Served at the site root (`https://<origin>/robots.txt`), `Content-Type: text/plain`. Origin-independent body; the `Sitemap:` absolute URL is build-time injected (D8).

## Production body (deny-by-default)

```
# Cadence — only the public home page and the assets needed to render it are crawlable.
# Everything else (auth, app, and per-candidate token pages) is disallowed by default.
User-agent: *
Allow: /$
Allow: /favicon.ico
Allow: /assets/
Allow: /*.js$
Allow: /*.css$
Allow: /*.woff2$
Disallow: /
Sitemap: https://__CADENCE_PUBLIC_ORIGIN__/sitemap.xml
```

> **Allow-list discipline (Security review B1)**: search engines resolve conflicts by **longest-match-wins**, not order. A broad `Allow: /*.png$` (or `.svg$`/`.ico$`) would out-match `Disallow: /` for ANY path ending in that extension (e.g. a hypothetical `/status/x.png`), silently re-opening a private prefix. The allow-list is therefore narrowed to the render-critical set only. The retained `/*.js$`/`/*.css$`/`/*.woff2$` patterns are safe because **Angular SPA routes are extension-less by convention** — real files with those extensions are only the build bundles at the served root; routes (`/status`, `/admin/...`) never end in a file extension and remain caught by `Disallow: /`. The CI scan (T029) asserts a representative private URL (`/status`) is not matched by any `Allow:` line, so a future over-broad allow is caught.

## Non-production body (D6 — blanket discourage)

```
User-agent: *
Disallow: /
```

## Contract assertions (CI scan, SC-003/SC-007)

- MUST contain `Disallow: /`.
- Production MUST contain a `Sitemap:` line with the injected absolute origin (no placeholder token left).
- MUST allow `/$`, `/favicon.ico`, `/assets/`, and the `/*.js$`/`/*.css$`/`/*.woff2$` render-bundle patterns (so Googlebot can render the home page) — and MUST NOT contain a broader `Allow:` than these (CI-asserted, T029).
- The effective directives MUST disallow every token/auth prefix (guaranteed by `Disallow: /` + the narrow allow-list); CI asserts `/status` is not matched by any `Allow:`.
- File MUST be valid UTF-8/ASCII, LF line endings.
