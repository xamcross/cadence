import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { AtsIntegrationComponent } from './ats-integration.component';
import { AtsService, AtsHealth } from './ats.service';
import { ConfirmDialogService } from '../../../shared/ui/confirm-dialog.service';
import { ToastService } from '../../../shared/ui/toast.service';
import { attachToBody, axeViolations, detachFromBody } from '../../../../testing/axe';

/**
 * F40/F41 ATS integration admin screen. Verifies the both-providers list, the write-only per-provider connect
 * form, and disconnect. Internal Admin screen — RBAC is enforced server-side (the route roleGuard('ADMIN') is
 * covered by role.guard.spec).
 *
 * Phase 3b (workbench overhaul): `disconnect(provider)` is gated behind the shared `ConfirmDialogService`
 * (⚠ danger). `connect`/`disconnect` outcomes are surfaced via `ToastService`, replacing the old
 * connect-only inline `error` signal (disconnect previously had no feedback at all).
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
    TestBed.resetTestingModule();
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

  describe('connect (toast)', () => {
    it('submits the write-only API key on connect to the right provider and toasts success', () => {
      const { fixture, connectSpy } = setup([greenhouse, lever]);
      const toastSpy = spyOn(TestBed.inject(ToastService), 'success');
      fixture.componentInstance.keys['LEVER'] = 'secret-key';
      fixture.componentInstance.connect('LEVER');
      expect(connectSpy).toHaveBeenCalledWith('LEVER', 'secret-key');
      // After a successful connect the key field is cleared (never retained in the UI).
      expect(fixture.componentInstance.keys['LEVER']).toBe('');
      expect(toastSpy).toHaveBeenCalled();
    });

    it('does not connect with a blank key', () => {
      const { fixture, connectSpy } = setup([greenhouse, lever]);
      fixture.componentInstance.keys['LEVER'] = '   ';
      fixture.componentInstance.connect('LEVER');
      expect(connectSpy).not.toHaveBeenCalled();
    });

    it('toasts an error when verification fails', () => {
      const failing = jasmine.createSpy('connect').and.returnValue(throwError(() => ({ status: 400 })));
      const { fixture } = setup([greenhouse, lever], failing);
      const toastSpy = spyOn(TestBed.inject(ToastService), 'error');
      fixture.componentInstance.keys['LEVER'] = 'bad';
      fixture.componentInstance.connect('LEVER');
      expect(toastSpy).toHaveBeenCalled();
    });
  });

  describe('disconnect (confirm-gate ⚠ danger + toast)', () => {
    it('does not disconnect when the confirm is declined', async () => {
      const { fixture, disconnectSpy } = setup([greenhouse, lever]);
      spyOn(TestBed.inject(ConfirmDialogService), 'confirm').and.resolveTo(false);
      await fixture.componentInstance.disconnect('GREENHOUSE');
      expect(disconnectSpy).not.toHaveBeenCalled();
    });

    it('gates with a danger confirm, disconnects the chosen provider, and toasts success', async () => {
      const { fixture, disconnectSpy } = setup([greenhouse, lever]);
      const confirmSpy = spyOn(TestBed.inject(ConfirmDialogService), 'confirm').and.resolveTo(true);
      const toastSpy = spyOn(TestBed.inject(ToastService), 'success');
      await fixture.componentInstance.disconnect('GREENHOUSE');
      expect(confirmSpy).toHaveBeenCalledWith(jasmine.objectContaining({ danger: true }));
      expect(disconnectSpy).toHaveBeenCalledWith('GREENHOUSE');
      expect(toastSpy).toHaveBeenCalled();
    });

    it('toasts an error when the confirmed disconnect fails', async () => {
      const failing = jasmine.createSpy('disconnect').and.returnValue(throwError(() => ({ status: 500 })));
      const { fixture } = setup([greenhouse, lever], undefined, failing);
      spyOn(TestBed.inject(ConfirmDialogService), 'confirm').and.resolveTo(true);
      const toastSpy = spyOn(TestBed.inject(ToastService), 'error');
      await fixture.componentInstance.disconnect('GREENHOUSE');
      expect(toastSpy).toHaveBeenCalled();
    });
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
