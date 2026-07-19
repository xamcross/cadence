import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { ErasureQueueComponent } from './erasure-queue.component';
import { ErasureRequestView, GdprService, RequestsView } from './gdpr.service';
import { ConfirmDialogService } from '../../../shared/ui/confirm-dialog.service';
import { ToastService } from '../../../shared/ui/toast.service';
import { attachToBody, axeViolations, detachFromBody } from '../../../../testing/axe';

/**
 * Admin-only pending erasure-request queue (F04 US4).
 *
 * Phase 3b (workbench overhaul): `confirm(id)` (⚠ danger) and `reject(id)` are gated behind
 * `ConfirmDialogService.confirm()` — injected as `dialog` since the component already has a method
 * named `confirm`. Outcomes are surfaced via `ToastService` instead of the old shared `message` signal.
 */
describe('ErasureQueueComponent', () => {
  const pending: ErasureRequestView[] = [
    { id: 'req1', candidateId: 'cand1', status: 'PENDING', reasonCode: null, createdAt: '2026-07-01T10:00:00Z' }
  ];

  let attachedEls: HTMLElement[] = [];

  function setup(overrides: Partial<GdprService> = {}) {
    const stub: Partial<GdprService> = { listRequests: () => of({ requests: pending } as RequestsView), ...overrides };
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [ErasureQueueComponent],
      providers: [{ provide: GdprService, useValue: stub }]
    });
    const fixture = TestBed.createComponent(ErasureQueueComponent);
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

  it('renders the shared page-header masthead', () => {
    const fixture = setup();
    expect(fixture.nativeElement.querySelector('app-page-header .page__head h1')).not.toBeNull();
  });

  it('has zero axe WCAG 2.2 AA violations', async () => {
    const fixture = setup();
    const violations = await axeViolations(fixture.nativeElement);
    expect(violations).withContext(violations.map((v) => v.id).join(', ')).toEqual([]);
  });

  it('shows the guided empty-state when the queue has no pending requests', () => {
    const fixture = setup({ listRequests: () => of({ requests: [] }) });
    expect(fixture.nativeElement.querySelector('app-empty-state')).not.toBeNull();
  });

  it('toasts an error when the initial load fails', () => {
    const toastSpy = spyOn(ToastService.prototype, 'error');
    setup({ listRequests: () => throwError(() => ({ status: 500 })) });
    expect(toastSpy).toHaveBeenCalled();
  });

  describe('confirm (confirm-gate ⚠ danger + toast)', () => {
    it('does not confirm the erasure when the dialog is declined', async () => {
      const confirmReqSpy = jasmine.createSpy('confirmRequest').and.returnValue(of({ status: 'CONFIRMED' }));
      const fixture = setup({ confirmRequest: confirmReqSpy as unknown as GdprService['confirmRequest'] });
      spyOn(TestBed.inject(ConfirmDialogService), 'confirm').and.resolveTo(false);
      await fixture.componentInstance.confirm('req1');
      expect(confirmReqSpy).not.toHaveBeenCalled();
    });

    it('gates with a danger confirm, confirms the erasure, and toasts success', async () => {
      const confirmReqSpy = jasmine.createSpy('confirmRequest').and.returnValue(of({ status: 'CONFIRMED' }));
      const fixture = setup({ confirmRequest: confirmReqSpy as unknown as GdprService['confirmRequest'] });
      const dialogSpy = spyOn(TestBed.inject(ConfirmDialogService), 'confirm').and.resolveTo(true);
      const toastSpy = spyOn(TestBed.inject(ToastService), 'success');
      await fixture.componentInstance.confirm('req1');
      expect(dialogSpy).toHaveBeenCalledWith(jasmine.objectContaining({ danger: true }));
      expect(confirmReqSpy).toHaveBeenCalledWith('req1');
      expect(toastSpy).toHaveBeenCalled();
    });

    it('toasts an error when the confirmed request fails', async () => {
      const confirmReqSpy = jasmine.createSpy('confirmRequest').and.returnValue(throwError(() => ({ status: 500 })));
      const fixture = setup({ confirmRequest: confirmReqSpy as unknown as GdprService['confirmRequest'] });
      spyOn(TestBed.inject(ConfirmDialogService), 'confirm').and.resolveTo(true);
      const toastSpy = spyOn(TestBed.inject(ToastService), 'error');
      await fixture.componentInstance.confirm('req1');
      expect(toastSpy).toHaveBeenCalled();
    });
  });

  describe('reject (confirm-gate + toast)', () => {
    it('does not reject when the dialog is declined', async () => {
      const rejectSpy = jasmine.createSpy('rejectRequest').and.returnValue(of({ status: 'REJECTED' }));
      const fixture = setup({ rejectRequest: rejectSpy as unknown as GdprService['rejectRequest'] });
      spyOn(TestBed.inject(ConfirmDialogService), 'confirm').and.resolveTo(false);
      await fixture.componentInstance.reject('req1');
      expect(rejectSpy).not.toHaveBeenCalled();
    });

    it('rejects with the chosen reason and toasts success when confirmed', async () => {
      const rejectSpy = jasmine.createSpy('rejectRequest').and.returnValue(of({ status: 'REJECTED' }));
      const fixture = setup({ rejectRequest: rejectSpy as unknown as GdprService['rejectRequest'] });
      spyOn(TestBed.inject(ConfirmDialogService), 'confirm').and.resolveTo(true);
      const toastSpy = spyOn(TestBed.inject(ToastService), 'success');
      fixture.componentInstance.setReason('req1', 'NOT_A_CANDIDATE');
      await fixture.componentInstance.reject('req1');
      expect(rejectSpy).toHaveBeenCalledWith('req1', 'NOT_A_CANDIDATE');
      expect(toastSpy).toHaveBeenCalled();
    });

    it('toasts an error when the confirmed rejection fails', async () => {
      const rejectSpy = jasmine.createSpy('rejectRequest').and.returnValue(throwError(() => ({ status: 500 })));
      const fixture = setup({ rejectRequest: rejectSpy as unknown as GdprService['rejectRequest'] });
      spyOn(TestBed.inject(ConfirmDialogService), 'confirm').and.resolveTo(true);
      const toastSpy = spyOn(TestBed.inject(ToastService), 'error');
      await fixture.componentInstance.reject('req1');
      expect(toastSpy).toHaveBeenCalled();
    });
  });
});
