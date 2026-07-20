import { TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { HttpErrorResponse } from '@angular/common/http';
import { of, throwError } from 'rxjs';
import { EmailTemplatesComponent } from './email-templates.component';
import { EmailTemplate, EmailTemplatesService, RenderedMessage, SendResult, TemplateList } from './email-templates.service';
import { ConfirmDialogService } from '../../shared/ui/confirm-dialog.service';
import { ToastService } from '../../shared/ui/toast.service';
import { SearchPickerComponent } from '../../shared/ui/search-picker.component';
import { PipelinePage, PipelineService } from '../pipeline/pipeline.service';
import { attachToBody, axeViolations, detachFromBody } from '../../../testing/axe';

/**
 * F21 SC-011: the email-templates component renders the missing-field warning, disables editing of a
 * locked template for a non-Admin, and renders a preview with sample data. The server is the security
 * boundary; the route guard + the disabled control are defense-in-depth.
 *
 * Phase 3b (workbench overhaul): `reset` and `send` are gated behind `ConfirmDialogService.confirm()`
 * (⚠ danger); `setLock` is gated only when locking (not unlocking). `save`/`applyTone`/`reset`/
 * `setLock`/`send` outcomes are surfaced via `ToastService`; the old dedicated `sendStatus`/`sendError`
 * signals (and their markup) are removed in favour of toasts.
 */
describe('EmailTemplatesComponent', () => {
  const base: EmailTemplate = {
    messageType: 'INVITATION', stageKey: 'BASE', subject: 'Hi {{candidate_name}}',
    body: 'Hello {{candidate_name}}', locked: false, version: 0, source: 'OVERRIDE',
    permittedTokens: ['candidate_name', 'workspace_name']
  };
  let attachedEls: HTMLElement[] = [];

  // Workbench overhaul phase 5: ngOnInit unconditionally loads the candidate picker options from the
  // pipeline list service, so the DI stub is required for every render.
  const emptyPipelinePage: PipelinePage = { rows: [], page: 0, size: 1000, totalInScope: 0, filteredCount: 0, truncated: false };

  function setup(list: TemplateList, overrides: Partial<EmailTemplatesService> = {}, pipelineOverrides: Partial<PipelineService> = {}) {
    const service: Partial<EmailTemplatesService> = {
      list: () => of(list),
      edit: () => of(base),
      applyTone: () => of(base),
      reset: () => of(base),
      lock: () => of({ ...base, locked: true }),
      unlock: () => of(base),
      preview: () => of({ subject: 'Hi Dana Lee', bodyText: 'Hello Dana Lee', bodyHtml: 'Hello Dana Lee', missingFields: [] }),
      sendToCandidate: () => of({ dispatchId: 'd1', status: 'SENT', messageType: 'INVITATION' } as SendResult),
      ...overrides
    };
    const pipelineStub: Partial<PipelineService> = { list: () => of(emptyPipelinePage), ...pipelineOverrides };
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [EmailTemplatesComponent],
      providers: [
        { provide: EmailTemplatesService, useValue: service },
        { provide: PipelineService, useValue: pipelineStub }
      ]
    });
    const fixture = TestBed.createComponent(EmailTemplatesComponent);
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

  it('lists the message types', () => {
    const fixture = setup({ templates: [base] });
    expect(fixture.nativeElement.textContent).toContain('INVITATION');
  });

  it('renders the shared page-header masthead', () => {
    const fixture = setup({ templates: [base] });
    expect(fixture.nativeElement.querySelector('app-page-header .page__head h1')).not.toBeNull();
  });

  it('shows the guided empty-state when there are no templates', () => {
    const fixture = setup({ templates: [] });
    expect(fixture.nativeElement.querySelector('app-empty-state')).not.toBeNull();
  });

  it('has zero axe WCAG 2.2 AA violations', async () => {
    const fixture = setup({ templates: [base] });
    const violations = await axeViolations(fixture.nativeElement);
    expect(violations).withContext(violations.map((v) => v.id).join(', ')).toEqual([]);
  });

  it('renders a preview with sample data', () => {
    const fixture = setup({ templates: [base] });
    fixture.componentInstance.preview(base);
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Hi Dana Lee');
    expect(fixture.nativeElement.textContent).toContain('Hello Dana Lee');
  });

  it('shows a visible warning when a merge field is missing', () => {
    const rendered: RenderedMessage = {
      subject: 'Hi [[missing:candidate_name]]', bodyText: 'Hello [[missing:candidate_name]]',
      bodyHtml: 'Hello [[missing:candidate_name]]', missingFields: ['candidate_name']
    };
    const fixture = setup({ templates: [base] }, { preview: () => of(rendered) });
    fixture.componentInstance.preview(base);
    fixture.detectChanges();
    const alert = fixture.nativeElement.querySelector('[role="alert"]');
    expect(alert).not.toBeNull();
    expect(alert.textContent).toContain('candidate_name');
  });

  it('disables the Edit control on a locked template for a non-Admin', () => {
    const locked: EmailTemplate = { ...base, locked: true };
    const fixture = setup({ templates: [locked] });
    fixture.componentInstance.isAdmin = false;
    fixture.detectChanges();
    const buttons: HTMLButtonElement[] = Array.from(fixture.nativeElement.querySelectorAll('button'));
    const editBtn = buttons.find((b) => /edit/i.test(b.textContent ?? ''));
    expect(editBtn).toBeTruthy();
    expect(editBtn!.disabled).toBeTrue();
    expect(fixture.componentInstance.canEdit(locked)).toBeFalse();
  });

  // ---- Phase 3b: per-action toasts (save / applyTone) ----

  describe('save (toast)', () => {
    it('saves and toasts success', () => {
      const fixture = setup({ templates: [base] });
      fixture.componentInstance.edit(base);
      const toastSpy = spyOn(TestBed.inject(ToastService), 'success');
      fixture.componentInstance.save();
      expect(toastSpy).toHaveBeenCalled();
    });

    it('toasts an error on failure', () => {
      const fixture = setup({ templates: [base] }, { edit: () => throwError(() => ({ status: 500 })) });
      fixture.componentInstance.edit(base);
      const toastSpy = spyOn(TestBed.inject(ToastService), 'error');
      fixture.componentInstance.save();
      expect(toastSpy).toHaveBeenCalled();
    });
  });

  describe('applyTone (toast)', () => {
    it('applies the tone and toasts success', () => {
      const fixture = setup({ templates: [base] });
      const toastSpy = spyOn(TestBed.inject(ToastService), 'success');
      fixture.componentInstance.applyTone(base, 'FORMAL');
      expect(toastSpy).toHaveBeenCalled();
    });

    it('toasts an error on failure', () => {
      const fixture = setup({ templates: [base] }, { applyTone: () => throwError(() => ({ status: 500 })) });
      const toastSpy = spyOn(TestBed.inject(ToastService), 'error');
      fixture.componentInstance.applyTone(base, 'FORMAL');
      expect(toastSpy).toHaveBeenCalled();
    });
  });

  // ---- Phase 3b: reset (confirm-gate ⚠ danger + toast) ----

  describe('reset (confirm-gate ⚠ danger + toast)', () => {
    it('does not reset when the confirm is declined', async () => {
      const resetSpy = jasmine.createSpy('reset').and.returnValue(of(base));
      const fixture = setup({ templates: [base] }, { reset: resetSpy as unknown as EmailTemplatesService['reset'] });
      spyOn(TestBed.inject(ConfirmDialogService), 'confirm').and.resolveTo(false);
      await fixture.componentInstance.reset(base);
      expect(resetSpy).not.toHaveBeenCalled();
    });

    it('gates with a danger confirm, resets, and toasts success when confirmed', async () => {
      const resetSpy = jasmine.createSpy('reset').and.returnValue(of(base));
      const fixture = setup({ templates: [base] }, { reset: resetSpy as unknown as EmailTemplatesService['reset'] });
      const confirmSpy = spyOn(TestBed.inject(ConfirmDialogService), 'confirm').and.resolveTo(true);
      const toastSpy = spyOn(TestBed.inject(ToastService), 'success');
      await fixture.componentInstance.reset(base);
      expect(confirmSpy).toHaveBeenCalledWith(jasmine.objectContaining({ danger: true }));
      expect(resetSpy).toHaveBeenCalled();
      expect(toastSpy).toHaveBeenCalled();
    });

    it('toasts an error when the confirmed reset fails', async () => {
      const resetSpy = jasmine.createSpy('reset').and.returnValue(throwError(() => ({ status: 500 })));
      const fixture = setup({ templates: [base] }, { reset: resetSpy as unknown as EmailTemplatesService['reset'] });
      spyOn(TestBed.inject(ConfirmDialogService), 'confirm').and.resolveTo(true);
      const toastSpy = spyOn(TestBed.inject(ToastService), 'error');
      await fixture.componentInstance.reset(base);
      expect(toastSpy).toHaveBeenCalled();
    });
  });

  // ---- Phase 3b: setLock (confirm-gate ONLY when locking + toast) ----

  describe('setLock (confirm-gate ONLY when locking + toast)', () => {
    it('locking: does not lock when the confirm is declined', async () => {
      const lockSpy = jasmine.createSpy('lock').and.returnValue(of({ ...base, locked: true }));
      const fixture = setup({ templates: [base] }, { lock: lockSpy as unknown as EmailTemplatesService['lock'] });
      spyOn(TestBed.inject(ConfirmDialogService), 'confirm').and.resolveTo(false);
      await fixture.componentInstance.setLock(base, true);
      expect(lockSpy).not.toHaveBeenCalled();
    });

    it('locking: gates, locks, and toasts success when confirmed', async () => {
      const lockSpy = jasmine.createSpy('lock').and.returnValue(of({ ...base, locked: true }));
      const fixture = setup({ templates: [base] }, { lock: lockSpy as unknown as EmailTemplatesService['lock'] });
      const confirmSpy = spyOn(TestBed.inject(ConfirmDialogService), 'confirm').and.resolveTo(true);
      const toastSpy = spyOn(TestBed.inject(ToastService), 'success');
      await fixture.componentInstance.setLock(base, true);
      expect(confirmSpy).toHaveBeenCalled();
      expect(lockSpy).toHaveBeenCalled();
      expect(toastSpy).toHaveBeenCalled();
    });

    it('unlocking: does NOT gate — calls the service directly and toasts success', async () => {
      const unlockSpy = jasmine.createSpy('unlock').and.returnValue(of(base));
      const fixture = setup({ templates: [base] }, { unlock: unlockSpy as unknown as EmailTemplatesService['unlock'] });
      const confirmSpy = spyOn(TestBed.inject(ConfirmDialogService), 'confirm');
      const toastSpy = spyOn(TestBed.inject(ToastService), 'success');
      await fixture.componentInstance.setLock(base, false);
      expect(confirmSpy).not.toHaveBeenCalled();
      expect(unlockSpy).toHaveBeenCalled();
      expect(toastSpy).toHaveBeenCalled();
    });

    it('toasts an error when the (confirmed) lock update fails', async () => {
      const lockSpy = jasmine.createSpy('lock').and.returnValue(throwError(() => ({ status: 500 })));
      const fixture = setup({ templates: [base] }, { lock: lockSpy as unknown as EmailTemplatesService['lock'] });
      spyOn(TestBed.inject(ConfirmDialogService), 'confirm').and.resolveTo(true);
      const toastSpy = spyOn(TestBed.inject(ToastService), 'error');
      await fixture.componentInstance.setLock(base, true);
      expect(toastSpy).toHaveBeenCalled();
    });
  });

  // ---- Phase 3b: send (confirm-gate ⚠ danger + toast; replaces sendStatus/sendError) ----

  describe('send (confirm-gate ⚠ danger + toast)', () => {
    it('does not send when the confirm is declined', async () => {
      const sendSpy = jasmine.createSpy('sendToCandidate').and.returnValue(of({ dispatchId: 'd1', status: 'SENT', messageType: 'INVITATION' } as SendResult));
      const fixture = setup({ templates: [base] }, { sendToCandidate: sendSpy as unknown as EmailTemplatesService['sendToCandidate'] });
      fixture.componentInstance.preview(base);
      fixture.detectChanges();
      fixture.componentInstance.sendCandidateId = 'cand1';
      spyOn(TestBed.inject(ConfirmDialogService), 'confirm').and.resolveTo(false);
      await fixture.componentInstance.send(base);
      expect(sendSpy).not.toHaveBeenCalled();
    });

    it('gates with a danger confirm, sends, and toasts success when confirmed', async () => {
      const sendSpy = jasmine.createSpy('sendToCandidate').and.returnValue(of({ dispatchId: 'd1', status: 'SENT', messageType: 'INVITATION' } as SendResult));
      const fixture = setup({ templates: [base] }, { sendToCandidate: sendSpy as unknown as EmailTemplatesService['sendToCandidate'] });
      fixture.componentInstance.preview(base);
      fixture.detectChanges();
      fixture.componentInstance.sendCandidateId = 'cand1';
      const confirmSpy = spyOn(TestBed.inject(ConfirmDialogService), 'confirm').and.resolveTo(true);
      const toastSpy = spyOn(TestBed.inject(ToastService), 'success');
      await fixture.componentInstance.send(base);
      expect(confirmSpy).toHaveBeenCalledWith(jasmine.objectContaining({ danger: true }));
      expect(sendSpy).toHaveBeenCalledWith('cand1', jasmine.any(Object));
      expect(toastSpy).toHaveBeenCalledWith(jasmine.stringContaining('SENT'));
    });

    it('toasts the not-contactable reason on a confirmed 409', async () => {
      const err = new HttpErrorResponse({ status: 409, error: { error: 'not_contactable', reason: 'WITHDRAWN' } });
      const fixture = setup({ templates: [base] }, { sendToCandidate: () => throwError(() => err) });
      fixture.componentInstance.preview(base);
      fixture.detectChanges();
      fixture.componentInstance.sendCandidateId = 'cand1';
      spyOn(TestBed.inject(ConfirmDialogService), 'confirm').and.resolveTo(true);
      const toastSpy = spyOn(TestBed.inject(ToastService), 'error');
      await fixture.componentInstance.send(base);
      expect(toastSpy).toHaveBeenCalledWith(jasmine.stringContaining('WITHDRAWN'));
    });

    it('does not send when the candidate id is blank (no confirm prompt)', async () => {
      const fixture = setup({ templates: [base] });
      fixture.componentInstance.preview(base);
      fixture.detectChanges();
      fixture.componentInstance.sendCandidateId = '   ';
      const confirmSpy = spyOn(TestBed.inject(ConfirmDialogService), 'confirm');
      await fixture.componentInstance.send(base);
      expect(confirmSpy).not.toHaveBeenCalled();
    });

    // Fix 6: the confirm body must never interpolate the raw candidate id; it shows the loaded
    // display label when available, otherwise a generic phrase.
    it('confirm body shows the candidate display label, never the raw id', async () => {
      const page: PipelinePage = {
        rows: [{
          candidateId: '7f3e-uuid-9c1a', name: 'Dana Okafor', stage: 'Technical', slaState: 'GREEN',
          schedulingStatus: 'NO_LINK_SENT', requisitionId: null, requisitionTitle: null, lastActivityAt: null
        }],
        page: 0, size: 1000, totalInScope: 1, filteredCount: 1, truncated: false
      };
      const fixture = setup({ templates: [base] }, {}, { list: () => of(page) });
      fixture.componentInstance.preview(base);
      fixture.detectChanges();
      fixture.componentInstance.sendCandidateId = '7f3e-uuid-9c1a';
      const confirmSpy = spyOn(TestBed.inject(ConfirmDialogService), 'confirm').and.resolveTo(false);
      await fixture.componentInstance.send(base);
      const body = (confirmSpy.calls.mostRecent().args[0] as { body?: string }).body ?? '';
      expect(body).toContain('Dana Okafor');
      expect(body).not.toContain('7f3e-uuid-9c1a');
    });

    it('confirm body falls back to a generic phrase (no id) when the candidate is not in the options', async () => {
      const fixture = setup({ templates: [base] });
      fixture.componentInstance.preview(base);
      fixture.detectChanges();
      fixture.componentInstance.sendCandidateId = 'unknown-uuid-xyz';
      const confirmSpy = spyOn(TestBed.inject(ConfirmDialogService), 'confirm').and.resolveTo(false);
      await fixture.componentInstance.send(base);
      const body = (confirmSpy.calls.mostRecent().args[0] as { body?: string }).body ?? '';
      expect(body).not.toContain('unknown-uuid-xyz');
      expect(body.toLowerCase()).toContain('selected candidate');
    });
  });

  // ---- Workbench overhaul phase 5: candidate picker replaces the raw "sendCandidateId" input ----

  describe('candidate picker (workbench overhaul phase 5)', () => {
    const pipelinePage: PipelinePage = {
      rows: [{
        candidateId: 'cand1', name: 'Dana Okafor', stage: 'Technical', slaState: 'GREEN',
        schedulingStatus: 'NO_LINK_SENT', requisitionId: null, requisitionTitle: null, lastActivityAt: null
      }],
      page: 0, size: 1000, totalInScope: 1, filteredCount: 1, truncated: false
    };

    it('loads candidate options from the pipeline list service and renders a picker in the send panel', () => {
      const fixture = setup({ templates: [base] }, {}, { list: () => of(pipelinePage) });
      fixture.componentInstance.preview(base);
      fixture.detectChanges();
      expect(fixture.componentInstance.candidateOpts()).toEqual([{ id: 'cand1', label: 'Dana Okafor', hint: 'Technical' }]);
      expect(fixture.nativeElement.querySelector('app-search-picker')).not.toBeNull();
    });

    it('selecting a candidate option sets sendCandidateId', () => {
      const fixture = setup({ templates: [base] }, {}, { list: () => of(pipelinePage) });
      fixture.componentInstance.preview(base);
      fixture.detectChanges();
      const picker = fixture.debugElement.query(By.directive(SearchPickerComponent));
      expect(picker).not.toBeNull();
      (picker.componentInstance as SearchPickerComponent).valueChange.emit('cand1');
      expect(fixture.componentInstance.sendCandidateId).toBe('cand1');
    });
  });
});
