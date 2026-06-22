import { TestBed, ComponentFixture } from '@angular/core/testing';
import { of } from 'rxjs';
import { provideRouter } from '@angular/router';
import { PipelineListComponent } from './pipeline-list.component';
import { PipelineService, PipelinePage } from './pipeline.service';
import { AuthService } from '../../core/auth/auth.service';

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

describe('PipelineListComponent', () => {
  let pipeline: { list: jasmine.Spy; bulk: jasmine.Spy; timeline: jasmine.Spy };
  let auth: { me: jasmine.Spy };

  function setup(role = 'RECRUITER'): ComponentFixture<PipelineListComponent> {
    TestBed.resetTestingModule();
    pipeline = {
      list: jasmine.createSpy('list').and.returnValue(of(page())),
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
    fixture.detectChanges();
    return fixture;
  }

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

  it('re-queries when a filter changes', () => {
    const fixture = setup('RECRUITER');
    pipeline.list.calls.reset();
    fixture.componentInstance.sla = 'RED';
    fixture.componentInstance.applyFilters();
    expect(pipeline.list).toHaveBeenCalledWith(jasmine.objectContaining({ sla: 'RED' }));
  });
});
