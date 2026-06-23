import { ComponentFixture, TestBed, fakeAsync, flushMicrotasks } from '@angular/core/testing';
import { ActivatedRoute } from '@angular/router';
import { Observable, of, throwError } from 'rxjs';
import { ConfirmAttendanceComponent } from './confirm-attendance.component';
import { BookingService, ConfirmAttendanceResponse } from './booking.service';
import { attachToBody, axeViolations, detachFromBody } from '../../../testing/axe';
import { CandidateBrandingService } from '../../core/branding/candidate-branding.service';

/**
 * F23 candidate attendance confirmation (§IX). Verifies: the confirm POST fires ONLY on the affirmative
 * "Confirm attendance" click (NEVER on page load — FR-006), the booked time renders in the candidate's LOCAL
 * time zone, WCAG 2.2 AA (axe 0 violations across loading / confirm / confirmed / expired / invalid states),
 * focus management, 44px targets, no horizontal scroll at 375px, RTL tolerance, no login, and no client-side
 * token persistence / no token in console (FR-019 token-leakage).
 */
describe('ConfirmAttendanceComponent (F23)', () => {
  const confirmed: ConfirmAttendanceResponse = {
    status: 'confirmed',
    bookedStart: '2026-06-20T09:00:00Z',
    zoneId: 'Europe/Prague',
    at: '2026-06-19T09:03:11Z'
  };
  const err = (status: number, code?: string) => ({ status, error: code ? { error: code } : null });

  let activeEl: HTMLElement | null = null;
  let confirmSpy: jasmine.Spy;

  function build(opts: { confirm?: () => Observable<ConfirmAttendanceResponse>; token?: string } = {}): ComponentFixture<ConfirmAttendanceComponent> {
    const token = opts.token ?? 'tok123';
    confirmSpy = jasmine.createSpy('confirm').and.callFake(opts.confirm ?? (() => of(confirmed)));
    const bookingSvc: Partial<BookingService> = { confirm: confirmSpy as unknown as BookingService['confirm'] };
    const route = { snapshot: { queryParamMap: { get: (_: string) => token } } };
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [ConfirmAttendanceComponent],
      providers: [
        { provide: BookingService, useValue: bookingSvc },
        { provide: ActivatedRoute, useValue: route },
        { provide: CandidateBrandingService, useValue: { applyAccent: () => {}, setAccent: () => {} } }
      ]
    });
    const fixture = TestBed.createComponent(ConfirmAttendanceComponent);
    activeEl = fixture.nativeElement as HTMLElement;
    attachToBody(activeEl);
    fixture.detectChanges();
    return fixture;
  }

  afterEach(() => {
    if (activeEl) { detachFromBody(activeEl); activeEl = null; }
    document.documentElement.removeAttribute('dir');
  });

  // ---- The load-bearing FR-006 guarantee (no auto-confirm) ----

  describe('affirmative-action only (FR-006)', () => {
    it('does NOT fire the confirm POST on page load', () => {
      build();
      expect(confirmSpy).not.toHaveBeenCalled();
    });

    it('starts on the confirm prompt with an explicit Confirm button (no auto-confirm)', () => {
      const fixture = build();
      expect(fixture.componentInstance.state()).toBe('confirm');
      const btn = fixture.nativeElement.querySelector('button.reschedule') as HTMLButtonElement;
      expect(btn).toBeTruthy();
      expect((btn.textContent ?? '').toLowerCase()).toContain('confirm');
    });

    it('fires the confirm POST only on the affirmative "Confirm attendance" click', () => {
      const fixture = build();
      const btn = fixture.nativeElement.querySelector('button.reschedule') as HTMLButtonElement;
      btn.click();
      expect(confirmSpy).toHaveBeenCalledTimes(1);
      expect(confirmSpy).toHaveBeenCalledWith('tok123');
      expect(fixture.componentInstance.state()).toBe('confirmed');
    });

    it('a missing token short-circuits to invalid without POSTing', () => {
      const fixture = build({ token: '' });
      expect(fixture.componentInstance.state()).toBe('invalid');
      fixture.componentInstance.doConfirm();
      expect(confirmSpy).not.toHaveBeenCalled();
    });
  });

  // ---- Confirm outcomes ----

  describe('confirm outcomes', () => {
    it('renders a localized success acknowledgement on 200', () => {
      const fixture = build();
      fixture.componentInstance.doConfirm();
      fixture.detectChanges();
      expect(fixture.componentInstance.state()).toBe('confirmed');
      const text = ((fixture.nativeElement as HTMLElement).textContent ?? '').toLowerCase();
      expect(text).toContain('confirmed');
    });

    it('idempotent replay: a second confirm returns the existing acknowledgement', () => {
      const fixture = build();
      fixture.componentInstance.doConfirm();
      fixture.componentInstance.doConfirm();
      expect(confirmSpy).toHaveBeenCalledTimes(2);
      expect(fixture.componentInstance.state()).toBe('confirmed');
    });

    it('maps 410 expired, 400 invalid, 429 rate-limited distinctly', () => {
      const expired = build({ confirm: () => throwError(() => err(410)) });
      expired.componentInstance.doConfirm();
      expect(expired.componentInstance.state()).toBe('expired');

      const invalid = build({ confirm: () => throwError(() => err(400)) });
      invalid.componentInstance.doConfirm();
      expect(invalid.componentInstance.state()).toBe('invalid');

      const limited = build({ confirm: () => throwError(() => err(429)) });
      limited.componentInstance.doConfirm();
      expect(limited.componentInstance.state()).toBe('rate_limited');
    });

    it('on a network failure: retryable_error and the booking is reported still booked', () => {
      const fixture = build({ confirm: () => throwError(() => err(0)) });
      fixture.componentInstance.doConfirm();
      fixture.detectChanges();
      expect(fixture.componentInstance.state()).toBe('retryable_error');
      const text = ((fixture.nativeElement as HTMLElement).textContent ?? '').toLowerCase();
      expect(text).toContain('still booked');
    });
  });

  // ---- Local time zone rendering (US1 AC#2) ----

  describe('local-zone time rendering', () => {
    it('renders the booked time using the local-zone DST token (zzz)', () => {
      const fixture = build();
      fixture.componentInstance.doConfirm();
      fixture.detectChanges();
      // The host renders a date string; the zone label is the device's resolved zone, never the raw ISO.
      const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
      expect(text).not.toContain('2026-06-20T09:00:00Z'); // not the raw wire instant
      expect(text.toLowerCase()).toContain('local time zone');
      const zone = Intl.DateTimeFormat().resolvedOptions().timeZone;
      expect(text).toContain(zone);
    });
  });

  // ---- axe per state ----

  describe('axe WCAG 2.2 AA — zero violations per state', () => {
    async function auditAfter(prep: (f: ComponentFixture<ConfirmAttendanceComponent>) => void): Promise<void> {
      const fixture = build();
      prep(fixture);
      await fixture.whenStable();
      fixture.detectChanges();
      const v = await axeViolations(fixture.nativeElement);
      expect(v).withContext(v.map((x) => x.id).join(', ')).toEqual([]);
    }

    it('loading state has no violations', async () => {
      const fixture = build({ confirm: () => new Observable<ConfirmAttendanceResponse>() });
      fixture.componentInstance.state.set('loading');
      await fixture.whenStable();
      fixture.detectChanges();
      const v = await axeViolations(fixture.nativeElement);
      expect(v).withContext(v.map((x) => x.id).join(', ')).toEqual([]);
    });

    it('confirm state has no violations', async () => {
      await auditAfter(() => { /* initial confirm prompt */ });
    });

    it('confirmed state has no violations', async () => {
      await auditAfter((f) => f.componentInstance.doConfirm());
    });

    it('expired state has no violations', async () => {
      const fixture = build({ confirm: () => throwError(() => err(410)) });
      fixture.componentInstance.doConfirm();
      await fixture.whenStable();
      fixture.detectChanges();
      const v = await axeViolations(fixture.nativeElement);
      expect(v).withContext(v.map((x) => x.id).join(', ')).toEqual([]);
    });

    it('invalid state has no violations', async () => {
      const fixture = build({ token: '' });
      await fixture.whenStable();
      fixture.detectChanges();
      const v = await axeViolations(fixture.nativeElement);
      expect(v).withContext(v.map((x) => x.id).join(', ')).toEqual([]);
    });
  });

  // ---- Focus management ----

  describe('focus management', () => {
    it('moves focus to the heading after confirming', fakeAsync(() => {
      const fixture = build();
      fixture.componentInstance.doConfirm();
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
    it('the Confirm button meets the 44px minimum target size (2.5.8)', () => {
      const fixture = build();
      const btn = fixture.nativeElement.querySelector('button.reschedule') as HTMLElement;
      const rect = btn.getBoundingClientRect();
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
      fixture.componentInstance.doConfirm();
      fixture.detectChanges();
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
      const storeSpy = spyOn(Storage.prototype, 'setItem').and.callThrough();
      const fixture = build();
      fixture.componentInstance.doConfirm();
      const wrote = storeSpy.calls.allArgs().some((args) => args.join(' ').includes('tok123'));
      expect(wrote).toBe(false);
    });

    it('never logs the token on a network error', () => {
      const errSpy = spyOn(console, 'error').and.callThrough();
      const fixture = build({ confirm: () => throwError(() => err(0)) });
      fixture.componentInstance.doConfirm();
      const logged = errSpy.calls.allArgs().some((args) => args.join(' ').includes('tok123'));
      expect(logged).toBe(false);
    });
  });

  // ---- 031-terms-privacy-notice: per-surface Privacy link (T018, C-LINK-2/3, SC-002/006) ----

  describe('Privacy Notice link', () => {
    it('renders a token-safe /privacy link opening in a new tab', () => {
      const link = build().nativeElement.querySelector('a.privacy-link') as HTMLAnchorElement;
      expect(link).withContext('a Privacy Notice link should be present').not.toBeNull();
      expect(link.getAttribute('href')).toBe('/privacy');
      expect(link.hasAttribute('routerLink')).toBe(false);
      expect(link.getAttribute('target')).toBe('_blank');
      expect(link.getAttribute('rel')).toBe('noopener noreferrer');
      expect(link.getAttribute('href')).not.toContain('tok123');
      expect(link.getAttribute('href')).not.toContain('?');
    });

    it('writes no web storage when the Privacy link is clicked', () => {
      const setItem = spyOn(Storage.prototype, 'setItem').and.callThrough();
      const link = build().nativeElement.querySelector('a.privacy-link') as HTMLAnchorElement;
      spyOn(link, 'click');
      link.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
      const wroteToken = setItem.calls.allArgs().some((args) => args.join(' ').includes('tok123'));
      expect(wroteToken).toBe(false);
    });
  });
});
