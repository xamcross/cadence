# Phase 0 Research: Candidate Scheduling Page (UX) (F14)

F14 is a **frontend + verification-harness** feature. It hardens the candidate slot-picker that F13 shipped functional-only (`frontend/src/app/features/schedule/`) and turns the advisory accessibility/performance/localization checks into blocking gates. No new backend behaviour is required: the F13 candidate contract (`GET/POST /api/candidate/scheduling/{token}`) already returns times-only slots, the booked confirmation, and the 410/400/429 outcomes. Each decision below resolves a "how do we verify / how do we present" question with the existing stack and the zero-download rule.

---

## D1 — Accessibility audit mechanism: axe-core in the existing Karma/Jasmine harness (NOT Playwright)

**Decision**: Add `axe-core` as a frontend **devDependency** and run it from the existing Jasmine component specs in the already-configured Karma + ChromeHeadless runner (`npx ng test`, the CI `frontend-test` job). Each enumerated page state (loading, slot list/open, booking success, expired, invalid, rate-limited, conflict/error, empty) is rendered by driving the mocked `ScheduleService`, then `axe.run(fixture.nativeElement, { runOnly: ['wcag2a','wcag2aa','wcag21a','wcag21aa','wcag22aa'] })` MUST report zero violations.

**Rationale**:
- The repo's frontend test runner is **Karma + Jasmine + ChromeHeadless** (`package.json`); there is **no Playwright/Cypress** installed. Adding Playwright would run `playwright install`, which **downloads Chromium** — a direct violation of **Constitution Principle X / gate C7** (no build-tool/runtime/binary download during implementation) and the project memory rule. Karma's ChromeHeadless reuses the already-installed Chrome.
- axe-core is a pure-JS, single-package audit library (no binary), the de-facto engine behind the backlog's "axe-core: 0 violations" acceptance criterion and the constitution DoD's "WCAG 2.2 AA automated scan (axe-core)". It is a **test/audit tool, not a UI component library**, so it is outside the frontend Dependency-Policy ban on component libraries (PrimeNG/NG-ZORRO); it is the same class of test tooling the constitution already names (Cypress/Playwright for E2E). Recorded with justification in `plan.md` (C4).
- Driving states via the mocked service in a component spec is deterministic and offline (no backend needed), so the 0-violations gate runs in the existing `frontend-test` CI job with no new service.

**Alternatives considered**:
- *Playwright + @axe-core/playwright*: rejected — Chromium download violates C7/Principle X; adds a whole new CI job and E2E framework for a check the unit harness can do.
- *Lighthouse accessibility category only*: rejected — Lighthouse's a11y audit is a strict subset of axe and the backlog/DoD specifically require axe-core; we still raise the Lighthouse a11y score as a secondary signal (D2) but axe is the authoritative gate.

---

## D2 — Lighthouse must measure the real candidate route with rendered content (CI stub server)

**Problem**: The current `lighthouserc.json` collects `http://localhost:4200` (the app root → guarded shell → login), **not** `/schedule?token=...`. The Lighthouse CI job serves the static `dist/` with `npx serve` and has **no backend**, so the schedule component's `view()` call fails and the page renders the *invalid* state — the slot list is never measured.

