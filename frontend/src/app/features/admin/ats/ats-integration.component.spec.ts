import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { AtsIntegrationComponent } from './ats-integration.component';
import { AtsService, AtsHealth } from './ats.service';
import { BillingService, EntitlementView } from '../billing/billing.service';
import { AuthService } from '../../../core/auth/auth.service';
import { ConfirmDialogService } from '../../../shared/ui/confirm-dialog.service';
import { ToastService } from '../../../shared/ui/toast.service';
import { attachToBody, axeViolations, detachFromBody } from '../../../../testing/axe';

const teamEntitlement: EntitlementView = { plan: 'TEAM', status: 'ACTIVE', expiresAt: null, boundAt: null };
const freeEntitlement: EntitlementView = { plan: 'FREE', status: null, expiresAt: null, boundAt: null };

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
    lastVerifiedAt: '2026-06-18T10:00:00Z', lastSyncAt: '2026-06-18T10:05:00Z', degraded: false, deadLetterCount: 0,
    pausedForPlan: false
  };
  const lever: AtsHealth = {
    provider: 'LEVER', status: 'INTEGRATION_PENDING', credentialSet: false,
    lastVerifiedAt: null, lastSyncAt: null, degraded: false, deadLetterCount: 0, pausedForPlan: false
  };

  let attachedEls: HTMLElement[] = [];

  function setup(list: AtsHealth[],
                 connectSpy = jasmine.createSpy('connect').and.returnValue(of(lever)),
                 disconnectSpy = jasmine.createSpy('disconnect').and.returnValue(of(void 0)),
                 billingOverrides: Partial<BillingService> = {}) {
    const stub: Partial<AtsService> = {
      getConnections: () => of(list),
      connect: connectSpy as AtsService['connect'],
      disconnect: disconnectSpy as AtsService['disconnect']
    };
    const billingStub: Partial<BillingService> = { getEntitlement: () => of(teamEntitlement), ...billingOverrides };
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [AtsIntegrationComponent],
      providers: [
        { provide: AtsService, useValue: stub },
        { provide: BillingService, useValue: billingStub },
        { provide: AuthService, useValue: { member$: of(null) } }
      ]
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

  // ---- 032 T9: FREE-plan gate on the connect/disconnect controls ----

  describe('plan gate (032 FR-013/FR-016)', () => {
    it('TEAM plan: shows the normal connect form, no upgrade prompt', () => {
      const { fixture } = setup([greenhouse, lever], undefined, undefined, { getEntitlement: () => of(teamEntitlement) });
      expect(fixture.componentInstance.plan()).toBe('TEAM');
      expect(fixture.nativeElement.querySelector('app-upgrade-prompt')).toBeNull();
      expect(fixture.nativeElement.querySelector('form.connect')).not.toBeNull();
    });

    it('FREE plan: shows the upgrade prompt and hides the connect form, but KEEPS disconnect', () => {
      const { fixture } = setup([greenhouse, lever], undefined, undefined, { getEntitlement: () => of(freeEntitlement) });
      expect(fixture.componentInstance.plan()).toBe('FREE');
      expect(fixture.nativeElement.querySelector('app-upgrade-prompt')).not.toBeNull();
      expect(fixture.nativeElement.querySelector('form.connect')).toBeNull();
      // Disconnect is ungated server-side, so a Free admin must still be able to remove a stored credential
      // (greenhouse has credentialSet: true) — never trap a workspace with a connection it cannot delete.
      expect(fixture.nativeElement.querySelector('button.disconnect')).not.toBeNull();
    });

    it('a failed entitlement load leaves plan() null and shows normal content (never blocks the screen)', () => {
      const { fixture } = setup([greenhouse, lever], undefined, undefined, { getEntitlement: () => throwError(() => ({ status: 500 })) });
      expect(fixture.componentInstance.plan()).toBeNull();
      expect(fixture.nativeElement.querySelector('app-upgrade-prompt')).toBeNull();
      expect(fixture.nativeElement.querySelector('form.connect')).not.toBeNull();
    });

    it('still lists provider status (including a retained connection) while FREE', () => {
      const { fixture } = setup([greenhouse, lever], undefined, undefined, { getEntitlement: () => of(freeEntitlement) });
      const text = fixture.nativeElement.textContent;
      expect(text).toContain('GREENHOUSE');
      expect(text).toContain('CONNECTED');
    });
  });

  describe('pausedForPlan badge (US2-AS2)', () => {
    it('renders the paused badge on a retained-but-paused connection', () => {
      const paused: AtsHealth = { ...greenhouse, pausedForPlan: true };
      const { fixture } = setup([paused, lever]);
      const badge = fixture.nativeElement.querySelector('[data-test=paused-badge]');
      expect(badge).not.toBeNull();
      expect(badge!.textContent).toContain('Paused');
    });

    it('does not render the paused badge when pausedForPlan is false', () => {
      const { fixture } = setup([greenhouse, lever]);
      expect(fixture.nativeElement.querySelector('[data-test=paused-badge]')).toBeNull();
    });
  });
});
