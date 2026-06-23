# Phase 0 Research: Terms & Conditions and Privacy Notice

All decisions below resolve the planning unknowns; none are left as NEEDS CLARIFICATION. Evidence is from the worktree codebase (file paths cited).

## D1 — Publishing mechanism: extend the F61 static-content generator (not SPA routes)

**Decision**: Publish `/terms` and `/privacy` as pre-rendered static HTML emitted by the build-time generator, written to `dist/terms/index.html` and `dist/privacy/index.html`. Do **not** add Angular routes.

**Rationale**: `frontend/src/app/core/seo/route-seo-inventory.spec.ts` hard-asserts exactly one indexable SPA route (`path===''`); a second indexable route fails the build, and a `PRIVATE` route is noindex (contradicting FR-015). The F61 lib comment (`article-build.lib.mjs:11-16`) documents that static files live *outside* the route table precisely so the invariant holds. Cloudflare Pages serves a real `dist/terms/index.html` ahead of the `/* → /index.html 200` SPA catch-all (`frontend/src/_redirects:9-13` documents static-asset precedence), so `/terms` returns the legal page, not the SPA shell/NotFound (FR-021/SC-013).

**Alternatives considered**: (a) Angular SPA routes — rejected (breaks `route-seo-inventory`, needs manual SEO wiring, ships JS for prose). (b) `/resources/legal/*` via the article path — rejected by the clarify decision (conventional top-level URLs chosen).

## D2 — Generator shape: new pure assembler + fixed legal list, NOT the article assembler

**Decision**: Add a pure `assembleLegalPage(doc, ctx)` and a fixed `LEGAL_PAGES` descriptor list to the static-content lib (`article-build.lib.mjs`, or a sibling `legal-build.lib.mjs` if it grows large), and extend `buildSitemap`/`buildLlms`/`buildArtifacts` (+ the `seo-build-articles.mjs` CLI) to emit legal pages and include them in the crawl artifacts.

**Rationale**: `buildSitemap` (`article-build.lib.mjs:382-403`) and `buildLlms` (`406-414`) iterate **only** the article list — there is no slot for non-article pages, so this is net-new emit logic, not "registration" (confirmed by the SEO review). The article assembler (`assembleArticlePage`) emits `BlogPosting`+`FAQPage`+`SpeakableSpecification` and enforces an article model (≤60-word summary, theme, related) that does not fit long-form legal prose. A dedicated assembler reuses the safe primitives (`headCommon`, `jsonLd`, `escapeHtml`, `PAGE_STYLE`, `lintBody`) without the article semantics.

**Concrete wiring (from SEO review S3/S4/N1)**:
- `buildArtifacts` (`:445-476`) gains a `legalPages: LEGAL_PAGES.map(d => ({slug, html: assembleLegalPage(d, ctx)}))` entry in its return object.
- `buildSitemap` / `buildLlms` signatures widen to take the legal list too (e.g. `buildSitemap(articles, legalPages, ctx)` / `buildLlms(baseLlms, articles, legalPages, ctx)`), and each emits `/terms/` + `/privacy/` entries (sitemap `<url>` + an `## Legal` llms section).
- The CLI (`seo-build-articles.mjs`) gains a `loadLegalPages(legalDir)` scanner mirroring `loadArticles` (`:51-70`), with a `CADENCE_LEGAL_DIR` env override mirroring `CADENCE_CONTENT_DIR` (`:27`) for the `node:test` harness, and a `writeFileEnsured(join(distDir, slug, 'index.html'), html)` loop mirroring `:104-111`.
- **`lastUpdated` mapping**: legal `meta.json` uses `lastUpdated` (not the article `datePublished`/`dateUpdated`), so the assembler/sitemap MUST map `lastUpdated` → the human date + sitemap `<lastmod>` explicitly — do NOT pass legal docs through the article `lastmodOf` helper (`:185-187`) blindly (it would emit `undefined`).
- **feed.xml exclusion**: legal pages are NOT emitted into `resources/feed.xml` (`buildFeed`, `:417-439`) — that Atom feed is the resources/article feed; a future editor must not add them.

**Alternatives considered**: shoe-horn legal docs as "articles" — rejected (wrong schema type, 60-word summary cap, theme/related machinery, FAQ-dedup gate all misfit).

## D3 — Structured data type: `WebPage` + `BreadcrumbList` (not Article/BlogPosting)

**Decision**: Each legal page emits `WebPage` + `BreadcrumbList` JSON-LD, sharing the existing `Organization` `@id` node (inlined on-page, the F61 pattern at `article-build.lib.mjs:244-254`).

