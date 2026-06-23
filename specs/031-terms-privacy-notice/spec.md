# Feature Specification: Terms & Conditions and Privacy Notice

**Feature Branch**: `031-terms-privacy-notice`  
**Created**: 2026-06-23  
**Status**: Draft  
**Input**: User description: "add terms and conditions and privacy notice links and texts (popups or separate pages). Check the latest updates to pr 34 (this will most probably be merged to main soon) and work in a separate worktree. Review with appropriate subagents"

## Overview

Cadence collects and displays personal data from two distinct audiences — **candidates** (name, email, scheduling/availability, status, interview feedback about them) and **workspace members** (recruiters/admins who sign in). Today the product has no published **Terms & Conditions** (the agreement governing use of the service) and no full **Privacy Notice** (the transparency disclosure required when personal data is collected). The public interest form shows a short four-point data-use summary, but there is nothing to link to for the full text, and no legal documents are reachable from the marketing home or from any candidate-facing page.

This feature publishes two readable, deep-linkable, accessible **legal documents** — a **Terms & Conditions** page and a **Privacy Notice** page — and surfaces clearly labelled **links** to them from the marketing home, from every point where personal data is collected or shown, and (for candidates the product reaches indirectly) from outbound candidate emails. It is a transparency-and-discoverability feature: it presents and links the documents; it does not add click-through acceptance gating or new consent-recording machinery (those are existing, separate concerns).

## Clarifications

No blocking ambiguities. The feature description explicitly allowed either "popups or separate pages"; the resolved decisions below are recorded in **Assumptions** and were confirmed against the existing codebase (the SPA route table is locked to exactly one indexable route, so legal pages are published via the existing static-content build path; candidate token-page hardening; the existing static-content/SEO infrastructure; and the existing GDPR/lawful-basis model). Where legal substance is involved, shipped text is treated as **placeholder content requiring review by the operator's legal counsel before production launch**.

### Session 2026-06-23

- Q: Which URL pattern should the legal pages use — conventional top-level (`/terms`, `/privacy`) or the existing content path (`/resources/legal/*`)? → A: **Conventional top-level `/terms` and `/privacy`.** This requires (and brings into scope) the coordinated SEO/AEO artifact updates: robots.txt allow-lines, the site-map generator allow-list, the CI allow-set guard, the `llms.txt` AI/answer-engine discovery file, the `_headers` no-index exclusion, and valid (non-Article) structured data — all preserving the deny-by-default posture and the "exactly one indexable SPA route" invariant.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Read the full Terms & Conditions and Privacy Notice (Priority: P1)

Any visitor or candidate can open a dedicated, fully readable **Terms & Conditions** page and a dedicated **Privacy Notice** page directly from a stable, shareable address — without signing in, without a token, and on any device.

**Why this priority**: The documents themselves are the core deliverable. Without readable full text reachable at a stable address, no link anywhere else has a destination, and the legal/transparency obligation is unmet. This is the minimum viable slice.

**Independent Test**: Navigate directly to the Terms page address and the Privacy page address as an unauthenticated visitor; verify each renders its full content, is legible on a 320 px-wide mobile viewport, shows a clearly visible "last updated" date and document version, carries a prominent "draft pending legal review" notice while text is non-final, and lets the reader return to the home page and reach the other document.

**Acceptance Scenarios**:

1. **Given** an unauthenticated visitor, **When** they open the Terms & Conditions page address, **Then** the full Terms text renders with a single page heading, a visible "last updated" date and version label, and navigation back to the home page.
2. **Given** an unauthenticated visitor, **When** they open the Privacy Notice page address, **Then** the full Privacy Notice text renders with all mandatory transparency elements (see FR-003), with a visible "last updated" date and version.
3. **Given** a reader on either document, **When** they follow the cross-link to the other document or to the home page, **Then** they arrive at the correct destination.
4. **Given** a reader on a 320 px-wide viewport, **When** the document renders, **Then** text reflows without horizontal scrolling and remains legible.
5. **Given** the shipped text is still placeholder/draft, **When** either document renders, **Then** a prominent on-page notice states the text is a draft pending legal review and not yet binding.

