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
}

// --- body safety lint (allow-list, FR-011/FR-020) -------------------------------------------------

const PUBLIC_LINK_RE = /^(\/resources\/[a-z0-9-]+\/?|\/resources\/?|\/|#[a-z0-9-]*|https:\/\/[a-z0-9.-]+(?:\/[^\s"']*)?)$/i;
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
  ':root { color-scheme: light; }',
  'body { margin: 0; font: 16px/1.6 system-ui, -apple-system, Segoe UI, Roboto, sans-serif; color: #1a1a1a; background: #fff; }',
  'main { max-width: 44rem; margin: 0 auto; padding: 1.5rem 1rem 4rem; }',
  'a { color: #0b5cad; }',
  'h1 { font-size: 1.9rem; line-height: 1.2; }',
  'h2 { font-size: 1.35rem; margin-top: 2rem; }',
  'h3 { font-size: 1.1rem; }',
  '.lead { font-size: 1.15rem; color: #333; }',
  '.meta { color: #555; font-size: 0.9rem; }',
  'nav.crumbs { font-size: 0.9rem; margin-bottom: 1rem; }',
  'ul.cards { list-style: none; padding: 0; }',
  'ul.cards li { border: 1px solid #d6d6d6; border-radius: 8px; padding: 1rem; margin-bottom: 1rem; }',
  '.related a, .home-link { display: inline-block; min-height: 44px; line-height: 44px; padding: 0 0.75rem; }',
  'body, main, h1, h2, p, li { overflow-wrap: anywhere; word-break: break-word; }'
].join('\n  ');

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
  const canonical = ctx.originBase + RESOURCES_PATH + '/' + article.slug;
  const ogImage = ctx.originBase + '/assets/og-cadence.png';
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
    logo: ogImage
  };
  const breadcrumbLd = {
    '@context': 'https://schema.org',
    '@type': 'BreadcrumbList',
    itemListElement: [
      { '@type': 'ListItem', position: 1, name: 'Home', item: ctx.originBase + '/' },
      { '@type': 'ListItem', position: 2, name: 'Resources', item: ctx.originBase + RESOURCES_PATH },
      { '@type': 'ListItem', position: 3, name: article.title, item: canonical }
    ]
  };
  const articleLd = {
    '@context': 'https://schema.org',
    '@type': 'BlogPosting',
    headline: article.title,
    description: article.summary,
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
      related.map((r) => '<li><a href="' + RESOURCES_PATH + '/' + r.slug + '">' + escapeHtml(r.title) + '</a></li>').join('\n') +
      '\n</ul>\n</aside>'
    : '';

  const dateLine = updated
    ? 'Published ' + escapeHtml(published) + ' &middot; Updated ' + escapeHtml(updated)
    : 'Published ' + escapeHtml(published);

  return '<!doctype html>\n<html lang="en">\n<head>\n' +
    headCommon(article.title + ' | Cadence', article.summary, canonical, ogImage, ROBOTS_PLACEHOLDER) + '\n' +
    ldBlocks + '\n' +
    '<style>' + PAGE_STYLE + '</style>\n' +
    '</head>\n<body>\n<main>\n' +
    '<nav class="crumbs" aria-label="Breadcrumb">\n' +
    '<a href="/">Home</a> &rsaquo; <a href="' + RESOURCES_PATH + '">Resources</a> &rsaquo; ' +
    '<span aria-current="page">' + escapeHtml(article.title) + '</span>\n</nav>\n' +
    '<article>\n<h1>' + escapeHtml(article.title) + '</h1>\n' +
    '<p class="lead">' + escapeHtml(article.summary) + '</p>\n' +
    '<p class="meta">' + dateLine + '</p>\n' +
    article.bodyHtml + '\n</article>\n' +
    relatedHtml + '\n' +
    '<footer>\n<p><a class="home-link" href="/">&larr; Cadence home</a> &middot; ' +
    '<a class="home-link" href="' + RESOURCES_PATH + '">All resources</a></p>\n</footer>\n' +
    '</main>\n</body>\n</html>\n';
}

/** Assemble the library index page (full HTML document string). Indexable only when >= 1 article. */
export function assembleIndexPage(articles, ctx) {
  const canonical = ctx.originBase + RESOURCES_PATH;
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
        url: ctx.originBase + RESOURCES_PATH + '/' + a.slug,
        name: a.title
      }))
    }
  };

  // Empty-library: keep reachable but non-indexable (no thin indexable page).
  const robots = sorted.length ? ROBOTS_PLACEHOLDER : 'noindex,follow';

  const cards = sorted.length
    ? '<ul class="cards">\n' +
      sorted.map((a) =>
        '<li>\n<h2><a href="' + RESOURCES_PATH + '/' + a.slug + '">' + escapeHtml(a.title) + '</a></h2>\n' +
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
    '</head>\n<body>\n<main>\n' +
    '<nav class="crumbs" aria-label="Breadcrumb"><a href="/">Home</a> &rsaquo; <span aria-current="page">Resources</span></nav>\n' +
    '<h1>Cadence resources</h1>\n' +
    '<p class="lead">' + escapeHtml(description) + '</p>\n' +
    cards + '\n' +
    '<footer><p><a class="home-link" href="/">&larr; Cadence home</a></p></footer>\n' +
    '</main>\n</body>\n</html>\n';
}

