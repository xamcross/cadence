/**
 * F61 (028-seo-content-library) -- PURE article-build library (plain ESM JavaScript).
 *
 * String in / string out ONLY. NO `node:fs`/`node:path`, so this module loads in BOTH Node (the
 * `scripts/seo-build-articles.mjs` CLI + the node:test runner) and the Karma/Jasmine browser bundle
 * (esbuild loads the explicit `.mjs` path) -- one source of truth, zero transpilation, works on Node 20.
 *
 * Assembles the static `/resources/` library: per-article pages, the library index, sitemap.xml,
 * llms.txt, and an Atom feed -- all carrying the `__CADENCE_PUBLIC_ORIGIN__` / `__CADENCE_ROBOTS__`
 * placeholders that `scripts/seo-inject-origin.mjs` substitutes at deploy time.
 *
 * FR-018 classification: an article page's `__CADENCE_ROBOTS__` meta IS the explicit "indexable content
 * page" marker (prod -> index,follow; non-prod -> noindex,nofollow), distinct from the SPA's
 * `route-seo.model` deny-by-default and the home-only `PUBLIC_HOME` marker. Because articles are static
 * files OUTSIDE the Angular route table, the `route-seo-inventory` "exactly one indexable route" holds.
 *
 * Security: body link-safety is an ALLOW-LIST (FR-020); meta values are HTML-escaped in HTML and
 * JSON-string-escaped (via JSON.stringify) in JSON-LD; the structured-data publisher/author is the
 * shared Organization @id, never a Person (D6).
 *
 * @typedef {'no-shows'|'candidate-experience'|'scheduling'|'privacy'} ThemeKey
 * @typedef {{q:string,a:string}} ArticleFaq
 * @typedef {{slug:string,title:string,summary:string,datePublished:string,dateUpdated?:string,theme:ThemeKey,related?:string[],faq?:ArticleFaq[],bodyHtml:string}} Article
 * @typedef {{originBase:string,buildDate:string,homeFaqQuestions:string[]}} BuildContext
 */

export const THEMES = {
  'no-shows': 'Reducing no-shows',
  'candidate-experience': 'Candidate experience',
  scheduling: 'Interview scheduling & calendar coordination',
  privacy: 'Privacy-safe recruiting'
};

export const DEFAULT_ORIGIN_BASE = 'https://__CADENCE_PUBLIC_ORIGIN__';
const ROBOTS_PLACEHOLDER = '__CADENCE_ROBOTS__';
const RESOURCES_PATH = '/resources';
const ORG_ID = '#organization';
const SLUG_RE = /^[a-z0-9]+(-[a-z0-9]+)*$/;
const MONTHS = [
  'January', 'February', 'March', 'April', 'May', 'June',
  'July', 'August', 'September', 'October', 'November', 'December'
];

export class ArticleBuildError extends Error {}

// --- escaping -------------------------------------------------------------------------------------

