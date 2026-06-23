---
description: "Task list for 031-terms-privacy-notice"
---

# Tasks: Terms & Conditions and Privacy Notice

**Input**: Design documents from `/specs/031-terms-privacy-notice/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/ (legal-pages, seo-aeo-artifacts, in-app-links)
**Tests**: INCLUDED — Constitution VII (test-first / acceptance-driven) is mandatory; the plan's D11 defines the test strategy.

**Worktree**: `C:/Users/xamcr/Cadence-terms-privacy` (branch `031-terms-privacy-notice`, based on PR 34). Zero new dependency (Principle X — use installed Node/Angular/Gradle; never download).

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependency on an incomplete task)
- **[Story]**: US1 / US2 / US3 / US4 (Setup/Foundational/Polish carry no story label)
- Paths are repo-root-relative within the worktree.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Scaffolding for the static legal-content publishing path.

- [x] T001 Verify the build toolchain is already installed (do NOT download): `node --version`, `npm --version` (from `frontend/`), cached Gradle, `@lhci/cli` + `axe-core` present in `frontend/package.json` devDependencies. Record versions in the task notes (Principle X / C7).
- [x] T002 Create the legal content-source directory `frontend/src/content/legal/` with empty `terms/` and `privacy/` subfolders, mirroring `frontend/src/content/articles/`.
- [x] T003 [P] Author `frontend/src/content/legal/AUTHORING.md` documenting the `meta.json` schema (slug, type, title, description, version, lastUpdated, draft), the `body.html` rules (starts at `<h2>`, no `<h1>`, public-allow-list links only, no token/email), the FR-003 12-section requirement, and the "set `draft:false` + bump version/lastUpdated to publish counsel-final copy" workflow (data-model.md C-LP-1, quickstart.md).

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: The shared static-content publishing pipeline that makes a legal page exist at `/terms` and `/privacy`. **No user story can be verified until this is complete.** (research D1–D6; contracts legal-pages C-LP-2, seo-aeo C-SEO-2/C-SEO-5)

**⚠️ CRITICAL**: Blocks US1–US4.

- [x] T004 Add a `LEGAL_PAGES` descriptor list + a pure `assembleLegalPage(doc, ctx)` to `frontend/src/app/core/seo/article-build.lib.mjs` (reusing `headCommon`/`jsonLd`/`escapeHtml`/`PAGE_STYLE`/`lintBody`). The assembler MUST: emit a complete `<html lang="en">` doc with a single `<h1>`; trailing-slash canonical `<origin>/<slug>/`; `<meta name="robots" content="__CADENCE_ROBOTS__">`; `WebPage` + `BreadcrumbList` + shared `Organization` JSON-LD (NO `BlogPosting`/`Article`/`FAQPage`); a visible "last updated" date + version; a prominent draft banner gated on `doc.draft`; the body after the banner; cross-links to the other legal doc + home **carrying the `.home-link` class** (PAGE_STYLE only applies 44 px to `.home-link`/`.related a`); map the `lastUpdated` meta key (NOT the article `lastmodOf`); add a distinct `.draft-banner` rule to `PAGE_STYLE` (border + background, WCAG-AA contrast) so the FR-018 draft banner is visually prominent. (C-LP-2, FR-018, research D2/D3/D9/D10)
- [x] T005 Unit-test `assembleLegalPage` in `frontend/src/app/core/seo/article-build.lib.spec.ts` (Karma/Jasmine; shares this spec file with T007/T011/T012 → serialize, not `[P]`) using inline fixtures: assert single `<h1>`, trailing-slash canonical, valid `WebPage`+`BreadcrumbList` JSON-LD, absence of `BlogPosting`/`Article`/`FAQPage`, draft banner toggles on `draft` and carries the `.draft-banner` style, cross-links present + `.home-link` class, no `token=`/email in output. (research D11)
- [x] T006 Widen `buildSitemap`, `buildLlms`, and `buildArtifacts` in `frontend/src/app/core/seo/article-build.lib.mjs` to accept and emit the legal-pages list: sitemap `<url>` entries for `/terms/` + `/privacy/` (lastmod from `lastUpdated`); an `## Legal` section in `llms.txt`; a `legalPages` array in the `buildArtifacts` return. Do NOT add legal pages to `buildFeed` (resources-only). (C-SEO-2, research D2/N1)
- [x] T007 Unit-test the widened emit in `article-build.lib.spec.ts` (shares the spec file with T005/T011/T012 → serialize, not `[P]`): `buildSitemap` includes `/terms/` + `/privacy/` with correct lastmod; `buildLlms` appends the `## Legal` section without disturbing `## Articles`; `buildFeed` excludes legal pages. (research D11)
- [x] T008 Extend the CLI `scripts/seo-build-articles.mjs`: add `loadLegalPages(legalDir)` (mirror `loadArticles`) with a `CADENCE_LEGAL_DIR` env override (mirror `CADENCE_CONTENT_DIR`), thread the legal list into `buildArtifacts`, and add a `writeFileEnsured(join(distDir, slug, 'index.html'), html)` loop writing `dist/terms/index.html` + `dist/privacy/index.html`. (C-SEO-2, research D2)
- [x] T009 Extend `scripts/seo-inject-origin.mjs` with a fixed legal-page loop — for `slug of ['terms','privacy']`, apply the existing origin + robots substitution to `dist/<slug>/index.html` (mirror the `resources/` loop / `patchOptionalHtml`). **BLOCKER fix**: today it only patches a hardcoded file set + `resources/`, so without this `/terms` ships literal `__CADENCE_ROBOTS__`/`__CADENCE_PUBLIC_ORIGIN__`. (C-SEO-5, research D6)
- [x] T010 [P] Test the CLI emit + inject extension by extending the existing node:test harness `scripts/seo-build-articles.node.test.mjs` (which already runs the real CLI + `seo-inject-origin.mjs` over a temp dist) — add `terms/`+`privacy/` legal source dirs (via the new `CADENCE_LEGAL_DIR` override) and assert: `dist/terms/index.html` + `dist/privacy/index.html` are emitted and listed in the generated `sitemap.xml` + `llms.txt`; after a prod-origin inject they carry the real origin + `index,follow` and NO `__CADENCE_*` placeholder; after a non-prod inject they carry `noindex,nofollow`. (research D2/D6, C-SEO-2/C-SEO-5)

