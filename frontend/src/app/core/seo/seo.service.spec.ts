import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { SeoService, SEO_INDEXING_ENABLED } from './seo.service';
import { PRIVATE, PUBLIC_HOME } from './route-seo.model';

/**
 * F60 (026-seo-aeo) SeoService contract (seo-service.md):
 *  - C-1/C-6 indexable route sets title/description/robots=index,follow/canonical/OG (T010)
 *  - initial navigation is covered even if NavigationEnd fired before init() (T010)
 *  - C-2 private route → noindex,nofollow + canonical/description/OG REMOVED (T017)
 *  - C-3 tags are replaced (not appended) on index->private (T017)
 *  - C-4 a ?token= URL never leaks the token into a canonical/OG (T017)
 *  - deny-by-default: a route with no `seo` → noindex (FR-004)
 */
@Component({ standalone: true, template: 'x' })
class DummyComponent {}

const ROUTES = [
  { path: '', pathMatch: 'full' as const, component: DummyComponent, data: { seo: PUBLIC_HOME } },
  { path: 'status', component: DummyComponent, data: { seo: PRIVATE } },
  { path: 'nodata', component: DummyComponent }
];

function head() {
  return document.head;
}
function robots(): string | null {
  return head().querySelector('meta[name="robots"]')?.getAttribute('content') ?? null;
}
function canonical(): string | null {
  return head().querySelector('link[rel="canonical"]')?.getAttribute('href') ?? null;
}
function og(prop: string): string | null {
  return head().querySelector(`meta[property="${prop}"]`)?.getAttribute('content') ?? null;
}

function configure(indexingEnabled: boolean) {
  TestBed.configureTestingModule({
    providers: [provideRouter(ROUTES), { provide: SEO_INDEXING_ENABLED, useValue: indexingEnabled }]
  });
}

afterEach(() => {
  head().querySelector('link[rel="canonical"]')?.remove();
  head().querySelector('meta[name="robots"]')?.remove();
  head().querySelector('meta[name="description"]')?.remove();
  head().querySelectorAll('meta[property^="og:"], meta[name^="twitter:"]').forEach((n) => n.remove());
});

describe('SeoService — indexable route (production)', () => {
  beforeEach(() => configure(true));

  it('C-1/C-6: sets title, description, index,follow, self-canonical and OG on the home route', async () => {
    const harness = await RouterTestingHarness.create();
    TestBed.inject(SeoService).init();
    await harness.navigateByUrl('/');

    expect(document.title).toBe(PUBLIC_HOME.title!);
    expect(robots()).toBe('index,follow');
    expect(canonical()).toBe(`${location.origin}/`);
    expect(head().querySelector('meta[name="description"]')?.getAttribute('content')).toContain('recruiters');
    expect(og('og:url')).toBe(`${location.origin}/`);
    expect(og('og:title')).toBe(PUBLIC_HOME.title!);
    expect(og('og:image')).toContain('/assets/og-cadence.png');
  });

  it('applies SEO on the INITIAL navigation even if NavigationEnd fired before init()', async () => {
    const harness = await RouterTestingHarness.create();
    await harness.navigateByUrl('/'); // navigation completes BEFORE the service subscribes
    TestBed.inject(SeoService).init(); // synchronous apply() must pick up the current snapshot
    expect(robots()).toBe('index,follow');
    expect(canonical()).toBe(`${location.origin}/`);
  });
});

describe('SeoService — private routes & deny-by-default (production)', () => {
  beforeEach(() => configure(true));

  it('C-2: a PRIVATE route is noindex,nofollow and has NO canonical/description/OG', async () => {
    const harness = await RouterTestingHarness.create();
    TestBed.inject(SeoService).init();
    await harness.navigateByUrl('/status');

    expect(robots()).toBe('noindex,nofollow');
    expect(canonical()).toBeNull();
    expect(head().querySelector('meta[name="description"]')).toBeNull();
    expect(og('og:title')).toBeNull();
  });

  it('C-3: navigating home -> private REPLACES the canonical (no stale home canonical on the token page)', async () => {
    const harness = await RouterTestingHarness.create();
    TestBed.inject(SeoService).init();
    await harness.navigateByUrl('/');
    expect(canonical()).toBe(`${location.origin}/`);
    await harness.navigateByUrl('/status');
    expect(document.querySelectorAll('link[rel="canonical"]').length).toBe(0);
  });

  it('C-4: a ?token= URL never leaks the token into any canonical/OG tag', async () => {
    const harness = await RouterTestingHarness.create();
    TestBed.inject(SeoService).init();
    await harness.navigateByUrl('/status?token=SENTINEL_SEO_TOKEN');
    const leak = [canonical(), og('og:url'), og('og:image')].filter(
      (v) => v && v.includes('SENTINEL_SEO_TOKEN')
    );
    expect(leak).toEqual([]);
    expect(robots()).toBe('noindex,nofollow');
  });

  it('removes statically-shipped OG/Twitter tags (from index.html) when navigating to a private route', async () => {
    // Simulate the static index.html OG/Twitter tags being present before navigation.
    const ogTitle = document.createElement('meta');
    ogTitle.setAttribute('property', 'og:title');
    ogTitle.setAttribute('content', 'static-home');
    document.head.appendChild(ogTitle);
    const twImage = document.createElement('meta');
    twImage.setAttribute('name', 'twitter:image');
    twImage.setAttribute('content', '/assets/og-cadence.png');
    document.head.appendChild(twImage);

    const harness = await RouterTestingHarness.create();
    TestBed.inject(SeoService).init();
    await harness.navigateByUrl('/status');

    expect(document.head.querySelector('meta[property="og:title"]')).toBeNull();
    expect(document.head.querySelector('meta[name="twitter:image"]')).toBeNull();
  });

  it('deny-by-default: a route with no `seo` resolves to noindex,nofollow (FR-004)', async () => {
    const harness = await RouterTestingHarness.create();
    TestBed.inject(SeoService).init();
    await harness.navigateByUrl('/nodata');
    expect(robots()).toBe('noindex,nofollow');
    expect(canonical()).toBeNull();
  });
});
