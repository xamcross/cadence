import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { AtsIntegrationComponent } from './ats-integration.component';
import { AtsService, AtsHealth, AtsSyncStatus, AtsDeadLetter } from './ats.service';

/**
 * F40 ATS integration admin screen. Verifies status rendering, the write-only connect form, and disconnect.
 * Internal Admin screen — RBAC is enforced server-side (the route roleGuard('ADMIN') is covered by role.guard.spec).
 */
describe('AtsIntegrationComponent', () => {
  const disconnected: AtsHealth = {
    provider: 'GREENHOUSE', status: 'INTEGRATION_PENDING', credentialSet: false,
    lastVerifiedAt: null, lastSyncAt: null, degraded: false, deadLetterCount: 0
  };
  const connected: AtsHealth = {
    provider: 'GREENHOUSE', status: 'CONNECTED', credentialSet: true,
    lastVerifiedAt: '2026-06-18T10:00:00Z', lastSyncAt: '2026-06-18T10:05:00Z', degraded: false, deadLetterCount: 0
  };
  const sync: AtsSyncStatus = {
    lastSyncAt: '2026-06-18T10:05:00Z', lastOutcome: 'SUCCESS', processed: 3, created: 2, updated: 1, skipped: 0
  };

  function setup(health: AtsHealth, connectSpy = jasmine.createSpy('connect').and.returnValue(of(connected)),
                 disconnectSpy = jasmine.createSpy('disconnect').and.returnValue(of(void 0))) {
    const stub: Partial<AtsService> = {
      getHealth: () => of(health),
      syncStatus: () => of(sync),
      deadLetters: () => of([] as AtsDeadLetter[]),
      connect: connectSpy as AtsService['connect'],
      disconnect: disconnectSpy as AtsService['disconnect']
    };
    TestBed.configureTestingModule({
      imports: [AtsIntegrationComponent],
      providers: [{ provide: AtsService, useValue: stub }]
    });
    const fixture = TestBed.createComponent(AtsIntegrationComponent);
    fixture.detectChanges();
    return { fixture, connectSpy, disconnectSpy };
  }

  it('renders the connection status', () => {
    const { fixture } = setup(connected);
    expect(fixture.nativeElement.textContent).toContain('CONNECTED');
    expect(fixture.componentInstance.health()?.credentialSet).toBeTrue();
  });

  it('submits the write-only API key on connect', () => {
    const { fixture, connectSpy } = setup(disconnected);
    fixture.componentInstance.apiKey = 'secret-key';
    fixture.componentInstance.connect();
    expect(connectSpy).toHaveBeenCalledWith('secret-key');
    // After a successful connect the key field is cleared (never retained in the UI).
    expect(fixture.componentInstance.apiKey).toBe('');
  });

  it('does not connect with a blank key', () => {
    const { fixture, connectSpy } = setup(disconnected);
    fixture.componentInstance.apiKey = '   ';
    fixture.componentInstance.connect();
    expect(connectSpy).not.toHaveBeenCalled();
  });

  it('calls disconnect when connected', () => {
    const { fixture, disconnectSpy } = setup(connected);
    fixture.componentInstance.disconnect();
    expect(disconnectSpy).toHaveBeenCalled();
  });

  it('shows the connect error when verification fails', () => {
    const failing = jasmine.createSpy('connect').and.returnValue(
      { subscribe: (o: { error: () => void }) => o.error() } as never);
    const { fixture } = setup(disconnected, failing);
    fixture.componentInstance.apiKey = 'bad';
    fixture.componentInstance.connect();
    expect(fixture.componentInstance.error()).toBeTruthy();
  });
});
