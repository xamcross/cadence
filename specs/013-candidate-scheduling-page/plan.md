# Implementation Plan: Candidate Scheduling Page (UX) (F14)

**Branch**: `013-candidate-scheduling-page` | **Date**: 2026-06-16 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/013-candidate-scheduling-page/spec.md`

## Summary

F14 hardens the candidate self-scheduling slot-picker that F13 shipped functional-only (`frontend/src/app/features/schedule/`) into the candidate-experience quality bar mandated by constitution §IX: WCAG 2.2 AA (axe-core **0 violations**, blocking), mobile-first (375/768/1280 px, ≥44 px touch targets, no horizontal scroll), Lighthouse Performance **≥ 85** + Largest Contentful Paint **≤ 2 s** on the real `/schedule` route (blocking), localization-ready (all strings externalized; RTL/long-string tolerant), humane token-state presentation (distinct expired vs indistinguishable invalid; no oracle), and token-leakage/no-cache hardening (referrer suppression, no third-party assets, memory-only token).

F14 is **frontend + verification-harness only** — it adds **no new backend behaviour, no collection, no index, no changeset, and no new runtime/component dependency**. It consumes the existing F13 candidate contract (`GET/POST /api/candidate/scheduling/{token}`) unchanged. The work is: (1) rebuild the `schedule` component/styles to the §IX bar with full state coverage and focus/live-region management; (2) add an `axe-core` devDependency and per-state accessibility specs in the existing Karma/Jasmine harness (deliberately **not** Playwright — that would download Chromium, violating Principle X); (3) point Lighthouse CI at the candidate route via a tiny CI-only Node static+stub server and add the LCP gate; (4) add static security config (`index.html` referrer meta + Cloudflare Pages `_headers` + self-hosted fonts).

The §II demonstrable leg is the existing F13 browser→Spring→Mongo flow; F14's deliverable is that same flow's candidate page passing the now-blocking accessibility/performance/localization/security gates on the real route.

## Technical Context

**Language/Version**: TypeScript 5.4 / Angular 17.3 (frontend). No backend change (Java 21 / Spring Boot 3.3.5 untouched).
**Primary Dependencies**: Angular standalone components + Angular CDK/Material 17.3 (CDK a11y `LiveAnnouncer`/`FocusTrap` where it earns its weight), Angular i18n / `@angular/localize` (already present). **One new frontend devDependency: `axe-core`** (test/audit tool — not a UI component library, so outside the frontend Dependency-Policy ban; same class as the constitution-named Cypress/Playwright test tooling). `@lhci/cli` + `serve` already used in CI. **No new backend or runtime dependency; no Playwright (Principle X — no Chromium download).**
**Storage**: None. No collection, index, or Mongock changeset. Consumes F13's read/confirm contract only.
**Testing**: Jasmine + Karma + **ChromeHeadless** (already configured) — per-state `axe-core` audits (tags `wcag2a/2aa/21a/21aa/22aa`), WCAG-2.2-non-automatable component tests (target-size 44 px, focus-not-obscured, focus-appearance, no-CAPTCHA absence, consistent-help, focus-management + `aria-live` politeness), RTL/long-string overflow test, localization-marker check. Lighthouse CI (mobile preset, 4G throttle) against `/schedule?token=lighthouse-demo` via the CI static+stub server, asserting performance ≥ 0.85 and LCP ≤ 2000 ms. A documented manual accessibility audit (SC-002a) recorded at close. No new E2E framework.
**Target Platform**: Cloudflare Pages (frontend SPA) + Fly.io single Machine (backend, unchanged). Topology unchanged.
**Project Type**: Web application (Angular SPA front; existing Spring Boot back — not modified).
**Performance Goals**: Lighthouse Performance ≥ 85 and LCP ≤ 2 s under the mobile-preset 4G throttle on the candidate route (SC-001); candidate completes booking < 2 min on mobile (SC-003, manual target).
**Constraints**: axe-core 0 violations across all enumerated states (SC-002, blocking); no horizontal scroll + ≥44 px targets at 375/768/1280 px (SC-004); keyboard-only completion with visible focus (SC-005); DST-correct local-zone rendering (SC-006); 100% externalized strings + RTL/long-string no-overflow (SC-007); token-state messages correct/oracle-safe and never a raw error (SC-008); zero candidate PII / token in logs (SC-009, F13 scan not regressed); no third-party token leakage + no client-side token persistence (SC-010). Zero-download (no Playwright/Chromium). No new service/queue/replica.
**Scale/Scope**: Rebuild one standalone candidate component + its styles + spec; one new devDependency (`axe-core`); modify `lighthouserc.json` (route + LCP assertion); add `frontend/lighthouse/serve-with-stub.mjs` (CI-only); add `index.html` referrer meta + `frontend/public/_headers`; verify/self-host fonts; extend the CI `lighthouse` job to use the stub server. Possible (not anticipated) gated additive candidate-safe response field — default **none**.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Gate | Question | Result |
|---|---|---|
| **C1** | Within MVP scope (spec §11)? | ✅ The candidate scheduling page is the §11 Flow A1 candidate surface and the direct subject of constitution §IX (Candidate-Experience First: no-login, WCAG 2.2 AA, mobile-first, < 2 s/Lighthouse ≥ 85). Backlog F14, Tier 1 P1. |
| **C2** | New service, queue, or replica? | ✅ No. No deployed service; the Lighthouse CI stub is a test fixture in the CI job, not a runtime service. No collection/index/changeset. |
| **C3** | Exposes candidate PII to unauthorized roles? | ✅ No — improves the posture. Page is public-by-token, renders **times only** (no participant identities, no `locationText`, no internal ids beyond the opaque slot id/token), adds referrer suppression + no third-party assets so the URL token cannot leak off-origin. No RBAC change. |
| **C4** | Dependency outside the fixed stack? | ✅ One frontend **devDependency** `axe-core` — a test/audit tool (not a UI component library; the Dependency-Policy ban targets PrimeNG/NG-ZORRO-style libraries), recorded here per policy; it is the engine the backlog/DoD name for the WCAG scan. No new runtime, component, or backend dependency. |
| **C5** | New/modified Windows scripts with non-ASCII? | ✅ No new/modified `.ps1`/`.cmd`/`.bat`. The CI stub is a Node `.mjs` (LF) and `_headers` is a static config (LF) — both outside the Principle V script rule. |
| **C6** | Multi-role sub-agent review (≥3) scheduled? | ✅ Spec reviewed (4 roles). This plan is reviewed in this command (below, ≥3 roles). Implementation review at task close. |
| **C7** | Downloads a build tool/runtime/CLI? | ✅ No — and this is a deliberate design choice: **no Playwright** (would download Chromium). Reuse the installed Chrome via Karma ChromeHeadless and the already-present lhci/lighthouse. `axe-core` is a pure-JS npm package via normal `npm ci`. |

**Initial gate: PASS.** No Complexity Tracking entries required.

**Post-Phase-1 re-check: PASS** — design adds zero backend/runtime/component dependency, zero persisted data, and reuses the F13 contract; the only additions are a JS test/audit devDependency, CI-config + a CI-only stub, and static security headers. See Phase 1 artifacts.

## Project Structure

### Documentation (this feature)

```text
specs/013-candidate-scheduling-page/
├── plan.md              # This file
├── research.md          # Phase 0 — D1 axe-in-Karma (no Playwright), D2 Lighthouse-on-route via CI stub, D3 WCAG-2.2 non-automatable, D4 i18n/RTL, D5 token-leakage/no-cache, D6 responsive/reduced-motion, D7 §II, D8 constitution posture
├── data-model.md        # Phase 1 — no persisted entity; consumed F13 contract + client view-state machine + explicit non-changes
├── quickstart.md        # Phase 1 — run/gates + SC-002a manual audit checklist + security checks + DoD
├── contracts/
│   └── candidate-page-contract.md  # view-state contract (A), CI quality-gate contract (B), security-headers/asset contract (C)
├── checklists/
│   └── requirements.md  # spec quality + multi-role spec-review notes (already present)
└── tasks.md             # Phase 2 (/speckit.tasks — NOT created here)
```

### Source Code (repository root)

```text
frontend/
├── package.json                                  # MODIFIED add devDependency axe-core (regenerate + commit package-lock.json in the SAME change, or `npm ci` fails "lock out of sync")
├── package-lock.json                             # MODIFIED regenerated with axe-core
├── angular.json                                  # MODIFIED add assets glob { "glob": "_headers", "input": "src", "output": "/" } so _headers reaches the served root
├── src/
│   ├── index.html                                # MODIFIED add <meta name="referrer" content="no-referrer">
│   ├── _headers                                  # NEW Cloudflare Pages root config — Referrer-Policy: no-referrer, full CSP (see contracts §C), X-Content-Type-Options: nosniff (deployed via the angular.json glob above; NOT public/)
│   ├── styles.scss                               # MODIFIED global a11y (focus-visible, prefers-reduced-motion); do NOT add a CDN font (system fonts today)
│   ├── app/features/schedule/
│   │   ├── schedule.component.ts                  # MODIFIED rebuild to §IX bar: full state machine, focus mgmt, aria-live politeness (no double-announce), ≥44px, local-zone/DST labels, RTL-safe, reduced-motion, retryable-error + empty states
│   │   ├── schedule.component.scss               # NEW (or inline) mobile-first 375/768/1280 styles, ≥44px targets, logical properties, prefers-reduced-motion
│   │   ├── schedule.component.spec.ts            # MODIFIED async per-state axe audits (await + appendChild fixture to document.body), focus/live-region, ≥44px target-size, no-CAPTCHA absence, consistent-help, RTL/long-string overflow, no-storage, no-console-token
│   │   └── schedule.service.ts                   # (unchanged — consumes F13 contract as-is)
│   └── testing/axe.ts                            # NEW helper wrapping async axe.run with the WCAG 2.2 AA tag set (wcag2a/2aa/21a/21aa/22aa) + DOM attach/detach
└── lighthouse/serve-with-stub.mjs                # NEW CI-only static+stub server with SPA fallback (serves dist + canned candidate open-state) — Node, LF

