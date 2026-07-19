import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { AtsIntegrationComponent } from './ats-integration.component';
import { AtsService, AtsHealth } from './ats.service';
import { attachToBody, axeViolations, detachFromBody } from '../../../../testing/axe';

/**
 * F40/F41 ATS integration admin screen. Verifies the both-providers list, the write-only per-provider connect
 * form, and disconnect. Internal Admin screen — RBAC is enforced server-side (the route roleGuard('ADMIN') is
 * covered by role.guard.spec).
 */
describe('AtsIntegrationComponent', () => {
  const greenhouse: AtsHealth = {
    provider: 'GREENHOUSE', status: 'CONNECTED', credentialSet: true,
    lastVerifiedAt: '2026-06-18T10:00:00Z', lastSyncAt: '2026-06-18T10:05:00Z', degraded: false, deadLetterCount: 0
  };
  const lever: AtsHealth = {
    provider: 'LEVER', status: 'INTEGRATION_PENDING', credentialSet: false,
    lastVerifiedAt: null, lastSyncAt: null, degraded: false, deadLetterCount: 0
  };

  let attachedEls: HTMLElement[] = [];

  function setup(list: AtsHealth[],
                 connectSpy = jasmine.createSpy('connect').and.returnValue(of(lever)),
                 disconnectSpy = jasmine.createSpy('disconnect').and.returnValue(of(void 0))) {
    const stub: Partial<AtsService> = {
      getConnections: () => of(list),
      connect: connectSpy as AtsService['connect'],
      disconnect: disconnectSpy as AtsService['disconnect']
    };
    TestBed.configureTestingModule({
      imports: [AtsIntegrationComponent],
      providers: [{ provide: AtsService, useValue: stub }]
    });
    const fixture = TestBed.createComponent(AtsIntegrationComponent);
    const el = fixture.nativeElement as HTMLElement;
    attachedEls.push(el);
    attachToBody(el);
    fixture.detectChanges();
    return { fixture, connectSpy, disconnectSpy };
  }

  afterEach(() => {
    attachedEls.forEach(detachFromBody);
    attachedEls = [];
  });

  it('lists both providers with their status', () => {
    const { fixture } = setup([greenhouse, lever]);
    const text = fixture.nativeElement.textContent;
    expect(text).toContain('GREENHOUSE');
    expect(text).toContain('LEVER');
    expect(text).toContain('CONNECTED');
    expect(text).toContain('INTEGRATION_PENDING');
    expect(fixture.componentInstance.providers().length).toBe(2);
  });

  it('submits the write-only API key on connect to the right provider', () => {
    const { fixture, connectSpy } = setup([greenhouse, lever]);
    fixture.componentInstance.keys['LEVER'] = 'secret-key';
    fixture.componentInstance.connect('LEVER');
    expect(connectSpy).toHaveBeenCalledWith('LEVER', 'secret-key');
    // After a successful connect the key field is cleared (never retained in the UI).
    expect(fixture.componentInstance.keys['LEVER']).toBe('');
  });

  it('does not connect with a blank key', () => {
    const { fixture, connectSpy } = setup([greenhouse, lever]);
    fixture.componentInstance.keys['LEVER'] = '   ';
    fixture.componentInstance.connect('LEVER');
    expect(connectSpy).not.toHaveBeenCalled();
  });

  it('calls disconnect for the chosen provider', () => {
    const { fixture, disconnectSpy } = setup([greenhouse, lever]);
    fixture.componentInstance.disconnect('GREENHOUSE');
    expect(disconnectSpy).toHaveBeenCalledWith('GREENHOUSE');
  });

  it('shows the connect error when verification fails', () => {
    const failing = jasmine.createSpy('connect').and.returnValue(
      { subscribe: (o: { error: () => void }) => o.error() } as never);
    const { fixture } = setup([greenhouse, lever], failing);
    fixture.componentInstance.keys['LEVER'] = 'bad';
    fixture.componentInstance.connect('LEVER');
    expect(fixture.componentInstance.error()).toBeTruthy();
  });

  it('renders the shared page-header masthead', () => {
    const { fixture } = setup([greenhouse, lever]);
    expect(fixture.nativeElement.querySelector('app-page-header .page__head h1')).not.toBeNull();
  });

  it('has zero axe WCAG 2.2 AA violations', async () => {
    const { fixture } = setup([greenhouse, lever]);
    const violations = await axeViolations(fixture.nativeElement);
    expect(violations).withContext(violations.map((v) => v.id).join(', ')).toEqual([]);
  });
});