**Rationale**: schema.org has **no** `PrivacyPolicy`/`TermsOfService` core type; `WebPage` is the valid, Rich-Results-safe generic type. FR-022(f) forbids reusing the article/blog type. Inlining `Organization` on-page keeps `publisher`/`isPartOf` `@id` resolvable in Google's single-page validator (the documented F61 reason).

**Alternatives considered**: `Article`/`BlogPosting` (semantically wrong, forbidden); no JSON-LD (loses AEO value, FR-022(f) requires valid structured data).

## D4 — URL / trailing-slash / canonical

**Decision**: Canonical and sitemap/llms URLs use the **trailing-slash** served form: `https://<origin>/terms/` and `https://<origin>/privacy/`. The no-slash `/terms` 308-redirects to `/terms/` (Cloudflare directory-index behaviour). robots.txt allows both forms with anchored rules.

**Rationale**: The article precedent self-canonicals with a trailing slash because "Cloudflare Pages serves the directory-index page at `/resources/<slug>/` and 308-redirects the no-slash form" (`article-build.lib.mjs:236-238`). A `dist/terms/index.html` behaves identically. The canonical MUST match the served URL (FR-021), so it is the slash form. SC-013's "direct request to `/terms`" is satisfied via the 308 → `/terms/` → page.

**robots rules** (mirroring the anchored style at `robots.txt:6`): add `Allow: /terms$`, `Allow: /terms/$`, `Allow: /privacy$`, `Allow: /privacy/$` (covers both the redirect source and the served directory form; anchored so no broader `/terms-*` path is freed — the SEO-review N2 concern). Placed before `Disallow: /` (RFC 9309 longest-match wins regardless of order; kept adjacent to the existing `/resources` allows for readability).

**Alternatives considered**: no-slash canonical (mismatches served URL → canonical/redirect loop risk); unanchored `Allow: /terms` (over-allows `/terms-anything`).

## D5 — `_headers` and the SPA-fallback interaction

**Decision**: Add **no** `X-Robots-Tag` rule for `/terms`/`/privacy` (so they inherit the indexable default). Add a CI guard asserting `_headers` contains no `/terms`/`/privacy` noindex line (prevents a later regression). Confirm no existing rule matches: the noindex prefixes (`frontend/src/_headers:13-46`) are `/schedule* /booking* /confirm* /status* /feedback* /app* /admin/* /pipeline* /scheduling* /calendar/* /workspace/* /interview-templates* /email-templates* /login* /accept-invite* /reset* /not-authorized*` — none is a prefix of `/terms` or `/privacy`.