**Checkpoint**: `ng build` + `node scripts/seo-build-articles.mjs <dist>` + `node scripts/seo-inject-origin.mjs <dist>` emit substituted `/terms` + `/privacy` pages (with fixture/placeholder copy). User stories can now proceed.

---

## Phase 3: User Story 1 — Read the full Terms & Conditions and Privacy Notice (Priority: P1) 🎯 MVP

**Goal**: Two dedicated, fully readable, accessible legal documents at `/terms` and `/privacy` with the mandated content, draft notice, date/version, and cross-links.

**Independent Test**: As an unauthenticated visitor, open `/terms` and `/privacy`; each renders full content with a single h1, visible last-updated date + version, the draft banner, cross-links + home link that resolve, legible at 320 px and in print.

### Tests for User Story 1

- [x] T011 [US1] Content-completeness test in `frontend/src/app/core/seo/article-build.lib.spec.ts` (shares the spec file with T012 → serialize, not `[P]`): assemble the REAL `privacy` content and assert each of the 12 FR-003 transparency sections is present (heading text match), and the `terms` content covers the FR-004 elements + references Privacy. (SC-009)
- [x] T012 [US1] axe + render test for both legal pages using the F61 static-HTML `render()` helper pattern (`article-build.lib.spec.ts:289-296`): inject `<style>`+`<main>`, `attachToBody`, assert 0 WCAG 2.2 AA violations, ≥44 px on cross-doc/home links, and the draft banner is present + styled. (SC-004, research D10/D11)

### Implementation for User Story 1

- [x] T013 [P] [US1] Author `frontend/src/content/legal/terms/meta.json` (`type:TERMS`, title, description, version `1.0`, lastUpdated, `draft:true`) and `terms/body.html` — acceptable use, operator↔user relationship, disclaimers/limitations, reference + root-relative link to `/privacy` (FR-004). ASCII prose; starts at `<h2>`; allow-list links only.
- [x] T014 [P] [US1] Author `frontend/src/content/legal/privacy/meta.json` (`type:PRIVACY`, …, `draft:true`) and `privacy/body.html` — all 12 sections from data-model.md (controller/DPO, data categories, purposes+lawful basis incl. legitimate-interest + withdraw-consent, recipients/ATS+calendar, international transfers, retention, data-subject rights + contact, complaint right, automated-decision-making absence, indirect-source disclosure, statutory/contractual note, cookies/session-cookie section). (FR-003/FR-019)
- [x] T015 [US1] Add `http://localhost:4200/terms` and `.../privacy` to the `ci.collect.url` array in the repo-root `lighthouserc.json` (NOT `frontend/` — the file is at the worktree root), and add an `assert.assertMatrix` entry with a `matchingUrlPattern` covering the legal routes (else they fall through to Lighthouse defaults). (research D10/D11)
- [x] T016 [US1] Manual/scripted verification per quickstart.md: build → generate → inject; open both pages at 320 px (no horizontal scroll), confirm single h1, draft banner, last-updated + version, cross-links + home link resolve, print preview legible. Record result. (FR-014/SC-003/SC-007)

