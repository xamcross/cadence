# Brand Review Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix all 10 findings from the 2026-07-21 brand review of cadenceapp.cc — honest top-of-funnel claims, a prospect-first CTA, one spelling convention, softened competitor language, scannable copy, and a branded shell (logo + nav + CTA) on the static marketing/SEO pages.

**Architecture:** Copy/claim fixes are small edits to the Angular home component, the static `index.html`, the per-route SEO model, and first-party content `body.html` files. The static-page brand shell (findings #1/#2) is added in one place — the pure `article-build.lib.mjs` generator — via a shared `siteHeader()` helper + an enhanced inline `PAGE_STYLE`, so every static page (articles, resources index, legal, marketing) gets the same branded header with zero new runtime dependency and no external request. Tests are updated first (TDD) where a spec exists; content-only edits are gated by the existing `article-build.lib.spec.ts` fixtures plus `git grep` guards.

**Tech Stack:** TypeScript 5.4 / Angular 17.3 standalone (home component + spec, Karma/Jasmine via EdgeHeadless), plain ESM `.mjs` (the F61 static-content generator, shared verbatim by the Node CLI and the Karma bundle), first-party HTML/JSON content files. No backend change. No new dependency.

## Global Constraints

- **No new backend or frontend runtime/build dependency** — the brand shell is inline CSS + a string helper in the existing `.mjs`; static pages MUST stay self-contained (no external CSS/font/image request).
- **Spelling convention: American English** for prose. Fix the British outliers (`optimise`, `maths`, `categorised`). **Keep `No-show defense` and `artifacts`** — `No-show defense` is a coined, site-wide product term (US-spelled by decision, documented in Task 5).
- **WCAG 2.2 AA**: axe 0 violations on every generated static page; every interactive target ≥ 44px (Principle IX). The header CTA and nav links must meet both.
- **"Exactly one indexable route" invariant** (`route-seo-inventory`): do NOT add a new Angular route or mark any route `index:true`. Static pages carry their own `__CADENCE_ROBOTS__` marker — unchanged.
- **Internal link forms**: static directory pages use the trailing-slash form (`/features/`, `/resources/…/`, `/terms/`); SPA routes use the no-slash form (`/login`, `/request-access`). The body-safety allow-list (`PUBLIC_LINK_RE`) only lints `page.bodyHtml`, NOT the template header/footer, so header links to `/request-access` and `/login` are permitted.
- **i18n**: all user-facing Angular strings keep their `@@id` and `i18n` attributes; changing the English *source* text is safe (no `frontend/src/locale/**` catalogs exist). New CTA copy gets a new `@@id`.
- **Line endings**: LF for `.html`/`.mjs`/`.ts`/`.json`/`.md` (match the existing files). No CRLF.
- **Commit hygiene (stale-index trap)**: run `git add -A` immediately before every `git commit`, then confirm `git status` shows a clean tree before moving on. Never rely on an earlier `git add`.
- **Test command** (from `frontend/`): `npx ng test --watch=false --browsers=EdgeHeadless --include=<spec-glob>`. Full integration gate (optional, heavy): `npx ng build`.
- **Branch**: work continues on `ui/workbench-overhaul` (already checked out). Do not commit/push unless the operator asks.

---

## File map

- Modify: `frontend/src/app/features/home/home.component.ts` — hero lede copy (#3/#4), feature-card title (#4), hero + closing CTA order (#5), resources label (#9).
- Modify: `frontend/src/app/features/home/home.component.spec.ts` — CTA/copy assertions (TDD anchor for #3/#4/#5).
- Modify: `frontend/src/index.html` — meta/OG/Twitter descriptions, FAQ Q/A, SoftwareApplication description, static no-JS hero (#3/#4).
- Modify: `frontend/src/app/core/seo/route-seo.model.ts` — `PUBLIC_HOME.description` (#3/#4).
- Modify: `frontend/src/content/pages/features/body.html` — "Built for GDPR" H2 + break the dense erasure sentence into a list (#4/#8).
- Modify: `frontend/src/content/pages/vs/calendly/body.html` — drop the "ever made" superlative (#7), soften "has no concept" (#10), `maths`→`math` (#6).
- Modify: `frontend/src/content/articles/candidate-experience-best-practices/body.html` — `optimise`→`optimize` (#6).
- Modify: `frontend/src/content/articles/interview-scheduling-and-calendar-coordination/body.html` — `maths`→`math` (#6).
- Modify: `frontend/src/content/pages/integrations/greenhouse/body.html` — `categorised`→`categorized` (#6).
- Modify: `frontend/src/content/pages/integrations/lever/body.html` — `categorised`→`categorized` (#6).
- Modify: `frontend/src/app/core/seo/article-build.lib.mjs` — `siteHeader()` helper + enhanced `PAGE_STYLE` + insert header into all four assemble functions (#1/#2).
- Modify: `frontend/src/app/core/seo/article-build.lib.spec.ts` — render helpers include `<header>`; branded-header assertions (TDD anchor for #1/#2).
- Create: `frontend/src/content/TERMINOLOGY.md` — canonical product-term reference (#9).

---

## Task 1: Home component — honest claims + prospect-first CTA (#3, #4, #5, #9)

**Files:**
- Modify: `frontend/src/app/features/home/home.component.ts`
- Test: `frontend/src/app/features/home/home.component.spec.ts`

**Interfaces:**
- Consumes: nothing new.
- Produces: the home hero primary CTA is now `a.cta` → `/request-access` with text "Request access"; a secondary `/login` "Sign in" link remains. Later tasks do not depend on this.

- [ ] **Step 1: Write the failing tests**

In `home.component.spec.ts`, update the existing CTA-href assertion and add a copy/CTA test. Replace line 40:

```ts
    expect(el.querySelector('a.cta')?.getAttribute('href')).toContain('/request-access');
```

Then add this `it` block inside the `describe` (e.g. after the existing "anonymous" test):

```ts
  it('leads with the prospect CTA and uses honest, non-absolute claims (#3/#4/#5)', async () => {
    const { fixture } = await setup('anon');
    const el: HTMLElement = fixture.nativeElement;

    // #5 — primary CTA is "Request access" (prospect action), sign-in demoted but present
    const primary = el.querySelector('a.cta') as HTMLAnchorElement;
    expect(primary.getAttribute('href')).toContain('/request-access');
    expect(primary.textContent?.trim()).toBe('Request access');
    const signIn = Array.from(el.querySelectorAll('a')).find(a => a.getAttribute('href') === '/login');
    expect(signIn).withContext('sign-in link still reachable').toBeTruthy();

    // #3/#4 — no absolute "prevent" / "GDPR-safe" claims in the hero
    const text = el.textContent || '';
    expect(text).not.toContain('prevent no-shows');
    expect(text).not.toContain('GDPR-safe');
    expect(text).toContain('cut no-shows');
    expect(text).toContain('Built for GDPR');
  });
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd frontend && npx ng test --watch=false --browsers=EdgeHeadless --include=src/app/features/home/home.component.spec.ts`
Expected: FAIL — `a.cta` still points to `/login`; text still contains "prevent no-shows" / "GDPR-safe".

- [ ] **Step 3: Fix the hero lede copy (#3/#4)**

In `home.component.ts`, replace the `.lede` paragraph (lines ~27-30):

```html
        <p class="lede reveal reveal-2" i18n="@@home.lede">
          Schedule interviews, cut no-shows, and keep every candidate informed — with no candidate
          account required. Cadence syncs with Google and Microsoft calendars and is built for GDPR.
        </p>
```

- [ ] **Step 4: Reorder the hero CTAs (#5)**

Replace the hero `.hero__actions` block (lines ~31-36). Move `cta btn btn--primary` onto "Request access" and demote "Sign in" to `btn btn--outline`; keep each link's existing `@@id`:

```html
        <div class="hero__actions reveal reveal-3">
          <a class="cta btn btn--primary" routerLink="/request-access" i18n="@@home.requestAccess">Request access</a>
          <a class="btn btn--outline" routerLink="/login" i18n="@@home.cta">Sign in</a>
          <a class="btn btn--ghost" href="#features" i18n="@@home.learn">See what it does</a>
        </div>
```

- [ ] **Step 5: Rename the GDPR feature card (#4)**

In `home.component.ts`, change the feature-card title (line ~80):

```html
            <h3 class="feature__title" i18n="@@home.features.gdpr.title">Built for GDPR</h3>
```

- [ ] **Step 6: Reorder the closing CTAs (#5) and keep the resources label consistent (#9)**

Replace the closing `.hero__actions` block (lines ~92-98). Lead with "Request access"; keep sign-in as an outline; leave the ghost content links:

```html
        <div class="hero__actions">
          <a class="btn btn--primary" routerLink="/request-access" i18n="@@home.requestAccess2">Request access</a>
          <a class="btn btn--outline" routerLink="/login" i18n="@@home.cta2">Sign in to your workspace</a>
          <a class="btn btn--ghost" href="/features/" i18n="@@home.featuresPage">Explore features</a>
          <a class="btn btn--ghost" href="/pricing/" i18n="@@home.pricingPage">Pricing</a>
          <a class="btn btn--ghost resources-link" href="/resources/" i18n="@@home.resources">Resources</a>
        </div>
```

(The `resources-link` href stays `/resources/` so the existing F61 test still passes; the label is "Resources" to match the footer and nav — canonical per Task 5.)

- [ ] **Step 7: Run the tests to verify they pass**

Run: `cd frontend && npx ng test --watch=false --browsers=EdgeHeadless --include=src/app/features/home/home.component.spec.ts`
Expected: PASS — including the unchanged axe (0 violations), 44px `a.cta`, and resources-link tests.

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "fix(home): honest claims (cut no-shows / built for GDPR) + prospect-first CTA"
git status   # verify clean tree
```

---

## Task 2: Static index.html + route SEO metadata (#3, #4)

**Files:**
- Modify: `frontend/src/index.html`
- Modify: `frontend/src/app/core/seo/route-seo.model.ts`

**Interfaces:**
- Consumes: nothing.
- Produces: the home FAQ question becomes "How does Cadence handle GDPR?". `scripts/seo-build-articles.mjs` reads this at build time via `readHomeFaqQuestions()` (from the FAQPage JSON-LD), so no script edit is needed; the `faqDedupCheck` gate re-derives it automatically.

- [ ] **Step 1: Fix the crawler-facing meta descriptions (#3/#4)**

In `index.html`, replace line 11:

```html
  <meta name="description" content="Cadence helps recruiters schedule interviews, cut no-shows, and keep candidates informed. Calendar sync, built for GDPR, no candidate account required.">
```

Replace line 19 (`og:description`) and line 24 (`twitter:description`) — both currently identical — with:

```html
  <meta property="og:description" content="Schedule interviews, cut no-shows, and keep candidates informed — no candidate account required.">
```
```html
  <meta name="twitter:description" content="Schedule interviews, cut no-shows, and keep candidates informed — no candidate account required.">
```

- [ ] **Step 2: Fix the SoftwareApplication + FAQ JSON-LD (#3/#4)**

In `index.html`, replace the SoftwareApplication `description` (line 54):

```html
    "description": "Interview scheduling, no-show defense, and a privacy-first candidate status page — no candidate login required.",
```

Replace the "What is Cadence?" answer (line 75) — prose uses "reduce":

```html
        "acceptedAnswer": { "@type": "Answer", "text": "Cadence is an interview-scheduling and candidate-experience platform that helps recruiters schedule interviews, reduce no-shows, and keep candidates informed." }
```

Replace the GDPR FAQ question + answer (lines 88-91) — reframe from an absolute yes/no claim to a factual "how":

```html
      {
        "@type": "Question",
        "name": "How does Cadence handle GDPR?",
        "acceptedAnswer": { "@type": "Answer", "text": "Personal data is encrypted at rest, consent is recorded before any contact, and erasure is one click." }
      }
```

- [ ] **Step 3: Fix the static no-JS hero (#3/#4)**

In `index.html`, replace the `<app-root>` fallback paragraph (lines 102-104):

```html
      <p>Cadence helps recruiters schedule interviews, cut no-shows, and keep candidates informed —
         with no candidate account required. It syncs with Google and Microsoft calendars and is
         built for GDPR.</p>
```

- [ ] **Step 4: Fix the runtime route description (#3/#4)**

In `route-seo.model.ts`, replace the `PUBLIC_HOME.description` (lines 33-35):

```ts
  description:
    'Cadence helps recruiters schedule interviews, cut no-shows, and keep candidates ' +
    'informed — with no candidate account required. Calendar sync, built for GDPR.',
```

- [ ] **Step 5: Verify no absolute claims remain and JSON-LD still parses**

Run the copy guards (expect no output):

```bash
git grep -n "prevent no-show" -- frontend/src ; echo "exit:$?"
git grep -n "GDPR-safe by design\|GDPR-safe," -- frontend/src frontend/src/index.html ; echo "exit:$?"
```

Verify the four JSON-LD blocks in `index.html` still parse (Node, no deps):

```bash
node -e "const h=require('fs').readFileSync('frontend/src/index.html','utf8');const b=[...h.matchAll(/<script type=\"application\/ld\+json\">([\s\S]*?)<\/script>/g)];b.forEach((m,i)=>{JSON.parse(m[1]);});console.log('ok',b.length,'blocks')"
```
Expected: `ok 4 blocks`, and both greps print only `exit:1` (grep found nothing).

- [ ] **Step 6: Run the SEO library tests (dedup gate re-derives the home FAQ)**

Run: `cd frontend && npx ng test --watch=false --browsers=EdgeHeadless --include=src/app/core/seo/article-build.lib.spec.ts`
Expected: PASS (the spec uses its own `HOME_FAQ` fixture; this confirms nothing regressed).

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "fix(seo): honest claims in index.html meta/JSON-LD + route SEO description"
git status
```

---

## Task 3: Marketing & article content copy (#6, #7, #8, #10)

**Files:**
- Modify: `frontend/src/content/pages/features/body.html`
- Modify: `frontend/src/content/pages/vs/calendly/body.html`
- Modify: `frontend/src/content/articles/candidate-experience-best-practices/body.html`
- Modify: `frontend/src/content/articles/interview-scheduling-and-calendar-coordination/body.html`
- Modify: `frontend/src/content/pages/integrations/greenhouse/body.html`
- Modify: `frontend/src/content/pages/integrations/lever/body.html`

**Interfaces:**
- Consumes: nothing.
- Produces: static content only; no code depends on these strings (the `article-build.lib.spec.ts` uses synthetic fixtures, not real content).

- [ ] **Step 1: features/body.html — "Built for GDPR" + break the dense sentence (#4/#8)**

Replace the GDPR section (lines 70-78) with a lead + bullet list. Links are unchanged (both on the allow-list):

```html
<h2>Built for GDPR</h2>
<p>
  Candidate personal data is encrypted at rest, access is role-scoped, and retention is automated.
  Contact consent is recorded before outreach and checked again at send time. The right to erasure
  is one click, and it reaches everything Cadence holds:
</p>
<ul>
  <li>the candidate record and any unsent email;</li>
  <li>calendar artifacts and synced ATS copies;</li>
  <li>an audit trail that survives the erasure without retaining the erased data.</li>
</ul>
<p>
  The practices, and why they matter, are described in
  <a href="/resources/gdpr-safe-recruiting/">privacy-safe recruiting</a> and our
  <a href="/privacy/">privacy notice</a>.
</p>
```

- [ ] **Step 2: vs/calendly/body.html — drop the superlative (#7), soften "has no concept" (#10), fix spelling (#6)**

Replace the opening clause (line 3):

```html
  Calendly is one of the most widely used general-purpose schedulers: booking pages, wide
```

Replace the "The waiting candidate" clause (lines 28-29): change `A general-purpose scheduler has no concept of a candidate between bookings.` to:

```html
    when anyone has waited too long. A general-purpose scheduler isn't built around the candidate
    between bookings.</li>
```

Replace `slot maths` on line 20:

```html
    daily interview caps per interviewer, enforced in the slot math, plus atomic reservation designed
```

- [ ] **Step 3: candidate-experience-best-practices/body.html — spelling (#6)**

Replace the H2 on line 11:

```html
<h2>Map the journey before you optimize it</h2>
```

- [ ] **Step 4: interview-scheduling-and-calendar-coordination/body.html — spelling (#6)**

Replace `slot maths` on line 60:

```html
  it in the slot math, not in a wiki page nobody reads mid-booking.
```

- [ ] **Step 5: greenhouse/body.html and lever/body.html — spelling (#6)**

In `greenhouse/body.html` line 16, `categorised` → `categorized`:

```html
  categorized and retried; a sync problem surfaces as a status you can see, not silence.</p>
```

In `lever/body.html` line 15, `categorised` → `categorized`:

```html
  timestamps, and categorized errors, so the state of the connection is never a mystery.
```

- [ ] **Step 6: Verify spelling + claim guards and that content still lints/builds**

Run the guards (expect the British spellings and superlative to be gone from content):

```bash
git grep -nE "optimise|categoris|\bmaths\b|ever made" -- frontend/src/content ; echo "exit:$?"
```
Expected: only `exit:1` (no matches).

Confirm the content bodies still pass the build-time link allow-list + assemble without error (Node, using the real generator against the real content, into a throwaway dist that already has an SPA build is NOT required — instead exercise the pure lib directly):

```bash
node --input-type=module -e "import { lintPageBody, lintBody } from './frontend/src/app/core/seo/article-build.lib.mjs'; import { readFileSync } from 'node:fs'; lintPageBody('features', readFileSync('frontend/src/content/pages/features/body.html','utf8')); lintPageBody('vs/calendly', readFileSync('frontend/src/content/pages/vs/calendly/body.html','utf8')); lintBody('cx', readFileSync('frontend/src/content/articles/candidate-experience-best-practices/body.html','utf8')); console.log('lint ok')"
```
Expected: `lint ok`.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "fix(content): US spelling, drop superlative, soften competitor claim, scannable GDPR copy"
git status
```

---

## Task 4: Branded shell for every static page (#1, #2)

Add a shared branded header (clay wordmark + primary nav + "Request access" CTA) and adopt the brand palette in the inline `PAGE_STYLE`, applied to article, resources-index, legal, and marketing pages. Self-contained: no external CSS/font/image.

**Files:**
- Modify: `frontend/src/app/core/seo/article-build.lib.mjs`
- Test: `frontend/src/app/core/seo/article-build.lib.spec.ts`

**Interfaces:**
- Consumes: nothing external.
- Produces: a module-private `siteHeader()` (string → string, no args) inserted between `<body>` and `<main>` in `assembleArticlePage`, `assembleIndexPage`, `assembleLegalPage`, and `assembleMarketingPage`. Emits `<header class="site-header">` with `a.site-header__brand[href="/"]` (wordmark "Cadence"), a `nav.site-header__nav`, and `a.site-header__cta[href="/request-access"]` ("Request access").

- [ ] **Step 1: Write the failing tests**

In `article-build.lib.spec.ts`, update the three render helpers so the header is included in the axe/DOM assertions. In `render` (line ~299-306), `lrender` (line ~342-349), and `mrender` (line ~401-408), add a header capture and prepend it. For each helper, change the body to:

```ts
      const style = (fullHtml.match(/<style>[\s\S]*?<\/style>/) || [''])[0];
      const header = (fullHtml.match(/<header[\s\S]*?<\/header>/) || [''])[0];
      const main = (fullHtml.match(/<main>[\s\S]*?<\/main>/) || [''])[0];
      host.innerHTML = style + header + main;   // (lhost / mhost in the other two)
```

Add a branded-header test inside the `describe('marketing pages ...')` block:

```ts
    it('renders a branded site header: wordmark links home + a >=44px Request access CTA (#1/#2)', () => {
      const html = assembleMarketingPage(page({}), ctx());
      expect(html).toContain('class="site-header"');
      expect(html).toContain('<a class="site-header__brand" href="/"');
      expect(html).toContain('>Cadence</span>');
      expect(html).toContain('class="site-header__cta" href="/request-access"');
      const el = mrender(html);
      const cta = el.querySelector('.site-header__cta') as HTMLElement;
      expect(cta.textContent).toContain('Request access');
      expect(cta.getBoundingClientRect().height).toBeGreaterThanOrEqual(44);
    });
```

Add a header-presence assertion to the article block (inside `describe('US1 article page' ...)`):

```ts
    it('every static page carries the shared branded header (#1/#2)', () => {
      const out = buildArtifacts(FIXTURE, '#', ctx());
      expect(out.pages[0].html).toContain('class="site-header"');
      expect(out.indexHtml).toContain('class="site-header"');
    });
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd frontend && npx ng test --watch=false --browsers=EdgeHeadless --include=src/app/core/seo/article-build.lib.spec.ts`
Expected: FAIL — no `site-header` in output; `mrender`/`render` reference `header` which is now captured but empty.

- [ ] **Step 3: Add the `siteHeader()` helper**

In `article-build.lib.mjs`, add above `function headCommon(` (line ~216):

```js
/** Shared branded site header for every static page (#1/#2). Self-contained: text wordmark + inline
 *  SVG mark (no image request), primary nav, and the prospect CTA. Links here are NOT body-linted
 *  (that gate only guards page.bodyHtml), so the /request-access SPA route is allowed. */
function siteHeader() {
  return '<header class="site-header">\n' +
    '<a class="site-header__brand" href="/" aria-label="Cadence home">' +
    '<svg class="site-header__mark" viewBox="0 0 24 24" width="22" height="22" aria-hidden="true" ' +
    'fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round">' +
    '<path d="M5 19V11M12 19V5M19 19v-6"/></svg>' +
    '<span class="site-header__word">Cadence</span></a>\n' +
    '<nav class="site-header__nav" aria-label="Primary">' +
    '<a href="/features/">Features</a>' +
    '<a href="/pricing/">Pricing</a>' +
    '<a href="/integrations/">Integrations</a>' +
    '<a href="/resources/">Resources</a>' +
    '</nav>\n' +
    '<a class="site-header__cta" href="/request-access">Request access</a>\n' +
    '</header>\n';
}
```

- [ ] **Step 4: Adopt the brand palette + header styles in `PAGE_STYLE`**

Replace the entire `PAGE_STYLE` constant (lines ~198-214) with:

```js
const PAGE_STYLE = [
  ':root { color-scheme: light; --clay: #b5512e; --clay-ink: #8f3a1f; --ink: #1b1a16; --ink-muted: #54514a; --paper: #f6f4ef; --line: #e8e3da; --link: #0b5cad; }',
  '*, *::before, *::after { box-sizing: border-box; }',
  'body { margin: 0; font: 16px/1.6 system-ui, -apple-system, "Segoe UI", Roboto, sans-serif; color: var(--ink); background: var(--paper); }',
  'main { max-width: 44rem; margin: 0 auto; padding: 1.5rem 1rem 4rem; }',
  'a { color: var(--link); }',
  'h1, h2, h3, .site-header__word { font-family: ui-serif, Georgia, "Times New Roman", serif; }',
  'h1 { font-size: 1.95rem; line-height: 1.2; }',
  'h2 { font-size: 1.4rem; margin-top: 2rem; }',
  'h3 { font-size: 1.1rem; }',
  '.lead { font-size: 1.15rem; color: var(--ink-muted); }',
  '.meta { color: var(--ink-muted); font-size: 0.9rem; }',
  'nav.crumbs { font-size: 0.9rem; margin-bottom: 1rem; }',
  'ul.cards { list-style: none; padding: 0; }',
  'ul.cards li { border: 1px solid var(--line); border-radius: 12px; padding: 1rem; margin-bottom: 1rem; background: #fff; }',
  '.related a, .home-link { display: inline-block; min-height: 44px; line-height: 44px; padding: 0 0.75rem; }',
  '.draft-banner { border: 2px solid #b35900; background: #fff4e5; color: var(--ink); padding: 0.75rem 1rem; border-radius: 8px; margin-bottom: 1.5rem; font-weight: 600; }',
  'body, main, h1, h2, p, li { overflow-wrap: anywhere; word-break: break-word; }',
  '.site-header { display: flex; flex-wrap: wrap; align-items: center; gap: 0.5rem 1rem; max-width: 60rem; margin: 0 auto; padding: 0.75rem 1rem; border-bottom: 1px solid var(--line); }',
  '.site-header__brand { display: inline-flex; align-items: center; gap: 0.5rem; min-height: 44px; text-decoration: none; color: var(--ink); }',
  '.site-header__mark { color: var(--clay); flex: none; }',
  '.site-header__word { font-size: 1.25rem; font-weight: 700; }',
  '.site-header__nav { display: flex; flex-wrap: wrap; gap: 0.25rem 1rem; margin-inline-start: auto; }',
  '.site-header__nav a { display: inline-flex; align-items: center; min-height: 44px; color: var(--ink-muted); text-decoration: none; }',
  '.site-header__nav a:hover { color: var(--ink); text-decoration: underline; }',
  '.site-header__cta { display: inline-flex; align-items: center; min-height: 44px; padding: 0 1rem; border-radius: 10px; background: var(--clay-ink); color: #fff; text-decoration: none; font-weight: 600; }',
  '.site-header__cta:hover { background: var(--clay); }'
].join('\n  ');
```

(`--clay-ink` #8f3a1f under `#fff` clears 4.5:1; `--ink-muted` #54514a and `--ink` #1b1a16 on `--paper` #f6f4ef clear AA — so the axe contrast gate holds. `flex-wrap` on the header keeps the 375px overflow test green.)

- [ ] **Step 5: Insert the header into all four assemble functions**

In each of `assembleArticlePage`, `assembleIndexPage`, `assembleLegalPage`, and `assembleMarketingPage`, find the return-string fragment `'</head>\n<body>\n<main>\n'` and change it to `'</head>\n<body>\n' + siteHeader() + '<main>\n'`.

- `assembleArticlePage` (line ~324): `'</head>\n<body>\n' + siteHeader() + '<main>\n' +`
- `assembleIndexPage` (line ~381): `'</head>\n<body>\n' + siteHeader() + '<main>\n' +`
- `assembleLegalPage` (line ~487): `'</head>\n<body>\n' + siteHeader() + '<main>\n' +` (the `draftBanner` still follows `<main>`)
- `assembleMarketingPage` (line ~627): `'</head>\n<body>\n' + siteHeader() + '<main>\n' +`

- [ ] **Step 6: Run the tests to verify they pass**

Run: `cd frontend && npx ng test --watch=false --browsers=EdgeHeadless --include=src/app/core/seo/article-build.lib.spec.ts`
Expected: PASS — new header assertions pass; every existing axe test (article, index, legal, marketing) still reports 0 violations with the header now in the rendered DOM; the 375px overflow test and the 44px `.home-link` test stay green.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat(seo): branded header (wordmark + nav + Request access CTA) + brand palette on static pages"
git status
```

---

## Task 5: Terminology reference (#9)

Establish the canonical product-term list so future copy stays consistent, and record the spelling decision.

**Files:**
- Create: `frontend/src/content/TERMINOLOGY.md`

**Interfaces:** none (documentation).

- [ ] **Step 1: Create the terminology reference**

Write `frontend/src/content/TERMINOLOGY.md`:

```markdown
# Cadence copy terminology (canonical)

Use these exact terms in marketing/product copy. Reviewed 2026-07-21 (brand review).

## Spelling
- **American English** for prose (optimize, categorized, math, license).
- **Exceptions (coined product terms, keep as written):** "No-show defense", "artifacts".

## Feature names (use exactly)
| Use this | Not this |
|----------|----------|
| self-scheduling | self-serve booking, self-book |
| No-show defense | no-show protection, anti-no-show |
| confirmation cascade | reminder chain, confirm flow |
| candidate status page | status portal, candidate portal |
| interviewer scorecards | feedback forms, review cards |
| Resources (nav/label) | Recruiting guides, recruiting resources |

## Claims (honest register — no absolutes)
- "cut no-shows" / "reduce no-shows" — never "prevent no-shows".
- "built for GDPR" / "privacy by design" — never "GDPR-safe" / "GDPR-compliant" as a guarantee.
- Competitor mentions stay factual and fair; avoid "the best … ever" and "has no concept of".
```

- [ ] **Step 2: Verify no stray absolute claims survived across all copy surfaces**

```bash
git grep -nE "prevent no-show|GDPR-safe by design|ever made|has no concept" -- frontend/src ; echo "exit:$?"
```
Expected: `exit:1` (no matches).

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "docs(content): canonical terminology + spelling/claims reference"
git status
```

---

## Final verification (after all tasks)

- [ ] Run the two touched specs together:
  `cd frontend && npx ng test --watch=false --browsers=EdgeHeadless --include=src/app/features/home/home.component.spec.ts --include=src/app/core/seo/article-build.lib.spec.ts`
  Expected: all PASS, 0 axe violations.
- [ ] Copy guard is clean: `git grep -nE "prevent no-show|GDPR-safe by design|optimise|categoris|\bmaths\b|ever made" -- frontend/src` prints nothing.
- [ ] (Optional, heavy integration gate) `cd frontend && npx ng build` then `node scripts/seo-build-articles.mjs frontend/dist/cadence/browser` completes without an `ArticleBuildError`, and a spot-check of `frontend/dist/cadence/browser/features/index.html` shows the `site-header` markup.

---

## Findings → tasks coverage

| # | Finding | Task |
|---|---------|------|
| 1 | Static pages have no site chrome (header/logo) | Task 4 |
| 2 | No CTA button on marketing pages | Task 4 (header CTA) |
| 3 | "prevent no-shows" absolute claim | Task 1 (home), Task 2 (index/route) |
| 4 | "GDPR-safe by design" absolute claim | Task 1 (home), Task 2 (index/route), Task 3 (features H2) |
| 5 | Home primary CTA is "Sign in" not "Request access" | Task 1 |
| 6 | Mixed British/American spelling | Task 3, decision recorded in Task 5 |
| 7 | Over-praising competitor ("best … ever made") | Task 3 |
| 8 | Dense, comma-heavy sentences | Task 3 (features GDPR paragraph) |
| 9 | Feature/term naming drift | Task 5 (+ resources label in Task 1) |
| 10 | Competitor claim stated as permanent fact | Task 3 (calendly softening; page already shows an "Updated" date) |

## Self-review notes

- **Spec coverage:** all 10 findings map to a task (table above). No finding left unaddressed.
- **Placeholder scan:** every step contains the literal replacement text or code — no TBD/TODO/"handle edge cases".
- **Type/name consistency:** the CSS class `site-header__cta`, the href `/request-access` (no trailing slash — SPA route), and the helper name `siteHeader()` are used identically in `article-build.lib.mjs` and `article-build.lib.spec.ts`. The home-component `a.cta` selector and `/request-access` href match between `home.component.ts` and `home.component.spec.ts`.
- **Ordering:** Tasks 1–5 touch disjoint files (home component / index+route / content bodies / generator+spec / new doc), so they can run in any order or in parallel; the resources-label bullet in Task 1 and the doc in Task 5 are the only cross-reference and do not conflict.
```
