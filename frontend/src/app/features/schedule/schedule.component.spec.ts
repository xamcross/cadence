import { ComponentFixture, TestBed, fakeAsync, flushMicrotasks } from '@angular/core/testing';
import { ActivatedRoute } from '@angular/router';
import { NEVER, Observable, of, throwError } from 'rxjs';
import { ScheduleComponent } from './schedule.component';
import { CandidateSlot, CandidateSlotsResponse, ConfirmResponse, ScheduleService } from './schedule.service';
import { attachToBody, axeViolations, detachFromBody } from '../../../testing/axe';

/**
 * F14 candidate scheduling page (§IX). Verifies: WCAG 2.2 AA (axe 0 violations across every state),
 * focus management + live announcements, accessible slot names, consistent help, no-CAPTCHA, mobile
 * touch-targets + no horizontal scroll, local-zone slot rendering, the full token-state machine,
 * no client-side token persistence / no token in console, and RTL / long-string overflow tolerance.
 */
describe('ScheduleComponent (F14)', () => {
  const slot: CandidateSlot = {
    slotId: '0', start: '2026-06-20T14:00:00Z', end: '2026-06-20T15:00:00Z', zoneId: 'America/New_York'
  };
  const open: CandidateSlotsResponse = {
    status: 'open', zoneHint: 'America/New_York', bookedStart: null, slots: [slot]
  };
  const emptyOpen: CandidateSlotsResponse = { status: 'open', zoneHint: 'America/New_York', bookedStart: null, slots: [] };
  const bookedView: CandidateSlotsResponse = {
    status: 'booked', zoneHint: 'America/New_York', bookedStart: '2026-06-20T14:00:00Z', slots: []
  };
  const confirmed: ConfirmResponse = { status: 'booked', bookedStart: '2026-06-20T14:00:00Z', zoneId: 'America/New_York' };

  const err = (status: number, code?: string) => ({ status, error: code ? { error: code } : null });

  let activeEl: HTMLElement | null = null;

  function build(view: () => Observable<CandidateSlotsResponse>,
                 confirm: () => Observable<ConfirmResponse> = () => of(confirmed),
                 token = 'tok123'): ComponentFixture<ScheduleComponent> {
    const svc: Partial<ScheduleService> = { view, confirm };
    const route = { snapshot: { queryParamMap: { get: (_: string) => token } } };
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [ScheduleComponent],
      providers: [
        { provide: ScheduleService, useValue: svc },
        { provide: ActivatedRoute, useValue: route }
      ]
    });
    const fixture = TestBed.createComponent(ScheduleComponent);
    activeEl = fixture.nativeElement as HTMLElement;
    attachToBody(activeEl);
    fixture.detectChanges(); // ngOnInit -> view() (synchronous with of()) -> state
    return fixture;
  }

  afterEach(() => {
    if (activeEl) { detachFromBody(activeEl); activeEl = null; }
    document.documentElement.removeAttribute('dir');
  });

  // ---- State machine (T018, FR-013..017, SC-008) ----

  describe('token-state machine', () => {
    it('renders open with slots', () => {
      const c = build(() => of(open)).componentInstance;
      expect(c.state()).toBe('open');
      expect(c.slots().length).toBe(1);
    });

    it('renders empty when a valid link has zero slots', () => {
      const c = build(() => of(emptyOpen)).componentInstance;
      expect(c.state()).toBe('empty');
    });

    it('renders booked (existing confirmation) on reopen', () => {
      const c = build(() => of(bookedView)).componentInstance;
      expect(c.state()).toBe('booked');
      expect(c.bookedStart()).toBe('2026-06-20T14:00:00Z');
    });

    it('renders a distinct expired state for 410', () => {
      const c = build(() => throwError(() => err(410))).componentInstance;
      expect(c.state()).toBe('expired');
    });

    it('renders invalid (indistinguishable) for 400 and for a missing token', () => {
      expect(build(() => throwError(() => err(400))).componentInstance.state()).toBe('invalid');
      expect(build(() => of(open), () => of(confirmed), '').componentInstance.state()).toBe('invalid');
    });

    it('renders rate_limited for 429', () => {
      const c = build(() => throwError(() => err(429))).componentInstance;
      expect(c.state()).toBe('rate_limited');
    });

    it('renders a retryable error (not invalid) on a network failure (status 0)', () => {
      const c = build(() => throwError(() => err(0))).componentInstance;
      expect(c.state()).toBe('retryable_error');
    });

    it('confirms a slot into booked', () => {
      const c = build(() => of(open)).componentInstance;
      c.confirm(slot);
      expect(c.state()).toBe('booked');
    });

    it('on slot_taken: warns, re-loads remaining, stays open', () => {
      let views = 0;
      const c = build(() => { views++; return of(open); }, () => throwError(() => err(409, 'slot_taken'))).componentInstance;
      c.confirm(slot);
      expect(c.error()).toContain('just taken');
      expect(views).toBe(2);
      expect(c.state()).toBe('open');
    });

    it('on slot_taken with no remaining slots: falls through to empty', () => {
      let views = 0;
      const c = build(() => { views++; return of(views === 1 ? open : emptyOpen); },
        () => throwError(() => err(409, 'slot_taken'))).componentInstance;
      c.confirm(slot);
      expect(c.state()).toBe('empty');
    });

    it('on not_available: invalid (byte-identical refusal)', () => {
      const c = build(() => of(open), () => throwError(() => err(409, 'not_available'))).componentInstance;
      c.confirm(slot);
      expect(c.state()).toBe('invalid');
    });

    it('on cleanup_incomplete: problem state', () => {
      const c = build(() => of(open), () => throwError(() => err(409, 'cleanup_incomplete'))).componentInstance;
      c.confirm(slot);
      expect(c.state()).toBe('problem');
    });

    it('on a 400 at confirm (used/superseded token): goes to invalid, not a dead slot list', () => {
      const c = build(() => of(open), () => throwError(() => err(400, 'invalid'))).componentInstance;
      c.confirm(slot);
      expect(c.state()).toBe('invalid');
    });

    it('rate_limited message does not echo any quota/window', () => {
      const c = build(() => throwError(() => err(429)));
      const text = (c.nativeElement as HTMLElement).textContent ?? '';
      expect(text).not.toMatch(/\d+\s*(per|\/)\s*(minute|min|second)/i);
    });
  });

  // ---- Accessibility: axe per state (T012, FR-005, SC-002) ----

  describe('axe WCAG 2.2 AA — zero violations per state', () => {
    const cases: Array<[string, () => Observable<CandidateSlotsResponse>, (() => Observable<ConfirmResponse>)?]> = [
      ['loading', () => NEVER as unknown as Observable<CandidateSlotsResponse>],
      ['open', () => of(open)],
      ['empty', () => of(emptyOpen)],
      ['booked', () => of(bookedView)],
      ['expired', () => throwError(() => err(410))],
      ['invalid', () => throwError(() => err(400))],
      ['rate_limited', () => throwError(() => err(429))],
      ['retryable_error', () => throwError(() => err(0))]
    ];

    cases.forEach(([name, view]) => {
      it(`${name} state has no violations`, async () => {
        const fixture = build(view);
        await fixture.whenStable();
        fixture.detectChanges();
        const violations = await axeViolations(fixture.nativeElement);
        expect(violations).withContext(violations.map(v => v.id).join(', ')).toEqual([]);
      });
    });

    it('problem state has no violations', async () => {
      const fixture = build(() => of(open), () => throwError(() => err(409, 'cleanup_incomplete')));
      fixture.componentInstance.confirm(slot);
      await fixture.whenStable();
      fixture.detectChanges();
      const violations = await axeViolations(fixture.nativeElement);
      expect(violations).withContext(violations.map(v => v.id).join(', ')).toEqual([]);
    });
  });

  // ---- Focus management + live region (T013, FR-024) ----

  describe('focus management', () => {
    it('moves focus to the state heading after a transition to booked', fakeAsync(() => {
      const fixture = build(() => of(open));
      fixture.componentInstance.confirm(slot); // -> booked
      fixture.detectChanges();
      flushMicrotasks();
      const heading = fixture.nativeElement.querySelector('.state-heading') as HTMLElement;
      expect(document.activeElement).toBe(heading);
      expect(heading.getAttribute('tabindex')).toBe('-1');
    }));

    it('does not steal focus on the initial loading paint', fakeAsync(() => {
      const fixture = build(() => NEVER as unknown as Observable<CandidateSlotsResponse>);
      fixture.detectChanges();
      flushMicrotasks();
      const heading = fixture.nativeElement.querySelector('.state-heading') as HTMLElement;
      expect(document.activeElement).not.toBe(heading);
    }));

    it('exposes a single assertive alert region for transient errors', () => {
      const fixture = build(() => of(open), () => throwError(() => err(409, 'slot_taken')));
      fixture.componentInstance.confirm(slot);
      fixture.detectChanges();
      const alert = fixture.nativeElement.querySelector('[role="alert"]') as HTMLElement;
      expect(alert).withContext('an alert region should carry the transient conflict message').not.toBeNull();
      expect(alert.textContent).toContain('just taken');
    });
  });

  // ---- WCAG 2.2 non-automatable-by-axe (T014/T007) ----

  describe('WCAG 2.2 specifics', () => {
    it('slot control has an accessible name conveying the full date and time (FR-007)', () => {
      const fixture = build(() => of(open));
      const btn = fixture.nativeElement.querySelector('button.slot') as HTMLElement;
      const label = btn.getAttribute('aria-label') ?? '';
      expect(label).toMatch(/2026/);
      expect(label.toLowerCase()).toContain('book interview');
    });

    it('rate_limited renders NO CAPTCHA / cognitive-test element (3.3.8 / FR-022)', () => {
      const fixture = build(() => throwError(() => err(429)));
      const host = fixture.nativeElement as HTMLElement;
      expect(host.querySelector('iframe, [class*="captcha" i], [id*="captcha" i], [data-captcha]')).toBeNull();
      expect((host.textContent ?? '').toLowerCase()).not.toMatch(/captcha|prove you.?re human|verify you.?re human/);
    });

    it('shows the same "contact your recruiter" help text + placement across what-next states (3.2.6 / FR-023)', () => {
      const states: Array<() => Observable<CandidateSlotsResponse>> = [
        () => of(emptyOpen), () => throwError(() => err(410)),
        () => throwError(() => err(400)), () => throwError(() => err(429))
      ];
      const helpTexts = states.map((v) => {
        const fx = build(v);
        const help = fx.nativeElement.querySelector('.help') as HTMLElement;
        return help?.textContent?.trim();
      });
      expect(helpTexts.every((t) => t && t === helpTexts[0])).withContext(JSON.stringify(helpTexts)).toBe(true);
    });

    it('slot/action controls meet the 44px minimum target size (2.5.8 / FR-003)', () => {
      const fixture = build(() => of(open));
      const btn = fixture.nativeElement.querySelector('button.slot') as HTMLElement;
      const rect = btn.getBoundingClientRect();
      expect(rect.height).toBeGreaterThanOrEqual(44);
      expect(rect.width).toBeGreaterThanOrEqual(44);
    });
  });

  // ---- Mobile / responsive / time-zone (T007/T008, FR-002/009, SC-004/006) ----

  describe('mobile + time-zone presentation', () => {
    it('does not horizontally scroll at a 375px viewport width', () => {
      const fixture = build(() => of(open));
      const host = fixture.nativeElement as HTMLElement;
      host.style.width = '375px';
      fixture.detectChanges();
      expect(host.scrollWidth).toBeLessThanOrEqual(host.clientWidth + 1); // +1 for sub-pixel rounding
    });

    it('shows the local zone hint and the offered zone (FR-009)', () => {
      const fixture = build(() => of(open));
      const zone = fixture.nativeElement.querySelector('.zone') as HTMLElement;
      expect(zone.textContent).toContain('America/New_York'); // offered zone
      expect(fixture.componentInstance.localZone().length).toBeGreaterThan(0);
    });

    it('renders no participant identity — times only (FR-010)', () => {
      const fixture = build(() => of(open));
      const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
      expect(text).not.toMatch(/@/); // no emails
    });
  });

  // ---- Localization / RTL (T022, FR-012, SC-007) ----

  describe('localization + RTL', () => {
    it('tolerates RTL direction and long strings without horizontal overflow', () => {
      document.documentElement.setAttribute('dir', 'rtl');
      const longZone = 'Antarctica/DumontDUrville_with_a_very_long_descriptive_suffix_for_pseudo_localization';
      const fixture = build(() => of({ ...open, zoneHint: longZone }));
      const host = fixture.nativeElement as HTMLElement;
      host.style.width = '375px';
      fixture.detectChanges();
      expect(host.scrollWidth).toBeLessThanOrEqual(host.clientWidth + 1);
    });
  });

  // ---- Token leakage / no client persistence (T021, FR-019/026, SC-009/010) ----

  describe('token leakage controls', () => {
    it('never writes the token to local/session storage', () => {
      const localSpy = spyOn(Storage.prototype, 'setItem').and.callThrough();
      const fixture = build(() => of(open));
      fixture.componentInstance.confirm(slot);
      const wroteToken = localSpy.calls.allArgs().some((args) => args.join(' ').includes('tok123'));
      expect(wroteToken).toBe(false);
    });

    it('never logs the token (no console.error with the token) on a network error', () => {
      const errSpy = spyOn(console, 'error').and.callThrough();
      build(() => throwError(() => err(0)));
      const loggedToken = errSpy.calls.allArgs().some((args) => args.join(' ').includes('tok123'));
      expect(loggedToken).toBe(false);
    });
  });
});
