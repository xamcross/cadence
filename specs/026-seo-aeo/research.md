# Phase 0 Research: SEO & AEO Discoverability

All Technical Context unknowns are resolved below. The spec carried one open assumption ("build-time pre-rendering of public routes"); **D1 refines that assumption** to a no-SSR static-shell approach and records the rationale.

---

## D1 — Crawlable public content WITHOUT server-side rendering (the central decision)

**Decision**: Do **not** add `@angular/ssr` / prerendering. Author the public home page's primary descriptive content and all machine-readable metadata **statically in `index.html`**, served as-is by Cloudflare Pages. Apply per-route runtime behavior via Angular's `Meta`/`Title` services for JS-executing crawlers. `robots.txt` is the primary crawl barrier.

**Rationale**:
- **Topology (Principle IV)**: Production is a static SPA on Cloudflare Pages with no Node server at runtime. SSR would need a server process; prerendering (SSG) avoids that but still requires `@angular/ssr` + `@angular/platform-server` as build dependencies.
- **Dependency posture (Principle X + Dependency Policy + project history)**: `@angular/ssr` is **not installed** (confirmed: `frontend/node_modules/@angular/` has no `ssr`/`platform-server`). Adding it triggers a network package fetch and introduces a substantial new build mode. Every prior feature (F00–F51) ships "no new frontend runtime dependency"; the user's standing instruction is "stop and report if a tool is missing, never download". Avoiding SSR keeps C4/C7 unambiguously PASS.
- **Sufficiency**: The only page that needs no-JS-readable content is the **single public home page**. Its descriptive prose + headings can live as static HTML inside `<app-root>` (replaced on Angular bootstrap, but present in the served bytes), and its machine-readable facts (meta description, canonical, OG/Twitter, JSON-LD) live in `<head>`. This satisfies FR-017/SC-005 for the public surface. Token/auth pages do **not** need no-JS content — they are `noindex` and robots-disallowed.

**Alternatives considered**:
- **`@angular/ssr` prerendering (SSG)**: the SOTA "ideal" for SPA SEO. Rejected: new build dependency + fetch (Principle X), prerendering guarded/auth routes is fiddly (guards run during prerender and redirect), and per-route static HTML is unnecessary when only one page must be no-JS-readable. Documented as the future upgrade path if a multi-page marketing site is ever in scope.
- **Hand-authored non-Angular static home served at `/`**: rejected — splits the app into two rendering models and complicates the SPA fallback; "1x Angular SPA" (Principle IV) intent.

**Consequence for the spec assumption**: the "build-time pre-rendering" assumption is replaced by "static descriptive content + metadata authored at build into `index.html`". Still build-time-static in spirit; no per-request rendering. SC-005 is met for the public home page.

---

## D2 — Single-document SPA: per-route `noindex` and `robots.txt` deny-by-default

**Decision**: Two independent, layered controls.
1. **`robots.txt` (primary, pre-fetch barrier)** — deny-by-default via end-anchored allow of the root + a **narrowed** render-asset set, then `Disallow: /`:
   ```
   User-agent: *
   Allow: /$
   Allow: /favicon.ico
   Allow: /assets/
   Allow: /*.js$
   Allow: /*.css$
   Allow: /*.woff2$
   Disallow: /
   Sitemap: https://<prod-origin>/sitemap.xml
   ```
   Any route except `/` (and static assets needed to render `/`) is disallowed — so **a future token/auth route is protected with no further change** (FR-004/SC-009). **Allow-list narrowed after Security review B1**: broad `/*.png$`/`/*.svg$`/`/*.ico$` were removed because longest-match-wins would let them out-match `Disallow: /` for any private path ending in that extension; the retained `.js`/`.css`/`.woff2` are safe because Angular routes are extension-less (only root bundles carry those extensions). CI (T029) asserts `/status` is matched by no `Allow:`.
