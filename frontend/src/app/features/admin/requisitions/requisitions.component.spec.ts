import { TestBed, ComponentFixture } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { of, throwError } from 'rxjs';
import { RequisitionsComponent } from './requisitions.component';
import { RequisitionsService, RequisitionDto } from './requisitions.service';
import { ConfirmDialogService } from '../../../shared/ui/confirm-dialog.service';
import { ToastService } from '../../../shared/ui/toast.service';
import { SearchPickerComponent } from '../../../shared/ui/search-picker.component';
import { MemberRow, MembersService } from '../members/members.service';
import { PipelinePage, PipelineService } from '../../pipeline/pipeline.service';
import { attachToBody, axeViolations, detachFromBody } from '../../../../testing/axe';

function req(id: string, status: 'OPEN' | 'CLOSED' = 'OPEN'): RequisitionDto {
  return { id, title: 'Backend ' + id, status, externalLabel: null, createdAt: '2026-06-18T00:00:00Z' };
}

/**
 * F51 requisition management (Admin internal screen).
 *
 * Phase 3b (workbench overhaul): `close(r)` is gated behind the shared `ConfirmDialogService`;
 * `reopen` is intentionally left ungated. create/close/reopen/assign/link each surface a per-action
 * toast; the boolean `error` signal now covers only the initial list-load failure.
 */