**Decision**: In the `lighthouse` CI job, replace the bare `npx serve` with a tiny **CI-only Node static+stub server** (`frontend/lighthouse/serve-with-stub.mjs`, ~40 lines, LF, not a `.ps1` so Principle V's script rule does not apply) that (a) serves `dist/cadence/browser` **with SPA fallback** — any non-file, non-API path (e.g. `/schedule`) returns `index.html`, which `npx serve -s` did for free and a hand-rolled server must not forget — and (b) answers `GET /api/candidate/scheduling/<demo-token>` with a fixed canned **open-state** payload (a handful of future slots, times only) and canned responses for the other candidate endpoints. Point `lighthouserc.json` `url` at `http://localhost:4200/schedule?token=lighthouse-demo` (lhci passes the full query-string URL straight to Chrome — routine).

Add the gates to `lighthouserc.json`: keep `categories:performance >= 0.85` (**error**) and add `largest-contentful-paint` as `["error", { "maxNumericValue": 2000 }]` (audit id correct, value in **milliseconds**) plus a secondary `categories:accessibility` threshold. **Flakiness control (DevOps finding)**: a hard absolute LCP gate on shared GitHub runners flakes even under `throttlingMethod: simulate` (runner-CPU contention shifts modelled LCP by hundreds of ms, and `collect.numberOfRuns` currently defaults to **1** — the worst case). So set `collect.numberOfRuns: 3` with median aggregation, and introduce the LCP assertion at `warn` first (or with a modest margin, e.g. ~2300 ms) and promote to a hard `error` 2000 ms gate once a few runs establish the real median on CI; SC-001's ≤ 2 s remains the product target verified under the mobile-preset 4G throttle.

**Rationale**: Measures the actual content-bearing candidate state, reproducibly and offline. Production code stays clean (no demo/fixture mode shipped to users — the stub lives only in the CI harness). Node is already installed (no download). Keeps the single-instance/topology rules intact (the stub is a test fixture, not a deployed service).

**Alternatives considered**:
- *In-app demo mode toggled by env*: rejected — ships fixture code and a demo branch into the production bundle (and risks an accidental data path); the CI stub keeps prod code clean.
- *Run Lighthouse against the live dev backend*: rejected — not reproducible/offline in CI, needs Mongo + a seeded token; the canned stub is deterministic.

---

## D3 — WCAG 2.2 criteria axe cannot detect: explicit component tests + documented manual audit (SC-002a)

**Decision**: For each WCAG 2.2-AA criterion that automated tooling cannot reliably assert, add an **explicit Jasmine component test** where feasible and a **documented manual audit step** in `quickstart.md` otherwise:
- **2.5.8 Target Size (min)** / 44 px (FR-003, FR-020): component test asserting the computed min-height/width of slot buttons and action controls is ≥ 44 px. **Note (accessibility finding)**: axe's `target-size` rule is a best-practice/experimental rule **not** included in the `wcag2a/2aa/21a/21aa/22aa` tag set, so with `runOnly: [those tags]` it does **not** run — there is no axe backstop. The explicit ≥ 44 px component test is the sole automated check (and it exceeds 2.5.8's 24 px floor, which is correct).
- **2.4.11 Focus Not Obscured** (FR-021): a component/layout test can attempt a rect check, but Karma/headless layout+scroll math is unreliable, so this criterion's **authoritative** verification is the documented manual audit step; any component test is advisory and MUST NOT be a hard gate.
- **2.4.13 Focus Appearance** (FR-021): CSS `:focus-visible` indicator with sufficient area/contrast; component test asserts a focus-visible style is applied; manual contrast check documented.
- **3.3.8 Accessible Authentication** (FR-022): an **absence** test — the rate-limited and all states render no CAPTCHA/cognitive-test element; the token in the URL remains the sole auth.
- **3.2.6 Consistent Help** (FR-023): test that the "contact your recruiter" affordance text/placement is identical across the expired/invalid/empty/rate-limited states (shared template fragment).
- **Focus management on transitions** (FR-024): tests asserting that after load→slots, confirm→success, conflict/error, and expiry, focus is moved programmatically to the new primary heading/message; and live-region politeness (`aria-live="assertive"` for errors/conflicts, `polite` for informational) is set. **Avoid double-announcement (accessibility finding)**: do not both move focus to a heading carrying the status text *and* announce the same text via a live region — let the live region carry the status text and move focus to a heading that does not duplicate it (or announce only when focus is not moved to a text node).

**axe test mechanics (frontend finding — applies to D1 and these tests)**: `axe.run(...)` is **async** (Promise), so each spec must `async`/`await` (or `fakeAsync`) and call `fixture.detectChanges()` (and `await fixture.whenStable()`) before auditing each state; and the fixture root must be **attached to the live DOM** (`document.body.appendChild(fixture.nativeElement)` in setup, removed in `afterEach`) or axe's colour-contrast/visibility rules silently no-op on the detached `TestBed` root. The existing synchronous specs change shape accordingly.

