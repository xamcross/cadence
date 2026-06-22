// @ts-nocheck
// F61 (028-seo-content-library) unit + accessibility tests for the pure article-build library.
// The lib is plain ESM (.mjs) so it is shared verbatim by the Node CLI; esbuild loads the explicit
// .mjs path here. @ts-nocheck because there is no .d.ts for the .mjs (the runtime contract is what we
// assert). The axe gate renders the GENERATED static HTML into the Karma DOM (FR-015/SC-006).
import {
  buildArtifacts,
  defaultContext,
  computeRelated,
  faqDedupCheck,
  lintBody,
  buildSitemap,
  buildLlms,
  buildFeed,
  ArticleBuildError
} from './article-build.lib.mjs';
import { attachToBody, axeViolations, detachFromBody } from '../../../testing/axe';

const HOME_FAQ = [
  'What is Cadence?',
  'Do candidates need to create an account?',
  'Which calendars and ATS does Cadence support?',
  'Is candidate data GDPR-safe?'
];

function art(over) {
  return {
    slug: 'a-one',
    title: 'Reducing interview no-shows in practice',
    summary: 'A short lead that answers the question.',
    datePublished: '2026-06-01',
    theme: 'no-shows',
    related: [],
    bodyHtml: '<h2>Section</h2><p>Body with a <a href="/resources/a-two">link</a>.</p>',
    ...over
  };
}

const FIXTURE = [
  art({ slug: 'a-one', theme: 'no-shows', related: ['a-two'] }),
  art({
    slug: 'a-two',
    title: 'Candidate experience guide',
    theme: 'candidate-experience',
    datePublished: '2026-06-02',
    related: [],
    faq: [{ q: 'How fast should recruiters reply?', a: 'Within a day or two.' }],
    bodyHtml: '<h2>Tips</h2><p>Body text here.</p>'
  })
];

function ctx() {
  return defaultContext('2026-06-22', HOME_FAQ);
}

