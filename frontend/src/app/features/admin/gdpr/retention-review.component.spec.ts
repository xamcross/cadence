import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { RetentionReviewComponent } from './retention-review.component';
import { FlaggedList, FlaggedView, GdprService } from './gdpr.service';
import { ConfirmDialogService } from '../../../shared/ui/confirm-dialog.service';
import { ToastService } from '../../../shared/ui/toast.service';
import { attachToBody, axeViolations, detachFromBody } from '../../../../testing/axe';

/**
 * Admin-only review of retention-flagged candidates with confirm-delete (F04 US5).
 *
 * Phase 3b (workbench overhaul): `del(id)` is gated behind `ConfirmDialogService.confirm()` (⚠ danger),
 * replacing the old hand-rolled `confirmingId`-signal inline two-step prompt. Outcomes are surfaced via
 * `ToastService` instead of the old shared `message` signal.
 */
describe('RetentionReviewComponent', () => {
  const flagged: FlaggedView[] = [
    { candidateId: 'cand1', retentionFlaggedAt: '2026-07-01T10:00:00Z', lastContactAt: '2025-01-01T10:00:00Z' }
  ];

  let attachedEls: HTMLElement[] = [];

  function setup(overrides: Partial<GdprService> = {}) {
    const stub: Partial<GdprService> = { listFlagged: () => of({ flagged } as FlaggedList), ...overrides };
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [RetentionReviewComponent],
      providers: [{ provide: GdprService, useValue: stub }]
    });
    const fixture = TestBed.createComponent(RetentionReviewComponent);
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

  it('shows the guided empty-state when there are no flagged records', () => {
    const fixture = setup({ listFlagged: () => of({ flagged: [] }) });
    expect(fixture.nativeElement.querySelector('app-empty-state')).not.toBeNull();
  });

  it('toasts an error when the initial load fails', () => {
    const toastSpy = spyOn(ToastService.prototype, 'error');
    setup({ listFlagged: () => throwError(() => ({ status: 500 })) });
    expect(toastSpy).toHaveBeenCalled();
  });

  it('no longer renders an inline two-step delete confirmation', () => {
    // Phase 3b: the confirmingId-signal inline prompt is replaced by the shared ConfirmDialogService.
    const fixture = setup();
    expect(fixture.nativeElement.textContent).not.toContain('Permanently delete?');
    expect((fixture.componentInstance as unknown as { confirmingId?: unknown }).confirmingId).toBeUndefined();
  });

  describe('del (confirm-gate ⚠ danger + toast)', () => {
    it('does not delete when the confirm is declined', async () => {
      const deleteSpy = jasmine.createSpy('deleteFlagged').and.returnValue(of({ status: 'DELETED' }));
      const fixture = setup({ deleteFlagged: deleteSpy as unknown as GdprService['deleteFlagged'] });
      spyOn(TestBed.inject(ConfirmDialogService), 'confirm').and.resolveTo(false);
      await fixture.componentInstance.del('cand1');
      expect(deleteSpy).not.toHaveBeenCalled();
    });

    it('gates with a danger confirm, deletes, and toasts success when confirmed', async () => {
      const deleteSpy = jasmine.createSpy('deleteFlagged').and.returnValue(of({ status: 'DELETED' }));
      const fixture = setup({ deleteFlagged: deleteSpy as unknown as GdprService['deleteFlagged'] });
      const confirmSpy = spyOn(TestBed.inject(ConfirmDialogService), 'confirm').and.resolveTo(true);
      const toastSpy = spyOn(TestBed.inject(ToastService), 'success');
      await fixture.componentInstance.del('cand1');
      expect(confirmSpy).toHaveBeenCalledWith(jasmine.objectContaining({ danger: true }));
      expect(deleteSpy).toHaveBeenCalledWith('cand1');
      expect(toastSpy).toHaveBeenCalled();
    });

    it('toasts an error when the confirmed deletion fails', async () => {
      const deleteSpy = jasmine.createSpy('deleteFlagged').and.returnValue(throwError(() => ({ status: 500 })));
      const fixture = setup({ deleteFlagged: deleteSpy as unknown as GdprService['deleteFlagged'] });
      spyOn(TestBed.inject(ConfirmDialogService), 'confirm').and.resolveTo(true);
      const toastSpy = spyOn(TestBed.inject(ToastService), 'error');
      await fixture.componentInstance.del('cand1');
      expect(toastSpy).toHaveBeenCalled();
    });
  });
});
