import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { of, throwError } from 'rxjs';
import { SchedulingComponent } from './scheduling.component';
import {
  InitiateResponse,
  RecruiterStatusResponse,
  RotateLinkResponse,
  SchedulingService,
  StatusResponse
} from './scheduling.service';
import { ActionResult, CandidateSla, DraftPreview, SlaNudgeService } from './sla-nudge.service';
import { ConfirmDialogService } from '../../shared/ui/confirm-dialog.service';
import { ToastService } from '../../shared/ui/toast.service';
import { SearchPickerComponent } from '../../shared/ui/search-picker.component';
import { PipelinePage, PipelineService } from '../pipeline/pipeline.service';
import { InterviewTemplatesService, TemplateList } from '../interview-templates/interview-templates.service';
import { attachToBody, axeViolations, detachFromBody } from '../../../testing/axe';

/**
 * F13 US1 (§II): the recruiter surface sends a link (happy path), surfaces a 409 not-contactable, and
 * a 422 no-slots, and maps the per-candidate status to a label. The role guard redirect is covered by
 * role.guard.spec; the server is the security boundary.
 *
 * Phase 3b (workbench overhaul): the destructive/consequential actions (`cancel`, `release`,
 * `rotateStatusLink`, `dismissDraft`) are gated behind `ConfirmDialogService.confirm()`, and every
 * action outcome is surfaced via `ToastService` instead of the old shared `manageMsg`/`statusMsg`/
 * `slaMsg`/`error` signals (which mis-styled error text inside a success `.alert--ok` box).
 */