---

### User Story 2 - See a Privacy Notice link wherever personal data is collected or shown (Priority: P1)

At every point where Cadence collects personal data from a candidate, or displays personal data to them, a clearly labelled link to the Privacy Notice is visible so the person is informed about how their data is handled at the moment it matters (GDPR Article 13/14 transparency). For candidates whose data Cadence obtains indirectly (sourced, CSV-imported, or via an ATS connector) and who never visit a candidate-facing page, the first outbound communication carries the same link.

**Why this priority**: Linking the Privacy Notice at the point of collection (or first contact, for indirectly-obtained data) is the substantive privacy/compliance value of the feature and the reason it exists. A full document nobody can find at the moment that matters does not satisfy transparency.

**Independent Test**: Visit each candidate-facing data surface in the planning-fixed surface inventory (the public interest/request-access form, the candidate scheduling page, the candidate status page, the interviewer feedback/scorecard page, and the booking-manage/cancel/confirm pages) and verify a visible, correctly labelled link to the Privacy Notice is present and resolves; and confirm each candidate-facing outbound email template includes the Privacy Notice link.

**Acceptance Scenarios**:

1. **Given** the public interest/request-access form (which collects name, email, organisation, message), **When** it renders, **Then** the existing data-use summary is retained and accompanied by a clearly labelled link to the full Privacy Notice.
2. **Given** the candidate scheduling page where the candidate confirms a slot, **When** it renders, **Then** a visible link to the Privacy Notice is present.
3. **Given** the candidate status page, the interviewer feedback page, and the booking-manage/cancel/confirm pages, **When** each renders, **Then** a visible link to the Privacy Notice is present.
4. **Given** a candidate on a token-bearing page (e.g. scheduling or status), **When** they follow the Privacy Notice link, **Then** their private access token is **not** transmitted to the Privacy Notice page (no token in the destination address and no referrer disclosure), and they can return to their original page without losing their place.
5. **Given** a candidate whose data was obtained indirectly (CSV/ATS/sourced), **When** they receive the first outbound communication from Cadence, **Then** it includes a link to the Privacy Notice.

---

### User Story 3 - Discover the legal documents from a persistent footer (Priority: P2)

A visitor on the public marketing home (and ideally on every public page) can always find Terms and Privacy links in a consistent footer location, matching the universal web convention.

**Why this priority**: Persistent footer links are the conventional, expected discovery path and signal trustworthiness, but the documents and the at-collection links (US1, US2) already deliver the core obligation, so this is important rather than critical. (No footer exists in the product today; this story introduces a shared footer primitive.)

**Independent Test**: Load the public marketing home and confirm a footer (or equivalent persistent region) contains clearly labelled links to both the Terms & Conditions and Privacy Notice pages that resolve correctly.

**Acceptance Scenarios**:

1. **Given** the public marketing home, **When** it renders, **Then** a clearly labelled link to Terms & Conditions and a clearly labelled link to the Privacy Notice are present and resolve to the respective pages.
2. **Given** any public candidate-facing page, **When** it renders, **Then** at minimum the Privacy Notice link is reachable in a consistent location (per US2); minimal token-page card layouts use a single inline Privacy link rather than a full Terms+Privacy footer.

---

### User Story 4 - Legal documents are findable by search and answer engines (Priority: P3)

The Terms & Conditions and Privacy Notice pages are discoverable by search engines and AI answer engines (indexable, listed in the site map, machine-readable), reinforcing trust and supporting answer-engine optimisation — while every private/token-bearing page remains non-indexable.

**Why this priority**: Public legal documents are conventionally indexable and contribute to trust/AEO, but the documents are valuable to candidates even if not yet indexed, so this layers on top.

**Independent Test**: Confirm the Terms and Privacy page addresses are present in the generated site map and permitted by crawl-control rules, that the pages carry valid machine-readable metadata, and that no private/token-bearing route became indexable as a side effect; in a non-production build, confirm the pages are non-indexable.

