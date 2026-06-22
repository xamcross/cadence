# Feature Specification: SEO & AEO Discoverability

**Feature Branch**: `026-seo-aeo`
**Created**: 2026-06-22
**Status**: Draft
**Input**: User description: "Check the current implementation of the app. Based on SOTA practices and guidelines add SEO and AEO related files, features"

## Context & Problem

Cadence today ships as a single-page application with **no machine-readable discoverability metadata of any kind**: the served HTML carries only a bare title and viewport tag, and there is no `robots.txt`, no sitemap, no canonical URLs, no per-page descriptions, no structured data, no social-share previews, and no answer-engine guidance file. At the same time, large parts of the product are intentionally private: every authenticated workspace screen and — critically — every **per-candidate link page** (scheduling, booking, reschedule/cancel, attendance confirmation, status, and interviewer scorecard) carries a secret token in its URL and must **never** be visited, crawled, indexed, or cited by any search engine or AI answer engine.

This feature adds the search-engine-optimization (SEO) and answer-engine-optimization (AEO) surface that lets the **public** parts of the product (the marketing/home entry and sign-in) be found, correctly described, and accurately cited by both traditional search engines and AI answer engines (e.g. AI Overviews, ChatGPT, Perplexity), while **guaranteeing** that private and token-bearing pages stay invisible to all crawlers and answer engines. SEO and AEO discoverability is therefore inseparable from a privacy obligation: getting one wrong (e.g. emitting a sitemap that lists a token page) would be a data-exposure incident.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Public pages are findable and correctly described (Priority: P1)

A prospective customer searches for an interview-scheduling product, or clicks a link to Cadence shared in a chat or social post. Search engines have crawled the public entry pages, understand what each page is, and present an accurate title, description, and canonical link in results. The visitor lands on a page whose purpose is clear and whose preview (when shared) shows a meaningful title, description, and image rather than a blank or broken card.

**Why this priority**: This is the headline ask — making the product discoverable. Without crawlable, correctly-described public pages there is no SEO at all. It is the smallest slice that delivers standalone value.

**Independent Test**: Crawl the public site root with a standard crawler/validator and confirm each public page returns a unique, descriptive title and meta description, a self-referential canonical URL, valid `robots.txt` directives, and a sitemap that lists exactly the public pages. Confirm the public content is present in the served markup without requiring client-side script execution.

**Acceptance Scenarios**:

1. **Given** a search engine crawler requests `/robots.txt`, **When** it reads the file, **Then** it receives valid directives that allow the public entry pages and point to the sitemap location.
2. **Given** a crawler requests the sitemap, **When** it parses it, **Then** it finds every public, indexable page listed with a canonical absolute URL and **no** token-bearing or authenticated page listed.
3. **Given** any public page is loaded, **When** its markup is inspected, **Then** it has a unique page title, a meta description, a self-referential canonical URL, and a declared content language.
4. **Given** a public page link is pasted into a social/chat tool, **When** the preview renders, **Then** it shows a title, description, and preview image (no blank/broken card).
5. **Given** a crawler that does not execute scripts requests a public indexable page, **When** it reads the response, **Then** the page's primary descriptive content is present in the returned markup.

---

### User Story 2 - Private and token-bearing pages are never indexed (Priority: P1)

A candidate receives a private scheduling/status link, or a recruiter works inside the authenticated workspace. None of these pages must ever appear in a search engine, be cited by an answer engine, or leak via referrer. A crawler that discovers such a URL (e.g. from a leaked link) must be instructed not to index it, and the page itself must independently signal "do not index" so that a single misconfiguration cannot expose private candidate data.

**Why this priority**: The product is privacy-hardened by design; token pages carry secrets and personal data. Shipping SEO metadata that accidentally exposed these pages would be a serious data-protection incident. Non-indexing is co-critical with discoverability and must ship together.

**Independent Test**: For each token page (`/schedule`, `/booking`, `/booking/cancel`, `/confirm`, `/status`, `/feedback`) and each authenticated route, confirm the page is excluded by `robots.txt`, carries a page-level "do not index, do not follow" directive, is absent from the sitemap, and emits no referrer when navigating away. Confirm no token value appears in any sitemap, canonical URL, structured data, or social-preview field.

