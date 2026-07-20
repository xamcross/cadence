import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import { of, throwError, Subject } from 'rxjs';
import { CalendarConnectionsComponent } from './calendar-connections.component';
import { AvailabilityPreview, CalendarService, ConnectionList, StartResponse } from './calendar.service';
import { ConfirmDialogService } from '../../shared/ui/confirm-dialog.service';
import { ToastService } from '../../shared/ui/toast.service';
import { attachToBody, axeViolations, detachFromBody } from '../../../testing/axe';

/**
 * F01.1: the calendar-connections component renders the three statuses, surfaces the ?error=/?connected= outcome
 * as a toast, and disables Connect while a start request is in flight. The route is reachable by every role
 * (authGuard only — covered by app.routes); the server is the security boundary.
 *
 * Phase 3b (workbench overhaul): the old inline `confirmingDisconnect` 2-step confirm is replaced with the shared
 * `ConfirmDialogService` (⚠ danger) on `disconnect(p)`. The typed `banner` signal is removed — every outcome
 * (mount-time connected/error, connect start failure, disconnect outcome, preview failure) is a `ToastService` call.
 */
describe('CalendarConnectionsComponent', () => {
  let attachedEls: HTMLElement[] = [];

  function setup(
    connections: ConnectionList,
    query: Record<string, string> = {},
    startStub?: Partial<CalendarService>,
    beforeInit?: (fixture: ComponentFixture<CalendarConnectionsComponent>) => void
  ) {
    const noPreview: AvailabilityPreview = { provider: null, status: 'NOT_CONNECTED', windowStart: '', windowEnd: '', busy: [] };
    const service: Partial<CalendarService> = {
      list: () => of(connections),
      disconnect: () => of(void 0),
      previewAvailability: () => of(noPreview),
      ...startStub
    };
    TestBed.resetTestingModule(); // allow re-setup within a single looping test
    TestBed.configureTestingModule({
      imports: [CalendarConnectionsComponent],
      providers: [
        { provide: CalendarService, useValue: service },
        { provide: ActivatedRoute, useValue: { snapshot: { queryParamMap: convertToParamMap(query) } } }
      ]
    });
    const fixture = TestBed.createComponent(CalendarConnectionsComponent);
    const el = fixture.nativeElement as HTMLElement;
    attachedEls.push(el);
    attachToBody(el);
    if (beforeInit) {
      beforeInit(fixture);
    }
    fixture.detectChanges();
    return fixture;
  }

  afterEach(() => {
    attachedEls.forEach(detachFromBody);
    attachedEls = [];
  });

  it('renders the shared page-header masthead', () => {
    const fixture = setup({ connections: [] });
    expect(fixture.nativeElement.querySelector('app-page-header .page__head h1')).not.toBeNull();
  });

  it('has zero axe WCAG 2.2 AA violations', async () => {
    const fixture = setup({ connections: [{ provider: 'GOOGLE', status: 'CONNECTED', connectedAccount: 'alex@example.com', connectedAt: null }] });
    const violations = await axeViolations(fixture.nativeElement);
    expect(violations).withContext(violations.map((v) => v.id).join(', ')).toEqual([]);
  });

  it('renders Not connected when there are no connections', () => {
    const fixture = setup({ connections: [] });
    expect(fixture.nativeElement.textContent).toContain('Not connected');
  });

  it('renders Connected as <account>', () => {
    const fixture = setup({ connections: [{ provider: 'GOOGLE', status: 'CONNECTED', connectedAccount: 'alex@example.com', connectedAt: null }] });
    expect(fixture.nativeElement.textContent).toContain('Connected as');
    expect(fixture.nativeElement.textContent).toContain('alex@example.com');
  });

  it('renders Needs reconnection', () => {
    const fixture = setup({ connections: [{ provider: 'MICROSOFT', status: 'NEEDS_RECONNECTION', connectedAccount: 'a@contoso.com', connectedAt: null }] });
    expect(fixture.nativeElement.textContent).toContain('Needs reconnection');
  });

  it('toasts an error for each ?error= code on mount', () => {
    for (const code of ['invalid_state', 'consent_denied', 'no_offline_grant', 'session_expired', 'exchange_failed']) {
      let toastSpy!: jasmine.Spy;
      setup({ connections: [] }, { error: code }, undefined, () => {
        toastSpy = spyOn(TestBed.inject(ToastService), 'error');
      });
      expect(toastSpy).withContext(code).toHaveBeenCalled();
    }
  });

  it('toasts a success message on ?connected=', () => {
    let toastSpy!: jasmine.Spy;
    setup({ connections: [] }, { connected: 'google' }, undefined, () => {
      toastSpy = spyOn(TestBed.inject(ToastService), 'success');
    });
    expect(toastSpy).toHaveBeenCalled();
  });

  it('does not toast when there is no ?connected=/?error= query param', () => {
    let successSpy!: jasmine.Spy;
    let errorSpy!: jasmine.Spy;
    setup({ connections: [] }, {}, undefined, () => {
      successSpy = spyOn(TestBed.inject(ToastService), 'success');
      errorSpy = spyOn(TestBed.inject(ToastService), 'error');
    });
    expect(successSpy).not.toHaveBeenCalled();
    expect(errorSpy).not.toHaveBeenCalled();
  });

  it('disables Connect while the start request is in flight', () => {
    const pending = new Subject<StartResponse>(); // never emits -> no navigation during the test
    const fixture = setup({ connections: [] }, {}, { start: () => pending.asObservable() });
    fixture.componentInstance.connect(fixture.componentInstance.providers[0]);
    expect(fixture.componentInstance.starting()).toBe('google');
  });

  it('toasts an error when starting the connection fails', () => {
    const fixture = setup({ connections: [] }, {}, { start: () => throwError(() => ({ status: 500 })) });
    const toastSpy = spyOn(TestBed.inject(ToastService), 'error');
    fixture.componentInstance.connect(fixture.componentInstance.providers[0]);
    expect(toastSpy).toHaveBeenCalled();
    expect(fixture.componentInstance.starting()).toBeNull();
  });

  it('renders busy blocks when the preview returns DATA with intervals', () => {
    const pv: AvailabilityPreview = {
      provider: 'GOOGLE', status: 'DATA', windowStart: '2026-06-16T00:00:00Z', windowEnd: '2026-06-23T00:00:00Z',
      busy: [{ start: '2026-06-16T13:00:00Z', end: '2026-06-16T14:00:00Z' }]
    };
    const fixture = setup({ connections: [] }, {}, { previewAvailability: () => of(pv) });
    fixture.componentInstance.previewMine();
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('2026-06-16T13:00:00Z');
  });

  it('renders "free" when the preview returns DATA with no intervals', () => {
    const pv: AvailabilityPreview = {
      provider: 'GOOGLE', status: 'DATA', windowStart: '', windowEnd: '', busy: []
    };
    const fixture = setup({ connections: [] }, {}, { previewAvailability: () => of(pv) });
    fixture.componentInstance.previewMine();
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('free');
  });

  it('renders a reconnection prompt when the preview returns NEEDS_RECONNECTION', () => {
    const pv: AvailabilityPreview = {
      provider: 'GOOGLE', status: 'NEEDS_RECONNECTION', windowStart: '', windowEnd: '', busy: []
    };
    const fixture = setup({ connections: [] }, {}, { previewAvailability: () => of(pv) });
    fixture.componentInstance.previewMine();
    fixture.detectChanges();
    const alert = fixture.nativeElement.querySelector('.preview [role="alert"]');
    expect(alert).not.toBeNull();
  });

  it('toasts an error when the availability preview fails', () => {
    const fixture = setup({ connections: [] }, {}, { previewAvailability: () => throwError(() => ({ status: 500 })) });
    const toastSpy = spyOn(TestBed.inject(ToastService), 'error');
    fixture.componentInstance.previewMine();
    expect(toastSpy).toHaveBeenCalled();
  });

  describe('disconnect (confirm-gate ⚠ danger + toast)', () => {
    it('does not disconnect when the confirm is declined', async () => {
      const disconnect = jasmine.createSpy('disconnect').and.returnValue(of(void 0));
      const fixture = setup(
        { connections: [{ provider: 'GOOGLE', status: 'CONNECTED', connectedAccount: 'a@b.com', connectedAt: null }] },
        {},
        { disconnect }
      );
      spyOn(TestBed.inject(ConfirmDialogService), 'confirm').and.resolveTo(false);
      await fixture.componentInstance.disconnect(fixture.componentInstance.providers[0]);
      expect(disconnect).not.toHaveBeenCalled();
    });

    it('gates with a danger confirm naming the provider, disconnects, and toasts success', async () => {
      const disconnect = jasmine.createSpy('disconnect').and.returnValue(of(void 0));
      const fixture = setup(
        { connections: [{ provider: 'GOOGLE', status: 'CONNECTED', connectedAccount: 'a@b.com', connectedAt: null }] },
        {},
        { disconnect }
      );
      const confirmSpy = spyOn(TestBed.inject(ConfirmDialogService), 'confirm').and.resolveTo(true);
      const toastSpy = spyOn(TestBed.inject(ToastService), 'success');
      const google = fixture.componentInstance.providers[0];
      await fixture.componentInstance.disconnect(google);
      expect(confirmSpy).toHaveBeenCalledWith(jasmine.objectContaining({ danger: true }));
      const body = (confirmSpy.calls.mostRecent().args[0] as { body?: string }).body ?? '';
      expect(body).toContain('Google Calendar');
      expect(disconnect).toHaveBeenCalledWith('google');
      expect(toastSpy).toHaveBeenCalled();
    });

    it('toasts an error when the confirmed disconnect fails', async () => {
      const fixture = setup(
        { connections: [{ provider: 'GOOGLE', status: 'CONNECTED', connectedAccount: 'a@b.com', connectedAt: null }] },
        {},
        { disconnect: () => throwError(() => ({ status: 500 })) }
      );
      spyOn(TestBed.inject(ConfirmDialogService), 'confirm').and.resolveTo(true);
      const toastSpy = spyOn(TestBed.inject(ToastService), 'error');
      await fixture.componentInstance.disconnect(fixture.componentInstance.providers[0]);
      expect(toastSpy).toHaveBeenCalled();
    });
  });

  it('does not render an inline two-step disconnect confirm in the template', () => {
    const fixture = setup({ connections: [{ provider: 'GOOGLE', status: 'CONNECTED', connectedAccount: 'a@b.com', connectedAt: null }] });
    expect(fixture.nativeElement.textContent).not.toContain('Yes, disconnect');
    expect((fixture.componentInstance as unknown as Record<string, unknown>)['confirmingDisconnect']).toBeUndefined();
  });
});