**Rationale**: Cloudflare most-specific match; absence = indexable (the home's posture). The global `/*` CSP/Referrer block still applies (good — preserves FR-011). Static `dist/terms/index.html` is served before the `/* → index.html` rewrite, so the SPA wildcard `**`→NotFound (noindex) never renders for `/terms` (FR-021/SC-013).

## D6 — Non-prod / environment gate (inject script MUST be extended — SEO review B1)

**Decision**: The legal pages emit `<meta name="robots" content="__CADENCE_ROBOTS__">` + `__CADENCE_PUBLIC_ORIGIN__` placeholders via `headCommon`, AND `scripts/seo-inject-origin.mjs` is **extended** with a fixed legal-page loop so it substitutes those placeholders in `dist/terms/index.html` + `dist/privacy/index.html`.

**Rationale (corrected from the SEO review)**: `seo-inject-origin.mjs` processes a **hardcoded** file set only — `index.html`, `sitemap.xml`, `llms.txt`, `robots.txt` (`:47-60`) plus a `resources/`-scoped loop (`:71-81`). It does **not** walk arbitrary emitted HTML, so without an edit the legal pages would ship a literal `__CADENCE_ROBOTS__` and `__CADENCE_PUBLIC_ORIGIN__` (broken canonical, neither indexable in prod nor noindex in non-prod — FR-015/FR-017/SC-008 fail). The edit adds, for `slug of ['terms','privacy']`, a `patchOptionalHtml(join(slug,'index.html'))` call mirroring the existing `resources/` loop (applies both origin + robots substitution). This is a required `scripts/seo-inject-origin.mjs # EDIT` (it was missing from the initial plan source list — now added). Non-prod also appends the blanket `/* X-Robots-Tag: noindex` to `_headers` as today.

**Verification**: a CI non-prod step asserts a legal page is noindex and placeholder-free, mirroring the article `$ARTICLE` check (`ci.yml:465-468`).

## D7 — In-app link surfaces

**Decision**: Introduce one shared standalone `PublicFooterComponent` (Terms + Privacy + Home links) mounted on the marketing home and other non-token public pages. On token-bearing candidate pages (`schedule`, `status`, `feedback`, `booking-manage`, `cancel-confirm`, `confirm-attendance`) add a **single inline Privacy Notice link** (not the full footer) to keep the minimal-card layout clean (US3-AC2). On `request-access`, add the Privacy link to the existing 4-point notice block (`request-access.component.ts:84-100`) without removing it (FR-009).

**Rationale**: No global footer exists today (each public page renders bare; `app.component.ts:19-35` only mounts top-bar chrome when route `data.shell===true`). A shared component avoids duplication and centralises the link labels for i18n (FR-012).

**Mount point (frontend review SF-3)**: the footer is mounted **inside each public page component template** (the home today), **NOT** in `AppComponent` (bare `<router-outlet>`) — a global mount would leak it onto token cards and the authenticated shell. Token cards and shell routes never include it; `request-access` keeps its inline notice (FR-009), not the full footer.

**Link mechanism (frontend review SF-1 / security NTH-5)**: a **root-relative** anchor `href="/privacy"` / `href="/terms"` — leading slash **required**. These are static files outside the router, so in-app links use a full-document `href`, NOT `routerLink` (which would hit the SPA router → wildcard `**` → `NotFoundComponent`, `app.routes.ts:276`). A *relative* href (`privacy`/`./privacy`) would mis-resolve against `<base href="/">` (`index.html:6`) on nested routes like `/booking/cancel` → wrong `/booking/privacy`. Precedent: `home.component.ts:94` uses `href="/resources/"`. A component test asserts the links use `href` (not `routerLink`) equal to exactly `/privacy`/`/terms` with no token/query.

**Token-page state preservation (frontend SF-2 + security NTH-4)**: on token-bearing pages the Privacy link opens in a new tab — `target="_blank" rel="noopener noreferrer"` — as the **default** (not optional): a same-tab nav would discard the candidate's in-memory token/state (token is a private field re-resolved from the URL, e.g. `candidate-status.component.ts:19-37`). Leak-safety holds either way (token-free target + global `no-referrer`); `rel="noopener noreferrer"` is mandatory whenever `target="_blank"` is used (reverse-tabnabbing). Same-origin static page → no third-party contact (FR-011).

## D8 — Candidate email Privacy link (FR-020, Art. 14)

**Decision**: Add a **URL-typed** `privacy_link` merge token (the `status_link`/`reschedule_link` precedent) to `BuiltInEmailTemplates` footers and `MergeTokenCatalogue`, **permitted for every candidate-facing message type**, with its constant value injected **centrally** in `EmailTemplateService.renderForSend`: `values.put("privacy_link", authProps.getSpaBaseUrl() + "/privacy")`.

**Rationale / mechanism (security review SF-1/SF-2)**: a *literal* `<a href>` in the template body does NOT work — the F21 `MergeRenderer` HTML-escapes recruiter-authored body text (anti-spoofing), so a clickable link MUST come through a **URL-typed** token (rendered as an `href==text` anchor restricted to http(s)). `spaBaseUrl` (`AuthProperties`) is the same Cloudflare origin that serves the static `/privacy`, so the value is a constant — no candidate interpolation, SC-010 (no token/PII) holds by construction. Central injection in `renderForSend` (single source) avoids a per-call-site `[[missing:privacy_link]]`; permitting the token for *every* candidate-facing type avoids a per-type literal `{{privacy_link}}`. Indirectly-collected candidates (CSV/ATS/sourced) never see a candidate page, so the first email is the Art. 14 touchpoint (GDPR review B2). Content-only change — no new service/collection/dependency (C2/C4 hold).

**Constraint**: the token addition MUST move atomically with `MergeTokenCatalogue` + `BuiltInEmailTemplates` + the F21 `@PostConstruct`/`BuiltInTemplateCompletenessTest` (the documented F21 lesson) or startup fails.

**Test (security SF-2)**: a MockMvc/render contract test asserts that **every** candidate-facing template renders `privacy_link` as an `<a href="https://<origin>/privacy">` anchor — never the literal `{{privacy_link}}` and never `[[missing:privacy_link]]` — and that `MergeTokenCatalogue.isPermitted(type, PRIVACY_LINK)` for each.

**Alternatives considered**: defer FR-020 — rejected (Art. 14 obligation, GDPR-review BLOCKER, minimal edit). Literal footer anchor with no token — rejected (escaped to inert text by the F21 renderer).

## D9 — Draft/placeholder notice (FR-018)

**Decision**: Ship placeholder legal copy with a prominent, visually-distinct on-page banner ("Draft — pending legal review; not yet binding") rendered above the document body on both pages, plus a `draft: true` flag + `version` + `lastUpdated` in the content `meta.json`. The banner is gated on the draft flag so counsel-final copy (flag off) removes it.

**Rationale**: FR-018 requires more than a version label. Driving it from a content-meta flag lets the operator flip to final without code change.

## D10 — Accessibility & design-system reuse

**Decision**: Legal pages use the static-content `PAGE_STYLE` system-font stack (`article-build.lib.mjs:191-206`) — `max-width: 44rem`, `overflow-wrap: anywhere`, single `<h1>`, `lang="en"`. **Note (frontend NTH-1)**: `PAGE_STYLE` applies the ≥44 px min-height only to `.related a` / `.home-link` selectors (`:204`), so the legal cross-doc + home links MUST carry the `.home-link` class (a bare `<a>` would miss the 44 px rule). The in-app footer/links reuse PR-34 `styles.scss` primitives (`.container`, `.measure`, link tokens, `.btn`/link min-height 44 px). axe WCAG 2.2 AA + explicit 44 px `getBoundingClientRect` checks (the F14 harness, `frontend/src/testing/axe.ts`) cover the new Angular surfaces; `@lhci/cli` audits `/terms`+`/privacy`.

**axe on the static pages (frontend NTH-1)**: the legal HTML is not an Angular component, but F61 already runs axe on assembled static HTML by regex-extracting `<style>`+`<main>` and injecting via `host.innerHTML` + `attachToBody` (`article-build.lib.spec.ts:289-296` `render()` helper) — the legal assembler output is the same shape, so this is a direct reuse, not a novel harness. `PAGE_STYLE` contrast passes AA (link `#0b5cad` on `#fff` ≈ 5.9:1; body `#1a1a1a` ≈ 17:1).

**Rationale**: Zero new CSS needed for the static pages; candidate pages keep the system font (no Fraunces, the `styles.scss:215-222` token-page rule). **But `request-access` is NOT in that token-page font allow-list — it is an entry/brand page rendered in Fraunces (frontend SF-4)**, so the added Privacy link's axe + 44 px gate MUST run against the actual `request-access` render (Fraunces + its own `.scss`), not a generic token-card mock. Satisfies FR-013/FR-014/SC-003/SC-004.

## D11 — Testing strategy (maps to acceptance criteria)

**Decision**:
- **Pure lib (`node:test`)**: `assembleLegalPage` emits single `<h1>`, `WebPage`+`BreadcrumbList` JSON-LD (valid JSON, non-Article), draft banner when `draft:true`, cross-links to the other doc + home, trailing-slash canonical; `buildSitemap`/`buildLlms` include `/terms/`+`/privacy/`; `lintBody` runs on legal bodies (no token/PII/script). (US1, FR-021/022, SC-007/009/012/013-shape.)
- **Angular/Karma + axe**: footer renders both links (root-relative `href`, not `routerLink`) and resolves; each candidate surface in the inventory renders the Privacy link — explicitly incl. `request-access` (Fraunces render), `schedule`, `status`, `feedback`, `booking-manage`, `cancel-confirm`, `confirm-attendance`; request-access retains the 4-point notice + adds the link; token pages emit no token in the link, set `target="_blank" rel="noopener noreferrer"`, and write no web storage on click; axe 0 violations + 44 px on `/terms`,`/privacy` (the F61 `render()` static-HTML helper) and on each modified surface. (US2/US3, FR-008/009/010/013, SC-002/004/006/011.)
- **i18n scope note (frontend NTH-2)**: the static legal `body.html` prose is build-time content (English, emitted by the Node generator) and is intentionally OUT of the Angular `$localize` extraction scope; only the Angular link labels (footer + per-surface) require `$localize`/`i18n` (FR-012/SC-011). A future localized legal page is a separate authored file, not a string-table entry.
- **Lighthouse (`@lhci/cli`)**: `/terms`,`/privacy` perform within budget.
- **Backend (JUnit/MockMvc)**: a contract test renders each candidate built-in template and asserts the Privacy URL is present and contains no token/PII; the F21 completeness test stays green. (FR-020/SC-010.)
- **CI artifact scan**: robots `ok`-set updated; new assertion that `/terms`,`/privacy` are in sitemap+llms with valid non-Article JSON-LD; `_headers` has no legal noindex line; deny-grep finds no token/PII in artifacts. (FR-022/SC-005/008/012.)

**Rationale**: Constitution VII requires ≥1 acceptance test per user story; the above covers US1–US4. The pure lib is the single source of truth (runs in Node test + browser bundle), the F61 precedent.

## D12 — Encoding discipline (Principle V / F30 lesson)

**Decision**: Legal prose, `meta.json`, the lib, and Java edits are authored in ASCII (or documented UTF-8); a byte-level non-ASCII / NUL scan of every new/modified source file runs before done (`git diff --numstat` binary check + grep). No `.ps1/.cmd/.bat` is added.

**Rationale**: The F30 NUL-byte and F42 BOM incidents — generated content/source must be byte-scanned, not just visually reviewed.
