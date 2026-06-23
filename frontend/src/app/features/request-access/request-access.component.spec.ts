import { ComponentFixture, TestBed, fakeAsync, flushMicrotasks } from '@angular/core/testing';
import { Observable, of, throwError } from 'rxjs';
import { HttpErrorResponse } from '@angular/common/http';
import { RequestAccessComponent } from './request-access.component';
import { InterestService, InterestSubmitResponse } from './interest.service';
import { attachToBody, axeViolations, detachFromBody } from '../../../testing/axe';

/**
 * F70 join / express-interest public form (§IX). Verifies: WCAG 2.2 AA (axe 0 violations) across the form,
 * submitting, confirmation, and error states; submit button >=44px (SC target-size — not in the axe WCAG tag
 * set); the token/PII-free model is never written to web storage; no horizontal overflow at 375px (the F14
 * scrollWidth<=clientWidth precedent); and the privacy notice renders all four GDPR elements.
 */
describe('RequestAccessComponent (F70)', () => {
  let activeEl: HTMLElement | null = null;
  let submitSpy: jasmine.Spy;

  function build(opts: { submit?: () => Observable<InterestSubmitResponse> } = {}): ComponentFixture<RequestAccessComponent> {
    submitSpy = jasmine
      .createSpy('submit')
      .and.callFake(opts.submit ?? (() => of({ status: 'received' } as InterestSubmitResponse)));
    const svc: Partial<InterestService> = { submit: submitSpy as unknown as InterestService['submit'] };
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [RequestAccessComponent],
      providers: [{ provide: InterestService, useValue: svc }]
    });
    const fixture = TestBed.createComponent(RequestAccessComponent);
    const el = fixture.nativeElement as HTMLElement;
    activeEl = el;
    // 375px viewport for the no-horizontal-overflow assertion (the F14 precedent).
    el.style.width = '375px';
    attachToBody(el);
    fixture.detectChanges();
    return fixture;
  }

  function fill(c: RequestAccessComponent): void {
    c.name = 'Dana Lee';
    c.email = 'dana@example.com';
  }

  afterEach(() => { if (activeEl) { detachFromBody(activeEl); } });

  it('renders the blank form with name/email + optional org/message + a hidden honeypot', () => {
    const fixture = build();
    const el: HTMLElement = fixture.nativeElement;
    expect(el.querySelector('input[name="name"]')).toBeTruthy();
    expect(el.querySelector('input[name="email"]')).toBeTruthy();
    expect(el.querySelector('input[name="organization"]')).toBeTruthy();
    expect(el.querySelector('textarea[name="message"]')).toBeTruthy();
    const honeypot = el.querySelector('input[name="website"]') as HTMLInputElement;
    expect(honeypot).toBeTruthy();
    // Off the tab order and inside an aria-hidden wrapper (humans never reach it).
    expect(honeypot.getAttribute('tabindex')).toBe('-1');
    expect(honeypot.closest('[aria-hidden="true"]')).toBeTruthy();
  });

  it('renders the privacy notice with all four GDPR elements', () => {
    const fixture = build();
    const el: HTMLElement = fixture.nativeElement;
    expect(el.querySelector('.privacy__collected')).toBeTruthy();   // data collected
    expect(el.querySelector('.privacy__purpose')).toBeTruthy();     // purpose
    expect(el.querySelector('.privacy__basis')).toBeTruthy();       // lawful basis (legitimate interest)
    expect(el.querySelector('.privacy__retention')).toBeTruthy();   // retention period
    const text = (el.querySelector('.privacy') as HTMLElement).textContent ?? '';
    expect(text.toLowerCase()).toContain('legitimate interest');
  });

  it('has zero axe WCAG 2.2 AA violations on the form state', async () => {
    const fixture = build();
    expect(await axeViolations(fixture.nativeElement)).toEqual([]);
  });

  it('has zero axe violations on the submitting state', async () => {
    // Hold the submit observable open so the component stays in 'submitting'.
    const fixture = build({ submit: () => new Observable<InterestSubmitResponse>() });
    fill(fixture.componentInstance);
    fixture.componentInstance.submit();
    fixture.detectChanges();
    expect(fixture.componentInstance.state()).toBe('submitting');
    expect(await axeViolations(fixture.nativeElement)).toEqual([]);
  });

  it('has zero axe violations on the confirmation state', async () => {
    const fixture = build();
    fill(fixture.componentInstance);
    fixture.componentInstance.submit();
    fixture.detectChanges();
    expect(fixture.componentInstance.state()).toBe('confirmation');
    expect(await axeViolations(fixture.nativeElement)).toEqual([]);
  });

  it('has zero axe violations on the error state', async () => {
    const fixture = build({ submit: () => throwError(() => new HttpErrorResponse({ status: 500 })) });
    fill(fixture.componentInstance);
    fixture.componentInstance.submit();
    fixture.detectChanges();
    expect(fixture.componentInstance.state()).toBe('error');
    expect(await axeViolations(fixture.nativeElement)).toEqual([]);
  });

  it('submit button meets the 44px target size', () => {
    const fixture = build();
    const btn = fixture.nativeElement.querySelector('.action.submit') as HTMLElement;
    const rect = btn.getBoundingClientRect();
    expect(rect.height).toBeGreaterThanOrEqual(44);
  });

  it('has no horizontal overflow at a 375px viewport', () => {
    const fixture = build();
    const container = fixture.nativeElement.querySelector('.request-access') as HTMLElement;
    expect(container.scrollWidth).toBeLessThanOrEqual(container.clientWidth);
  });

  it('posts the submission and shows the neutral confirmation', () => {
    const fixture = build();
    fill(fixture.componentInstance);
    fixture.componentInstance.organization = 'Acme';
    fixture.componentInstance.submit();
    expect(submitSpy).toHaveBeenCalled();
    const payload = submitSpy.calls.mostRecent().args[0];
    expect(payload.name).toBe('Dana Lee');
    expect(payload.email).toBe('dana@example.com');
    expect(payload.organization).toBe('Acme');
    expect(payload.website).toBe('');
    expect(fixture.componentInstance.state()).toBe('confirmation');
  });

  it('maps a 429 to the rate_limited state', () => {
    const fixture = build({ submit: () => throwError(() => new HttpErrorResponse({ status: 429 })) });
    fill(fixture.componentInstance);
    fixture.componentInstance.submit();
    expect(fixture.componentInstance.state()).toBe('rate_limited');
  });

  it('never writes the form input to web storage', () => {
    const setLocal = spyOn(Storage.prototype, 'setItem').and.callThrough();
    const fixture = build();
    fill(fixture.componentInstance);
    fixture.componentInstance.message = 'secret message';
    fixture.componentInstance.submit();
    expect(setLocal).not.toHaveBeenCalled();
  });

  it('moves focus to the state heading after a transition', fakeAsync(() => {
    const fixture = build();
    fill(fixture.componentInstance);
    fixture.componentInstance.submit();
    fixture.detectChanges();
    flushMicrotasks();
    expect(document.activeElement).toBe(fixture.nativeElement.querySelector('.state-heading'));
  }));

  // ---- 031-terms-privacy-notice: full Privacy Notice link in the notice block (T018/T020, C-LINK-2) ----

  it('adds a full /privacy link while RETAINING the 4-point notice', () => {
    const el: HTMLElement = build().nativeElement;
    // The 4-point summary is retained (not replaced - FR-009).
    expect(el.querySelector('.privacy__collected')).toBeTruthy();
    expect(el.querySelector('.privacy__purpose')).toBeTruthy();
    expect(el.querySelector('.privacy__basis')).toBeTruthy();
    expect(el.querySelector('.privacy__retention')).toBeTruthy();
    // ...and a full Privacy Notice link is added inside the same notice block.
    const link = el.querySelector('.privacy a.privacy-link') as HTMLAnchorElement;
    expect(link).withContext('the notice block should contain a /privacy link').not.toBeNull();
    // Root-relative full-document anchor (NOT routerLink) to the static /privacy page.
    expect(link.getAttribute('href')).toBe('/privacy');
    expect(link.hasAttribute('routerLink')).toBe(false);
  });
});
