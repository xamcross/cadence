import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { SeoService, SEO_INDEXING_ENABLED } from './seo.service';
import { PUBLIC_HOME } from './route-seo.model';

/**
 * F60 (026-seo-aeo) US4 / SC-008: in a NON-production deployment (SEO_INDEXING_ENABLED=false, i.e. the
 * `cadence-index` meta is not 'enabled'), SeoService forces noindex on EVERY route — including the
 * otherwise-indexable home — so a preview deployment never gets indexed.
 */
@Component({ standalone: true, template: 'x' })
class DummyComponent {}

afterEach(() => {
  document.head.querySelector('link[rel="canonical"]')?.remove();
  document.head.querySelector('meta[name="robots"]')?.remove();
});

describe('SeoService — non-production indexability override', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([{ path: '', pathMatch: 'full', component: DummyComponent, data: { seo: PUBLIC_HOME } }]),
        { provide: SEO_INDEXING_ENABLED, useValue: false }
      ]
    });
  });

  it('forces noindex,nofollow on the home route and emits no canonical when indexing is disabled', async () => {
    const harness = await RouterTestingHarness.create();
    TestBed.inject(SeoService).init();
    await harness.navigateByUrl('/');
    expect(document.head.querySelector('meta[name="robots"]')?.getAttribute('content')).toBe('noindex,nofollow');
    expect(document.head.querySelector('link[rel="canonical"]')).toBeNull();
  });
});