// --- crawl artifacts ------------------------------------------------------------------------------

/** Build sitemap.xml from the article ALLOW-LIST only (never a route scan -- FR-007/SC-010). */
export function buildSitemap(articles, ctx) {
  const newest = articles.reduce((acc, a) => (lastmodOf(a) > acc ? lastmodOf(a) : acc), ctx.buildDate);
  const entries = [];
  entries.push(
    '  <url>\n    <loc>' + ctx.originBase + '/</loc>\n    <lastmod>' + newest +
    '</lastmod>\n    <changefreq>monthly</changefreq>\n    <priority>1.0</priority>\n  </url>'
  );
  if (articles.length) {
    entries.push(
      '  <url>\n    <loc>' + ctx.originBase + RESOURCES_PATH + '</loc>\n    <lastmod>' + newest +
      '</lastmod>\n    <changefreq>weekly</changefreq>\n    <priority>0.8</priority>\n  </url>'
    );
  }
  for (const a of [...articles].sort((x, y) => (x.slug < y.slug ? -1 : 1))) {
    entries.push(
      '  <url>\n    <loc>' + ctx.originBase + RESOURCES_PATH + '/' + a.slug + '</loc>\n    <lastmod>' +
      lastmodOf(a) + '</lastmod>\n    <changefreq>monthly</changefreq>\n    <priority>0.7</priority>\n  </url>'
    );
  }
  return '<?xml version="1.0" encoding="UTF-8"?>\n<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">\n' +
    entries.join('\n') + '\n</urlset>\n';
}

/** Append the per-article URL list to the base llms.txt (FR-013/SC-011). */
export function buildLlms(baseLlms, articles, ctx) {
  const trimmed = baseLlms.replace(/\s*$/, '');
  if (!articles.length) return trimmed + '\n';
  const lines = [...articles]
    .sort((a, b) => (lastmodOf(b) < lastmodOf(a) ? -1 : 1))
    .map((a) => '- ' + a.title + ': ' + ctx.originBase + RESOURCES_PATH + '/' + a.slug);
  return trimmed + '\n\n## Articles\n\n- Resources index: ' + ctx.originBase + RESOURCES_PATH + '\n' +
    lines.join('\n') + '\n';
}

/** Build an Atom feed for the library (Research D10). */
export function buildFeed(articles, ctx) {
  const self = ctx.originBase + RESOURCES_PATH + '/feed.xml';
  const sorted = [...articles].sort((a, b) => (lastmodOf(b) < lastmodOf(a) ? -1 : 1));
  const updated = (sorted[0] ? lastmodOf(sorted[0]) : ctx.buildDate) + 'T00:00:00Z';
  const entries = sorted.map((a) =>
    '  <entry>\n' +
    '    <title>' + escapeHtml(a.title) + '</title>\n' +
    '    <link href="' + ctx.originBase + RESOURCES_PATH + '/' + a.slug + '"/>\n' +
    '    <id>' + ctx.originBase + RESOURCES_PATH + '/' + a.slug + '</id>\n' +
    '    <updated>' + lastmodOf(a) + 'T00:00:00Z</updated>\n' +
    '    <summary>' + escapeHtml(a.summary) + '</summary>\n' +
    '  </entry>'
  ).join('\n');
  return '<?xml version="1.0" encoding="UTF-8"?>\n' +
    '<feed xmlns="http://www.w3.org/2005/Atom">\n' +
    '  <title>Cadence resources</title>\n' +
    '  <link href="' + ctx.originBase + RESOURCES_PATH + '"/>\n' +
    '  <link rel="self" href="' + self + '"/>\n' +
    '  <id>' + self + '</id>\n' +
    '  <updated>' + updated + '</updated>\n' +
    '  <author>\n    <name>Cadence</name>\n  </author>\n' +
    entries + '\n</feed>\n';
}

// --- orchestration --------------------------------------------------------------------------------

/** Validate + lint + de-dup the whole article set, then assemble every artifact.
 *  Throws ArticleBuildError on any violation (slug collision, bad meta, unsafe body, FAQ dup). */
export function buildArtifacts(articles, baseLlms, ctx) {
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

  const relatedMap = computeRelated(articles);
  const bySlug = new Map(articles.map((a) => [a.slug, a]));
  const pages = articles.map((a) => ({
    slug: a.slug,
    html: assembleArticlePage(a, (relatedMap.get(a.slug) || []).map((s) => bySlug.get(s)), ctx)
  }));

  return {
    pages,
    indexHtml: assembleIndexPage(articles, ctx),
    sitemap: buildSitemap(articles, ctx),
    llms: buildLlms(baseLlms, articles, ctx),
    feed: buildFeed(articles, ctx)
  };
}

/** Default build context (placeholder origin so seo-inject substitutes the real one). */
export function defaultContext(buildDate, homeFaqQuestions) {
  return { originBase: DEFAULT_ORIGIN_BASE, buildDate, homeFaqQuestions };
}
