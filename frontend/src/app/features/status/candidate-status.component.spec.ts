import { ComponentFixture, TestBed, fakeAsync, flushMicrotasks } from '@angular/core/testing';
import { ActivatedRoute } from '@angular/router';
import { Observable, of, throwError } from 'rxjs';
import { CandidateStatusComponent } from './candidate-status.component';
import { CandidateStatusView, ErasureAckResponse, PublicBranding, StatusService } from './status.service';
import { attachToBody, axeViolations, detachFromBody } from '../../../testing/axe';
import { CandidateBrandingService } from '../../core/branding/candidate-branding.service';

/**
 * F30 candidate status page (§IX). Verifies: WCAG 2.2 AA (axe 0 violations across PUBLISHED / PAST_DATE /
 * TERMINAL / UNDER_REVIEW), first-paint stage/next-step/date visible @375px (SC-001), markup-laden free text
 * renders INERT via interpolation — never [innerHTML] (SC-015), the token is held memory-only (no
 * localStorage/sessionStorage/console — SC-012), long-unbroken + RTL next-step has no horizontal scroll
 * (SC-003), the erasure-request ack flow (FR-015/017), and the contact route is workspace-sourced (never a
 * candidate email/phone — FR-007 negative).
 */
describe('CandidateStatusComponent (F30)', () => {
  const published: CandidateStatusView = {
    displayState: 'PUBLISHED',
    stage: 'Onsite interview',
    nextStep: 'We are collecting interviewer feedback.',
    expectedDate: '2030-01-20',
    outcome: 'IN_PROGRESS',
    workspaceZone: 'Europe/London'
  };
  const pastDate: CandidateStatusView = {
    displayState: 'PAST_DATE',
    stage: 'Onsite interview',
    nextStep: null,
    expectedDate: '2020-01-20',
    outcome: 'IN_PROGRESS',
    workspaceZone: 'Europe/London'
  };
  const terminal: CandidateStatusView = {
    displayState: 'TERMINAL',
    stage: null,
    nextStep: 'Thank you for your time. We will not be moving forward.',
    expectedDate: null,
    outcome: 'COMPLETE_REJECTED',
    workspaceZone: 'Europe/London'
  };
  const underReview: CandidateStatusView = {
    displayState: 'UNDER_REVIEW',
    workspaceZone: 'Europe/London'
  };
  const branding: PublicBranding = { brandColor: '#1F2937', logoUrl: '/api/public/workspace/logo' };
  const ack: ErasureAckResponse = { status: 'received' };
  const err = (status: number, code?: string) => ({ status, error: code ? { error: code } : null });

  let activeEl: HTMLElement | null = null;
  let viewSpy: jasmine.Spy;
  let erasureSpy: jasmine.Spy;
  let brandingSpy: jasmine.Spy;

  function build(opts: {
    view?: () => Observable<CandidateStatusView>;
    erasure?: () => Observable<ErasureAckResponse>;
    token?: string;
  } = {}): ComponentFixture<CandidateStatusComponent> {
    const token = opts.token ?? 'tok123';
    viewSpy = jasmine.createSpy('view').and.callFake(opts.view ?? (() => of(published)));
    erasureSpy = jasmine.createSpy('requestErasure').and.callFake(opts.erasure ?? (() => of(ack)));
    brandingSpy = jasmine.createSpy('branding').and.callFake(() => of(branding));
    const svc: Partial<StatusService> = {
      view: viewSpy as unknown as StatusService['view'],
      requestErasure: erasureSpy as unknown as StatusService['requestErasure'],
      branding: brandingSpy as unknown as StatusService['branding']
    };
    const route = { snapshot: { queryParamMap: { get: (_: string) => token } } };
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [CandidateStatusComponent],
      providers: [
        { provide: StatusService, useValue: svc },
        { provide: ActivatedRoute, useValue: route },
        { provide: CandidateBrandingService, useValue: { applyAccent: () => {}, setAccent: () => {} } }
      ]
    });
    const fixture = TestBed.createComponent(CandidateStatusComponent);
    activeEl = fixture.nativeElement as HTMLElement;
    attachToBody(activeEl);
    fixture.detectChanges();
    return fixture;
  }

  afterEach(() => {
    if (activeEl) { detachFromBody(activeEl); activeEl = null; }
    document.documentElement.removeAttribute('dir');
  });

  // ---- First paint / displayState rendering (SC-001) ----

  describe('displayState rendering', () => {
    it('renders stage, next step, and expected date for PUBLISHED', () => {
      const fixture = build({ view: () => of(published) });
      expect(fixture.componentInstance.state()).toBe('published');
      const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
      expect(text).toContain('Onsite interview');
      expect(text).toContain('We are collecting interviewer feedback.');
      // Date is presented in local presentation, never the raw ISO.
      expect(text).not.toContain('2030-01-20');
      expect(text).toContain('2030'); // the formatted year is present
    });

    it('renders the PAST_DATE framing preserving the stage (FR-017)', () => {
      const fixture = build({ view: () => of(pastDate) });
      expect(fixture.componentInstance.state()).toBe('past_date');
      expect((fixture.nativeElement as HTMLElement).textContent ?? '').toContain('Onsite interview');
    });

    it('renders the TERMINAL concluded message', () => {
      const fixture = build({ view: () => of(terminal) });
      expect(fixture.componentInstance.state()).toBe('terminal');
      expect((fixture.nativeElement as HTMLElement).textContent ?? '')
        .toContain('Thank you for your time.');
    });

    it('renders the neutral UNDER_REVIEW default', () => {
      const fixture = build({ view: () => of(underReview) });
      expect(fixture.componentInstance.state()).toBe('under_review');
      expect((fixture.nativeElement as HTMLElement).textContent?.toLowerCase() ?? '').toContain('reviewed');
    });

    it('maps 404 -> invalid (byte-identical not-an-oracle), 429 -> rate_limited, network -> retryable', () => {
      expect(build({ view: () => throwError(() => err(404)) }).componentInstance.state()).toBe('invalid');
      expect(build({ view: () => throwError(() => err(429)) }).componentInstance.state()).toBe('rate_limited');
      expect(build({ view: () => throwError(() => err(0)) }).componentInstance.state()).toBe('retryable_error');
    });

    it('a missing token short-circuits to invalid without fetching', () => {
      const fixture = build({ token: '' });
      expect(fixture.componentInstance.state()).toBe('invalid');
      expect(viewSpy).not.toHaveBeenCalled();
    });
  });

  // ---- Free text is INERT (SC-015) — interpolation, never [innerHTML] ----

  describe('free-text safety (SC-015)', () => {
    it('renders markup-laden next-step text inert (no script/markup injected into the DOM)', () => {
      const malicious: CandidateStatusView = {
        ...published,
        stage: '<img src=x onerror="window.__xss=1">',
        nextStep: '<script>window.__xss=2</script><b>bold</b>'
      };
      (window as unknown as { __xss?: number }).__xss = undefined;
      const fixture = build({ view: () => of(malicious) });
      fixture.detectChanges();
      const host = fixture.nativeElement as HTMLElement;
      // No injected element nodes from the free text.
      expect(host.querySelector('script')).toBeNull();
      expect(host.querySelector('.next-step b')).toBeNull();
      expect(host.querySelector('.stage img')).toBeNull();
      // The raw markup appears as escaped TEXT.
      expect((host.querySelector('.next-step')?.textContent ?? '')).toContain('<script>');
      expect((window as unknown as { __xss?: number }).__xss).toBeUndefined();
    });
  });

  // ---- axe per displayState ----

  describe('axe WCAG 2.2 AA — zero violations per displayState', () => {
    async function audit(v: CandidateStatusView): Promise<void> {
      const fixture = build({ view: () => of(v) });
      await fixture.whenStable();
      fixture.detectChanges();
      const violations = await axeViolations(fixture.nativeElement);
      expect(violations).withContext(violations.map((x) => x.id).join(', ')).toEqual([]);
    }

    it('PUBLISHED has no violations', async () => { await audit(published); });
    it('PAST_DATE has no violations', async () => { await audit(pastDate); });
    it('TERMINAL has no violations', async () => { await audit(terminal); });
    it('UNDER_REVIEW has no violations', async () => { await audit(underReview); });

    it('still 0 violations after the erasure ack is shown', async () => {
      const fixture = build();
      fixture.componentInstance.requestErasure();
      await fixture.whenStable();
      fixture.detectChanges();
      const violations = await axeViolations(fixture.nativeElement);
      expect(violations).withContext(violations.map((x) => x.id).join(', ')).toEqual([]);
    });
  });

  // ---- Focus management ----

  describe('focus management', () => {
    it('does not steal focus on first paint', fakeAsync(() => {
      const fixture = build();
      flushMicrotasks();
      const heading = fixture.nativeElement.querySelector('.state-heading') as HTMLElement;
      expect(document.activeElement).not.toBe(heading);
    }));

    it('moves focus to the heading on a post-first-paint state transition', fakeAsync(() => {
      // First paint settles into PUBLISHED (first settle is skipped). A subsequent transition (the retry
      // reload re-resolving into a state) moves focus to the heading (FR-024).
      const fixture = build();
      flushMicrotasks();
      // Force a transition: set the state directly to a different resolved state.
      fixture.componentInstance.state.set('under_review');
      fixture.detectChanges();
      flushMicrotasks();
      const heading = fixture.nativeElement.querySelector('.state-heading') as HTMLElement;
      expect(document.activeElement).toBe(heading);
    }));
  });

  // ---- Erasure request ack flow (FR-015/017) ----

  describe('erasure request', () => {
    it('does NOT POST on page load — only on the affirmative click', () => {
      const fixture = build();
      expect(erasureSpy).not.toHaveBeenCalled();
      const btn = fixture.nativeElement.querySelector('button.erasure-request') as HTMLButtonElement;
      expect(btn).toBeTruthy();
      btn.click();
      expect(erasureSpy).toHaveBeenCalledTimes(1);
      expect(erasureSpy).toHaveBeenCalledWith('tok123');
    });

    it('shows an on-page acknowledgement after a successful request', () => {
      const fixture = build();
      fixture.componentInstance.requestErasure();
      fixture.detectChanges();
      expect(fixture.componentInstance.erasureAck()).toBe(true);
      const ackEl = fixture.nativeElement.querySelector('.erasure-ack') as HTMLElement;
      expect(ackEl).toBeTruthy();
      expect((ackEl.textContent ?? '').toLowerCase()).toContain('received');
    });

    it('the erasure control is NOT offered on the invalid state', () => {
      const fixture = build({ view: () => throwError(() => err(404)) });
      expect(fixture.componentInstance.canRequestErasure()).toBe(false);
      expect(fixture.nativeElement.querySelector('button.erasure-request')).toBeNull();
    });
  });

  // ---- WCAG 2.2 specifics ----

  describe('WCAG 2.2 specifics', () => {
    it('the erasure button meets the 44px minimum target size (2.5.8)', () => {
      const fixture = build();
      const btn = fixture.nativeElement.querySelector('button.erasure-request') as HTMLElement;
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

    it('no horizontal scroll with a long unbroken next-step at 375px', () => {
      const longText = 'x'.repeat(400);
      const fixture = build({ view: () => of({ ...published, nextStep: longText }) });
      const host = fixture.nativeElement as HTMLElement;
      host.style.width = '375px';
      fixture.detectChanges();
      expect(host.scrollWidth).toBeLessThanOrEqual(host.clientWidth + 1);
    });

    it('tolerates RTL direction with a long next-step without horizontal overflow (SC-003)', () => {
      document.documentElement.setAttribute('dir', 'rtl');
      const longText = 'مرحبا'.repeat(120);
      const fixture = build({ view: () => of({ ...published, nextStep: longText }) });
      const host = fixture.nativeElement as HTMLElement;
      host.style.width = '375px';
      fixture.detectChanges();
      expect(host.scrollWidth).toBeLessThanOrEqual(host.clientWidth + 1);
    });
  });

  // ---- Contact route is workspace-sourced (FR-007 negative) ----

  describe('contact route + no candidate PII', () => {
    it('renders a generic "contact your recruiter" help route, never a candidate email/phone', () => {
      const fixture = build({ view: () => of(terminal) });
      const help = fixture.nativeElement.querySelector('.help') as HTMLElement;
      expect(help).toBeTruthy();
      expect((help.textContent ?? '').toLowerCase()).toContain('recruiter');
      // No candidate email anywhere on the page.
      expect((fixture.nativeElement as HTMLElement).textContent ?? '').not.toMatch(/@/);
    });

    it('the logo route is the workspace-sourced public route, never candidate PII', () => {
      const fixture = build();
      const logo = fixture.nativeElement.querySelector('img.logo') as HTMLImageElement;
      expect(logo).toBeTruthy();
      expect(logo.getAttribute('src')).toContain('/api/public/workspace/logo');
    });
  });

  // ---- Token leakage controls (SC-012) ----

  describe('token leakage controls', () => {
    it('never writes the token to local/session storage', () => {
      const storeSpy = spyOn(Storage.prototype, 'setItem').and.callThrough();
      const fixture = build();
      fixture.componentInstance.requestErasure();
      const wrote = storeSpy.calls.allArgs().some((args) => args.join(' ').includes('tok123'));
      expect(wrote).toBe(false);
    });

    it('never logs the token on an error', () => {
      const errSpy = spyOn(console, 'error').and.callThrough();
      build({ view: () => throwError(() => err(0)) });
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