2. **Page-level runtime `noindex,nofollow` (defense-in-depth, for JS-executing crawlers)** — `SeoService` sets `<meta name="robots" content="noindex,nofollow">` on every route whose `data.seo.index !== true`. Default is non-indexable; only `/` (and explicitly-marked routes) flip to `index,follow`.

**Rationale**: A pure SPA serves one `index.html` for all routes, so the no-JS markup cannot vary per route. `robots.txt` handles compliant crawlers (incl. the major answer-engine bots) before they fetch; the runtime meta handles JS-executing crawlers (Googlebot) that render the app.

**Static robots baseline decision**: the static `index.html` carries `<meta name="robots" content="__CADENCE_ROBOTS__">` substituted to **`index,follow`** in production (and `noindex,nofollow` in non-prod) + a static canonical `{origin}/`. `SeoService` then sets `index,follow` on `/` and `noindex,nofollow` elsewhere at runtime (for JS crawlers). Keeping the prod baseline `index,follow` keeps the **one page we want indexed** (`/`) indexable for no-JS crawlers too — without SSR the static bytes cannot differ per route, so a per-route static meta is impossible.

**Per-path `X-Robots-Tag` closes the no-JS gap (loop-2 audit, FR-008/SC-003)**: because the same `index.html` is SPA-fallback-served at a token URL, a crawler that ignores `robots.txt` AND runs no JS would otherwise read the home's `index,follow` at `/status?token=…`. **Cloudflare Pages `_headers` supports per-path rules statically** (no server — the earlier "needs a server, Principle IV" rejection was wrong), so `frontend/src/_headers` now emits `X-Robots-Tag: noindex, nofollow` for every token/auth route prefix (home + assets excluded → still indexable). This is the independent, page-level, all-crawler-class second layer FR-008/SC-003 require. Layers now: (a) `robots.txt Disallow: /` (compliant crawlers), (b) per-path `X-Robots-Tag: noindex` header (ALL crawlers, no JS needed), (c) runtime `noindex` meta (JS crawlers), (d) canonical `{origin}/`. No token/PII is ever in the served bytes regardless.

**Alternatives**: a server-emitted `X-Robots-Tag` per path (rejected — needs a server, Principle IV). Used only for the **non-prod blanket** case (D6) where it is a single static header rule.

---

## D3 — Canonical, hreflang, and duplicate-URL handling

**Decision**: `SeoService` writes a single self-referential `<link rel="canonical">` per indexable route, built from the injected public origin + the route path in its preferred form (no trailing slash, no query string). Token pages emit **no** canonical (FR-006) — a canonical that echoed the URL would embed the token. Language is declared via the static `<html lang="en">` (already present); an `hreflang` hook is provided in `RouteSeo` for future locales but only `en` is emitted in MVP.

**Rationale**: The app is localization-ready (single active locale). One canonical form prevents trailing-slash/query duplicate dilution. Stripping the query string from the canonical is also the mechanism that guarantees no token leaks into canonical (D2/C3).

---

## D4 — Structured data (AEO) shape

**Decision**: Static JSON-LD blocks in `index.html` `<head>` (present without JS):
- **Organization** — name, url, logo (absolute), sameAs (optional socials).
- **SoftwareApplication** — name "Cadence", applicationCategory "BusinessApplication", operatingSystem "Web", a plain-English description, and an `offers` stub.
- **WebSite** — name + url (no `SearchAction`: the public site has no search box, so a SearchAction would be a false claim).
- **FAQPage** — 3–5 product Q&As ("What is Cadence?", "Do candidates need an account?", "Which calendars/ATS are supported?", "Is candidate data GDPR-safe?"). Q&A pairs are the highest-leverage AEO primitive — directly answer-engine-extractable.

All `url`/`logo` values use the public origin only; **no token, no private path, no PII** (FR-014). Validates against schema.org / Rich Results expectations with zero errors (SC-004).

**Rationale**: Organization + SoftwareApplication establish entity identity; FAQPage maximizes answer-engine citability. Keeping them static (not JS-injected) means non-rendering answer-engine crawlers still read them.