lighthouserc.json                                 # MODIFIED url -> /schedule?token=lighthouse-demo; numberOfRuns:3 + median; add LCP (warn->error 2000ms) + (secondary) accessibility assertions
.github/workflows/ci.yml                          # MODIFIED lighthouse job uses serve-with-stub.mjs instead of bare `serve`
```

**Structure Decision**: Standard Cadence frontend layout (constitution Reference Source Layout, `features/`). The candidate page stays a top-level **un-guarded** standalone route (`schedule`, already wired in `app.routes.ts`). No backend module touched; no `scripts/` `.ps1` added. The Lighthouse stub and `_headers` are build/CI artefacts, not production services.

## Multi-role plan review (2026-06-16) — verdict: APPROVE-WITH-FIXES (all applied)

Reviewers (4, per C6 / Principle VI and the user's request): Frontend (Angular) Lead, Accessibility/UX specialist, DevOps/CI Lead, Security/GDPR Lead — each verified claims against the real repo (`angular.json`, `ci.yml`, `lighthouserc.json`, `package.json`, `index.html`, `styles.scss`, the F13 component). One BLOCKER + several should-fixes found and folded into the artifacts before task generation.

- **Security/GDPR (1 BLOCKER + 1 should-fix, both fixed)**: **BLOCKER** — `frontend/public/_headers` would never deploy: the `application` builder's `assets` are only `["src/favicon.ico","src/assets"]` (no `public/` glob), so Cloudflare Pages would serve **no** `Referrer-Policy`/CSP/`nosniff` and the URL-token leak defence would silently vanish. **Fixed**: file moved to `frontend/src/_headers` + an explicit `angular.json` assets glob `{glob:"_headers",input:"src",output:"/"}` so it lands at the served root; the built `dist/cadence/browser/_headers` must be verified. **Should-fix** — a bare CSP `default-src 'self'` breaks Angular's runtime-injected styles; **fixed** by pinning the full directive set (`style-src 'self' 'unsafe-inline'`, `img-src 'self' data:`, `font-src/connect-src 'self'`, `object-src 'none'`, `base-uri 'self'`, `frame-ancestors 'none'`), validate against a real build. Confirmed: no font CDN today (system fonts), no F13 invariant weakened; added a console-no-token assertion.
- **Accessibility (should-fixes, fixed)**: axe's `target-size` rule is **not** in the `wcag2a/2aa/21a/21aa/22aa` tag set, so it does NOT run with `runOnly` — the "axe backstop" for 2.5.8 was illusory; **fixed**: the explicit ≥44 px component test is the sole automated check (correctly above the 24 px floor). Focus-Not-Obscured (2.4.11) / Focus-Appearance (2.4.13) reassigned to **manual-authoritative** (Karma layout math unreliable) — advisory only, not a hard gate. Noted the focus+live-region **double-announcement** risk and the fix. Confirmed the axe tag set and the assertive/polite split are correct; SC-002a checklist complete.
- **Frontend/Angular (should-fixes, fixed)**: `axe.run` is **async** — specs must `await` after `detectChanges()`/`whenStable()` and **attach the fixture to `document.body`** or contrast rules silently no-op (real change from the current synchronous specs); folded into D1/D3 and the spec-file note. Confirmed `@angular/cdk` (LiveAnnouncer/FocusTrap) and `@angular/localize` are already present and the rebuild scope is realistic. **Reframed D5 font task**: verified `styles.scss` is empty and there is **no CDN font today** — the task is "don't introduce one" + a CI grep guard, not "find and remove."
- **DevOps/CI (should-fixes, fixed)**: an absolute LCP ≤ 2000 ms `error` gate on shared runners flakes (`numberOfRuns` defaults to 1); **fixed**: `numberOfRuns:3` + median and introduce LCP at `warn`/margin → promote to hard `error` once the CI median is known. Added the **SPA-fallback** requirement to the stub server (`npx serve -s` gave it for free). Flagged that `package-lock.json` MUST be regenerated with `axe-core` in the same change or `npm ci` fails. Confirmed `dist/cadence/browser` path, query-string URL handling, `ubuntu-latest` Chrome availability, and `lighthouse needs: frontend-test` ordering are all correct; no Playwright/Chromium download (C7 honoured).

No remaining blocking items. Residual items intentionally left to `tasks.md`: the exact `lighthouserc.json` assertion block + the warn→error LCP promotion step; the `angular.json` glob + a built-output `_headers` verification task; the CSP real-build validation; the CI `googleapis`/`gstatic` grep guard; the async-axe spec scaffolding helper.

## Complexity Tracking

No constitution violations — table intentionally empty.

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| (none) | — | — |
</content>