**Acceptance Scenarios**:

1. **Given** a production build, **When** crawl-control artifacts are generated, **Then** the Terms and Privacy page addresses are listed in the site map and explicitly permitted, and the pages emit index-permitting metadata.
2. **Given** a production build, **When** the indexing inventory is checked, **Then** exactly the intended public pages are indexable and every token-bearing/authenticated route remains non-indexable.
3. **Given** a non-production/preview build, **When** the pages are served, **Then** they are non-indexable (consistent with the deny-by-default environment gate).

---

### Edge Cases

- **Token leakage from candidate pages**: A link from a token-bearing page (scheduling, status, feedback, booking, cancel, confirm) to a legal document MUST NOT carry the candidate's access token in the destination address or referrer. The primary control is the existing global no-referrer policy plus the legal-page address containing no token, so a same-tab navigation is already safe; opening in a new browsing context is an optional state-preservation convenience and, if used, MUST prevent reverse-navigation control (no-opener).
- **Indirect collection (Article 14)**: Candidates obtained via sourcing, CSV import, or ATS connectors never visit a candidate-facing page; the Privacy Notice link in the first outbound email is the enabling control so they can be informed.
- **Unknown/mistyped legal address**: An unknown or malformed legal-document address resolves to the standard not-found page and remains non-indexable (no new error surface).
- **Non-production indexing**: On staging/preview, the legal pages MUST remain non-indexable, like every other page in those environments.
- **Localization**: All link labels and any in-app legal snippets MUST be translation-ready under the application's supported locale set; the documents render correctly under those locales.
- **Placeholder content shipped**: The initial legal text is templated/placeholder and MUST carry a prominent on-page draft notice (not merely a version label) so it is not mistaken for finalised, counsel-approved wording before launch.
- **Print / save**: A reader can print or save the documents legibly (standard browser print produces a readable result).
- **Accessibility of long-form prose**: Documents use a correct heading hierarchy (one page heading), constrained reading width, sufficient contrast, visible focus, and ≥44 px touch targets for any interactive controls.
- **Cross-linking integrity**: Each document links to the other and to the home page; links never dead-end.
- **No third-party contact from candidate token pages**: Following a Privacy link from a token page must not cause the page to contact any third-party origin (preserving the existing candidate-page no-third-party / no-referrer guarantees and the CI-locked CSP).

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The system MUST publish a dedicated, fully readable **Terms & Conditions** page reachable at the conventional top-level address `/terms`, without authentication or a token.
- **FR-002**: The system MUST publish a dedicated, fully readable **Privacy Notice** page reachable at the conventional top-level address `/privacy`, without authentication or a token.
- **FR-003**: The Privacy Notice MUST disclose the GDPR Article 13 **and** Article 14 mandatory transparency elements, at minimum:
  - identity and contact details of the controller (and a DPO / EU representative where appointed);
  - the categories of personal data collected (candidate and member);
  - the purpose(s) of processing and the lawful basis for each (including a description of any legitimate interest relied upon, and the right to withdraw consent where consent is the basis);
  - categories of recipients / third parties the data may be shared with (e.g. the calendar and ATS integrations the operator connects);
  - any transfer of data to a third country and the safeguard relied upon (relevant where calendar/ATS recipients are outside the EEA);
  - retention periods (or the criteria used to set them);
  - data-subject rights (access, rectification, erasure, restriction, portability, objection) and how to exercise them / a contact route;
  - the right to lodge a complaint with a supervisory authority;
  - the existence or explicit absence of automated decision-making / profiling;
  - for candidate data obtained indirectly, the source of the data and the categories of data obtained;
  - whether provision of data is a statutory/contractual requirement and the consequences of not providing it (where applicable).