**Checkpoint**: `/terms` and `/privacy` are fully readable, accessible, and correct as standalone documents (MVP).

---

## Phase 4: User Story 2 — Privacy Notice link wherever personal data is collected or shown (Priority: P1)

**Goal**: A resolving Privacy Notice link on every candidate-facing personal-data surface and in every candidate-facing outbound email (Art. 14 reach), with no token leakage.

**Independent Test**: Each surface in the inventory shows a working `/privacy` link; each candidate email template renders a `/privacy` anchor with no token/PII.

### Tests for User Story 2

- [x] T017 [P] [US2] Backend contract test in `backend/src/test/java/com/cadence/emailtemplate/` (MockMvc/render): for EVERY `EmailMessageType` whose built-in flows through `renderForSend` (the same set permitted in T019), the rendered email contains an `<a href="<spaBaseUrl>/privacy">` anchor — assert against the **configured `spaBaseUrl`** (test default `http://localhost:4200/privacy`), NOT a literal `https://` — never literal `{{privacy_link}}` nor `[[missing:privacy_link]]`; assert `MergeTokenCatalogue.isPermitted(type, PRIVACY_LINK)` per type; URL has no token/PII; the F21 `BuiltInTemplateCompletenessTest` stays green. (SC-010, contract C-LINK-4)
- [x] T018 [P] [US2] Frontend per-surface specs: assert each of the 7 candidate surfaces renders a Privacy link whose `href` is exactly `/privacy` (root-relative, NOT `routerLink`); token pages set `target="_blank" rel="noopener noreferrer"`, the href carries no token, and clicking writes no web storage; request-access retains its 4-point notice + adds the link. axe 0 violations on each modified surface (incl. the Fraunces request-access render). (SC-002/SC-006, contract C-LINK-2/3)

### Implementation for User Story 2

- [x] T019 [US2] Backend (one atomic change — the F21 `@PostConstruct` completeness lesson): add a URL-typed `PRIVACY_LINK` to `MergeToken`/`MergeTokenCatalogue`, permitted for **every `EmailMessageType` whose built-in flows through `renderForSend`** (enumerate them from `BuiltInEmailTemplates` — all candidate-addressed types; the identical set T017 iterates); add the `{{privacy_link}}` footer link to those built-in templates in `BuiltInEmailTemplates`; **add `AuthProperties` to the `EmailTemplateService` constructor** (currently does not inject it — update the constructor signature + every instantiation/test site) and inject the constant value centrally in `renderForSend` (`values.put("privacy_link", authProps.getSpaBaseUrl() + "/privacy")`). `getSpaBaseUrl()` is absolute, yielding the `http(s)://…/privacy` the URL-typed renderer requires. (FR-020, contract C-LINK-4, research D8)
- [x] T020 [P] [US2] `frontend/src/app/features/request-access/request-access.component.ts`: add a root-relative `/privacy` link inside the existing 4-point notice block (retain the summary, FR-009).
- [x] T021 [P] [US2] `frontend/src/app/features/schedule/schedule.component.ts`: add a single inline Privacy link (`href="/privacy"`, `target="_blank" rel="noopener noreferrer"`). (FR-008/FR-010)
- [x] T022 [P] [US2] `frontend/src/app/features/status/candidate-status.component.ts`: same single inline token-safe Privacy link.
- [x] T023 [P] [US2] `frontend/src/app/features/feedback/scorecard-page.component.ts`: same.
- [x] T024 [P] [US2] `frontend/src/app/features/booking/booking-manage.component.ts`: same.
- [x] T025 [P] [US2] `frontend/src/app/features/booking/cancel-confirm.component.ts`: same.
- [x] T026 [P] [US2] `frontend/src/app/features/booking/confirm-attendance.component.ts`: same.
- [x] T027 [US2] Externalise all new link labels for i18n (`$localize`/`i18n` ids) across the surfaces touched in T019–T026. (FR-012/SC-011)

**Checkpoint**: Every collection/display point and every candidate email links to the Privacy Notice, leak-safe.

---

## Phase 5: User Story 3 — Discover the legal documents from a persistent footer (Priority: P2)

