import { ComponentFixture, TestBed, fakeAsync, flushMicrotasks } from '@angular/core/testing';
import { ActivatedRoute, Router } from '@angular/router';
import { NEVER, Observable, of, throwError } from 'rxjs';
import { BookingManageComponent } from './booking-manage.component';
import { BookingService, BookingView, OpenRescheduleResponse } from './booking.service';
import { ConfirmResponse, CandidateSlotsResponse, ScheduleService } from '../schedule/schedule.service';
import { attachToBody, axeViolations, detachFromBody } from '../../../testing/axe';
import { CandidateBrandingService } from '../../core/branding/candidate-branding.service';

/**
 * F20 candidate booking-management page (§IX). Verifies: WCAG 2.2 AA (axe 0 violations across booked /
 * reschedule-open / cancelled / error states, body-attached), focus management + live region, accessible
 * action/slot names, consistent help, mobile touch-targets + no horizontal scroll, local-zone rendering,
 * the manage-token state machine (incl. distinct 410-expired and 400-invalid), capability-driven disabled
 * actions, cancel-never-on-load (navigates instead), no client-side token persistence / no token in console,
 * and RTL / long-string overflow tolerance.
 */
describe('BookingManageComponent (F20)', () => {
  const bookedView: BookingView = {
    status: 'booked', bookedStart: '2026-06-20T14:00:00Z', zoneId: 'America/New_York',
    at: null, canReschedule: true, canCancel: true, rescheduleRemaining: 2
  };
  const cancelledView: BookingView = {
    status: 'cancelled', bookedStart: null, zoneId: null, at: '2026-06-19T10:00:00Z',
    canReschedule: false, canCancel: false, rescheduleRemaining: 0
  };
  const rescheduledView: BookingView = {
    status: 'rescheduled', bookedStart: null, zoneId: null, at: '2026-06-19T10:00:00Z',
    canReschedule: false, canCancel: false, rescheduleRemaining: 0
  };
  const noActionsView: BookingView = {
    ...bookedView, canReschedule: false, canCancel: false, rescheduleRemaining: 0
  };

  const openReschedule: OpenRescheduleResponse = {
    rescheduleToken: 'rtok', zoneHint: 'America/New_York',
    slots: [{ slotId: '0', start: '2026-06-22T14:00:00Z', end: '2026-06-22T15:00:00Z', zoneId: 'America/New_York' }]
  };
  const emptyReschedule: OpenRescheduleResponse = { rescheduleToken: 'rtok', zoneHint: 'America/New_York', slots: [] };
  const confirmed: ConfirmResponse = { status: 'booked', bookedStart: '2026-06-22T14:00:00Z', zoneId: 'America/New_York' };

  const err = (status: number, code?: string) => ({ status, error: code ? { error: code } : null });

  let activeEl: HTMLElement | null = null;
  let navigateSpy: jasmine.Spy;

  function build(opts: {
    view?: () => Observable<BookingView>;
    open?: () => Observable<OpenRescheduleResponse>;
    confirm?: () => Observable<ConfirmResponse>;
    scheduleView?: () => Observable<CandidateSlotsResponse>;
    token?: string;
  } = {}): ComponentFixture<BookingManageComponent> {
    const token = opts.token ?? 'tok123';
    const bookingSvc: Partial<BookingService> = {
      view: opts.view ?? (() => of(bookedView)),
      openReschedule: opts.open ?? (() => of(openReschedule)),
      cancel: () => of({ status: 'cancelled', at: '2026-06-19T10:00:00Z' })
    };
    const scheduleSvc: Partial<ScheduleService> = {
      confirm: opts.confirm ?? (() => of(confirmed)),
      view: opts.scheduleView ?? (() => of({ status: 'open', zoneHint: 'America/New_York', bookedStart: null, slots: openReschedule.slots }))
    };
    const route = { snapshot: { queryParamMap: { get: (_: string) => token } } };
    navigateSpy = jasmine.createSpy('navigate');
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [BookingManageComponent],
      providers: [
        { provide: BookingService, useValue: bookingSvc },
        { provide: ScheduleService, useValue: scheduleSvc },
        { provide: ActivatedRoute, useValue: route },
        { provide: Router, useValue: { navigate: navigateSpy } },
        { provide: CandidateBrandingService, useValue: { applyAccent: () => {}, setAccent: () => {} } }
      ]
    });
    const fixture = TestBed.createComponent(BookingManageComponent);
    activeEl = fixture.nativeElement as HTMLElement;
    attachToBody(activeEl);
    fixture.detectChanges(); // ngOnInit -> view() (sync with of()) -> state
    return fixture;
  }

  afterEach(() => {
    if (activeEl) { detachFromBody(activeEl); activeEl = null; }
    document.documentElement.removeAttribute('dir');
  });

  // ---- Manage-token state machine ----

  describe('manage-token state machine', () => {
    it('renders booked with manage actions', () => {
      const c = build().componentInstance;
      expect(c.state()).toBe('booked');
      expect(c.bookedStart()).toBe('2026-06-20T14:00:00Z');
    });

    it('renders the terminal cancelled state', () => {
      const c = build({ view: () => of(cancelledView) }).componentInstance;
      expect(c.state()).toBe('cancelled');
    });

    it('renders the terminal rescheduled state (superseded link)', () => {
      const c = build({ view: () => of(rescheduledView) }).componentInstance;
      expect(c.state()).toBe('rescheduled');
    });

    it('renders a distinct expired state for 410 (interview in the past)', () => {
      const c = build({ view: () => throwError(() => err(410)) }).componentInstance;
      expect(c.state()).toBe('expired');
    });

    it('renders invalid (indistinguishable) for 400 and for a missing token', () => {
      expect(build({ view: () => throwError(() => err(400)) }).componentInstance.state()).toBe('invalid');
      expect(build({ token: '' }).componentInstance.state()).toBe('invalid');
    });

    it('renders rate_limited for 429', () => {
      expect(build({ view: () => throwError(() => err(429)) }).componentInstance.state()).toBe('rate_limited');
    });

    it('renders a retryable error (not invalid) on a network failure (status 0)', () => {
      expect(build({ view: () => throwError(() => err(0)) }).componentInstance.state()).toBe('retryable_error');
    });

    it('opens a reschedule round into the slot-picker', () => {
      const c = build().componentInstance;
      c.startReschedule();
      expect(c.state()).toBe('reschedule_open');
      expect(c.slots().length).toBe(1);
    });

    it('on 422 no_slots: reschedule_empty (booking retained)', () => {
      const c = build({ open: () => throwError(() => err(422)) }).componentInstance;
      c.startReschedule();
      expect(c.state()).toBe('reschedule_empty');
    });

    it('on cap_reached: an oracle-free error message (no quota numbers)', () => {
      const c = build({ open: () => throwError(() => err(409, 'cap_reached')) }).componentInstance;
      c.startReschedule();
      expect(c.error()).toContain('reschedule limit');
      expect(c.state()).toBe('booked');
    });

    it('on ineligible: a helpful error, booking unchanged', () => {
      const c = build({ open: () => throwError(() => err(409, 'ineligible')) }).componentInstance;
      c.startReschedule();
      expect(c.error()).toBeTruthy();
      expect(c.state()).toBe('booked');
    });

    it('on not_available (erased): byte-identical neutral refusal (no GDPR oracle)', () => {
      const c = build({ open: () => throwError(() => err(409, 'not_available')) }).componentInstance;
      c.startReschedule();
      expect((c.error() ?? '').toLowerCase()).not.toMatch(/eras|withdraw|consent|gdpr/);
    });

    it('confirms a reschedule slot into reschedule_done (reuses F13 confirm)', () => {
      const c = build().componentInstance;
      c.startReschedule();
      c.confirmReschedule(openReschedule.slots[0]);
      expect(c.state()).toBe('reschedule_done');
      expect(c.bookedStart()).toBe('2026-06-22T14:00:00Z');
    });

    it('on slot_taken during reschedule confirm: warns and reloads remaining', () => {
      const c = build({ confirm: () => throwError(() => err(409, 'slot_taken')) }).componentInstance;
      c.startReschedule();
      c.confirmReschedule(openReschedule.slots[0]);
      expect(c.error()).toContain('just taken');
      expect(c.state()).toBe('reschedule_open');
    });

    it('on a 400 at reschedule confirm: re-resolves the intact original booking', () => {
      const c = build({ confirm: () => throwError(() => err(400, 'invalid')) }).componentInstance;
      c.startReschedule();
      c.confirmReschedule(openReschedule.slots[0]);
      expect(c.state()).toBe('booked'); // original preserved (FR-009/FR-010)
    });
  });

  // ---- Capability-driven actions ----

  describe('capabilities', () => {
    it('disables Reschedule and Cancel when the server says so, with helpful hints', () => {
      const fixture = build({ view: () => of(noActionsView) });
      const host = fixture.nativeElement as HTMLElement;
      const reschedule = host.querySelector('button.reschedule') as HTMLButtonElement;
      const cancel = host.querySelector('button.cancel') as HTMLButtonElement;
      expect(reschedule.disabled).toBe(true);
      expect(cancel.disabled).toBe(true);
      const hints = (host.textContent ?? '').toLowerCase();
      expect(hints).toContain("isn't available");
    });

    it('Cancel navigates to the affirmative confirm page — it does NOT POST on this page', () => {
      const cancelSpy = jasmine.createSpy('cancel');
      const fixture = build();
      // Re-wire cancel to detect any accidental call from the manage page.
      (TestBed.inject(BookingService).cancel as unknown) = cancelSpy;
      fixture.componentInstance.goToCancel();
      expect(navigateSpy).toHaveBeenCalledWith(['/booking/cancel'], { queryParams: { token: 'tok123' } });
      expect(cancelSpy).not.toHaveBeenCalled();
    });
  });

  // ---- axe per state ----

  describe('axe WCAG 2.2 AA — zero violations per state', () => {
    const cases: Array<[string, () => ComponentFixture<BookingManageComponent>]> = [
      ['loading', () => build({ view: () => NEVER as unknown as Observable<BookingView> })],
      ['booked', () => build()],
      ['cancelled', () => build({ view: () => of(cancelledView) })],
      ['rescheduled', () => build({ view: () => of(rescheduledView) })],
      ['expired', () => build({ view: () => throwError(() => err(410)) })],
      ['invalid', () => build({ view: () => throwError(() => err(400)) })],
      ['rate_limited', () => build({ view: () => throwError(() => err(429)) })],
      ['retryable_error', () => build({ view: () => throwError(() => err(0)) })]
    ];

    cases.forEach(([name, make]) => {
      it(`${name} state has no violations`, async () => {
        const fixture = make();
        await fixture.whenStable();
        fixture.detectChanges();
        const violations = await axeViolations(fixture.nativeElement);
        expect(violations).withContext(violations.map(v => v.id).join(', ')).toEqual([]);
      });
    });

    it('reschedule_open state has no violations', async () => {
      const fixture = build();
      fixture.componentInstance.startReschedule();
      await fixture.whenStable();
      fixture.detectChanges();
      const violations = await axeViolations(fixture.nativeElement);
      expect(violations).withContext(violations.map(v => v.id).join(', ')).toEqual([]);
    });

    it('reschedule_done state has no violations', async () => {
      const fixture = build();
      fixture.componentInstance.startReschedule();
      fixture.componentInstance.confirmReschedule(openReschedule.slots[0]);
      await fixture.whenStable();
      fixture.detectChanges();
      const violations = await axeViolations(fixture.nativeElement);
      expect(violations).withContext(violations.map(v => v.id).join(', ')).toEqual([]);
    });

    it('reschedule_empty state has no violations', async () => {
      const fixture = build({ open: () => of(emptyReschedule) });
      fixture.componentInstance.startReschedule();
      await fixture.whenStable();
      fixture.detectChanges();
      const violations = await axeViolations(fixture.nativeElement);
      expect(violations).withContext(violations.map(v => v.id).join(', ')).toEqual([]);
    });
  });

  // ---- Brand-override contrast safety (030 design system) ----

  describe('brand-override contrast safety (adversarial hue)', () => {
    // The workspace brand --accent is overridden at runtime to an arbitrary validated hex (set on the
    // component host). A near-white yellow is the canonical contrast-killer. The candidate-safety rule is
    // that the FIXED --accent-ink/--accent-wash/--focus-ring tokens carry all text/fill/focus contrast and
    // only the BORDER tracks the brand - so axe must stay clean and the brand button must NOT adopt the
    // override for its fill or text. This guards the load-bearing rule against a future regression that
    // re-derives ink/wash/fill from --accent. (The other axe specs run with the DEFAULT accent only.)
    const ADVERSARIAL = '#ffe600';

    it('booked state has no axe violations under an adversarial brand --accent', async () => {
      const fixture = build();
      (fixture.nativeElement as HTMLElement).style.setProperty('--accent', ADVERSARIAL);
      fixture.detectChanges();
      await fixture.whenStable();
      const violations = await axeViolations(fixture.nativeElement);
      expect(violations).withContext(violations.map(v => v.id).join(', ')).toEqual([]);
    });

    it('the brand (reschedule) button keeps FIXED ink + wash regardless of the brand hue', () => {
      const fixture = build();
      (fixture.nativeElement as HTMLElement).style.setProperty('--accent', ADVERSARIAL);
      fixture.detectChanges();
      const brand = fixture.nativeElement.querySelector('.action.reschedule') as HTMLElement;
      const cs = getComputedStyle(brand);
      // --accent-wash (#eef3fe) and --accent-ink (#11337a) are fixed - never the #ffe600 override.
      expect(cs.backgroundColor).toBe('rgb(238, 243, 254)');
      expect(cs.color).toBe('rgb(17, 51, 122)');
    });
  });

  // ---- Focus management + live region ----

  describe('focus management', () => {
    it('moves focus to the state heading after a transition to reschedule_open', fakeAsync(() => {
      const fixture = build();
      fixture.componentInstance.startReschedule();
      fixture.detectChanges();
      flushMicrotasks();
      const heading = fixture.nativeElement.querySelector('.state-heading') as HTMLElement;
      expect(document.activeElement).toBe(heading);
      expect(heading.getAttribute('tabindex')).toBe('-1');
    }));

    it('does not steal focus on the initial loading paint', fakeAsync(() => {
      const fixture = build({ view: () => NEVER as unknown as Observable<BookingView> });
      fixture.detectChanges();
      flushMicrotasks();
      const heading = fixture.nativeElement.querySelector('.state-heading') as HTMLElement;
      expect(document.activeElement).not.toBe(heading);
    }));

    it('exposes a single assertive alert region for transient errors', () => {
      const fixture = build({ open: () => throwError(() => err(409, 'cap_reached')) });
      fixture.componentInstance.startReschedule();
      fixture.detectChanges();
      const alert = fixture.nativeElement.querySelector('[role="alert"]') as HTMLElement;
      expect(alert).not.toBeNull();
      expect(alert.textContent).toContain('reschedule limit');
    });
  });

  // ---- WCAG 2.2 non-automatable-by-axe ----

  describe('WCAG 2.2 specifics', () => {
    it('reschedule slot control has an accessible name conveying the full date and time (FR-007)', () => {
      const fixture = build();
      fixture.componentInstance.startReschedule();
      fixture.detectChanges();
      const btn = fixture.nativeElement.querySelector('button.slot') as HTMLElement;
      const label = btn.getAttribute('aria-label') ?? '';
      expect(label).toMatch(/2026/);
      expect(label.toLowerCase()).toContain('move interview');
    });

    it('action controls carry an accessible name with the booked date (FR-007)', () => {
      const fixture = build();
      const reschedule = fixture.nativeElement.querySelector('button.reschedule') as HTMLElement;
      expect((reschedule.getAttribute('aria-label') ?? '')).toMatch(/2026/);
    });

    it('renders NO CAPTCHA / cognitive-test element (3.3.8)', () => {
      const fixture = build({ view: () => throwError(() => err(429)) });
      const host = fixture.nativeElement as HTMLElement;
      expect(host.querySelector('iframe, [class*="captcha" i], [id*="captcha" i], [data-captcha]')).toBeNull();
      expect((host.textContent ?? '').toLowerCase()).not.toMatch(/captcha|prove you.?re human/);
    });

    it('shows the same "contact your recruiter" help text + placement across what-next states (3.2.6)', () => {
      const states: Array<() => ComponentFixture<BookingManageComponent>> = [
        () => build({ view: () => of(cancelledView) }),
        () => build({ view: () => throwError(() => err(410)) }),
        () => build({ view: () => throwError(() => err(400)) }),
        () => build({ view: () => throwError(() => err(429)) })
      ];
      const helpTexts = states.map((make) => {
        const fx = make();
        const help = fx.nativeElement.querySelector('.help') as HTMLElement;
        return help?.textContent?.trim();
      });
      expect(helpTexts.every((t) => t && t === helpTexts[0])).withContext(JSON.stringify(helpTexts)).toBe(true);
    });

    it('action controls meet the 44px minimum target size (2.5.8)', () => {
      const fixture = build();
      const btn = fixture.nativeElement.querySelector('button.reschedule') as HTMLElement;
      const rect = btn.getBoundingClientRect();
      expect(rect.height).toBeGreaterThanOrEqual(44);
      expect(rect.width).toBeGreaterThanOrEqual(44);
    });
  });

  // ---- Mobile / responsive / time-zone ----

  describe('mobile + time-zone presentation', () => {
    it('does not horizontally scroll at a 375px viewport width', () => {
      const fixture = build();
      const host = fixture.nativeElement as HTMLElement;
      host.style.width = '375px';
      fixture.detectChanges();
      expect(host.scrollWidth).toBeLessThanOrEqual(host.clientWidth + 1);
    });

    it('shows the local zone and renders no participant identity — times only (FR-020)', () => {
      const fixture = build();
      const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
      expect(text).not.toMatch(/@/); // no emails
      expect(fixture.componentInstance.localZone().length).toBeGreaterThan(0);
    });

    it('never renders a location / dial-in (FR-020/C3)', () => {
      const fixture = build();
      const text = ((fixture.nativeElement as HTMLElement).textContent ?? '').toLowerCase();
      expect(text).not.toContain('dial-in');
      expect(text).not.toContain('http://');
      expect(text).not.toContain('https://');
    });
  });

  // ---- Localization / RTL ----

  describe('localization + RTL', () => {
    it('tolerates RTL direction and long strings without horizontal overflow', () => {
      document.documentElement.setAttribute('dir', 'rtl');
      const longZone = 'Antarctica/DumontDUrville_with_a_very_long_descriptive_suffix_for_pseudo_localization';
      const fixture = build({ open: () => of({ ...openReschedule, zoneHint: longZone }) });
      fixture.componentInstance.startReschedule();
      const host = fixture.nativeElement as HTMLElement;
      host.style.width = '375px';
      fixture.detectChanges();
      expect(host.scrollWidth).toBeLessThanOrEqual(host.clientWidth + 1);
    });
  });

  // ---- Token leakage / no client persistence ----

  describe('token leakage controls', () => {
    it('never writes the token to local/session storage', () => {
      const localSpy = spyOn(Storage.prototype, 'setItem').and.callThrough();
      const fixture = build();
      fixture.componentInstance.startReschedule();
      const wroteToken = localSpy.calls.allArgs().some((args) => args.join(' ').includes('tok123'));
      expect(wroteToken).toBe(false);
    });

    it('never logs the token (no console.error with the token) on a network error', () => {
      const errSpy = spyOn(console, 'error').and.callThrough();
      build({ view: () => throwError(() => err(0)) });
      const loggedToken = errSpy.calls.allArgs().some((args) => args.join(' ').includes('tok123'));
      expect(loggedToken).toBe(false);
    });
  });
});
