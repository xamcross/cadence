import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { InterviewTemplatesComponent } from './interview-templates.component';
import {
  InterviewTemplatesService,
  SlotComputationResponse,
  TemplateList,
  TemplateResponse
} from './interview-templates.service';

/**
 * F12: the interview-templates component lists templates, renders the create/edit form, and renders the
 * slot-preview states (slots present, empty, and the unschedulable panel). The server is the security
 * boundary; the route guard is defense-in-depth.
 */
describe('InterviewTemplatesComponent', () => {
  const template: TemplateResponse = {
    id: 't1', name: 'Phone Screen', status: 'ACTIVE', durationMinutes: 45, slotCadenceMinutes: 15,
    bufferBeforeMinutes: 15, bufferAfterMinutes: 15, dailyCapPerInterviewer: 2, requiredMemberIds: ['m1'], pools: []
  };

  function setup(list: TemplateList, overrides: Partial<InterviewTemplatesService> = {}) {
    const service: Partial<InterviewTemplatesService> = {
      list: () => of(list),
      create: () => of(template),
      update: () => of(template),
      retire: () => of({ ...template, status: 'RETIRED' }),
      computeSlots: () => of({ slots: [], windowClamped: false, unschedulable: [] }),
      ...overrides
    };
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [InterviewTemplatesComponent],
      providers: [{ provide: InterviewTemplatesService, useValue: service }]
    });
    const fixture = TestBed.createComponent(InterviewTemplatesComponent);
    fixture.detectChanges();
    return fixture;
  }

  it('renders the empty state when there are no templates', () => {
    const fixture = setup({ templates: [] });
    expect(fixture.nativeElement.textContent).toContain('No templates yet');
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

  it('shows an error when save fails', () => {
    const fixture = setup({ templates: [] }, { create: () => throwError(() => new Error('400')) });
    fixture.componentInstance.submit();
    fixture.detectChanges();
    const alert = fixture.nativeElement.querySelector('[role="alert"]');
    expect(alert).not.toBeNull();
  });
});