export function escapeHtml(s) {
  return String(s)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

/** Render a JSON-LD object as a safe <script> body. JSON.stringify performs all string-escaping
 *  (the JSON-string-escape requirement); we additionally neutralise the </script close sequence. */
export function jsonLd(obj) {
  return JSON.stringify(obj, null, 2).replace(/<\/(script)/gi, '<\\/$1');
}

// --- validation -----------------------------------------------------------------------------------

function isIsoDate(s) {
  return typeof s === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(s) && !Number.isNaN(Date.parse(s));
}

/** Validate a single article's metadata. `knownSlugs` resolves `related`. Throws on failure. */
export function validateMeta(meta, knownSlugs) {
  if (!meta || typeof meta !== 'object') throw new ArticleBuildError('invalid_meta: not an object');
  if (typeof meta.slug !== 'string' || !SLUG_RE.test(meta.slug)) {
    throw new ArticleBuildError('invalid_slug: ' + JSON.stringify(meta.slug));
  }
  if (typeof meta.title !== 'string' || meta.title.trim() === '') {
    throw new ArticleBuildError('missing_title: ' + meta.slug);
  }
  if (typeof meta.summary !== 'string' || meta.summary.trim() === '') {
    throw new ArticleBuildError('summary_missing: ' + meta.slug);
  }
  if (meta.summary.trim().split(/\s+/).length > 60) {
    throw new ArticleBuildError('summary_too_long: ' + meta.slug + ' (>60 words)');
  }
  if (!isIsoDate(meta.datePublished)) {
    throw new ArticleBuildError('invalid_date: ' + meta.slug + ' datePublished');
  }
  if (meta.dateUpdated !== undefined) {
    if (!isIsoDate(meta.dateUpdated)) throw new ArticleBuildError('invalid_update_date: ' + meta.slug);
    if (meta.dateUpdated < meta.datePublished) {
      throw new ArticleBuildError('invalid_update_date: ' + meta.slug + ' (before published)');
    }
  }
  if (!Object.prototype.hasOwnProperty.call(THEMES, meta.theme)) {
    throw new ArticleBuildError('unknown_theme: ' + meta.slug + ' ' + meta.theme);
  }
  for (const r of meta.related || []) {
    if (!knownSlugs.has(r)) throw new ArticleBuildError('unresolved_related: ' + meta.slug + ' -> ' + r);
    if (r === meta.slug) throw new ArticleBuildError('self_related: ' + meta.slug);
  }
  if (meta.ogImage !== undefined && !/^\/assets\/og\/[a-z0-9-]+\.png$/.test(meta.ogImage)) {
    throw new ArticleBuildError('invalid_og_image: ' + meta.slug + ' (must be /assets/og/<name>.png)');
  }
}

// --- body safety lint (allow-list, FR-011/FR-020) -------------------------------------------------

// Internal directory links MUST use the trailing-slash form (/resources/<slug>/, /terms/, /features/,
// /integrations/<x>/, ...): Cloudflare Pages serves the directory index at the slash URL and
// 308-redirects the no-slash form, so a no-slash internal link costs every visitor (and crawler) a
// redirect hop. The lint REJECTS the no-slash form outright — build-time enforcement of the served form.
const PUBLIC_LINK_RE = /^(\/|#[a-z0-9-]*|https:\/\/[a-z0-9.-]+(?:\/[^\s"']*)?|\/resources\/(?:[a-z0-9-]+\/)?|\/(?:terms|privacy|features|pricing|integrations|gdpr|small-teams)\/|\/(?:integrations|vs)\/[a-z0-9-]+\/)$/i;
const PII_TOKEN_RE = /token=|[a-z0-9._%+-]+@[a-z0-9.-]+\.[a-z]{2,}/i;

/** Reject an unsafe body fragment (defense-in-depth; content is first-party). Throws on violation. */
export function lintBody(slug, bodyHtml) {
  const lower = bodyHtml.toLowerCase();
  for (const bad of ['<script', '<iframe', '<object', '<embed', '<style', '<h1']) {
    if (lower.includes(bad)) throw new ArticleBuildError('unsafe_body: ' + slug + ' contains ' + bad);
  }
  if (/\son[a-z]+\s*=/i.test(bodyHtml)) {
    throw new ArticleBuildError('unsafe_body: ' + slug + ' has an inline event handler');
  }
  if (/(href|src)\s*=\s*["']\s*(javascript|data):/i.test(bodyHtml)) {
    throw new ArticleBuildError('unsafe_body: ' + slug + ' has a javascript:/data: URL');
  }
  if (PII_TOKEN_RE.test(bodyHtml)) {
    throw new ArticleBuildError('unsafe_body: ' + slug + ' contains a token/email sentinel');
  }
  for (const m of bodyHtml.matchAll(/(?:href|src)\s*=\s*["']([^"']*)["']/gi)) {
    const target = m[1].trim();
    if (!PUBLIC_LINK_RE.test(target)) {
      throw new ArticleBuildError('unsafe_body: ' + slug + ' link not on public allow-list: ' + target);
    }
  }
}

// --- related-article selection (reciprocal, theme-clustered) --------------------------------------

/** Reciprocal, topically-related slug list per article (FR-006/SC-009): explicit `related` +
 *  reciprocal back-links, then same-theme auto-fill. Returns Map<slug, slug[]>. */
export function computeRelated(articles) {
  const bySlug = new Map(articles.map((a) => [a.slug, a]));
  const out = new Map(articles.map((a) => [a.slug, new Set(a.related || [])]));
  for (const a of articles) {
    for (const r of a.related || []) {
      if (bySlug.has(r)) out.get(r).add(a.slug);
    }
  }
  for (const a of articles) {
    if (out.get(a.slug).size === 0) {
      for (const b of articles) {
        if (b.slug !== a.slug && b.theme === a.theme) out.get(a.slug).add(b.slug);
      }
    }
  }
  const result = new Map();
  for (const [slug, set] of out) {
    set.delete(slug); // never self-link (degenerate single-article-theme -> empty, no zero-related crash)
    result.set(slug, [...set].sort());
  }
  return result;
}

// --- FAQ anti-duplication gate (FR-021) -----------------------------------------------------------

function normalizeQuestion(q) {
  return String(q).toLowerCase().replace(/[^a-z0-9 ]+/g, ' ').replace(/\s+/g, ' ').trim();
}

/** Fail the build if an article headline/FAQ near-duplicates a home-FAQ question (D6/FR-021). */
export function faqDedupCheck(articles, homeFaqQuestions) {
  const home = (homeFaqQuestions || []).map(normalizeQuestion).filter(Boolean);
  for (const a of articles) {
    const candidates = [a.title].concat((a.faq || []).map((f) => f.q)).map(normalizeQuestion);
    for (const c of candidates) {
      for (const h of home) {
        if (c === h || (c.length > 12 && (c.includes(h) || h.includes(c)))) {
          throw new ArticleBuildError('faq_duplicate: "' + a.slug + '" duplicates home FAQ "' + h + '"');
        }
      }
    }
  }
}

// --- date helpers ---------------------------------------------------------------------------------

export function humanDate(iso) {
  const parts = iso.split('-').map(Number);
  return MONTHS[parts[1] - 1] + ' ' + parts[2] + ', ' + parts[0];
}

function lastmodOf(a) {
  return a.dateUpdated || a.datePublished;
}

// --- page assembly --------------------------------------------------------------------------------

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

/** Shared branded site header for every static page (#1/#2). Self-contained: text wordmark + inline
 *  SVG mark (no image request), primary nav, and the prospect CTA.
 *  The CTA points to the home page ("/"), NOT the /request-access SPA route: the CI /resources (F61,
 *  FR-011/SC-005) and legal (F71, FR-011/SC-010) artifact scans deny app-entry routes — including
 *  /request-access — inside static content pages. Home is the correct static->SPA bridge; its hero
 *  leads with the Request access CTA. */
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
    '<a class="site-header__cta" href="/">Request access</a>\n' +
    '</header>\n';
}

function headCommon(title, description, canonical, ogImage, robots, ogType) {
  const t = escapeHtml(title);
  const d = escapeHtml(description);
  return [
    '<meta charset="utf-8">',
    '<title>' + t + '</title>',
    '<meta name="viewport" content="width=device-width, initial-scale=1">',
    '<meta name="description" content="' + d + '">',
    '<meta name="robots" content="' + robots + '">',
    '<link rel="canonical" href="' + canonical + '">',
    '<link rel="alternate" hreflang="en" href="' + canonical + '">',
    '<link rel="alternate" hreflang="x-default" href="' + canonical + '">',
    '<meta name="referrer" content="no-referrer">',
    '<meta property="og:type" content="' + (ogType || 'article') + '">',
    '<meta property="og:title" content="' + t + '">',
    '<meta property="og:description" content="' + d + '">',
    '<meta property="og:url" content="' + canonical + '">',
    '<meta property="og:image" content="' + ogImage + '">',
    '<meta name="twitter:card" content="summary_large_image">',
    '<meta name="twitter:title" content="' + t + '">',
    '<meta name="twitter:description" content="' + d + '">',
    '<meta name="twitter:image" content="' + ogImage + '">',
    '<link rel="icon" type="image/svg+xml" href="/assets/favicon.svg">'
  ].join('\n');
}

/** Assemble one article page (full HTML document string). */
export function assembleArticlePage(article, related, ctx) {
  // Trailing slash: Cloudflare Pages serves the directory-index page at /resources/<slug>/ and
  // 308-redirects the no-slash form, so the self-canonical must use the slash (matches served URL).
  const canonical = ctx.originBase + RESOURCES_PATH + '/' + article.slug + '/';
  // Per-article social card when authored (meta.ogImage, validated shape); brand card otherwise.
  // The Organization logo ALWAYS stays the brand card — it identifies the publisher, not the article.
  const ogImage = ctx.originBase + (article.ogImage || '/assets/og-cadence.png');
  const orgLogo = ctx.originBase + '/assets/og-cadence.png';
  const orgId = ctx.originBase + '/' + ORG_ID;
  const published = humanDate(article.datePublished);
  const updated = article.dateUpdated ? humanDate(article.dateUpdated) : null;

  // Inline the Organization node ON THE ARTICLE PAGE so publisher.logo resolves in Google's
  // Rich Results validator (it parses one page's JSON-LD graph in isolation; a cross-page @id
  // to index.html would not resolve). Shares the same @id as index.html's Organization (D6).
  const orgLd = {
    '@context': 'https://schema.org',
    '@type': 'Organization',
    '@id': orgId,
    name: 'Cadence',
    url: ctx.originBase + '/',
    logo: orgLogo
  };
  const breadcrumbLd = {
    '@context': 'https://schema.org',
    '@type': 'BreadcrumbList',
    itemListElement: [
      { '@type': 'ListItem', position: 1, name: 'Home', item: ctx.originBase + '/' },
      { '@type': 'ListItem', position: 2, name: 'Resources', item: ctx.originBase + RESOURCES_PATH + '/' },
      { '@type': 'ListItem', position: 3, name: article.title, item: canonical }
    ]
  };
  const articleLd = {
    '@context': 'https://schema.org',
    '@type': 'BlogPosting',
    headline: article.title,
    description: article.summary,
    image: ogImage,
    datePublished: article.datePublished,
    dateModified: lastmodOf(article),
    mainEntityOfPage: canonical,
    publisher: { '@id': orgId },
    author: { '@id': orgId }
  };
  const faqLd = article.faq && article.faq.length
    ? {
        '@context': 'https://schema.org',
        '@type': 'FAQPage',
        mainEntity: article.faq.map((f) => ({
          '@type': 'Question',
          name: f.q,
          acceptedAnswer: { '@type': 'Answer', text: f.a }
        }))
      }
    : null;
  const speakableLd = {
    '@context': 'https://schema.org',
    '@type': 'WebPage',
    url: canonical,
    speakable: { '@type': 'SpeakableSpecification', cssSelector: ['h1', '.lead'] }
  };

  const ldBlocks = [orgLd, breadcrumbLd, articleLd, speakableLd, faqLd]
    .filter(Boolean)
    .map((b) => '<script type="application/ld+json">\n' + jsonLd(b) + '\n</script>')
    .join('\n');

  const relatedHtml = related.length
    ? '<aside class="related" aria-labelledby="related-h">\n<h2 id="related-h">Related articles</h2>\n<ul>\n' +
      related.map((r) => '<li><a href="' + RESOURCES_PATH + '/' + r.slug + '/">' + escapeHtml(r.title) + '</a></li>').join('\n') +
      '\n</ul>\n</aside>'
    : '';

  const dateLine = updated
    ? 'Published ' + escapeHtml(published) + ' &middot; Updated ' + escapeHtml(updated)
    : 'Published ' + escapeHtml(published);

  return '<!doctype html>\n<html lang="en">\n<head>\n' +
    headCommon(article.title + ' | Cadence', article.summary, canonical, ogImage, ROBOTS_PLACEHOLDER) + '\n' +
    ldBlocks + '\n' +
    '<style>' + PAGE_STYLE + '</style>\n' +
    '</head>\n<body>\n' + siteHeader() + '<main>\n' +
    '<nav class="crumbs" aria-label="Breadcrumb">\n' +
    '<a href="/">Home</a> &rsaquo; <a href="' + RESOURCES_PATH + '/">Resources</a> &rsaquo; ' +
    '<span aria-current="page">' + escapeHtml(article.title) + '</span>\n</nav>\n' +
    '<article>\n<h1>' + escapeHtml(article.title) + '</h1>\n' +
    '<p class="lead">' + escapeHtml(article.summary) + '</p>\n' +
    '<p class="meta">' + dateLine + '</p>\n' +
    article.bodyHtml + '\n</article>\n' +
    relatedHtml + '\n' +
    '<footer>\n<p><a class="home-link" href="/">&larr; Cadence home</a> &middot; ' +
    '<a class="home-link" href="' + RESOURCES_PATH + '/">All resources</a></p>\n</footer>\n' +
    '</main>\n</body>\n</html>\n';
}

/** Assemble the library index page (full HTML document string). Indexable only when >= 1 article. */
export function assembleIndexPage(articles, ctx) {
  const canonical = ctx.originBase + RESOURCES_PATH + '/';
  const ogImage = ctx.originBase + '/assets/og-cadence.png';
  const sorted = [...articles].sort((a, b) => (lastmodOf(b) < lastmodOf(a) ? -1 : 1));
  const description =
    'Practical guides on interview scheduling, reducing no-shows, candidate experience, and privacy-safe recruiting from Cadence.';

  const itemListLd = {
    '@context': 'https://schema.org',
    '@type': 'CollectionPage',
    name: 'Cadence Resources',
    url: canonical,
    isPartOf: { '@id': ctx.originBase + '/' + ORG_ID },
    mainEntity: {
      '@type': 'ItemList',
      itemListElement: sorted.map((a, i) => ({
        '@type': 'ListItem',
        position: i + 1,
        url: ctx.originBase + RESOURCES_PATH + '/' + a.slug + '/',
        name: a.title
      }))
    }
  };

  // Empty-library: keep reachable but non-indexable (no thin indexable page).
  const robots = sorted.length ? ROBOTS_PLACEHOLDER : 'noindex,follow';

  const cards = sorted.length
    ? '<ul class="cards">\n' +
      sorted.map((a) =>
        '<li>\n<h2><a href="' + RESOURCES_PATH + '/' + a.slug + '/">' + escapeHtml(a.title) + '</a></h2>\n' +
        '<p>' + escapeHtml(a.summary) + '</p>\n' +
        '<p class="meta">Published ' + escapeHtml(humanDate(a.datePublished)) + '</p>\n</li>'
      ).join('\n') +
      '\n</ul>'
    : '<p>New guides are on the way. Check back soon.</p>';

  return '<!doctype html>\n<html lang="en">\n<head>\n' +
    headCommon('Resources | Cadence', description, canonical, ogImage, robots, 'website') + '\n' +
    '<link rel="alternate" type="application/atom+xml" title="Cadence resources" href="' + RESOURCES_PATH + '/feed.xml">\n' +
    '<script type="application/ld+json">\n' + jsonLd(itemListLd) + '\n</script>\n' +
    '<style>' + PAGE_STYLE + '</style>\n' +
    '</head>\n<body>\n' + siteHeader() + '<main>\n' +
    '<nav class="crumbs" aria-label="Breadcrumb"><a href="/">Home</a> &rsaquo; <span aria-current="page">Resources</span></nav>\n' +
    '<h1>Cadence resources</h1>\n' +
    '<p class="lead">' + escapeHtml(description) + '</p>\n' +
    cards + '\n' +
    '<footer><p><a class="home-link" href="/">&larr; Cadence home</a></p></footer>\n' +
    '</main>\n</body>\n</html>\n';
}

// --- legal pages (031-terms-privacy-notice) -------------------------------------------------------
// Conventional top-level /terms and /privacy, published as static HTML OUTSIDE the SPA route table
// (the route-seo-inventory "exactly one indexable route" invariant holds). Non-article schema:
// WebPage + BreadcrumbList (+ shared Organization), never BlogPosting/Article/FAQPage (FR-022f).

/** Validate a legal-page meta (terms|privacy). Throws on failure. */
export function validateLegalMeta(meta) {
  if (!meta || typeof meta !== 'object') throw new ArticleBuildError('invalid_legal_meta: not an object');
  if (meta.slug !== 'terms' && meta.slug !== 'privacy') {
    throw new ArticleBuildError('invalid_legal_slug: ' + JSON.stringify(meta.slug));
  }
  if (typeof meta.title !== 'string' || meta.title.trim() === '') {
    throw new ArticleBuildError('missing_legal_title: ' + meta.slug);
  }
  if (typeof meta.description !== 'string' || meta.description.trim() === '') {
    throw new ArticleBuildError('missing_legal_description: ' + meta.slug);
  }
  if (typeof meta.version !== 'string' || meta.version.trim() === '') {
    throw new ArticleBuildError('missing_legal_version: ' + meta.slug);
  }
  if (!isIsoDate(meta.lastUpdated)) throw new ArticleBuildError('invalid_legal_date: ' + meta.slug);
  if (typeof meta.draft !== 'boolean') throw new ArticleBuildError('invalid_legal_draft: ' + meta.slug);
}

/** Body-safety lint for a legal page (allow-list incl. /terms, /privacy cross-links). Throws. */
export function lintLegalBody(slug, bodyHtml) {
  const lower = bodyHtml.toLowerCase();
  for (const bad of ['<script', '<iframe', '<object', '<embed', '<style', '<h1']) {
    if (lower.includes(bad)) throw new ArticleBuildError('unsafe_legal_body: ' + slug + ' contains ' + bad);
  }
  if (/\son[a-z]+\s*=/i.test(bodyHtml)) {
    throw new ArticleBuildError('unsafe_legal_body: ' + slug + ' has an inline event handler');
  }
  if (/(href|src)\s*=\s*["']\s*(javascript|data):/i.test(bodyHtml)) {
    throw new ArticleBuildError('unsafe_legal_body: ' + slug + ' has a javascript:/data: URL');
  }
  if (PII_TOKEN_RE.test(bodyHtml)) {
    throw new ArticleBuildError('unsafe_legal_body: ' + slug + ' contains a token/email sentinel');
  }
  for (const m of bodyHtml.matchAll(/(?:href|src)\s*=\s*["']([^"']*)["']/gi)) {
    const target = m[1].trim();
    if (!PUBLIC_LINK_RE.test(target)) {
      throw new ArticleBuildError('unsafe_legal_body: ' + slug + ' link not on allow-list: ' + target);
    }
  }
}

/** Assemble one legal page (full HTML document string). Canonical is the trailing-slash served form. */
export function assembleLegalPage(doc, ctx) {
  const canonical = ctx.originBase + '/' + doc.slug + '/';
  const ogImage = ctx.originBase + '/assets/og-cadence.png';
  const orgId = ctx.originBase + '/' + ORG_ID;
  const updated = humanDate(doc.lastUpdated);
  const other = doc.slug === 'terms'
    ? { href: '/privacy/', label: 'Privacy Notice' }
    : { href: '/terms/', label: 'Terms & Conditions' };

  const orgLd = {
    '@context': 'https://schema.org',
    '@type': 'Organization',
    '@id': orgId,
    name: 'Cadence',
    url: ctx.originBase + '/',
    logo: ogImage
  };
  const breadcrumbLd = {
    '@context': 'https://schema.org',
    '@type': 'BreadcrumbList',
    itemListElement: [
      { '@type': 'ListItem', position: 1, name: 'Home', item: ctx.originBase + '/' },
      { '@type': 'ListItem', position: 2, name: doc.title, item: canonical }
    ]
  };
  const webPageLd = {
    '@context': 'https://schema.org',
    '@type': 'WebPage',
    '@id': canonical,
    url: canonical,
    name: doc.title,
    description: doc.description,
    inLanguage: 'en',
    isPartOf: { '@id': orgId },
    publisher: { '@id': orgId },
    dateModified: doc.lastUpdated
  };
  const ldBlocks = [orgLd, breadcrumbLd, webPageLd]
    .map((b) => '<script type="application/ld+json">\n' + jsonLd(b) + '\n</script>')
    .join('\n');

  const draftBanner = doc.draft
    ? '<div class="draft-banner" role="note">Draft - pending legal review. This text is a template and is not yet binding.</div>\n'
    : '';

  return '<!doctype html>\n<html lang="en">\n<head>\n' +
    headCommon(doc.title + ' | Cadence', doc.description, canonical, ogImage, ROBOTS_PLACEHOLDER, 'website') + '\n' +
    ldBlocks + '\n' +
    '<style>' + PAGE_STYLE + '</style>\n' +
    '</head>\n<body>\n' + siteHeader() + '<main>\n' +
    draftBanner +
    '<nav class="crumbs" aria-label="Breadcrumb"><a href="/">Home</a> &rsaquo; ' +
    '<span aria-current="page">' + escapeHtml(doc.title) + '</span></nav>\n' +
    '<article>\n<h1>' + escapeHtml(doc.title) + '</h1>\n' +
    '<p class="meta">Version ' + escapeHtml(doc.version) + ' &middot; Last updated ' + escapeHtml(updated) + '</p>\n' +
    doc.bodyHtml + '\n</article>\n' +
    '<footer>\n<p><a class="home-link" href="' + other.href + '">' + escapeHtml(other.label) + '</a> &middot; ' +
    '<a class="home-link" href="/">&larr; Cadence home</a></p>\n</footer>\n' +
    '</main>\n</body>\n</html>\n';
}

// --- marketing pages (seo/audit-improvements) -----------------------------------------------------
// Commercial static pages (/features, /pricing, /integrations/<x>, /vs/<x>) published OUTSIDE the SPA
// route table exactly like the legal pages, so the route-seo-inventory "exactly one indexable route"
// invariant holds. Schema: WebPage + BreadcrumbList (+ shared Organization, + optional FAQPage for
// AEO). They join sitemap.xml + llms.txt but are excluded from the Atom feed (articles only).

const PAGE_SLUG_RE = /^[a-z0-9]+(-[a-z0-9]+)*(\/[a-z0-9]+(-[a-z0-9]+)*)?$/;
// First path segment must not shadow a private/token route (robots-Disallowed), a reserved artifact
// path, or a content type with its own pipeline. Keep in sync with robots.txt's Disallow list.
const RESERVED_PAGE_PREFIXES = new Set([
  'resources', 'terms', 'privacy', 'assets', 'api', 'oauth2',
  'schedule', 'booking', 'confirm', 'status', 'feedback', 'app', 'admin', 'pipeline', 'scheduling',
  'calendar', 'workspace', 'interview-templates', 'email-templates', 'login', 'accept-invite',
  'request-access', 'reset', 'not-authorized'
]);

/** Validate a marketing-page meta. Throws on failure. */
export function validatePageMeta(meta) {
  if (!meta || typeof meta !== 'object') throw new ArticleBuildError('invalid_page_meta: not an object');
  if (typeof meta.slug !== 'string' || !PAGE_SLUG_RE.test(meta.slug)) {
    throw new ArticleBuildError('invalid_page_slug: ' + JSON.stringify(meta.slug));
  }
  if (RESERVED_PAGE_PREFIXES.has(meta.slug.split('/')[0])) {
    throw new ArticleBuildError('reserved_page_slug: ' + meta.slug);
  }
  if (typeof meta.title !== 'string' || meta.title.trim() === '') {
    throw new ArticleBuildError('missing_page_title: ' + meta.slug);
  }
  if (typeof meta.description !== 'string' || meta.description.trim() === '') {
    throw new ArticleBuildError('missing_page_description: ' + meta.slug);
  }
  if (meta.description.length > 160) {
    throw new ArticleBuildError('page_description_too_long: ' + meta.slug + ' (>160 chars, it is the meta description)');
  }
  if (!isIsoDate(meta.lastUpdated)) throw new ArticleBuildError('invalid_page_date: ' + meta.slug);
  for (const f of meta.faq || []) {
    if (!f || typeof f.q !== 'string' || typeof f.a !== 'string' || !f.q.trim() || !f.a.trim()) {
      throw new ArticleBuildError('invalid_page_faq: ' + meta.slug);
    }
  }
  if (meta.ogImage !== undefined && !/^\/assets\/og\/[a-z0-9-]+\.png$/.test(meta.ogImage)) {
    throw new ArticleBuildError('invalid_page_og_image: ' + meta.slug + ' (must be /assets/og/<name>.png)');
  }
}

/** Body-safety lint for a marketing page (same allow-list as articles/legal). Throws. */
export function lintPageBody(slug, bodyHtml) {
  const lower = bodyHtml.toLowerCase();
  for (const bad of ['<script', '<iframe', '<object', '<embed', '<style', '<h1']) {
    if (lower.includes(bad)) throw new ArticleBuildError('unsafe_page_body: ' + slug + ' contains ' + bad);
  }
  if (/\son[a-z]+\s*=/i.test(bodyHtml)) {
    throw new ArticleBuildError('unsafe_page_body: ' + slug + ' has an inline event handler');
  }
  if (/(href|src)\s*=\s*["']\s*(javascript|data):/i.test(bodyHtml)) {
    throw new ArticleBuildError('unsafe_page_body: ' + slug + ' has a javascript:/data: URL');
  }
  if (PII_TOKEN_RE.test(bodyHtml)) {
    throw new ArticleBuildError('unsafe_page_body: ' + slug + ' contains a token/email sentinel');
  }
  for (const m of bodyHtml.matchAll(/(?:href|src)\s*=\s*["']([^"']*)["']/gi)) {
    const target = m[1].trim();
    if (!PUBLIC_LINK_RE.test(target)) {
      throw new ArticleBuildError('unsafe_page_body: ' + slug + ' link not on public allow-list: ' + target);
    }
  }
}

/** Assemble one marketing page (full HTML document string). Canonical is the trailing-slash form. */
export function assembleMarketingPage(page, ctx) {
  const canonical = ctx.originBase + '/' + page.slug + '/';
  const ogImage = ctx.originBase + (page.ogImage || '/assets/og-cadence.png');
  const orgLogo = ctx.originBase + '/assets/og-cadence.png';
  const orgId = ctx.originBase + '/' + ORG_ID;
  const updated = humanDate(page.lastUpdated);

  const orgLd = {
    '@context': 'https://schema.org',
    '@type': 'Organization',
    '@id': orgId,
    name: 'Cadence',
    url: ctx.originBase + '/',
    logo: orgLogo
  };
  // Nested integration pages get the /integrations/ hub as an intermediate crumb (it is a real page).
  const crumbs = [{ '@type': 'ListItem', position: 1, name: 'Home', item: ctx.originBase + '/' }];
  if (page.slug.startsWith('integrations/')) {
    crumbs.push({ '@type': 'ListItem', position: 2, name: 'Integrations', item: ctx.originBase + '/integrations/' });
  }
  crumbs.push({ '@type': 'ListItem', position: crumbs.length + 1, name: page.title, item: canonical });
  const breadcrumbLd = { '@context': 'https://schema.org', '@type': 'BreadcrumbList', itemListElement: crumbs };
  const webPageLd = {
    '@context': 'https://schema.org',
    '@type': 'WebPage',
    '@id': canonical,
    url: canonical,
    name: page.title,
    description: page.description,
    inLanguage: 'en',
    isPartOf: { '@id': orgId },
    publisher: { '@id': orgId },
    dateModified: page.lastUpdated
  };
  const faqLd = page.faq && page.faq.length
    ? {
        '@context': 'https://schema.org',
        '@type': 'FAQPage',
        mainEntity: page.faq.map((f) => ({
          '@type': 'Question',
          name: f.q,
          acceptedAnswer: { '@type': 'Answer', text: f.a }
        }))
      }
    : null;
  const ldBlocks = [orgLd, breadcrumbLd, webPageLd, faqLd]
    .filter(Boolean)
    .map((b) => '<script type="application/ld+json">\n' + jsonLd(b) + '\n</script>')
    .join('\n');

  const crumbHtml = page.slug.startsWith('integrations/')
    ? '<a href="/">Home</a> &rsaquo; <a href="/integrations/">Integrations</a> &rsaquo; ' +
      '<span aria-current="page">' + escapeHtml(page.title) + '</span>'
    : '<a href="/">Home</a> &rsaquo; <span aria-current="page">' + escapeHtml(page.title) + '</span>';

  return '<!doctype html>\n<html lang="en">\n<head>\n' +
    headCommon(page.title + ' | Cadence', page.description, canonical, ogImage, ROBOTS_PLACEHOLDER, 'website') + '\n' +
    ldBlocks + '\n' +
    '<style>' + PAGE_STYLE + '</style>\n' +
    '</head>\n<body>\n' + siteHeader() + '<main>\n' +
    '<nav class="crumbs" aria-label="Breadcrumb">' + crumbHtml + '</nav>\n' +
    '<article>\n<h1>' + escapeHtml(page.title) + '</h1>\n' +
    '<p class="lead">' + escapeHtml(page.description) + '</p>\n' +
    '<p class="meta">Updated ' + escapeHtml(updated) + '</p>\n' +
    page.bodyHtml + '\n</article>\n' +
    '<footer>\n<p><a class="home-link" href="/">&larr; Cadence home</a> &middot; ' +
    '<a class="home-link" href="/features/">Features</a> &middot; ' +
    '<a class="home-link" href="/pricing/">Pricing</a> &middot; ' +
    '<a class="home-link" href="/integrations/">Integrations</a> &middot; ' +
    '<a class="home-link" href="/resources/">Resources</a></p>\n</footer>\n' +
    '</main>\n</body>\n</html>\n';
}

// --- crawl artifacts ------------------------------------------------------------------------------

/** Build sitemap.xml from the article + legal + marketing ALLOW-LIST only (never a route scan -- FR-007/SC-010). */
export function buildSitemap(articles, ctx, legalPages = [], marketingPages = []) {
  const newest = articles.reduce((acc, a) => (lastmodOf(a) > acc ? lastmodOf(a) : acc), ctx.buildDate);
  const entries = [];
  entries.push(
    '  <url>\n    <loc>' + ctx.originBase + '/</loc>\n    <lastmod>' + newest +
    '</lastmod>\n    <changefreq>monthly</changefreq>\n    <priority>1.0</priority>\n  </url>'
  );
  if (articles.length) {
    entries.push(
      '  <url>\n    <loc>' + ctx.originBase + RESOURCES_PATH + '/</loc>\n    <lastmod>' + newest +
      '</lastmod>\n    <changefreq>weekly</changefreq>\n    <priority>0.8</priority>\n  </url>'
    );
  }
  for (const p of [...marketingPages].sort((x, y) => (x.slug < y.slug ? -1 : 1))) {
    entries.push(
      '  <url>\n    <loc>' + ctx.originBase + '/' + p.slug + '/</loc>\n    <lastmod>' +
      p.lastUpdated + '</lastmod>\n    <changefreq>monthly</changefreq>\n    <priority>0.8</priority>\n  </url>'
    );
  }
  for (const a of [...articles].sort((x, y) => (x.slug < y.slug ? -1 : 1))) {
    entries.push(
      '  <url>\n    <loc>' + ctx.originBase + RESOURCES_PATH + '/' + a.slug + '/</loc>\n    <lastmod>' +
      lastmodOf(a) + '</lastmod>\n    <changefreq>monthly</changefreq>\n    <priority>0.7</priority>\n  </url>'
    );
  }
  for (const d of [...legalPages].sort((x, y) => (x.slug < y.slug ? -1 : 1))) {
    entries.push(
      '  <url>\n    <loc>' + ctx.originBase + '/' + d.slug + '/</loc>\n    <lastmod>' +
      d.lastUpdated + '</lastmod>\n    <changefreq>yearly</changefreq>\n    <priority>0.5</priority>\n  </url>'
    );
  }
  return '<?xml version="1.0" encoding="UTF-8"?>\n<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">\n' +
    entries.join('\n') + '\n</urlset>\n';
}

/** Append the marketing, per-article, and legal URL lists to the base llms.txt (FR-013/SC-011, FR-022c). */
export function buildLlms(baseLlms, articles, ctx, legalPages = [], marketingPages = []) {
  let out = baseLlms.replace(/\s*$/, '');
  if (marketingPages.length) {
    const pageLines = [...marketingPages]
      .sort((a, b) => (a.slug < b.slug ? -1 : 1))
      .map((p) => '- ' + p.title + ': ' + ctx.originBase + '/' + p.slug + '/');
    out += '\n\n## Product\n\n' + pageLines.join('\n');
  }
  if (articles.length) {
    const lines = [...articles]
      .sort((a, b) => (lastmodOf(b) < lastmodOf(a) ? -1 : 1))
      .map((a) => '- ' + a.title + ': ' + ctx.originBase + RESOURCES_PATH + '/' + a.slug + '/');
    out += '\n\n## Articles\n\n- Resources index: ' + ctx.originBase + RESOURCES_PATH + '/\n' + lines.join('\n');
  }
  if (legalPages.length) {
    const legalLines = [...legalPages]
      .sort((a, b) => (a.slug < b.slug ? -1 : 1))
      .map((d) => '- ' + d.title + ': ' + ctx.originBase + '/' + d.slug + '/');
    out += '\n\n## Legal\n\n' + legalLines.join('\n');
  }
  return out + '\n';
}

/** Build an Atom feed for the library (Research D10). */
export function buildFeed(articles, ctx) {
  const self = ctx.originBase + RESOURCES_PATH + '/feed.xml';
  const sorted = [...articles].sort((a, b) => (lastmodOf(b) < lastmodOf(a) ? -1 : 1));
  const updated = (sorted[0] ? lastmodOf(sorted[0]) : ctx.buildDate) + 'T00:00:00Z';
  const entries = sorted.map((a) =>
    '  <entry>\n' +
    '    <title>' + escapeHtml(a.title) + '</title>\n' +
    '    <link href="' + ctx.originBase + RESOURCES_PATH + '/' + a.slug + '/"/>\n' +
    '    <id>' + ctx.originBase + RESOURCES_PATH + '/' + a.slug + '/</id>\n' +
    '    <updated>' + lastmodOf(a) + 'T00:00:00Z</updated>\n' +
    '    <summary>' + escapeHtml(a.summary) + '</summary>\n' +
    '  </entry>'
  ).join('\n');
  return '<?xml version="1.0" encoding="UTF-8"?>\n' +
    '<feed xmlns="http://www.w3.org/2005/Atom">\n' +
    '  <title>Cadence resources</title>\n' +
    '  <link href="' + ctx.originBase + RESOURCES_PATH + '/"/>\n' +
    '  <link rel="self" href="' + self + '"/>\n' +
    '  <id>' + self + '</id>\n' +
    '  <updated>' + updated + '</updated>\n' +
    '  <author>\n    <name>Cadence</name>\n  </author>\n' +
    entries + '\n</feed>\n';
}

// --- orchestration --------------------------------------------------------------------------------

/** Validate + lint + de-dup the whole article set, then assemble every artifact.
 *  Throws ArticleBuildError on any violation (slug collision, bad meta, unsafe body, FAQ dup). */
export function buildArtifacts(articles, baseLlms, ctx, legalPages = [], marketingPages = []) {
  const seen = new Set();
  const seenTitles = new Set();
  for (const a of articles) {
    if (seen.has(a.slug)) throw new ArticleBuildError('duplicate_slug: ' + a.slug);
    seen.add(a.slug);
    const titleKey = normalizeQuestion(a.title);
    if (titleKey && seenTitles.has(titleKey)) throw new ArticleBuildError('duplicate_title: ' + a.slug);
    seenTitles.add(titleKey);
  }
  const knownSlugs = new Set(articles.map((a) => a.slug));
  for (const a of articles) {
    validateMeta(a, knownSlugs);
    lintBody(a.slug, a.bodyHtml);
  }
  faqDedupCheck(articles, ctx.homeFaqQuestions);

  const seenLegal = new Set();
  for (const d of legalPages) {
    validateLegalMeta(d);
    if (seenLegal.has(d.slug)) throw new ArticleBuildError('duplicate_legal_slug: ' + d.slug);
    seenLegal.add(d.slug);
    lintLegalBody(d.slug, d.bodyHtml);
  }

  const seenPage = new Set();
  for (const p of marketingPages) {
    validatePageMeta(p);
    if (seenPage.has(p.slug)) throw new ArticleBuildError('duplicate_page_slug: ' + p.slug);
    seenPage.add(p.slug);
    lintPageBody(p.slug, p.bodyHtml);
  }
  // Marketing FAQPage questions must not near-duplicate the home FAQ either (same FR-021 gate).
  faqDedupCheck(marketingPages.map((p) => ({ slug: p.slug, title: p.title, faq: p.faq })), ctx.homeFaqQuestions);

  const relatedMap = computeRelated(articles);
  const bySlug = new Map(articles.map((a) => [a.slug, a]));
  const pages = articles.map((a) => ({
    slug: a.slug,
    html: assembleArticlePage(a, (relatedMap.get(a.slug) || []).map((s) => bySlug.get(s)), ctx)
  }));

  return {
    pages,
    indexHtml: assembleIndexPage(articles, ctx),
    legalPages: legalPages.map((d) => ({ slug: d.slug, html: assembleLegalPage(d, ctx) })),
    marketingPages: marketingPages.map((p) => ({ slug: p.slug, html: assembleMarketingPage(p, ctx) })),
    sitemap: buildSitemap(articles, ctx, legalPages, marketingPages),
    llms: buildLlms(baseLlms, articles, ctx, legalPages, marketingPages),
    feed: buildFeed(articles, ctx)
  };
}

/** Default build context (placeholder origin so seo-inject substitutes the real one). */
export function defaultContext(buildDate, homeFaqQuestions) {
  return { originBase: DEFAULT_ORIGIN_BASE, buildDate, homeFaqQuestions };
}