**Acceptance Scenarios**:

1. **Given** `/robots.txt`, **When** a crawler reads it, **Then** all token-page route prefixes and all authenticated route prefixes are disallowed.
2. **Given** any token-bearing or authenticated page is loaded, **When** its markup is inspected, **Then** it carries an explicit page-level directive to neither index nor follow.
3. **Given** the sitemap, **When** it is parsed, **Then** it contains no token page, no authenticated page, and no URL bearing a query token.
4. **Given** a token page generates SEO/social metadata, **When** its canonical/preview fields are inspected, **Then** they never include the secret token (or the page omits canonical/preview entirely).
5. **Given** a visitor navigates away from a token page, **When** the outbound request is inspected, **Then** no referrer carrying the token is sent (the existing no-referrer posture is preserved).

---

### User Story 3 - Answer engines can accurately describe and cite Cadence (Priority: P2)

Someone asks an AI answer engine "what is Cadence" or "is there a tool that does interview scheduling with no-show defense and GDPR-safe candidate handling". The answer engine has access to clear, structured, machine-readable facts about the product (what it is, what it does, who it's for, how to reach it) and an explicit guidance file describing the site for large language models, so it can describe and cite Cadence accurately rather than hallucinating or omitting it.

**Why this priority**: Answer engines increasingly mediate discovery. Structured data and an LLM-guidance file are the SOTA levers that make a product citable. It builds on the P1 public surface but is a distinct, independently demonstrable capability.

**Independent Test**: Validate the structured data on each public page against a structured-data validator with zero errors; confirm an LLM-guidance file is served at the well-known path and accurately summarizes the product and its public links; confirm the structured data and guidance file contain no private/token URLs.

**Acceptance Scenarios**:

1. **Given** a public page, **When** its structured data is validated, **Then** it describes the organization and the product/application with no validation errors.
2. **Given** an answer engine requests the LLM-guidance file at its well-known path, **When** it reads the file, **Then** it finds an accurate plain-text summary of the product, its purpose, and links to public pages only.
3. **Given** the structured data and guidance file, **When** their URLs are inspected, **Then** none references a token page, an authenticated page, or a secret.
4. **Given** a public page describing product capabilities, **When** an answer engine parses it, **Then** the key facts (product name, category, primary purpose) are unambiguously machine-extractable.

---

### User Story 4 - Operators can keep non-production environments out of the index (Priority: P3)

A site operator deploys a staging/preview environment and needs assurance it will never be indexed or compete with production, and that production defaults to "public pages indexable, private pages not", all without per-page manual edits.

**Why this priority**: Operational safety; valuable but not required for the core SEO/AEO value, and lower-frequency than the discovery and privacy stories.

**Independent Test**: Deploy a non-production environment and confirm every page (including the otherwise-public ones) signals "do not index"; deploy production and confirm public pages are indexable while private/token pages remain non-indexable.

**Acceptance Scenarios**:

1. **Given** a non-production/staging environment, **When** it is deployed, **Then** every page signals "do not index" and `robots.txt` disallows all crawling, so staging never competes with or leaks ahead of production.
2. **Given** a production environment, **When** it is deployed, **Then** public pages are indexable and private/token pages remain non-indexable.

---

### Edge Cases

- **Token leaks into a crawler queue**: a private link is shared publicly and a crawler fetches it — the page must still self-signal non-indexing and the route must be robots-disallowed, so it is dropped even though it was reachable.
- **Deep-link directly to a public page (not the root)**: a crawler or visitor arrives at a non-root public page first — it must still carry a correct title, description, canonical, and structured data (metadata is per-page, not only on the root).
- **Same content reachable at more than one URL** (e.g. trailing slash, default-locale path): canonical URLs must point to one preferred form to avoid duplicate-content dilution.
- **Localized content**: the product is localization-ready; public pages must declare their language and, where alternate-language versions exist, point to them, without exposing private pages.
- **A new private/token route is added later**: the disallow/non-indexing posture must default to "private unless explicitly marked public", so a future token route is protected without a separate change (deny-by-default).
- **Sitemap drift**: a public page is added or removed — the sitemap must reflect the actual set of public pages and never go stale in a way that lists a removed/redirected URL or omits a live public one.
- **Crawler ignores robots**: a non-compliant crawler ignores `robots.txt` — the page-level non-indexing directive is the second, independent line of defense.
- **Preview image missing**: a public page without a specific share image must fall back to a default brand image, never a broken reference.

## Requirements *(mandatory)*

### Functional Requirements

#### Crawl control & sitemap

- **FR-001**: The system MUST serve a `robots.txt` at the site root that allows crawling of the public, indexable pages and disallows every token-bearing route prefix and every authenticated route prefix.
- **FR-002**: `robots.txt` MUST reference the absolute location of the sitemap.
- **FR-003**: The system MUST serve a sitemap that lists every public, indexable page with its canonical absolute URL, and MUST NOT list any token-bearing page, authenticated page, or URL containing a secret token.
- **FR-004**: The set of disallowed/non-indexable routes MUST be deny-by-default: any route not explicitly designated public is treated as non-indexable, so newly added private/token routes are protected without further change.

#### Per-page metadata

- **FR-005**: Every public, indexable page MUST provide a unique, human-meaningful title and a meta description.
- **FR-006**: Every page MUST declare a self-referential canonical URL in a single preferred form (resolving trailing-slash/locale/duplicate variants to one canonical), except token pages, which MUST NOT emit a canonical that contains the token.
- **FR-007**: Every page MUST declare its content language.
- **FR-008**: Every token-bearing page and every authenticated page MUST emit an explicit page-level directive instructing crawlers to neither index the page nor follow its links.
- **FR-009**: Page-level metadata MUST be correct for direct deep-links to any public page, not only the site root.

#### Social / shareable previews

- **FR-010**: Every public, indexable page MUST provide social-share preview metadata (title, description, and preview image) sufficient to render a rich, non-broken preview when shared.
- **FR-011**: A public page without a page-specific preview image MUST fall back to a default brand image; no preview field may reference a missing/broken resource.
- **FR-012**: Social-share and preview metadata MUST NOT be emitted for token-bearing pages, and MUST never contain a secret token.

#### Structured data (AEO)

- **FR-013**: Public pages MUST include machine-readable structured data describing, at minimum, the organization and the software product/application, validating with no errors against a standard structured-data validator.
- **FR-014**: Structured data MUST reference only public URLs and MUST NOT contain any token, private URL, or personal data.

#### Answer-engine guidance (AEO)

- **FR-015**: The system MUST serve a large-language-model guidance file at its conventional well-known path that summarizes the product, its purpose and audience, and links to public pages only.
- **FR-016**: The guidance file MUST NOT reference token pages, authenticated pages, secrets, or personal data.

#### Crawlable content

- **FR-017**: The primary descriptive content of each public, indexable page MUST be present in the served markup such that a crawler or answer engine that does not execute client-side scripts can read it.

#### Privacy preservation

- **FR-018**: The feature MUST preserve the existing no-referrer posture so that navigating away from a token page never transmits the token via referrer.
- **FR-019**: No SEO/AEO artifact (robots, sitemap, canonical, structured data, social metadata, guidance file) may weaken the existing content-security and header posture of the application.

#### Environment control

- **FR-020**: Non-production environments MUST signal "do not index" for all pages (including otherwise-public ones) and disallow all crawling, so that staging/preview deployments never get indexed or compete with production.
- **FR-021**: The indexability posture (public-indexable in production, fully-discouraged elsewhere) MUST be determinable at deploy time without per-request manual intervention.

#### Designated public surface

- **FR-022**: The system MUST provide a new, minimal **public home page** that describes the product (name, purpose, primary capabilities) and offers a clear path to sign in. This page is the canonical indexable discoverable entry point and the primary carrier of the per-page metadata, structured data, and social-preview requirements above. The sign-in page MAY also be indexable but is secondary; all token and authenticated pages remain non-indexable.
- **FR-023**: The public home page MUST present accurate, non-marketing-inflated product facts (what Cadence is and does) in human-readable content that is also the basis for the page's machine-readable description and structured data, so search and answer engines describe the product consistently with the page.

### Key Entities *(include if feature involves data)*

- **Public page**: A page intended for anonymous discovery (e.g. home/marketing entry, sign-in). Attributes: canonical URL, title, description, language, share-preview metadata, structured-data block, indexable=true.
- **Private/token page**: A page reachable only via a secret token or an authenticated session (scheduling, booking, cancel, confirm, status, scorecard, all `/admin/*` and workspace screens). Attributes: non-indexable, no canonical/preview emitting the token, robots-disallowed, no-referrer.
- **Crawl-control artifacts**: `robots.txt`, sitemap, and the LLM-guidance file — each derived from the same authoritative public/private route classification.
- **Indexability posture**: Per-environment setting (production = public-indexable; non-production = fully discouraged) that governs the directives emitted.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 100% of public, indexable pages return a unique title, a non-empty meta description, a self-referential canonical URL, and a declared language.
- **SC-002**: 0 token-bearing or authenticated URLs appear in the sitemap, in any canonical URL, in structured data, in social metadata, or in the LLM-guidance file (verified by automated scan).
- **SC-003**: 100% of token-bearing and authenticated pages carry an explicit page-level "do not index, do not follow" directive and are disallowed in `robots.txt`.
- **SC-004**: Structured data on every public page validates with 0 errors against a standard structured-data validator.
- **SC-005**: The primary descriptive content of every public page is readable from the served markup without executing client-side scripts (verified by fetching the page with scripting disabled).
- **SC-006**: A shared link to any public page renders a complete preview (title, description, image) with 0 broken/missing preview fields.
- **SC-007**: `robots.txt`, the sitemap, and the LLM-guidance file are each served successfully at their conventional locations and parse without error.
- **SC-008**: In a non-production environment, 100% of pages signal "do not index".
- **SC-009**: Adding a new private/token route requires no change to crawl-control artifacts for it to be protected (deny-by-default verified by adding a test route and confirming it is disallowed and non-indexable).
- **SC-010**: 0 regressions to the existing security-header and no-referrer posture (verified against the current header contract).

## Assumptions

- **Single public domain**: SEO/AEO is applied to the application's own production domain; the SPA and API are served same-origin (as today). Absolute URLs in artifacts derive from the configured production base URL.
- **Static-CDN deployment**: The frontend is served from a static CDN (Cloudflare Pages today). Public indexable content is made crawler-readable via build-time pre-rendering of public routes (no per-request server rendering), consistent with the current static deployment model. Private/token pages remain client-rendered and non-indexable.
- **Public surface is a new home page + sign-in**: A new minimal public home page is the canonical indexable entry point; sign-in is secondary-indexable. All candidate token pages and all authenticated workspace pages are private and non-indexable.
- **No content marketing site in scope**: The new home page is a single, minimal product page. Authoring a multi-page marketing/blog site, keyword strategy, and ongoing content production are out of scope; this feature delivers the technical SEO/AEO foundation plus that one canonical public page.
- **Localization-ready, single active locale**: The app is localization-ready; this feature declares language and supports alternate-language linking but does not author additional localized content.
- **Default brand assets exist**: A default brand/share image and product description (name, category, purpose) are available or can be derived from existing branding.
- **Reuse existing privacy controls**: The existing no-referrer meta, CSP/security headers, and token-page hardening are reused and extended, not replaced.
- **Deny-by-default classification**: A single authoritative classification of routes as public vs private drives robots, sitemap, per-page directives, and the guidance file, so the artifacts cannot drift apart.

## Out of Scope

- Ongoing content/keyword strategy, blog, or multi-page marketing site authoring.
- Paid search, analytics dashboards, or rank tracking.
- Backlink building or off-site SEO.
- Server-side rendering of authenticated/token pages (they remain client-rendered and non-indexable by design).
- Internationalized content authoring beyond language declaration and alternate-language linking hooks.
