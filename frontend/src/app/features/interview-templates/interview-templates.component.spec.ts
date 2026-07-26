import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { InterviewTemplatesComponent } from './interview-templates.component';
import {
  InterviewTemplatePreset,
  InterviewTemplatesService,
  SlotComputationResponse,
  TemplateList,
  TemplateResponse
} from './interview-templates.service';
import { EmailTemplate, EmailTemplatesService } from '../email-templates/email-templates.service';
import { ConfirmDialogService } from '../../shared/ui/confirm-dialog.service';
import { ToastService } from '../../shared/ui/toast.service';
import { attachToBody, axeViolations, detachFromBody } from '../../../testing/axe';

/**
 * F12: the interview-templates component lists templates, renders the create/edit form, and renders the
 * slot-preview states (slots present, empty, and the unschedulable panel). The server is the security
 * boundary; the route guard is defense-in-depth.
 *
 * Phase 3b (workbench overhaul): `retire` is gated behind `ConfirmDialogService.confirm()`.
 * `submit` (create/edit) and `retire` outcomes are surfaced via `ToastService`; the old shared `error`
 * signal's action-outcome usage (submit/retire/preview failures) is routed through `toast.error` and
 * the signal is removed.
 */
describe('InterviewTemplatesComponent', () => {
  const template: TemplateResponse = {
    id: 't1', name: 'Phone Screen', status: 'ACTIVE', durationMinutes: 45, slotCadenceMinutes: 15,
    bufferBeforeMinutes: 15, bufferAfterMinutes: 15, dailyCapPerInterviewer: 2, requiredMemberIds: ['m1'],
    optionalMemberIds: [], pools: []
  };
  let attachedEls: HTMLElement[] = [];

  function setup(list: TemplateList, overrides: Partial<InterviewTemplatesService> = {}) {
    const service: Partial<InterviewTemplatesService> = {
      list: () => of(list),
      create: () => of(template),
      update: () => of(template),
      retire: () => of({ ...template, status: 'RETIRED' }),
      computeSlots: () => of({ slots: [], windowClamped: false, unschedulable: [] }),
      presets: () => of({ presets: [] as InterviewTemplatePreset[] }),
      ...overrides
    };
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [InterviewTemplatesComponent],
      providers: [
        { provide: InterviewTemplatesService, useValue: service },
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting()
      ]
    });
    const fixture = TestBed.createComponent(InterviewTemplatesComponent);
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

  it('renders the empty state when there are no templates', () => {
    const fixture = setup({ templates: [] });
    expect(fixture.nativeElement.textContent).toContain('No templates yet');
    expect(fixture.nativeElement.querySelector('app-empty-state')).not.toBeNull();
  });

  it('renders the shared page-header masthead', () => {
    const fixture = setup({ templates: [template] });
    expect(fixture.nativeElement.querySelector('app-page-header .page__head h1')).not.toBeNull();
  });

  it('has zero axe WCAG 2.2 AA violations', async () => {
    const fixture = setup({ templates: [template] });
    const violations = await axeViolations(fixture.nativeElement);
    expect(violations).withContext(violations.map((v) => v.id).join(', ')).toEqual([]);
  });

  it('lists templates with their meta', () => {
    const fixture = setup({ templates: [template] });
    expect(fixture.nativeElement.textContent).toContain('Phone Screen');
    expect(fixture.nativeElement.textContent).toContain('45 min');
  });

  it('loads a template into the edit form when Edit is clicked', () => {
    const fixture = setup({ templates: [template] });
    const component = fixture.componentInstance;
    component.edit(template);
    fixture.detectChanges();
    expect(component.editingId()).toBe('t1');
    expect(component.name).toBe('Phone Screen');
    expect(fixture.nativeElement.textContent).toContain('Edit template');
  });

  it('renders computed slots after preview', () => {
    const result: SlotComputationResponse = {
      slots: [{ start: '2026-06-15T08:00:00Z', end: '2026-06-15T08:45:00Z', zoneId: 'UTC', requiredMemberIds: ['m1'], qualifyingByPool: {} }],
      windowClamped: false,
      unschedulable: []
    };
    const fixture = setup({ templates: [template] }, { computeSlots: () => of(result) });
    fixture.componentInstance.preview(template);
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Computed slots');
    expect(fixture.nativeElement.textContent).toContain('2026-06-15T08:00:00Z');
  });

  it('renders the unschedulable panel when a member cannot be scheduled', () => {
    const result: SlotComputationResponse = {
      slots: [],
      windowClamped: false,
      unschedulable: [{ memberId: 'm1', reason: 'NOT_CONNECTED' }]
    };
    const fixture = setup({ templates: [template] }, { computeSlots: () => of(result) });
    fixture.componentInstance.preview(template);
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('No compliant slots');
    expect(fixture.nativeElement.textContent).toContain('NOT_CONNECTED');
  });

  it('toasts an error when the slot preview fails', () => {
    const fixture = setup({ templates: [template] }, { computeSlots: () => throwError(() => new Error('500')) });
    const toastSpy = spyOn(TestBed.inject(ToastService), 'error');
    fixture.componentInstance.preview(template);
    expect(toastSpy).toHaveBeenCalled();
  });

  // ---- Phase 3b: submit (create/edit) toasts ----

  describe('submit (toast; not gated)', () => {
    it('creates a template and toasts success', () => {
      const fixture = setup({ templates: [] });
      const toastSpy = spyOn(TestBed.inject(ToastService), 'success');
      fixture.componentInstance.name = 'New template';
      fixture.componentInstance.submit();
      expect(toastSpy).toHaveBeenCalled();
    });

    it('saves an edit and toasts success', () => {
      const fixture = setup({ templates: [template] });
      fixture.componentInstance.edit(template);
      const toastSpy = spyOn(TestBed.inject(ToastService), 'success');
      fixture.componentInstance.submit();
      expect(toastSpy).toHaveBeenCalled();
    });

    it('toasts an error when save fails', () => {
      const fixture = setup({ templates: [] }, { create: () => throwError(() => new Error('400')) });
      const toastSpy = spyOn(TestBed.inject(ToastService), 'error');
      fixture.componentInstance.submit();
      fixture.detectChanges();
      expect(toastSpy).toHaveBeenCalled();
    });
  });

  // ---- Phase 3b: retire (confirm-gate + toast) ----

  describe('retire (confirm-gate + toast)', () => {
    it('does not retire when the confirm is declined', async () => {
      const retireSpy = jasmine.createSpy('retire').and.returnValue(of({ ...template, status: 'RETIRED' }));
      const fixture = setup({ templates: [template] }, { retire: retireSpy as unknown as InterviewTemplatesService['retire'] });
      spyOn(TestBed.inject(ConfirmDialogService), 'confirm').and.resolveTo(false);
      await fixture.componentInstance.retire(template);
      expect(retireSpy).not.toHaveBeenCalled();
    });

    it('gates, retires, and toasts success when confirmed', async () => {
      const retireSpy = jasmine.createSpy('retire').and.returnValue(of({ ...template, status: 'RETIRED' }));
      const fixture = setup({ templates: [template] }, { retire: retireSpy as unknown as InterviewTemplatesService['retire'] });
      const confirmSpy = spyOn(TestBed.inject(ConfirmDialogService), 'confirm').and.resolveTo(true);
      const toastSpy = spyOn(TestBed.inject(ToastService), 'success');
      await fixture.componentInstance.retire(template);
      expect(confirmSpy).toHaveBeenCalledWith(jasmine.objectContaining({
        title: jasmine.stringContaining('Retire'),
        body: jasmine.stringContaining('Phone Screen')
      }));
      expect(retireSpy).toHaveBeenCalledWith('t1');
      expect(toastSpy).toHaveBeenCalled();
    });

    it('toasts an error when the confirmed retirement fails', async () => {
      const retireSpy = jasmine.createSpy('retire').and.returnValue(throwError(() => ({ status: 500 })));
      const fixture = setup({ templates: [template] }, { retire: retireSpy as unknown as InterviewTemplatesService['retire'] });
      spyOn(TestBed.inject(ConfirmDialogService), 'confirm').and.resolveTo(true);
      const toastSpy = spyOn(TestBed.inject(ToastService), 'error');
      await fixture.componentInstance.retire(template);
      expect(toastSpy).toHaveBeenCalled();
    });
  });

  it('no longer exposes a generic error signal (routed through toasts)', () => {
    const fixture = setup({ templates: [] });
    expect((fixture.componentInstance as unknown as { error?: unknown }).error).toBeUndefined();
  });

  describe('pools and optional members (preset groundwork)', () => {
    it('adds and removes pool rows', () => {
      const fixture = setup({ templates: [] });
      const c = fixture.componentInstance;
      expect(c.pools.length).toBe(0);
      c.addPool();
      expect(c.pools).toEqual([{ membersCsv: '', n: 1 }]);
      c.removePool(0);
      expect(c.pools.length).toBe(0);
    });

    it('submits optional members and pools parsed from CSV rows', () => {
      const createSpy = jasmine.createSpy('create').and.returnValue(of(template));
      const fixture = setup({ templates: [] },
        { create: createSpy as unknown as InterviewTemplatesService['create'] });
      const c = fixture.componentInstance;
      c.name = 'Panel loop';
      c.durationMinutes = 90;
      c.requiredCsv = 'm1';
      c.optionalCsv = 'm2, m3';
      c.pools = [{ membersCsv: 'm4, m5', n: 2 }, { membersCsv: '  ', n: 1 }];
      c.submit();
      expect(createSpy).toHaveBeenCalledWith(jasmine.objectContaining({
        optionalMemberIds: ['m2', 'm3'],
        pools: [{ memberIds: ['m4', 'm5'], n: 2 }]
      }));
    });

    it('edit() populates optional and pool CSV rows from the response', () => {
      const withPools = {
        ...template, optionalMemberIds: ['m9'],
        pools: [{ memberIds: ['m4', 'm5'], n: 2 }]
      };
      const fixture = setup({ templates: [withPools] });
      const c = fixture.componentInstance;
      c.edit(withPools);
      expect(c.optionalCsv).toBe('m9');
      expect(c.pools).toEqual([{ membersCsv: 'm4, m5', n: 2 }]);
    });
  });

  describe('preset gallery', () => {
    const panelLoop: InterviewTemplatePreset = {
      key: 'PANEL_LOOP', durationMinutes: 90, slotCadenceMinutes: 30, bufferBeforeMinutes: 15,
      bufferAfterMinutes: 15, dailyCapPerInterviewer: 1, requiredCount: 1, optionalShadow: false,
      poolN: 2, starterEmailTypes: ['INVITATION', 'CONFIRMATION', 'REMINDER_24H']
    };

    it('renders a card per preset with a localized name', () => {
      const fixture = setup({ templates: [] },
        { presets: () => of({ presets: [panelLoop] }) });
      const card = fixture.nativeElement.querySelector('.preset-card');
      expect(card).not.toBeNull();
      expect(card.textContent).toContain('Panel');
    });

    it('applying a preset pre-fills the form, seeds a pool row, and sets the banner', () => {
      const fixture = setup({ templates: [] },
        { presets: () => of({ presets: [panelLoop] }) });
      const c = fixture.componentInstance;
      c.applyPreset(panelLoop);
      expect(c.durationMinutes).toBe(90);
      expect(c.slotCadenceMinutes).toBe(30);
      expect(c.bufferBeforeMinutes).toBe(15);
      expect(c.dailyCapPerInterviewer).toBe(1);
      expect(c.pools).toEqual([{ membersCsv: '', n: 2 }]);
      expect(c.activePresetKey()).toBe('PANEL_LOOP');
      expect(c.name.length).toBeGreaterThan(0);
      fixture.detectChanges();
      expect(fixture.nativeElement.querySelector('.preset-banner')).not.toBeNull();
    });

    it('gallery load failure is non-blocking and offers a retry', () => {
      const fixture = setup({ templates: [] },
        { presets: () => throwError(() => new Error('down')) });
      const c = fixture.componentInstance;
      expect(c.presetsFailed()).toBeTrue();
      expect(fixture.nativeElement.querySelector('form')).not.toBeNull(); // blank create still works
      const retryBtn = fixture.nativeElement.querySelector('.presets button.btn--link');
      expect(retryBtn).not.toBeNull();
      expect(retryBtn.textContent).toContain('Try again');
    });

    it('has zero axe violations with the gallery rendered', async () => {
      const fixture = setup({ templates: [] },
        { presets: () => of({ presets: [panelLoop] }) });
      const violations = await axeViolations(fixture.nativeElement);
      expect(violations).withContext(violations.map((v) => v.id).join(', ')).toEqual([]);
    });
  });

  describe('edit() clears stale preset state (banner leak fix)', () => {
    const techDeepDive: InterviewTemplatePreset = {
      key: 'TECH_DEEP_DIVE', durationMinutes: 60, slotCadenceMinutes: 30, bufferBeforeMinutes: 10,
      bufferAfterMinutes: 10, dailyCapPerInterviewer: 2, requiredCount: 1, optionalShadow: true,
      poolN: null, starterEmailTypes: ['INVITATION', 'CONFIRMATION', 'REMINDER_24H']
    };

    it('clears activePresetKey (and hides the banner) when Edit is clicked after applying a preset', () => {
      const fixture = setup({ templates: [template] },
        { presets: () => of({ presets: [techDeepDive] }) });
      const c = fixture.componentInstance;
      c.applyPreset(techDeepDive);
      expect(c.activePresetKey()).toBe('TECH_DEEP_DIVE');
      c.edit(template);
      expect(c.activePresetKey()).toBeNull();
      fixture.detectChanges();
      expect(fixture.nativeElement.querySelector('.preset-banner')).toBeNull();
    });

    it('an edit-path submit() never opens the starter dialog, even if a preset was applied beforehand', () => {
      const fixture = setup({ templates: [template] },
        { presets: () => of({ presets: [techDeepDive] }) });
      const c = fixture.componentInstance;
      c.applyPreset(techDeepDive);
      c.edit(template);
      c.requiredCsv = 'm1';
      c.submit();
      fixture.detectChanges();
      expect(c.starterPrompt()).toBeNull();
    });
  });

  describe('post-save starter-email dialog', () => {
    const techDeepDive: InterviewTemplatePreset = {
      key: 'TECH_DEEP_DIVE', durationMinutes: 60, slotCadenceMinutes: 30, bufferBeforeMinutes: 10,
      bufferAfterMinutes: 10, dailyCapPerInterviewer: 2, requiredCount: 1, optionalShadow: true,
      poolN: null, starterEmailTypes: ['INVITATION', 'CONFIRMATION', 'REMINDER_24H']
    };
    const starterResponse = { messageType: 'INVITATION', stageKey: 't1', subject: 's', body: 'b',
      locked: false, version: 0, source: 'OVERRIDE', permittedTokens: [] } as EmailTemplate;

    function saveFromPreset(fixture: ReturnType<typeof setup>): void {
      const c = fixture.componentInstance;
      c.applyPreset(techDeepDive);
      c.requiredCsv = 'm1';
      c.submit();
      fixture.detectChanges();
    }

    it('opens the dialog after saving from a preset, listing the starter types checked', () => {
      const fixture = setup({ templates: [] },
        { presets: () => of({ presets: [techDeepDive] }) });
      saveFromPreset(fixture);
      const prompt = fixture.componentInstance.starterPrompt();
      expect(prompt).not.toBeNull();
      expect(prompt!.templateId).toBe('t1');
      expect(prompt!.rows.map((r) => r.type)).toEqual(['INVITATION', 'CONFIRMATION', 'REMINDER_24H']);
      expect(prompt!.rows.every((r) => r.checked)).toBeTrue();
      expect(fixture.nativeElement.querySelector('.ps-panel')).not.toBeNull();
    });

    it('does not open the dialog after a blank (non-preset) save', () => {
      const fixture = setup({ templates: [] });
      const c = fixture.componentInstance;
      c.name = 'Blank';
      c.requiredCsv = 'm1';
      c.submit();
      expect(c.starterPrompt()).toBeNull();
    });

    it('applies one starter per checked type with expectedVersion null', () => {
      const fixture = setup({ templates: [] },
        { presets: () => of({ presets: [techDeepDive] }) });
      const applySpy = spyOn(TestBed.inject(EmailTemplatesService), 'applyPresetStarter')
        .and.returnValue(of(starterResponse));
      saveFromPreset(fixture);
      fixture.componentInstance.toggleStarterRow('CONFIRMATION'); // uncheck one
      fixture.componentInstance.applyStarters();
      expect(applySpy).toHaveBeenCalledTimes(2);
      expect(applySpy).toHaveBeenCalledWith('INVITATION',
        { stageKey: 't1', presetKey: 'TECH_DEEP_DIVE', expectedVersion: null });
      expect(fixture.componentInstance.starterPrompt()!.rows
        .find((r) => r.type === 'INVITATION')!.status).toBe('done');
    });

    it('a failed apply marks the row failed and retry re-applies it', () => {
      const fixture = setup({ templates: [] },
        { presets: () => of({ presets: [techDeepDive] }) });
      const applySpy = spyOn(TestBed.inject(EmailTemplatesService), 'applyPresetStarter')
        .and.returnValue(throwError(() => new Error('boom')));
      saveFromPreset(fixture);
      const c = fixture.componentInstance;
      c.applyStarterRow(c.starterPrompt()!, 'INVITATION');
      expect(c.starterPrompt()!.rows.find((r) => r.type === 'INVITATION')!.status).toBe('failed');
      applySpy.and.returnValue(of(starterResponse));
      c.applyStarterRow(c.starterPrompt()!, 'INVITATION');
      expect(c.starterPrompt()!.rows.find((r) => r.type === 'INVITATION')!.status).toBe('done');
    });

    it('skip closes the dialog without calling the email service', () => {
      const fixture = setup({ templates: [] },
        { presets: () => of({ presets: [techDeepDive] }) });
      const applySpy = spyOn(TestBed.inject(EmailTemplatesService), 'applyPresetStarter');
      saveFromPreset(fixture);
      fixture.componentInstance.closeStarterPrompt();
      expect(fixture.componentInstance.starterPrompt()).toBeNull();
      expect(applySpy).not.toHaveBeenCalled();
    });

    it('has zero axe violations with the dialog open', async () => {
      const fixture = setup({ templates: [] },
        { presets: () => of({ presets: [techDeepDive] }) });
      saveFromPreset(fixture);
      const violations = await axeViolations(fixture.nativeElement);
      expect(violations).withContext(violations.map((v) => v.id).join(', ')).toEqual([]);
    });
  });
});