**Alternatives**: per-route JSON-LD injected by SeoService (deferred — only the home page needs rich structured data in MVP; global blocks suffice).

---

## D5 — `llms.txt` for answer engines

**Decision**: A root `/llms.txt` (Markdown) following the emerging convention: `# Cadence`, a one-line blockquote summary, a short "what it is / who it's for" section, and a `## Links` list pointing to **public pages only** (home, and public docs if any). Served as a static asset. **No** token/admin/candidate URLs (FR-016). The conventional location is the site root (`/llms.txt`); a copy is not duplicated under `/.well-known/` for MVP (root is the de-facto standard answer engines probe).

**Rationale**: `llms.txt` is the current SOTA AEO guidance-file convention. Keeping it root-level + public-links-only satisfies FR-015/FR-016 and the SC-002/SC-007 scans.

---

## D6 — Environment control (production-indexable vs everywhere-else-discouraged)

**Decision**: Build-time switch with **explicit production opt-in** (Security review N2): index only when `CADENCE_PUBLIC_ENV === 'production'`; **every other value (preview, unset, misconfigured) defaults to blanket noindex** (deny-by-default — the safe state is the default, indexing is opt-in). The injection step (`scripts/seo-inject-origin.mjs`) runs **on the built `dist/` output after `ng build`** (the artifacts only exist post-build) and is wired into the `lighthouse`/`deploy` CI jobs and `scripts/deploy-frontend.ps1` (Frontend review B3 — the script was previously defined but invoked nowhere). For a **non-production** build (preview branch / `*.pages.dev` / unset):
- `robots.txt` is replaced by an all-disallow body (`User-agent: *` / `Disallow: /`).
- `_headers` adds `X-Robots-Tag: noindex` for all paths.
- `index.html`'s default robots meta is forced to `noindex` (SeoService also forced to never emit `index`).

Production keeps the D2 deny-by-default robots + per-route index/noindex. The switch is a single build-time flag (e.g. an env var read by the injection step) — **no per-request logic** (FR-021).

**Rationale**: Cloudflare Pages preview deployments get public `*.pages.dev` URLs that Google can index; a blanket non-prod `noindex` + disallow prevents staging from competing with or leaking ahead of production (FR-020/SC-008). All decided at build/deploy time, consistent with Principle IV.

---

## D7 — Routing relocation (`/` becomes public; shell moves to `/app`)

**Decision**: `app.routes.ts` gains `{ path: '', loadComponent: HomeComponent }` (no guard) as the public home; the existing guarded shell moves to `{ path: 'app', canActivate: [authGuard], ... }`; and a **new wildcard `{ path: '**', loadComponent: NotFoundComponent }`** with `seo: PRIVATE` (Frontend review B2). The wildcard is required: once `''` is the public home, every unknown/typo URL would otherwise be served as the indexable home (a soft-404 SEO + UX trap) — the `NotFound` route returns a lightweight `noindex` page instead.

`HomeComponent` renders marketing **immediately for anonymous** visitors (never blocking paint on `me()` — the common crawler/visitor case); it fires `me()` in the background and redirects a signed-in member to `/app` on success. It must NOT let the `me()` 401 trigger the auth-interceptor's `navigate(['/login'])` (Security S3 / Frontend S3) — that would bounce every anonymous crawler off `/` and defeat root indexing.

**All five hardcoded `'/'` navigations are retargeted** (Security S2, Frontend B1, QA SF-4): `login.component.ts:74`, `accept-invite.component.ts:85`, `workspace-setup-wizard.component.ts:123`+`:124` → `['/app']`; `not-authorized.component.ts:19` `routerLink="/"` → `/login`. A grep-guard test asserts no post-auth `navigate(['/'])` remains. The shell's own `navigate(['/workspace/setup'])` + `logout → /login` are absolute and unaffected.

