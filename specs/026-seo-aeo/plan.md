# Implementation Plan: SEO & AEO Discoverability

**Branch**: `026-seo-aeo` | **Date**: 2026-06-22 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/026-seo-aeo/spec.md`

## Summary

Add the SEO/AEO surface for Cadence's **public** entry while **guaranteeing** every token-bearing and authenticated page stays out of every search index and answer engine. Concretely: a new public **home page** at `/` (the canonical indexable entry; the authenticated shell relocates to `/app`), a build-time-injected set of static crawl-control files (`robots.txt`, `sitemap.xml`, `llms.txt`), static structured data (JSON-LD: Organization, SoftwareApplication, WebSite, FAQPage) + social-preview metadata baked into `index.html`, and a small runtime `SeoService` that sets per-route title/description/canonical/robots from route data with a **deny-by-default `noindex,nofollow`** for any route not explicitly marked public.

**Technical approach — no SSR.** The frontend is a static SPA on Cloudflare Pages (Principle IV) and `@angular/ssr` is **not installed** (adding it is a new build dependency + a network fetch, conflicting with Principle X and the project's consistent "no new frontend dependency" posture). Instead, the home page's primary descriptive content and all machine-readable metadata (meta description, canonical, OG/Twitter, JSON-LD) are authored **statically in `index.html`** so a crawler/answer-engine that does not execute scripts reads them directly (FR-017/SC-005). Per-route behavior (e.g. `noindex` on token pages) is applied at runtime via the Angular `Meta`/`Title` services for JS-executing crawlers (defense-in-depth), while `robots.txt` is the primary crawl barrier (deny-by-default: `Allow: /$` + assets, `Disallow: /`). This is **frontend + CI only — no backend, no MongoDB, no new collection, no Mongock changeset.**

## Technical Context

**Language/Version**: TypeScript 5.4 / Angular 17.3 (frontend only). No backend change (Java 21 / Spring Boot 3.3.5 untouched).
**Primary Dependencies**: Angular standalone + Angular `Meta`/`Title` (`@angular/platform-browser`, already present), Angular Router. **No new runtime or build dependency.** Test/audit: `axe-core` + `@lhci/cli` (already devDependencies from F14). **No `@angular/ssr`, no `@angular/platform-server`, no prerender package** (see research.md D1).
**Storage**: N/A — no database interaction. Static assets only (`robots.txt`, `sitemap.xml`, `llms.txt`, an OG image), served by Cloudflare Pages.
**Testing**: Jasmine (frontend unit — `SeoService`, route-SEO inventory, home component, no-token assertions) + the existing axe-core harness (home page WCAG) + `@lhci/cli` (home route Lighthouse). CI bash scan extended to assert the built `robots.txt`/`sitemap.xml`/`llms.txt`/`index.html` carry no token/authenticated URL and the required directives are present.
**Target Platform**: Static SPA on Cloudflare Pages CDN, same-origin with the Fly backend (unchanged topology).
**Project Type**: Web application — this feature touches **only** `frontend/` + `.github/workflows/ci.yml`.
**Performance Goals**: Public home page Lighthouse Performance >= 85 (mobile), reusing the F14 LHCI harness with a new home-route target. No regression to the candidate-page budgets.
**Constraints**: Pure static CDN (no per-request server rendering — Principle IV). Zero token/PII leakage into any SEO artifact. Existing CSP + `Referrer-Policy: no-referrer` + header contract preserved (FR-018/FR-019/SC-010). Absolute URLs derive from a build-time-injected public origin (the `CADENCE_API_URL` injection precedent).
**Scale/Scope**: One new public page + ~3 static files + 1 runtime service + a contained routing relocation (`/` ↔ `/app`) + CI scan. Indexable surface = exactly one URL (`/`); login and all app/token routes are non-indexable.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Gate | Question | Result |
|---|---|---|
| **C1** | Within MVP scope (spec §11)? | **PASS (justified).** SEO/AEO is not a §11 product capability, but it is **not a deferred item** either (it is absent from the §I deferral table). §I's own rationale states the goal is "a public, working MVP" — a public MVP must be **discoverable**. This is a cross-cutting production-readiness concern (like CI, headers, accessibility), directly requested by the project owner. No deferred capability is started. |
| **C2** | New service, queue, or replica? | **PASS.** Static files on the existing Cloudflare Pages deployment. No new process, broker, cache, or replica. |
| **C3** | Exposes candidate PII to unauthorized roles? | **PASS — and is the feature's central control.** The dominant risk is the inverse: accidentally indexing a token page = exposing candidate data to the entire public. Mitigated by (a) `robots.txt` deny-by-default, (b) runtime `noindex,nofollow` on every non-public route, (c) **no token in any canonical/sitemap/JSON-LD/OG field** (a token is a runtime query param, never emitted into any artifact), (d) sitemap lists only `/`. Verified by unit tests + a CI scan (SC-002/SC-003). |
| **C4** | Dependency outside the fixed stack? | **PASS.** Zero new dependencies. `@angular/ssr`/prerender was the considered SOTA alternative and was **rejected** (research.md D1) specifically to keep C4/C7 clean and respect Principle IV/X. |
| **C5** | New/modified Windows scripts contain non-ASCII? | **PASS (committed).** Any new `.ps1` (the build-time origin-injection helper, if added as a script) MUST be pure ASCII + CRLF and byte-scanned (Principle V). The static SEO files are LF. |
| **C6** | Multi-role sub-agent review (>=3 roles) scheduled? | **PASS.** Two-loop review scheduled: **Security** (token/PII leakage into artifacts, header/CSP non-regression), **Frontend** (routing relocation, SeoService correctness, a11y), **QA** (deny-by-default coverage, no-token assertions, CI scan non-vacuity). |
| **C7** | Downloads a build tool/runtime/CLI distribution? | **PASS.** No new tooling. Uses the already-installed Angular CLI / npm (node v24.13.0 present). The OG image is authored, not fetched. No `npm install` of a new package. |

**§VIII Security & Privacy**: No new logging; the load-bearing control is no-token-in-artifacts (above). No PII is involved on the public home page. **§IX Candidate-Experience**: the home page is public marketing (not a candidate token page), but is held to WCAG 2.2 AA + Lighthouse >= 85 as good practice for the public face; **token/candidate pages are unchanged except for an added runtime `noindex`** — their existing no-login, no-referrer, CSP, and focus behavior must not regress (verified). **§VII Test-First**: each user story gets at least one acceptance test (SeoService per-route meta, deny-by-default inventory, robots/sitemap CI scan, home a11y/Lighthouse).

**Result: ALL GATES PASS. No Complexity Tracking entries required.**

### Multi-role review (round 1, planning) — findings applied

Per Constitution C6, Security + Frontend/Angular + QA sub-agents reviewed spec/plan/tasks against real source. No gate flipped; the design (no-SSR static shell, deny-by-default, no-token-in-artifacts) was confirmed sound. Material findings, all folded into tasks.md/research.md/contracts:

- **robots.txt over-broad allows** (Security B1/B2) → narrowed allow-list (`/$`, `/favicon.ico`, `/assets/`, `/*.js$|css$|woff2$`), CI asserts `/status` matches no `Allow:` (T014/T029, contract + research D2).
- **Missing wildcard/404 route** (Frontend B2) → new `{ path: '**' }` → `NotFoundComponent` (noindex), else unknown URLs render the indexable home (T008).
- **Incomplete `'/'`-navigation retarget** (Security S2, Frontend B1, QA SF-4) → all 5 sites enumerated (login, accept-invite, setup-wizard ×2, not-authorized) + grep-guard test (T009).
- **Origin-injection unwired** (Frontend B3) → wired into CI `lighthouse`/`deploy` jobs + `deploy-frontend.ps1`, `CADENCE_PUBLIC_ORIGIN`/`CADENCE_PUBLIC_ENV` sourced (T004).
- **No-JS content (SC-005) + content-language (FR-007) untested** (QA B1/B2) → CI asserts home copy present in `<app-root>` + `<html lang>` (T013/T029).
- **Canonical-link DOM removal + initial-navigation timing** (Frontend S1/S2) → SeoService manages `<link rel=canonical>` via DOM, applies on initial URL (T006, contract).
- **Home must not bounce anonymous crawlers to `/login`** (Security S3, Frontend S3) → anon-first render, background `me()` (T011/T012).
- **CI scan hardening** (Security S5, QA) → non-vacuity guards, no-leftover-placeholder, og:image-exists, XML parse, sentinel build (T029).
- **Env opt-in** (Security N2) → index only when `CADENCE_PUBLIC_ENV='production'`, else blanket noindex (T026, research D6).
- **Accepted residual** (no-SSR): a non-compliant + no-JS crawler may fetch a token URL and receive the shell (no token/PII; canonical → `/`); mitigations layered (research D2). Documented, not silently dropped.

## Project Structure

### Documentation (this feature)

```text
specs/026-seo-aeo/
├── plan.md              # This file
├── research.md          # Phase 0 output — SSR-vs-static decision + SOTA SEO/AEO patterns
├── data-model.md        # Phase 1 output — RouteSeo metadata model + static-artifact schemas
├── quickstart.md        # Phase 1 output — how to verify the feature end-to-end
├── contracts/           # Phase 1 output — static-file + SeoService contracts
│   ├── robots.txt.md
│   ├── sitemap.xml.md
│   ├── llms.txt.md
│   ├── structured-data.md
│   └── seo-service.md
└── checklists/
    └── requirements.md  # Created by /speckit.specify
```

### Source Code (repository root) — frontend + CI only

```text
frontend/
├── src/
│   ├── index.html                      # MODIFIED: static <title>/description/canonical/OG/Twitter,
│   │                                   #   JSON-LD (Organization/SoftwareApplication/WebSite/FAQPage),
│   │                                   #   default <meta name="robots"> handling, static home content
│   │                                   #   inside <app-root> for no-JS crawlers. Keeps existing
│   │                                   #   referrer meta + favicon.
│   ├── robots.txt                      # NEW static asset (build-time origin injected) — deny-by-default
│   ├── sitemap.xml                     # NEW static asset (build-time origin injected) — lists only "/"
│   ├── llms.txt                        # NEW static asset — AEO product summary, public links only
│   ├── _headers                        # MODIFIED: add X-Robots-Tag for non-prod; keep CSP/Referrer-Policy
│   ├── assets/
│   │   └── og-cadence.png              # NEW default social-share / OG image (authored brand asset)
│   └── app/
│       ├── app.routes.ts               # MODIFIED: new public "" -> HomeComponent; shell -> "app";
│       │                               #   attach `data.seo` to every route (deny-by-default)
│       ├── core/seo/
│       │   ├── seo.service.ts          # NEW — sets Title/description/canonical/robots/OG per route data
│       │   ├── seo.service.spec.ts     # NEW — per-route meta + deny-by-default + no-token tests
│       │   ├── route-seo.model.ts      # NEW — RouteSeo interface + PUBLIC/PRIVATE presets
│       │   └── route-seo-inventory.spec.ts # NEW — asserts every route is public-or-noindex (FR-004/SC-009)
│       ├── features/home/
│       │   ├── home.component.ts        # NEW — public marketing home (anon-first; background me() -> /app)
│       │   └── home.component.spec.ts   # NEW — content, a11y (axe), anon-no-bounce, signed-in redirect, CTA
│       └── features/not-found/
│           └── not-found.component.ts   # NEW — wildcard `**` 404 (noindex) so unknown URLs aren't the indexable home
│
├── scripts/ or build hook                # Origin-injection for robots/sitemap/llms/index (mirrors the
│                                         #   existing CADENCE_API_URL Node one-liner; ASCII if .ps1)
└── lighthouse/                           # F14 harness — add the home route to the LHCI config

.github/workflows/ci.yml                  # MODIFIED: SEO artifact scan (no token/auth URL in any artifact;
                                          #   required directives present; non-prod all-noindex)
```

**Structure Decision**: Web application, but this feature is **frontend-only** plus a CI step. All new code lives under `frontend/src/app/core/seo/` (the runtime SEO mechanism), `frontend/src/app/features/home/` (the new public page), and static files under `frontend/src/`. No `backend/` change, no `domain/`, `repository/`, `service/`, `config/migration/` touch, and no Mongock changeset — consistent with the spec's "no new collection/index/changeset". The authenticated shell relocates from `''` to `app` with login post-auth and any hardcoded `/` navigations retargeted to `/app` (the one contained routing change, covered by acceptance tests).

## Complexity Tracking

> No Constitution Check violations. Table intentionally empty.

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| — | — | — |
