# Feature Specification: SEO/AEO Content Article Library

**Feature Branch**: `027-seo-content-library`  
**Created**: 2026-06-22  
**Status**: Draft  
**Input**: User description: "find out if sitemap is already in the project for SEO/AEO. Also, find out if there are theme-related articles in the app that will bring the indexing score up. if they are not there - prepare specification to create them"

## Investigation Findings (context for this feature)

A discovery pass over the existing project answered the two questions in the request:

1. **Is a sitemap already present?** — **Yes.** A prior feature (026-seo-aeo) shipped a crawl-control surface: a `sitemap.xml`, a `robots.txt` (deny-by-default), an `llms.txt` (AI-crawler guidance), structured data (Organization, SoftwareApplication, WebSite, FAQPage), and per-route indexing control. **However, the sitemap lists exactly ONE indexable URL — the marketing home page.** Every other route is intentionally non-indexable (auth, internal app screens, and per-candidate token pages).
2. **Are there theme-related articles that raise the indexing score?** — **No.** The product exposes a single indexable page. There is no library of topical, crawler-readable content (no articles, guides, glossary, or resource pages). A site with one thin indexable page has very little to rank for and offers no internal-linking depth, which caps both the classic search (SEO) footprint and the answer-engine (AEO) extractability.

**Conclusion**: the sitemap exists; the theme-related articles do **not**. Per the request, this specification defines the creation of a public, indexable content article library to raise the site's indexing and answer-engine footprint.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A prospective customer finds Cadence through a topical search (Priority: P1)

A talent-acquisition leader searches for help with a recruiting problem Cadence solves (for example, "how to reduce interview no-shows" or "candidate experience best practices"). A Cadence article on that exact theme appears in the results, answers the question clearly, and links the reader onward to the product home and to related articles.

**Why this priority**: This is the core purpose of the request — turning a one-page indexable footprint into a discoverable, theme-rich surface. Without indexable topical content there is essentially nothing for search engines to rank beyond the brand name, and the indexing score cannot improve. This story alone delivers a viable MVP: even a small set of well-structured articles plus their inclusion in the sitemap measurably raises the indexable surface.

**Independent Test**: Publish a single article on a relevant theme, confirm it has its own crawlable URL, is listed in the sitemap, is fully readable without JavaScript, and links to the home page and at least one related article. Verify the home page links into the library so crawlers can reach the articles.

**Acceptance Scenarios**:

1. **Given** a published article on a recruiting theme, **When** a search/answer engine crawls the site, **Then** the article is reachable from the home page, has a unique canonical URL, is present in the sitemap, and exposes its title and full body text without requiring JavaScript.
2. **Given** the article library, **When** a reader opens any article, **Then** they see the article title, body, publication date, and links to the product home and to at least one related article.
3. **Given** the library index (a page that lists all articles), **When** a reader or crawler opens it, **Then** every published article is listed with its title and a short summary, each linking to the full article.

---

### User Story 2 - An answer engine extracts a direct, attributable answer (Priority: P2)

Someone asks an AI answer assistant a question in Cadence's domain (for example, "Does interview-scheduling software need a candidate account?"). The assistant is able to extract a concise, accurate answer from a Cadence article and attribute it to Cadence.

**Why this priority**: Answer-engine optimization (AEO) is explicitly named in the request. Articles structured for extraction — clear headings, a question-style framing, a short summary, and machine-readable article metadata — make Cadence's content quotable by answer engines, extending reach beyond classic blue-link search. It builds on the same articles created in P1, so it is additive rather than a separate content effort.

**Independent Test**: Confirm each article carries machine-readable article metadata (headline, summary, publication date, author/publisher) and uses a clear heading structure with a concise lead summary, and that the existing AI-crawler guidance file points to the library.

**Acceptance Scenarios**:

1. **Given** a published article, **When** its page is inspected, **Then** it carries machine-readable article metadata identifying it as an article with a headline, a short description, a publication date, and the publisher.
2. **Given** the article body, **When** read top-down, **Then** it opens with a concise summary that directly answers the article's core question before expanding into detail.
3. **Given** the AI-crawler guidance file, **When** it is read, **Then** it references the article library so answer engines can discover the topical content.

---

### User Story 3 - The content set stays current and indexing-safe over time (Priority: P3)

The team adds, updates, and retires articles over time without breaking the crawl-control guarantees or accidentally exposing anything private.

**Why this priority**: Sustainable maintenance protects the gains from P1/P2 and preserves the strict deny-by-default privacy posture established for the rest of the site. It is lower priority because the initial indexing-score lift comes from publishing the first articles; this story ensures the surface does not decay or leak.

**Independent Test**: Add a new article and confirm it automatically appears in the library index and the sitemap; retire an article and confirm it no longer appears in either and resolves to a "not found" response rather than a broken page; confirm no private route, token, or personal data ever appears in any article, the index, or the sitemap.

**Acceptance Scenarios**:

1. **Given** a newly published article, **When** the site is rebuilt/republished, **Then** the article appears in both the library index and the sitemap with no manual edit to either.
2. **Given** a retired article, **When** a reader or crawler requests its old URL, **Then** they receive a clear "not found" response, the article is absent from the index and sitemap, and no indexable dead link remains.
3. **Given** any article, index page, or sitemap entry, **When** inspected, **Then** it contains only public marketing/educational content — never a candidate token, an authenticated route, or any personal data.

### Edge Cases

- What happens when an article URL is typed with a wrong or stale slug? The reader receives a "not found" response that is itself non-indexable, never a redirect to the home page presented as the article.
- How does the system handle an empty library (zero published articles)? The library index renders gracefully with a neutral message; to avoid shipping a thin indexable page, the library index is treated as indexable only once at least one article is published (until then it remains reachable but non-indexed). The launch floor of at least four articles means this is an interim/maintenance state, not the launch state.
- What happens when the site is built in a non-production environment? The whole site (including the new library) remains non-indexable, consistent with the existing deny-by-default posture for non-production builds.
- How does the system handle very long or non-Latin article titles and summaries? They render without layout overflow and remain readable on mobile.
- What happens if two articles are given the same slug? The conflict is detected at build/publish time and fails loudly rather than silently overwriting or producing duplicate URLs.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The system MUST provide a public, indexable library of theme-related articles covering topics in Cadence's domain (for example: reducing interview no-shows, candidate experience, interview scheduling and calendar coordination, and privacy-safe/GDPR-conscious recruiting).
- **FR-002**: Each article MUST have its own stable, human-readable, crawlable URL distinct from every other article and from the home page.
- **FR-003**: The system MUST provide a library index page that lists every published article with its title and a short summary, each linking to the full article.
- **FR-004**: The home page MUST link to the article library so that crawlers and readers can reach the articles from the site's single most-linked page.
- **FR-005**: Each article and the library index MUST expose their primary title and full readable body text without requiring JavaScript execution (readable by no-JS crawlers). Because the rest of the site has no server-side rendering, this MUST be satisfied by content delivered in a crawler-readable form at publish/build time (e.g. pre-rendered or statically authored article pages) rather than fetched and rendered only in the browser.
- **FR-006**: Each article MUST link to the product home and to at least one TOPICALLY related article (one sharing a theme), creating internal-linking depth.
- **FR-007**: Every published article and the library index MUST be included in the site's sitemap automatically, with no manual sitemap editing required when articles are added or removed. The sitemap MUST be generated ONLY from the set of {home, library index, published articles} — never by scanning the application's route table or built page set — so that no private, authenticated, or per-candidate token route can ever appear in it. Each article and the library index sitemap entry MUST carry a last-modified date.
- **FR-008**: Each article MUST carry machine-readable structured data identifying it as an article (with headline, summary/description, publication date, last-updated date where applicable, and publisher). The library index MUST carry collection/item-list structured data, and every article MUST carry breadcrumb structured data describing the home → library → article path. The publisher identity MUST be the organization (reusing the existing site-wide Organization identity); structured data MUST NOT embed any individual person's name, email, or personal contact details.
- **FR-009**: Each article MUST open with a summary/lead that directly answers the article's core question, followed by a body organized under descriptive section headings. The lead summary MUST be a single short paragraph (no more than ~60 words).
- **FR-010**: The library and all articles MUST be indexable ONLY in the production environment; in any non-production build they MUST remain non-indexable through the same controls the rest of the site uses (the deny-by-default crawl-control file AND a per-page noindex signal that holds even on a direct page fetch).
- **FR-011**: No article, library index page, sitemap entry, structured-data block, or AI-crawler guidance entry may contain any candidate token, authenticated/internal route, or personal data — content is strictly public marketing/educational material. This MUST be verified by an automated scan over the built article pages, the index, the sitemap, and the structured-data blocks (the same artifact-scan approach the existing SEO surface uses).
- **FR-012**: A request for a non-existent or retired article URL MUST return a clear "not found" response that is itself non-indexable, never a redirect that serves the home page or another article in its place. On retirement, the article's index entry, sitemap entry, and canonical reference MUST all be removed together.
- **FR-013**: The AI-crawler guidance file MUST list each published article's URL (not merely a single link to the library index) so answer engines can discover every piece of topical content.
- **FR-014**: Each article MUST display to readers its publication date and, when it has been revised, a last-updated date.
- **FR-015**: The library and articles MUST be readable on mobile devices and MUST meet the same accessibility bar (no accessibility violations; usable on a small screen, including long and non-Latin titles without horizontal overflow) applied to the existing public marketing page.
- **FR-016**: Publishing duplicate article URLs/slugs MUST be prevented; a slug collision MUST be detected at publish/build time and fail loudly rather than silently producing duplicate or overwritten content.
- **FR-017**: The article library and the home page MUST cross-link (home → library, library → home, and library → each article) so the new content is reachable within at most two link hops from the home page.
- **FR-018**: Each article and the library index MUST declare a self-referential canonical URL in one preferred form (no query string, no token, no trailing-slash ambiguity). The indexing model MUST gain an explicit "indexable content page" classification for these pages, distinct from the existing home-only indexable marker and the deny-by-default private marker.
- **FR-019**: The crawl-control allow-rule that makes articles crawlable MUST be scoped to the article-library path prefix only and MUST NOT use a broad pattern that could re-expose any private, authenticated, or per-candidate token route. Adding the library MUST NOT make any previously non-crawlable route crawlable.
- **FR-020**: Every in-content link within an article or the library index MUST resolve to a public, indexable, non-token URL; no article or index body may link into an authenticated, internal, or per-candidate token route.
- **FR-021**: Each article MUST be a substantive, standalone answer with a unique title and summary; articles MUST NOT be near-duplicates of one another, and MUST NOT merely restate the home page's existing FAQ content.