describe('SchedulingComponent', () => {
  const initiated: InitiateResponse = {
    schedulingRequestId: 'r1', status: 'PENDING_SELECTION', offeredSlotCount: 5,
    sentAt: '2026-06-16T10:00:00Z', expiresAt: '2026-06-19T10:00:00Z'
  };
  const sentStatus: StatusResponse = {
    status: 'PENDING_SELECTION', sentAt: '2026-06-16T10:00:00Z', expiresAt: '2026-06-19T10:00:00Z', chosenStart: null
  };

  // Workbench overhaul phase 5: ngOnInit unconditionally loads picker options from the pipeline +
  // interview-template list services, so every helper below must provide DI stubs for them.
  const emptyPipelinePage: PipelinePage = { rows: [], page: 0, size: 1000, totalInScope: 0, filteredCount: 0, truncated: false };
  const emptyTemplateList: TemplateList = { templates: [] };

  function setup(initiate: SchedulingService['initiate'], overrides: Partial<SchedulingService> = {},
                 slaOverrides: Partial<SlaNudgeService> = {},
                 pipelineOverrides: Partial<PipelineService> = {}, templatesOverrides: Partial<InterviewTemplatesService> = {}) {
    const stub: Partial<SchedulingService> = { initiate, status: () => of(sentStatus), ...overrides };
    const slaStub: Partial<SlaNudgeService> = {
      getSla: () => of({ candidateId: 'cand1', slaState: 'GREEN', lastActivityAt: null, openDraftId: null }),
      previewDraft: () => of({ messageType: 'SLA_HOLDING', subject: 's', body: 'b', missingFields: [] }),
      approve: () => of({ draftId: 'd1', result: 'SENT_ENQUEUED' }),
      dismiss: () => of({ draftId: 'd1', result: 'DISMISSED' }),
      ...slaOverrides
    };
    const pipelineStub: Partial<PipelineService> = { list: () => of(emptyPipelinePage), ...pipelineOverrides };
    const templatesStub: Partial<InterviewTemplatesService> = { list: () => of(emptyTemplateList), ...templatesOverrides };
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [SchedulingComponent],
      providers: [
        { provide: SchedulingService, useValue: stub },
        { provide: SlaNudgeService, useValue: slaStub },
        { provide: PipelineService, useValue: pipelineStub },
        { provide: InterviewTemplatesService, useValue: templatesStub }
      ]
    });
    const fixture = TestBed.createComponent(SchedulingComponent);
    return fixture.componentInstance;
  }

  /** Renders the component (setup() above only ever returns the bare instance) for masthead/axe assertions. */
  function setupFixture(pipelineOverrides: Partial<PipelineService> = {},
                        templatesOverrides: Partial<InterviewTemplatesService> = {}): ComponentFixture<SchedulingComponent> {
    const stub: Partial<SchedulingService> = { initiate: () => of(initiated), status: () => of(sentStatus) };
    const slaStub: Partial<SlaNudgeService> = {
      getSla: () => of({ candidateId: 'cand1', slaState: 'GREEN', lastActivityAt: null, openDraftId: null }),
      previewDraft: () => of({ messageType: 'SLA_HOLDING', subject: 's', body: 'b', missingFields: [] }),
      approve: () => of({ draftId: 'd1', result: 'SENT_ENQUEUED' }),
      dismiss: () => of({ draftId: 'd1', result: 'DISMISSED' })
    };
    const pipelineStub: Partial<PipelineService> = { list: () => of(emptyPipelinePage), ...pipelineOverrides };
    const templatesStub: Partial<InterviewTemplatesService> = { list: () => of(emptyTemplateList), ...templatesOverrides };
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [SchedulingComponent],
      providers: [
        { provide: SchedulingService, useValue: stub },
        { provide: SlaNudgeService, useValue: slaStub },
        { provide: PipelineService, useValue: pipelineStub },
        { provide: InterviewTemplatesService, useValue: templatesStub }
      ]
    });
    return TestBed.createComponent(SchedulingComponent);
  }

  it('renders the shared page-header masthead', () => {
    const fixture = setupFixture();
    const el = fixture.nativeElement as HTMLElement;
    attachToBody(el);
    fixture.detectChanges();
    expect(el.querySelector('app-page-header .page__head h1')).not.toBeNull();
    detachFromBody(el);
  });

  it('has zero axe WCAG 2.2 AA violations', async () => {
    const fixture = setupFixture();
    const el = fixture.nativeElement as HTMLElement;
    attachToBody(el);
    fixture.detectChanges();
    const violations = await axeViolations(el);
    expect(violations).withContext(violations.map((v) => v.id).join(', ')).toEqual([]);
    detachFromBody(el);
  });

  it('sends a link, shows the offered-slot count, and toasts success', () => {
    const c = setup(() => of(initiated));
    const toastSpy = spyOn(TestBed.inject(ToastService), 'success');
    c.candidateId = 'cand1';
    c.templateId = 'tmpl1';
    c.send();
    expect(c.result()?.offeredSlotCount).toBe(5);
    expect(c.statusView()?.status).toBe('PENDING_SELECTION');
    expect(toastSpy).toHaveBeenCalled();
  });

  it('surfaces a not-contactable refusal as an error toast', () => {
    const c = setup(() => throwError(() => ({ status: 409, error: { error: 'not_contactable' } })));
    const toastSpy = spyOn(TestBed.inject(ToastService), 'error');
    c.candidateId = 'cand1';
    c.templateId = 'tmpl1';
    c.send();
    expect(c.result()).toBeNull();
    expect(toastSpy).toHaveBeenCalledWith(jasmine.stringContaining('contacted'));
  });

  it('surfaces a no-slots refusal as an error toast', () => {
    const c = setup(() => throwError(() => ({ status: 422, error: { error: 'no_slots' } })));
    const toastSpy = spyOn(TestBed.inject(ToastService), 'error');
    c.candidateId = 'cand1';
    c.templateId = 'tmpl1';
    c.send();
    expect(toastSpy).toHaveBeenCalledWith(jasmine.stringContaining('No available slots'));
  });

  it('maps scheduling status to a label', () => {
    const c = setup(() => of(initiated));
    expect(c.statusLabel({ ...sentStatus, status: 'BOOKED' })).toBe('Scheduled');
    expect(c.statusLabel({ ...sentStatus, status: 'EXPIRED' })).toBe('Link expired');
  });

  // ---- Phase 3b: confirm-gate + toast on cancel/release/rotateStatusLink/dismissDraft ----

  describe('confirm-gated actions (Phase 3b)', () => {
    it('cancel: declined confirm does not cancel the interview', async () => {
      const cancelSpy = jasmine.createSpy('cancel').and.returnValue(of({ status: 'CANCELLED', at: '2026-07-19T10:00:00Z' }));
      const c = setup(() => of(initiated), { cancel: cancelSpy as unknown as SchedulingService['cancel'] });
      spyOn(TestBed.inject(ConfirmDialogService), 'confirm').and.resolveTo(false);
      c.candidateId = 'cand1';
      await c.cancel();
      expect(cancelSpy).not.toHaveBeenCalled();
    });

    it('cancel: confirmed cancels the interview and toasts success', async () => {
      const cancelSpy = jasmine.createSpy('cancel').and.returnValue(of({ status: 'CANCELLED', at: '2026-07-19T10:00:00Z' }));
      const c = setup(() => of(initiated), { cancel: cancelSpy as unknown as SchedulingService['cancel'] });
      spyOn(TestBed.inject(ConfirmDialogService), 'confirm').and.resolveTo(true);
      const toastSpy = spyOn(TestBed.inject(ToastService), 'success');
      c.candidateId = 'cand1';
      await c.cancel();
      expect(cancelSpy).toHaveBeenCalledWith('cand1');
      expect(toastSpy).toHaveBeenCalled();
    });

    it('cancel: confirmed but the server refuses, toasts error', async () => {
      const cancelSpy = jasmine.createSpy('cancel')
        .and.returnValue(throwError(() => ({ status: 409, error: { error: 'no_active_booking' } })));
      const c = setup(() => of(initiated), { cancel: cancelSpy as unknown as SchedulingService['cancel'] });
      spyOn(TestBed.inject(ConfirmDialogService), 'confirm').and.resolveTo(true);
      const toastSpy = spyOn(TestBed.inject(ToastService), 'error');
      c.candidateId = 'cand1';
      await c.cancel();
      expect(toastSpy).toHaveBeenCalledWith(jasmine.stringContaining('no booked interview'));
    });

    it('release: declined confirm does not release the slot', async () => {
      const releaseSpy = jasmine.createSpy('release')
        .and.returnValue(of({ status: 'RELEASED', at: '2026-07-19T10:00:00Z', cleanupIncomplete: false }));
      const c = setup(() => of(initiated), { release: releaseSpy as unknown as SchedulingService['release'] });
      spyOn(TestBed.inject(ConfirmDialogService), 'confirm').and.resolveTo(false);
      c.candidateId = 'cand1';
      await c.release();
      expect(releaseSpy).not.toHaveBeenCalled();
    });

    it('release: confirmed releases the slot and toasts success', async () => {
      const releaseSpy = jasmine.createSpy('release')
        .and.returnValue(of({ status: 'RELEASED', at: '2026-07-19T10:00:00Z', cleanupIncomplete: false }));
      const c = setup(() => of(initiated), { release: releaseSpy as unknown as SchedulingService['release'] });
      spyOn(TestBed.inject(ConfirmDialogService), 'confirm').and.resolveTo(true);
      const toastSpy = spyOn(TestBed.inject(ToastService), 'success');
      c.candidateId = 'cand1';
      await c.release();
      expect(releaseSpy).toHaveBeenCalledWith('cand1');
      expect(toastSpy).toHaveBeenCalled();
    });

    it('rotateStatusLink: declined confirm does not rotate the link', async () => {
      const rotateSpy = jasmine.createSpy('rotateStatusLink').and.returnValue(of({ statusLink: 'https://app.example/status?token=NEW' }));
      const c = setup(() => of(initiated), { rotateStatusLink: rotateSpy as unknown as SchedulingService['rotateStatusLink'] });
      spyOn(TestBed.inject(ConfirmDialogService), 'confirm').and.resolveTo(false);
      c.candidateId = 'cand1';
      c.statusLink.set('https://app.example/status?token=OLD');
      await c.rotateStatusLink();
      expect(rotateSpy).not.toHaveBeenCalled();
      expect(c.statusLink()).toBe('https://app.example/status?token=OLD');
    });

    it('rotateStatusLink: confirmed rotates the link and toasts success', async () => {
      const rotated: RotateLinkResponse = { statusLink: 'https://app.example/status?token=NEW' };
      const rotateSpy = jasmine.createSpy('rotateStatusLink').and.returnValue(of(rotated));
      const c = setup(() => of(initiated), { rotateStatusLink: rotateSpy as unknown as SchedulingService['rotateStatusLink'] });
      spyOn(TestBed.inject(ConfirmDialogService), 'confirm').and.resolveTo(true);
      const toastSpy = spyOn(TestBed.inject(ToastService), 'success');
      c.candidateId = 'cand1';
      c.statusLink.set('https://app.example/status?token=OLD');
      await c.rotateStatusLink();
      expect(rotateSpy).toHaveBeenCalledWith('cand1');
      expect(c.statusLink()).toBe('https://app.example/status?token=NEW');
      expect(toastSpy).toHaveBeenCalled();
    });

    it('dismissDraft: declined confirm does not dismiss the draft', async () => {
      const dismissSpy = jasmine.createSpy('dismiss').and.returnValue(of({ draftId: 'd1', result: 'DISMISSED' }));
      const c = setup(() => of(initiated), {}, { dismiss: dismissSpy as unknown as SlaNudgeService['dismiss'] });
      spyOn(TestBed.inject(ConfirmDialogService), 'confirm').and.resolveTo(false);
      c.candidateId = 'cand1';
      await c.dismissDraft('d1');
      expect(dismissSpy).not.toHaveBeenCalled();
    });

    it('dismissDraft: confirmed dismisses the draft and toasts success', async () => {
      const dismissSpy = jasmine.createSpy('dismiss').and.returnValue(of({ draftId: 'd1', result: 'DISMISSED' }));
      const c = setup(() => of(initiated), {}, { dismiss: dismissSpy as unknown as SlaNudgeService['dismiss'] });
      spyOn(TestBed.inject(ConfirmDialogService), 'confirm').and.resolveTo(true);
      const toastSpy = spyOn(TestBed.inject(ToastService), 'success');
      c.candidateId = 'cand1';
      await c.dismissDraft('d1');
      expect(dismissSpy).toHaveBeenCalledWith('d1');
      expect(toastSpy).toHaveBeenCalled();
    });
  });

  // ---- F30 candidate Status panel (T032) ----

  describe('F30 candidate status panel', () => {
    const recruiterStatus: RecruiterStatusResponse = {
      displayState: 'PUBLISHED', outcome: 'IN_PROGRESS', stage: 'Onsite', nextStep: 'Collecting feedback',
      expectedDate: '2030-01-20', statusLink: 'https://app.example/status?token=abc', publishedAt: null
    };

    it('client validation mirrors the server rules (in-progress requires stage + next + date)', () => {
      const c = setup(() => of(initiated));
      c.candidateId = 'cand1';
      c.statusOutcome = 'IN_PROGRESS';
      c.statusStage = '';
      c.statusNextStep = '';
      c.statusExpectedDate = '';
      expect(c.statusValid()).toBe(false);
      c.statusStage = 'Onsite';
      c.statusNextStep = 'Collecting feedback';
      expect(c.statusValid()).toBe(false); // still missing the date
      c.statusExpectedDate = '2030-01-20';
      expect(c.statusValid()).toBe(true);
    });

    it('a terminal outcome requires only a non-blank next-step message', () => {
      const c = setup(() => of(initiated));
      c.statusOutcome = 'COMPLETE_REJECTED';
      c.statusStage = '';
      c.statusExpectedDate = '';
      c.statusNextStep = '   ';
      expect(c.statusValid()).toBe(false);
      c.statusNextStep = 'Thank you for your time.';
      expect(c.statusValid()).toBe(true);
    });

    it('does NOT publish when invalid (guards against the dateless holding message)', () => {
      const publishSpy = jasmine.createSpy('publishStatus');
      const c = setup(() => of(initiated), {
        publishStatus: publishSpy as unknown as SchedulingService['publishStatus']
      });
      c.candidateId = 'cand1';
      c.statusOutcome = 'IN_PROGRESS';
      c.statusNextStep = 'x'; // no stage, no date
      c.publishStatus();
      expect(publishSpy).not.toHaveBeenCalled();
      expect(c.statusTouched()).toBe(true);
    });

    it('publishes a valid status, surfaces the returned link, and toasts success', () => {
      const publishSpy = jasmine.createSpy('publishStatus').and.returnValue(of(recruiterStatus));
      const c = setup(() => of(initiated), {
        publishStatus: publishSpy as unknown as SchedulingService['publishStatus']
      });
      const toastSpy = spyOn(TestBed.inject(ToastService), 'success');
      c.candidateId = 'cand1';
      c.statusOutcome = 'IN_PROGRESS';
      c.statusStage = 'Onsite';
      c.statusNextStep = 'Collecting feedback';
      c.statusExpectedDate = '2030-01-20';
      c.publishStatus();
      expect(publishSpy).toHaveBeenCalledTimes(1);
      expect(c.statusLink()).toBe('https://app.example/status?token=abc');
      expect(toastSpy).toHaveBeenCalled();
    });

    it('surfaces a 400 invalid_status from the server as an error toast', () => {
      const publishSpy = jasmine.createSpy('publishStatus')
        .and.returnValue(throwError(() => ({ status: 400, error: { error: 'invalid_status' } })));
      const c = setup(() => of(initiated), {
        publishStatus: publishSpy as unknown as SchedulingService['publishStatus']
      });
      const toastSpy = spyOn(TestBed.inject(ToastService), 'error');
      c.candidateId = 'cand1';
      c.statusOutcome = 'COMPLETE_OFFER';
      c.statusNextStep = 'Congratulations';
      c.publishStatus();
      expect(toastSpy).toHaveBeenCalledWith(jasmine.stringContaining('incomplete'));
    });

    it('copy-link writes the current link to the clipboard and toasts success', async () => {
      const writeText = jasmine.createSpy('writeText').and.returnValue(Promise.resolve());
      const original = navigator.clipboard;
      Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true });
      try {
        const c = setup(() => of(initiated));
        const toastSpy = spyOn(TestBed.inject(ToastService), 'success');
        c.statusLink.set('https://app.example/status?token=abc');
        c.copyStatusLink();
        await Promise.resolve(); // flush the clipboard promise microtask
        expect(writeText).toHaveBeenCalledWith('https://app.example/status?token=abc');
        expect(toastSpy).toHaveBeenCalled();
      } finally {
        Object.defineProperty(navigator, 'clipboard', { value: original, configurable: true });
      }
    });

    it('loadStatus reads the persisted status + link', () => {
      const readSpy = jasmine.createSpy('readStatus').and.returnValue(of(recruiterStatus));
      const c = setup(() => of(initiated), {
        readStatus: readSpy as unknown as SchedulingService['readStatus']
      });
      c.candidateId = 'cand1';
      c.loadStatus();
      expect(readSpy).toHaveBeenCalledWith('cand1');
      expect(c.statusLink()).toBe('https://app.example/status?token=abc');
      expect(c.statusStage).toBe('Onsite');
    });
  });

  // ---- F31 SLA nudge panel (T040) ----

  describe('F31 SLA nudge panel', () => {
    const redWithDraft: CandidateSla = {
      candidateId: 'cand1', slaState: 'RED', lastActivityAt: '2026-06-01T10:00:00Z', openDraftId: 'd1'
    };

    it('loadSla renders the candidate green/amber/red state', () => {
      const c = setup(() => of(initiated), {}, { getSla: () => of(redWithDraft) });
      c.candidateId = 'cand1';
      c.loadSla();
      expect(c.sla()?.slaState).toBe('RED');
      expect(c.sla()?.openDraftId).toBe('d1');
      expect(c.slaLabel('RED')).toContain('silence');
    });

    it('approve sends the holding message, clears the preview, and toasts success', () => {
      const approveSpy = jasmine.createSpy('approve').and.returnValue(of({ draftId: 'd1', result: 'SENT_ENQUEUED' }));
      const c = setup(() => of(initiated), {}, { getSla: () => of(redWithDraft), approve: approveSpy as unknown as SlaNudgeService['approve'] });
      const toastSpy = spyOn(TestBed.inject(ToastService), 'success');
      c.candidateId = 'cand1';
      c.approveDraft('d1');
      expect(approveSpy).toHaveBeenCalledWith('d1');
      expect(toastSpy).toHaveBeenCalled();
      expect(c.draftPreview()).toBeNull();
    });

    it('preview surfaces a missing-field warning', () => {
      const preview: DraftPreview = { messageType: 'SLA_HOLDING', subject: 's', body: 'b', missingFields: ['expected_date'] };
      const c = setup(() => of(initiated), {}, { getSla: () => of(redWithDraft), previewDraft: () => of(preview) });
      c.candidateId = 'cand1';
      c.previewDraft();
      expect(c.draftPreview()?.missingFields).toContain('expected_date');
    });
  });

  // ---- Workbench overhaul phase 5: candidate + template pickers replace the raw-id inputs ----

  describe('candidate + template pickers (workbench overhaul phase 5)', () => {
    const pipelinePage: PipelinePage = {
      rows: [{
        candidateId: 'cand1', name: 'Dana Okafor', stage: 'Technical', slaState: 'GREEN',
        schedulingStatus: 'NO_LINK_SENT', requisitionId: null, requisitionTitle: null, lastActivityAt: null
      }],
      page: 0, size: 1000, totalInScope: 1, filteredCount: 1, truncated: false
    };
    const templateList: TemplateList = {
      templates: [{
        id: 'tmpl1', name: 'Onsite loop', status: 'ACTIVE', durationMinutes: 60, slotCadenceMinutes: 30,
        bufferBeforeMinutes: 0, bufferAfterMinutes: 0, dailyCapPerInterviewer: 3, requiredMemberIds: [],
        optionalMemberIds: [], pools: []
      }]
    };

    it('loads candidate + template options from the pipeline and template list services and renders two pickers', () => {
      const fixture = setupFixture({ list: () => of(pipelinePage) }, { list: () => of(templateList) });
      fixture.detectChanges();
      expect(fixture.componentInstance.candidateOpts()).toEqual([{ id: 'cand1', label: 'Dana Okafor', hint: 'Technical' }]);
      expect(fixture.componentInstance.templateOpts()).toEqual([{ id: 'tmpl1', label: 'Onsite loop' }]);
      const pickers = fixture.nativeElement.querySelectorAll('app-search-picker');
      expect(pickers.length).toBe(2);
    });

    it('selecting a candidate option sets candidateId; selecting a template option sets templateId', () => {
      const fixture = setupFixture({ list: () => of(pipelinePage) }, { list: () => of(templateList) });
      fixture.detectChanges();
      const pickers = fixture.debugElement.queryAll(By.directive(SearchPickerComponent));
      expect(pickers.length).toBe(2);
      (pickers[0].componentInstance as SearchPickerComponent).valueChange.emit('cand1');
      (pickers[1].componentInstance as SearchPickerComponent).valueChange.emit('tmpl1');
      expect(fixture.componentInstance.candidateId).toBe('cand1');
      expect(fixture.componentInstance.templateId).toBe('tmpl1');
    });
  });
});
