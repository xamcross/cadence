import { ComponentFixture, TestBed } from '@angular/core/testing';
import { PublicFooterComponent } from './public-footer.component';
import { attachToBody, axeViolations, detachFromBody } from '../../testing/axe';

/**
 * 031-terms-privacy-notice US3 (contract C-LINK-1): the shared public footer.
 * Verifies: Terms + Privacy + Home links rendered as root-relative full-document anchors using `href`
 * (NOT routerLink - these are static files outside the SPA router), the hrefs are exactly
 * /terms /privacy / (no token, no query), a <footer> landmark is present, axe 0 WCAG 2.2 AA violations,
 * and the links meet the 44px touch-target minimum.
 */
describe('PublicFooterComponent (031 US3)', () => {
  let fixture: ComponentFixture<PublicFooterComponent>;
  let el: HTMLElement;

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [PublicFooterComponent] });
    fixture = TestBed.createComponent(PublicFooterComponent);
    el = fixture.nativeElement as HTMLElement;
    attachToBody(el);
    fixture.detectChanges();
  });

  afterEach(() => detachFromBody(el));

  it('renders a <footer> contentinfo landmark', () => {
    const footer = el.querySelector('footer');
    expect(footer).withContext('a <footer> landmark should be present').not.toBeNull();
    expect(footer?.getAttribute('role')).toBe('contentinfo');
  });

  it('renders Home, Terms and Privacy links using href (not routerLink) with exact root-relative targets', () => {
    const anchors = Array.from(el.querySelectorAll('a')) as HTMLAnchorElement[];
    const hrefs = anchors.map((a) => a.getAttribute('href'));
    expect(hrefs).toContain('/');
    expect(hrefs).toContain('/terms');
    expect(hrefs).toContain('/privacy');

    // Root-relative full-document anchors: every link carries an explicit `href`, and none uses routerLink.
    anchors.forEach((a) => {
      expect(a.getAttribute('href')).withContext('every footer link must use a real href').not.toBeNull();
      expect(a.hasAttribute('routerLink')).withContext('footer links must NOT use routerLink').toBe(false);
      expect(a.hasAttribute('ng-reflect-router-link')).toBe(false);
      // No token / query / fragment carried on the legal targets.
      expect(a.getAttribute('href')).not.toContain('?');
      expect(a.getAttribute('href')).not.toContain('token');
    });
  });

  it('has zero axe WCAG 2.2 AA violations', async () => {
    const violations = await axeViolations(el);
    expect(violations).withContext(violations.map((v) => v.id).join(', ')).toEqual([]);
  });

  it('every footer link meets the 44px target-size minimum', () => {
    const anchors = Array.from(el.querySelectorAll('a')) as HTMLAnchorElement[];
    expect(anchors.length).toBeGreaterThan(0);
    anchors.forEach((a) => {
      const rect = a.getBoundingClientRect();
      expect(rect.height).withContext(`${a.getAttribute('href')} height`).toBeGreaterThanOrEqual(44);
    });
  });
});
