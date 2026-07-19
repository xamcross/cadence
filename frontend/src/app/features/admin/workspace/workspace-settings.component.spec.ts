import { TestBed } from '@angular/core/testing';
import { NEVER, of, throwError } from 'rxjs';
import { WorkspaceSettingsComponent } from './workspace-settings.component';
import { WorkspaceConfig, WorkspaceService } from './workspace.service';
import { ConfirmDialogService } from '../../../shared/ui/confirm-dialog.service';
import { ToastService } from '../../../shared/ui/toast.service';
import { attachToBody, axeViolations, detachFromBody } from '../../../../testing/axe';

/**
 * F03 US6 (SC-012 sibling): the settings component renders for an Admin and loads the config. The
 * per-role guard redirect is covered by role.guard.spec (the same roleGuard('ADMIN') guards both the
 * settings and wizard routes); the server is the security boundary.
 *
 * Phase 3b (workbench overhaul): `removeLogo`, `removeCredential`, and `toggleLock` (lock only, not
 * unlock) are gated behind `ConfirmDialogService.confirm()`, and every action outcome is surfaced via
 * `ToastService` instead of the old shared `message`/`error` signals (which were shared across ~8
 * actions and rendered as generic `.alert` boxes). Field-level logo validation (`logoError`) is
 * unchanged.
 */
