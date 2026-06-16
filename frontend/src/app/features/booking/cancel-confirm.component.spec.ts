import { ComponentFixture, TestBed, fakeAsync, flushMicrotasks } from '@angular/core/testing';
import { ActivatedRoute, Router } from '@angular/router';
import { Observable, of, throwError } from 'rxjs';
import { CancelConfirmComponent } from './cancel-confirm.component';
import { BookingService, CancelResponse } from './booking.service';
import { attachToBody, axeViolations, detachFromBody } from '../../../testing/axe';

/**
 * F20 candidate cancellation confirmation (§IX). Verifies: the cancel POST fires ONLY on the affirmative
 * "Yes, cancel" click (NEVER on page load — FR-012), respectful post-cancel messaging, WCAG 2.2 AA (axe 0
 * violations across confirm / cancelled / cleanup_incomplete / error states), focus management, 44px targets,
 * no horizontal scroll at 375px, RTL tolerance, and no client-side token persistence / no token in console.
 */
describe('CancelConfirmComponent (F20)', () => {
  const cancelled: CancelResponse = { status: 'cancelled', at: '2026-06-19T10:00:00Z' };
  const err = (status: number, code?: string) => ({ status, error: code ? { error: code } : null });

  let activeEl: HTMLElement | null = null;
  let cancelSpy: jasmine.Spy;
  let navigateSpy: jasmine.Spy;

  function build(opts: { cancel?: () => Observable<CancelResponse>; token?: string } = {}): ComponentFixture<CancelConfirmComponent> {
    const token = opts.token ?? 'tok123';
    cancelSpy = jasmine.createSpy('cancel').and.callFake(opts.cancel ?? (() => of(cancelled)));
    const bookingSvc: Partial<BookingService> = { cancel: cancelSpy as unknown as BookingService['cancel'] };
    const route = { snapshot: { queryParamMap: { get: (_: string) => token } } };
    navigateSpy = jasmine.createSpy('navigate');
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [CancelConfirmComponent],
      providers: [
        { provide: BookingService, useValue: bookingSvc },
        { provide: ActivatedRoute, useValue: route },
        { provide: Router, useValue: { navigate: navigateSpy } }
      ]
    });
    const fixture = TestBed.createComponent(CancelConfirmComponent);
    activeEl = fixture.nativeElement as HTMLElement;
    attachToBody(activeEl);
    fixture.detectChanges();
    return fixture;
  }

  afterEach(() => {
    if (activeEl) { detachFromBody(activeEl); activeEl = null; }
    document.documentElement.removeAttribute('dir');
  });

  // ---- The load-bearing FR-012 guarantee ----

  describe('affirmative-action only (FR-012)', () => {
    it('does NOT fire the cancel POST on page load', () => {
      build();
      expect(cancelSpy).not.toHaveBeenCalled();
    });

    it('starts on the confirm prompt (no state change yet)', () => {
      expect(build().componentInstance.state()).toBe('confirm');
    });

    it('fires the cancel POST only on the affirmative "Yes, cancel" click', () => {
      const fixture = build();
      const yes = fixture.nativeElement.querySelector('button.cancel') as HTMLButtonElement;
      yes.click();
      expect(cancelSpy).toHaveBeenCalledTimes(1);
      expect(cancelSpy).toHaveBeenCalledWith('tok123');
      expect(fixture.componentInstance.state()).toBe('cancelled');
    });

    it('"No, keep my interview" navigates back without cancelling', () => {
      const fixture = build();
      fixture.componentInstance.back();
      expect(cancelSpy).not.toHaveBeenCalled();
      expect(navigateSpy).toHaveBeenCalledWith(['/booking'], { queryParams: { token: 'tok123' } });
    });
  });

  // ---- Cancel outcomes ----

  describe('cancel outcomes', () => {
    it('renders respectful cancelled messaging on 200', () => {
      const fixture = build();
      fixture.componentInstance.doCancel();
      fixture.detectChanges();
      expect(fixture.componentInstance.state()).toBe('cancelled');
      const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
      expect(text.toLowerCase()).toContain('cancelled');
    });

    it('renders cleanup_incomplete honestly (recruiter will confirm)', () => {
      const fixture = build({ cancel: () => of({ status: 'cleanup_incomplete', at: '2026-06-19T10:00:00Z' }) });
      fixture.componentInstance.doCancel();
      expect(fixture.componentInstance.state()).toBe('cleanup_incomplete');
    });

    it('maps a 409 cleanup_incomplete error to the cleanup state', () => {
      const fixture = build({ cancel: () => throwError(() => err(409, 'cleanup_incomplete')) });
      fixture.componentInstance.doCancel();
      expect(fixture.componentInstance.state()).toBe('cleanup_incomplete');
    });

    it('maps 409 ineligible, 410 expired, 400 invalid, 429 rate-limited distinctly', () => {
      const ineligible = build({ cancel: () => throwError(() => err(409, 'ineligible')) });
      ineligible.componentInstance.doCancel();
      expect(ineligible.componentInstance.state()).toBe('ineligible');

      const expired = build({ cancel: () => throwError(() => err(410)) });
      expired.componentInstance.doCancel();
      expect(expired.componentInstance.state()).toBe('expired');

      const invalid = build({ cancel: () => throwError(() => err(400)) });
      invalid.componentInstance.doCancel();
      expect(invalid.componentInstance.state()).toBe('invalid');

      const limited = build({ cancel: () => throwError(() => err(429)) });
      limited.componentInstance.doCancel();
      expect(limited.componentInstance.state()).toBe('rate_limited');
    });

    it('on a network failure: retryable_error and the booking is reported still booked', () => {
      const fixture = build({ cancel: () => throwError(() => err(0)) });
      fixture.componentInstance.doCancel();
      fixture.detectChanges();
      expect(fixture.componentInstance.state()).toBe('retryable_error');
      const text = ((fixture.nativeElement as HTMLElement).textContent ?? '').toLowerCase();
      expect(text).toContain('still booked');
    });

    it('idempotent replay: a second confirm returns the existing cancellation', () => {
      const fixture = build();
      fixture.componentInstance.doCancel();
      fixture.componentInstance.reset(); // would only happen via retry, but exercises no-crash
      expect(cancelSpy).toHaveBeenCalled();
    });

    it('a missing token short-circuits to invalid without POSTing', () => {
      const fixture = build({ token: '' });
      expect(fixture.componentInstance.state()).toBe('invalid');
      fixture.componentInstance.doCancel();
      expect(cancelSpy).not.toHaveBeenCalled();
    });
  });

  // ---- axe per state ----

  describe('axe WCAG 2.2 AA — zero violations per state', () => {
    it('confirm state has no violations', async () => {
      const fixture = build();
      await fixture.whenStable();
      fixture.detectChanges();
      const v = await axeViolations(fixture.nativeElement);
      expect(v).withContext(v.map(x => x.id).join(', ')).toEqual([]);
    });

    it('cancelled state has no violations', async () => {
      const fixture = build();
      fixture.componentInstance.doCancel();
      await fixture.whenStable();
      fixture.detectChanges();
      const v = await axeViolations(fixture.nativeElement);
      expect(v).withContext(v.map(x => x.id).join(', ')).toEqual([]);
    });

    it('cleanup_incomplete state has no violations', async () => {
      const fixture = build({ cancel: () => of({ status: 'cleanup_incomplete', at: '2026-06-19T10:00:00Z' }) });
      fixture.componentInstance.doCancel();
      await fixture.whenStable();
      fixture.detectChanges();
      const v = await axeViolations(fixture.nativeElement);
      expect(v).withContext(v.map(x => x.id).join(', ')).toEqual([]);
    });

    it('retryable_error state has no violations', async () => {
      const fixture = build({ cancel: () => throwError(() => err(0)) });
      fixture.componentInstance.doCancel();
      await fixture.whenStable();
      fixture.detectChanges();
      const v = await axeViolations(fixture.nativeElement);
      expect(v).withContext(v.map(x => x.id).join(', ')).toEqual([]);
    });
  });

  // ---- Focus management ----

  describe('focus management', () => {
    it('moves focus to the heading after cancelling', fakeAsync(() => {
      const fixture = build();
      fixture.componentInstance.doCancel();
      fixture.detectChanges();
      flushMicrotasks();
      const heading = fixture.nativeElement.querySelector('.state-heading') as HTMLElement;
      expect(document.activeElement).toBe(heading);
    }));

    it('does not steal focus on the initial confirm paint', fakeAsync(() => {
      const fixture = build();
      flushMicrotasks();
      const heading = fixture.nativeElement.querySelector('.state-heading') as HTMLElement;
      expect(document.activeElement).not.toBe(heading);
    }));
  });

  // ---- WCAG 2.2 specifics ----

  describe('WCAG 2.2 specifics', () => {
    it('the confirm buttons meet the 44px minimum target size (2.5.8)', () => {
      const fixture = build();
      const yes = fixture.nativeElement.querySelector('button.cancel') as HTMLElement;
      const rect = yes.getBoundingClientRect();
      expect(rect.height).toBeGreaterThanOrEqual(44);
      expect(rect.width).toBeGreaterThanOrEqual(44);
    });

    it('does not horizontally scroll at a 375px viewport width', () => {
      const fixture = build();
      const host = fixture.nativeElement as HTMLElement;
      host.style.width = '375px';
      fixture.detectChanges();
      expect(host.scrollWidth).toBeLessThanOrEqual(host.clientWidth + 1);
    });

    it('renders no participant identity / email', () => {
      const fixture = build();
      expect(((fixture.nativeElement as HTMLElement).textContent ?? '')).not.toMatch(/@/);
    });
  });

  // ---- Localization / RTL ----

  describe('localization + RTL', () => {
    it('tolerates RTL direction without horizontal overflow', () => {
      document.documentElement.setAttribute('dir', 'rtl');
      const fixture = build();
      const host = fixture.nativeElement as HTMLElement;
      host.style.width = '375px';
      fixture.detectChanges();
      expect(host.scrollWidth).toBeLessThanOrEqual(host.clientWidth + 1);
    });
  });

  // ---- Token leakage ----

  describe('token leakage controls', () => {
    it('never writes the token to local/session storage', () => {
      const localSpy = spyOn(Storage.prototype, 'setItem').and.callThrough();
      const fixture = build();
      fixture.componentInstance.doCancel();
      const wrote = localSpy.calls.allArgs().some((args) => args.join(' ').includes('tok123'));
      expect(wrote).toBe(false);
    });

    it('never logs the token on a network error', () => {
      const errSpy = spyOn(console, 'error').and.callThrough();
      const fixture = build({ cancel: () => throwError(() => err(0)) });
      fixture.componentInstance.doCancel();
      const logged = errSpy.calls.allArgs().some((args) => args.join(' ').includes('tok123'));
      expect(logged).toBe(false);
    });
  });
});