describe('RequisitionsComponent', () => {
  let svc: { list: jasmine.Spy; create: jasmine.Spy; update: jasmine.Spy; assignHm: jasmine.Spy; linkCandidate: jasmine.Spy };
  let attachedEls: HTMLElement[] = [];

  // Workbench overhaul phase 5: ngOnInit unconditionally loads member + candidate picker options, so
  // every render needs DI stubs for MembersService/PipelineService.
  const emptyPipelinePage: PipelinePage = { rows: [], page: 0, size: 1000, totalInScope: 0, filteredCount: 0, truncated: false };

  function setup(listResult = of([req('r1')]), membersResult: MemberRow[] = [], pipelineResult = emptyPipelinePage): ComponentFixture<RequisitionsComponent> {
    TestBed.resetTestingModule();
    svc = {
      list: jasmine.createSpy('list').and.returnValue(listResult),
      create: jasmine.createSpy('create').and.returnValue(of(req('r2'))),
      update: jasmine.createSpy('update').and.returnValue(of(req('r1', 'CLOSED'))),
      assignHm: jasmine.createSpy('assignHm').and.returnValue(of(void 0)),
      linkCandidate: jasmine.createSpy('linkCandidate').and.returnValue(of(void 0))
    };
    const membersStub: Partial<MembersService> = { getMembers: () => of(membersResult) };
    const pipelineStub: Partial<PipelineService> = { list: () => of(pipelineResult) };
    TestBed.configureTestingModule({
      imports: [RequisitionsComponent],
      providers: [
        { provide: RequisitionsService, useValue: svc },
        { provide: MembersService, useValue: membersStub },
        { provide: PipelineService, useValue: pipelineStub }
      ]
    });
    const fixture = TestBed.createComponent(RequisitionsComponent);
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

  it('lists requisitions on load', () => {
    const fixture = setup();
    expect(svc.list).toHaveBeenCalled();
    expect(fixture.nativeElement.textContent).toContain('Backend r1');
  });

  it('creates a requisition and toasts success', () => {
    const fixture = setup();
    const toastSpy = spyOn(TestBed.inject(ToastService), 'success');
    fixture.componentInstance.newTitle = 'Frontend Eng';
    fixture.componentInstance.create();
    expect(svc.create).toHaveBeenCalledWith('Frontend Eng', undefined);
    expect(toastSpy).toHaveBeenCalled();
  });

  it('toasts an error when create fails', () => {
    const fixture = setup();
    svc.create.and.returnValue(throwError(() => ({ status: 500 })));
    const toastSpy = spyOn(TestBed.inject(ToastService), 'error');
    fixture.componentInstance.newTitle = 'Frontend Eng';
    fixture.componentInstance.create();
    expect(toastSpy).toHaveBeenCalled();
  });

  it('links a candidate to a requisition and toasts success', () => {
    const fixture = setup();
    const toastSpy = spyOn(TestBed.inject(ToastService), 'success');
    fixture.componentInstance.linkCandidateId = 'c1';
    fixture.componentInstance.linkRequisitionId = 'r1';
    fixture.componentInstance.link();
    expect(svc.linkCandidate).toHaveBeenCalledWith('c1', 'r1');
    expect(toastSpy).toHaveBeenCalled();
  });

  it('toasts an error when linking fails', () => {
    const fixture = setup();
    svc.linkCandidate.and.returnValue(throwError(() => ({ status: 500 })));
    const toastSpy = spyOn(TestBed.inject(ToastService), 'error');
    fixture.componentInstance.linkCandidateId = 'c1';
    fixture.componentInstance.link();
    expect(toastSpy).toHaveBeenCalled();
  });

  it('assigns a hiring manager and toasts success', () => {
    const fixture = setup();
    const toastSpy = spyOn(TestBed.inject(ToastService), 'success');
    fixture.componentInstance.assignMemberId['r1'] = 'm1';
    fixture.componentInstance.assign(req('r1'));
    expect(svc.assignHm).toHaveBeenCalledWith('r1', 'm1');
    expect(toastSpy).toHaveBeenCalled();
  });

  it('toasts an error when assigning fails', () => {
    const fixture = setup();
    svc.assignHm.and.returnValue(throwError(() => ({ status: 500 })));
    const toastSpy = spyOn(TestBed.inject(ToastService), 'error');
    fixture.componentInstance.assignMemberId['r1'] = 'm1';
    fixture.componentInstance.assign(req('r1'));
    expect(toastSpy).toHaveBeenCalled();
  });

  describe('close (confirm-gate + toast)', () => {
    it('does not close when the confirm is declined', async () => {
      const fixture = setup();
      spyOn(TestBed.inject(ConfirmDialogService), 'confirm').and.resolveTo(false);
      await fixture.componentInstance.close(req('r1'));
      expect(svc.update).not.toHaveBeenCalled();
    });

    it('gates, closes, and toasts success when confirmed', async () => {
      const fixture = setup();
      const confirmSpy = spyOn(TestBed.inject(ConfirmDialogService), 'confirm').and.resolveTo(true);
      const toastSpy = spyOn(TestBed.inject(ToastService), 'success');
      await fixture.componentInstance.close(req('r1'));
      expect(confirmSpy).toHaveBeenCalled();
      expect(svc.update).toHaveBeenCalledWith('r1', { status: 'CLOSED' });
      expect(toastSpy).toHaveBeenCalled();
    });

    it('toasts an error when the confirmed close fails', async () => {
      const fixture = setup();
      svc.update.and.returnValue(throwError(() => ({ status: 500 })));
      spyOn(TestBed.inject(ConfirmDialogService), 'confirm').and.resolveTo(true);
      const toastSpy = spyOn(TestBed.inject(ToastService), 'error');
      await fixture.componentInstance.close(req('r1'));
      expect(toastSpy).toHaveBeenCalled();
    });
  });

  describe('reopen (ungated + toast)', () => {
    it('reopens without a confirm dialog and toasts success', () => {
      const fixture = setup();
      const confirmSpy = spyOn(TestBed.inject(ConfirmDialogService), 'confirm');
      const toastSpy = spyOn(TestBed.inject(ToastService), 'success');
      fixture.componentInstance.reopen(req('r1', 'CLOSED'));
      expect(confirmSpy).not.toHaveBeenCalled();
      expect(svc.update).toHaveBeenCalledWith('r1', { status: 'OPEN' });
      expect(toastSpy).toHaveBeenCalled();
    });

    it('toasts an error when reopen fails', () => {
      const fixture = setup();
      svc.update.and.returnValue(throwError(() => ({ status: 500 })));
      const toastSpy = spyOn(TestBed.inject(ToastService), 'error');
      fixture.componentInstance.reopen(req('r1', 'CLOSED'));
      expect(toastSpy).toHaveBeenCalled();
    });
  });

  it('renders the shared page-header masthead', () => {
    const fixture = setup();
    expect(fixture.nativeElement.querySelector('app-page-header .page__head h1')).not.toBeNull();
  });

  it('wraps the table in the shared table-scroll region', () => {
    const fixture = setup();
    expect(fixture.nativeElement.querySelector('app-table-scroll table.table')).not.toBeNull();
  });

  it('renders a responsive card-fallback table (table--stack + per-cell data-label)', () => {
    const fixture = setup();
    const table = fixture.nativeElement.querySelector('table.table');
    expect(table?.classList.contains('table--stack')).toBe(true);
    const td = fixture.nativeElement.querySelector('tbody td') as HTMLElement | null;
    expect(td?.getAttribute('data-label')).toBeTruthy();
  });

  it('shows the guided empty-state when there are no requisitions', () => {
    const fixture = setup(of([]));
    expect(fixture.nativeElement.querySelector('app-empty-state')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('table.table')).toBeNull();
  });

  it('has zero axe WCAG 2.2 AA violations', async () => {
    const fixture = setup();
    const violations = await axeViolations(fixture.nativeElement);
    expect(violations).withContext(violations.map((v) => v.id).join(', ')).toEqual([]);
  });

  // ---- Workbench overhaul phase 5: member/candidate/requisition pickers ----

  describe('member/candidate/requisition pickers (workbench overhaul phase 5)', () => {
    const member: MemberRow = { memberId: 'm1', displayName: 'Jordan Blake', role: 'HIRING_MANAGER', status: 'ACTIVE' };
    const pipelinePage: PipelinePage = {
      rows: [{
        candidateId: 'c1', name: 'Dana Okafor', stage: 'Technical', slaState: 'GREEN',
        schedulingStatus: 'NO_LINK_SENT', requisitionId: null, requisitionTitle: null, lastActivityAt: null
      }],
      page: 0, size: 1000, totalInScope: 1, filteredCount: 1, truncated: false
    };

    it('renders a per-row member picker, and candidate + requisition pickers in the link section', () => {
      const fixture = setup(of([req('r1')]), [member], pipelinePage);
      const pickers = fixture.nativeElement.querySelectorAll('app-search-picker');
      // 1 per-row member picker + 1 candidate picker + 1 requisition picker.
      expect(pickers.length).toBe(3);
      expect(fixture.componentInstance.memberOpts()).toEqual([{ id: 'm1', label: 'Jordan Blake', hint: 'HIRING_MANAGER' }]);
      expect(fixture.componentInstance.candidateOpts()).toEqual([{ id: 'c1', label: 'Dana Okafor', hint: 'Technical' }]);
    });

    it('the requisition picker options come from the already-loaded requisitions() signal, no new fetch', () => {
      const fixture = setup(of([req('r1')]));
      expect(fixture.componentInstance.requisitionOpts()).toEqual([{ id: 'r1', label: 'Backend r1', hint: 'OPEN' }]);
    });

    it('selecting a member option sets the per-row assignMemberId', () => {
      const fixture = setup(of([req('r1')]), [member], pipelinePage);
      const pickers = fixture.debugElement.queryAll(By.directive(SearchPickerComponent));
      (pickers[0].componentInstance as SearchPickerComponent).valueChange.emit('m1');
      expect(fixture.componentInstance.assignMemberId['r1']).toBe('m1');
    });

    it('selecting a candidate option sets linkCandidateId; selecting a requisition option sets linkRequisitionId', () => {
      const fixture = setup(of([req('r1')]), [member], pipelinePage);
      const pickers = fixture.debugElement.queryAll(By.directive(SearchPickerComponent));
      // Order: [0] per-row member picker, [1] candidate picker, [2] requisition picker.
      (pickers[1].componentInstance as SearchPickerComponent).valueChange.emit('c1');
      (pickers[2].componentInstance as SearchPickerComponent).valueChange.emit('r1');
      expect(fixture.componentInstance.linkCandidateId).toBe('c1');
      expect(fixture.componentInstance.linkRequisitionId).toBe('r1');
    });
  });
});
