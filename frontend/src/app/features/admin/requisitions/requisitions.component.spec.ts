import { TestBed, ComponentFixture } from '@angular/core/testing';
import { of } from 'rxjs';
import { RequisitionsComponent } from './requisitions.component';
import { RequisitionsService, RequisitionDto } from './requisitions.service';
import { attachToBody, axeViolations, detachFromBody } from '../../../../testing/axe';

function req(id: string, status: 'OPEN' | 'CLOSED' = 'OPEN'): RequisitionDto {
  return { id, title: 'Backend ' + id, status, externalLabel: null, createdAt: '2026-06-18T00:00:00Z' };
}

describe('RequisitionsComponent', () => {
  let svc: { list: jasmine.Spy; create: jasmine.Spy; update: jasmine.Spy; assignHm: jasmine.Spy; linkCandidate: jasmine.Spy };
  let attachedEls: HTMLElement[] = [];

  function setup(listResult = of([req('r1')])): ComponentFixture<RequisitionsComponent> {
    TestBed.resetTestingModule();
    svc = {
      list: jasmine.createSpy('list').and.returnValue(listResult),
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

  it('renders the shared page-header masthead', () => {
    const fixture = setup();
    expect(fixture.nativeElement.querySelector('app-page-header .page__head h1')).not.toBeNull();
  });

  it('wraps the table in the shared table-scroll region', () => {
    const fixture = setup();
    expect(fixture.nativeElement.querySelector('app-table-scroll table.table')).not.toBeNull();
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
});
