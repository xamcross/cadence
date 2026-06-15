import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import { of, Subject } from 'rxjs';
import { CalendarConnectionsComponent } from './calendar-connections.component';
import { AvailabilityPreview, CalendarService, ConnectionList, StartResponse } from './calendar.service';

/**
 * F01.1: the calendar-connections component renders the three statuses, surfaces the ?error= banner,
 * and disables Connect while a start request is in flight. The route is reachable by every role
 * (authGuard only — covered by app.routes); the server is the security boundary.
 */
describe('CalendarConnectionsComponent', () => {
  function setup(connections: ConnectionList, query: Record<string, string> = {}, startStub?: Partial<CalendarService>) {
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
    fixture.detectChanges();
    return fixture;
  }

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

  it('shows an error banner for each ?error= code', () => {
    for (const code of ['invalid_state', 'consent_denied', 'no_offline_grant', 'session_expired', 'exchange_failed']) {
      const fixture = setup({ connections: [] }, { error: code });
      const alert = fixture.nativeElement.querySelector('[role="alert"]');
      expect(alert).withContext(code).not.toBeNull();
      expect(alert.textContent.trim().length).toBeGreaterThan(0);
    }
  });

  it('shows a success banner on ?connected=', () => {
    const fixture = setup({ connections: [] }, { connected: 'google' });
    const alert = fixture.nativeElement.querySelector('[role="alert"]');
    expect(alert.classList).toContain('success');
  });

  it('disables Connect while the start request is in flight', () => {
    const pending = new Subject<StartResponse>(); // never emits -> no navigation during the test
    const fixture = setup({ connections: [] }, {}, { start: () => pending.asObservable() });
    fixture.componentInstance.connect(fixture.componentInstance.providers[0]);
    expect(fixture.componentInstance.starting()).toBe('google');
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

  it('disconnects only after the confirm step', () => {
    const disconnect = jasmine.createSpy('disconnect').and.returnValue(of(void 0));
    const fixture = setup(
      { connections: [{ provider: 'GOOGLE', status: 'CONNECTED', connectedAccount: 'a@b.com', connectedAt: null }] },
      {},
      { disconnect }
    );
    const google = fixture.componentInstance.providers[0];
    // First click only arms the confirm; it must NOT call the service.
    fixture.componentInstance.confirmingDisconnect.set(google.id);
    expect(disconnect).not.toHaveBeenCalled();
    // Confirming actually disconnects and clears the confirm state.
    fixture.componentInstance.disconnect(google);
    expect(disconnect).toHaveBeenCalledWith('google');
    expect(fixture.componentInstance.confirmingDisconnect()).toBeNull();
  });
});