describe('WorkspaceSettingsComponent', () => {
  const config: WorkspaceConfig = {
    configured: true, name: 'Acme', timeZone: 'Europe/London',
    workingHours: { start: '09:00', end: '17:00' },
    slaSilenceWindowDays: 5, retentionPeriodDays: 365, retentionAcknowledgedAt: '2026-06-14T00:00:00Z',
    brandColor: '#1F2937', hasLogo: false, emailSendingDomain: null, credentialSet: false,
    templateLocks: { interview_invite: true }
  };

  let attachedEls: HTMLElement[] = [];

  function setup(overrides: Partial<WorkspaceService> = {}) {
    const stub: Partial<WorkspaceService> = { getConfig: () => of(config), ...overrides };
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [WorkspaceSettingsComponent],
      providers: [{ provide: WorkspaceService, useValue: stub }]
    });
    const fixture = TestBed.createComponent(WorkspaceSettingsComponent);
    const el = fixture.nativeElement as HTMLElement;
    attachedEls.push(el);
    attachToBody(el);
    fixture.detectChanges();
    return fixture;
  }

  afterEach(() => {
    attachedEls.forEach(detachFromBody);
    attachedEls = [];
  });

  it('loads and renders the config for an Admin', () => {
    const fixture = setup();
    expect(fixture.componentInstance.config()?.name).toBe('Acme');
    expect(fixture.nativeElement.textContent).toContain('Workspace settings');
  });

  it('lists existing template lock keys', () => {
    const fixture = setup();
    expect(fixture.componentInstance.templateKeys()).toContain('interview_invite');
  });

  it('reflects credential-not-set state', () => {
    const fixture = setup();
    expect(fixture.nativeElement.textContent).toContain('not set');
  });

  it('renders the shared page-header masthead', () => {
    const fixture = setup();
    expect(fixture.nativeElement.querySelector('app-page-header .page__head h1')).not.toBeNull();
  });

  it('has zero axe WCAG 2.2 AA violations', async () => {
    const fixture = setup();
    const violations = await axeViolations(fixture.nativeElement);
    expect(violations).withContext(violations.map((v) => v.id).join(', ')).toEqual([]);
  });

  it('shows a form skeleton while the config has not yet loaded', () => {
    const fixture = setup({ getConfig: () => NEVER });
    expect(fixture.componentInstance.config()).toBeNull();
    expect(fixture.nativeElement.querySelector('app-skeleton')).not.toBeNull();
  });

  it('toasts an error when the initial config load fails', () => {
    // getConfig()'s throwError is synchronous, so ngOnInit's error fires during setup()'s
    // detectChanges() — spy on the prototype (setup() resets the testing module, which would
    // otherwise orphan an instance-bound spy obtained beforehand).
    const toastSpy = spyOn(ToastService.prototype, 'error');
    setup({ getConfig: () => throwError(() => ({ status: 500 })) });
    expect(toastSpy).toHaveBeenCalled();
  });

  // ---- Phase 3b: per-action toasts replacing the shared message/error signals ----

  describe('operational settings (toast)', () => {
    it('saves and toasts success', () => {
      const patched: WorkspaceConfig = { ...config, name: 'New Name' };
      const patchSpy = jasmine.createSpy('patchConfig').and.returnValue(of(patched));
      const fixture = setup({ patchConfig: patchSpy as unknown as WorkspaceService['patchConfig'] });
      const toastSpy = spyOn(TestBed.inject(ToastService), 'success');
      fixture.componentInstance.saveOps();
      expect(patchSpy).toHaveBeenCalled();
      expect(fixture.componentInstance.config()?.name).toBe('New Name');
      expect(toastSpy).toHaveBeenCalled();
      expect(fixture.nativeElement.querySelector('.alert--ok')).toBeNull();
    });

    it('toasts an invalid-value error on a 400', () => {
      const patchSpy = jasmine.createSpy('patchConfig')
        .and.returnValue(throwError(() => ({ status: 400 })));
      const fixture = setup({ patchConfig: patchSpy as unknown as WorkspaceService['patchConfig'] });
      const toastSpy = spyOn(TestBed.inject(ToastService), 'error');
      fixture.componentInstance.saveOps();
      expect(toastSpy).toHaveBeenCalledWith(jasmine.stringContaining('invalid'));
    });

    it('toasts a generic error on a non-400 failure', () => {
      const patchSpy = jasmine.createSpy('patchConfig')
        .and.returnValue(throwError(() => ({ status: 500 })));
      const fixture = setup({ patchConfig: patchSpy as unknown as WorkspaceService['patchConfig'] });
      const toastSpy = spyOn(TestBed.inject(ToastService), 'error');
      fixture.componentInstance.saveOps();
      expect(toastSpy).toHaveBeenCalled();
    });
  });

  describe('branding (toast)', () => {
    it('saves and toasts success', () => {
      const putSpy = jasmine.createSpy('putBranding').and.returnValue(of(config));
      const fixture = setup({ putBranding: putSpy as unknown as WorkspaceService['putBranding'] });
      const toastSpy = spyOn(TestBed.inject(ToastService), 'success');
      fixture.componentInstance.saveBranding();
      expect(putSpy).toHaveBeenCalled();
      expect(toastSpy).toHaveBeenCalled();
    });

    it('toasts an error on failure', () => {
      const putSpy = jasmine.createSpy('putBranding').and.returnValue(throwError(() => ({ status: 500 })));
      const fixture = setup({ putBranding: putSpy as unknown as WorkspaceService['putBranding'] });
      const toastSpy = spyOn(TestBed.inject(ToastService), 'error');
      fixture.componentInstance.saveBranding();
      expect(toastSpy).toHaveBeenCalled();
    });
  });

  describe('logo upload (toast; field-level validation unchanged)', () => {
    function fileEvent(file: File): Event {
      const input = document.createElement('input');
      input.type = 'file';
      Object.defineProperty(input, 'files', { value: [file] });
      return { target: input } as unknown as Event;
    }

    it('uploads and toasts success', () => {
      const uploadSpy = jasmine.createSpy('uploadLogo').and.returnValue(of({ hasLogo: true }));
      const fixture = setup({ uploadLogo: uploadSpy as unknown as WorkspaceService['uploadLogo'] });
      const toastSpy = spyOn(TestBed.inject(ToastService), 'success');
      const file = new File(['x'], 'logo.png', { type: 'image/png' });
      fixture.componentInstance.onLogo(fileEvent(file));
      expect(uploadSpy).toHaveBeenCalled();
      expect(toastSpy).toHaveBeenCalled();
    });

    it('keeps the field-level error for an oversized file (no toast)', () => {
      const fixture = setup();
      const toastErrorSpy = spyOn(TestBed.inject(ToastService), 'error');
      const big = new File([new Uint8Array(1024 * 1024 + 1)], 'logo.png', { type: 'image/png' });
      fixture.componentInstance.onLogo(fileEvent(big));
      expect(fixture.componentInstance.logoError()).toContain('1 MB');
      expect(toastErrorSpy).not.toHaveBeenCalled();
    });
  });

  describe('removeLogo (confirm-gate + toast)', () => {
    it('does not remove the logo when the confirm is declined', async () => {
      const deleteSpy = jasmine.createSpy('deleteLogo').and.returnValue(of(undefined));
      const fixture = setup({ deleteLogo: deleteSpy as unknown as WorkspaceService['deleteLogo'] });
      spyOn(TestBed.inject(ConfirmDialogService), 'confirm').and.resolveTo(false);
      await fixture.componentInstance.removeLogo();
      expect(deleteSpy).not.toHaveBeenCalled();
    });

    it('removes the logo and toasts success when confirmed', async () => {
      const deleteSpy = jasmine.createSpy('deleteLogo').and.returnValue(of(undefined));
      const fixture = setup({
        deleteLogo: deleteSpy as unknown as WorkspaceService['deleteLogo'],
        getConfig: () => of(config)
      });
      spyOn(TestBed.inject(ConfirmDialogService), 'confirm').and.resolveTo(true);
      const toastSpy = spyOn(TestBed.inject(ToastService), 'success');
      await fixture.componentInstance.removeLogo();
      expect(deleteSpy).toHaveBeenCalled();
      expect(toastSpy).toHaveBeenCalled();
    });

    it('toasts an error when the confirmed removal fails', async () => {
      const deleteSpy = jasmine.createSpy('deleteLogo').and.returnValue(throwError(() => ({ status: 500 })));
      const fixture = setup({ deleteLogo: deleteSpy as unknown as WorkspaceService['deleteLogo'] });
      spyOn(TestBed.inject(ConfirmDialogService), 'confirm').and.resolveTo(true);
      const toastSpy = spyOn(TestBed.inject(ToastService), 'error');
      await fixture.componentInstance.removeLogo();
      expect(toastSpy).toHaveBeenCalled();
    });
  });

  describe('email settings (toast)', () => {
    it('saves, resets the credential field, and toasts success', () => {
      const putSpy = jasmine.createSpy('putEmail').and.returnValue(of(config));
      const fixture = setup({ putEmail: putSpy as unknown as WorkspaceService['putEmail'] });
      const toastSpy = spyOn(TestBed.inject(ToastService), 'success');
      fixture.componentInstance.email.controls.credential.setValue('sekret');
      fixture.componentInstance.saveEmail();
      expect(putSpy).toHaveBeenCalled();
      expect(fixture.componentInstance.email.controls.credential.value).toBe('');
      expect(toastSpy).toHaveBeenCalled();
    });

    it('toasts an error on failure', () => {
      const putSpy = jasmine.createSpy('putEmail').and.returnValue(throwError(() => ({ status: 500 })));
      const fixture = setup({ putEmail: putSpy as unknown as WorkspaceService['putEmail'] });
      const toastSpy = spyOn(TestBed.inject(ToastService), 'error');
      fixture.componentInstance.saveEmail();
      expect(toastSpy).toHaveBeenCalled();
    });
  });

  describe('removeCredential (confirm-gate ⚠ danger + toast)', () => {
    it('does not remove the credential when the confirm is declined', async () => {
      const deleteSpy = jasmine.createSpy('deleteCredential').and.returnValue(of(undefined));
      const fixture = setup({ deleteCredential: deleteSpy as unknown as WorkspaceService['deleteCredential'] });
      spyOn(TestBed.inject(ConfirmDialogService), 'confirm').and.resolveTo(false);
      await fixture.componentInstance.removeCredential();
      expect(deleteSpy).not.toHaveBeenCalled();
    });

    it('gates with a danger confirm, removes, and toasts success when confirmed', async () => {
      const deleteSpy = jasmine.createSpy('deleteCredential').and.returnValue(of(undefined));
      const fixture = setup({ deleteCredential: deleteSpy as unknown as WorkspaceService['deleteCredential'] });
      const confirmSpy = spyOn(TestBed.inject(ConfirmDialogService), 'confirm').and.resolveTo(true);
      const toastSpy = spyOn(TestBed.inject(ToastService), 'success');
      await fixture.componentInstance.removeCredential();
      expect(confirmSpy).toHaveBeenCalledWith(jasmine.objectContaining({ danger: true }));
      expect(deleteSpy).toHaveBeenCalled();
      expect(toastSpy).toHaveBeenCalled();
    });

    it('toasts an error when the confirmed removal fails', async () => {
      const deleteSpy = jasmine.createSpy('deleteCredential').and.returnValue(throwError(() => ({ status: 500 })));
      const fixture = setup({ deleteCredential: deleteSpy as unknown as WorkspaceService['deleteCredential'] });
      spyOn(TestBed.inject(ConfirmDialogService), 'confirm').and.resolveTo(true);
      const toastSpy = spyOn(TestBed.inject(ToastService), 'error');
      await fixture.componentInstance.removeCredential();
      expect(toastSpy).toHaveBeenCalled();
    });
  });

  describe('toggleLock (confirm-gate ONLY when locking + toast)', () => {
    it('locking: does not lock when the confirm is declined', async () => {
      const lockSpy = jasmine.createSpy('putTemplateLock').and.returnValue(of(config));
      const fixture = setup({ putTemplateLock: lockSpy as unknown as WorkspaceService['putTemplateLock'] });
      spyOn(TestBed.inject(ConfirmDialogService), 'confirm').and.resolveTo(false);
      await fixture.componentInstance.toggleLock('phone_screen'); // not yet locked -> locking path
      expect(lockSpy).not.toHaveBeenCalled();
    });

    it('locking: gates, locks, and toasts "locked" when confirmed', async () => {
      const locked: WorkspaceConfig = { ...config, templateLocks: { ...config.templateLocks, phone_screen: true } };
      const lockSpy = jasmine.createSpy('putTemplateLock').and.returnValue(of(locked));
      const fixture = setup({ putTemplateLock: lockSpy as unknown as WorkspaceService['putTemplateLock'] });
      const confirmSpy = spyOn(TestBed.inject(ConfirmDialogService), 'confirm').and.resolveTo(true);
      const toastSpy = spyOn(TestBed.inject(ToastService), 'success');
      await fixture.componentInstance.toggleLock('phone_screen');
      expect(confirmSpy).toHaveBeenCalled();
      expect(lockSpy).toHaveBeenCalledWith('phone_screen', true);
      expect(toastSpy).toHaveBeenCalledWith(jasmine.stringContaining('locked'));
    });

    it('unlocking: does NOT gate — calls the service directly and toasts "unlocked"', async () => {
      const unlocked: WorkspaceConfig = { ...config, templateLocks: { ...config.templateLocks, interview_invite: false } };
      const lockSpy = jasmine.createSpy('putTemplateLock').and.returnValue(of(unlocked));
      const fixture = setup({ putTemplateLock: lockSpy as unknown as WorkspaceService['putTemplateLock'] });
      const confirmSpy = spyOn(TestBed.inject(ConfirmDialogService), 'confirm');
      const toastSpy = spyOn(TestBed.inject(ToastService), 'success');
      await fixture.componentInstance.toggleLock('interview_invite'); // already locked -> unlocking path
      expect(confirmSpy).not.toHaveBeenCalled();
      expect(lockSpy).toHaveBeenCalledWith('interview_invite', false);
      expect(toastSpy).toHaveBeenCalledWith(jasmine.stringContaining('unlocked'));
    });

    it('toasts an error when the (confirmed) lock update fails', async () => {
      const lockSpy = jasmine.createSpy('putTemplateLock').and.returnValue(throwError(() => ({ status: 500 })));
      const fixture = setup({ putTemplateLock: lockSpy as unknown as WorkspaceService['putTemplateLock'] });
      spyOn(TestBed.inject(ConfirmDialogService), 'confirm').and.resolveTo(true);
      const toastSpy = spyOn(TestBed.inject(ToastService), 'error');
      await fixture.componentInstance.toggleLock('phone_screen');
      expect(toastSpy).toHaveBeenCalled();
    });
  });

  describe('addLock (toast; not gated)', () => {
    it('adds a template lock and toasts success', () => {
      const added: WorkspaceConfig = { ...config, templateLocks: { ...config.templateLocks, offer: true } };
      const lockSpy = jasmine.createSpy('putTemplateLock').and.returnValue(of(added));
      const fixture = setup({ putTemplateLock: lockSpy as unknown as WorkspaceService['putTemplateLock'] });
      const toastSpy = spyOn(TestBed.inject(ToastService), 'success');
      fixture.componentInstance.lockForm.controls.key.setValue('offer');
      fixture.componentInstance.addLock();
      expect(lockSpy).toHaveBeenCalledWith('offer', true);
      expect(fixture.componentInstance.lockForm.controls.key.value).toBe('');
      expect(toastSpy).toHaveBeenCalled();
    });

    it('toasts an error on failure', () => {
      const lockSpy = jasmine.createSpy('putTemplateLock').and.returnValue(throwError(() => ({ status: 500 })));
      const fixture = setup({ putTemplateLock: lockSpy as unknown as WorkspaceService['putTemplateLock'] });
      const toastSpy = spyOn(TestBed.inject(ToastService), 'error');
      fixture.componentInstance.lockForm.controls.key.setValue('offer');
      fixture.componentInstance.addLock();
      expect(toastSpy).toHaveBeenCalled();
    });
  });
});
