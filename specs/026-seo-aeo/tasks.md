---

description: "Task list for 026-seo-aeo — SEO & AEO Discoverability"
---

# Tasks: SEO & AEO Discoverability

**Input**: Design documents from `/specs/026-seo-aeo/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/

**Tests**: INCLUDED — Principle VII (Test-First) applies and the plan/spec request acceptance tests per user story.

**Scope reminder**: Frontend + CI only. **No backend, no MongoDB, no Mongock changeset, no new dependency.** All paths are under `frontend/` except the CI step (`.github/workflows/ci.yml`), `scripts/`, and `CLAUDE.md`.

> **Multi-role review applied (round 1, planning)** — Security + Frontend/Angular + QA sub-agents reviewed spec/plan/tasks against real source. Their blockers/should-fixes are folded into the tasks below (notably: narrowed robots allow-list T014; new wildcard/404 route T008; full `'/'`-navigation retarget T009; wired origin-injection T004; no-JS-content + lang tests T013/T029; canonical-link DOM removal + initial-nav timing T006; anon-first home T011/T012; hardened CI scan T029). A second review loop runs at implementation close (T032).

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: US1 (discoverability), US2 (non-indexing), US3 (AEO), US4 (env control)

---

## Phase 1: Setup (Shared Infrastructure)

- [X] T001 Register the new static SEO files as build assets in `frontend/angular.json` (add globs for `robots.txt`, `sitemap.xml`, `llms.txt` from `src` → output `/`, mirroring the existing `_headers` glob; `src/assets` is already globbed → carries `og-cadence.png`).
- [X] T002 [P] Add the default social-share / OG brand image at `frontend/src/assets/og-cadence.png` — **exactly 1200×630**, < 200 KB, **PII-free** (no candidate screenshots / real workspace data / EXIF-GPS), authored not downloaded (Principle X/C7).
- [X] T003 [P] Create the build-time origin-injection step at `scripts/seo-inject-origin.mjs` (Node; runs **on the built `dist/cadence/browser/` output, after `ng build`**): substitute `__CADENCE_PUBLIC_ORIGIN__` in `robots.txt`/`sitemap.xml`/`llms.txt`/`index.html` from `CADENCE_PUBLIC_ORIGIN`; **non-prod is opt-OUT-of-indexing by default** — index only when `CADENCE_PUBLIC_ENV === 'production'` (explicit allow-list, deny-by-default), otherwise emit the all-disallow `robots.txt` body and force `noindex`. Safe substitution (no HTML/JSON injection from the origin value). If a `.ps1` wrapper is added it MUST be pure ASCII + CRLF (Principle V/C5).
- [X] T004 Wire the injection step into every build pipeline: add `node scripts/seo-inject-origin.mjs dist/cadence/browser` **after** the `ng build` in the `lighthouse` and `deploy` jobs of `.github/workflows/ci.yml` and in `scripts/deploy-frontend.ps1`; add `CADENCE_PUBLIC_ORIGIN` + `CADENCE_PUBLIC_ENV` to the CI job env and the secrets/runbook table (currently absent). Depends on T003.

**Checkpoint**: Asset + injection pipeline ready and actually invoked.

---

## Phase 2: Foundational (Blocking Prerequisites)

**⚠️ CRITICAL**: Blocks all user stories.

- [X] T005 [P] Create the `RouteSeo` interface + `PUBLIC_HOME`/`PRIVATE` presets in `frontend/src/app/core/seo/route-seo.model.ts` per data-model.md §1. Note: content-language (`<html lang>`) is a **static `index.html`** concern, not a `RouteSeo` field (see T013).
- [X] T006 Implement `SeoService` in `frontend/src/app/core/seo/seo.service.ts` per `contracts/seo-service.md`: subscribe to router `NavigationEnd` **and apply to the current URL on `init()`** (do not wait for a `NavigationEnd` that may already have fired for first paint — `provideRouter` default is `enabledNonBlocking`); resolve the deepest activated route's `data.seo`; **deny-by-default `noindex,nofollow`** when `seo?.index !== true`. For indexable routes set `Title`, `<meta name="description">`, `robots=index,follow`, OG/Twitter (via `Meta`), and the canonical. **Canonical is a `<link rel="canonical">` — Angular `Meta` does NOT manage it**: create/update/remove it via direct `document.head` DOM by selector, and on a private route **remove the static canonical element shipped in `index.html`**. Remove OG tags via `Meta.removeTag("property='og:...'")` (note `property=`, not `name=`). Build the canonical from `origin + path` with the query/token **stripped** and no double slash. Expose a non-prod force-`noindex` mode (env-driven). Depends on T005.
- [X] T007 Wire `SeoService.init()` once in `frontend/src/app/app.component.ts` **constructor** (early enough to catch the initial non-blocking navigation). Depends on T006.
- [X] T008 In `frontend/src/app/app.routes.ts`: relocate the authenticated shell from `path: ''` to `path: 'app'` (keep `authGuard`); add a new public `path: ''` → `HomeComponent` (no guard, lazy `loadComponent`); **add a wildcard `{ path: '**' }`** → a lightweight `NotFoundComponent` (lazy) carrying `seo: PRIVATE` (noindex) so unknown/typo URLs are NOT served as the indexable home (a soft-404 SEO + UX trap); attach `data: { seo: ... }` to **every** route (`PUBLIC_HOME` on `''`, `PRIVATE`/omitted everywhere else). Depends on T005.
- [X] T009 Retarget **all five** hardcoded `'/'` navigations found in source so signed-in users land on `/app` and recovery links don't dump users on marketing: `features/auth/login/login.component.ts:74`, `features/auth/accept-invite/accept-invite.component.ts:85`, `features/admin/workspace/workspace-setup-wizard.component.ts:123` **and** `:124` → `navigate(['/app'])`; `shared/not-authorized/not-authorized.component.ts:19` `routerLink="/"` → `/login`. Add a guard test asserting no component issues a post-auth `navigate(['/'])`/`routerLink="/"` to reach the shell. (The shell's own `navigate(['/workspace/setup'])` and `logout → /login` are absolute and unaffected.) Depends on T008.

**Checkpoint**: SEO engine + routing (incl. 404) in place; user stories can proceed.

---

## Phase 3: User Story 1 - Public pages findable & correctly described (Priority: P1) 🎯 MVP

**Independent Test**: Build + serve; `GET /` renders product content; view-source shows title/description/canonical/OG + `<html lang="en">` in raw HTML; `GET /robots.txt` allows `/` + render assets and references the sitemap; `GET /sitemap.xml` lists exactly `/`.

### Tests for User Story 1

- [X] T010 [P] [US1] `SeoService` indexable-route + initial-navigation specs (contract C-1, C-6) in `frontend/src/app/core/seo/seo.service.spec.ts`: navigating to `/` sets `<title>`, non-empty description, `robots=index,follow`, canonical `{origin}/` (no trailing double-slash, no query), OG/Twitter title/description/image/url; assert the meta is applied on the **initial** navigation (not only on a later route change).
- [X] T011 [P] [US1] `HomeComponent` spec in `frontend/src/app/features/home/home.component.spec.ts`: renders `<h1>` + product description + a "Sign in" CTA → `/login`; **anonymous visitor stays on `/` (no navigation to `/login`, marketing rendered — the `me()` 401 path must NOT bounce to login)**; a signed-in member is redirected to `/app`; **axe 0 violations** (WCAG 2.2 AA, body-attach the fixture per `frontend/src/testing/axe.ts`); CTA touch target ≥ 44 px (explicit `getBoundingClientRect`, the F14 lesson).

### Implementation for User Story 1

- [X] T012 [US1] Implement `HomeComponent` at `frontend/src/app/features/home/home.component.ts`: standalone, mobile-first, semantic `<h1>`/`<h2>`, `$localize`-marked strings, sign-in CTA. **Render marketing immediately for anonymous** (never block paint on `me()`); fire `me()` in the background and redirect to `/app` only on success (accept a brief flash for the rare already-signed-in direct visitor); ensure the `me()` 401 does not trigger the auth interceptor's `navigate(['/login'])`. Depends on T008.
- [X] T013 [US1] Edit `frontend/src/index.html`: keep `<html lang="en">` (FR-007/SC-001), the `referrer` meta, and the favicon; add `<title>`, `<meta name="description">`, `<link rel="canonical" href="https://__CADENCE_PUBLIC_ORIGIN__/">`, Open Graph (`og:title/description/image/url/type`) + Twitter card (image = `/assets/og-cadence.png`); add the home page's primary descriptive copy as static markup inside `<app-root>` so a no-JS crawler reads it (FR-017/SC-005). **Do not add a static `<meta name="robots">`** (default = indexable; `SeoService` sets `index,follow` on `/` and `noindex` elsewhere at runtime — see the documented no-JS residual in research D2).
- [X] T014 [P] [US1] Author `frontend/src/robots.txt` per the revised `contracts/robots.txt.md`: **narrowed allow-list** — `Allow: /$`, `Allow: /favicon.ico`, `Allow: /assets/`, `Allow: /*.js$`, `Allow: /*.css$`, `Allow: /*.woff2$` (the render bundles; SPA routes are extension-less so these don't widen route exposure), then `Disallow: /`, then `Sitemap: https://__CADENCE_PUBLIC_ORIGIN__/sitemap.xml`. **Drop the broad `/*.png$`/`/*.svg$`/`/*.ico$` wildcards** (Security B1). LF line endings.
- [X] T015 [P] [US1] Author `frontend/src/sitemap.xml` per `contracts/sitemap.xml.md` (single `<url>` = home loc, placeholder origin; well-formed sitemap 0.9 XML).
- [X] T016 [US1] Add the `/` home route as a Lighthouse target in `lighthouserc.json` (Performance ≥ 85 error gate; accessibility warn — axe authoritative). The F14 `serve-with-stub.mjs` already falls through to `index.html` for `/`; confirm no stub endpoint is needed and the static home copy is the LCP (the OG image is a `<head>` ref, not rendered).

**Checkpoint**: Public home discoverable + correctly described — MVP shippable.

---

## Phase 4: User Story 2 - Private & token-bearing pages never indexed (Priority: P1)

**Independent Test**: each token/auth route → `noindex,nofollow`, no canonical; `robots.txt` disallows them; sitemap/llms/JSON-LD contain none of them; navigating to `/status?token=SENTINEL` leaks `SENTINEL` into no tag; outbound nav sends no token referrer.

### Tests for User Story 2

- [X] T017 [P] [US2] `SeoService` private-route specs (contract C-2, C-3, C-4) in `frontend/src/app/core/seo/seo.service.spec.ts`: a route without `seo.index` emits `noindex,nofollow` + **removes** the canonical `<link>` (assert `document.querySelectorAll('link[rel=canonical]').length === 0`) + description + OG; tags are **replaced** (not appended) on `index→private` navigation; navigating to `/status?token=SENTINEL_SEO_TOKEN` produces no canonical/OG containing the token.
- [X] T018 [P] [US2] Route-SEO inventory spec in `frontend/src/app/core/seo/route-seo-inventory.spec.ts`: iterate the real `app.routes.ts`; assert exactly `''` is `index:true` and every other route (incl. the wildcard `**`) resolves to `noindex` by default; add a synthetic no-`seo` route and assert the engine emits `noindex,nofollow` (deny-by-default proven — FR-004/SC-009).
- [X] T019 [P] [US2] Header/referrer non-regression (SC-010). **Realized in the CI SEO artifact scan (T029), not a Karma spec** — Karma's browser sandbox cannot read `src/_headers`/`src/index.html` via fs, so the check runs over the built `dist/` bytes: it asserts the **exact** CSP directive string, `Referrer-Policy: no-referrer`, `X-Content-Type-Options: nosniff`, the `index.html` no-referrer meta, and that `script-src 'unsafe-inline'` was NOT added — with existence/non-empty non-vacuity guards. (Loop-1 QA finding: the planned `seo-headers.spec.ts` was the wrong layer.) A focused `auth.interceptor.spec.ts` additionally covers the home 401-exemption (FR-022).

### Implementation for User Story 2

- [X] T020 [US2] Confirm/annotate token + auth routes in `frontend/src/app/app.routes.ts` with `seo: PRIVATE` (or intentional omission); verify `robots.txt` deny-by-default covers every token/auth prefix (`/schedule`, `/booking`, `/confirm`, `/status`, `/feedback`, `/app`, `/admin/**`, `/pipeline/**`, `/scheduling`, `/calendar/**`, `/workspace/**`, `/interview-templates`, `/email-templates`, auth-utility routes). The representative-private-URL disallow assertion lives in the CI scan (T029).

**Checkpoint**: Private surface provably non-indexable; both P1 stories complete.

---

## Phase 5: User Story 3 - Answer-engine citability (Priority: P2)

**Independent Test**: JSON-LD on `/` validates 0 errors / 4 types; `GET /llms.txt` returns an accurate summary with public links only; neither references a token/private URL.

### Tests for User Story 3

- [X] T021 [P] [US3] Structured-data assertions — implemented as a **Node/CI check over `dist/cadence/browser/index.html`** (NOT a Karma browser spec, which can't read `src/*.html`): the 4 JSON-LD `@type`s (`Organization`, `SoftwareApplication`, `WebSite`, `FAQPage`) are present, each parses as valid JSON, each carries its required schema.org fields (shape check per `contracts/structured-data.md`), no leftover `__CADENCE_PUBLIC_ORIGIN__`, and no token/admin/candidate URL or PII. Note: full Rich-Results/schema.org validation is a **manual gate** (documented in quickstart) — the automated check is presence + JSON validity + required-field shape (SC-004).
- [X] T022 [P] [US3] `llms.txt` assertions (Node/CI over `dist/.../llms.txt`): single H1, blockquote summary, `## Links` lists only public URLs, contains none of the disallow set, no leftover placeholder (FR-016).

### Implementation for User Story 3

- [X] T023 [US3] Add the 4 JSON-LD `<script type="application/ld+json">` blocks to `frontend/src/index.html` per `contracts/structured-data.md` (absolute placeholder-origin URLs; no `SearchAction`; FAQPage 3–5 accurate Q&As consistent with the home copy — FR-023). **Do not add `'unsafe-inline'` to `script-src`** — JSON-LD is data, not executed script, and renders under the current CSP; confirm so (SC-010).
- [X] T024 [P] [US3] Author `frontend/src/llms.txt` per `contracts/llms.txt.md` (H1 + blockquote + product/audience + `## Links` public-only; placeholder origin).

**Checkpoint**: AEO surface live + validated.

---

## Phase 6: User Story 4 - Environment-level index control (Priority: P3)

**Independent Test**: build non-prod → `robots.txt` is `Disallow: /`, `_headers` adds `X-Robots-Tag: noindex`, every route `noindex`; build prod → `/` `index,follow`, rest `noindex`.

### Tests for User Story 4

- [X] T025 [P] [US4] Env-switch spec in `frontend/src/app/core/seo/seo-env.spec.ts` + an injection-step assertion: when `CADENCE_PUBLIC_ENV !== 'production'`, `SeoService` forces `noindex` regardless of route `seo.index`, and `seo-inject-origin.mjs` emits the all-disallow `robots.txt` + the non-prod `_headers` rule (SC-008).

### Implementation for User Story 4

- [X] T026 [US4] Implement the non-prod path in `scripts/seo-inject-origin.mjs` (T003): **explicit production opt-in** (`CADENCE_PUBLIC_ENV === 'production'`); any other value (preview, missing) → all-disallow `robots.txt` body, neutralized sitemap, forced `noindex` (Security N2 deny-by-default).
- [X] T027 [US4] Add the non-prod `X-Robots-Tag: noindex` rule to `frontend/src/_headers` **additively** via the injection step — production `_headers` MUST stay byte-identical for the CSP + `Referrer-Policy` lines (asserted by T019).
- [X] T028 [US4] Implement the `SeoService` non-prod override (force `noindex` for all routes when the build-time env flag indicates non-production), reading a build-time environment value. Depends on T006.

**Checkpoint**: Staging/preview deployments index-safe.

---

## Phase 7: Polish & Cross-Cutting Concerns

- [X] T029 [P] Add a hardened "SEO artifact scan" step to `.github/workflows/ci.yml` (bash, over built `dist/cadence/browser/`). It MUST: (a) **assert each of `robots.txt`/`sitemap.xml`/`llms.txt`/`index.html` exists and is non-empty before grepping** (non-vacuity guard, the repo precedent); (b) fail on any of `/schedule|/booking|/confirm|/status|/feedback|/admin|/pipeline|/scheduling|/calendar|/workspace|/interview-templates|/email-templates|token=` in any artifact; (c) assert **no leftover `__CADENCE_PUBLIC_ORIGIN__`** in any of the four; (d) assert `robots.txt` has `Disallow: /` + a resolved `Sitemap:` line and that a representative private URL (`/status`) is not matched by any `Allow:`; (e) assert `index.html` has `<html lang=`, a non-empty `<meta name="description">`, a `canonical`, the 4 JSON-LD `@type`s, **and the home H1/description copy present inside `<app-root>`** (SC-005); (f) assert the `og:image` path resolves to a file present in `dist`; (g) parse `sitemap.xml` as well-formed XML; (h) a positive sentinel build: with `/status?token=ZZSENTINELSEOTOKEN` reachable, assert the sentinel appears in NO artifact. (SC-002/003/004/005/006/007).
- [X] T030 [P] Update `CLAUDE.md`: add "Implementation Notes (026-seo-aeo)" (no-SSR rationale + the documented no-JS non-compliant-crawler residual, deny-by-default robots + narrowed allow-list, runtime noindex + canonical-link DOM removal, `/`→`/app` relocation + wildcard 404, no-token-in-artifacts control, origin-injection wiring) + a "Recent Changes" entry.
- [X] T031 Run `quickstart.md` end-to-end validation: `ng build --configuration production` + `node scripts/seo-inject-origin.mjs`, `ng test --watch=false` (all new specs green + **the 218 existing frontend tests still green** — no regression from the routing relocation), `npx @lhci/cli autorun` (home ≥ 85), and the artifact scan locally.
- [X] T032 Multi-role sub-agent review at implementation close (Constitution C6). **Loop 1** (Security/Frontend/QA vs real source): zero BLOCKERs; SHOULD-FIX applied — deploy gated on the SEO scan, `takeUntilDestroyed` on the home probe, CI scan hardened (positive-control, nav-guard, Allow-subset, non-prod-path verify, deny-set widened), interceptor home-exemption test, single-h1, T019 reconciled to CI realization. **Loop 2** (fix-verify + independent fresh-eyes audit): all loop-1 fixes confirmed OK; the audit caught the per-path `X-Robots-Tag` gap (FR-008/SC-003 for no-JS crawlers) — implemented in `_headers` + CI-asserted, research D2 corrected; static-OG-removal test + double-quote nav-guard added. Both loops clean. Loop 3 not needed (loop-2 fix is a mechanical `_headers`/CI/spec addition, verified locally: 255 tests green, per-path rules in dist, CSP intact, full scan green).

---

## Dependencies & Execution Order

- **Setup (Phase 1)**: T001–T004; T002/T003 parallel, T004 after T003.
- **Foundational (Phase 2)**: T005 first; T006→T007 serial; T008 after T005; T009 after T008. **Blocks all stories.**
- **US1 (Phase 3)**: after Foundational. MVP. Tests T010/T011 parallel; static artifacts T014/T015 parallel with T012/T013.
- **US2 (Phase 4)**: after Foundational; builds on US1's `robots.txt`/`sitemap.xml`; acceptance (noindex, inventory, no-token, headers) independently verifiable. T017/T018/T019 parallel.
- **US3 (Phase 5)**: after Foundational; independent of US1/US2. T021/T022 parallel; T024 parallel with T023.
- **US4 (Phase 6)**: after Foundational + T003/T006.
- **Polish (Phase 7)**: after targeted stories. T029/T030 parallel.

---

## Implementation Strategy

**MVP**: Phase 1 → 2 → 3 (US1) → STOP & VALIDATE (home discoverable, robots/sitemap correct) → demo.
**Incremental**: US1 + US2 ship together (the privacy guarantee is co-critical) → US3 (AEO) → US4 (env) → Polish (hardened CI scan + review loop 2).

---

## Notes

- [P] = different files, no dependency.
- The two P1 stories are tightly coupled (one `robots.txt`, one `SeoService`); ship together.
- Verify each new spec fails before implementing (Principle VII).
- `git add -A` immediately before commit (CLAUDE.md stale-index trap).
- **No token, candidate PII, or secret may appear in any built artifact** — the hardened CI scan (T029) is the backstop.
- **Accepted residual (no-SSR, research D2)**: a crawler that ignores `robots.txt` AND executes no JS, fetching a token URL, receives the SPA shell with home content + canonical `{origin}/` (no token/PII in the bytes; canonical consolidates ranking to `/`). robots.txt (compliant crawlers) + runtime `noindex` (JS crawlers like Googlebot) + canonical are the layered mitigations; per-route static HTML would require SSR (rejected, D1).