**Goal**: A shared public footer with Terms + Privacy links on the marketing home.

**Independent Test**: Load the home; a footer shows clearly-labelled Terms and Privacy links that resolve.

### Tests for User Story 3

- [x] T028 [P] [US3] Spec for `PublicFooterComponent` in `frontend/src/app/shared/`: renders Terms + Privacy + Home links using `href` (NOT `routerLink`), labels externalised, axe 0 violations, ≥44 px targets; asserts resolved hrefs are exactly `/terms`/`/privacy`/`/`. (contract C-LINK-1)

### Implementation for User Story 3

- [x] T029 [US3] Create `frontend/src/app/shared/public-footer.component.ts` — standalone, root-relative `href` links to `/terms`, `/privacy`, `/`, i18n labels, reusing `styles.scss` primitives (no new global CSS). (FR-006/FR-007, contract C-LINK-1)
- [x] T030 [US3] Mount `PublicFooterComponent` in `frontend/src/app/features/home/home.component.ts` template (NOT in `AppComponent` — would leak onto token cards / shell). (research D7, frontend-review SF-3)

**Checkpoint**: Home footer provides persistent discovery of both legal documents.

---

## Phase 6: User Story 4 — Findable by search & answer engines (Priority: P3)

**Goal**: `/terms` + `/privacy` are crawlable, indexable, and answer-engine-discoverable in prod; deny-by-default + the one-indexable-SPA-route invariant preserved; non-prod non-indexable.

**Independent Test**: In a prod build, both pages are in the sitemap + `llms.txt`, allowed by robots, emit valid non-Article structured data, and no token/authenticated route became indexable; in a non-prod build they are noindex.

### Tests for User Story 4

- [x] T031 [P] [US4] Confirm `frontend/src/app/core/seo/route-seo-inventory.spec.ts` stays green (still exactly one indexable SPA route `''`; legal pages are outside the route table). (FR-016/SC-005)

### Implementation for User Story 4

- [x] T032 [US4] `frontend/src/robots.txt`: add the four anchored allow lines `Allow: /terms$`, `Allow: /terms/$`, `Allow: /privacy$`, `Allow: /privacy/$` (before `Disallow: /`; do not alter existing lines). (FR-022a, contract C-SEO-1)
- [x] T033 [US4] `.github/workflows/ci.yml`: ADD those four lines to the hardcoded robots `ok` allow-set (now 12 total, byte-exact) — the set is closed and fails the build otherwise. (FR-022e, contract C-SEO-6)
- [x] T034 [US4] `.github/workflows/ci.yml`: add a legal-page artifact step (modeled on the per-article `/resources/<slug>/` scan, NOT the home index.html scan) asserting `dist/terms/index.html` + `dist/privacy/index.html` exist (FAIL-CLOSED if absent), contain the legal `<h1>` (not SPA-shell markup), have a `<link rel="canonical">` equal to the **trailing-slash served form** `<origin>/<slug>/` (SC-013/C-LP-4), appear in `dist/sitemap.xml` + `dist/llms.txt`, emit `WebPage`+`BreadcrumbList`, and contain no `BlogPosting`/`Article`/`FAQPage`. (FR-021/FR-022b/c/f, SC-013, contract C-SEO-6, security-review SF-3)
- [x] T035 [US4] `.github/workflows/ci.yml` (sequential — same file as T033/T034/T036/T037): add (a) a token/PII deny-grep over the two legal pages with a positive-control sentinel (anti-vacuous-scan precedent), and (b) a no-third-party-origin grep asserting the legal pages reference no external host (no `googleapis`/`gstatic`/off-origin `src`/`href`) — mirrors the F60 base-URL guard. (FR-011, contract C-SEO-6/N5)
- [x] T036 [US4] `.github/workflows/ci.yml` (sequential — same file): add a guard asserting `frontend/src/_headers` contains no `/terms`/`/privacy` `X-Robots-Tag` line (regression catch; no `_headers` edit needed today). (FR-022d, contract C-SEO-4)
- [x] T037 [US4] `.github/workflows/ci.yml` (sequential — same file): add a non-prod verification step asserting a legal page is `noindex` + placeholder-free after a non-prod inject run (mirror the article `$ARTICLE` non-prod check). (FR-017/SC-008, contract C-SEO-6)

**Checkpoint**: Legal pages are discoverable in prod, non-indexable in non-prod, with deny-by-default intact.

---

## Phase 7: Polish & Cross-Cutting Concerns

