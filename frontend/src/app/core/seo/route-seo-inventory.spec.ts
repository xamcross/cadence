import { Route } from '@angular/router';
import { routes } from '../../app.routes';
import { RouteSeo } from './route-seo.model';

/**
 * F60 (026-seo-aeo) FR-004/SC-009 deny-by-default inventory: assert against the REAL app route table
 * that exactly one route (`''`, the public home) is indexable and every other route — including the
 * wildcard `**` 404 — is non-indexable. A future token/auth route added without `index: true` is
 * therefore protected automatically; this test fails the build if someone marks a second route
 * indexable or leaves the home unmarked.
 */
function seoOf(r: Route): RouteSeo | undefined {
  return r.data?.['seo'] as RouteSeo | undefined;
}

describe('app.routes SEO inventory (deny-by-default)', () => {
  it('every route carries a `data.seo` descriptor', () => {
    const missing = routes.filter((r) => seoOf(r) === undefined).map((r) => r.path);
    expect(missing).toEqual([]);
  });

  it('exactly one route is indexable, and it is the public home (path "")', () => {
    const indexable = routes.filter((r) => seoOf(r)?.index === true);
    expect(indexable.length).toBe(1);
    expect(indexable[0].path).toBe('');
  });

  it('the wildcard 404 route is non-indexable', () => {
    const wildcard = routes.find((r) => r.path === '**');
    expect(wildcard).toBeTruthy();
    expect(seoOf(wildcard!)?.index).not.toBe(true);
  });

  it('the indexable home has a title, description and canonical path', () => {
    const home = routes.find((r) => r.path === '')!;
    const seo = seoOf(home)!;
    expect(seo.title?.length).toBeGreaterThan(0);
    expect(seo.description?.length).toBeGreaterThan(0);
    expect(seo.path).toBe('/');
  });

  it('every token/auth route is non-indexable', () => {
    const sensitive = ['schedule', 'booking', 'booking/cancel', 'confirm', 'status', 'feedback', 'app'];
    for (const path of sensitive) {
      const r = routes.find((x) => x.path === path);
      expect(r).withContext(`route ${path} present`).toBeTruthy();
      expect(seoOf(r!)?.index).withContext(`route ${path} non-indexable`).not.toBe(true);
    }
  });
});
