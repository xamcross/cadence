import { DOCUMENT } from '@angular/common';
import { Injectable, InjectionToken, inject } from '@angular/core';
import { Meta, Title } from '@angular/platform-browser';
import { ActivatedRouteSnapshot, NavigationEnd, Router } from '@angular/router';
import { filter } from 'rxjs';
import { RouteSeo } from './route-seo.model';

/**
 * Whether THIS deployment may emit `index,follow` at all. Production builds carry
 * `<meta name="cadence-index" content="enabled">` (substituted by scripts/seo-inject-origin.mjs);
 * preview / non-production deployments (and local dev / Karma) do NOT, so the factory returns false
 * and SeoService forces `noindex` on every route (US4 / FR-020 / SC-008). Tests override this token.
 */
export const SEO_INDEXING_ENABLED = new InjectionToken<boolean>('SEO_INDEXING_ENABLED', {
  providedIn: 'root',
  factory: () => {
    const doc = inject(DOCUMENT);
    return doc.querySelector('meta[name="cadence-index"]')?.getAttribute('content') === 'enabled';
  }
});

const APP_NAME = 'Cadence';
const DEFAULT_OG_IMAGE = '/assets/og-cadence.png';
const OG_PROPS = ['og:type', 'og:title', 'og:description', 'og:url', 'og:image'];
const TWITTER_NAMES = ['twitter:card', 'twitter:title', 'twitter:description', 'twitter:image'];

/**
 * F60 (026-seo-aeo) runtime SEO/AEO controller for the SPA.
 *
 * On each navigation it resolves the deepest activated route's `data.seo` and applies title /
 * description / robots / canonical / Open Graph. DENY-BY-DEFAULT: any route not explicitly
 * `index: true` (and any deployment that is not production) gets `noindex,nofollow` and has its
 * canonical/description/OG REMOVED — including the static canonical shipped in index.html — so a
 * token page never carries the home canonical (C2/C3, FR-008). The canonical/token is never built
 * from the query string, so a secret token cannot leak into a canonical or share URL (FR-014).
 */
@Injectable({ providedIn: 'root' })
export class SeoService {
  private readonly doc = inject(DOCUMENT);
  private readonly router = inject(Router);
  private readonly title = inject(Title);
  private readonly meta = inject(Meta);
  private readonly indexingEnabled = inject(SEO_INDEXING_ENABLED);

  /** Wire once in AppComponent's constructor. Subscribes, then applies for the current snapshot so
   *  the initial (enabledNonBlocking) navigation is covered whether or not it has already fired. */
  init(): void {
    this.router.events
      .pipe(filter((e): e is NavigationEnd => e instanceof NavigationEnd))
      .subscribe(() => this.apply());
    this.apply();
  }

  private apply(): void {
    const seo = this.resolve();
    if (this.indexingEnabled && seo?.index === true) {
      this.applyIndexable(seo);
    } else {
      this.applyNonIndexable();
    }
  }

  private resolve(): RouteSeo | undefined {
    let route: ActivatedRouteSnapshot | null = this.router.routerState.snapshot.root;
    let seo: RouteSeo | undefined;
    while (route) {
      const candidate = route.data?.['seo'] as RouteSeo | undefined;
      if (candidate) {
        seo = candidate;
      }
      route = route.firstChild;
    }
    return seo;
  }

  private applyIndexable(seo: RouteSeo): void {
    const url = this.canonicalUrl(seo.path ?? '/');
    this.title.setTitle(seo.title ?? APP_NAME);
    this.meta.updateTag({ name: 'description', content: seo.description ?? '' });
    this.meta.updateTag({ name: 'robots', content: seo.noFollow ? 'index,nofollow' : 'index,follow' });
    this.setCanonical(url);

    const image = this.absolute(seo.ogImage ?? DEFAULT_OG_IMAGE);
    this.meta.updateTag({ property: 'og:type', content: 'website' });
    this.meta.updateTag({ property: 'og:title', content: seo.title ?? APP_NAME });
    this.meta.updateTag({ property: 'og:description', content: seo.description ?? '' });
    this.meta.updateTag({ property: 'og:url', content: url });
    this.meta.updateTag({ property: 'og:image', content: image });
    this.meta.updateTag({ name: 'twitter:card', content: 'summary_large_image' });
    this.meta.updateTag({ name: 'twitter:title', content: seo.title ?? APP_NAME });
    this.meta.updateTag({ name: 'twitter:description', content: seo.description ?? '' });
    this.meta.updateTag({ name: 'twitter:image', content: image });
  }

  private applyNonIndexable(): void {
    this.title.setTitle(APP_NAME);
    this.meta.updateTag({ name: 'robots', content: 'noindex,nofollow' });
    this.meta.removeTag("name='description'");
    this.removeCanonical();
    OG_PROPS.forEach((p) => this.meta.removeTag(`property='${p}'`));
    TWITTER_NAMES.forEach((n) => this.meta.removeTag(`name='${n}'`));
  }

  /** Build an absolute canonical from the live origin + path, with query/fragment STRIPPED (no token). */
  private canonicalUrl(path: string): string {
    const clean = '/' + String(path).replace(/^\/+/, '').split('?')[0].split('#')[0];
    return this.doc.location.origin + clean;
  }

  private absolute(path: string): string {
    if (/^https?:\/\//i.test(path)) {
      return path;
    }
    return this.doc.location.origin + '/' + path.replace(/^\/+/, '');
  }

  private setCanonical(href: string): void {
    let link = this.doc.head.querySelector('link[rel="canonical"]');
    if (!link) {
      link = this.doc.createElement('link');
      link.setAttribute('rel', 'canonical');
      this.doc.head.appendChild(link);
    }
    link.setAttribute('href', href);
  }

  private removeCanonical(): void {
    const link = this.doc.head.querySelector('link[rel="canonical"]');
    link?.parentNode?.removeChild(link);
  }
}