- [x] T038 [P] Encoding/byte-scan every new/modified source file (content `.html`/`.json`, `.mjs`, `.ts`, `.java`, `ci.yml`) for non-ASCII / NUL: `git diff --numstat` shows no binary (`-`/`-`) rows for text files; grep new content for non-ASCII (legal prose ASCII or documented UTF-8). No `.ps1/.cmd/.bat` added. (Principle V, F30 lesson)
- [x] T039 [P] Negative-scope guard (FR-019): confirm the diff introduces NO cookie-consent banner, mandatory acceptance checkbox/click-through gate, or new consent/acceptance record — the feature is display + link only. Record the check.
- [x] T040 Run the full quickstart.md verification end-to-end: `ng build` → `seo-build-articles.mjs` → `seo-inject-origin.mjs`; `ng test --watch=false`; `node --test` lib suite; `npx @lhci/cli autorun`; backend `./gradlew test` for the email-template + completeness tests. Record green results. (Constitution VII, Definition of Done)
- [x] T041 Mandatory multi-role sub-agent review (≥3 roles: security, frontend/a11y, SEO/build) of the implemented diff; apply or report all findings before closure. (Constitution VI / C6)

---

## Dependencies & Execution Order

### Phase dependencies

- **Setup (P1)** → no deps.
- **Foundational (P2)** → after Setup; **BLOCKS US1–US4** (the publishing pipeline + inject must exist before any page renders/substitutes).
- **US1 (P3)** → after Foundational. MVP.
- **US2 (P4)** → after Foundational. Frontend surfaces + backend email are independent of US1 content (links target `/privacy`, which the pipeline serves); independently testable.
- **US3 (P5)** → after Foundational. Independent.
- **US4 (P6)** → after Foundational (needs the generator emit + inject from P2 to verify artifacts). Independent of US1/US2/US3 content.
- **Polish (P7)** → after the desired stories.

### Within stories

- Tests before implementation (Principle VII). Lib/assembler (T004) before its emit widening (T006) before the CLI (T008); inject (T009) after the CLI emits files.
- T019 (backend token) is a single cohesive atomic change (catalogue + templates + injection move together) — do not split across the F21 completeness boundary.

### Parallel opportunities

- T003 ∥ (within Setup).
- Foundational: T010 is `[P]` (its own `scripts/seo-build-articles.node.test.mjs`). **T005 → T007 serialize** (both edit `article-build.lib.spec.ts`).
- US1: **T011 → T012 serialize** (both edit `article-build.lib.spec.ts`); T013 ∥ T014 (the two documents, different files).
- US2: T020–T026 are all different component files → fully parallel after T019; T017 ∥ T018 (different test files — backend vs frontend).
- US4: **T032 → T033 → T034 → T035 → T036 → T037 all serialize** — T032 edits `robots.txt`, T033–T037 all edit the one `.github/workflows/ci.yml` (no `[P]`). T031 is `[P]` (independent spec read).
- Polish: T038 ∥ T039 (different scan concerns); T040 (quickstart) then T041 (review) sequential after stories.

---

## Parallel Example: User Story 2

```bash
# After T019 (backend token) lands, the 7 candidate-surface link edits are independent files:
Task: "T020 Add /privacy link to request-access.component.ts (retain 4-point notice)"
Task: "T021 Add token-safe /privacy link to schedule.component.ts"
Task: "T022 Add token-safe /privacy link to candidate-status.component.ts"
Task: "T023 Add token-safe /privacy link to scorecard-page.component.ts"
Task: "T024 Add token-safe /privacy link to booking-manage.component.ts"
Task: "T025 Add token-safe /privacy link to cancel-confirm.component.ts"
Task: "T026 Add token-safe /privacy link to confirm-attendance.component.ts"
```

---

## Implementation Strategy

### MVP First (US1)

1. Phase 1 Setup → 2. Phase 2 Foundational (CRITICAL — the publishing pipeline + inject fix) → 3. Phase 3 US1 (author + verify both documents) → **STOP & VALIDATE**: open `/terms`,`/privacy`, read full text, draft banner, 320 px, print. Deploy/demo.

### Incremental delivery

Foundational → US1 (documents, MVP) → US2 (links at collection points + email Art. 14 reach) → US3 (footer discovery) → US4 (search/answer-engine indexability). Each adds value without breaking the prior.

### Notes

- `[P]` = different files, no incomplete-task dependency. `ci.yml` edits (T033–T037) touch one file — sequence them.
- Backend touch is content-only (one token + templates + central injection); no new service/collection/dependency.
- Commit after each task or logical group; `git add -A` immediately before commit (stale-index trap).