- **FR-004**: The Terms & Conditions MUST describe, at minimum: who may use the service and acceptable use, the relationship between the operator and the user, disclaimers/limitations as applicable, and a reference to the Privacy Notice.
- **FR-005**: Each document MUST display a clearly visible "last updated" date and a document version identifier that monotonically distinguishes successive published revisions; the version label and the "last updated" date MUST be mutually consistent.
- **FR-006**: Each document MUST link to the other document and back to the public home page.
- **FR-007**: The public marketing home MUST present clearly labelled links to both the Terms & Conditions and the Privacy Notice in a consistent, conventional location (e.g. a footer). No footer exists today; introducing a shared footer primitive is in scope.
- **FR-008**: Every candidate-facing personal-data surface MUST present a clearly labelled, resolving link to the Privacy Notice. The authoritative list of such surfaces is fixed during planning (initially: the public interest/request-access form, the candidate scheduling page, the candidate status page, the interviewer feedback/scorecard page, and the booking-manage/cancel/confirm pages); any surface added later that collects or displays candidate personal data inherits this requirement.
- **FR-009**: On the public interest/request-access form (which is token-free, so FR-010 does not apply to it), the existing short data-use summary MUST be retained and augmented with a link to the full Privacy Notice (the summary is not removed or replaced).
- **FR-010**: A link from any token-bearing candidate page to a legal document MUST NOT transmit the candidate's access token to the legal page — neither embedded in the destination address nor disclosed via referrer. The guarantee MUST be satisfied by the existing global no-referrer policy plus the legal-page address containing no token (so a same-tab navigation is safe); if the link opens a new browsing context for state preservation, it MUST use a no-opener relationship.
- **FR-011**: Following a legal-document link from a candidate token-bearing page MUST NOT cause that page to contact any third-party origin; the legal pages MUST add no external/third-party assets, preserving the existing CI-locked content-security and referrer policies.
- **FR-012**: All user-facing link labels and any legal snippets presented in-app MUST be externalised for translation (no hardcoded user-facing strings) and render correctly under the application's supported locale set.
- **FR-013**: The legal documents MUST meet the project's candidate-facing accessibility bar (WCAG 2.2 AA): a single page heading with correct heading hierarchy, constrained reading width, sufficient colour contrast, visible keyboard focus, and ≥44 px interactive targets.
- **FR-014**: The legal documents MUST be legible and reflow without horizontal scrolling on a 320 px-wide mobile viewport, and MUST produce a readable result when printed/saved via the standard browser print path.
- **FR-015**: In a production build, the Terms & Conditions and Privacy Notice pages MUST be indexable: listed in the generated site map, permitted by crawl-control rules, and emitting index-permitting metadata.
- **FR-016**: Making the legal pages indexable MUST NOT make any token-bearing or authenticated route indexable, and MUST NOT add an indexable Angular SPA route; the existing "exactly one indexable SPA route" invariant and the deny-by-default indexing posture for all other private routes MUST be preserved and verifiable.
- **FR-017**: In non-production/preview builds, the legal pages MUST remain non-indexable, consistent with the existing environment indexing gate.
- **FR-018**: The shipped initial legal text MUST carry a prominent, explicit on-page notice on **both** documents stating the text is a draft/template pending legal review and not yet binding, displayed until counsel-approved wording is supplied (a version label alone does not satisfy this).
- **FR-019**: The feature MUST NOT introduce click-through acceptance gating, a cookie-consent banner, or any new consent/acceptance record. (Cookie/tracking disclosure, if any, is presented as content within the Privacy Notice, not as a new mechanism.) See the acceptance/consent-basis limitation in Assumptions.
- **FR-020**: Candidate-facing outbound email templates (e.g. scheduling invitation, status, holding/SLA, confirmation, reminder) MUST include a link to the Privacy Notice, so candidates whose data is obtained indirectly (sourced, CSV-imported, via ATS) receive the notice at first contact (Article 14 enabling control). The Privacy Notice link in email MUST contain no candidate token or personal data.
- **FR-021**: The legal pages MUST be published as pre-rendered static HTML emitted at build time as real `/terms` and `/privacy` index documents (outside the SPA route table — not Angular SPA routes), so the static host serves them ahead of the SPA catch-all fallback: a direct request to `/terms` or `/privacy` MUST return the legal page, never the SPA shell or the not-found route. The site-map/discovery **generator** MUST be extended to emit these pages (they cannot be hand-added to the static, build-overwritten site-map file), and each page's canonical address MUST match the form actually served by the host (including any trailing-slash normalisation the host applies).
- **FR-022**: Publishing at the conventional top-level addresses MUST update, in coordination, all affected SEO/AEO artifacts so the pages are crawlable, indexable, and answer-engine-discoverable while the deny-by-default posture is preserved: (a) the robots/crawl-control allow-list MUST permit exactly `/terms` and `/privacy` using the anchored exact-match form, so no broader path is freed; (b) the site-map generator MUST be extended to emit both (it currently has no slot for non-article pages — new emit logic is required); (c) the AI/answer-engine discovery file (`llms.txt`) generator MUST likewise be extended to list both; (d) no per-path no-index header rule may cover `/terms` or `/privacy`, and a guard SHOULD prevent one being added later; (e) every CI guard that asserts the permitted crawl-control / site-map / discovery set (including the hardcoded robots allow-set guard, which is closed and will otherwise fail the build) MUST be updated to expect exactly these two additions and no other, and a CI assertion MUST verify that `/terms` and `/privacy` appear in the site map and discovery file and emit valid structured data — mirroring the existing per-article scan; (f) each page MUST emit valid, machine-readable structured data of an appropriate, non-article page type (e.g. a generic web-page / terms-of-service / privacy-policy type), which requires a new generic-page emitter since the existing emitters produce only the article and collection-index types.

