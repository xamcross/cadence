import { ComponentFixture, TestBed, fakeAsync, flushMicrotasks } from '@angular/core/testing';
import { ActivatedRoute } from '@angular/router';
import { Observable, of } from 'rxjs';
import { ScorecardPageComponent } from './scorecard-page.component';
import { FeedbackService, ScorecardFormView, SubmitResponse } from './feedback.service';
import { attachToBody, axeViolations, detachFromBody } from '../../../testing/axe';
import { CandidateBrandingService } from '../../core/branding/candidate-branding.service';

/**
 * F32 interviewer scorecard page (§IX). Verifies: WCAG 2.2 AA (axe 0 violations) on the form and terminal
 * states, submit button >=44px (SC target-size — not in the axe WCAG tag set), the happy-path submit, the
 * expired/invalid envelopes, and that the token is never written to web storage.
 */
describe('ScorecardPageComponent (F32)', () => {
  const form: ScorecardFormView = {
    state: 'FORM',
    interviewLabel: 'Interview on 2026-06-15',
    recommendationOptions: ['STRONG_YES', 'YES', 'NO', 'STRONG_NO'],
    ratingDimensions: ['Technical skills', 'Communication']
  };

  let activeEl: HTMLElement | null = null;
  let loadSpy: jasmine.Spy;
  let submitSpy: jasmine.Spy;

  function build(opts: { load?: () => Observable<ScorecardFormView>;
                         submit?: () => Observable<SubmitResponse>;
                         token?: string } = {}): ComponentFixture<ScorecardPageComponent> {
    loadSpy = jasmine.createSpy('load').and.callFake(opts.load ?? (() => of(form)));
    submitSpy = jasmine.createSpy('submit').and.callFake(opts.submit ?? (() => of({ state: 'SUBMITTED' } as SubmitResponse)));
    const svc: Partial<FeedbackService> = {
      load: loadSpy as unknown as FeedbackService['load'],
      submit: submitSpy as unknown as FeedbackService['submit']
    };
    const route = { snapshot: { queryParamMap: { get: (_: string) => opts.token ?? 'tok123' } } };
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [ScorecardPageComponent],
      providers: [
        { provide: FeedbackService, useValue: svc },
        { provide: ActivatedRoute, useValue: route },
        { provide: CandidateBrandingService, useValue: { applyAccent: () => {}, setAccent: () => {} } }
      ]
    });
    const fixture = TestBed.createComponent(ScorecardPageComponent);
    const el = fixture.nativeElement as HTMLElement;
    activeEl = el;
    attachToBody(el);
    fixture.detectChanges();
    return fixture;
  }

  afterEach(() => { if (activeEl) { detachFromBody(activeEl); } });

  it('renders the blank form with no prior content', () => {
    const fixture = build();
    const el: HTMLElement = fixture.nativeElement;
    expect(el.querySelector('.recommendation')).toBeTruthy();
    expect(el.querySelectorAll('input[name="recommendation"]').length).toBe(4);
    // write-only: nothing is pre-selected / pre-filled (no prior submission is ever shown)
    const checked = el.querySelectorAll('input[name="recommendation"]:checked');
    expect(checked.length).toBe(0);
    const comment = el.querySelector('textarea[name="comment"]') as HTMLTextAreaElement;
    expect(comment.value).toBe('');
  });

  it('has zero axe WCAG 2.2 AA violations on the form', async () => {
    const fixture = build();
    expect(await axeViolations(fixture.nativeElement)).toEqual([]);
  });

  it('has zero axe violations on the submitted state', async () => {
    const fixture = build();
    fixture.componentInstance.recommendation = 'YES';
    fixture.componentInstance.submit();
    fixture.detectChanges();
    expect(fixture.componentInstance.state()).toBe('submitted');
    expect(await axeViolations(fixture.nativeElement)).toEqual([]);
  });

  it('submit button meets the 44px target size', () => {
    const fixture = build();
    const btn = fixture.nativeElement.querySelector('.action.submit') as HTMLElement;
    const rect = btn.getBoundingClientRect();
    expect(rect.height).toBeGreaterThanOrEqual(44);
  });

  it('renders the expired envelope distinctly from invalid', () => {
    const fixture = build({ load: () => of({ state: 'EXPIRED' } as ScorecardFormView) });
    expect(fixture.componentInstance.state()).toBe('expired');
  });

  it('renders the used/unknown token as invalid (no oracle)', () => {
    const fixture = build({ load: () => of({ state: 'USED' } as ScorecardFormView) });
    expect(fixture.componentInstance.state()).toBe('invalid');
  });

  it('never writes the token to web storage', () => {
    const setLocal = spyOn(Storage.prototype, 'setItem').and.callThrough();
    build({ token: 'secret-token' });
    expect(setLocal).not.toHaveBeenCalled();
    expect(window.localStorage.getItem('token')).toBeNull();
  });

  it('moves focus to the state heading after a transition', fakeAsync(() => {
    const fixture = build();
    fixture.componentInstance.recommendation = 'YES';
    fixture.componentInstance.submit();
    fixture.detectChanges();
    flushMicrotasks();
    expect(document.activeElement).toBe(fixture.nativeElement.querySelector('.state-heading'));
  }));

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