### Key Entities *(include if feature involves data)*

- **Article**: A single piece of public, educational/marketing content on a Cadence-relevant theme. Key attributes: stable slug/URL, title, concise summary/lead, full body, publication date, optional last-updated date, publisher/author identity, and a set of related-article references. Contains only public content — never personal data or private links.
- **Article Library Index**: The collection view that lists all published articles (each with title and summary) and serves as the hub linking the home page to individual articles. Reflects the current set of published articles automatically.
- **Theme/Topic**: The subject area an article addresses (e.g., no-shows, candidate experience, scheduling, privacy). Used to group and cross-link related articles and to shape the editorial coverage of the library.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: The number of indexable, content-bearing public URLs grows from 1 (home only) to at least 6 at launch (home + library index + a launch floor of at least 4 articles); the library is designed to grow beyond this.
- **SC-002**: 100% of published articles appear in the sitemap and are reachable from the home page within at most two link hops.
- **SC-003**: 100% of published articles and the library index expose their full title and body text to a no-JavaScript crawler.
- **SC-004**: 100% of published articles carry valid Article structured data (headline, summary, publication date, publisher) AND breadcrumb structured data; the library index carries valid collection/item-list structured data — all passing structured-data validation.
- **SC-005**: An automated scan over every built article page, the library index, the sitemap, the structured-data blocks, and the AI-crawler guidance file finds 0 candidate tokens, 0 authenticated/internal routes, and 0 personal data.
- **SC-006**: The article library and each article meet the same accessibility and mobile-quality bar as the existing public marketing page (no accessibility violations; usable on a small screen; long/non-Latin titles produce no horizontal overflow).
- **SC-007**: Adding or retiring an article requires no manual edit to the sitemap or the library index — both reflect the change automatically on the next publish, and a retired article's sitemap entry, index entry, and canonical reference are all removed together.
- **SC-008**: In a non-production build, 100% of library and article pages remain non-indexable via both the crawl-control file and a per-page noindex signal.
- **SC-009**: A reader can move from the home page to any article and on to at least one topically related article using on-page links alone.
- **SC-010**: 0 private, authenticated, or per-candidate token routes appear in the sitemap or become crawlable as a result of the new crawl-control allow-rule (the allow-rule is scoped to the library path prefix only).
- **SC-011**: 100% of published articles' URLs are listed in the AI-crawler guidance file, and each article and the library index carries a last-modified date in the sitemap.
- **SC-012**: Attempting to publish two articles with the same slug fails the publish/build rather than producing two URLs or overwriting content.
- **SC-013**: 100% of articles render their publication date (and a last-updated date when revised) to readers, and 100% of in-content links resolve to public, non-token URLs.

## Assumptions

- **Static, build-time content is sufficient.** The articles are editorial/marketing content authored by the team, not user-generated or dynamic. No new database collection, backend service, or per-request server rendering is assumed; this mirrors the existing SEO surface, which is a frontend/CDN-and-build concern with no backend involvement.
- **Reuse of the existing SEO/AEO machinery.** The crawl-control posture, per-route indexing control, build-time origin injection, structured-data approach, automated artifact-scan guard, and accessibility/mobile gates established by the prior SEO feature (026-seo-aeo) are reused and extended rather than reinvented. Because that feature deliberately ships no server-side rendering, the no-JS readability requirement (FR-005) is met by delivering article content in a crawler-readable form at publish/build time (the exact mechanism — pre-render of article routes vs. statically authored article pages — is a planning decision, but a browser-only fetch-and-render approach is explicitly insufficient).
- **Editorial scope at launch is a small, high-quality set.** A starter set of roughly four to six articles on the highest-value themes is sufficient to demonstrate the indexing lift; the library is designed to grow but does not require dozens of articles to deliver value.
- **English-only at launch.** Articles ship in English; the structure should not preclude future localization, but multi-language content is out of scope for this feature.
- **No comments, on-site search, or personalization.** Reader-facing extras (commenting, article search, recommendation engines, gated content) are out of scope; the focus is indexable, extractable, internally-linked topical pages.
- **Deny-by-default privacy is non-negotiable.** The new public surface must not weaken the existing default-non-indexable, no-personal-data posture for the rest of the site.