### Key Entities *(include if feature involves data)*

- **Legal Document**: A published, versioned, human-readable static document of a given type (Terms & Conditions or Privacy Notice). Attributes: type, title, full body content, "last updated" date, version identifier, stable address, draft/final status. There are exactly two instances initially. These are first-party authored content artifacts, not user-generated or per-workspace data.
- **Privacy-Notice Link Reference**: A labelled link to the Privacy Notice presented at a personal-data surface (collection or display point) or within a candidate-facing email. Not stored data — a UI/content affordance whose presence and correctness is the testable requirement.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A first-time visitor can locate and open the full Privacy Notice from the public home in no more than 2 interactions.
- **SC-002**: 100% of candidate-facing personal-data surfaces in the planning-fixed surface inventory present a working link to the Privacy Notice.
- **SC-003**: Each legal document renders legibly with no horizontal scrolling across viewport widths from 320 px to desktop.
- **SC-004**: Automated accessibility checks report zero WCAG 2.2 AA violations on each legal page and on each modified candidate-facing surface.
- **SC-005**: In a production build, exactly the indexing inventory's expected public set is indexable; zero token-bearing or authenticated routes are indexable, and the count of indexable SPA routes is unchanged.
- **SC-006**: Following the Privacy Notice link from any token-bearing candidate page results in zero transmission of the access token to the legal page (no token in the destination address; no referrer disclosure) — verified for every token-bearing page.
- **SC-007**: Both legal pages display a "last updated" date and version, carry the prominent draft-pending-review notice while non-final, and each links successfully to the other document and to the home page (no dead links).
- **SC-008**: In a non-production/preview build, both legal pages are non-indexable.
- **SC-009**: The rendered Privacy Notice contains every mandatory transparency element enumerated in FR-003 (verifiable as presence of the corresponding sections).
- **SC-010**: Every candidate-facing outbound email template includes a Privacy Notice link, and that link contains no candidate token or personal data.
- **SC-011**: All added user-facing link labels and in-app legal snippets are externalised for translation with no hardcoded strings.
- **SC-012**: In a production build, `/terms` and `/privacy` appear in the generated site map, are permitted by crawl-control rules, are listed in the `llms.txt` discovery file, are absent from the no-index header rules, and emit valid appropriate-type (non-article) structured data; the CI artifact guard passes with exactly these two additions and no other route newly permitted.
- **SC-013**: A direct request to `/terms` and to `/privacy` (no in-app SPA navigation) returns the corresponding legal page — not the SPA shell or the not-found route — and the returned page's canonical URL matches the served address form.

## Assumptions