**Rationale**: Hosting marketing at the **root** maximizes domain SEO authority and matches the "canonical discoverable entry point" requirement (FR-022). The change is small and fully covered by acceptance tests (home renders anon, redirects signed-in, login lands on `/app`). The auth-aware redirect avoids a guard on `/` (which would bounce anonymous crawlers to `/login` and defeat indexing).

**Alternatives**: keep shell at `/`, host marketing at `/home` (rejected — the bare domain would redirect crawlers to `/login`, wasting root authority; canonical-at-subpath is weaker SEO).

---

## D8 — Static-file delivery on Cloudflare Pages + absolute-origin injection

**Decision**: `robots.txt`, `sitemap.xml`, `llms.txt`, and `og-cadence.png` are added to `frontend/src/` and emitted to the served root via `angular.json` `assets` globs (the exact mechanism already used for `_headers`). Absolute-origin placeholders (`__CADENCE_PUBLIC_ORIGIN__`) in `robots.txt`/`sitemap.xml`/`llms.txt`/`index.html` are substituted at Cloudflare build time by a tiny Node step, mirroring the documented `CADENCE_API_URL` `JSON.stringify` injection (and using the same safe-substitution discipline). If a `.ps1` helper is added for local builds it MUST be pure ASCII + CRLF (Principle V).

**Rationale**: Reuses a proven, in-repo pattern; no new tooling (Principle X). Keeps the production origin out of source (it is environment config, like the API URL).

---

## D9 — Testing & CI strategy

**Decision**:
- **Jasmine unit**: `SeoService` (sets title/description/canonical/robots/OG from route data; defaults to `noindex`; never emits a query/token into canonical); `route-seo-inventory.spec` (every route in `app.routes.ts` is either explicitly `index:true` **or** inherits the `noindex` default — proves FR-004/SC-009 by adding a synthetic route and asserting it is non-indexable); `home.component.spec` (renders marketing content, sign-in CTA, axe 0 WCAG 2.2 AA violations, redirects an authenticated member to `/app`).
- **CI (bash, Ubuntu) — new "SEO artifact scan" step over the built `dist/`**: assert (a) `robots.txt` contains the deny-by-default `Disallow: /` + a `Sitemap:` line and disallows the known token/auth prefixes; (b) `sitemap.xml` contains the home URL and **zero** of `/schedule|/booking|/confirm|/status|/feedback|/admin|/pipeline|/scheduling|/calendar|/workspace|/interview-templates|/email-templates|?token=`; (c) `llms.txt` + the `index.html` JSON-LD contain none of those prefixes; (d) `index.html` has a non-empty `<meta name="description">`, a `canonical`, OG tags, and the four JSON-LD `@type`s. The scan FAILS the build on any token/auth URL in an artifact (SC-002).
- **Lighthouse**: add the `/` home route to the F14 LHCI config; Performance >= 85, accessibility warn (axe authoritative).

**Rationale**: Mirrors the established F14 axe/LHCI harness and the F13/F22 CI-sentinel-scan precedent. The inventory test makes deny-by-default a build-enforced invariant rather than a convention.

---

## Resolved unknowns summary

| Unknown | Resolution |
|---|---|
| SSR vs static for crawlability | **Static shell, no `@angular/ssr`** (D1) |
| Per-route noindex in a single-doc SPA | robots.txt deny-by-default + runtime meta (D2) |
| Canonical / token-leak avoidance | self-referential canonical sans query; none on token pages (D3) |
| Structured data shape | Organization + SoftwareApplication + WebSite + FAQPage, static (D4) |
| AEO guidance file | root `/llms.txt`, public links only (D5) |
| Non-prod indexing | build-time blanket noindex + disallow (D6) |
| Public entry routing | `/` = public home, shell → `/app` (D7) |
| Static delivery + origin | `angular.json` asset globs + build-time origin injection (D8) |
| Tests/CI | Jasmine + axe/LHCI + CI artifact scan (D9) |
