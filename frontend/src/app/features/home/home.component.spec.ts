import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { HomeComponent } from './home.component';
import { AuthService } from '../../core/auth/auth.service';
import { attachToBody, axeViolations, detachFromBody } from '../../../testing/axe';

/**
 * F60 (026-seo-aeo) US1 public home:
 *  - anonymous visitor (me() 401) STAYS on the page (no navigation to /login or /app) and the
 *    marketing content renders — crawlers/visitors must not be bounced (FR-022/SC-005)
 *  - a signed-in member is redirected to /app
 *  - WCAG 2.2 AA (axe 0 violations) and the sign-in CTA is >= 44px (Principle IX)
 */
describe('HomeComponent (F60)', () => {
  let authSpy: { me: jasmine.Spy };

  async function setup(meReturn: 'anon' | 'member') {
    authSpy = {
      me: jasmine.createSpy('me').and.returnValue(
        meReturn === 'member' ? of({ role: 'ADMIN' } as any) : throwError(() => ({ status: 401 }))
      )
    };
    TestBed.configureTestingModule({
      imports: [HomeComponent],
      providers: [provideRouter([]), { provide: AuthService, useValue: authSpy }]
    });
    const fixture: ComponentFixture<HomeComponent> = TestBed.createComponent(HomeComponent);
    const router = TestBed.inject(Router);
    const navSpy = spyOn(router, 'navigate').and.resolveTo(true);
    fixture.detectChanges();
    await fixture.whenStable();
    return { fixture, navSpy };
  }

  it('anonymous: renders marketing content and does NOT navigate away', async () => {
    const { fixture, navSpy } = await setup('anon');
    const el: HTMLElement = fixture.nativeElement;
    expect(el.querySelector('h1')?.textContent).toContain('interview scheduling');
    expect(el.querySelector('a.cta')?.getAttribute('href')).toContain('/login');
    expect(navSpy).not.toHaveBeenCalled();
  });

  it('signed-in: redirects to /app', async () => {
    const { navSpy } = await setup('member');
    expect(navSpy).toHaveBeenCalledWith(['/app']);
  });

  it('has zero WCAG 2.2 AA violations', async () => {
    const { fixture } = await setup('anon');
    const el: HTMLElement = fixture.nativeElement;
    attachToBody(el);
    try {
      expect(await axeViolations(el)).toEqual([]);
    } finally {
      detachFromBody(el);
    }
  });

  it('sign-in CTA meets the 44px touch-target minimum', async () => {
    const { fixture } = await setup('anon');
    const el: HTMLElement = fixture.nativeElement;
    attachToBody(el);
    try {
      const cta = el.querySelector('a.cta') as HTMLElement;
      expect(cta.getBoundingClientRect().height).toBeGreaterThanOrEqual(44);
    } finally {
      detachFromBody(el);
    }
  });
});