**Rationale**: axe catches contrast, names, roles, and (partially) target-size, but cannot judge focus-not-obscured, focus-appearance adequacy, accessible-authentication, consistent-help, or correct focus movement. SC-002a explicitly requires these be verified outside the axe gate so the 0-violations result is not false confidence (the accessibility reviewer's spec blocker).

**Alternatives considered**: relying on axe alone — rejected per the spec's SC-002a and the reviewer finding.

---

## D4 — Localization readiness verification (FR-012 / SC-007)

**Decision**: (a) Verify all candidate-facing strings are externalized by running `ng extract-i18n` and asserting the schedule feature's user-visible text appears as extracted messages (the F13 component already uses `i18n`/`$localize` markers); add a lightweight check (test or grep in the frontend test step) that the `schedule.component.ts` template contains no unmarked display text. (b) Tolerate long translations and RTL: use CSS **logical properties** and flexible (no fixed-width/clamped) text containers, and add a component test that renders with `dir="rtl"` and artificially long pseudo-localized strings and asserts **no horizontal overflow** (`scrollWidth <= clientWidth`) and no truncation of critical content. Full additional-language translation stays **deferred** (English-only MVP per F03 / constitution §IX).

**Rationale**: SC-007 requires "100% externalized" + a long-string/RTL pseudo-localization check without overflow. Angular i18n is the mandated mechanism (already a dependency: `@angular/localize`). Logical properties make RTL correct without a second stylesheet.

**Alternatives considered**: building a full pseudo-locale bundle — heavier than needed for MVP; the in-test long-string + `dir=rtl` assertion gives the overflow guarantee without shipping a second locale.

---

## D5 — Token-leakage & no-cache hardening (FR-025 / FR-026 / SC-010)

**Decision**:
- **Referrer suppression**: add `<meta name="referrer" content="no-referrer">` to `index.html` **and** a Cloudflare Pages `_headers` file setting `Referrer-Policy: no-referrer`, a fully-specified `Content-Security-Policy` (below), and `X-Content-Type-Options: nosniff`. The bearer token lives in the URL query string, so the `Referer` header must never carry it off-origin.
  - **`_headers` placement (BLOCKER fix — security finding)**: `angular.json` uses the `application` builder with `assets: ["src/favicon.ico", "src/assets"]`, `outputPath: dist/cadence` (served root `dist/cadence/browser`). There is **no `public/` glob**, so a file at `frontend/public/_headers` is **never copied to the output** and Cloudflare Pages would serve no headers (silent loss of Referrer-Policy/CSP/nosniff). Cloudflare requires `_headers` at the **site root**. Fix: keep the file at `frontend/src/_headers` and add an explicit assets glob to `angular.json` so it lands at the output root: `{ "glob": "_headers", "input": "src", "output": "/" }`. Verify the built `dist/cadence/browser/_headers` exists.
  - **CSP must be fully specified (security finding)**: a bare `default-src 'self'` breaks the Angular SPA (runtime-injected component/Material `<style>` blocks need `style-src`). Use at minimum: `default-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'self'; frame-ancestors 'none'`. Validate against a real production build before making it a gate.
- **No third-party assets (reframed — frontend+security finding)**: verified against the repo today — `index.html` has no CDN `<link>`, `styles.scss` is effectively empty, `angular.json` styles = just `src/styles.scss`, and no `@font-face`/`googleapis`/`gstatic`/Roboto/Material-Icons reference exists; the app renders with **system fonts**, so there is **nothing to remove**. The task is therefore "**do not introduce** a third-party font/asset" — if the F14 rebuild adds Material components/icons, bundle the icon font locally (never the `fonts.googleapis.com` CDN). Add a CI guard (grep the built `index.html`/sources for `googleapis`/`gstatic`) so a future Material-Icons import cannot silently reintroduce the leak.
- **No client-side token persistence**: the component keeps the token in memory only (the F13 component already does — token read from the query param into a field, never written to `localStorage`/`sessionStorage`); add a test asserting no storage write. Re-resolve state on every navigation (the component reads the token and calls `view()` on `ngOnInit`), so a back/forward-cache restore shows the current state, not a stale bookable picker. Also assert the error/retry paths never `console.error` the response URL/token (ties to FR-019/SC-009). Token-in-URL exposure to browser history/autocomplete is inherent to the F13 token contract and is out of F14's presentation scope (noted, not closed here).
- **No-store**: the F13 backend already sets `Cache-Control: no-store` on the candidate view/confirm responses; F14 must not regress it (asserted by a contract check) and must not cache responses in the SPA.

**Rationale**: The page owns the presentation layer where token-in-URL leakage actually happens (referrer, third-party assets, client storage, bfcache). These are the security reviewer's should-fixes; all are achievable with static config + a CSS/asset check + a memory-only token (no new dependency).

**Alternatives considered**: moving the token out of the query string (e.g., to a fragment or POST body) — rejected for F14: that is an F13 token-contract change, out of F14's presentation scope; referrer suppression + no third-party assets closes the leak without altering the contract.

---

## D6 — Responsive layout, touch targets, reduced motion (FR-001..FR-004)

**Decision**: Mobile-first layout authored for 375 px and enhanced at 768 px / 1280 px breakpoints; slot controls and buttons styled to a minimum 44 × 44 px touch target; all interactive text/contrast meeting AA; honor `@media (prefers-reduced-motion: reduce)` for any transition and provide a **non-animated** loading affordance (text/spinner that does not continuously animate) when reduced motion is requested. Keep the bundle lean (the candidate route is already a lazy-loaded standalone component) to hit the LCP ≤ 2 s / Lighthouse ≥ 85 budget; avoid heavy Material components on the candidate page where a lighter native control suffices.

**Rationale**: Directly implements §IX (mobile-first, ≥ 44 px, < 2 s on 4G, Lighthouse ≥ 85) and the spec FRs. Lazy-loading + minimal dependencies is the proven lever for the mobile performance budget.

**Alternatives considered**: a full Angular Material card/list build — acceptable but heavier; we use Material/CDK only where it earns its weight (e.g., focus-trap/live-announcer from CDK a11y), preferring lightweight semantic HTML for the slot list to protect the performance budget.

---

## D7 — §II End-to-end & demonstrable leg

**Decision**: F14 adds **no new backend path**; the candidate page is already wired browser → Spring → Mongo from F13. The §II obligation is met by that existing live flow; F14's deliverable is the **hardened** page plus the now-**blocking** verification: axe-core 0 violations across all states (D1/D3), Lighthouse ≥ 85 + LCP ≤ 2 s on the real `/schedule` route (D2), the localization/RTL checks (D4), and the token-leakage/no-cache controls (D5). The demonstrable leg: open the link on a 375 px device → fast, accessible slot pick → confirm → confirmation — keyboard-only and screen-reader paths included.

**Rationale**: Honors §II without re-implementing scheduling; the value F14 adds is the candidate-experience quality bar, verified by gates, on the real route.

---

## D8 — Constitution / topology / dependency posture

- **C2 (no new service/queue/replica)**: none. The Lighthouse CI stub (D2) is a test fixture in the CI job, not a deployed service.
- **C4 (dependency)**: one new frontend **devDependency** — `axe-core` (test/audit tool; justified above). `@lhci/cli` + `serve` are already used in CI. No new runtime/component dependency; no backend dependency.
- **C7 / Principle X (zero download)**: deliberately **no Playwright** (would download Chromium); reuse installed Chrome via Karma ChromeHeadless and the already-present lhci/lighthouse Chrome. `axe-core` is a pure-JS npm package fetched by the normal `npm ci` (not a tool/runtime binary download).
- **§IV topology**: unchanged — no production service/header change alters the single-Machine + Atlas + Cloudflare-Pages model; `_headers` is a static Cloudflare Pages config file.
- **§V**: no new `.ps1`/`.cmd`/`.bat`; the CI stub is a Node `.mjs` (LF) outside the Principle V script rule; the `_headers` file is LF.

All Constitution Check gates pass (see `plan.md`). No Complexity Tracking entries.
</content>
