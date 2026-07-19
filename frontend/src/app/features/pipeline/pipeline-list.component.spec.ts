import { TestBed, ComponentFixture } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { provideRouter } from '@angular/router';
import { PipelineListComponent } from './pipeline-list.component';
import { PipelineService, PipelinePage } from './pipeline.service';
import { AuthService } from '../../core/auth/auth.service';
import { ToastService } from '../../shared/ui/toast.service';
import { attachToBody, axeViolations, detachFromBody } from '../../../testing/axe';

function page(): PipelinePage {
  return {
    rows: [
      { candidateId: 'c1', name: 'Ada', stage: 'Screening', slaState: 'RED', schedulingStatus: 'LINK_SENT',
        requisitionId: 'r1', requisitionTitle: 'Backend', lastActivityAt: '2026-06-10T00:00:00Z' },
      { candidateId: 'c2', name: 'Bea', stage: 'Onsite', slaState: 'GREEN', schedulingStatus: 'CONFIRMED',
        requisitionId: null, requisitionTitle: null, lastActivityAt: '2026-06-18T00:00:00Z' }
    ],
    page: 0, size: 50, totalInScope: 2, filteredCount: 2, truncated: false
  };
}

function emptyPage(): PipelinePage {
  return { rows: [], page: 0, size: 50, totalInScope: 0, filteredCount: 0, truncated: false };
}

