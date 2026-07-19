import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { CandidateErasureActionComponent } from './candidate-erasure-action.component';
import { GdprService } from './gdpr.service';
import { ConfirmDialogService } from '../../../shared/ui/confirm-dialog.service';
import { ToastService } from '../../../shared/ui/toast.service';
import { attachToBody, axeViolations, detachFromBody } from '../../../../testing/axe';

/**
 * Admin/Recruiter candidate erasure-trigger + lawful-basis screen (F04 US3).
 *
 * Phase 3b (workbench overhaul): `erase` is gated behind `ConfirmDialogService.confirm()` (⚠ danger),
 * replacing the old hand-rolled `confirming`-signal inline two-step prompt. `withdrawBasis` gets a
 * light (non-danger) confirm gate. `recordBasis`/`withdrawBasis`/`erase` outcomes are surfaced via
 * `ToastService` instead of the old shared `message` signal.
 */
describe('CandidateErasureActionComponent', () => {
  let attachedEls: HTMLElement[] = [];

  function setup(overrides: Partial<GdprService> = {}) {
    const stub: Partial<GdprService> = { ...overrides };
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [CandidateErasureActionComponent],
      providers: [{ provide: GdprService, useValue: stub }]
    });
    const fixture = TestBed.createComponent(CandidateErasureActionComponent);
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

  it('no longer renders an inline two-step erase confirmation', () => {
    // Phase 3b: the confirming-signal inline prompt is replaced by the shared ConfirmDialogService.
    const fixture = setup();
    expect(fixture.nativeElement.textContent).not.toContain('Confirm erasure');
    expect((fixture.componentInstance as unknown as { confirming?: unknown }).confirming).toBeUndefined();
  });

  describe('recordBasis (toast; not gated)', () => {
    it('records the basis and toasts success', () => {
      const recordSpy = jasmine.createSpy('recordBasis').and.returnValue(of({ basisRecorded: true }));
      const fixture = setup({ recordBasis: recordSpy as unknown as GdprService['recordBasis'] });
      const toastSpy = spyOn(TestBed.inject(ToastService), 'success');
      fixture.componentInstance.candidateId = 'cand1';
      fixture.componentInstance.recordBasis();
      expect(recordSpy).toHaveBeenCalledWith('cand1', 'LEGITIMATE_INTEREST');
      expect(toastSpy).toHaveBeenCalled();
    });

    it('toasts an error on failure', () => {
      const recordSpy = jasmine.createSpy('recordBasis').and.returnValue(throwError(() => ({ status: 500 })));
      const fixture = setup({ recordBasis: recordSpy as unknown as GdprService['recordBasis'] });
      const toastSpy = spyOn(TestBed.inject(ToastService), 'error');
      fixture.componentInstance.candidateId = 'cand1';
      fixture.componentInstance.recordBasis();
      expect(toastSpy).toHaveBeenCalled();
    });
  });

  describe('withdrawBasis (confirm-gate + toast)', () => {
    it('does not withdraw when the confirm is declined', async () => {
      const withdrawSpy = jasmine.createSpy('withdrawBasis').and.returnValue(of({ basisWithdrawn: true }));
      const fixture = setup({ withdrawBasis: withdrawSpy as unknown as GdprService['withdrawBasis'] });
      spyOn(TestBed.inject(ConfirmDialogService), 'confirm').and.resolveTo(false);
      fixture.componentInstance.candidateId = 'cand1';
      await fixture.componentInstance.withdrawBasis();
      expect(withdrawSpy).not.toHaveBeenCalled();
    });

    it('withdraws and toasts success when confirmed', async () => {
      const withdrawSpy = jasmine.createSpy('withdrawBasis').and.returnValue(of({ basisWithdrawn: true }));
      const fixture = setup({ withdrawBasis: withdrawSpy as unknown as GdprService['withdrawBasis'] });
      spyOn(TestBed.inject(ConfirmDialogService), 'confirm').and.resolveTo(true);
      const toastSpy = spyOn(TestBed.inject(ToastService), 'success');
      fixture.componentInstance.candidateId = 'cand1';
      await fixture.componentInstance.withdrawBasis();
      expect(withdrawSpy).toHaveBeenCalledWith('cand1');
      expect(toastSpy).toHaveBeenCalled();
    });

    it('toasts an error when the confirmed withdrawal fails', async () => {
      const withdrawSpy = jasmine.createSpy('withdrawBasis').and.returnValue(throwError(() => ({ status: 500 })));
      const fixture = setup({ withdrawBasis: withdrawSpy as unknown as GdprService['withdrawBasis'] });
      spyOn(TestBed.inject(ConfirmDialogService), 'confirm').and.resolveTo(true);
      const toastSpy = spyOn(TestBed.inject(ToastService), 'error');
      fixture.componentInstance.candidateId = 'cand1';
      await fixture.componentInstance.withdrawBasis();
      expect(toastSpy).toHaveBeenCalled();
    });
  });

  describe('erase (confirm-gate ⚠ danger + toast)', () => {
    it('does not erase when the confirm is declined', async () => {
      const eraseSpy = jasmine.createSpy('erase').and.returnValue(of({ status: 'ERASED' }));
      const fixture = setup({ erase: eraseSpy as unknown as GdprService['erase'] });
      spyOn(TestBed.inject(ConfirmDialogService), 'confirm').and.resolveTo(false);
      fixture.componentInstance.candidateId = 'cand1';
      await fixture.componentInstance.erase();
      expect(eraseSpy).not.toHaveBeenCalled();
    });

    it('gates with a danger confirm, erases, and toasts success when confirmed', async () => {
      const eraseSpy = jasmine.createSpy('erase').and.returnValue(of({ status: 'ERASED' }));
      const fixture = setup({ erase: eraseSpy as unknown as GdprService['erase'] });
      const confirmSpy = spyOn(TestBed.inject(ConfirmDialogService), 'confirm').and.resolveTo(true);
      const toastSpy = spyOn(TestBed.inject(ToastService), 'success');
      fixture.componentInstance.candidateId = 'cand1';
      await fixture.componentInstance.erase();
      expect(confirmSpy).toHaveBeenCalledWith(jasmine.objectContaining({ danger: true }));
      expect(eraseSpy).toHaveBeenCalledWith('cand1');
      expect(toastSpy).toHaveBeenCalled();
    });

    it('toasts an error when the confirmed erasure fails', async () => {
      const eraseSpy = jasmine.createSpy('erase').and.returnValue(throwError(() => ({ status: 500 })));
      const fixture = setup({ erase: eraseSpy as unknown as GdprService['erase'] });
      spyOn(TestBed.inject(ConfirmDialogService), 'confirm').and.resolveTo(true);
      const toastSpy = spyOn(TestBed.inject(ToastService), 'error');
      fixture.componentInstance.candidateId = 'cand1';
      await fixture.componentInstance.erase();
      expect(toastSpy).toHaveBeenCalled();
    });
  });
});