- **Delivery as dedicated static pages, not popups or SPA routes**: The documents are delivered as dedicated, deep-linkable, indexable static pages published via the existing static-content build path (the SPA route table is locked to exactly one indexable route, so an indexable Angular route is not viable). Dedicated pages were chosen over modal popups because legal documents must be citable, shareable, printable, indexable, and accessible. (The description allowed either; this is the resolved choice.)
- **Reuse of existing infrastructure, no new dependencies**: The pages and their links reuse the existing static-content/SEO publishing infrastructure and the design system introduced by PR 34 (030-sota-design-system); no new runtime dependency, backend service, database collection, or third-party legal-content provider is introduced.
- **Legal pages use the system-font stack** (consistent with candidate pages and existing static content), not the branded display font.
- **Display/transparency, not acceptance capture**: This feature presents and links the documents; it does not add mandatory acceptance checkboxes, click-through gates, or consent/acceptance records, and the existing admin-side lawful-basis/consent model is unchanged. **Limitation to confirm before launch**: recruiter/member T&C acceptance recording at signup, and any candidate processing that rests on *consent* rather than legitimate interest, may legally require explicit acceptance/consent records; that machinery is intentionally out of scope, and the operator/counsel must confirm display-only suffices for their lawful bases.
- **Placeholder legal text**: Shipped content is templated/placeholder, carrying the prominent draft notice (FR-018); final wording is supplied by the operator's legal counsel. The team is not providing legal advice.
- **Single global document set**: One Terms and one Privacy document apply across the product (not per-workspace, not per-locale-divergent in body text). In a multi-workspace deployment each operator may be a distinct controller with its own identity/DPO; per-controller identity disclosure may need per-workspace handling later — for MVP the notice describes the Cadence operator as controller/processor as applicable.
- **Supported locales** are the application's currently-configured locale set; this feature adds no new locale.
- **Cookie/tracking disclosure within the Privacy Notice**: The application relies on a first-party session cookie and self-hosted assets with no third-party trackers, so cookie/tracking disclosure is handled as a section of the Privacy Notice rather than a separate cookie-consent banner.
- **Candidate token pages remain hardened**: Links added to token-bearing pages preserve the existing token-leak, no-referrer, and no-third-party-contact guarantees; legal-page addresses contain no tokens.
- **Builds on PR 34**: This branch is based on `030-sota-design-system` (PR 34, expected to merge to main shortly) so the legal pages use the merged design-system tokens/components and integrate cleanly once PR 34 lands on main.

## Dependencies

- The merge of PR 34 (`030-sota-design-system`) to main is expected; this branch is based on it so the legal pages adopt the unified design system. If PR 34's surface changes before merge, the integration points (home footer area, candidate-page layouts) should be re-confirmed.
- **Publishing-mechanism / address constraint (decided)**: The conventional top-level `/terms` & `/privacy` addresses are chosen (see Clarifications). This brings into scope coordinated edits to the crawl-control allow-list, the site-map generator's allow-list, the `llms.txt` discovery file, the CI allow-set guard, and the static-content artifact scan — all of which MUST move together (FR-022). The addresses are registered in the site-map **generator**, never hand-added to the static (build-overwritten) site-map file.
- Final legal wording is an external dependency on the operator's legal counsel; the feature ships placeholder text and the mechanism to publish it.

## Out of Scope

- Click-through / mandatory acceptance gating and any acceptance/consent record (display + link only).
- A cookie-consent banner or third-party tracking-consent management.
- An admin-editable CMS or per-workspace customization of the legal documents.
- Version history / changelog / archival of superseded legal-document revisions (a single current version per document is published).
- Translation of the legal-document **body text** itself (only UI labels and in-app legal snippets are translation-ready; the documents' canonical body is the placeholder source language).
- New backend services, database collections, or new runtime/third-party dependencies. (The only backend touch is a content-only edit to the existing candidate-facing built-in email templates to add the Privacy Notice link required by FR-020 — no new service, collection, schema, or dependency.)
- Drafting final, legally binding wording (placeholder content is provided for counsel to finalise).
