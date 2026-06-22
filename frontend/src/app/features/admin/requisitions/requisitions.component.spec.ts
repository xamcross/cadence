import { TestBed, ComponentFixture } from '@angular/core/testing';
import { of } from 'rxjs';
import { RequisitionsComponent } from './requisitions.component';
import { RequisitionsService, RequisitionDto } from './requisitions.service';

function req(id: string, status: 'OPEN' | 'CLOSED' = 'OPEN'): RequisitionDto {
  return { id, title: 'Backend ' + id, status, externalLabel: null, createdAt: '2026-06-18T00:00:00Z' };
}

describe('RequisitionsComponent', () => {
  let svc: { list: jasmine.Spy; create: jasmine.Spy; update: jasmine.Spy; assignHm: jasmine.Spy; linkCandidate: jasmine.Spy };

  function setup(): ComponentFixture<RequisitionsComponent> {
    TestBed.resetTestingModule();
    svc = {
      list: jasmine.createSpy('list').and.returnValue(of([req('r1')])),
      create: jasmine.createSpy('create').and.returnValue(of(req('r2'))),
      update: jasmine.createSpy('update').and.returnValue(of(req('r1', 'CLOSED'))),
      assignHm: jasmine.createSpy('assignHm').and.returnValue(of(void 0)),
      linkCandidate: jasmine.createSpy('linkCandidate').and.returnValue(of(void 0))
    };
    TestBed.configureTestingModule({
      imports: [RequisitionsComponent],
      providers: [{ provide: RequisitionsService, useValue: svc }]
    });
    const fixture = TestBed.createComponent(RequisitionsComponent);
    fixture.detectChanges();
    return fixture;
  }

  it('lists requisitions on load', () => {
    const fixture = setup();
    expect(svc.list).toHaveBeenCalled();
    expect(fixture.nativeElement.textContent).toContain('Backend r1');
  });

  it('creates a requisition', () => {
    const fixture = setup();
    fixture.componentInstance.newTitle = 'Frontend Eng';
    fixture.componentInstance.create();
    expect(svc.create).toHaveBeenCalledWith('Frontend Eng', undefined);
  });

  it('links a candidate to a requisition', () => {
    const fixture = setup();
    fixture.componentInstance.linkCandidateId = 'c1';
    fixture.componentInstance.linkRequisitionId = 'r1';
    fixture.componentInstance.link();
    expect(svc.linkCandidate).toHaveBeenCalledWith('c1', 'r1');
  });

  it('closes an open requisition', () => {
    const fixture = setup();
    fixture.componentInstance.close(req('r1'));
    expect(svc.update).toHaveBeenCalledWith('r1', { status: 'CLOSED' });
  });
});