describe('PipelineListComponent', () => {
  let pipeline: { list: jasmine.Spy; bulk: jasmine.Spy; timeline: jasmine.Spy };
  let auth: { me: jasmine.Spy };
  let attachedEls: HTMLElement[] = [];

  function setup(role = 'RECRUITER', listResult = of(page())): ComponentFixture<PipelineListComponent> {
    TestBed.resetTestingModule();
    pipeline = {
      list: jasmine.createSpy('list').and.returnValue(listResult),
      bulk: jasmine.createSpy('bulk').and.returnValue(of({ results: [] })),
      timeline: jasmine.createSpy('timeline')
    };
    auth = { me: jasmine.createSpy('me').and.returnValue(of({ role })) };
    TestBed.configureTestingModule({
      imports: [PipelineListComponent],
      providers: [
        { provide: PipelineService, useValue: pipeline },
        { provide: AuthService, useValue: auth },
        provideRouter([])
      ]
    });
    const fixture = TestBed.createComponent(PipelineListComponent);
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

  it('loads and renders the pipeline rows', () => {
    const fixture = setup();
    expect(pipeline.list).toHaveBeenCalled();
    expect(fixture.nativeElement.textContent).toContain('Ada');
    expect(fixture.nativeElement.textContent).toContain('Bea');
  });

  it('shows bulk controls for a Recruiter', () => {
    const fixture = setup('RECRUITER');
    expect(fixture.nativeElement.querySelector('.bulk')).not.toBeNull();
  });

  it('hides bulk controls for Read-only and Hiring Manager', () => {
    expect(setup('READ_ONLY').nativeElement.querySelector('.bulk')).toBeNull();
    expect(setup('HIRING_MANAGER').nativeElement.querySelector('.bulk')).toBeNull();
  });

  it('sends a bulk update email for the selected candidates', () => {
    const fixture = setup('RECRUITER');
    const cmp = fixture.componentInstance;
    cmp.toggle('c1');
    cmp.sendUpdateEmail();
    expect(pipeline.bulk).toHaveBeenCalledWith('SEND_UPDATE_EMAIL', ['c1']);
  });

  describe('bulk-action summary toast', () => {
    it('toasts a success summary when every candidate sends', () => {
      const fixture = setup('RECRUITER');
      pipeline.bulk.and.returnValue(of({
        results: [
          { candidateId: 'c1', outcome: 'SENT', reason: null },
          { candidateId: 'c2', outcome: 'ENQUEUED', reason: null }
        ]
      }));
      const toastSpy = spyOn(TestBed.inject(ToastService), 'success');
      const errorToastSpy = spyOn(TestBed.inject(ToastService), 'error');
      fixture.componentInstance.toggle('c1');
      fixture.componentInstance.toggle('c2');
      fixture.componentInstance.sendUpdateEmail();
      expect(toastSpy).toHaveBeenCalled();
      expect(errorToastSpy).not.toHaveBeenCalled();
    });

    it('toasts a mixed summary (n sent, m failed) as an error when some are skipped', () => {
      const fixture = setup('RECRUITER');
      pipeline.bulk.and.returnValue(of({
        results: [
          { candidateId: 'c1', outcome: 'SENT', reason: null },
          { candidateId: 'c2', outcome: 'SKIPPED', reason: 'no email on file' }
        ]
      }));
      const toastSpy = spyOn(TestBed.inject(ToastService), 'error');
      fixture.componentInstance.toggle('c1');
      fixture.componentInstance.toggle('c2');
      fixture.componentInstance.sendUpdateEmail();
      expect(toastSpy).toHaveBeenCalled();
      const message = toastSpy.calls.mostRecent().args[0] as string;
      expect(message).toContain('1');
    });

    it('toasts an error summary when every candidate is skipped', () => {
      const fixture = setup('RECRUITER');
      pipeline.bulk.and.returnValue(of({
        results: [{ candidateId: 'c1', outcome: 'SKIPPED', reason: 'no email on file' }]
      }));
      const toastSpy = spyOn(TestBed.inject(ToastService), 'error');
      fixture.componentInstance.toggle('c1');
      fixture.componentInstance.sendUpdateEmail();
      expect(toastSpy).toHaveBeenCalled();
    });

    it('toasts an error when the bulk request itself fails', () => {
      const fixture = setup('RECRUITER');
      pipeline.bulk.and.returnValue(throwError(() => ({ status: 500 })));
      const toastSpy = spyOn(TestBed.inject(ToastService), 'error');
      fixture.componentInstance.toggle('c1');
      fixture.componentInstance.sendUpdateEmail();
      expect(toastSpy).toHaveBeenCalled();
      expect(fixture.componentInstance.error()).toBeTrue();
    });

    it('keeps rendering the existing per-candidate bulkResults detail list', () => {
      const fixture = setup('RECRUITER');
      pipeline.bulk.and.returnValue(of({
        results: [{ candidateId: 'c1', outcome: 'SENT', reason: null }]
      }));
      fixture.componentInstance.toggle('c1');
      fixture.componentInstance.sendUpdateEmail();
      fixture.detectChanges();
      expect(fixture.componentInstance.bulkResults()).toEqual([{ candidateId: 'c1', outcome: 'SENT', reason: null }]);
      expect(fixture.nativeElement.querySelector('.bulk-results')).not.toBeNull();
    });
  });

  it('re-queries when a filter changes', () => {
    const fixture = setup('RECRUITER');
    pipeline.list.calls.reset();
    fixture.componentInstance.sla = 'RED';
    fixture.componentInstance.applyFilters();
    expect(pipeline.list).toHaveBeenCalledWith(jasmine.objectContaining({ sla: 'RED' }));
  });

  it('renders the shared page-header masthead', () => {
    const fixture = setup();
    expect(fixture.nativeElement.querySelector('app-page-header .page__head h1')).not.toBeNull();
  });

  it('wraps the table in the shared table-scroll region', () => {
    const fixture = setup();
    expect(fixture.nativeElement.querySelector('app-table-scroll table.table')).not.toBeNull();
  });

  it('shows the guided empty-state with an import CTA when there are no matching candidates', () => {
    const fixture = setup('RECRUITER', of(emptyPage()));
    const empty = fixture.nativeElement.querySelector('app-empty-state');
    expect(empty).not.toBeNull();
    const cta = empty!.querySelector('a[routerLink="/admin/csv-import"]') as HTMLAnchorElement | null;
    expect(cta).not.toBeNull();
    expect(fixture.nativeElement.querySelector('table.table')).toBeNull();
  });

  it('has zero axe WCAG 2.2 AA violations', async () => {
    const fixture = setup();
    const violations = await axeViolations(fixture.nativeElement);
    expect(violations).withContext(violations.map((v) => v.id).join(', ')).toEqual([]);
  });
});
