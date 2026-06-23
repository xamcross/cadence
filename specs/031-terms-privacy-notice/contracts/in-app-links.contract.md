# Contract: In-App Privacy/Terms Link Affordances

Covers FR-006, FR-007, FR-008, FR-009, FR-010, FR-011, FR-012, FR-020, SC-002, SC-006, SC-010, SC-011.

## C-LINK-1: Shared public footer (`PublicFooterComponent`, NEW standalone)

- Renders clearly-labelled links to `/terms` and `/privacy` (and the home page) using **root-relative full-document anchors** — `href="/terms"` / `href="/privacy"`, leading slash **required**. MUST NOT use `routerLink` (would hit the SPA router → wildcard `**` → NotFound) and MUST NOT use a relative href (would mis-resolve against `<base href="/">` on nested routes).
- Mounted **inside each public page component template** (the marketing home today), **NOT** in `AppComponent` (which renders a bare `<router-outlet>`) — a global mount would leak the footer onto token cards and the authenticated shell.
- All labels externalised for i18n (`$localize`/`i18n`), no hardcoded user-facing strings (FR-012/SC-011).
- Meets WCAG 2.2 AA, ≥44 px targets; reuses PR-34 `styles.scss` primitives (no new global CSS needed).
- **Test**: assert the link elements use `href` (not `routerLink`) and the resolved attribute equals exactly `/terms`/`/privacy` (no token, no query).

## C-LINK-2: Candidate-surface Privacy link (FR-008 / SC-002)

For each surface in the planning-fixed inventory (`data-model.md`): a visible, labelled, root-relative-`href` link resolving to `/privacy`.

- Token-bearing pages (`schedule`, `status`, `feedback`, `booking-manage`, `cancel-confirm`, `confirm-attendance`): a **single inline** Privacy link (not the full footer), opening in a new tab — `target="_blank" rel="noopener noreferrer"` — as the **default** to preserve the candidate's in-memory token/state.
- `request-access`: link **added to** the existing 4-point notice block; the summary is retained, not replaced (FR-009). This is a Fraunces entry page (NOT in the token-page system-font allow-list), so its axe + 44 px gate runs against the actual `request-access` render, not a token-card mock.

## C-LINK-3: Token-leak safety (FR-010 / FR-011 / SC-006)

- The link target is the constant `/privacy` (or `/terms`) — it MUST contain no candidate token and no query/fragment carrying state.
- A same-tab navigation is safe by construction: the destination URL has no token and the global `Referrer-Policy: no-referrer` (`_headers`) suppresses the Referer, so the candidate's token-bearing URL is never disclosed.
- Token pages open the link in a new tab by default and MUST set `rel="noopener noreferrer"` (mandatory whenever `target="_blank"` is used — reverse-tabnabbing).
- Following the link MUST cause no third-party-origin contact (same-origin static page; CI-locked CSP preserved).
- Test: for every token-bearing page, assert the rendered Privacy link's `href` contains no token and no web-storage write occurs on click.

## C-LINK-4: Candidate email Privacy link (FR-020 / SC-010)

- Each candidate-facing built-in email template (invitation, confirmation, reminder, holding/SLA, status, rejection, cancellation, feedback-request) MUST render a Privacy Notice link.
- Implemented as a **URL-typed `privacy_link` merge token** (the `status_link` precedent) — a literal `<a>` in the body fails (the F21 renderer HTML-escapes recruiter-authored body text). The token's constant value is injected **centrally** in `EmailTemplateService.renderForSend` (`values.put("privacy_link", authProps.getSpaBaseUrl() + "/privacy")`), NOT per call-site, carrying **no** candidate token or PII (SC-010).
- `privacy_link` MUST be permitted in `MergeTokenCatalogue` for **every** candidate-facing message type (an omitted type renders the literal `{{privacy_link}}`). The token addition moves atomically with `MergeTokenCatalogue` + `BuiltInEmailTemplates` + the `@PostConstruct` completeness check / `BuiltInTemplateCompletenessTest` (the F21 startup-failure lesson).
- Test (JUnit/MockMvc): render **each** candidate-facing template; assert `privacy_link` renders as an `<a href="https://<origin>/privacy">` anchor — never the literal `{{privacy_link}}` and never `[[missing:privacy_link]]` — assert `MergeTokenCatalogue.isPermitted(type, PRIVACY_LINK)` per type, and the URL contains no token/PII; the F21 completeness test stays green.

## C-LINK-5: Cross-document links (FR-006 / SC-007)

Each legal page links to the other legal page and to home (covered by the legal-pages contract C-LP-2); the footer provides the reciprocal entry points from public pages.