describe('article-build.lib (F61)', () => {
  // --- US1: page shape, no-JS body, canonical ---
  describe('US1 article page', () => {
    it('renders a single h1=title, lead=summary, full body, html lang, self-canonical', () => {
      const out = buildArtifacts(FIXTURE, '# Cadence\n', ctx());
      const html = out.pages.find((p) => p.slug === 'a-one').html;
      expect((html.match(/<h1>/g) || []).length).toBe(1);
      expect(html).toContain('<html lang="en">');
      expect(html).toContain('Reducing interview no-shows in practice');
      expect(html).toContain('class="lead">A short lead');
      expect(html).toContain('<h2>Section</h2>'); // body present (no-JS readable string)
      expect(html).toContain('<link rel="canonical" href="https://__CADENCE_PUBLIC_ORIGIN__/resources/a-one">');
      expect(html).toContain('content="__CADENCE_ROBOTS__"'); // FR-018 indexable-content marker placeholder
    });

    it('escapes a quote in the title in HTML and JSON-LD (no broken markup/JSON)', () => {
      const out = buildArtifacts([art({ slug: 'q', title: 'A "quoted" title', related: [] })], '#', ctx());
      const html = out.pages[0].html;
      expect(html).toContain('A &quot;quoted&quot; title'); // HTML-escaped
      const ld = [...html.matchAll(/<script type="application\/ld\+json">([\s\S]*?)<\/script>/g)].map((m) => m[1]);
      for (const block of ld) expect(() => JSON.parse(block)).not.toThrow(); // JSON-string-escaped
    });

    it('renders the published date in the body (FR-014/SC-013), not only JSON-LD', () => {
      const out = buildArtifacts([art({ slug: 'd', dateUpdated: '2026-06-10', related: [] })], '#', ctx());
      const html = out.pages[0].html;
      expect(html).toContain('class="meta">Published June 1, 2026');
      expect(html).toContain('Updated June 10, 2026');
    });
  });

  // --- US1: sitemap allow-list + SC-001 count + empty library ---
  describe('US1 sitemap', () => {
    it('URL set is exactly {home, /resources, each article}, every url has lastmod, home priority 1.0', () => {
      const xml = buildSitemap(FIXTURE, ctx());
      const locs = [...xml.matchAll(/<loc>([^<]+)<\/loc>/g)].map((m) => m[1].replace('https://__CADENCE_PUBLIC_ORIGIN__', ''));
      expect(new Set(locs)).toEqual(new Set(['/', '/resources', '/resources/a-one', '/resources/a-two']));
      const urls = xml.match(/<url>/g).length;
      expect((xml.match(/<lastmod>/g) || []).length).toBe(urls);
      expect(xml).toContain('<priority>1.0</priority>');
    });

    it('contains zero private/token routes (SC-010)', () => {
      const xml = buildSitemap(FIXTURE, ctx());
      expect(/\/schedule|\/status|\/admin|\/booking|\/confirm|\/feedback|\/pipeline|token=/.test(xml)).toBeFalse();
    });

    it('launch floor yields >= 6 indexable URLs with 4 articles (SC-001)', () => {
      const four = ['no-shows', 'candidate-experience', 'scheduling', 'privacy'].map((t, i) =>
        art({ slug: 'art-' + i, title: 'Title ' + i, theme: t, related: [] })
      );
      const xml = buildSitemap(four, ctx());
      expect((xml.match(/<loc>/g) || []).length).toBeGreaterThanOrEqual(6);
    });

    it('empty library: index page is noindex (no thin indexable page)', () => {
      const out = buildArtifacts([], '# Cadence\n', ctx());
      expect(out.indexHtml).toContain('content="noindex,follow"');
      expect(out.sitemap).not.toContain('/resources<'); // no resources index entry when empty
    });
  });

  // --- US1: index + related ---
  describe('US1 index + related', () => {
    it('index lists every article and links home', () => {
      const out = buildArtifacts(FIXTURE, '#', ctx());
      expect(out.indexHtml).toContain('/resources/a-one');
      expect(out.indexHtml).toContain('/resources/a-two');
      expect(out.indexHtml).toContain('href="/"');
    });

    it('related is reciprocal (a-one->a-two implies a-two->a-one)', () => {
      const rel = computeRelated(FIXTURE);
      expect(rel.get('a-one')).toContain('a-two');
      expect(rel.get('a-two')).toContain('a-one');
    });

    it('auto-selects a same-theme article when related is empty', () => {
      const two = [
        art({ slug: 'x1', theme: 'no-shows', related: [] }),
        art({ slug: 'x2', theme: 'no-shows', related: [] })
      ];
      const rel = computeRelated(two);
      expect(rel.get('x1')).toEqual(['x2']);
    });

    it('single-article-theme degenerates to no related and never self-links', () => {
      const one = [art({ slug: 'solo', theme: 'privacy', related: [] })];
      const rel = computeRelated(one);
      expect(rel.get('solo')).toEqual([]);
    });
  });

  // --- US2: structured data + shared Org @id ---
  describe('US2 structured data', () => {
    it('emits Article + BreadcrumbList with publisher/author = shared Organization @id', () => {
      const out = buildArtifacts(FIXTURE, '#', ctx());
      const html = out.pages.find((p) => p.slug === 'a-two').html;
      const blocks = [...html.matchAll(/<script type="application\/ld\+json">([\s\S]*?)<\/script>/g)].map((m) => JSON.parse(m[1]));
      const types = blocks.map((b) => b['@type']);
      expect(types).toContain('BlogPosting');
      expect(types).toContain('BreadcrumbList');
      const article = blocks.find((b) => b['@type'] === 'BlogPosting');
      expect(article.publisher['@id']).toBe('https://__CADENCE_PUBLIC_ORIGIN__/#organization');
      expect(article.author['@id']).toBe('https://__CADENCE_PUBLIC_ORIGIN__/#organization');
      expect(article.mainEntityOfPage).toBe('https://__CADENCE_PUBLIC_ORIGIN__/resources/a-two');
    });

    it('no JSON-LD contains a Person node, author.name, or an email (D6 org-only)', () => {
      const out = buildArtifacts(FIXTURE, '#', ctx());
      for (const p of out.pages) {
        expect(p.html).not.toContain('"@type": "Person"');
        expect(p.html).not.toMatch(/"name":\s*"[^"]+@[^"]+"/);
      }
    });

    it('per-article FAQPage + speakable present; lead summary placed before the body', () => {
      const out = buildArtifacts(FIXTURE, '#', ctx());
      const html = out.pages.find((p) => p.slug === 'a-two').html;
      expect(html).toContain('"FAQPage"');
      expect(html).toContain('"SpeakableSpecification"');
      expect(html.indexOf('class="lead"')).toBeLessThan(html.indexOf('<h2>Tips</h2>'));
    });

    it('index carries CollectionPage + ItemList and a feed link', () => {
      const out = buildArtifacts(FIXTURE, '#', ctx());
      expect(out.indexHtml).toContain('"CollectionPage"');
      expect(out.indexHtml).toContain('"ItemList"');
      expect(out.indexHtml).toContain('type="application/atom+xml"');
    });
  });

  // --- US2: FAQ dedup, llms, feed ---
  describe('US2 dedup + llms + feed', () => {
    it('faqDedupCheck fails on a home-FAQ near-duplicate title', () => {
      expect(() => faqDedupCheck([art({ title: 'What is Cadence?' })], HOME_FAQ)).toThrowError(ArticleBuildError);
    });

    it('faqDedupCheck passes for distinct article questions', () => {
      expect(() => faqDedupCheck(FIXTURE, HOME_FAQ)).not.toThrow();
    });

    it('buildLlms lists every article URL', () => {
      const llms = buildLlms('# Cadence\n\n> base\n', FIXTURE, ctx());
      expect(llms).toContain('/resources/a-one');
      expect(llms).toContain('/resources/a-two');
      expect(llms).toContain('## Articles');
    });

    it('buildFeed emits one Atom entry per article', () => {
      const feed = buildFeed(FIXTURE, ctx());
      expect((feed.match(/<entry>/g) || []).length).toBe(2);
      expect(feed).toContain('<feed xmlns="http://www.w3.org/2005/Atom">');
    });
  });

  // --- US3: collision, retirement, lint ---
  describe('US3 lifecycle + safety', () => {
    it('duplicate slug fails the build (FR-016/SC-012)', () => {
      expect(() => buildArtifacts([art({ slug: 'dup' }), art({ slug: 'dup' })], '#', ctx())).toThrowError(/duplicate_slug/);
    });

    it('retiring an article removes it from page set, sitemap, llms, feed, index together', () => {
      const full = buildArtifacts(FIXTURE, '#', ctx());
      expect(full.pages.length).toBe(2);
      // Retire a-two: the author also clears a-one's now-dangling reference (a stale ref correctly
      // throws unresolved_related, which is the typo guard — see the separate assertion below).
      const reduced = buildArtifacts([{ ...FIXTURE[0], related: [] }], '#', ctx());
      expect(reduced.pages.map((p) => p.slug)).toEqual(['a-one']);
      expect(reduced.sitemap).not.toContain('/resources/a-two');
      expect(reduced.llms).not.toContain('/resources/a-two');
      expect(reduced.feed).not.toContain('/resources/a-two');
      expect(reduced.indexHtml).not.toContain('/resources/a-two');
    });

    it('a dangling related reference (retired target still referenced) fails validation', () => {
      expect(() => buildArtifacts([{ ...FIXTURE[0], related: ['gone'] }], '#', ctx())).toThrowError(/unresolved_related/);
    });

    it('lintBody rejects scripts, handlers, and non-allow-list links; accepts public links', () => {
      expect(() => lintBody('x', '<p><a href="/admin/secret">x</a></p>')).toThrowError(/allow-list/);
      expect(() => lintBody('x', '<script>alert(1)</script>')).toThrowError(/script/);
      expect(() => lintBody('x', '<p onclick="x()">y</p>')).toThrowError(/event handler/);
      expect(() => lintBody('x', '<a href="/status?token=abc">x</a>')).toThrowError(ArticleBuildError);
      expect(() => lintBody('x', '<p><a href="/resources/ok">k</a> <a href="/">home</a> <a href="https://example.com/x">ext</a></p>')).not.toThrow();
    });

    it('lintBody rejects an embedded email/token sentinel (FR-011/SC-005)', () => {
      expect(() => lintBody('x', '<p>Contact jane.doe@example.com</p>')).toThrowError(/sentinel/);
    });
  });

  // --- Polish: axe WCAG 2.2 AA gate over the GENERATED static HTML (FR-015/SC-006) ---
  describe('accessibility (axe WCAG 2.2 AA)', () => {
    let host: HTMLElement;
    afterEach(() => {
      if (host) detachFromBody(host);
    });

    function render(fullHtml: string): HTMLElement {
      const style = (fullHtml.match(/<style>[\s\S]*?<\/style>/) || [''])[0];
      const main = (fullHtml.match(/<main>[\s\S]*?<\/main>/) || [''])[0];
      host = document.createElement('div');
      host.innerHTML = style + main;
      attachToBody(host);
      return host;
    }

    it('an article page has zero WCAG 2.2 AA violations', async () => {
      const out = buildArtifacts(FIXTURE, '#', ctx());
      const el = render(out.pages[0].html);
      expect(await axeViolations(el)).toEqual([]);
    });

    it('the library index has zero WCAG 2.2 AA violations', async () => {
      const out = buildArtifacts(FIXTURE, '#', ctx());
      const el = render(out.indexHtml);
      expect(await axeViolations(el)).toEqual([]);
    });

    it('navigation links meet the 44px touch-target minimum', () => {
      const out = buildArtifacts(FIXTURE, '#', ctx());
      const el = render(out.pages[0].html);
      const link = el.querySelector('.home-link') as HTMLElement;
      expect(link.getBoundingClientRect().height).toBeGreaterThanOrEqual(44);
    });

    it('a long, non-Latin title does not cause horizontal overflow', () => {
      // Non-Latin (CJK) + a long unbroken Latin word, authored via char codes so the SOURCE stays ASCII.
      const cjk = String.fromCharCode(0x7121, 0x65ad, 0x30ad, 0x30e3, 0x30f3, 0x30bb, 0x30eb, 0x3092);
      const longTitle = 'Reducing no-shows ' + 'verylongunbrokenword'.repeat(8) + ' - ' + cjk.repeat(6);
      const out = buildArtifacts([art({ slug: 'long', title: longTitle, related: [] })], '#', ctx());
      const el = render(out.pages[0].html);
      el.style.width = '375px';
      expect(el.scrollWidth).toBeLessThanOrEqual(el.clientWidth + 1);
    });
  });
});
